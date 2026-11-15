package com.matoshree.shopmanager.data.firebase

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PhoneUtils
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirestoreUdhariRepository(
    private val gateway: UdhariFirestoreGateway = DefaultUdhariFirestoreGateway()
) {
    private val TAG = "FirestoreUdhariRepo"

    /**
     * Observes all Udhari transactions in realtime.
     */
    fun getAllTransactions(): Flow<List<UdhariTransaction>> = callbackFlow {
        trySend(emptyList())
        val listener = FirebaseManager.udhariTransactionsCollection()
            .limit(1000)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Udhari transactions snapshot error: ${error.message}")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(UdhariTransactionDto::class.java)?.apply { id = doc.id }?.toDomain()
                    }.sortedByDescending { it.createdAt }
                    trySend(list)
                }
            }
        awaitClose { listener.remove() }
    }.distinctUntilChanged()

    /**
     * Observes Udhari transactions for a specific customer in realtime.
     * Matches by customerId OR customerMobile to ensure rock-solid consistency across local and cloud ID models.
     */
    fun getTransactionsForCustomer(customerId: Long, customerMobile: String? = null): Flow<List<UdhariTransaction>> = callbackFlow {
        trySend(emptyList())
        val cleanMobile = customerMobile?.trim() ?: ""
        val listener = FirebaseManager.udhariTransactionsCollection()
            .limit(500)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Customer Udhari snapshot error: ${error.message}")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { doc ->
                        val dto = doc.toObject(UdhariTransactionDto::class.java) ?: return@mapNotNull null
                        val matchesId = (customerId > 0L && dto.customerId == customerId)
                        val matchesMobile = (cleanMobile.isNotBlank() && dto.customerMobile.isNotBlank() && dto.customerMobile.trim() == cleanMobile)
                        val isTrash = dto.status.equals("TRASH", ignoreCase = true) || dto.isReversed
                        if ((matchesId || matchesMobile) && !isTrash) {
                            dto.apply { id = doc.id }.toDomain()
                        } else {
                            null
                        }
                    }.sortedByDescending { it.createdAt }
                    trySend(list)
                }
            }
        awaitClose { listener.remove() }
    }.distinctUntilChanged()

    /**
     * Observes Udhari transactions for a specific bill in realtime.
     */
    fun getTransactionsForBill(billId: Long): Flow<List<UdhariTransaction>> = callbackFlow {
        trySend(emptyList())
        val listener = FirebaseManager.udhariTransactionsCollection()
            .whereEqualTo("billId", billId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Bill Udhari snapshot error: ${error.message}")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(UdhariTransactionDto::class.java)?.apply { id = doc.id }?.toDomain()
                    }.sortedByDescending { it.createdAt }
                    trySend(list)
                }
            }
        awaitClose { listener.remove() }
    }.distinctUntilChanged()

    /**
     * Atomically records a payment against a bill and updates customer outstanding.
     * Enforces idempotency via transactionUuid.
     */
    suspend fun recordPayment(payment: UdhariTransaction): Result<Unit> {
        return try {
            gateway.ensureAuthenticated()

            if (payment.amount <= 0.0) {
                return Result.failure(
                    IllegalArgumentException("भरणा रक्कम ० पेक्षा जास्त असावी (Payment amount must be greater than 0)")
                )
            }

            // Check if transactionUuid already exists (Idempotency protection)
            if (payment.transactionUuid.isNotBlank()) {
                if (gateway.isTransactionDuplicate(payment.transactionUuid)) {
                    Log.d(TAG, "Duplicate transaction prevented (idempotent): ${payment.transactionUuid}")
                    return Result.success(Unit)
                }
            }

            val cleanPaymentMobile = PhoneUtils.clean10DigitMobileInput(payment.customerMobile)
            val paymentCustGid = payment.customerGid.trim()
            val paymentCustId = payment.customerId

            // Strict customer identification: reject ambiguous payments with no valid ID or mobile
            if (paymentCustGid.isBlank() && paymentCustId == 0L && cleanPaymentMobile.length != 10) {
                return Result.failure(IllegalArgumentException("ग्राहक ओळख स्पष्ट नाही (Customer GID, ID, or 10-digit mobile required)"))
            }

            // 1. Fetch customer documents via gateway (root and shop collections)
            val allCustDocs = gateway.getCustomerDocs()
            val matchedCustDocs = allCustDocs.filter { doc ->
                val c = doc.data
                val cCleanMobile = PhoneUtils.clean10DigitMobileInput(c.mobile)
                val gidMatches = paymentCustGid.isNotBlank() && (
                    doc.id == paymentCustGid ||
                    c.id == paymentCustGid ||
                    c.gid == paymentCustGid
                )
                val mobileMatches = cleanPaymentMobile.length == 10 && cCleanMobile == cleanPaymentMobile
                val idMatches = (paymentCustId != 0L && (
                    c.legacyId == paymentCustId ||
                    (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == paymentCustId
                ))
                // Identify customer ONLY by customerGid / docId / legacyId / exact 10-digit mobile, NEVER by name
                gidMatches || idMatches || mobileMatches
            }

            val distinctMobiles = matchedCustDocs.mapNotNull {
                val m = PhoneUtils.clean10DigitMobileInput(it.data.mobile)
                if (m.length == 10) m else null
            }.distinct()
            if (distinctMobiles.size > 1) {
                return Result.failure(IllegalArgumentException("अस्पष्ट ग्राहक ओळख सापडली (Ambiguous customer match across different mobiles)"))
            }

            val currentCustDue = matchedCustDocs.firstOrNull()?.data?.outstandingAmount ?: payment.previousOutstanding

            // 2. Fetch bills via gateway
            val allBillDocs = gateway.getBillDocs()
            val matchedBillsMap = mutableMapOf<String, Pair<BillDocSnapshot, MutableList<String>>>()

            for (doc in allBillDocs) {
                val b = doc.data
                if (b.status == "VOIDED" || b.status == "TRASH") continue

                val bCleanMobile = PhoneUtils.clean10DigitMobileInput(b.customerMobile ?: "")
                val mobileMatches = cleanPaymentMobile.length == 10 && bCleanMobile == cleanPaymentMobile
                val gidMatches = paymentCustGid.isNotBlank() && (
                    b.customerGid == paymentCustGid ||
                    b.linkedCustomerGid == paymentCustGid ||
                    b.customerId == paymentCustGid
                )
                val idMatches = (paymentCustId != 0L && (
                    b.legacyCustomerId == paymentCustId ||
                    b.customerId == paymentCustId.toString() ||
                    b.customerId == "cust_$paymentCustId" ||
                    b.customerId?.removePrefix("cust_")?.toLongOrNull() == paymentCustId
                ))
                val specificBillMatches = (
                    (!payment.billGid.isNullOrBlank() && (b.gid == payment.billGid || b.id == payment.billGid || doc.id == payment.billGid)) ||
                    (payment.billId != 0L && (b.legacyId == payment.billId || (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == payment.billId)) ||
                    (payment.billNumber.isNotBlank() && b.billNumber.trim().equals(payment.billNumber.trim(), ignoreCase = true))
                )

                // Identify customer bills ONLY by customerGid / customer id / exact mobile / bill match, NEVER by name
                if (gidMatches || idMatches || mobileMatches || specificBillMatches) {
                    val billKey = if (b.transactionUuid.isNotBlank()) b.transactionUuid else if (b.billNumber.isNotBlank()) b.billNumber else doc.id
                    val existing = matchedBillsMap[billKey]
                    if (existing != null) {
                        if (!existing.second.contains(doc.path)) {
                            existing.second.add(doc.path)
                        }
                    } else {
                        matchedBillsMap[billKey] = Pair(doc, mutableListOf(doc.path))
                    }
                }
            }

            // Helper to get pending balance on a bill
            fun getBillPending(b: BillDto): Double {
                return if (b.pendingAmount > 0.0) b.pendingAmount
                else if (b.paidAmount > 0.0) (b.finalAmount - b.paidAmount).coerceAtLeast(0.0)
                else if (b.paymentStatus == "PAID") 0.0
                else b.finalAmount
            }

            // Target pending bills with amounts > 0.01
            val pendingBillsList = matchedBillsMap.values
                .filter { getBillPending(it.first.data) > 0.01 }
                .toMutableList()

            // If a specific bill was explicitly targeted (e.g. from Bill Preview), prioritize that bill first
            if (payment.billId != 0L || !payment.billGid.isNullOrBlank() || payment.billNumber.isNotBlank()) {
                val targetIndex = pendingBillsList.indexOfFirst { (snap, _) ->
                    val b = snap.data
                    (!payment.billGid.isNullOrBlank() && (b.gid == payment.billGid || b.id == payment.billGid || snap.id == payment.billGid)) ||
                    (payment.billId != 0L && (b.legacyId == payment.billId || (snap.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == payment.billId)) ||
                    (payment.billNumber.isNotBlank() && b.billNumber.trim().equals(payment.billNumber.trim(), ignoreCase = true))
                }
                if (targetIndex > 0) {
                    val prioritized = pendingBillsList.removeAt(targetIndex)
                    pendingBillsList.add(0, prioritized)
                }
            } else {
                // FIFO: Oldest unpaid bills settled first
                pendingBillsList.sortBy { it.first.data.billDate }
            }

            val totalBillsPending = pendingBillsList.sumOf { getBillPending(it.first.data) }
            val maxPayable = maxOf(totalBillsPending, currentCustDue)

            // F4: Enforce overpayment rejection: amount cannot exceed currentDue or specific bill outstanding
            if (payment.billId != 0L || !payment.billGid.isNullOrBlank() || payment.billNumber.isNotBlank()) {
                val targetBill = pendingBillsList.firstOrNull { (snap, _) ->
                    val b = snap.data
                    (!payment.billGid.isNullOrBlank() && (b.gid == payment.billGid || b.id == payment.billGid || snap.id == payment.billGid)) ||
                    (payment.billId != 0L && (b.legacyId == payment.billId || (snap.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == payment.billId)) ||
                    (payment.billNumber.isNotBlank() && b.billNumber.trim().equals(payment.billNumber.trim(), ignoreCase = true))
                }
                if (targetBill != null) {
                    val billPending = getBillPending(targetBill.first.data)
                    if (payment.amount > billPending + 0.01) {
                        return Result.failure(
                            IllegalArgumentException("भरणा रक्कम बिलाच्या बाकीपेक्षा जास्त आहे. बाकी: ₹${com.matoshree.shopmanager.utils.CurrencyFormatter.format(billPending)} (Payment exceeds bill pending)")
                        )
                    }
                }
            }

            if (maxPayable > 0.01 && payment.amount > maxPayable + 0.01) {
                return Result.failure(
                    IllegalArgumentException("भरणा रक्कम बाकीपेक्षा जास्त आहे. बाकी: ₹${com.matoshree.shopmanager.utils.CurrencyFormatter.format(maxPayable)} (Payment exceeds current due)")
                )
            } else if (maxPayable <= 0.01) {
                return Result.failure(
                    IllegalArgumentException("ग्राहकाची कोणतीही उधारी बाकी नाही (Customer has no outstanding due)")
                )
            }

            val safePaymentAmount = payment.amount
            val newCustDue = (currentCustDue - safePaymentAmount).coerceAtLeast(0.0)

            val commonTransactionUuid = payment.transactionUuid.ifBlank { UUID.randomUUID().toString() }
            val now = System.currentTimeMillis()

            val resolvedCustGid = when {
                payment.customerGid.isNotBlank() -> payment.customerGid
                matchedCustDocs.isNotEmpty() -> {
                    val firstC = matchedCustDocs.first().data
                    if (firstC.gid.isNotBlank()) firstC.gid else firstC.id.ifBlank { matchedCustDocs.first().id }
                }
                else -> ""
            }

            val resolvedCustMobile = if (cleanPaymentMobile.length == 10) cleanPaymentMobile else {
                matchedCustDocs.firstOrNull()?.data?.mobile?.let { PhoneUtils.clean10DigitMobileInput(it) }?.ifBlank { payment.customerMobile } ?: payment.customerMobile
            }

            val billUpdates = mutableMapOf<String, BillBatchUpdate>()
            val transactionsToSave = mutableListOf<UdhariTransactionDto>()

            // 3. Multi-Bill Cascading Settlement (FIFO / Priority)
            var remainingToAllocate = safePaymentAmount
            var recordedAllocationsCount = 0

            for ((snap, paths) in pendingBillsList) {
                val b = snap.data
                if (remainingToAllocate <= 0.001) break
                val billPending = getBillPending(b)
                if (billPending <= 0.001) continue

                val allocation = minOf(billPending, remainingToAllocate)
                if (allocation <= 0.001) continue

                val currentPaid = if (b.paidAmount > 0.0) b.paidAmount
                else if (b.paymentStatus == "PAID") b.finalAmount
                else 0.0
                val newPaid = (currentPaid + allocation).coerceAtMost(b.finalAmount)
                val newPending = (b.finalAmount - newPaid).coerceAtLeast(0.0)
                val newStatus = if (newPending <= 0.01) "PAID" else "PARTIAL"

                val updatedPayments = b.payments.toMutableList().apply {
                    add(
                        BillPaymentDto(
                            id = IdGenerator.generate(IdType.PAYMENT),
                            method = payment.paymentMethod.name,
                            amount = allocation,
                            status = "PAID",
                            createdAt = now
                        )
                    )
                }

                val updateObj = BillBatchUpdate(
                    paidAmount = newPaid,
                    pendingAmount = newPending,
                    paymentStatus = newStatus,
                    payments = updatedPayments,
                    updatedAt = now
                )

                for (p in paths) {
                    billUpdates[p] = updateObj
                }

                // Clean bill settlement transaction
                val txGid = if (recordedAllocationsCount == 0 && payment.gid.isNotBlank()) payment.gid else IdGenerator.generate(IdType.PAYMENT)
                val cleanBillNum = b.billNumber.trim()
                val billTxDto = UdhariTransactionDto(
                    id = txGid,
                    gid = txGid,
                    customerId = payment.customerId,
                    customerGid = resolvedCustGid,
                    customerName = payment.customerName,
                    customerMobile = resolvedCustMobile,
                    customerVillage = payment.customerVillage,
                    billId = b.legacyId ?: (snap.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL),
                    billGid = b.gid.ifBlank { snap.id },
                    billNumber = cleanBillNum, // Exact clean billNumber, e.g. "MC-1"
                    kind = "SETTLEMENT",
                    type = UdhariTransactionType.PAYMENT.name,
                    amount = allocation,
                    paymentMethod = payment.paymentMethod.name,
                    previousOutstanding = currentCustDue,
                    remainingOutstanding = newCustDue,
                    transactionUuid = commonTransactionUuid,
                    date = payment.date.ifBlank { DateUtils.todayDate() },
                    dueDate = b.dueDate,
                    note = payment.note ?: "Settlement for $cleanBillNum",
                    isReversed = false,
                    reversalReason = null,
                    reversedAt = null,
                    createdByDeviceId = payment.createdByDeviceId,
                    createdAt = now
                )
                transactionsToSave.add(billTxDto)
                recordedAllocationsCount++
                remainingToAllocate -= allocation
            }

            // If any amount was not allocated to a specific bill, record general payment
            if (remainingToAllocate > 0.001 || recordedAllocationsCount == 0) {
                val genAmount = if (recordedAllocationsCount == 0) safePaymentAmount else remainingToAllocate
                val txGid = if (recordedAllocationsCount == 0 && payment.gid.isNotBlank()) payment.gid else IdGenerator.generate(IdType.PAYMENT)
                val genTxDto = UdhariTransactionDto(
                    id = txGid,
                    gid = txGid,
                    customerId = payment.customerId,
                    customerGid = resolvedCustGid,
                    customerName = payment.customerName,
                    customerMobile = resolvedCustMobile,
                    customerVillage = payment.customerVillage,
                    billId = payment.billId,
                    billGid = payment.billGid,
                    billNumber = if (payment.billNumber.isNotBlank()) payment.billNumber.trim() else "DIRECT-PAY",
                    kind = if (payment.billNumber.isNotBlank() || !payment.billGid.isNullOrBlank()) "SETTLEMENT" else "GENERAL_PAYMENT",
                    type = UdhariTransactionType.PAYMENT.name,
                    amount = genAmount,
                    paymentMethod = payment.paymentMethod.name,
                    previousOutstanding = currentCustDue,
                    remainingOutstanding = newCustDue,
                    transactionUuid = commonTransactionUuid,
                    date = payment.date.ifBlank { DateUtils.todayDate() },
                    dueDate = pendingBillsList.firstOrNull()?.first?.data?.dueDate,
                    note = payment.note ?: "Direct payment",
                    isReversed = false,
                    reversalReason = null,
                    reversedAt = null,
                    createdByDeviceId = payment.createdByDeviceId,
                    createdAt = now
                )
                transactionsToSave.add(genTxDto)
            }

            // 5. Update customer cached outstanding amount in all matched customer docs
            val customerUpdates = mutableMapOf<String, Double>()
            for (custDoc in matchedCustDocs) {
                customerUpdates[custDoc.path] = newCustDue
            }

            gateway.commitPaymentBatch(customerUpdates, billUpdates, transactionsToSave)
            Log.d(TAG, "Payment recorded successfully: $commonTransactionUuid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error recording payment: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Atomically reverses an authorized payment to preserve full financial auditability.
     */
    suspend fun reversePayment(
        transactionId: String,
        reason: String,
        expectedCustomerGid: String? = null,
        expectedCustomerMobile: String? = null,
        expectedCustomerId: Long = 0L,
        expectedAmount: Double? = null
    ): Result<Unit> {
        return try {
            gateway.ensureAuthenticated()

            val cleanTxId = transactionId.trim()
            if (cleanTxId.isBlank()) {
                return Result.failure(IllegalArgumentException("भरणा आयडी अवैध आहे (Invalid transaction ID)"))
            }

            val allTxs = gateway.getTransactionDocs()

            // 1. Look up by EXACT document ID first
            val targetDoc = allTxs.firstOrNull { it.id == cleanTxId }
                ?: allTxs.firstOrNull { it.data.id == cleanTxId }
                ?: allTxs.firstOrNull { it.data.gid.isNotBlank() && it.data.gid == cleanTxId }
                ?: (if (cleanTxId.startsWith("pay_")) allTxs.firstOrNull { it.id == cleanTxId.removePrefix("pay_") || it.data.id == cleanTxId.removePrefix("pay_") } else null)
                ?: allTxs.firstOrNull { it.id == "pay_$cleanTxId" || it.data.id == "pay_$cleanTxId" }
                ?: allTxs.firstOrNull { it.data.transactionUuid.isNotBlank() && it.data.transactionUuid == cleanTxId }
                ?: return Result.failure(IllegalStateException("भरणा नोंद सापडली नाही (Transaction not found: $transactionId)"))

            val originalTx = targetDoc.data

            // Refuse to reverse initial checkout payment directly
            if (originalTx.note?.contains("checkout", ignoreCase = true) == true ||
                originalTx.note?.contains("initial", ignoreCase = true) == true ||
                originalTx.id.endsWith("_pay")) {
                return Result.failure(IllegalStateException("बिलातील सुरुवातीचा भरणा रद्द करता येत नाही. कृपया बिल बदला किंवा रद्द करा."))
            }

            // Customer isolation safety check: verify expected customer matches if provided
            if (expectedCustomerGid?.isNotBlank() == true || expectedCustomerMobile?.isNotBlank() == true || expectedCustomerId != 0L) {
                val cleanExpectedMobile = PhoneUtils.clean10DigitMobileInput(expectedCustomerMobile ?: "")
                val cleanTxMobile = PhoneUtils.clean10DigitMobileInput(originalTx.customerMobile)

                val gidMatch = expectedCustomerGid?.isNotBlank() == true && (
                    originalTx.customerGid == expectedCustomerGid ||
                    originalTx.customerId.toString() == expectedCustomerGid
                )
                val mobMatch = cleanExpectedMobile.length == 10 && cleanTxMobile == cleanExpectedMobile
                val idMatch = expectedCustomerId != 0L && originalTx.customerId == expectedCustomerId

                if (!gidMatch && !mobMatch && !idMatch) {
                    return Result.failure(IllegalStateException("भरणा नोंद दुसऱ्या ग्राहकाशी संबंधित आहे (Transaction belongs to another customer)"))
                }
            }

            if (expectedAmount != null && expectedAmount > 0.0) {
                if (kotlin.math.abs(originalTx.amount - expectedAmount) > 0.01) {
                    return Result.failure(IllegalStateException("भरणा रक्कम जुळत नाही (Transaction amount mismatch)"))
                }
            }

            val allTxsToReverse = if (originalTx.transactionUuid.isNotBlank()) {
                allTxs.filter {
                    it.data.transactionUuid == originalTx.transactionUuid &&
                    it.data.type != UdhariTransactionType.REVERSAL.name &&
                    !it.data.isReversed
                }
            } else {
                listOf(targetDoc)
            }

            if (allTxsToReverse.all { it.data.isReversed }) {
                return Result.failure(IllegalStateException("हा भरणा आधीच रद्द केलेला आहे (Already reversed)"))
            }

            val now = System.currentTimeMillis()
            var totalReversedAmount = 0.0

            val reversedTxDocUpdates = mutableMapOf<String, Map<String, Any?>>()
            val reversalTransactionsToSave = mutableListOf<UdhariTransactionDto>()
            val billUpdates = mutableMapOf<String, Map<String, Any?>>()
            val allBillDocs = gateway.getBillDocs()

            for (docItem in allTxsToReverse) {
                val tx = docItem.data
                if (tx.isReversed) continue

                totalReversedAmount += tx.amount

                // Mark original as reversed (audit trail - keeps status ACTIVE so visible as cancelled)
                reversedTxDocUpdates[docItem.path] = mapOf(
                    "isReversed" to true,
                    "reversalReason" to reason,
                    "reversedAt" to now,
                    "updatedAt" to now
                )

                // Add reversal transaction record
                val reversalGid = IdGenerator.generate(IdType.REVERSAL)
                val reversalDto = UdhariTransactionDto(
                    id = reversalGid,
                    gid = reversalGid,
                    customerId = tx.customerId,
                    customerGid = tx.customerGid,
                    customerName = tx.customerName,
                    customerMobile = tx.customerMobile,
                    customerVillage = tx.customerVillage,
                    billId = tx.billId,
                    billGid = tx.billGid,
                    billNumber = tx.billNumber,
                    type = UdhariTransactionType.REVERSAL.name,
                    amount = tx.amount,
                    paymentMethod = tx.paymentMethod,
                    previousOutstanding = 0.0,
                    remainingOutstanding = 0.0,
                    transactionUuid = UUID.randomUUID().toString(),
                    date = DateUtils.todayDate(),
                    note = "Reversal: $reason",
                    isReversed = false,
                    reversalReason = reason,
                    createdAt = now
                )
                reversalTransactionsToSave.add(reversalDto)

                // Direct bill read by billGid or clean billNumber (stripping "(₹...)" notes and handling multi-bill CSV)
                val candidateBillNumbers = tx.billNumber.split(",")
                    .map { it.substringBefore("(").trim() }
                    .filter { it.isNotBlank() && it != "DIRECT-PAY" }

                val matchingBills = allBillDocs.filter { bDoc ->
                    val b = bDoc.data
                    (!tx.billGid.isNullOrBlank() && (b.gid == tx.billGid || b.id == tx.billGid || bDoc.id == tx.billGid)) ||
                    (candidateBillNumbers.any { num -> b.billNumber.trim().equals(num, ignoreCase = true) }) ||
                    (tx.billId > 0L && (b.legacyId == tx.billId || (bDoc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == tx.billId))
                }

                for (matchedBill in matchingBills) {
                    val currentBill = matchedBill.data
                    val newPaid = (currentBill.paidAmount - tx.amount).coerceAtLeast(0.0)
                    val newPending = (currentBill.finalAmount - newPaid).coerceAtLeast(0.0)
                    val newStatus = if (newPaid <= 0.01) "PENDING" else if (newPending <= 0.01) "PAID" else "PARTIAL"

                    val updatedPayments = currentBill.payments.filterNot { p ->
                        p.id == tx.id || p.id == tx.gid ||
                        (tx.id.isNotBlank() && p.id.contains(tx.id)) ||
                        (tx.transactionUuid.isNotBlank() && p.id.contains(tx.transactionUuid))
                    }

                    billUpdates[matchedBill.path] = mapOf(
                        "paidAmount" to newPaid,
                        "pendingAmount" to newPending,
                        "paymentStatus" to newStatus,
                        "payments" to updatedPayments,
                        "updatedAt" to now
                    )
                }
            }

            // Restore customer outstanding amount across all matching customer docs
            val customerUpdates = mutableMapOf<String, Double>()
            val allCustDocs = gateway.getCustomerDocs()
            val cleanMob = PhoneUtils.clean10DigitMobileInput(originalTx.customerMobile)

            val matchedCustDocs = allCustDocs.filter { cDoc ->
                val c = cDoc.data
                val cClean = PhoneUtils.clean10DigitMobileInput(c.mobile)
                val gidMatches = originalTx.customerGid.isNotBlank() && (
                    cDoc.id == originalTx.customerGid || c.id == originalTx.customerGid || c.gid == originalTx.customerGid
                )
                val mobMatches = cleanMob.length == 10 && cClean == cleanMob
                val idMatches = originalTx.customerId > 0L && (
                    c.legacyId == originalTx.customerId || (cDoc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == originalTx.customerId
                )
                gidMatches || mobMatches || idMatches
            }

            for (cDoc in matchedCustDocs) {
                val prev = cDoc.data.outstandingAmount
                customerUpdates[cDoc.path] = prev + totalReversedAmount
            }

            gateway.commitReversalBatch(reversalTransactionsToSave, reversedTxDocUpdates, billUpdates, customerUpdates)
            Log.d(TAG, "Payment reversed successfully: ${targetDoc.id}, reason: $reason")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error reversing payment: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Updates customer credit limit.
     */
    suspend fun setCustomerCreditLimit(customerId: Long, creditLimit: Double?): Result<Unit> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val custSnap = try {
                kotlinx.coroutines.withTimeoutOrNull(600) {
                    FirebaseManager.customersCollection().get().await()
                } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) {
                null
            }
            val custDoc = custSnap?.documents?.find {
                val c = it.toObject(CustomerDto::class.java)
                c?.legacyId == customerId || (it.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == customerId
            } ?: return Result.failure(IllegalStateException("Customer not found"))

            FirebaseManager.customersCollection().document(custDoc.id).update(
                mapOf(
                    "creditLimit" to creditLimit,
                    "updatedAt" to System.currentTimeMillis()
                )
            ).await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Atomically records a manual/old Udhari opening balance (जुनी बाकी) for a customer.
     */
    suspend fun recordManualDebit(
        customer: Customer,
        amount: Double,
        note: String? = null,
        date: String? = null,
        dueDate: String? = null
    ): Result<UdhariTransaction> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val creditGid = IdGenerator.generate(IdType.UDHARI)
            val txDocId = creditGid
            val effectiveDate = date?.ifBlank { DateUtils.todayDate() } ?: DateUtils.todayDate()
            val cleanNote = note?.ifBlank { "जुनी उधारी" } ?: "जुनी उधारी"

            val custSnap = try {
                kotlinx.coroutines.withTimeoutOrNull(600) {
                    FirebaseManager.customersCollection().get().await()
                } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) {
                null
            }

            val custDoc = custSnap?.documents?.find {
                val c = it.toObject(CustomerDto::class.java)
                (customer.gid.isNotBlank() && (c?.gid == customer.gid || it.id == customer.gid)) ||
                (customer.mobile.isNotBlank() && c?.mobile == customer.mobile) ||
                c?.legacyId == customer.id ||
                (it.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == customer.id
            }

            val prevDue = custDoc?.toObject(CustomerDto::class.java)?.outstandingAmount ?: customer.outstandingBalance
            val newDue = prevDue + amount
            val targetCustId = custDoc?.toObject(CustomerDto::class.java)?.legacyId ?: customer.id
            val targetCustGid = if (customer.gid.isNotBlank()) customer.gid else (custDoc?.toObject(CustomerDto::class.java)?.gid ?: "")

            val txDto = UdhariTransactionDto(
                id = txDocId,
                gid = creditGid,
                customerId = targetCustId,
                customerGid = targetCustGid,
                customerName = customer.name,
                customerMobile = customer.mobile,
                customerVillage = customer.address,
                billId = 0L,
                billNumber = "MANUAL-${System.currentTimeMillis().toString().takeLast(4)}",
                type = UdhariTransactionType.PURCHASE_CREDIT.name,
                kind = "MANUAL",
                amount = amount,
                paymentMethod = PaymentMethod.OTHER.name,
                previousOutstanding = prevDue,
                remainingOutstanding = newDue,
                transactionUuid = UUID.randomUUID().toString(),
                date = effectiveDate,
                dueDate = dueDate,
                note = cleanNote,
                isReversed = false,
                status = "ACTIVE",
                createdAt = System.currentTimeMillis()
            )

            val batch = FirebaseManager.firestore.batch()
            batch.set(FirebaseManager.udhariTransactionsCollection().document(txDocId), txDto)

            if (custDoc != null) {
                batch.update(
                    FirebaseManager.customersCollection().document(custDoc.id),
                    mapOf(
                        "outstandingAmount" to newDue,
                        "updatedAt" to System.currentTimeMillis()
                    )
                )
            } else {
                val newCustDocId = "cust_${customer.id}"
                batch.set(
                    FirebaseManager.customersCollection().document(newCustDocId),
                    mapOf(
                        "outstandingAmount" to newDue,
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )
            }

            val commitTask = batch.commit()
            FirebaseManager.awaitWriteTask(commitTask)
            Log.d(TAG, "Manual debit recorded: $txDocId")
            Result.success(txDto.toDomain())
        } catch (e: Exception) {
            Log.e(TAG, "Error recording manual debit: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Resolves a transaction document by direct ID, prefix-stripped ID, gid, uuid or billNumber.
     */
    private suspend fun findTransactionDocument(txId: String): com.google.firebase.firestore.DocumentSnapshot? {
        val col = FirebaseManager.udhariTransactionsCollection()
        // 1. Direct document ID match
        try {
            val d = col.document(txId).get().await()
            if (d.exists()) return d
        } catch (_: Exception) {}

        // 2. Stripped prefix match (manual_, credit_, bill_) WITHOUT cutting at underscore
        val clean = txId.removePrefix("manual_").removePrefix("credit_").removePrefix("bill_")
        if (clean != txId && clean.isNotBlank()) {
            try {
                val d = col.document(clean).get().await()
                if (d.exists()) return d
            } catch (_: Exception) {}
        }

        // 3. By gid
        try {
            val q = col.whereEqualTo("gid", txId).limit(1).get().await()
            if (!q.isEmpty) return q.documents.first()
        } catch (_: Exception) {}

        // 4. By transactionUuid
        try {
            val q = col.whereEqualTo("transactionUuid", txId).limit(1).get().await()
            if (!q.isEmpty) return q.documents.first()
        } catch (_: Exception) {}

        // 5. By billNumber (if clean is a valid bill number)
        if (clean.isNotBlank()) {
            try {
                val q = col.whereEqualTo("billNumber", clean).limit(1).get().await()
                if (!q.isEmpty) return q.documents.first()
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Atomically deletes or cancels an Udhari transaction (manual or orphan) and restores customer balance.
     */
    suspend fun deleteOrReverseManualUdhari(transactionId: String, reason: String = "Deleted by user"): Result<Unit> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val isOnline = FirebaseManager.isNetworkAvailable()
            val txSnap = findTransactionDocument(transactionId)
                ?: return Result.failure(IllegalStateException("Transaction not found: $transactionId"))

            val originalTx = txSnap.toObject(UdhariTransactionDto::class.java)
                ?: return Result.failure(IllegalStateException("Failed to parse transaction"))

            val rootCustSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(800) {
                        FirebaseManager.customersCollection().get().await()
                    } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val shopCustSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(800) {
                        FirebaseManager.shopCustomersCollection().get().await()
                    } ?: FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val allCustDocs = (rootCustSnap?.documents ?: emptyList()) + (shopCustSnap?.documents ?: emptyList())
            val matchedCustDocs = allCustDocs.filter { doc ->
                val c = doc.toObject(CustomerDto::class.java)
                c?.legacyId == originalTx.customerId || (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == originalTx.customerId
            }

            val batch = FirebaseManager.firestore.batch()
            val now = System.currentTimeMillis()
            batch.update(
                txSnap.reference,
                mapOf(
                    "status" to "TRASH",
                    "deletedAt" to now,
                    "isDeleted" to true,
                    "reversalReason" to reason,
                    "updatedAt" to now
                )
            )

            if (matchedCustDocs.isNotEmpty()) {
                val currentCust = matchedCustDocs.firstOrNull()?.toObject(CustomerDto::class.java)
                val currentCustDue = currentCust?.outstandingAmount ?: 0.0
                val newCustDue = (currentCustDue - originalTx.amount).coerceAtLeast(0.0)
                for (doc in matchedCustDocs) {
                    batch.update(
                        doc.reference,
                        mapOf(
                            "outstandingAmount" to newCustDue,
                            "updatedAt" to now
                        )
                    )
                }
            }

            val commitTask = batch.commit()
            FirebaseManager.awaitWriteTask(commitTask)
            Log.d(TAG, "Udhari transaction deleted successfully: $transactionId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting udhari: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Ignores an orphan transaction so it is excluded from active ledger calculations.
     */
    suspend fun ignoreTransaction(transactionId: String): Result<Unit> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val txSnap = findTransactionDocument(transactionId)
                ?: return Result.failure(IllegalStateException("Transaction not found: $transactionId"))

            val commitTask = txSnap.reference.update(
                mapOf(
                    "status" to "IGNORED",
                    "updatedAt" to System.currentTimeMillis()
                )
            )
            FirebaseManager.awaitWriteTask(commitTask)
            Log.d(TAG, "Transaction ignored successfully: $transactionId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error ignoring transaction: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Converts an orphan payment to a general (FIFO) payment applied against all customer dues.
     */
    suspend fun convertOrphanPaymentToGeneral(transactionId: String): Result<Unit> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val txSnap = findTransactionDocument(transactionId)
                ?: return Result.failure(IllegalStateException("Transaction not found: $transactionId"))

            val commitTask = txSnap.reference.update(
                mapOf(
                    "kind" to "GENERAL_PAYMENT",
                    "billNumber" to "GENERAL",
                    "billId" to 0L,
                    "billGid" to null,
                    "needsReview" to false,
                    "updatedAt" to System.currentTimeMillis()
                )
            )
            FirebaseManager.awaitWriteTask(commitTask)
            Log.d(TAG, "Orphan payment converted to general payment: $transactionId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting orphan payment: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Bulk soft-deletes a list of orphan transaction IDs.
     */
    suspend fun bulkSoftDeleteOrphans(txIds: List<String>): Result<Int> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val batch = FirebaseManager.firestore.batch()
            val now = System.currentTimeMillis()
            var count = 0
            for (id in txIds) {
                val snap = findTransactionDocument(id)
                if (snap != null && snap.exists()) {
                    batch.update(
                        snap.reference,
                        mapOf(
                            "status" to "TRASH",
                            "deletedAt" to now,
                            "isDeleted" to true,
                            "reversalReason" to "Bulk orphan delete",
                            "updatedAt" to now
                        )
                    )
                    count++
                }
            }
            if (count > 0) {
                val commitTask = batch.commit()
                FirebaseManager.awaitWriteTask(commitTask)
            }
            Log.d(TAG, "Bulk soft deleted $count orphan transactions")
            Result.success(count)
        } catch (e: Exception) {
            Log.e(TAG, "Error bulk deleting orphans: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Updates the due date of an Udhari transaction.
     */
    suspend fun updateUdhariDueDate(transactionId: String, newDueDate: String?): Result<Unit> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val txSnap = FirebaseManager.udhariTransactionsCollection().document(transactionId).get().await()
            if (!txSnap.exists()) {
                return Result.failure(IllegalStateException("Transaction not found"))
            }
            FirebaseManager.udhariTransactionsCollection().document(transactionId).update(
                mapOf(
                    "dueDate" to newDueDate,
                    "updatedAt" to System.currentTimeMillis()
                )
            ).await()
            Log.d(TAG, "Udhari due date updated: $transactionId -> $newDueDate")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating udhari due date: ${e.message}", e)
            Result.failure(e)
        }
    }
}

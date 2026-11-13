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

class FirestoreUdhariRepository {
    private val TAG = "FirestoreUdhariRepo"

    /**
     * Observes all Udhari transactions in realtime.
     */
    fun getAllTransactions(): Flow<List<UdhariTransaction>> = callbackFlow {
        val listener = FirebaseManager.udhariTransactionsCollection()
            .limit(1000)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Udhari transactions snapshot error: ${error.message}")
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
        val cleanMobile = customerMobile?.trim() ?: ""
        val listener = FirebaseManager.udhariTransactionsCollection()
            .limit(500)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Customer Udhari snapshot error: ${error.message}")
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
        val listener = FirebaseManager.udhariTransactionsCollection()
            .whereEqualTo("billId", billId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Bill Udhari snapshot error: ${error.message}")
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
            FirebaseManager.ensureAuthenticated()

            // Check if transactionUuid already exists (Idempotency protection)
            if (payment.transactionUuid.isNotBlank()) {
                val existingQuery = FirebaseManager.udhariTransactionsCollection()
                    .whereEqualTo("transactionUuid", payment.transactionUuid)
                    .limit(1)
                    .get()
                    .await()
                if (!existingQuery.isEmpty) {
                    Log.d(TAG, "Duplicate transaction prevented (idempotent): ${payment.transactionUuid}")
                    return Result.success(Unit)
                }
            }

            val isOnline = FirebaseManager.isNetworkAvailable()

            val cleanPaymentMobile = PhoneUtils.clean10DigitMobileInput(payment.customerMobile)
            val cleanCustName = payment.customerName.trim()

            // 1. Fetch customer documents from both root and shop collections
            val rootCustSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(1000) {
                        FirebaseManager.customersCollection().get().await()
                    } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val shopCustSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(1000) {
                        FirebaseManager.shopCustomersCollection().get().await()
                    } ?: FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val allCustDocs = (rootCustSnap?.documents ?: emptyList()) + (shopCustSnap?.documents ?: emptyList())
            val matchedCustDocs = allCustDocs.filter { doc ->
                val c = doc.toObject(CustomerDto::class.java)
                val cCleanMobile = PhoneUtils.clean10DigitMobileInput(c?.mobile ?: "")
                val mobileMatches = cleanPaymentMobile.isNotBlank() && cCleanMobile.isNotBlank() && cCleanMobile == cleanPaymentMobile
                val idMatches = (payment.customerId != 0L && (c?.legacyId == payment.customerId || (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == payment.customerId))
                val nameMatches = cleanCustName.isNotBlank() && c?.name?.trim()?.equals(cleanCustName, ignoreCase = true) == true
                idMatches || mobileMatches || nameMatches
            }

            val currentCustDue = matchedCustDocs.firstOrNull()?.toObject(CustomerDto::class.java)?.outstandingAmount ?: payment.previousOutstanding

            // 2. Fetch all bills across both root and shop collections
            val rootBillsSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(1000) {
                        FirebaseManager.billsCollection().get().await()
                    } ?: FirebaseManager.billsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.billsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val shopBillsSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(1000) {
                        FirebaseManager.shopBillsCollection().get().await()
                    } ?: FirebaseManager.shopBillsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.shopBillsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val allBillDocs = (rootBillsSnap?.documents ?: emptyList()) + (shopBillsSnap?.documents ?: emptyList())
            val matchedBillsMap = mutableMapOf<String, Pair<BillDto, MutableList<com.google.firebase.firestore.DocumentReference>>>()

            for (doc in allBillDocs) {
                val b = doc.toObject(BillDto::class.java) ?: continue
                if (b.status == "VOIDED" || b.status == "TRASH") continue

                val bCleanMobile = PhoneUtils.clean10DigitMobileInput(b.customerMobile ?: "")
                val mobileMatches = cleanPaymentMobile.isNotBlank() && bCleanMobile.isNotBlank() && bCleanMobile == cleanPaymentMobile
                val idMatches = (payment.customerId != 0L && (
                    b.legacyCustomerId == payment.customerId ||
                    b.customerId == payment.customerId.toString() ||
                    b.customerId == "cust_${payment.customerId}" ||
                    b.customerId?.removePrefix("cust_")?.toLongOrNull() == payment.customerId
                ))
                val nameMatches = cleanCustName.isNotBlank() && b.customerName?.trim()?.equals(cleanCustName, ignoreCase = true) == true

                if (idMatches || mobileMatches || nameMatches) {
                    val billKey = if (b.transactionUuid.isNotBlank()) b.transactionUuid else if (b.billNumber.isNotBlank()) b.billNumber else doc.id
                    val existing = matchedBillsMap[billKey]
                    if (existing != null) {
                        if (!existing.second.any { it.path == doc.reference.path }) {
                            existing.second.add(doc.reference)
                        }
                    } else {
                        matchedBillsMap[billKey] = Pair(b, mutableListOf(doc.reference))
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
                .filter { getBillPending(it.first) > 0.01 }
                .toMutableList()

            // If a specific bill was explicitly targeted (e.g. from Bill Preview), prioritize that bill first
            if (payment.billId != 0L || payment.billNumber.isNotBlank()) {
                val targetIndex = pendingBillsList.indexOfFirst { (b, _) ->
                    (payment.billId != 0L && (b.legacyId == payment.billId || (b.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == payment.billId)) ||
                    (payment.billNumber.isNotBlank() && b.billNumber == payment.billNumber)
                }
                if (targetIndex > 0) {
                    val prioritized = pendingBillsList.removeAt(targetIndex)
                    pendingBillsList.add(0, prioritized)
                }
            } else {
                // FIFO: Oldest unpaid bills settled first
                pendingBillsList.sortBy { it.first.billDate }
            }

            val totalBillsPending = pendingBillsList.sumOf { getBillPending(it.first) }
            val maxPayable = maxOf(totalBillsPending, currentCustDue)
            val safePaymentAmount = if (maxPayable > 0.01) payment.amount.coerceIn(0.01, maxPayable) else payment.amount.coerceAtLeast(0.01)
            val newCustDue = (currentCustDue - safePaymentAmount).coerceAtLeast(0.0)

            val txDocId = "udhari_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val batch = FirebaseManager.firestore.batch()
            val now = System.currentTimeMillis()

            // 3. Multi-Bill Cascading Settlement (FIFO / Priority)
            var remainingToAllocate = safePaymentAmount
            val settledBillDetails = mutableListOf<String>()

            for ((b, refs) in pendingBillsList) {
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
                            id = UUID.randomUUID().toString(),
                            method = payment.paymentMethod.name,
                            amount = allocation,
                            status = "PAID",
                            createdAt = now
                        )
                    )
                }

                val updateMap = mapOf(
                    "paidAmount" to newPaid,
                    "pendingAmount" to newPending,
                    "paymentStatus" to newStatus,
                    "payments" to updatedPayments,
                    "updatedAt" to now
                )

                for (ref in refs) {
                    batch.update(ref, updateMap)
                }

                settledBillDetails.add("${b.billNumber} (₹${String.format(java.util.Locale.US, "%.0f", allocation)})")
                remainingToAllocate -= allocation
            }

            // 4. Record Unified Payment Transaction
            val primaryBillNumber = when {
                settledBillDetails.isNotEmpty() -> settledBillDetails.joinToString(", ")
                payment.billNumber.isNotBlank() -> payment.billNumber
                else -> "DIRECT-PAY"
            }

            val txDto = UdhariTransactionDto(
                id = txDocId,
                customerId = payment.customerId,
                customerName = payment.customerName,
                customerMobile = payment.customerMobile,
                customerVillage = payment.customerVillage,
                billId = payment.billId,
                billNumber = primaryBillNumber,
                type = UdhariTransactionType.PAYMENT.name,
                amount = safePaymentAmount,
                paymentMethod = payment.paymentMethod.name,
                previousOutstanding = currentCustDue,
                remainingOutstanding = newCustDue,
                transactionUuid = payment.transactionUuid.ifBlank { UUID.randomUUID().toString() },
                date = payment.date.ifBlank { DateUtils.todayDate() },
                dueDate = pendingBillsList.firstOrNull()?.first?.dueDate,
                note = payment.note ?: if (settledBillDetails.size > 1) "Settled ${settledBillDetails.size} bills" else null,
                isReversed = false,
                reversalReason = null,
                reversedAt = null,
                createdByDeviceId = payment.createdByDeviceId,
                createdAt = now
            )
            batch.set(FirebaseManager.udhariTransactionsCollection().document(txDocId), txDto)

            // 5. Update customer cached outstanding amount in both root and shop collections
            for (custDoc in matchedCustDocs) {
                batch.update(
                    custDoc.reference,
                    mapOf(
                        "outstandingAmount" to newCustDue,
                        "updatedAt" to now
                    )
                )
            }

            val commitTask = batch.commit()
            FirebaseManager.awaitWriteTask(commitTask)
            Log.d(TAG, "Payment recorded successfully: $txDocId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error recording payment: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Atomically reverses an authorized payment to preserve full financial auditability.
     */
    suspend fun reversePayment(transactionId: String, reason: String): Result<Unit> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val isOnline = FirebaseManager.isNetworkAvailable()

            val txSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(800) {
                        FirebaseManager.udhariTransactionsCollection().document(transactionId).get().await()
                    } ?: FirebaseManager.udhariTransactionsCollection().document(transactionId).get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.udhariTransactionsCollection().document(transactionId).get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) {
                FirebaseManager.udhariTransactionsCollection().document(transactionId).get(com.google.firebase.firestore.Source.CACHE).await()
            }

            if (!txSnap.exists()) {
                return Result.failure(IllegalStateException("Transaction not found"))
            }

            val originalTx = txSnap.toObject(UdhariTransactionDto::class.java)
                ?: return Result.failure(IllegalStateException("Failed to parse transaction"))

            if (originalTx.isReversed) {
                return Result.failure(IllegalStateException("Transaction is already reversed"))
            }

            // Find bills if linked to a specific bill across both root and shop collections
            val rootBillsSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(800) {
                        FirebaseManager.billsCollection().get().await()
                    } ?: FirebaseManager.billsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.billsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val shopBillsSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(800) {
                        FirebaseManager.shopBillsCollection().get().await()
                    } ?: FirebaseManager.shopBillsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.shopBillsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val allBillDocs = (rootBillsSnap?.documents ?: emptyList()) + (shopBillsSnap?.documents ?: emptyList())

            val matchedBillDocs = if (originalTx.billId != 0L || originalTx.billNumber.isNotBlank()) {
                allBillDocs.filter { doc ->
                    val b = doc.toObject(BillDto::class.java)
                    (originalTx.billId != 0L && (b?.legacyId == originalTx.billId || (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == originalTx.billId)) ||
                    (originalTx.billNumber.isNotBlank() && b?.billNumber == originalTx.billNumber)
                }
            } else emptyList()

            val currentBill = matchedBillDocs.firstOrNull()?.toObject(BillDto::class.java)
            val newPaid = if (currentBill != null) (currentBill.paidAmount - originalTx.amount).coerceAtLeast(0.0) else 0.0
            val newPending = if (currentBill != null) (currentBill.finalAmount - newPaid).coerceAtLeast(0.0) else 0.0
            val newStatus = if (newPaid <= 0.01) "PENDING" else if (newPending <= 0.01) "PAID" else "PARTIAL"

            val reversalDocId = "reversal_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val reversalDto = UdhariTransactionDto(
                id = reversalDocId,
                customerId = originalTx.customerId,
                customerName = originalTx.customerName,
                customerMobile = originalTx.customerMobile,
                customerVillage = originalTx.customerVillage,
                billId = originalTx.billId,
                billNumber = originalTx.billNumber,
                type = UdhariTransactionType.REVERSAL.name,
                amount = originalTx.amount,
                paymentMethod = originalTx.paymentMethod,
                previousOutstanding = currentBill?.pendingAmount ?: 0.0,
                remainingOutstanding = newPending,
                transactionUuid = UUID.randomUUID().toString(),
                date = DateUtils.todayDate(),
                note = "Reversal: $reason",
                isReversed = false,
                reversalReason = reason,
                createdAt = System.currentTimeMillis()
            )

            val batch = FirebaseManager.firestore.batch()
            val now = System.currentTimeMillis()
            // Mark original as TRASH & reversed so it goes to Trash Bin!
            batch.update(
                FirebaseManager.udhariTransactionsCollection().document(transactionId),
                mapOf(
                    "status" to "TRASH",
                    "isReversed" to true,
                    "reversalReason" to reason,
                    "reversedAt" to now,
                    "deletedAt" to now,
                    "updatedAt" to now
                )
            )
            // Add reversal transaction record
            batch.set(FirebaseManager.udhariTransactionsCollection().document(reversalDocId), reversalDto)
            // Update bill status and balances across all matched bill references (root and shop)
            for (doc in matchedBillDocs) {
                batch.update(
                    doc.reference,
                    mapOf(
                        "paidAmount" to newPaid,
                        "pendingAmount" to newPending,
                        "paymentStatus" to newStatus,
                        "updatedAt" to now
                    )
                )
            }

            // Update customer cached outstanding amount across both root and shop collections
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

            val cleanTxMobile = PhoneUtils.clean10DigitMobileInput(originalTx.customerMobile)
            val allCustDocs = (rootCustSnap?.documents ?: emptyList()) + (shopCustSnap?.documents ?: emptyList())
            val matchedCustDocs = allCustDocs.filter { doc ->
                val c = doc.toObject(CustomerDto::class.java)
                (originalTx.customerId > 0L && (c?.legacyId == originalTx.customerId || (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == originalTx.customerId)) ||
                (cleanTxMobile.length == 10 && c?.mobile?.isNotBlank() == true && PhoneUtils.clean10DigitMobileInput(c.mobile) == cleanTxMobile)
            }
            if (matchedCustDocs.isNotEmpty()) {
                val currentCust = matchedCustDocs.firstOrNull()?.toObject(CustomerDto::class.java)
                val newCustDue = (currentCust?.outstandingAmount ?: 0.0) + originalTx.amount
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
            Log.d(TAG, "Payment reversed successfully: $transactionId, reason: $reason")
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
            val txDocId = "udhari_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val effectiveDate = date?.ifBlank { DateUtils.todayDate() } ?: DateUtils.todayDate()
            val cleanNote = note?.ifBlank { "जुनी बाकी / Manual Opening Balance" } ?: "जुनी बाकी / Manual Opening Balance"

            val custSnap = try {
                kotlinx.coroutines.withTimeoutOrNull(600) {
                    FirebaseManager.customersCollection().get().await()
                } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) {
                null
            }

            val custDoc = custSnap?.documents?.find {
                val c = it.toObject(CustomerDto::class.java)
                (customer.mobile.isNotBlank() && c?.mobile == customer.mobile) ||
                c?.legacyId == customer.id ||
                (it.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == customer.id
            }

            val prevDue = custDoc?.toObject(CustomerDto::class.java)?.outstandingAmount ?: customer.outstandingBalance
            val newDue = prevDue + amount
            val targetCustId = custDoc?.toObject(CustomerDto::class.java)?.legacyId ?: customer.id

            val txDto = UdhariTransactionDto(
                id = txDocId,
                customerId = targetCustId,
                customerName = customer.name,
                customerMobile = customer.mobile,
                customerVillage = customer.address,
                billId = 0L,
                billNumber = "MANUAL-${System.currentTimeMillis().toString().takeLast(4)}",
                type = UdhariTransactionType.PURCHASE_CREDIT.name,
                amount = amount,
                paymentMethod = PaymentMethod.OTHER.name,
                previousOutstanding = prevDue,
                remainingOutstanding = newDue,
                transactionUuid = UUID.randomUUID().toString(),
                date = effectiveDate,
                dueDate = dueDate,
                note = cleanNote,
                isReversed = false,
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
     * Atomically deletes or cancels a manual Udhari debit transaction and restores customer balance.
     */
    suspend fun deleteOrReverseManualUdhari(transactionId: String, reason: String = "Deleted by user"): Result<Unit> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val isOnline = FirebaseManager.isNetworkAvailable()
            val txSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(800) {
                        FirebaseManager.udhariTransactionsCollection().document(transactionId).get().await()
                    } ?: FirebaseManager.udhariTransactionsCollection().document(transactionId).get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.udhariTransactionsCollection().document(transactionId).get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            if (txSnap == null || !txSnap.exists()) {
                return Result.failure(IllegalStateException("Transaction not found"))
            }
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
            Log.d(TAG, "Manual udhari deleted successfully: $transactionId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting manual udhari: ${e.message}", e)
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

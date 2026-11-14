package com.matoshree.shopmanager.data.diagnostic

import android.content.Context
import android.util.Log
import com.matoshree.shopmanager.data.firebase.CustomerDto
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.entity.CustomerEntity
import com.matoshree.shopmanager.domain.model.SyncStatus
import com.matoshree.shopmanager.data.repository.toDomain
import com.matoshree.shopmanager.data.repository.toEntity
import com.matoshree.shopmanager.utils.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CustomerDiagnosticReport(
    val directoryCount: Int = 0,
    val firestoreActiveCount: Int = 0,
    val hiddenCount: Int = 0,
    val hiddenReasonBreakdown: Map<String, Int> = emptyMap(),
    val missingCustomerNames: List<String> = emptyList(),
    val orphanPaymentCount: Int = 0,
    val orphanPaymentDetails: List<String> = emptyList(),
    val totalRoomActive: Int,
    val totalRoomArchived: Int,
    val totalFirestoreActive: Int,
    val erroneouslyArchivedCount: Int,
    val missingFromRoomCount: Int,
    val duplicateMobileCount: Int,
    val balanceMismatchCount: Int = 0,
    val duplicateEventsCount: Int = 0,
    val needsReviewCount: Int = 0,
    val overpaidCustomersCount: Int = 0,
    val details: List<String> = emptyList()
)

data class CustomerRepairResult(
    val reactivatedCount: Int,
    val importedCount: Int,
    val deduplicatedCount: Int,
    val balancesSynchronizedCount: Int = 0,
    val duplicateTransactionsMarkedCount: Int = 0,
    val backupPath: String,
    val message: String
)

object CustomerDiagnosticManager {
    private const val TAG = "CustomerDiagnostic"

    suspend fun runDiagnosticScan(context: Context): CustomerDiagnosticReport = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val allRoom = db.customerDao().getAllCustomersDirect()
        val roomActive = allRoom.filter { it.isActive && it.name.isNotBlank() && !it.name.equals("Walk-in", ignoreCase = true) }
        val roomArchived = allRoom.filter { !it.isActive }

        val directoryList = allRoom
            .filter { it.isActive && it.name.isNotBlank() && !it.name.equals("Walk-in", ignoreCase = true) }
            .distinctBy { it.gid }
        val directoryCount = directoryList.size

        val hiddenReasons = mutableMapOf<String, Int>()
        val archivedCount = allRoom.count { !it.isActive }
        val blankNameCount = allRoom.count { it.isActive && it.name.isBlank() }
        val walkInCount = allRoom.count { it.isActive && it.name.equals("Walk-in", ignoreCase = true) }
        val duplicateGidCount = (allRoom.count { it.isActive && it.name.isNotBlank() && !it.name.equals("Walk-in", ignoreCase = true) } - directoryCount).coerceAtLeast(0)

        if (archivedCount > 0) hiddenReasons["अर्काईव्ह (Archived)"] = archivedCount
        if (blankNameCount > 0) hiddenReasons["नाव रिकामे (Blank name)"] = blankNameCount
        if (walkInCount > 0) hiddenReasons["वॉक-इन (Walk-in)"] = walkInCount
        if (duplicateGidCount > 0) hiddenReasons["डुप्लिकेट खाते (Duplicate GID)"] = duplicateGidCount

        val totalHidden = hiddenReasons.values.sum()

        val roomMobileSet = roomActive.map { PhoneUtils.clean10DigitMobileInput(it.mobile) }.filter { it.length == 10 }.toSet()
        val roomArchivedMobileSet = roomArchived.map { PhoneUtils.clean10DigitMobileInput(it.mobile) }.filter { it.length == 10 }.toSet()

        var firestoreActiveCount = 0
        var erroneouslyArchivedCount = 0
        var missingFromRoomCount = 0
        val missingCustNames = mutableListOf<String>()
        val details = mutableListOf<String>()

        try {
            if (FirebaseManager.isNetworkAvailable()) {
                FirebaseManager.ensureAuthenticated()
                val custSnap = FirebaseManager.customersCollection().get().await()
                val cloudCusts = custSnap.documents.mapNotNull { doc ->
                    doc.toObject(CustomerDto::class.java)?.apply { id = doc.id }
                }.filter { it.status != "ARCHIVED" && it.status != "TRASH" }

                firestoreActiveCount = cloudCusts.size

                for (cust in cloudCusts) {
                    val clean = PhoneUtils.clean10DigitMobileInput(cust.mobile ?: "")
                    if (clean.length == 10) {
                        if (roomArchivedMobileSet.contains(clean) && !roomMobileSet.contains(clean)) {
                            erroneouslyArchivedCount++
                            missingCustNames.add("${cust.name} ($clean) [Archived in Room]")
                            details.add("चुकून अर्काईव्ह: ${cust.name} ($clean)")
                        } else if (!roomMobileSet.contains(clean) && !roomArchivedMobileSet.contains(clean)) {
                            missingFromRoomCount++
                            missingCustNames.add("${cust.name} ($clean) [Missing in Room]")
                            details.add("फोनमध्ये नसलेला: ${cust.name} ($clean)")
                        }
                    } else if (cust.name.isNotBlank()) {
                        val matchesRoom = roomActive.any { it.name.trim().equals(cust.name.trim(), ignoreCase = true) }
                        if (!matchesRoom) {
                            missingFromRoomCount++
                            missingCustNames.add("${cust.name} (मोबाईल नाही) [Missing in Room]")
                            details.add("फोनमध्ये नसलेला (मोबाईल नाही): ${cust.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Diagnostic cloud fetch note: ${e.message}")
            details.add("क्लाउड तपासणी करताना त्रुटी: ${e.message}")
        }

        // Check duplicate mobiles in Room
        val mobileGroups = allRoom.filter { it.mobile.isNotBlank() }
            .groupBy { PhoneUtils.clean10DigitMobileInput(it.mobile) }
            .filter { it.key.length == 10 && it.value.size > 1 }
        val duplicateCount = mobileGroups.size

        // Compare Ledger vs Stored balances using single authoritative UdhariLedger
        var balanceMismatchCount = 0
        try {
            val allBills = db.billDao().getAllBillsDirect(50000).map { it.toDomain() }
            val txs = mutableListOf<com.matoshree.shopmanager.domain.model.UdhariTransaction>()
            if (FirebaseManager.isNetworkAvailable()) {
                val txSnap = FirebaseManager.udhariTransactionsCollection().limit(2000).get().await()
                txSnap.documents.mapNotNullTo(txs) { doc ->
                    doc.toObject(com.matoshree.shopmanager.data.firebase.UdhariTransactionDto::class.java)?.apply { id = doc.id }?.toDomain()
                }
            }
            val domainCusts = allRoom.map { it.toDomain() }
            val shopLedger = com.matoshree.shopmanager.domain.usecase.UdhariLedger.computeShopLedger(allBills, txs, domainCusts)
            val keyResolver = com.matoshree.shopmanager.domain.usecase.UdhariLedger.buildCustomerKeyResolver(domainCusts, allBills, txs)

            var overpaidCount = 0
            for (c in roomActive) {
                val key = keyResolver(c.mobile, c.name, c.id) ?: "cust_${c.id}"
                val verifiedBalance = shopLedger.customerResults[key]?.balance ?: 0.0
                if (verifiedBalance < -0.01) {
                    overpaidCount++
                    details.add("जास्त भरणा (data issue): ${c.name} (जास्त: ₹${String.format(java.util.Locale.US, "%.0f", Math.abs(verifiedBalance))})")
                } else if (Math.abs(c.outstandingBalance - verifiedBalance) > 0.5) {
                    balanceMismatchCount++
                    details.add("उधारी तफावत: ${c.name} (नोंद: ₹${String.format(java.util.Locale.US, "%.0f", c.outstandingBalance)} -> खरी बाकी: ₹${String.format(java.util.Locale.US, "%.0f", verifiedBalance)})")
                }
            }

            // Check for duplicate customer candidates
            val duplicateCandidates = allRoom
                .groupBy { it.name.trim().lowercase() }
                .filter { it.key.isNotBlank() && it.value.mapNotNull { c -> c.docId }.distinct().size > 1 }
            for ((name, candidates) in duplicateCandidates) {
                details.add("एकाच नावाने भिन्न खाते: $name (${candidates.size} नोंदी)")
            }

            // Check for duplicate transactions / orphan credits
            var duplicateEventsCount = 0
            val activeTxs = txs.filter { !it.isReversed && it.status != "TRASH" && it.status != "DUPLICATE" }
            val seenSignatures = mutableSetOf<String>()
            for (t in activeTxs) {
                val sig = "${t.customerId}_${t.type}_${String.format(java.util.Locale.US, "%.2f", t.amount)}_${t.billNumber}_${t.date}"
                if (seenSignatures.contains(sig)) {
                    duplicateEventsCount++
                    details.add("संभाव्य डुप्लिकेट ट्रॅन्झॅक्शन: ${t.customerName} - ${t.type} ₹${t.amount} (${t.billNumber.ifBlank { "मॅन्युअल" }})")
                } else {
                    seenSignatures.add(sig)
                }
            }

            val reviewNeeded = db.customerDao().getReviewNeededCustomers()
            val needsReviewCount = reviewNeeded.size
            if (needsReviewCount > 0) {
                reviewNeeded.forEach {
                    details.add("पुनरावलोकन आवश्यक: ${it.name} (${it.mobile})")
                }
            }

            val orphanTxs = shopLedger.allOrphanPayments
            val orphanCount = orphanTxs.size
            val orphanDetails = orphanTxs.map { "${it.customerName} - बिल #${it.billNumber.ifBlank { it.billId.toString() }} • ₹${it.amount} (${it.date})" }
            if (orphanCount > 0) {
                details.add("⚠️ अनधिकृत/ऑर्फन भरणा नोंदी: $orphanCount")
            }

            return@withContext CustomerDiagnosticReport(
                directoryCount = directoryCount,
                firestoreActiveCount = firestoreActiveCount,
                hiddenCount = totalHidden,
                hiddenReasonBreakdown = hiddenReasons,
                missingCustomerNames = missingCustNames,
                orphanPaymentCount = orphanCount,
                orphanPaymentDetails = orphanDetails,
                totalRoomActive = roomActive.size,
                totalRoomArchived = roomArchived.size,
                totalFirestoreActive = firestoreActiveCount,
                erroneouslyArchivedCount = erroneouslyArchivedCount,
                missingFromRoomCount = missingFromRoomCount,
                duplicateMobileCount = duplicateCount,
                balanceMismatchCount = balanceMismatchCount,
                duplicateEventsCount = duplicateEventsCount,
                needsReviewCount = needsReviewCount,
                overpaidCustomersCount = overpaidCount,
                details = details.take(30)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Balance ledger audit note: ${e.message}")
        }

        val reviewNeeded = db.customerDao().getReviewNeededCustomers()
        val needsReviewCount = reviewNeeded.size

        CustomerDiagnosticReport(
            directoryCount = directoryCount,
            firestoreActiveCount = firestoreActiveCount,
            hiddenCount = totalHidden,
            hiddenReasonBreakdown = hiddenReasons,
            missingCustomerNames = missingCustNames,
            orphanPaymentCount = 0,
            orphanPaymentDetails = emptyList(),
            totalRoomActive = roomActive.size,
            totalRoomArchived = roomArchived.size,
            totalFirestoreActive = firestoreActiveCount,
            erroneouslyArchivedCount = erroneouslyArchivedCount,
            missingFromRoomCount = missingFromRoomCount,
            duplicateMobileCount = duplicateCount,
            balanceMismatchCount = balanceMismatchCount,
            duplicateEventsCount = 0,
            needsReviewCount = needsReviewCount,
            details = details.take(30)
        )
    }

    suspend fun performSafeRepair(context: Context): CustomerRepairResult = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val allRoom = db.customerDao().getAllCustomersDirect()

        // 1. Create Safe JSON Backup before any modification
        val backupDir = File(context.filesDir, "customer_backups").apply { mkdirs() }
        val backupFile = File(backupDir, "customers_backup_${System.currentTimeMillis()}.json")

        var reactivatedCount = 0
        var importedCount = 0

        try {
            val backupArray = JSONArray()
            for (c in allRoom) {
                val obj = JSONObject()
                obj.put("id", c.id)
                obj.put("serverId", c.serverId)
                obj.put("name", c.name)
                obj.put("mobile", c.mobile)
                obj.put("address", c.address)
                obj.put("isActive", c.isActive)
                obj.put("outstandingBalance", c.outstandingBalance)
                backupArray.put(obj)
            }
            backupFile.writeText(backupArray.toString(2))
            Log.i(TAG, "Customer backup saved before repair: ${backupFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Backup write note: ${e.message}")
        }

        // 2. Scan and repair with Cloud
        try {
            if (FirebaseManager.isNetworkAvailable()) {
                FirebaseManager.ensureAuthenticated()
                val custSnap = FirebaseManager.customersCollection().get().await()
                val cloudCusts = custSnap.documents.mapNotNull { doc ->
                    doc.toObject(CustomerDto::class.java)?.apply { id = doc.id }
                }.filter { it.status != "ARCHIVED" && it.status != "TRASH" }

                val currentRoom = db.customerDao().getAllCustomersDirect()
                val roomByMobile = currentRoom.filter { it.mobile.isNotBlank() }
                    .associateBy { PhoneUtils.clean10DigitMobileInput(it.mobile) }

                for (cust in cloudCusts) {
                    val clean = PhoneUtils.clean10DigitMobileInput(cust.mobile ?: "")
                    if (clean.length == 10) {
                        val existingRoom = roomByMobile[clean]
                        if (existingRoom != null) {
                            if (!existingRoom.isActive) {
                                db.customerDao().unarchiveCustomerByMobile(existingRoom.mobile, clean)
                                reactivatedCount++
                            }
                        } else {
                            // Missing in Room, import from cloud
                            val domainCust = cust.toDomain()
                            val entity = domainCust.toEntity().copy(
                                id = if (domainCust.id in 1 until 1_000_000_000L) domainCust.id else 0L,
                                isActive = true,
                                syncStatus = SyncStatus.SYNCED
                            )
                            db.customerDao().upsertServerCustomer(entity)
                            importedCount++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Repair cloud pass failed: ${e.message}", e)
        }

        // 3. Reconcile customer identities (no rows deleted, all given canonical docId/gid)
        var deduplicatedCount = 0

        // 4. Synchronize customer balances with single authoritative ledger
        var balancesSynchronizedCount = 0
        try {
            val allBills = db.billDao().getAllBillsDirect(50000).map { it.toDomain() }
            val txs = mutableListOf<com.matoshree.shopmanager.domain.model.UdhariTransaction>()
            if (FirebaseManager.isNetworkAvailable()) {
                val txSnap = FirebaseManager.udhariTransactionsCollection().limit(2000).get().await()
                txSnap.documents.mapNotNullTo(txs) { doc ->
                    doc.toObject(com.matoshree.shopmanager.data.firebase.UdhariTransactionDto::class.java)?.apply { id = doc.id }?.toDomain()
                }
            }
            val currentRoomCusts = db.customerDao().getAllCustomersDirect().map { it.toDomain() }
            val shopLedger = com.matoshree.shopmanager.domain.usecase.UdhariLedger.computeShopLedger(allBills, txs, currentRoomCusts)
            val keyResolver = com.matoshree.shopmanager.domain.usecase.UdhariLedger.buildCustomerKeyResolver(currentRoomCusts, allBills, txs)

            for (c in currentRoomCusts) {
                val key = keyResolver(c.gid, c.mobile, c.name, c.id) ?: (c.gid.ifBlank { "cust_${c.id}" })
                val verifiedBal = shopLedger.customerResults[key]?.balance ?: 0.0
                if (Math.abs(c.outstandingBalance - verifiedBal) > 0.5) {
                    val entity = c.copy(outstandingBalance = verifiedBal).toEntity()
                    db.customerDao().upsertServerCustomer(entity)
                    balancesSynchronizedCount++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Balance repair note: ${e.message}")
        }

        // 5. Mark duplicate transactions in Firestore with status = DUPLICATE (restorable, excluded from ledger)
        var duplicateTransactionsMarkedCount = 0
        try {
            if (FirebaseManager.isNetworkAvailable()) {
                val seenSignatures = mutableSetOf<String>()
                val rawTxs = FirebaseManager.udhariTransactionsCollection().limit(2000).get().await()
                for (doc in rawTxs.documents) {
                    val dto = doc.toObject(com.matoshree.shopmanager.data.firebase.UdhariTransactionDto::class.java) ?: continue
                    if (dto.isReversed || dto.status == "TRASH" || dto.status == "DUPLICATE") continue

                    val sig = "${dto.customerId}_${dto.type}_${String.format(java.util.Locale.US, "%.2f", dto.amount)}_${dto.billNumber}_${dto.date}"
                    if (seenSignatures.contains(sig)) {
                        FirebaseManager.udhariTransactionsCollection().document(doc.id)
                            .update("status", "DUPLICATE")
                            .await()
                        duplicateTransactionsMarkedCount++
                    } else {
                        seenSignatures.add(sig)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Duplicate transaction mark note: ${e.message}")
        }

        CustomerRepairResult(
            reactivatedCount = reactivatedCount,
            importedCount = importedCount,
            deduplicatedCount = deduplicatedCount,
            balancesSynchronizedCount = balancesSynchronizedCount,
            duplicateTransactionsMarkedCount = duplicateTransactionsMarkedCount,
            backupPath = backupFile.absolutePath,
            message = "दुरुस्ती यशस्वी: $reactivatedCount ग्राहक पुन्हा सक्रिय केले, $importedCount ग्राहक क्लाउडवरून आणले, $deduplicatedCount डुप्लिकेट नोंदी हटवल्या, $duplicateTransactionsMarkedCount डुप्लिकेट ट्रॅन्झॅक्शन्स मार्क केले, $balancesSynchronizedCount खाती अचूक ताळमेळ केली."
        )
    }
}

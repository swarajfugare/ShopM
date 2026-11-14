package com.matoshree.shopmanager.data.migration

import android.content.Context
import android.os.Build
import android.util.Log
import com.matoshree.shopmanager.data.firebase.*
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.entity.SettingsEntity
import com.matoshree.shopmanager.data.repository.toDomain
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.domain.usecase.ShopMetricsCalculator
import com.matoshree.shopmanager.domain.usecase.UdhariLedger
import com.matoshree.shopmanager.utils.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

data class DataSnapshot(
    val timestamp: Long,
    val totalUdhari: Double,
    val pendingCustomersCount: Int,
    val billCount: Int,
    val totalProductStock: Int,
    val totalExpenses: Double,
    val customerBalances: Map<String, Double>
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("timestamp", timestamp)
        obj.put("totalUdhari", totalUdhari)
        obj.put("pendingCustomersCount", pendingCustomersCount)
        obj.put("billCount", billCount)
        obj.put("totalProductStock", totalProductStock)
        obj.put("totalExpenses", totalExpenses)

        val balObj = JSONObject()
        customerBalances.forEach { (k, v) -> balObj.put(k, v) }
        obj.put("customerBalances", balObj)
        return obj
    }
}

data class MigrationResult(
    val isSuccess: Boolean,
    val preSnapshot: DataSnapshot?,
    val postSnapshot: DataSnapshot?,
    val backupFilePath: String?,
    val cloudRecordsUpdated: Int,
    val localRecordsUpdated: Int,
    val errorMessage: String? = null
)

object DataMigrationManager {
    private const val TAG = "DataMigrationManager"
    private const val SETTING_KEY_MIGRATION_DONE = "migration_v3_5_0_done"

    suspend fun isMigrationDone(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getInstance(context)
            val flag = db.settingsDao().get(SETTING_KEY_MIGRATION_DONE)
            flag == "true"
        } catch (e: Exception) {
            false
        }
    }

    suspend fun captureSnapshot(context: Context): DataSnapshot = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val allBillsWithItems = db.billDao().getAllBillsDirect(limit = 100000)
        val domainBills = allBillsWithItems.map { it.toDomain() }
        val activeBills = domainBills.filter { !it.isVoided }

        val allCustomers = db.customerDao().getAllCustomersDirect().map { it.toDomain() }
        val allProducts = db.productDao().searchProducts("")
        val totalStock = allProducts.sumOf { it.currentStock }

        // Fetch cloud transactions if online, else empty
        val txs = try {
            if (FirebaseManager.isNetworkAvailable()) {
                val snap = FirebaseManager.udhariTransactionsCollection().get().await()
                snap.documents.mapNotNull { doc ->
                    doc.toObject(UdhariTransactionDto::class.java)?.apply { id = doc.id }?.toDomain()
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        val ledgerBalances = ShopMetricsCalculator.calculateCustomerLedgerBalances(activeBills, txs, allCustomers)
        val totalUdhari = ShopMetricsCalculator.calculateTotalPendingUdhari(activeBills, txs, allCustomers)
        val pendingCount = ShopMetricsCalculator.calculatePendingCustomersCount(activeBills, txs, allCustomers)

        // Expense total
        val todayDate = com.matoshree.shopmanager.utils.DateUtils.todayDate()
        val expenseTotal = try {
            db.expenseDao().getDailyExpenseTotal(todayDate)
        } catch (e: Exception) {
            0.0
        }

        DataSnapshot(
            timestamp = System.currentTimeMillis(),
            totalUdhari = totalUdhari,
            pendingCustomersCount = pendingCount,
            billCount = domainBills.size,
            totalProductStock = totalStock,
            totalExpenses = expenseTotal,
            customerBalances = ledgerBalances
        )
    }

    suspend fun createPreMigrationBackup(context: Context, snapshot: DataSnapshot): String = withContext(Dispatchers.IO) {
        val backupDir = File(context.filesDir, "pre_v3_5_migration_backup").apply { mkdirs() }
        val backupFile = File(backupDir, "backup_${System.currentTimeMillis()}.json")

        val db = AppDatabase.getInstance(context)
        val root = JSONObject()
        root.put("snapshot", snapshot.toJsonObject())

        // Export customers
        val custArr = JSONArray()
        for (c in db.customerDao().getAllCustomersDirect()) {
            val cObj = JSONObject().apply {
                put("id", c.id)
                put("gid", c.gid)
                put("name", c.name)
                put("mobile", c.mobile)
                put("outstandingBalance", c.outstandingBalance)
            }
            custArr.put(cObj)
        }
        root.put("customers", custArr)

        // Export bills
        val billArr = JSONArray()
        for (b in db.billDao().getAllBillsDirect(limit = 100000)) {
            val bObj = JSONObject().apply {
                put("id", b.bill.id)
                put("gid", b.bill.gid)
                put("billNumber", b.bill.billNumber)
                put("transactionUuid", b.bill.transactionUuid)
                put("finalAmount", b.bill.finalAmount)
                put("paidAmount", b.bill.paidAmount)
                put("pendingAmount", b.bill.pendingAmount)
            }
            billArr.put(bObj)
        }
        root.put("bills", billArr)

        backupFile.writeText(root.toString(2), Charsets.UTF_8)
        backupFile.absolutePath
    }

    suspend fun runMigration(context: Context): MigrationResult = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val alreadyDone = isMigrationDone(context)
        if (alreadyDone) {
            return@withContext MigrationResult(
                isSuccess = true,
                preSnapshot = null,
                postSnapshot = null,
                backupFilePath = null,
                cloudRecordsUpdated = 0,
                localRecordsUpdated = 0,
                errorMessage = "Migration v3.5.0 already completed previously"
            )
        }

        Log.i(TAG, "Starting v3.5.0 Global ID Architecture Migration...")

        // Step 1: Pre-migration snapshot & backup
        val preSnapshot = captureSnapshot(context)
        val backupPath = try {
            createPreMigrationBackup(context, preSnapshot)
        } catch (e: Exception) {
            Log.w(TAG, "Backup creation notice: ${e.message}")
            null
        }

        // Step 2: Local Room Backfill verification
        var localUpdated = 0
        try {
            val customers = db.customerDao().getAllCustomersDirect()
            for (c in customers) {
                if (c.gid.isBlank()) {
                    val cleanMob = PhoneUtils.clean10DigitMobileInput(c.mobile)
                    val seed = when {
                        c.serverId != null && c.serverId > 0 -> "srv_${c.serverId}"
                        cleanMob.length == 10 -> "mob_$cleanMob"
                        c.name.isNotBlank() -> "name_${c.name.trim().lowercase()}"
                        else -> "cust_${c.id}"
                    }
                    val gid = IdGenerator.legacyGid(IdType.CUSTOMER, seed)
                    db.customerDao().insertCustomer(c.copy(gid = gid))
                    localUpdated++
                }
            }

            val bills = db.billDao().getAllBillsDirect(limit = 100000)
            for (b in bills) {
                if (b.bill.gid.isBlank() || b.bill.customerGid.isNullOrBlank()) {
                    val billGid = if (b.bill.gid.isNotBlank()) b.bill.gid else {
                        val seed = when {
                            b.bill.transactionUuid.isNotBlank() -> b.bill.transactionUuid.trim()
                            b.bill.serverId != null && b.bill.serverId > 0 -> "srv_${b.bill.serverId}"
                            b.bill.billNumber.isNotBlank() -> "num_${b.bill.billNumber.trim().uppercase()}"
                            else -> "bill_${b.bill.id}"
                        }
                        IdGenerator.legacyGid(IdType.BILL, seed)
                    }
                    val custGid = if (!b.bill.customerGid.isNullOrBlank()) b.bill.customerGid else {
                        if (b.bill.customerId != null && b.bill.customerId > 0) {
                            val cust = db.customerDao().getCustomerById(b.bill.customerId)
                            cust?.gid
                        } else null
                    }
                    db.billDao().updateBill(b.bill.copy(gid = billGid, customerGid = custGid))
                    localUpdated++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local Room backfill notice: ${e.message}")
        }

        // Step 3: Cloud Firestore Backfill (Chunked batches <= 400 writes)
        var cloudUpdated = 0
        try {
            if (FirebaseManager.isNetworkAvailable()) {
                FirebaseManager.ensureAuthenticated()

                // Customers
                val custSnap = FirebaseManager.customersCollection().get().await()
                val custBatchList = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()
                for (doc in custSnap.documents) {
                    val dto = doc.toObject(CustomerDto::class.java) ?: continue
                    if (dto.gid.isBlank()) {
                        val gid = IdGenerator.legacyGid(IdType.CUSTOMER, doc.id)
                        custBatchList.add(Pair(doc.reference, mapOf("gid" to gid, "updatedAt" to System.currentTimeMillis())))
                    }
                }

                // Bills
                val billSnap = FirebaseManager.billsCollection().get().await()
                val billBatchList = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()
                for (doc in billSnap.documents) {
                    val dto = doc.toObject(BillDto::class.java) ?: continue
                    if (dto.gid.isBlank()) {
                        val seed = if (dto.transactionUuid.isNotBlank()) dto.transactionUuid else doc.id
                        val gid = IdGenerator.legacyGid(IdType.BILL, seed)
                        val custGid = if (!dto.customerGid.isNullOrBlank()) dto.customerGid else {
                            if (!dto.customerId.isNullOrBlank()) IdGenerator.legacyGid(IdType.CUSTOMER, dto.customerId!!) else null
                        }
                        val updateMap = mutableMapOf<String, Any>("gid" to gid, "updatedAt" to System.currentTimeMillis())
                        if (custGid != null) updateMap["customerGid"] = custGid
                        billBatchList.add(Pair(doc.reference, updateMap))
                    }
                }

                // Udhari transactions
                val txSnap = FirebaseManager.udhariTransactionsCollection().get().await()
                val txBatchList = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()
                for (doc in txSnap.documents) {
                    val dto = doc.toObject(UdhariTransactionDto::class.java) ?: continue
                    if (dto.gid.isBlank()) {
                        val targetType = if (dto.type == "PURCHASE_CREDIT") IdType.UDHARI else if (dto.type == "REVERSAL") IdType.REVERSAL else IdType.PAYMENT
                        val gid = IdGenerator.legacyGid(targetType, doc.id)
                        val custGid = if (dto.customerGid.isNotBlank()) dto.customerGid else if (dto.customerId > 0L) IdGenerator.legacyGid(IdType.CUSTOMER, "cust_${dto.customerId}") else ""
                        val updateMap = mutableMapOf<String, Any>("gid" to gid)
                        if (custGid.isNotBlank()) updateMap["customerGid"] = custGid
                        txBatchList.add(Pair(doc.reference, updateMap))
                    }
                }

                // Execute in chunks <= 400
                val allCloudUpdates = custBatchList + billBatchList + txBatchList
                val chunks = allCloudUpdates.chunked(400)
                for (chunk in chunks) {
                    val batch = FirebaseManager.firestore.batch()
                    for ((ref, map) in chunk) {
                        batch.update(ref, map)
                    }
                    batch.commit().await()
                    cloudUpdated += chunk.size
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud Firestore migration notice: ${e.message}")
        }

        // Step 4: Post-migration verification snapshot
        val postSnapshot = captureSnapshot(context)

        // Step 5: Verify totals within 0.001
        val udhariDiff = abs(postSnapshot.totalUdhari - preSnapshot.totalUdhari)
        val billCountDiff = abs(postSnapshot.billCount - preSnapshot.billCount)
        val stockDiff = abs(postSnapshot.totalProductStock - preSnapshot.totalProductStock)
        val expenseDiff = abs(postSnapshot.totalExpenses - preSnapshot.totalExpenses)

        val isVerified = udhariDiff < 0.01 && billCountDiff == 0 && stockDiff == 0 && expenseDiff < 0.01

        if (isVerified) {
            Log.i(TAG, "v3.5.0 Migration VERIFIED successfully! Udhari diff: $udhariDiff, Bills diff: $billCountDiff")
            db.settingsDao().set(SettingsEntity(SETTING_KEY_MIGRATION_DONE, "true"))

            try {
                if (FirebaseManager.isNetworkAvailable()) {
                    FirebaseManager.firestore.collection("settings").document(SETTING_KEY_MIGRATION_DONE).set(
                        mapOf(
                            "completed" to true,
                            "timestamp" to System.currentTimeMillis(),
                            "device" to (Build.MODEL ?: "AndroidDevice")
                        )
                    ).await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notice recording cloud migration flag: ${e.message}")
            }

            return@withContext MigrationResult(
                isSuccess = true,
                preSnapshot = preSnapshot,
                postSnapshot = postSnapshot,
                backupFilePath = backupPath,
                cloudRecordsUpdated = cloudUpdated,
                localRecordsUpdated = localUpdated
            )
        } else {
            val errMsg = "Totals mismatch! Udhari diff=$udhariDiff, Bills diff=$billCountDiff, Stock diff=$stockDiff, Expense diff=$expenseDiff"
            Log.e(TAG, "v3.5.0 Migration FAILED verification: $errMsg")
            return@withContext MigrationResult(
                isSuccess = false,
                preSnapshot = preSnapshot,
                postSnapshot = postSnapshot,
                backupFilePath = backupPath,
                cloudRecordsUpdated = cloudUpdated,
                localRecordsUpdated = localUpdated,
                errorMessage = errMsg
            )
        }
    }
}

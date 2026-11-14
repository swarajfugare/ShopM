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
    private const val SETTING_KEY_MIGRATION_DONE = "migration_v3_5_2_repair_done"

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

        // Expense total across ALL dates
        val expenseTotal = try {
            db.expenseDao().getAllExpensesDirect().sumOf { it.amount }
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
                put("docId", c.docId)
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
                put("docId", b.bill.docId)
                put("billNumber", b.bill.billNumber)
                put("transactionUuid", b.bill.transactionUuid)
                put("customerGid", b.bill.customerGid)
                put("finalAmount", b.bill.finalAmount)
                put("paidAmount", b.bill.paidAmount)
                put("pendingAmount", b.bill.pendingAmount)
                val itemsArr = JSONArray()
                for (item in b.items) {
                    itemsArr.put(JSONObject().apply {
                        put("id", item.id)
                        put("gid", item.gid)
                        put("productName", item.productNameSnapshot)
                        put("quantity", item.quantity)
                        put("totalPrice", item.lineTotal)
                    })
                }
                put("items", itemsArr)
            }
            billArr.put(bObj)
        }
        root.put("bills", billArr)

        // Export products
        val prodArr = JSONArray()
        for (p in db.productDao().getAllProductsDirect()) {
            prodArr.put(JSONObject().apply {
                put("id", p.id)
                put("gid", p.gid)
                put("name", p.name)
                put("currentStock", p.currentStock)
                put("sellingPrice", p.sellingPrice)
            })
        }
        root.put("products", prodArr)

        // Export categories
        val catArr = JSONArray()
        for (cat in db.productDao().getAllCategoriesDirect()) {
            catArr.put(JSONObject().apply {
                put("id", cat.id)
                put("gid", cat.gid)
                put("name", cat.name)
            })
        }
        root.put("categories", catArr)

        // Export expenses
        val expArr = JSONArray()
        for (exp in db.expenseDao().getAllExpensesDirect()) {
            expArr.put(JSONObject().apply {
                put("id", exp.id)
                put("gid", exp.gid)
                put("amount", exp.amount)
                put("expenseDate", exp.expenseDate)
                put("note", exp.note)
            })
        }
        root.put("expenses", expArr)

        // Export daily closings
        val closeArr = JSONArray()
        for (cl in db.dailyClosingDao().getAllClosingsDirect()) {
            closeArr.put(JSONObject().apply {
                put("id", cl.id)
                put("gid", cl.gid)
                put("closingDate", cl.closingDate)
                put("actualCash", cl.actualCash)
            })
        }
        root.put("daily_closings", closeArr)

        // Export reminder logs
        val remArr = JSONArray()
        for (rem in db.reminderLogDao().getRecentLogsDirect(200)) {
            remArr.put(JSONObject().apply {
                put("id", rem.id)
                put("customerGid", rem.customerGid)
                put("timestamp", rem.timestamp)
                put("type", rem.type)
            })
        }
        root.put("reminder_logs", remArr)

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
                errorMessage = "Migration v3.5.1 repair already completed previously"
            )
        }

        // Fresh empty install check
        val isFreshInstall = db.customerDao().getAllCustomersDirect().isEmpty() &&
                db.billDao().getAllBillsDirect(limit = 1).isEmpty()
        if (isFreshInstall) {
            Log.i(TAG, "Fresh empty install detected. Marking v3.5.1 migration done without operations.")
            db.settingsDao().set(SettingsEntity(SETTING_KEY_MIGRATION_DONE, "true"))
            return@withContext MigrationResult(
                isSuccess = true,
                preSnapshot = null,
                postSnapshot = null,
                backupFilePath = null,
                cloudRecordsUpdated = 0,
                localRecordsUpdated = 0,
                errorMessage = "Fresh install - migration not needed"
            )
        }

        // Kill switch check (local and remote)
        val localKillSwitch = db.settingsDao().get("migration.enabled")
        if (localKillSwitch == "false") {
            Log.w(TAG, "Migration skipped: disabled by local kill switch (migration.enabled = false)")
            return@withContext MigrationResult(
                isSuccess = false,
                preSnapshot = null,
                postSnapshot = null,
                backupFilePath = null,
                cloudRecordsUpdated = 0,
                localRecordsUpdated = 0,
                errorMessage = "Migration disabled via local kill switch"
            )
        }

        if (FirebaseManager.isNetworkAvailable()) {
            try {
                val remoteDoc = FirebaseManager.firestore.collection("settings").document("migration.enabled").get().await()
                if (remoteDoc.exists() && (remoteDoc.getBoolean("enabled") == false || remoteDoc.getString("enabled") == "false")) {
                    Log.w(TAG, "Migration skipped: disabled by remote kill switch (settings/migration.enabled = false)")
                    return@withContext MigrationResult(
                        isSuccess = false,
                        preSnapshot = null,
                        postSnapshot = null,
                        backupFilePath = null,
                        cloudRecordsUpdated = 0,
                        localRecordsUpdated = 0,
                        errorMessage = "Migration disabled via remote kill switch"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Remote kill switch check notice: ${e.message}")
            }
        }

        Log.i(TAG, "Starting v3.5.1 Global ID Architecture Repair...")

        // Step 1: Pre-migration snapshot & backup
        val preSnapshot = captureSnapshot(context)
        val backupPath = try {
            createPreMigrationBackup(context, preSnapshot)
        } catch (e: Exception) {
            Log.w(TAG, "Backup creation notice: ${e.message}")
            null
        }

        // Step 2: Local Room Repair verification & Customer Deduplication
        var localUpdated = 0
        try {
            val customers = db.customerDao().getAllCustomersDirect()
            for (c in customers) {
                val cleanMob = PhoneUtils.clean10DigitMobileInput(c.mobile)
                val resolvedDocId = if (cleanMob.length == 10) "cust_m_$cleanMob" else (c.docId ?: c.serverId?.toString())
                val targetGid = when {
                    !resolvedDocId.isNullOrBlank() -> IdGenerator.customerGid(resolvedDocId)
                    c.serverId != null && c.serverId > 0 -> IdGenerator.customerGid(c.serverId.toString())
                    c.gid.isNotBlank() && IdFormat.isValid(c.gid) -> c.gid
                    else -> IdGenerator.newCustomerGid()
                }
                if (c.gid != targetGid || c.docId != resolvedDocId) {
                    db.customerDao().updateCustomer(c.copy(gid = targetGid, docId = resolvedDocId))
                    localUpdated++
                }
            }

            // Deduplicate duplicate customer rows in Room
            try {
                db.customerDao().deduplicateCustomerRows()
            } catch (e: Exception) {
                Log.w(TAG, "Deduplicate in migration notice: ${e.message}")
            }

            val bills = db.billDao().getAllBillsDirect(limit = 100000)
            for (b in bills) {
                val targetGid = when {
                    !b.bill.docId.isNullOrBlank() -> IdGenerator.billGid(b.bill.docId)
                    b.bill.transactionUuid.isNotBlank() -> IdGenerator.legacyGid(IdType.BILL, b.bill.transactionUuid)
                    b.bill.gid.isNotBlank() && IdFormat.isValid(b.bill.gid) -> b.bill.gid
                    else -> IdGenerator.newBillGid()
                }
                val custGid = if (!b.bill.customerGid.isNullOrBlank()) {
                    val cust = db.customerDao().getCustomerByGid(b.bill.customerGid)
                    cust?.gid ?: b.bill.customerGid
                } else if (b.bill.customerId != null && b.bill.customerId > 0) {
                    val cust = db.customerDao().getCustomerById(b.bill.customerId)
                    cust?.gid
                } else null

                if (b.bill.gid != targetGid || b.bill.customerGid != custGid) {
                    db.billDao().updateBill(b.bill.copy(gid = targetGid, customerGid = custGid))
                    localUpdated++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local Room repair notice: ${e.message}")
        }

        // Step 3: Cloud Firestore Backfill / Repair (Chunked batches <= 400 writes)
        var cloudUpdated = 0
        var cloudSuccess = false
        try {
            if (FirebaseManager.isNetworkAvailable()) {
                FirebaseManager.ensureAuthenticated()

                val allCloudUpdates = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()

                // Customers
                val custSnap = FirebaseManager.customersCollection().get().await()
                val customerDocIdToGid = mutableMapOf<String, String>()
                for (doc in custSnap.documents) {
                    val dto = doc.toObject(CustomerDto::class.java) ?: continue
                    val targetGid = IdGenerator.customerGid(doc.id)
                    customerDocIdToGid[doc.id] = targetGid
                    if (dto.gid != targetGid) {
                        allCloudUpdates.add(Pair(doc.reference, mapOf(
                            "gid" to targetGid,
                            "updatedAt" to System.currentTimeMillis()
                        )))
                    }
                }

                // Bills
                val billSnap = FirebaseManager.billsCollection().get().await()
                for (doc in billSnap.documents) {
                    val dto = doc.toObject(BillDto::class.java) ?: continue
                    val targetGid = IdGenerator.billGid(doc.id)
                    val targetCustGid = if (!dto.customerId.isNullOrBlank()) {
                        customerDocIdToGid[dto.customerId] ?: IdGenerator.customerGid(dto.customerId!!)
                    } else dto.customerGid

                    val updateMap = mutableMapOf<String, Any>()
                    if (dto.gid != targetGid) updateMap["gid"] = targetGid
                    if (!targetCustGid.isNullOrBlank() && dto.customerGid != targetCustGid) updateMap["customerGid"] = targetCustGid
                    if (updateMap.isNotEmpty()) {
                        updateMap["updatedAt"] = System.currentTimeMillis()
                        allCloudUpdates.add(Pair(doc.reference, updateMap))
                    }
                }

                // Udhari transactions
                val txSnap = FirebaseManager.udhariTransactionsCollection().get().await()
                for (doc in txSnap.documents) {
                    val dto = doc.toObject(UdhariTransactionDto::class.java) ?: continue
                    val targetType = when (dto.type) {
                        "PURCHASE_CREDIT" -> IdType.UDHARI
                        "REVERSAL" -> IdType.REVERSAL
                        else -> IdType.PAYMENT
                    }
                    val targetGid = IdGenerator.legacyGid(targetType, doc.id)
                    val updateMap = mutableMapOf<String, Any>()
                    if (dto.gid != targetGid) updateMap["gid"] = targetGid
                    if (dto.customerGid.isNotBlank() && dto.customerGid.startsWith("cus_Lcust_") && dto.customerId > 0) {
                        // Re-link to canonical customer GID if known
                        val custGid = customerDocIdToGid[dto.customerId.toString()]
                        if (custGid != null) updateMap["customerGid"] = custGid
                    }
                    if (updateMap.isNotEmpty()) {
                        allCloudUpdates.add(Pair(doc.reference, updateMap))
                    }
                }

                // Products
                val prodSnap = FirebaseManager.productsCollection().get().await()
                for (doc in prodSnap.documents) {
                    val dto = doc.toObject(ProductDto::class.java) ?: continue
                    val targetGid = IdGenerator.productGid(doc.id)
                    if (dto.gid != targetGid) {
                        allCloudUpdates.add(Pair(doc.reference, mapOf(
                            "gid" to targetGid,
                            "updatedAt" to System.currentTimeMillis()
                        )))
                    }
                }

                // Categories
                val catSnap = FirebaseManager.categoriesCollection().get().await()
                for (doc in catSnap.documents) {
                    val dto = doc.toObject(CategoryDto::class.java) ?: continue
                    val targetGid = IdGenerator.categoryGid(doc.id)
                    if (dto.gid != targetGid) {
                        allCloudUpdates.add(Pair(doc.reference, mapOf(
                            "gid" to targetGid,
                            "updatedAt" to System.currentTimeMillis()
                        )))
                    }
                }

                // Expenses
                val expSnap = FirebaseManager.expensesCollection().get().await()
                for (doc in expSnap.documents) {
                    val dto = doc.toObject(ExpenseDto::class.java) ?: continue
                    val targetGid = IdGenerator.expenseGid(doc.id)
                    if (dto.gid != targetGid) {
                        allCloudUpdates.add(Pair(doc.reference, mapOf(
                            "gid" to targetGid,
                            "updatedAt" to System.currentTimeMillis()
                        )))
                    }
                }

                // Daily Closings
                val closeSnap = FirebaseManager.dailyClosingsCollection().get().await()
                for (doc in closeSnap.documents) {
                    val dto = doc.toObject(DailyClosingDto::class.java) ?: continue
                    val targetGid = IdGenerator.dailyClosingGid(doc.id)
                    if (dto.gid != targetGid) {
                        allCloudUpdates.add(Pair(doc.reference, mapOf(
                            "gid" to targetGid,
                            "updatedAt" to System.currentTimeMillis()
                        )))
                    }
                }

                // Stock Movements
                val stkSnap = FirebaseManager.stockMovementsCollection().get().await()
                for (doc in stkSnap.documents) {
                    val dto = doc.toObject(StockMovementDto::class.java) ?: continue
                    val targetGid = IdGenerator.stockMovementGid(doc.id)
                    if (dto.gid != targetGid) {
                        allCloudUpdates.add(Pair(doc.reference, mapOf(
                            "gid" to targetGid,
                            "timestamp" to System.currentTimeMillis()
                        )))
                    }
                }

                // Execute in chunks <= 400
                val chunks = allCloudUpdates.chunked(400)
                for (chunk in chunks) {
                    val batch = FirebaseManager.firestore.batch()
                    for ((ref, map) in chunk) {
                        batch.update(ref, map)
                    }
                    batch.commit().await()
                    cloudUpdated += chunk.size
                }
                cloudSuccess = true
            } else {
                Log.i(TAG, "Offline - cloud repair postponed to next sync")
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
            Log.i(TAG, "v3.5.2 Migration VERIFIED successfully! Udhari diff: $udhariDiff, Bills diff: $billCountDiff, Expense diff: $expenseDiff")
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
            Log.e(TAG, "v3.5.1 Migration FAILED verification: $errMsg")
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

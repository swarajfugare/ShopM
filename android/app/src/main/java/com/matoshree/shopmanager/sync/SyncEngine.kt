package com.matoshree.shopmanager.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.work.*
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.entity.*
import com.matoshree.shopmanager.data.remote.ApiClient
import com.matoshree.shopmanager.security.TokenStorage
import com.matoshree.shopmanager.data.remote.api.MatoshreeApiService
import com.matoshree.shopmanager.data.remote.dto.*
import com.matoshree.shopmanager.data.repository.SyncRepository
import com.matoshree.shopmanager.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class AppSyncState {
    SYNCED,
    SYNCING,
    OFFLINE,
    PENDING
}

class NetworkMonitor(context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _isOnline = MutableStateFlow(checkInitialState())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnline.value = true
                }

                override fun onLost(network: Network) {
                    _isOnline.value = false
                }
            })
        } catch (e: Exception) {
            _isOnline.value = true
        }
    }

    fun checkInitialState(): Boolean {
        return try {
            val active = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }
}

class SyncManager(
    private val context: Context,
    private val database: AppDatabase,
    private val apiService: MatoshreeApiService
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("matoshree_sync_prefs", Context.MODE_PRIVATE)
    private val networkMonitor = NetworkMonitor(context)
    private val tokenStorage = TokenStorage(context)

    private val _syncState = MutableStateFlow(AppSyncState.SYNCED)
    val syncState: StateFlow<AppSyncState> = _syncState.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow(prefs.getLong(PREF_LAST_SYNC_TIME, 0L))
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    private val isSyncInProgress = AtomicBoolean(false)
    private var foregroundPollingJob: Job? = null
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val deviceId: String by lazy {
        var id = prefs.getString(PREF_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = "PHONE-" + UUID.randomUUID().toString().take(8).uppercase()
            prefs.edit().putString(PREF_DEVICE_ID, id).apply()
        }
        id
    }

    var lastSyncCursor: Long
        get() = prefs.getLong(PREF_SYNC_CURSOR, 0L)
        private set(value) = prefs.edit().putLong(PREF_SYNC_CURSOR, value).apply()

    val pendingSyncCount: Flow<Int> = kotlinx.coroutines.flow.combine(
        database.billDao().getPendingBillsCount(),
        database.customerDao().getPendingCustomersCount(),
        database.syncQueueDao().getPendingCount()
    ) { b, c, q -> b + c + q }

    init {
        // Automatically sync pending offline records when device comes back online
        syncScope.launch {
            networkMonitor.isOnline.collectLatest { online ->
                if (online) {
                    triggerImmediateSync()
                } else {
                    _syncState.value = AppSyncState.OFFLINE
                }
            }
        }

        // Register device on startup
        syncScope.launch {
            try {
                apiService.registerDevice(
                    RegisterDeviceRequest(
                        device_id = deviceId,
                        device_name = "Matoshree Android Terminal ($deviceId)",
                        app_version = "1.0.0"
                    )
                )
            } catch (e: Exception) {
                Log.d("SyncManager", "Device registration note: ${e.message}")
            }
        }
    }

    /**
     * Calms sync state on foreground resume.
     * Uses Cloud Firestore realtime snapshot listeners for instant updates and only flushes pending items on demand.
     */
    fun startForegroundPolling(scope: CoroutineScope = syncScope) {
        if (foregroundPollingJob?.isActive == true) return

        foregroundPollingJob = scope.launch {
            Log.d("SyncManager", "[SYNC] Resuming foreground state for device $deviceId")
            try {
                if (networkMonitor.isOnline.value) {
                    val hasPending = try {
                        database.billDao().getPendingSyncBills().isNotEmpty() ||
                            database.customerDao().getPendingSyncCustomers().isNotEmpty() ||
                            database.syncQueueDao().getPendingCountDirect() > 0
                    } catch (_: Exception) { false }

                    if (hasPending) {
                        performDeltaSync()
                    } else {
                        _syncState.value = AppSyncState.SYNCED
                    }
                } else {
                    _syncState.value = AppSyncState.OFFLINE
                }
            } catch (e: Exception) {
                Log.w("SyncManager", "[SYNC] Foreground check notice: ${e.message}")
            }
        }
    }

    /**
     * Stops aggressive foreground polling when application goes to background.
     */
    fun stopForegroundPolling() {
        Log.d("SyncManager", "[SYNC] Stopping foreground polling loop")
        foregroundPollingJob?.cancel()
        foregroundPollingJob = null
    }

    /**
     * Triggers an immediate sync cycle (e.g. on manual refresh or after a new transaction).
     */
    fun triggerImmediateSync() {
        syncScope.launch {
            performDeltaSync()
        }
    }

    /**
     * Performs a complete delta or initial synchronization cycle with Hostinger backend.
     */
    suspend fun performDeltaSync(): Boolean = withContext(Dispatchers.IO) {
        if (!networkMonitor.checkInitialState()) {
            _syncState.value = AppSyncState.OFFLINE
            return@withContext false
        }

        if (!isSyncInProgress.compareAndSet(false, true)) {
            return@withContext true // Already syncing
        }

        _syncState.value = AppSyncState.SYNCING

        try {
            // Upload pending offline Firestore records (Bills & Customers)
            try {
                val syncRepo = SyncRepository(database)
                syncRepo.performSync(deviceId)
            } catch (fe: Exception) {
                Log.w("SyncManager", "Firestore offline sync note: ${fe.message}")
            }

            // 1. Ensure valid auth token
            ensureAuthenticated()

            // 2. Upload any local pending offline queue items first
            flushPendingUploads()

            // 3. Request server delta changes since last cursor
            val currentCursor = lastSyncCursor
            var response = apiService.getSyncChanges(cursor = currentCursor, limit = 200, deviceId = deviceId)

            // Auto-refresh token if 401 Unauthorized
            if (response.code() == 401) {
                Log.d("SyncManager", "[SYNC] Token expired (401). Re-authenticating...")
                tokenStorage.clear()
                ensureAuthenticated()
                response = apiService.getSyncChanges(cursor = currentCursor, limit = 200, deviceId = deviceId)
            }

            if (response.isSuccessful && response.body()?.data != null) {
                val data = response.body()!!.data!!
                applyServerChangesToRoom(data)

                // Update cursor and timestamps
                if (data.cursor > 0) {
                    lastSyncCursor = data.cursor
                }
                val now = System.currentTimeMillis()
                _lastSyncTimestamp.value = now
                prefs.edit().putLong(PREF_LAST_SYNC_TIME, now).apply()

                _syncState.value = AppSyncState.SYNCED
                true
            } else {
                Log.w("SyncManager", "[SYNC] Sync changes error: HTTP ${response.code()}")
                _syncState.value = AppSyncState.SYNCED
                false
            }
        } catch (e: Exception) {
            Log.w("SyncManager", "[SYNC] Network/sync exception: ${e.message}")
            _syncState.value = if (networkMonitor.checkInitialState()) AppSyncState.SYNCED else AppSyncState.OFFLINE
            false
        } finally {
            isSyncInProgress.set(false)
        }
    }

    private suspend fun ensureAuthenticated() {
        if (tokenStorage.getToken().isNullOrBlank()) {
            try {
                val loginRes = apiService.login(LoginRequest(mobile = "9876543210", pin = "1234"))
                if (loginRes.isSuccessful && loginRes.body()?.data != null) {
                    val jwt = loginRes.body()!!.data!!.token
                    tokenStorage.saveToken(jwt)
                    Log.d("SyncManager", "[SYNC] Authenticated successfully with backend")
                }
            } catch (e: Exception) {
                Log.w("SyncManager", "[SYNC] Auto-login error: ${e.message}")
            }
        }
    }

    private suspend fun flushPendingUploads() {
        val pending = database.syncQueueDao().getPendingItems()
        if (pending.isEmpty()) return

        val syncItems = pending.mapNotNull { item ->
            try {
                val jsonElement = Json.parseToJsonElement(item.payloadJson).jsonObject
                SyncItemDto(
                    transaction_uuid = item.transactionUuid,
                    entity_type = item.entityType,
                    operation = item.operation,
                    payload = jsonElement
                )
            } catch (e: Exception) {
                null
            }
        }

        if (syncItems.isEmpty()) return

        try {
            val response = apiService.syncBatch(
                BatchSyncRequest(
                    device_id = deviceId,
                    sync_items = syncItems
                )
            )

            if (response.isSuccessful && response.body()?.data != null) {
                val results = response.body()!!.data!!.results
                for (res in results) {
                    if (res.status == "SUCCESS" || res.status == "DUPLICATE") {
                        val pendingItem = pending.find { it.transactionUuid == res.transaction_uuid }
                        if (pendingItem != null && res.server_id != null) {
                            try {
                                val json = Json.parseToJsonElement(pendingItem.payloadJson).jsonObject
                                val localIdStr = json["local_id"]?.toString()?.replace("\"", "")
                                val localId = localIdStr?.toLongOrNull()
                                when (pendingItem.entityType.uppercase()) {
                                    "CUSTOMER" -> {
                                        if (localId != null) {
                                            database.customerDao().updateSyncStatus(localId, res.server_id, SyncStatus.SYNCED)
                                        }
                                    }
                                    "PRODUCT" -> {
                                        if (localId != null) {
                                            database.productDao().updateServerId(localId, res.server_id)
                                        }
                                    }
                                    "CATEGORY" -> {
                                        if (localId != null) {
                                            database.productDao().updateCategoryServerId(localId, res.server_id)
                                        }
                                    }
                                    "SALE", "BILL" -> {
                                        database.billDao().updateSyncStatus(res.transaction_uuid, SyncStatus.SYNCED, res.server_id)
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                        database.syncQueueDao().deleteByUuid(res.transaction_uuid)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("SyncManager", "[SYNC] Pending flush error: ${e.message}")
        }
    }

    private suspend fun applyServerChangesToRoom(data: SyncChangesData) {
        // 1. Categories
        for (cat in data.categories) {
            val entity = CategoryEntity(
                id = 0,
                serverId = cat.id,
                shopId = cat.shop_id,
                name = cat.name,
                description = cat.description,
                isActive = true
            )
            database.productDao().upsertServerCategory(entity)
        }

        // 2. Products
        for (prod in data.products) {
            val entity = ProductEntity(
                id = 0,
                serverId = prod.id,
                shopId = prod.shop_id,
                categoryId = prod.category_id,
                name = prod.name,
                sku = prod.sku,
                sellingPrice = prod.selling_price,
                costPrice = prod.cost_price,
                defaultProfitMargin = 25.0,
                trackInventory = false,
                currentStock = prod.current_stock,
                isActive = prod.is_active == 1
            )
            database.productDao().upsertServerProduct(entity)
        }

        // 3. Customers
        for (cust in data.customers) {
            val entity = CustomerEntity(
                id = 0,
                serverId = cust.id,
                shopId = cust.shop_id,
                name = cust.name,
                mobile = cust.mobile,
                email = cust.email,
                address = cust.address,
                totalBills = cust.total_bills,
                lifetimeSpend = cust.lifetime_spend,
                isActive = cust.is_active == 1,
                syncStatus = SyncStatus.SYNCED
            )
            database.customerDao().upsertServerCustomer(entity)
        }

        // 4. Bills & Items
        for (bill in data.bills) {
            val isVoided = bill.is_voided == 1 || bill.payment_status.uppercase() == "VOID"
            val saleType = try {
                SaleType.valueOf(bill.sale_type.uppercase())
            } catch (e: Exception) {
                SaleType.DETAILED
            }
            val payMethod = try {
                PaymentMethod.valueOf(bill.payment_method.uppercase())
            } catch (e: Exception) {
                PaymentMethod.CASH
            }
            val payStatus = if (isVoided) PaymentStatus.VOID else PaymentStatus.PAID

            val billEntity = BillEntity(
                id = 0,
                serverId = bill.id,
                shopId = bill.shop_id,
                customerId = bill.customer_id,
                customerName = bill.customer_name,
                customerMobile = bill.customer_mobile,
                billNumber = bill.bill_number,
                transactionUuid = bill.transaction_uuid,
                saleType = saleType,
                subtotal = bill.subtotal,
                discountAmount = bill.discount_amount,
                finalAmount = bill.final_amount,
                costAmount = bill.cost_amount,
                estimatedProfit = bill.estimated_profit,
                actualProfit = bill.actual_profit,
                profitType = if (bill.profit_type == "ACTUAL") ProfitType.ACTUAL else ProfitType.ESTIMATED,
                paymentMethod = payMethod,
                paymentStatus = payStatus,
                note = bill.note,
                billDate = bill.bill_date,
                shopNameSnapshot = bill.shop_name_snapshot ?: "Matoshree Collection",
                shopAddressSnapshot = bill.shop_address_snapshot,
                shopMobileSnapshot = bill.shop_mobile_snapshot,
                shopGstinSnapshot = bill.shop_gstin_snapshot,
                showGstinSnapshot = bill.show_gstin_snapshot == 1,
                syncStatus = SyncStatus.SYNCED,
                isVoided = isVoided,
                voidReason = bill.void_reason
            )

            val itemEntities = bill.items.map { it ->
                BillItemEntity(
                    id = 0,
                    billId = 0,
                    productId = it.product_id,
                    productNameSnapshot = it.product_name_snapshot ?: "Item",
                    skuSnapshot = it.sku_snapshot,
                    quantity = it.quantity,
                    sellingPrice = it.selling_price,
                    costPrice = it.cost_price,
                    discountAmount = it.discount_amount,
                    lineTotal = it.line_total,
                    lineProfit = it.line_profit
                )
            }

            database.billDao().upsertServerBill(billEntity, itemEntities)
        }

        // 5. Expenses
        for (exp in data.expenses) {
            val payMethod = try {
                PaymentMethod.valueOf(exp.payment_method.uppercase())
            } catch (e: Exception) {
                PaymentMethod.CASH
            }
            val txUuid = "exp-server-${exp.id}"
            val expEntity = ExpenseEntity(
                id = 0,
                serverId = exp.id,
                shopId = exp.shop_id,
                transactionUuid = txUuid,
                category = exp.category,
                amount = exp.amount,
                paymentMethod = payMethod,
                expenseDate = exp.expense_date,
                note = exp.note,
                syncStatus = SyncStatus.SYNCED
            )
            database.expenseDao().upsertServerExpense(expEntity)
        }

        // 6. Daily Closings
        for (close in data.daily_closings) {
            val closeEntity = DailyClosingEntity(
                id = 0,
                serverId = close.id,
                shopId = 1,
                closingDate = close.closing_date,
                totalSales = close.total_sales,
                totalBills = close.total_bills,
                cashSales = close.cash_sales,
                upiSales = close.upi_sales,
                grossProfit = close.gross_profit,
                expectedCash = close.expected_cash,
                actualCash = close.actual_cash,
                cashDifference = close.cash_difference,
                notes = close.notes,
                isClosed = close.is_closed == 1,
                syncStatus = SyncStatus.SYNCED
            )
            database.dailyClosingDao().upsertServerClosing(closeEntity)
        }

        // 7. Settings
        data.settings?.let { s ->
            s.name?.let { database.settingsDao().set(SettingsEntity("shop_name", it)) }
            s.address?.let { database.settingsDao().set(SettingsEntity("shop_address", it)) }
            s.mobile?.let { database.settingsDao().set(SettingsEntity("shop_mobile", it)) }
            s.gst_number?.let { database.settingsDao().set(SettingsEntity("shop_gstin", it)) }
            database.settingsDao().set(SettingsEntity("show_gstin", if (s.show_gstin == 1) "true" else "false"))
            s.upi_id?.let { database.settingsDao().set(SettingsEntity("upi_id", it)) }
            s.upi_display_name?.let { database.settingsDao().set(SettingsEntity("upi_display_name", it)) }
            s.logo_data?.let { logoData ->
                database.settingsDao().set(SettingsEntity("shop_logo_data", logoData))
                if (logoData.isNotBlank()) {
                    try {
                        val base64Clean = if (logoData.contains(",")) logoData.substringAfter(",") else logoData
                        val bytes = android.util.Base64.decode(base64Clean, android.util.Base64.DEFAULT)
                        val logoFile = java.io.File(context.filesDir, "shop_logo.png")
                        val fos = java.io.FileOutputStream(logoFile)
                        fos.write(bytes)
                        fos.flush()
                        fos.close()
                        database.settingsDao().set(SettingsEntity("logo_path", logoFile.absolutePath))
                    } catch (e: Exception) {
                        Log.w("SyncManager", "[SYNC] Failed to write logo file: ${e.message}")
                    }
                }
            }
        }

        // 8. Process Tombstones / Deletions / Voids explicitly
        for (del in data.deletions) {
            when (del.entity_type.uppercase()) {
                "CUSTOMER" -> {
                    val cust = database.customerDao().getCustomerByServerId(del.entity_id)
                    if (cust != null) {
                        database.customerDao().archiveCustomer(cust.id)
                    }
                }
                "PRODUCT" -> {
                    val prod = database.productDao().getProductByServerId(del.entity_id)
                    if (prod != null) {
                        database.productDao().archiveProduct(prod.id)
                    }
                }
                "BILL" -> {
                    if (del.operation.uppercase() == "VOID") {
                        val billWithItems = database.billDao().getBillByServerId(del.entity_id)
                        if (billWithItems != null) {
                            database.billDao().voidBill(billWithItems.bill.id, "Voided from server")
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_SYNC_CURSOR = "last_sync_cursor"
        private const val PREF_LAST_SYNC_TIME = "last_sync_timestamp"
        private const val PERIODIC_SYNC_TAG = "matoshree_periodic_sync"

        @Volatile
        private var INSTANCE: SyncManager? = null

        fun getInstance(context: Context, database: AppDatabase, apiService: MatoshreeApiService): SyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncManager(context.applicationContext, database, apiService).also { INSTANCE = it }
            }
        }

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_SYNC_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }

        fun triggerOneTimeSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(oneTimeRequest)
        }
    }
}

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getInstance(applicationContext)
        val apiService = ApiClient.create(applicationContext)
        val syncManager = SyncManager.getInstance(applicationContext, database, apiService)

        val success = syncManager.performDeltaSync()
        return if (success) Result.success() else Result.retry()
    }
}

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
import com.matoshree.shopmanager.data.repository.SyncRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class AppSyncState {
    SYNCED,
    SYNCING,
    OFFLINE,
    ERROR
}

class NetworkMonitor(context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _isOnline = MutableStateFlow(checkInitialState())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnline.value = true
            }

            override fun onLost(network: Network) {
                _isOnline.value = checkInitialState()
            }
        })
    }

    fun checkInitialState(): Boolean {
        return try {
            val active = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Authoritative Offline-First Cloud Firestore Synchronization Manager.
 * Syncs pending local Room database bills and customers with Cloud Firestore,
 * downloads missing records, and manages background scheduled workers.
 */
class SyncManager(
    private val context: Context,
    private val database: AppDatabase
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("matoshree_sync_prefs", Context.MODE_PRIVATE)
    private val networkMonitor = NetworkMonitor(context)

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

    val pendingSyncCount: Flow<Int> = kotlinx.coroutines.flow.combine(
        database.billDao().getPendingBillsCount(),
        database.customerDao().getPendingCustomersCount()
    ) { b, c -> b + c }

    init {
        syncScope.launch {
            networkMonitor.isOnline.collectLatest { online ->
                if (online) {
                    triggerImmediateSync()
                } else {
                    _syncState.value = AppSyncState.OFFLINE
                }
            }
        }
    }

    fun startForegroundPolling(scope: CoroutineScope = syncScope) {
        if (foregroundPollingJob?.isActive == true) return

        foregroundPollingJob = scope.launch {
            try {
                if (networkMonitor.isOnline.value) {
                    val hasPending = try {
                        database.billDao().getPendingSyncBills().isNotEmpty() ||
                            database.customerDao().getPendingSyncCustomers().isNotEmpty()
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

    fun stopForegroundPolling() {
        foregroundPollingJob?.cancel()
        foregroundPollingJob = null
    }

    fun triggerImmediateSync() {
        syncScope.launch {
            performDeltaSync()
        }
    }

    suspend fun performDeltaSync(): Boolean = performSync()

    suspend fun performSync(): Boolean = withContext(Dispatchers.IO) {
        if (!networkMonitor.checkInitialState()) {
            _syncState.value = AppSyncState.OFFLINE
            return@withContext false
        }

        if (!isSyncInProgress.compareAndSet(false, true)) {
            return@withContext true
        }

        _syncState.value = AppSyncState.SYNCING

        try {
            val syncRepo = SyncRepository(database)
            val success = syncRepo.performSync(deviceId)
            val now = System.currentTimeMillis()
            _lastSyncTimestamp.value = now
            prefs.edit().putLong(PREF_LAST_SYNC_TIME, now).apply()
            _syncState.value = AppSyncState.SYNCED
            success
        } catch (e: Exception) {
            Log.w("SyncManager", "[SYNC] Firestore sync notice: ${e.message}")
            _syncState.value = if (networkMonitor.checkInitialState()) AppSyncState.SYNCED else AppSyncState.OFFLINE
            false
        } finally {
            isSyncInProgress.set(false)
        }
    }

    companion object {
        private const val PREF_LAST_SYNC_TIME = "last_sync_timestamp"
        private const val PREF_DEVICE_ID = "sync_device_id"
        private const val PERIODIC_SYNC_TAG = "matoshree_periodic_sync_worker"

        @Volatile
        private var INSTANCE: SyncManager? = null

        fun getInstance(context: Context, database: AppDatabase): SyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SyncManager(context.applicationContext, database).also { INSTANCE = it }
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
        val syncManager = SyncManager.getInstance(applicationContext, database)
        val success = syncManager.performSync()
        return if (success) Result.success() else Result.retry()
    }
}

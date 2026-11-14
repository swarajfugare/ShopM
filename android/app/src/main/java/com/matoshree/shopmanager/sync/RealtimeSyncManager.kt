package com.matoshree.shopmanager.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.matoshree.shopmanager.data.firebase.*
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.entity.BillEntity
import com.matoshree.shopmanager.data.local.entity.BillItemEntity
import com.matoshree.shopmanager.data.local.entity.CustomerEntity
import com.matoshree.shopmanager.data.repository.toBillEntities
import com.matoshree.shopmanager.data.repository.toEntity
import com.matoshree.shopmanager.domain.model.UdhariTransaction
import com.matoshree.shopmanager.domain.model.UdhariTransactionType
import com.matoshree.shopmanager.notifications.ShopNotificationManager
import com.matoshree.shopmanager.utils.PhoneUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardened Single Application-Scoped Realtime Synchronization Engine.
 *
 * Guarantees:
 * 1. UI observes Room ONLY. Screens never attach independent Firestore listeners.
 * 2. Snapshots are processed strictly SEQUENTIALLY via an in-order Channel (zero concurrency races).
 * 3. Never skips whole-snapshot on pending writes: skips only per-document local write echoes.
 * 4. Resolves cross-device customer IDs to local Room customer row so bills/udhari are never misattributed.
 * 5. Attaches listeners ONLY when FirebaseAuth is valid; automatically retries with backoff on errors.
 * 6. Dispatches remote bill and udhari notifications directly, eliminating duplicate background listeners.
 */
class RealtimeSyncManager private constructor(
    private val context: Context,
    private val database: AppDatabase
) {
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private val localDeviceId = Build.MODEL ?: "AndroidDevice"

    private var rootBillsListener: ListenerRegistration? = null
    private var shopBillsListener: ListenerRegistration? = null
    private var rootCustListener: ListenerRegistration? = null
    private var shopCustListener: ListenerRegistration? = null
    private var udhariListener: ListenerRegistration? = null

    private var authStateListener: FirebaseAuth.AuthStateListener? = null
    private var retryJob: Job? = null
    private var retryAttempt = 0

    private val _syncState = MutableStateFlow(AppSyncState.SYNCED)
    val syncState: StateFlow<AppSyncState> = _syncState.asStateFlow()

    private val _allUdhariTransactions = MutableStateFlow<List<UdhariTransaction>>(emptyList())
    val allUdhariTransactions: StateFlow<List<UdhariTransaction>> = _allUdhariTransactions.asStateFlow()

    // Sequential queue to guarantee older snapshots never overwrite newer ones
    private val snapshotQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        syncScope.launch {
            for (task in snapshotQueue) {
                try {
                    task()
                } catch (e: Exception) {
                    Log.w(TAG, "Sequential sync processing notice: ${e.message}")
                }
            }
        }
    }

    @Synchronized
    fun start() {
        if (!isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "RealtimeSyncManager is already active.")
            return
        }
        Log.i(TAG, "Starting hardened RealtimeSyncManager...")

        if (authStateListener == null) {
            authStateListener = FirebaseAuth.AuthStateListener { auth ->
                val user = auth.currentUser
                if (user != null) {
                    Log.d(TAG, "FirebaseAuth verified (${user.uid}). Attaching realtime listeners...")
                    attachListeners()
                } else {
                    Log.d(TAG, "FirebaseAuth signed out. Detaching realtime listeners...")
                    detachListeners()
                }
            }
            FirebaseAuth.getInstance().addAuthStateListener(authStateListener!!)
        }

        if (FirebaseAuth.getInstance().currentUser != null) {
            attachListeners()
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.i(TAG, "Stopping RealtimeSyncManager...")
        retryJob?.cancel()
        retryJob = null
        authStateListener?.let {
            FirebaseAuth.getInstance().removeAuthStateListener(it)
            authStateListener = null
        }
        detachListeners()
        _syncState.value = AppSyncState.OFFLINE
    }

    @Synchronized
    private fun attachListeners() {
        if (!isRunning.get() || FirebaseAuth.getInstance().currentUser == null) return
        detachListeners()
        retryAttempt = 0

        _syncState.value = AppSyncState.SYNCING
        startBillsListener()
        startShopBillsListener()
        startCustomersListener()
        startShopCustomersListener()
        startUdhariListener()
    }

    @Synchronized
    private fun detachListeners() {
        rootBillsListener?.remove()
        rootBillsListener = null
        shopBillsListener?.remove()
        shopBillsListener = null
        rootCustListener?.remove()
        rootCustListener = null
        shopCustListener?.remove()
        shopCustListener = null
        udhariListener?.remove()
        udhariListener = null
    }

    private fun handleListenerError(sourceTag: String, error: FirebaseFirestoreException) {
        Log.w(TAG, "[$sourceTag] Firestore error: ${error.message} (code: ${error.code})")
        if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
            error.code == FirebaseFirestoreException.Code.UNAVAILABLE
        ) {
            _syncState.value = AppSyncState.ERROR
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!isRunning.get()) return
        retryJob?.cancel()
        val delayMs = (2000L * (1 shl retryAttempt.coerceAtMost(4))).coerceIn(2000L, 30000L)
        retryAttempt++
        Log.d(TAG, "Scheduling realtime listeners retry in ${delayMs}ms (attempt $retryAttempt)...")
        retryJob = syncScope.launch {
            delay(delayMs)
            if (isRunning.get() && FirebaseAuth.getInstance().currentUser != null) {
                attachListeners()
            }
        }
    }

    private fun startBillsListener() {
        try {
            rootBillsListener = FirebaseManager.billsCollection()
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null) {
                        handleListenerError("root_bills", error)
                        return@addSnapshotListener
                    }
                    snapshot?.let { handleBillsSnapshot(it, "root") }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error attaching root bills listener: ${e.message}")
        }
    }

    private fun startShopBillsListener() {
        try {
            shopBillsListener = FirebaseManager.shopBillsCollection()
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null) {
                        handleListenerError("shop_bills", error)
                        return@addSnapshotListener
                    }
                    snapshot?.let { handleBillsSnapshot(it, "shop") }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error attaching shop bills listener: ${e.message}")
        }
    }

    private fun handleBillsSnapshot(snapshot: com.google.firebase.firestore.QuerySnapshot, sourceTag: String) {
        _syncState.value = AppSyncState.SYNCED

        snapshotQueue.trySend {
            val toUpsert = mutableListOf<Pair<BillEntity, List<BillItemEntity>>>()
            val toVoidUuids = mutableListOf<String>()

            for (change in snapshot.documentChanges) {
                val doc = change.document
                // Skip only local write echoes per-document; remote changes in same snapshot are NEVER dropped!
                if (doc.metadata.hasPendingWrites()) continue

                val uuid = doc.getString("transactionUuid") ?: doc.id

                when (change.type) {
                    DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                        val bill = doc.toBillDomain() ?: continue
                        val status = doc.getString("status") ?: ""
                        val isTrashOrVoid = bill.isVoided ||
                            status.equals("TRASH", ignoreCase = true) ||
                            status.equals("VOIDED", ignoreCase = true)

                        if (isTrashOrVoid) {
                            toVoidUuids.add(uuid)
                        } else {
                            // Resolve customer to local Room row to eliminate cross-device auto-increment ID collisions
                            val cleanMob = PhoneUtils.clean10DigitMobileInput(bill.customerMobile ?: "")
                            val localCust = if (cleanMob.length == 10) {
                                database.customerDao().getCustomerByMobileFuzzy(bill.customerMobile ?: "", cleanMob)
                            } else if (!bill.customerName.isNullOrBlank()) {
                                database.customerDao().getCustomerByName(bill.customerName)
                            } else null

                            val resolvedCustId = localCust?.id ?: bill.customerId
                            val billToSave = if (resolvedCustId != bill.customerId) bill.copy(customerId = resolvedCustId) else bill

                            val docCreatedAt = doc.getLong("createdAt") ?: doc.getLong("updatedAt") ?: System.currentTimeMillis()
                            val pair = toBillEntities(billToSave, createdAt = docCreatedAt)
                            toUpsert.add(pair)

                            // Trigger notification for remote bill creation
                            val creator = doc.getString("createdByDeviceId") ?: ""
                            val isFromAnotherDevice = creator.isNotBlank() && creator != localDeviceId && !creator.equals("Counter", ignoreCase = true)
                            val isRecent = docCreatedAt > 0 && (System.currentTimeMillis() - docCreatedAt) in 0..120_000L
                            if (change.type == DocumentChange.Type.ADDED && isFromAnotherDevice && isRecent) {
                                ShopNotificationManager.sendNewBillNotification(
                                    context = context,
                                    bill = billToSave,
                                    deviceName = creator
                                )
                            }
                        }
                    }
                    DocumentChange.Type.REMOVED -> {
                        toVoidUuids.add(uuid)
                    }
                }
            }

            if (toUpsert.isNotEmpty() || toVoidUuids.isNotEmpty()) {
                database.withTransaction {
                    if (toUpsert.isNotEmpty()) {
                        database.billDao().upsertServerBills(toUpsert)
                    }
                    for (uuid in toVoidUuids) {
                        database.billDao().voidBillByUuid(uuid, "Voided on remote device")
                    }
                }
            }
        }
    }

    private fun startCustomersListener() {
        try {
            rootCustListener = FirebaseManager.customersCollection()
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null) {
                        handleListenerError("root_customers", error)
                        return@addSnapshotListener
                    }
                    snapshot?.let { handleCustomersSnapshot(it, "root") }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error attaching root customers listener: ${e.message}")
        }
    }

    private fun startShopCustomersListener() {
        try {
            shopCustListener = FirebaseManager.shopCustomersCollection()
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null) {
                        handleListenerError("shop_customers", error)
                        return@addSnapshotListener
                    }
                    snapshot?.let { handleCustomersSnapshot(it, "shop") }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error attaching shop customers listener: ${e.message}")
        }
    }

    private fun handleCustomersSnapshot(snapshot: com.google.firebase.firestore.QuerySnapshot, sourceTag: String) {
        _syncState.value = AppSyncState.SYNCED

        snapshotQueue.trySend {
            val toUpsert = mutableListOf<CustomerEntity>()
            val toArchive = mutableListOf<Triple<Long?, String, String>>()

            for (change in snapshot.documentChanges) {
                val doc = change.document
                if (doc.metadata.hasPendingWrites()) continue

                val cust = doc.toCustomerDomain() ?: continue
                val cleanMob = PhoneUtils.clean10DigitMobileInput(cust.mobile)

                when (change.type) {
                    DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                        if (!cust.isActive) {
                            toArchive.add(Triple(if (cust.id > 0) cust.id else null, cleanMob, cust.name))
                        } else {
                            toUpsert.add(cust.toEntity().copy(serverId = if (cust.id > 0) cust.id else null))
                        }
                    }
                    DocumentChange.Type.REMOVED -> {
                        toArchive.add(Triple(if (cust.id > 0) cust.id else null, cleanMob, cust.name))
                    }
                }
            }

            if (toUpsert.isNotEmpty() || toArchive.isNotEmpty()) {
                database.withTransaction {
                    if (toUpsert.isNotEmpty()) {
                        database.customerDao().upsertServerCustomers(toUpsert)
                    }
                    for (target in toArchive) {
                        database.customerDao().archiveCustomerComprehensive(
                            serverId = target.first,
                            cleanMobile = target.second
                        )
                    }
                }
            }
        }
    }

    private fun startUdhariListener() {
        try {
            udhariListener = FirebaseManager.udhariTransactionsCollection()
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (error != null) {
                        handleListenerError("udhari_transactions", error)
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener
                    _syncState.value = AppSyncState.SYNCED

                    snapshotQueue.trySend {
                        val list = snapshot.documents.mapNotNull { doc ->
                            val status = doc.getString("status") ?: ""
                            val isDeleted = doc.getBoolean("isDeleted") ?: false
                            if (status.equals("TRASH", ignoreCase = true) || isDeleted) {
                                null
                            } else {
                                doc.toObject(UdhariTransactionDto::class.java)?.apply { id = doc.id }?.toDomain()
                            }
                        }.filter { !it.isReversed }
                         .sortedByDescending { it.createdAt }

                        _allUdhariTransactions.value = list

                        // Trigger notifications for remote udhari payments
                        for (change in snapshot.documentChanges) {
                            if (change.type == DocumentChange.Type.ADDED) {
                                val txDoc = change.document
                                if (txDoc.metadata.hasPendingWrites()) continue
                                val tx = txDoc.toObject(UdhariTransactionDto::class.java) ?: continue

                                val creator = tx.createdByDeviceId ?: ""
                                val isFromAnotherDevice = creator.isNotBlank() && creator != localDeviceId && !creator.equals("Counter", ignoreCase = true)
                                val isRecent = tx.createdAt > 0 && (System.currentTimeMillis() - tx.createdAt) in 0..120_000L
                                if (isFromAnotherDevice && isRecent && tx.type == "PAYMENT") {
                                    ShopNotificationManager.sendUdhariPaymentReceivedNotification(
                                        context = context,
                                        customerId = tx.customerId,
                                        customerName = tx.customerName,
                                        amountPaid = tx.amount,
                                        remainingBalance = tx.remainingOutstanding,
                                        deviceName = creator
                                    )
                                }
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error starting udhari listener: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "RealtimeSyncManager"

        @Volatile
        private var INSTANCE: RealtimeSyncManager? = null

        fun getInstance(context: Context, database: AppDatabase): RealtimeSyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RealtimeSyncManager(context.applicationContext, database).also { INSTANCE = it }
            }
        }
    }
}

package com.matoshree.shopmanager.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
import com.matoshree.shopmanager.data.firebase.*
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.entity.BillEntity
import com.matoshree.shopmanager.data.local.entity.BillItemEntity
import com.matoshree.shopmanager.data.local.entity.CustomerEntity
import com.matoshree.shopmanager.data.repository.toBillEntities
import com.matoshree.shopmanager.data.repository.toEntity
import com.matoshree.shopmanager.domain.model.UdhariTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single Application-Scoped Realtime Synchronization Engine.
 *
 * Architecture Principles:
 * 1. UI observes Room ONLY. Screens never attach individual Firestore listeners.
 * 2. Exactly ONE persistent listener per collection, started when Google Auth is valid.
 * 3. Uses snapshot.documentChanges (ADDED / MODIFIED / REMOVED) instead of full collection re-fetches.
 * 4. Ignores local write echoes (hasPendingWrites == true) to eliminate duplicate writes and screen flickering.
 * 5. Applies all updates in a single atomic Room transaction per Firestore event.
 * 6. Realtime Udhari transactions are held in a shared singleton StateFlow to avoid multiple listeners.
 */
class RealtimeSyncManager private constructor(
    private val context: Context,
    private val database: AppDatabase
) {
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    private var rootBillsListener: ListenerRegistration? = null
    private var shopBillsListener: ListenerRegistration? = null
    private var rootCustListener: ListenerRegistration? = null
    private var shopCustListener: ListenerRegistration? = null
    private var udhariListener: ListenerRegistration? = null

    private val _allUdhariTransactions = MutableStateFlow<List<UdhariTransaction>>(emptyList())
    val allUdhariTransactions: StateFlow<List<UdhariTransaction>> = _allUdhariTransactions.asStateFlow()

    fun start() {
        if (!isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "RealtimeSyncManager is already running.")
            return
        }
        Log.i(TAG, "Starting single app-scoped RealtimeSyncManager...")
        startBillsListener()
        startShopBillsListener()
        startCustomersListener()
        startShopCustomersListener()
        startUdhariListener()
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.i(TAG, "Stopping RealtimeSyncManager...")
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

    private fun startBillsListener() {
        try {
            rootBillsListener = FirebaseManager.billsCollection()
                .addSnapshotListener { snapshot, error ->
                    handleBillsSnapshot(snapshot, error, "root")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error attaching root bills listener: ${e.message}")
        }
    }

    private fun startShopBillsListener() {
        try {
            shopBillsListener = FirebaseManager.shopBillsCollection()
                .addSnapshotListener { snapshot, error ->
                    handleBillsSnapshot(snapshot, error, "shop")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error attaching shop bills listener: ${e.message}")
        }
    }

    private fun handleBillsSnapshot(
        snapshot: com.google.firebase.firestore.QuerySnapshot?,
        error: com.google.firebase.firestore.FirebaseFirestoreException?,
        sourceTag: String
    ) {
        if (error != null) {
            Log.w(TAG, "[$sourceTag] Realtime bills snapshot notice: ${error.message} (code: ${error.code})")
            return
        }
        if (snapshot == null) return

        // If the entire snapshot is our own local optimistic write echo, skip to avoid double writes
        if (snapshot.metadata.hasPendingWrites()) {
            return
        }

        syncScope.launch {
            try {
                val toUpsert = mutableListOf<Pair<BillEntity, List<BillItemEntity>>>()
                val toVoidUuids = mutableListOf<String>()

                for (change in snapshot.documentChanges) {
                    val doc = change.document
                    // Skip local write echo for this document
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
                                toUpsert.add(toBillEntities(bill))
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
                            database.billDao().voidBillComprehensive(0L, uuid, null, "Voided on remote device")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error processing [$sourceTag] bill changes: ${e.message}")
            }
        }
    }

    private fun startCustomersListener() {
        try {
            rootCustListener = FirebaseManager.customersCollection()
                .addSnapshotListener { snapshot, error ->
                    handleCustomersSnapshot(snapshot, error, "root")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error attaching root customers listener: ${e.message}")
        }
    }

    private fun startShopCustomersListener() {
        try {
            shopCustListener = FirebaseManager.shopCustomersCollection()
                .addSnapshotListener { snapshot, error ->
                    handleCustomersSnapshot(snapshot, error, "shop")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error attaching shop customers listener: ${e.message}")
        }
    }

    private fun handleCustomersSnapshot(
        snapshot: com.google.firebase.firestore.QuerySnapshot?,
        error: com.google.firebase.firestore.FirebaseFirestoreException?,
        sourceTag: String
    ) {
        if (error != null) {
            Log.w(TAG, "[$sourceTag] Realtime customers snapshot notice: ${error.message}")
            return
        }
        if (snapshot == null) return
        if (snapshot.metadata.hasPendingWrites()) return

        syncScope.launch {
            try {
                val toUpsert = mutableListOf<CustomerEntity>()
                val toArchive = mutableListOf<Long>()

                for (change in snapshot.documentChanges) {
                    val doc = change.document
                    if (doc.metadata.hasPendingWrites()) continue

                    when (change.type) {
                        DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> {
                            val cust = doc.toCustomerDomain() ?: continue
                            if (!cust.isActive) {
                                if (cust.id > 0) toArchive.add(cust.id)
                            } else {
                                toUpsert.add(cust.toEntity().copy(serverId = if (cust.id > 0) cust.id else null))
                            }
                        }
                        DocumentChange.Type.REMOVED -> {
                            doc.toCustomerDomain()?.let { if (it.id > 0) toArchive.add(it.id) }
                        }
                    }
                }

                if (toUpsert.isNotEmpty() || toArchive.isNotEmpty()) {
                    database.withTransaction {
                        if (toUpsert.isNotEmpty()) {
                            database.customerDao().upsertServerCustomers(toUpsert)
                        }
                        for (id in toArchive) {
                            database.customerDao().archiveCustomer(id)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error processing [$sourceTag] customer changes: ${e.message}")
            }
        }
    }

    private fun startUdhariListener() {
        try {
            udhariListener = FirebaseManager.udhariTransactionsCollection()
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Realtime udhari listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener

                    val list = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(UdhariTransactionDto::class.java)?.apply { id = doc.id }?.toDomain()
                    }.filter { !it.isReversed }
                     .sortedByDescending { it.createdAt }

                    _allUdhariTransactions.value = list
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

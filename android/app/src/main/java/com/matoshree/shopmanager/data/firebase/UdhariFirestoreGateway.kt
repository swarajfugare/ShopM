package com.matoshree.shopmanager.data.firebase

import kotlinx.coroutines.tasks.await

data class CustomerDocSnapshot(
    val id: String,
    val path: String,
    val data: CustomerDto
)

data class BillDocSnapshot(
    val id: String,
    val path: String,
    val data: BillDto
)

data class UdhariTransactionDocSnapshot(
    val id: String,
    val path: String,
    val data: UdhariTransactionDto
)

data class BillBatchUpdate(
    val paidAmount: Double,
    val pendingAmount: Double,
    val paymentStatus: String,
    val payments: List<BillPaymentDto>,
    val updatedAt: Long
)

/**
 * Seam for Firestore operations in Udhari repository.
 * Enables unit testing of recordPayment and reversePayment with real doc-id shapes
 * without invoking live Firebase network calls.
 */
interface UdhariFirestoreGateway {
    suspend fun ensureAuthenticated()
    suspend fun isTransactionDuplicate(uuid: String): Boolean
    suspend fun getCustomerDocs(): List<CustomerDocSnapshot>
    suspend fun getBillDocs(): List<BillDocSnapshot>
    suspend fun getTransactionDocs(): List<UdhariTransactionDocSnapshot>
    suspend fun commitPaymentBatch(
        customerUpdates: Map<String, Double>,
        billUpdates: Map<String, BillBatchUpdate>,
        transactionsToSave: List<UdhariTransactionDto>
    )
    suspend fun commitReversalBatch(
        reversalTransactionsToSave: List<UdhariTransactionDto>,
        reversedTxDocUpdates: Map<String, Map<String, Any?>>,
        billUpdates: Map<String, Map<String, Any?>>,
        customerUpdates: Map<String, Double>
    )
    suspend fun verifyTransactionReversed(path: String): Boolean = true
}

class DefaultUdhariFirestoreGateway : UdhariFirestoreGateway {
    override suspend fun ensureAuthenticated() {
        try {
            FirebaseManager.ensureAuthenticated()
        } catch (_: Exception) {}
    }

    override suspend fun isTransactionDuplicate(uuid: String): Boolean {
        if (uuid.isBlank()) return false
        val snap = FirebaseManager.udhariTransactionsCollection()
            .whereEqualTo("transactionUuid", uuid)
            .limit(1)
            .get()
            .await()
        return !snap.isEmpty
    }

    override suspend fun getCustomerDocs(): List<CustomerDocSnapshot> {
        val isOnline = FirebaseManager.isNetworkAvailable()
        val rootSnap = try {
            if (isOnline) {
                kotlinx.coroutines.withTimeoutOrNull(2000) {
                    FirebaseManager.customersCollection().get().await()
                } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } else {
                FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            }
        } catch (_: Exception) { null }

        val shopSnap = try {
            if (isOnline) {
                kotlinx.coroutines.withTimeoutOrNull(2000) {
                    FirebaseManager.shopCustomersCollection().get().await()
                } ?: FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } else {
                FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            }
        } catch (_: Exception) { null }

        val all = (rootSnap?.documents ?: emptyList()) + (shopSnap?.documents ?: emptyList())
        return all.distinctBy { it.reference.path }.mapNotNull { doc ->
            val dto = doc.toObject(CustomerDto::class.java)
            if (dto != null) {
                dto.id = doc.id
                CustomerDocSnapshot(id = doc.id, path = doc.reference.path, data = dto)
            } else null
        }
    }

    override suspend fun getBillDocs(): List<BillDocSnapshot> {
        val isOnline = FirebaseManager.isNetworkAvailable()
        val rootSnap = try {
            if (isOnline) {
                kotlinx.coroutines.withTimeoutOrNull(3000) {
                    FirebaseManager.billsCollection().get().await()
                } ?: FirebaseManager.billsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } else {
                FirebaseManager.billsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            }
        } catch (_: Exception) { null }

        val shopSnap = try {
            if (isOnline) {
                kotlinx.coroutines.withTimeoutOrNull(3000) {
                    FirebaseManager.shopBillsCollection().get().await()
                } ?: FirebaseManager.shopBillsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } else {
                FirebaseManager.shopBillsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            }
        } catch (_: Exception) { null }

        val all = (rootSnap?.documents ?: emptyList()) + (shopSnap?.documents ?: emptyList())
        return all.distinctBy { it.reference.path }.mapNotNull { doc ->
            val dto = doc.toObject(BillDto::class.java)
            if (dto != null) {
                dto.id = doc.id
                BillDocSnapshot(id = doc.id, path = doc.reference.path, data = dto)
            } else null
        }
    }

    override suspend fun getTransactionDocs(): List<UdhariTransactionDocSnapshot> {
        val isOnline = FirebaseManager.isNetworkAvailable()
        val snap = try {
            if (isOnline) {
                kotlinx.coroutines.withTimeoutOrNull(3000) {
                    FirebaseManager.udhariTransactionsCollection().get().await()
                } ?: FirebaseManager.udhariTransactionsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } else {
                FirebaseManager.udhariTransactionsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            }
        } catch (_: Exception) { null }

        return snap?.documents?.mapNotNull { doc ->
            val dto = doc.toObject(UdhariTransactionDto::class.java)
            if (dto != null) {
                dto.id = doc.id
                UdhariTransactionDocSnapshot(id = doc.id, path = doc.reference.path, data = dto)
            } else null
        } ?: emptyList()
    }

    override suspend fun commitPaymentBatch(
        customerUpdates: Map<String, Double>,
        billUpdates: Map<String, BillBatchUpdate>,
        transactionsToSave: List<UdhariTransactionDto>
    ) {
        val batch = FirebaseManager.firestore.batch()
        for ((path, newDue) in customerUpdates) {
            batch.update(
                FirebaseManager.firestore.document(path),
                mapOf("outstandingAmount" to newDue, "updatedAt" to System.currentTimeMillis())
            )
        }
        for ((path, update) in billUpdates) {
            batch.update(
                FirebaseManager.firestore.document(path),
                mapOf(
                    "paidAmount" to update.paidAmount,
                    "pendingAmount" to update.pendingAmount,
                    "paymentStatus" to update.paymentStatus,
                    "payments" to update.payments,
                    "updatedAt" to update.updatedAt
                )
            )
        }
        for (tx in transactionsToSave) {
            batch.set(FirebaseManager.udhariTransactionsCollection().document(tx.id), tx)
        }
        val task = batch.commit()
        FirebaseManager.awaitWriteTask(task)
    }

    override suspend fun commitReversalBatch(
        reversalTransactionsToSave: List<UdhariTransactionDto>,
        reversedTxDocUpdates: Map<String, Map<String, Any?>>,
        billUpdates: Map<String, Map<String, Any?>>,
        customerUpdates: Map<String, Double>
    ) {
        val batch = FirebaseManager.firestore.batch()
        for (rtx in reversalTransactionsToSave) {
            batch.set(FirebaseManager.udhariTransactionsCollection().document(rtx.id), rtx)
        }
        for ((path, updates) in reversedTxDocUpdates) {
            batch.set(FirebaseManager.firestore.document(path), updates, com.google.firebase.firestore.SetOptions.merge())
        }
        for ((path, updates) in billUpdates) {
            batch.set(FirebaseManager.firestore.document(path), updates, com.google.firebase.firestore.SetOptions.merge())
        }
        for ((path, newOutstanding) in customerUpdates) {
            batch.set(
                FirebaseManager.firestore.document(path),
                mapOf("outstandingAmount" to newOutstanding, "updatedAt" to System.currentTimeMillis()),
                com.google.firebase.firestore.SetOptions.merge()
            )
        }
        val task = batch.commit()
        FirebaseManager.awaitWriteTask(task)
    }

    override suspend fun verifyTransactionReversed(path: String): Boolean {
        return try {
            val snap = kotlinx.coroutines.withTimeoutOrNull(4000) {
                FirebaseManager.firestore.document(path).get(com.google.firebase.firestore.Source.SERVER).await()
            } ?: FirebaseManager.firestore.document(path).get().await()
            val dto = snap.toObject(UdhariTransactionDto::class.java)
            dto?.isReversed == true
        } catch (e: Exception) {
            android.util.Log.e("UdhariFirestoreGateway", "Failed to verify transaction reversal on server: ${e.message}", e)
            false
        }
    }
}

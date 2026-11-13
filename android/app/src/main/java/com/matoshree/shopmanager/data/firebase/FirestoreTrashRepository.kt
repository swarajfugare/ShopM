package com.matoshree.shopmanager.data.firebase

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class TrashedItem(
    val docId: String,
    val legacyId: Long,
    val entityType: String, // "PRODUCT", "CUSTOMER", "BILL"
    val title: String,
    val subtitle: String,
    val amount: Double? = null,
    val deletedAt: Long,
    val daysLeft: Int
)

class FirestoreTrashRepository {
    private val TAG = "FirestoreTrashRepo"
    private val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L

    fun observeTrashedProducts(): Flow<List<TrashedItem>> = callbackFlow {
        val listener = FirebaseManager.productsCollection()
            .whereEqualTo("status", "TRASH")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Trash products error: ${error.message}")
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val list = snapshot?.documents?.mapNotNull { doc ->
                    val dto = doc.toObject(ProductDto::class.java)?.apply { id = doc.id } ?: return@mapNotNull null
                    val dAt = dto.deletedAt ?: dto.updatedAt
                    val elapsed = now - dAt
                    val daysRemaining = (((SEVEN_DAYS_MS - elapsed) / (24L * 60L * 60L * 1000L)) + 1).toInt().coerceIn(1, 7)
                    val idNum = dto.legacyId ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)

                    TrashedItem(
                        docId = doc.id,
                        legacyId = idNum,
                        entityType = "PRODUCT",
                        title = dto.name,
                        subtitle = "Price: ${CurrencyFormatter.format(dto.sellingPrice)} • SKU: ${dto.sku ?: "N/A"}",
                        amount = dto.sellingPrice,
                        deletedAt = dAt,
                        daysLeft = daysRemaining
                    )
                }?.sortedByDescending { it.deletedAt } ?: emptyList()

                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    fun observeTrashedCustomers(): Flow<List<TrashedItem>> = callbackFlow {
        val listener = FirebaseManager.customersCollection()
            .whereEqualTo("status", "TRASH")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Trash customers error: ${error.message}")
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val list = snapshot?.documents?.mapNotNull { doc ->
                    val dto = doc.toObject(CustomerDto::class.java)?.apply { id = doc.id } ?: return@mapNotNull null
                    val dAt = dto.deletedAt ?: dto.updatedAt
                    val elapsed = now - dAt
                    val daysRemaining = (((SEVEN_DAYS_MS - elapsed) / (24L * 60L * 60L * 1000L)) + 1).toInt().coerceIn(1, 7)
                    val idNum = dto.legacyId ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)

                    TrashedItem(
                        docId = doc.id,
                        legacyId = idNum,
                        entityType = "CUSTOMER",
                        title = dto.name,
                        subtitle = "Mobile: ${dto.mobile.ifBlank { "No Mobile" }} • ${dto.address ?: "No Address"}",
                        amount = dto.outstandingAmount,
                        deletedAt = dAt,
                        daysLeft = daysRemaining
                    )
                }?.sortedByDescending { it.deletedAt } ?: emptyList()

                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    fun observeTrashedBills(): Flow<List<TrashedItem>> = callbackFlow {
        val listener = FirebaseManager.billsCollection()
            .whereEqualTo("status", "TRASH")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Trash bills error: ${error.message}")
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val list = snapshot?.documents?.mapNotNull { doc ->
                    val dto = doc.toObject(BillDto::class.java)?.apply { id = doc.id } ?: return@mapNotNull null
                    val dAt = dto.deletedAt ?: dto.updatedAt
                    val elapsed = now - dAt
                    val daysRemaining = (((SEVEN_DAYS_MS - elapsed) / (24L * 60L * 60L * 1000L)) + 1).toInt().coerceIn(1, 7)
                    val idNum = dto.legacyId ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)

                    TrashedItem(
                        docId = doc.id,
                        legacyId = idNum,
                        entityType = "BILL",
                        title = "Bill #${dto.billNumber}",
                        subtitle = "${dto.customerName ?: "Walk-in"} • ${CurrencyFormatter.format(dto.finalAmount)} (${dto.paymentMethod})",
                        amount = dto.finalAmount,
                        deletedAt = dAt,
                        daysLeft = daysRemaining
                    )
                }?.sortedByDescending { it.deletedAt } ?: emptyList()

                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    fun observeTrashedPayments(): Flow<List<TrashedItem>> = callbackFlow {
        val listener = FirebaseManager.udhariTransactionsCollection()
            .whereEqualTo("status", "TRASH")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Trash payments snapshot note: ${error.message}")
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val list = snapshot?.documents?.mapNotNull { doc ->
                    val dto = doc.toObject(UdhariTransactionDto::class.java)?.apply { id = doc.id } ?: return@mapNotNull null
                    val dAt = dto.deletedAt ?: dto.reversedAt ?: dto.createdAt
                    val elapsed = now - dAt
                    val daysRemaining = (((SEVEN_DAYS_MS - elapsed) / (24L * 60L * 60L * 1000L)) + 1).toInt().coerceIn(1, 7)
                    val idNum = doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL

                    val label = if (dto.type == "PURCHASE_CREDIT") "मॅन्युअल उधारी (+)" else "उधारी जमा (${dto.paymentMethod})"

                    TrashedItem(
                        docId = doc.id,
                        legacyId = idNum,
                        entityType = "PAYMENT",
                        title = "$label • ${CurrencyFormatter.format(dto.amount)}",
                        subtitle = "${dto.customerName.ifBlank { "ग्राहक" }} • ${dto.customerMobile.ifBlank { "No Mobile" }}",
                        amount = dto.amount,
                        deletedAt = dAt,
                        daysLeft = daysRemaining
                    )
                }?.sortedByDescending { it.deletedAt } ?: emptyList()

                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    suspend fun restoreItem(item: TrashedItem) {
        FirebaseManager.ensureAuthenticated()
        when (item.entityType) {
            "PRODUCT" -> {
                FirebaseManager.productsCollection().document(item.docId).update(
                    mapOf(
                        "status" to "ACTIVE",
                        "isActive" to true,
                        "deletedAt" to null,
                        "updatedAt" to System.currentTimeMillis()
                    )
                ).await()
            }
            "CUSTOMER" -> {
                FirebaseManager.customersCollection().document(item.docId).update(
                    mapOf(
                        "status" to "ACTIVE",
                        "isActive" to true,
                        "deletedAt" to null,
                        "updatedAt" to System.currentTimeMillis()
                    )
                ).await()
            }
            "BILL" -> {
                FirebaseManager.billsCollection().document(item.docId).update(
                    mapOf(
                        "status" to "COMPLETED",
                        "paymentStatus" to "PAID",
                        "deletedAt" to null,
                        "updatedAt" to System.currentTimeMillis()
                    )
                ).await()
            }
            "PAYMENT" -> {
                FirebaseManager.udhariTransactionsCollection().document(item.docId).update(
                    mapOf(
                        "status" to "ACTIVE",
                        "isReversed" to false,
                        "deletedAt" to null,
                        "reversalReason" to null,
                        "reversedAt" to null
                    )
                ).await()
                try {
                    val txDoc = FirebaseManager.udhariTransactionsCollection().document(item.docId).get().await()
                    val tx = txDoc.toObject(UdhariTransactionDto::class.java)
                    if (tx != null && tx.amount > 0) {
                        val custSnap = FirebaseManager.customersCollection().whereEqualTo("legacyId", tx.customerId).get().await()
                        custSnap.documents.firstOrNull()?.let { cDoc ->
                            cDoc.reference.update("outstandingAmount", FieldValue.increment(-tx.amount))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Restore payment balance adjust note: ${e.message}")
                }
            }
        }
    }

    suspend fun permanentDeleteItem(item: TrashedItem) {
        FirebaseManager.ensureAuthenticated()
        when (item.entityType) {
            "PRODUCT" -> FirebaseManager.productsCollection().document(item.docId).delete().await()
            "CUSTOMER" -> {
                FirebaseManager.customersCollection().document(item.docId).delete().await()
                try {
                    val batch = FirebaseManager.firestore.batch()
                    val bills = FirebaseManager.billsCollection().whereEqualTo("customerId", item.docId).get().await()
                    bills.documents.forEach { batch.delete(it.reference) }
                    if (item.legacyId != null && item.legacyId > 0) {
                        val legacyBills = FirebaseManager.billsCollection().whereEqualTo("legacyCustomerId", item.legacyId).get().await()
                        legacyBills.documents.forEach { batch.delete(it.reference) }
                        val txs = FirebaseManager.udhariTransactionsCollection().whereEqualTo("customerId", item.legacyId).get().await()
                        txs.documents.forEach { batch.delete(it.reference) }
                    }
                    batch.commit().await()
                } catch (e: Exception) {
                    Log.w(TAG, "Permanent delete customer cleanup note: ${e.message}")
                }
            }
            "BILL" -> FirebaseManager.billsCollection().document(item.docId).delete().await()
            "PAYMENT" -> FirebaseManager.udhariTransactionsCollection().document(item.docId).delete().await()
        }
    }

    suspend fun emptyTrash(entityType: String? = null) {
        FirebaseManager.ensureAuthenticated()
        val batch = FirebaseManager.firestore.batch()

        if (entityType == null || entityType == "PRODUCT") {
            val prodSnap = FirebaseManager.productsCollection().whereEqualTo("status", "TRASH").get().await()
            prodSnap.documents.forEach { batch.delete(it.reference) }
        }
        if (entityType == null || entityType == "CUSTOMER") {
            val custSnap = FirebaseManager.customersCollection().whereEqualTo("status", "TRASH").get().await()
            custSnap.documents.forEach { batch.delete(it.reference) }
        }
        if (entityType == null || entityType == "BILL") {
            val billSnap = FirebaseManager.billsCollection().whereEqualTo("status", "TRASH").get().await()
            billSnap.documents.forEach { batch.delete(it.reference) }
        }
        if (entityType == null || entityType == "PAYMENT") {
            val paySnap = FirebaseManager.udhariTransactionsCollection().whereEqualTo("status", "TRASH").get().await()
            paySnap.documents.forEach { batch.delete(it.reference) }
        }

        batch.commit().await()
    }

    suspend fun autoPurgeOldTrash() {
        try {
            FirebaseManager.ensureAuthenticated()
            val purgeThreshold = System.currentTimeMillis() - SEVEN_DAYS_MS
            val batch = FirebaseManager.firestore.batch()
            var count = 0

            // 1. Purge expired products
            val prods = FirebaseManager.productsCollection().whereEqualTo("status", "TRASH").get().await()
            prods.documents.forEach { doc ->
                val dAt = doc.getLong("deletedAt") ?: doc.getLong("updatedAt")
                if (dAt != null && dAt > 0L && dAt < purgeThreshold) {
                    batch.delete(doc.reference)
                    count++
                }
            }

            // 2. Purge expired customers
            val custs = FirebaseManager.customersCollection().whereEqualTo("status", "TRASH").get().await()
            custs.documents.forEach { doc ->
                val dAt = doc.getLong("deletedAt") ?: doc.getLong("updatedAt")
                if (dAt != null && dAt > 0L && dAt < purgeThreshold) {
                    batch.delete(doc.reference)
                    count++
                }
            }

            // 3. Purge expired bills
            val bills = FirebaseManager.billsCollection().whereEqualTo("status", "TRASH").get().await()
            bills.documents.forEach { doc ->
                val dAt = doc.getLong("deletedAt") ?: doc.getLong("updatedAt")
                if (dAt != null && dAt > 0L && dAt < purgeThreshold) {
                    batch.delete(doc.reference)
                    count++
                }
            }

            // 4. Purge expired payments
            val pays = FirebaseManager.udhariTransactionsCollection().whereEqualTo("status", "TRASH").get().await()
            pays.documents.forEach { doc ->
                val dAt = doc.getLong("deletedAt") ?: doc.getLong("updatedAt")
                if (dAt != null && dAt > 0L && dAt < purgeThreshold) {
                    batch.delete(doc.reference)
                    count++
                }
            }

            if (count > 0) {
                batch.commit().await()
                Log.d(TAG, "Auto-purged $count expired items from Trash Bin")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auto purge error: ${e.message}")
        }
    }
}

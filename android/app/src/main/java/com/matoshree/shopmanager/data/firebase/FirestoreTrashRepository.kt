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
    val gid: String = "",
    val legacyId: Long,
    val entityType: String, // "PRODUCT", "CUSTOMER", "BILL"
    val title: String,
    val subtitle: String,
    val amount: Double? = null,
    val deletedAt: Long,
    val daysLeft: Int,
    val extraIdentifier: String = ""
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
                        gid = dto.gid.ifBlank { doc.id },
                        legacyId = idNum,
                        entityType = "PRODUCT",
                        title = dto.name,
                        subtitle = "Price: ${CurrencyFormatter.format(dto.sellingPrice)} • SKU: ${dto.sku ?: "N/A"}",
                        amount = dto.sellingPrice,
                        deletedAt = dAt,
                        daysLeft = daysRemaining,
                        extraIdentifier = dto.sku ?: ""
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
                    val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(dto.mobile)

                    TrashedItem(
                        docId = doc.id,
                        gid = dto.gid.ifBlank { doc.id },
                        legacyId = idNum,
                        entityType = "CUSTOMER",
                        title = dto.name,
                        subtitle = "Mobile: ${dto.mobile.ifBlank { "No Mobile" }} • ${dto.address ?: "No Address"}",
                        amount = dto.outstandingAmount,
                        deletedAt = dAt,
                        daysLeft = daysRemaining,
                        extraIdentifier = cleanMob
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
                        gid = dto.gid.ifBlank { doc.id },
                        legacyId = idNum,
                        entityType = "BILL",
                        title = "Bill #${dto.billNumber}",
                        subtitle = "${dto.customerName ?: "Walk-in"} • ${CurrencyFormatter.format(dto.finalAmount)} (${dto.paymentMethod})",
                        amount = dto.finalAmount,
                        deletedAt = dAt,
                        daysLeft = daysRemaining,
                        extraIdentifier = dto.transactionUuid.ifBlank { doc.id }
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

                    val isManual = dto.type == "PURCHASE_CREDIT" || dto.kind.equals("MANUAL", ignoreCase = true)
                    val label = if (isManual) "जुनी उधारी" else "उधारी जमा (${dto.paymentMethod})"
                    val displayCustName = dto.customerName.ifBlank { "ग्राहक" }
                    val itemTitle = if (isManual) {
                        "जुनी उधारी - $displayCustName - ${CurrencyFormatter.format(dto.amount)}"
                    } else {
                        "$label • ${CurrencyFormatter.format(dto.amount)}"
                    }

                    TrashedItem(
                        docId = doc.id,
                        gid = dto.gid.ifBlank { doc.id },
                        legacyId = idNum,
                        entityType = "PAYMENT",
                        title = itemTitle,
                        subtitle = "${dto.customerName.ifBlank { "ग्राहक" }} • ${dto.customerMobile.ifBlank { "No Mobile" }}",
                        amount = dto.amount,
                        deletedAt = dAt,
                        daysLeft = daysRemaining,
                        extraIdentifier = dto.transactionUuid.ifBlank { doc.id }
                    )
                }?.sortedByDescending { it.deletedAt } ?: emptyList()

                trySend(list)
            }
        awaitClose { listener.remove() }
    }

    fun observeTrashedExpenses(): Flow<List<TrashedItem>> = callbackFlow {
        val listener = FirebaseManager.expensesCollection()
            .whereEqualTo("status", "TRASH")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Trash expenses error: ${error.message}")
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val list = snapshot?.documents?.mapNotNull { doc ->
                    val dto = doc.toObject(ExpenseDto::class.java)?.apply { id = doc.id } ?: return@mapNotNull null
                    val dAt = dto.deletedAt ?: dto.updatedAt
                    val elapsed = now - dAt
                    val daysRemaining = (((SEVEN_DAYS_MS - elapsed) / (24L * 60L * 60L * 1000L)) + 1).toInt().coerceIn(1, 7)
                    val idNum = dto.legacyId ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)

                    TrashedItem(
                        docId = doc.id,
                        gid = dto.gid.ifBlank { doc.id },
                        legacyId = idNum,
                        entityType = "EXPENSE",
                        title = "${dto.category} • ${CurrencyFormatter.format(dto.amount)}",
                        subtitle = "${dto.expenseDate} • ${dto.paymentMethod} ${if (!dto.note.isNullOrBlank()) "• ${dto.note}" else ""}",
                        amount = dto.amount,
                        deletedAt = dAt,
                        daysLeft = daysRemaining,
                        extraIdentifier = dto.category
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
                val now = System.currentTimeMillis()
                val custRef = FirebaseManager.customersCollection().document(item.docId)
                val cDoc = try { custRef.get().await() } catch (_: Exception) { null }
                val cDto = cDoc?.toObject(CustomerDto::class.java)
                val custGid = cDto?.gid?.ifBlank { null } ?: item.gid.ifBlank { item.docId }
                val custLegacyId = cDto?.legacyId ?: item.legacyId
                val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(cDto?.mobile ?: item.extraIdentifier)

                custRef.update(
                    mapOf(
                        "status" to "ACTIVE",
                        "isActive" to true,
                        "deletedAt" to null,
                        "updatedAt" to now
                    )
                ).await()

                try {
                    FirebaseManager.shopCustomersCollection().document(item.docId).update(
                        mapOf(
                            "status" to "ACTIVE",
                            "isActive" to true,
                            "deletedAt" to null,
                            "updatedAt" to now
                        )
                    ).await()
                } catch (_: Exception) {}

                // Reactivate cascaded bills and reverse stock restore
                try {
                    val rootBills = try { FirebaseManager.billsCollection().whereEqualTo("cascadedBy", "CUSTOMER_DELETE").get().await().documents } catch (_: Exception) { emptyList() }
                    val shopBills = try { FirebaseManager.shopBillsCollection().whereEqualTo("cascadedBy", "CUSTOMER_DELETE").get().await().documents } catch (_: Exception) { emptyList() }
                    val allCascadedBills = (rootBills + shopBills).distinctBy { it.id }

                    val matchedBills = allCascadedBills.filter { doc ->
                        val b = doc.toObject(BillDto::class.java) ?: return@filter false
                        val bMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(b.customerMobile ?: "")
                        val gidMatch = b.linkedCustomerGid == custGid || b.customerGid == custGid
                        val idMatch = (custLegacyId > 0 && b.legacyCustomerId == custLegacyId) || b.customerId == item.docId || b.customerId == "cust_$custLegacyId"
                        val mobMatch = cleanMob.length == 10 && bMob == cleanMob
                        gidMatch || idMatch || mobMatch
                    }

                    if (matchedBills.isNotEmpty()) {
                        val batch = FirebaseManager.firestore.batch()
                        matchedBills.forEach { billDoc ->
                            val b = billDoc.toObject(BillDto::class.java)
                            // Reverse stock restore: deduct stock again
                            b?.items?.forEach { itm ->
                                val pId = itm.legacyProductId ?: itm.productId?.removePrefix("prod_")?.toLongOrNull()
                                if (pId != null && pId > 0 && itm.quantity > 0) {
                                    val prodRef = FirebaseManager.productsCollection().document("prod_$pId")
                                    val qty = itm.quantity.toLong()
                                    batch.set(
                                        prodRef,
                                        mapOf(
                                            "currentStock" to FieldValue.increment(-qty),
                                            "totalSold" to FieldValue.increment(qty),
                                            "updatedAt" to now
                                        ),
                                        com.google.firebase.firestore.SetOptions.merge()
                                    )
                                }
                            }
                            batch.update(billDoc.reference, mapOf(
                                "status" to "COMPLETED",
                                "cascadedBy" to null,
                                "linkedCustomerGid" to null,
                                "deletedAt" to null,
                                "updatedAt" to now
                            ))
                        }
                        batch.commit().await()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Restore customer cascaded bills error: ${e.message}")
                }

                // Reactivate cascaded udhari transactions
                try {
                    val txSnap = FirebaseManager.udhariTransactionsCollection()
                        .whereEqualTo("cascadedBy", "CUSTOMER_DELETE")
                        .get().await()
                    val matchedTxs = txSnap.documents.filter { doc ->
                        val tx = doc.toObject(UdhariTransactionDto::class.java) ?: return@filter false
                        val tMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(tx.customerMobile)
                        val gidMatch = tx.linkedCustomerGid == custGid || tx.customerGid == custGid
                        val idMatch = (custLegacyId > 0 && tx.customerId == custLegacyId)
                        val mobMatch = cleanMob.length == 10 && tMob == cleanMob
                        gidMatch || idMatch || mobMatch
                    }
                    if (matchedTxs.isNotEmpty()) {
                        val batch = FirebaseManager.firestore.batch()
                        matchedTxs.forEach { txDoc ->
                            batch.update(txDoc.reference, mapOf(
                                "status" to "ACTIVE",
                                "isDeleted" to false,
                                "cascadedBy" to null,
                                "linkedCustomerGid" to null,
                                "deletedAt" to null,
                                "updatedAt" to now
                            ))
                        }
                        batch.commit().await()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Restore customer cascaded transactions error: ${e.message}")
                }
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
                try {
                    val billDoc = FirebaseManager.billsCollection().document(item.docId).get().await()
                    val bDto = billDoc.toObject(BillDto::class.java)
                    val bGid = bDto?.gid?.ifBlank { null } ?: item.docId
                    val txDocs = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()
                    val txsByLinked = FirebaseManager.udhariTransactionsCollection()
                        .whereEqualTo("linkedBillGid", bGid)
                        .whereEqualTo("cascadedBy", "BILL_DELETE")
                        .get().await().documents
                    txDocs.addAll(txsByLinked)
                    val distinctTxs = txDocs.distinctBy { it.id }
                    if (distinctTxs.isNotEmpty()) {
                        val batch = FirebaseManager.firestore.batch()
                        distinctTxs.forEach { tDoc ->
                            batch.update(tDoc.reference, mapOf(
                                "status" to "ACTIVE",
                                "isDeleted" to false,
                                "deletedAt" to null,
                                "cascadedBy" to null,
                                "updatedAt" to System.currentTimeMillis()
                            ))
                        }
                        batch.commit().await()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Restore bill udhari txs note: ${e.message}")
                }
            }
            "PAYMENT" -> {
                FirebaseManager.udhariTransactionsCollection().document(item.docId).update(
                    mapOf(
                        "status" to "ACTIVE",
                        "isDeleted" to false,
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
            "EXPENSE" -> {
                FirebaseManager.expensesCollection().document(item.docId).update(
                    mapOf(
                        "status" to "ACTIVE",
                        "deletedAt" to null,
                        "updatedAt" to System.currentTimeMillis()
                    )
                ).await()
            }
        }
    }

    suspend fun permanentDeleteItem(item: TrashedItem) {
        FirebaseManager.ensureAuthenticated()
        when (item.entityType) {
            "PRODUCT" -> FirebaseManager.productsCollection().document(item.docId).delete().await()
            "CUSTOMER" -> {
                val cleanMobile = if (item.extraIdentifier.length == 10) item.extraIdentifier else {
                    val custDoc = try {
                        FirebaseManager.customersCollection().document(item.docId).get().await()
                    } catch (_: Exception) { null }
                    com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(custDoc?.getString("mobile") ?: "")
                }

                try {
                    FirebaseManager.customersCollection().document(item.docId).delete().await()
                } catch (_: Exception) {}
                try {
                    FirebaseManager.shopCustomersCollection().document(item.docId).delete().await()
                } catch (_: Exception) {}

                // SAFE POLICY: Disassociate bills from customer so the customer's history is detached,
                // while preserving the shop's total sales history, tax records, and daily closings!
                try {
                    val batch = FirebaseManager.firestore.batch()
                    val bills1 = FirebaseManager.billsCollection().whereEqualTo("customerId", item.docId).get().await()
                    bills1.documents.forEach { batch.update(it.reference, mapOf("customerId" to null, "customerMobile" to null)) }
                    val shopBills1 = FirebaseManager.shopBillsCollection().whereEqualTo("customerId", item.docId).get().await()
                    shopBills1.documents.forEach { batch.update(it.reference, mapOf("customerId" to null, "customerMobile" to null)) }

                    if (cleanMobile.length == 10) {
                        val bills2 = FirebaseManager.billsCollection().whereEqualTo("customerMobile", cleanMobile).get().await()
                        bills2.documents.forEach { batch.update(it.reference, mapOf("customerId" to null, "customerMobile" to null)) }
                        val shopBills2 = FirebaseManager.shopBillsCollection().whereEqualTo("customerMobile", cleanMobile).get().await()
                        shopBills2.documents.forEach { batch.update(it.reference, mapOf("customerId" to null, "customerMobile" to null)) }

                        // Purge Udhari debt transactions for this customer so they start fresh upon re-add
                        val txs = FirebaseManager.udhariTransactionsCollection().whereEqualTo("customerMobile", cleanMobile).get().await()
                        txs.documents.forEach { batch.delete(it.reference) }
                    }
                    batch.commit().await()
                } catch (e: Exception) {
                    Log.w(TAG, "Permanent delete customer safe cleanup note: ${e.message}")
                }
            }
            "BILL" -> FirebaseManager.billsCollection().document(item.docId).delete().await()
            "PAYMENT" -> FirebaseManager.udhariTransactionsCollection().document(item.docId).delete().await()
            "EXPENSE" -> FirebaseManager.expensesCollection().document(item.docId).delete().await()
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
        if (entityType == null || entityType == "EXPENSE") {
            val expSnap = FirebaseManager.expensesCollection().whereEqualTo("status", "TRASH").get().await()
            expSnap.documents.forEach { batch.delete(it.reference) }
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

            // 5. Purge expired expenses
            val exps = FirebaseManager.expensesCollection().whereEqualTo("status", "TRASH").get().await()
            exps.documents.forEach { doc ->
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

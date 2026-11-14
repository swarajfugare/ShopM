package com.matoshree.shopmanager.data.repository

import android.util.Log
import com.matoshree.shopmanager.data.firebase.*
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.dao.BillWithItems
import com.matoshree.shopmanager.data.local.entity.*
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.DateUtils
import androidx.room.withTransaction
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import java.util.UUID

// Extension mappers between Room Entities and Domain Models
fun CustomerEntity.toDomain(): Customer {
    val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile)
    val trueDocId = when {
        cleanMob.length == 10 -> "cust_m_$cleanMob"
        !docId.isNullOrBlank() -> docId
        else -> null
    }
    val effectiveGid = when {
        !trueDocId.isNullOrBlank() -> IdGenerator.customerGid(trueDocId)
        gid.isNotBlank() && IdFormat.isValid(gid) -> gid
        else -> IdGenerator.generate(IdType.CUSTOMER)
    }
    return Customer(
        id = id,
        gid = effectiveGid,
        docId = trueDocId,
        shopId = shopId,
        name = name,
        mobile = mobile,
        email = email,
        address = address,
        notes = notes,
        totalBills = totalBills,
        lifetimeSpend = lifetimeSpend,
        creditLimit = null,
        outstandingBalance = outstandingBalance,
        firstPurchaseAt = firstPurchaseAt,
        lastPurchaseAt = lastPurchaseAt,
        isActive = isActive,
        tier = "REGULAR", // VIP tag is never auto-assigned; only set if shop owner assigns it
        needsReview = needsReview
    )
}

fun Customer.toEntity(): CustomerEntity {
    val (canonicalDocId, canonicalGid) = IdGenerator.canonicalCustomerDocIdAndGid(
        mobile = mobile,
        existingDocId = docId,
        existingGid = gid
    )
    val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile)
    val finalMobile = if (cleanMob.length == 10) cleanMob else mobile
    return CustomerEntity(
        id = if (id > 0) id else 0,
        gid = canonicalGid,
        docId = canonicalDocId,
        serverId = null,
        shopId = shopId,
        name = name,
        mobile = finalMobile,
        email = email,
        address = address,
        notes = notes,
        totalBills = totalBills,
        lifetimeSpend = lifetimeSpend,
        outstandingBalance = outstandingBalance,
        firstPurchaseAt = firstPurchaseAt,
        lastPurchaseAt = lastPurchaseAt,
        isActive = isActive,
        needsReview = needsReview,
        syncStatus = SyncStatus.SYNCED
    )
}

fun ProductEntity.toDomain(): Product {
    val effectiveGid = if (!docId.isNullOrBlank()) {
        IdGenerator.productGid(docId)
    } else if (gid.isNotBlank() && !IdFormat.isLegacy(gid)) {
        gid
    } else if (gid.isNotBlank()) {
        gid
    } else {
        IdGenerator.generate(IdType.PRODUCT)
    }
    val effectiveAdded = if (totalStockAdded > 0) totalStockAdded else (currentStock + totalSold)
    return Product(
        id = id,
        gid = effectiveGid,
        shopId = shopId,
        categoryId = categoryId,
        categoryGid = categoryGid,
        name = name,
        sku = sku,
        sellingPrice = sellingPrice,
        costPrice = costPrice,
        defaultProfitMargin = defaultProfitMargin,
        trackInventory = trackInventory,
        currentStock = currentStock,
        totalStockAdded = effectiveAdded,
        totalSold = totalSold,
        lowStockThreshold = if (lowStockThreshold > 0) lowStockThreshold else 5,
        imageUrl = imageUrl,
        isActive = isActive
    )
}

fun Product.toEntity(): ProductEntity {
    val effectiveGid = if (!docId.isNullOrBlank()) {
        IdGenerator.productGid(docId)
    } else if (gid.isNotBlank() && !IdFormat.isLegacy(gid)) {
        gid
    } else if (gid.isNotBlank()) {
        gid
    } else {
        IdGenerator.generate(IdType.PRODUCT)
    }
    return ProductEntity(
        id = if (id > 0) id else 0,
        gid = effectiveGid,
        docId = docId,
        serverId = null,
        shopId = shopId,
        categoryId = categoryId,
        categoryGid = categoryGid,
        name = name,
        sku = sku,
        sellingPrice = sellingPrice,
        costPrice = costPrice,
        defaultProfitMargin = defaultProfitMargin,
        trackInventory = trackInventory,
        currentStock = currentStock,
        totalStockAdded = totalStockAdded,
        totalSold = totalSold,
        lowStockThreshold = lowStockThreshold,
        imageUrl = imageUrl,
        isActive = isActive
    )
}

fun CategoryEntity.toDomain(): Category {
    val effectiveGid = if (!docId.isNullOrBlank()) {
        IdGenerator.categoryGid(docId)
    } else if (gid.isNotBlank() && !IdFormat.isLegacy(gid)) {
        gid
    } else if (gid.isNotBlank()) {
        gid
    } else {
        IdGenerator.generate(IdType.CATEGORY)
    }
    return Category(
        id = id,
        gid = effectiveGid,
        shopId = shopId,
        name = name,
        description = description,
        isActive = isActive
    )
}

fun Category.toEntity(): CategoryEntity {
    val effectiveGid = if (!docId.isNullOrBlank()) {
        IdGenerator.categoryGid(docId)
    } else if (gid.isNotBlank() && !IdFormat.isLegacy(gid)) {
        gid
    } else if (gid.isNotBlank()) {
        gid
    } else {
        IdGenerator.generate(IdType.CATEGORY)
    }
    return CategoryEntity(
        id = if (id > 0) id else 0,
        gid = effectiveGid,
        docId = docId,
        serverId = null,
        shopId = shopId,
        name = name,
        description = description,
        isActive = isActive
    )
}

fun BillWithItems.toDomain(): Bill {
    val computedPaid = when (bill.paymentStatus) {
        PaymentStatus.PAID -> bill.finalAmount
        PaymentStatus.PENDING, PaymentStatus.UNPAID -> 0.0
        PaymentStatus.PARTIAL -> {
            if (bill.paidAmount > 0.0) bill.paidAmount
            else if (bill.pendingAmount > 0.0 && bill.pendingAmount < bill.finalAmount) bill.finalAmount - bill.pendingAmount
            else 0.0
        }
        else -> bill.paidAmount
    }
    val computedPending = when (bill.paymentStatus) {
        PaymentStatus.PAID -> 0.0
        PaymentStatus.PENDING, PaymentStatus.UNPAID -> bill.finalAmount
        PaymentStatus.PARTIAL -> {
            if (bill.pendingAmount > 0.0) bill.pendingAmount
            else (bill.finalAmount - computedPaid).coerceAtLeast(0.0)
        }
        else -> bill.pendingAmount
    }

    val effectiveBillGid = if (!bill.docId.isNullOrBlank()) {
        IdGenerator.billGid(bill.docId)
    } else if (bill.gid.isNotBlank() && !IdFormat.isLegacy(bill.gid)) {
        bill.gid
    } else if (bill.transactionUuid.isNotBlank()) {
        IdGenerator.billGid(bill.transactionUuid)
    } else if (bill.gid.isNotBlank()) {
        bill.gid
    } else {
        IdGenerator.generate(IdType.BILL)
    }

    return Bill(
        id = if (bill.serverId != null && bill.serverId > 0) bill.serverId else bill.id,
        gid = effectiveBillGid,
        docId = bill.docId,
        shopId = bill.shopId,
        customerId = bill.customerId,
        customerGid = bill.customerGid,
        customerName = bill.customerName,
        customerMobile = bill.customerMobile,
        billNumber = bill.billNumber,
        transactionUuid = bill.transactionUuid,
        saleType = bill.saleType,
        subtotal = bill.subtotal,
        discountType = bill.discountType,
        discountValue = bill.discountValue,
        discountAmount = bill.discountAmount,
        taxAmount = bill.taxAmount,
        finalAmount = bill.finalAmount,
        paidAmount = computedPaid,
        pendingAmount = computedPending,
        dueDate = null,
        costAmount = bill.costAmount,
        estimatedProfit = bill.estimatedProfit,
        actualProfit = bill.actualProfit,
        profitType = bill.profitType,
        paymentMethod = bill.paymentMethod,
        paymentStatus = bill.paymentStatus,
        note = bill.note,
        billDate = bill.billDate,
        shopNameSnapshot = bill.shopNameSnapshot,
        shopAddressSnapshot = bill.shopAddressSnapshot,
        shopMobileSnapshot = bill.shopMobileSnapshot,
        shopGstinSnapshot = bill.shopGstinSnapshot,
        showGstinSnapshot = bill.showGstinSnapshot,
        syncStatus = bill.syncStatus,
        isVoided = bill.isVoided,
        voidReason = bill.voidReason,
        needsReview = bill.needsReview,
        items = items.map {
            val itemGid = if (it.gid.isNotBlank()) it.gid else if (it.id > 0) IdGenerator.legacyGid(IdType.BILL_ITEM, "item_${effectiveBillGid}_${it.id}") else IdGenerator.generate(IdType.BILL_ITEM)
            BillItem(
                id = it.id,
                gid = itemGid,
                billId = it.billId,
                billGid = if (it.billGid.isNotBlank()) it.billGid else effectiveBillGid,
                productId = it.productId,
                productGid = it.productGid,
                productName = it.productNameSnapshot,
                sku = it.skuSnapshot,
                categoryId = it.categoryId,
                quantity = it.quantity,
                sellingPrice = it.sellingPrice,
                costPrice = it.costPrice,
                discountAmount = it.discountAmount,
                lineTotal = it.lineTotal,
                lineCost = it.lineCost,
                lineProfit = it.lineProfit
            )
        }
    )
}

fun toBillEntities(
    bill: Bill,
    syncStatus: SyncStatus = SyncStatus.SYNCED,
    createdAt: Long? = null
): Pair<BillEntity, List<BillItemEntity>> {
    val effectiveCreatedAt = createdAt ?: com.matoshree.shopmanager.utils.DateUtils.parseDateToMillis(bill.billDate).takeIf { it > 0 } ?: System.currentTimeMillis()
    val effectiveBillDate = if (bill.billDate.isNotBlank()) {
        bill.billDate
    } else {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH).apply {
            timeZone = com.matoshree.shopmanager.utils.DateUtils.kolkataTimeZone
        }
        sdf.format(java.util.Date(effectiveCreatedAt))
    }
    val effectiveBillGid = if (!bill.docId.isNullOrBlank()) {
        IdGenerator.billGid(bill.docId)
    } else if (bill.gid.isNotBlank() && !IdFormat.isLegacy(bill.gid)) {
        bill.gid
    } else if (bill.transactionUuid.isNotBlank()) {
        IdGenerator.billGid(bill.transactionUuid)
    } else if (bill.gid.isNotBlank()) {
        bill.gid
    } else {
        IdGenerator.generate(IdType.BILL)
    }
    val billEntity = BillEntity(
        id = 0L,
        gid = effectiveBillGid,
        docId = bill.docId,
        serverId = bill.id,
        shopId = bill.shopId,
        customerId = bill.customerId,
        customerGid = bill.customerGid,
        customerName = bill.customerName,
        customerMobile = bill.customerMobile,
        billNumber = bill.billNumber,
        transactionUuid = bill.transactionUuid,
        saleType = bill.saleType,
        subtotal = bill.subtotal,
        discountType = bill.discountType,
        discountValue = bill.discountValue,
        discountAmount = bill.discountAmount,
        taxAmount = bill.taxAmount,
        finalAmount = bill.finalAmount,
        paidAmount = bill.paidAmount,
        pendingAmount = bill.pendingAmount,
        costAmount = bill.costAmount,
        estimatedProfit = bill.estimatedProfit,
        actualProfit = bill.actualProfit,
        profitType = bill.profitType,
        paymentMethod = bill.paymentMethod,
        paymentStatus = bill.paymentStatus,
        note = bill.note,
        billDate = effectiveBillDate,
        shopNameSnapshot = bill.shopNameSnapshot,
        shopAddressSnapshot = bill.shopAddressSnapshot,
        shopMobileSnapshot = bill.shopMobileSnapshot,
        shopGstinSnapshot = bill.shopGstinSnapshot,
        showGstinSnapshot = bill.showGstinSnapshot,
        syncStatus = syncStatus,
        isVoided = bill.isVoided,
        voidReason = bill.voidReason,
        needsReview = bill.needsReview,
        createdAt = effectiveCreatedAt
    )
    val itemEntities = bill.items.map { item ->
        val itemGid = if (item.gid.isNotBlank()) item.gid else IdGenerator.generate(IdType.BILL_ITEM)
        BillItemEntity(
            id = 0,
            gid = itemGid,
            billId = 0L,
            billGid = effectiveBillGid,
            productId = item.productId,
            productGid = item.productGid,
            productNameSnapshot = item.productName,
            skuSnapshot = item.sku,
            categoryId = item.categoryId,
            quantity = item.quantity,
            sellingPrice = item.sellingPrice,
            costPrice = item.costPrice,
            discountAmount = item.discountAmount,
            lineTotal = item.lineTotal,
            lineCost = item.lineCost,
            lineProfit = item.lineProfit
        )
    }
    return Pair(billEntity, itemEntities)
}

class BillRepository(
    private val database: AppDatabase? = null,
    private val firestoreRepo: FirestoreBillRepository = FirestoreBillRepository()
) {
    fun getRecentBills(limit: Int = 100): Flow<List<Bill>> {
        if (database == null) return firestoreRepo.getRecentBills(limit)
        return database.billDao().getRecentBills(limit).map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged()
    }

    fun getAllBills(limit: Int = 10000): Flow<List<Bill>> {
        if (database == null) return firestoreRepo.getAllBills(limit)
        return database.billDao().getAllBills(limit).map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged()
    }

    fun getTodayBills(todayDatePrefix: String): Flow<List<Bill>> {
        if (database == null) return kotlinx.coroutines.flow.flowOf(emptyList())
        return database.billDao().getTodayBillsFlow(todayDatePrefix).map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged()
    }

    fun getWeekBills(startDate: String): Flow<List<Bill>> {
        if (database == null) return kotlinx.coroutines.flow.flowOf(emptyList())
        return database.billDao().getWeekBillsFlow(startDate).map { entities ->
            entities.map { it.toDomain() }
        }.distinctUntilChanged()
    }

    suspend fun getBillById(id: Long): Bill? {
        val cloudBill = try {
            firestoreRepo.getBillById(id)
        } catch (e: Exception) {
            null
        }
        if (cloudBill != null) return cloudBill
        return database?.billDao()?.getBillById(id)?.toDomain()
            ?: database?.billDao()?.getBillByServerId(id)?.toDomain()
    }

    suspend fun createSale(
        saleType: SaleType,
        customer: Customer?,
        items: List<BillItem>,
        discountType: DiscountType = DiscountType.NONE,
        discountValue: Double = 0.0,
        discountAmount: Double = 0.0,
        quickSaleAmount: Double?,
        paymentMethod: PaymentMethod,
        note: String?,
        paymentStatus: PaymentStatus = PaymentStatus.PAID,
        paidAmount: Double? = null,
        dueDate: String? = null
    ): Bill {
        val isOnline = com.matoshree.shopmanager.data.firebase.FirebaseManager.isNetworkAvailable()
        var cloudBill: Bill? = null

        if (isOnline) {
            try {
                cloudBill = firestoreRepo.createSale(
                    saleType = saleType,
                    customer = customer,
                    items = items,
                    discountType = discountType,
                    discountValue = discountValue,
                    discountAmount = discountAmount,
                    quickSaleAmount = quickSaleAmount,
                    paymentMethod = paymentMethod,
                    note = note,
                    paymentStatus = paymentStatus,
                    paidAmount = paidAmount,
                    dueDate = dueDate
                )
            } catch (e: Exception) {
                Log.w("BillRepo", "Cloud sale sync note: ${e.message}. Saving locally in Room as PENDING.")
            }
        }

        val billToSave = cloudBill ?: createLocalBill(
            saleType, customer, items, discountType, discountValue, discountAmount,
            quickSaleAmount, paymentMethod, note, paymentStatus, paidAmount, dueDate
        )

        val targetStatus = if (cloudBill != null) SyncStatus.SYNCED else SyncStatus.PENDING
        var localRoomId: Long = billToSave.id

        database?.let { db ->
            try {
                db.withTransaction {
                    localRoomId = cacheBillToRoom(db, billToSave, targetStatus)
                    items.forEach { item ->
                        item.productId?.let { pid ->
                            db.productDao().recordSaleStockDeduction(pid, item.quantity)
                        }
                    }
                    if (billToSave.customerId != null && billToSave.customerId > 0) {
                        db.customerDao().incrementCustomerSpend(billToSave.customerId, billToSave.finalAmount, billToSave.billDate)
                    }
                }
            } catch (re: Exception) {
                Log.e("BillRepo", "Error persisting bill to Room: ${re.message}", re)
                if (cloudBill == null) {
                    throw re
                }
            }
        }

        return billToSave.copy(id = localRoomId)
    }

    private suspend fun cacheBillToRoom(database: AppDatabase, bill: Bill, syncStatus: SyncStatus = SyncStatus.SYNCED): Long {
        val pair = toBillEntities(bill, syncStatus)
        return database.billDao().upsertServerBill(pair.first, pair.second)
        // Note: incrementCustomerSpend is deliberately NOT called here to prevent double-counting during sync
    }

    private fun createLocalBill(
        saleType: SaleType,
        customer: Customer?,
        items: List<BillItem>,
        discountType: DiscountType,
        discountValue: Double,
        discountAmount: Double,
        quickSaleAmount: Double?,
        paymentMethod: PaymentMethod,
        note: String?,
        paymentStatus: PaymentStatus,
        paidAmount: Double?,
        dueDate: String?
    ): Bill {
        val nowIso = DateUtils.nowIso()
        val year = nowIso.take(4)
        val seq = ((System.currentTimeMillis() / 1000) % 900000 + 100000).toInt()
        val billNumber = "MC-$year-$seq"
        val txUuid = UUID.randomUUID().toString()

        val subtotal: Double
        val finalAmount: Double
        if (saleType == SaleType.DETAILED) {
            subtotal = items.sumOf { it.sellingPrice * it.quantity }
            finalAmount = (subtotal - discountAmount).coerceAtLeast(0.0)
        } else {
            val amt = quickSaleAmount ?: 0.0
            subtotal = amt
            finalAmount = amt
        }

        val actualPaid = when (paymentStatus) {
            PaymentStatus.PAID -> finalAmount
            PaymentStatus.PARTIAL -> (paidAmount ?: 0.0).coerceIn(0.0, finalAmount)
            PaymentStatus.PENDING, PaymentStatus.UNPAID -> 0.0
            else -> finalAmount
        }
        val actualPending = (finalAmount - actualPaid).coerceAtLeast(0.0)

        return Bill(
            id = System.currentTimeMillis(),
            shopId = 1,
            customerId = customer?.id,
            customerName = customer?.name,
            customerMobile = customer?.mobile,
            billNumber = billNumber,
            transactionUuid = txUuid,
            saleType = saleType,
            subtotal = subtotal,
            discountType = discountType,
            discountValue = discountValue,
            discountAmount = discountAmount,
            taxAmount = 0.0,
            finalAmount = finalAmount,
            paidAmount = actualPaid,
            pendingAmount = actualPending,
            dueDate = dueDate,
            costAmount = items.sumOf { (it.costPrice ?: (it.sellingPrice * 0.75)) * it.quantity },
            estimatedProfit = (finalAmount * 0.25),
            actualProfit = 0.0,
            profitType = ProfitType.ESTIMATED,
            paymentMethod = paymentMethod,
            paymentStatus = paymentStatus,
            note = note,
            billDate = nowIso,
            shopNameSnapshot = "मातोश्री कलेक्शन",
            shopAddressSnapshot = "कोंढारकी",
            shopMobileSnapshot = "8208610261",
            shopGstinSnapshot = null,
            showGstinSnapshot = false,
            syncStatus = SyncStatus.PENDING,
            isVoided = false,
            voidReason = null,
            items = items
        )
    }

    suspend fun voidBill(
        billId: Long,
        reason: String = "Voided by manager",
        billNumber: String? = null,
        transactionUuid: String? = null
    ) {
        val localBill = database?.billDao()?.getBillById(billId)
            ?: database?.billDao()?.getBillByServerId(billId)
            ?: (if (!billNumber.isNullOrBlank()) database?.billDao()?.getBillByNumber(billNumber) else null)
            ?: (if (!transactionUuid.isNullOrBlank()) database?.billDao()?.getBillByUuid(transactionUuid) else null)

        val targetBillNumber = localBill?.bill?.billNumber ?: billNumber
        val targetUuid = localBill?.bill?.transactionUuid ?: transactionUuid

        try {
            localBill?.items?.forEach { item ->
                item.productId?.let { pid ->
                    database?.productDao()?.recordSaleStockRestore(pid, item.quantity)
                }
            }
        } catch (e: Exception) {
            Log.w("BillRepo", "Room stock restore note: ${e.message}")
        }
        try {
            firestoreRepo.voidBill(
                billId = billId,
                reason = reason,
                billNumber = targetBillNumber,
                transactionUuid = targetUuid
            )
        } catch (e: Exception) {
            Log.w("BillRepo", "Cloud void sync note: ${e.message}")
        }
        val targetLocalId = localBill?.bill?.id ?: billId
        database?.billDao()?.voidBillComprehensive(targetLocalId, targetUuid, targetBillNumber, reason)
    }

    suspend fun recomputeStoredBillValuesFromLedger(transactions: List<UdhariTransaction> = emptyList()): Int {
        val db = database ?: return 0
        val allBillsWithItems = db.billDao().getAllBillsDirect(50000)
        val allBills = allBillsWithItems.map { it.toDomain() }
        val allCusts = db.customerDao().getAllCustomersDirect().map { it.toDomain() }
        val allTxs = if (transactions.isNotEmpty()) {
            transactions
        } else {
            try {
                if (FirebaseManager.isNetworkAvailable()) {
                    FirebaseManager.ensureAuthenticated()
                    val snap = FirebaseManager.udhariTransactionsCollection().limit(3000).get().await()
                    snap.documents.mapNotNull { doc ->
                        doc.toObject(UdhariTransactionDto::class.java)?.apply { id = doc.id }?.toDomain()
                    }
                } else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        val shopLedger = com.matoshree.shopmanager.domain.usecase.UdhariLedger.computeShopLedger(allBills, allTxs, allCusts)
        var updatedCount = 0

        for (b in allBills) {
            val key = com.matoshree.shopmanager.domain.usecase.UdhariLedger.resolveCustomerKey(b.customerGid, b.customerMobile, b.customerName, b.customerId)
            val custResult = shopLedger.customerResults[key]
            val breakdown = custResult?.billBreakdowns?.find { it.billId == b.id || (it.billNumber.isNotBlank() && it.billNumber == b.billNumber) }

            if (breakdown != null) {
                val newPaid = breakdown.checkoutPaid + breakdown.directPayments + breakdown.fifoPayments
                val newPending = breakdown.remainingOutstanding
                val newStatus = if (newPending <= 0.01) PaymentStatus.PAID.name else if (newPaid > 0.01) PaymentStatus.PARTIAL.name else PaymentStatus.PENDING.name

                if (Math.abs(b.pendingAmount - newPending) > 0.01 || Math.abs(b.paidAmount - newPaid) > 0.01 || b.paymentStatus.name != newStatus) {
                    db.billDao().updateBillPaymentAmounts(b.id, b.transactionUuid, b.billNumber, newPaid, newPending, newStatus)
                    updatedCount++
                }
            }
        }
        return updatedCount
    }
}

class UdhariRepository(
    private val firestoreRepo: FirestoreUdhariRepository = FirestoreUdhariRepository(),
    private val syncManager: com.matoshree.shopmanager.sync.SyncManager? = null
) {
    fun getAllTransactions(): Flow<List<UdhariTransaction>> = syncManager?.allUdhariTransactions ?: firestoreRepo.getAllTransactions()
    fun getTransactionsForCustomer(customerId: Long, customerMobile: String? = null): Flow<List<UdhariTransaction>> = firestoreRepo.getTransactionsForCustomer(customerId, customerMobile)
    fun getTransactionsForBill(billId: Long): Flow<List<UdhariTransaction>> = firestoreRepo.getTransactionsForBill(billId)
    suspend fun recordPayment(payment: UdhariTransaction): Result<Unit> = firestoreRepo.recordPayment(payment)
    suspend fun reversePayment(transactionId: String, reason: String): Result<Unit> = firestoreRepo.reversePayment(transactionId, reason)
    suspend fun setCustomerCreditLimit(customerId: Long, creditLimit: Double?): Result<Unit> = firestoreRepo.setCustomerCreditLimit(customerId, creditLimit)
    suspend fun recordManualDebit(
        customer: Customer,
        amount: Double,
        note: String? = null,
        date: String? = null,
        dueDate: String? = null
    ): Result<UdhariTransaction> = firestoreRepo.recordManualDebit(customer, amount, note, date, dueDate)
    suspend fun deleteOrReverseManualUdhari(transactionId: String, reason: String = "Deleted by user"): Result<Unit> = firestoreRepo.deleteOrReverseManualUdhari(transactionId, reason)
    suspend fun updateUdhariDueDate(transactionId: String, newDueDate: String?): Result<Unit> = firestoreRepo.updateUdhariDueDate(transactionId, newDueDate)
}

class CustomerRepository(
    private val database: AppDatabase? = null,
    private val firestoreRepo: FirestoreCustomerRepository = FirestoreCustomerRepository()
) {
    private val reconciledKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val repoScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun getAllCustomers(): Flow<List<Customer>> {
        if (database == null) return firestoreRepo.getAllCustomers()
        return database.customerDao().getAllCustomers().map { entities ->
            entities.map { it.toDomain() }
                .filter { it.isActive && it.name.isNotBlank() && !it.name.equals("Walk-in", ignoreCase = true) }
                .distinctBy { it.gid }
                .sortedWith { c1, c2 ->
                    com.matoshree.shopmanager.utils.AlphabetUtils.compareAlphabetically(c1.name, c2.name)
                }
        }.distinctUntilChanged()
    }

    suspend fun searchCustomers(query: String): List<Customer> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()

        // Read from local Room database ONLY (single source of truth, avoids duplicate results)
        val localList = database?.customerDao()?.searchCustomers(cleanQuery)?.map { it.toDomain() }
            ?: firestoreRepo.searchCustomers(cleanQuery)

        return localList
            .filter { it.isActive && it.name.isNotBlank() && !it.name.equals("Walk-in", ignoreCase = true) }
            .distinctBy { if (it.gid.isNotBlank()) it.gid else (it.docId ?: it.id.toString()) }
            .sortedWith { c1, c2 ->
                com.matoshree.shopmanager.utils.AlphabetUtils.compareAlphabetically(c1.name, c2.name)
            }
    }

    suspend fun findCustomerByMobile(mobile: String): Customer? {
        val cloud = try {
            firestoreRepo.findCustomerByMobile(mobile)
        } catch (e: Exception) {
            null
        }
        if (cloud != null) return cloud
        val clean = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile)
        return database?.customerDao()?.getCustomerByMobile(clean)?.toDomain()
    }

    suspend fun findAnyCustomerByMobile(mobile: String): Customer? {
        val cloud = try {
            firestoreRepo.findAnyCustomerByMobile(mobile)
        } catch (e: Exception) {
            null
        }
        if (cloud != null) return cloud
        val clean = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile)
        return database?.customerDao()?.getAnyCustomerByMobile(clean)?.toDomain()
    }

    suspend fun saveCustomer(customer: Customer): Long {
        val (canonicalDocId, canonicalGid) = IdGenerator.canonicalCustomerDocIdAndGid(
            mobile = customer.mobile,
            existingDocId = customer.docId,
            existingGid = customer.gid
        )
        val preparedCustomer = customer.copy(
            docId = canonicalDocId,
            gid = canonicalGid
        )

        var localId = preparedCustomer.id
        val isOnline = FirebaseManager.isNetworkAvailable()
        val initialStatus = if (isOnline) SyncStatus.SYNCED else SyncStatus.PENDING

        if (database != null) {
            try {
                val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(preparedCustomer.mobile)
                val existing = (if (preparedCustomer.id > 0) database.customerDao().getCustomerById(preparedCustomer.id) else null)
                    ?: (if (preparedCustomer.id > 0) database.customerDao().getCustomerByServerId(preparedCustomer.id) else null)
                    ?: (if (cleanMob.length == 10) database.customerDao().getCustomerByMobileFuzzy(preparedCustomer.mobile, cleanMob) else null)
                    ?: database.customerDao().getCustomerByGid(canonicalGid)
                    ?: database.customerDao().getCustomerByDocId(canonicalDocId)

                val targetLocalId = existing?.id ?: if (preparedCustomer.id > 0 && preparedCustomer.id < 1_000_000_000L) preparedCustomer.id else 0L
                val entity = preparedCustomer.toEntity().copy(
                    id = targetLocalId,
                    serverId = existing?.serverId ?: if (preparedCustomer.id >= 1_000_000_000L) preparedCustomer.id else null,
                    isActive = true,
                    syncStatus = initialStatus
                )
                localId = database.customerDao().insertCustomer(entity)
            } catch (e: Exception) {
                Log.w("CustomerRepo", "Room customer insert note: ${e.message}")
            }
        }
        try {
            val syncCust = if (preparedCustomer.id > 0) preparedCustomer else preparedCustomer.copy(id = localId)
            val docId = firestoreRepo.saveCustomer(syncCust)
            val mappedId = (docId.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
            if (database != null && localId > 0) {
                database.customerDao().updateSyncStatus(localId, mappedId, SyncStatus.SYNCED)
            }
            return if (localId > 0) localId else mappedId
        } catch (e: Exception) {
            Log.w("CustomerRepo", "Cloud customer save pending (offline/rules): ${e.message}")
            if (database != null && localId > 0) {
                database.customerDao().updateSyncStatus(localId, localId, SyncStatus.PENDING)
            }
            return if (localId > 0) localId else System.currentTimeMillis()
        }
    }

    suspend fun updateCustomerTags(customerGid: String, tags: List<String>): Result<Unit> {
        val cust = database?.customerDao()?.getCustomerByGid(customerGid)?.toDomain()
        val legacyId = cust?.id ?: (if (customerGid.startsWith("id_")) customerGid.removePrefix("id_").toLongOrNull() ?: 0L else 0L)
        return try {
            firestoreRepo.updateCustomerTags(customerGid, tags)
            if (legacyId > 0) firestoreRepo.updateCustomerTags(legacyId, tags)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun updateCustomerTags(customerId: Long, tags: List<String>): Result<Unit> {
        return try {
            firestoreRepo.updateCustomerTags(customerId, tags)
        } catch (e: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun deleteOrArchiveCustomer(
        customerGid: String,
        hintMobile: String = "",
        hintName: String = ""
    ): String {
        val cust = database?.customerDao()?.getCustomerByGid(customerGid)?.toDomain()
            ?: (if (hintMobile.isNotBlank()) database?.customerDao()?.getCustomerByMobile(hintMobile)?.toDomain() else null)
        val targetId = cust?.id ?: (if (customerGid.startsWith("id_")) customerGid.removePrefix("id_").toLongOrNull() ?: 0L else 0L)
        return deleteOrArchiveCustomer(targetId, cust?.mobile ?: hintMobile, cust?.name ?: hintName)
    }

    suspend fun deleteOrArchiveCustomer(
        customerId: Long,
        hintMobile: String = "",
        hintName: String = ""
    ): String {
        val cust = database?.customerDao()?.getCustomerById(customerId)?.toDomain()
            ?: database?.customerDao()?.getCustomerByServerId(customerId)?.toDomain()
            ?: (if (hintMobile.isNotBlank()) database?.customerDao()?.getCustomerByMobile(hintMobile)?.toDomain() else null)

        val targetName = cust?.name ?: hintName
        val targetMobile = cust?.mobile ?: hintMobile
        val cleanMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(targetMobile)
        val targetId = cust?.id ?: customerId

        // 1. Restore product stock locally in Room for all bills of this customer, then delete bills
        if (database != null) {
            try {
                val localBills = database.billDao().getBillsByCustomer(targetId, cleanMobile)
                localBills.forEach { bWithItems ->
                    if (!bWithItems.bill.isVoided) {
                        bWithItems.items.forEach { item ->
                            item.productId?.let { pid ->
                                database.productDao().recordSaleStockRestore(pid, item.quantity)
                            }
                        }
                    }
                    database.billDao().deleteBillItems(bWithItems.bill.id)
                }
                database.billDao().deleteBillsByCustomer(targetId, cleanMobile)
                database.customerDao().resetCustomerStats(targetId)
                database.customerDao().archiveCustomer(targetId)
                database.customerDao().hardDeleteCustomer(targetId)
            } catch (e: Exception) {
                Log.w("CustomerRepo", "Local cascade delete note: ${e.message}")
            }
        }

        // 2. Cascade delete in Firestore (restores cloud stock, deletes cloud bills & udhari txs, moves customer to TRASH)
        try {
            firestoreRepo.deleteOrArchiveCustomer(
                customerId = targetId,
                hintMobile = cleanMobile,
                hintName = targetName
            )
        } catch (e: Exception) {
            Log.w("CustomerRepo", "Cloud customer delete note: ${e.message}")
        }
        return "Customer deleted"
    }

    suspend fun purgeCustomerHistory(customerId: Long, mobile: String, name: String = "") {
        val cleanMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile)
        if (database != null) {
            try {
                val localBills = database.billDao().getBillsByCustomerOrDetails(customerId, cleanMobile, name)
                localBills.forEach { bWithItems ->
                    if (!bWithItems.bill.isVoided) {
                        bWithItems.items.forEach { item ->
                            item.productId?.let { pid ->
                                database.productDao().recordSaleStockRestore(pid, item.quantity)
                            }
                        }
                    }
                    database.billDao().deleteBillItems(bWithItems.bill.id)
                }
                database.billDao().deleteBillsByCustomerOrDetails(customerId, cleanMobile, name)
                database.customerDao().resetCustomerStats(customerId)
            } catch (e: Exception) {
                Log.w("CustomerRepo", "Local purge history note: ${e.message}")
            }
        }
        try {
            firestoreRepo.purgeCustomerHistory(customerId, cleanMobile, name)
        } catch (e: Exception) {
            Log.w("CustomerRepo", "Cloud purge history note: ${e.message}")
        }
    }

    suspend fun purgeSpecificCustomerPermanently(name: String, mobile: String) {
        val cleanMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile)
        if (database != null) {
            try {
                val localBills = database.billDao().getBillsByCustomerOrDetails(0L, cleanMobile, name)
                localBills.forEach { bWithItems ->
                    if (!bWithItems.bill.isVoided) {
                        bWithItems.items.forEach { item ->
                            item.productId?.let { pid ->
                                database.productDao().recordSaleStockRestore(pid, item.quantity)
                            }
                        }
                    }
                    database.billDao().deleteBillItems(bWithItems.bill.id)
                }
                database.billDao().deleteBillsByCustomerOrDetails(0L, cleanMobile, name)
                database.customerDao().hardDeleteCustomerByDetails(cleanMobile)
            } catch (e: Exception) {
                Log.w("CustomerRepo", "Local purge specific customer note: ${e.message}")
            }
        }
        try {
            firestoreRepo.purgeSpecificCustomerPermanently(name, cleanMobile)
        } catch (e: Exception) {
            Log.w("CustomerRepo", "Cloud purge specific customer note: ${e.message}")
        }
    }

    suspend fun unTrashCustomerIfAccidental(name: String) {
        try {
            firestoreRepo.unTrashCustomerIfAccidental(name)
            database?.customerDao()?.unarchiveCustomerByName(name)
        } catch (e: Exception) {
            Log.w("CustomerRepo", "unTrashCustomerIfAccidental note: ${e.message}")
        }
    }
}

class ProductRepository(
    private val database: AppDatabase? = null,
    private val firestoreRepo: FirestoreProductRepository = FirestoreProductRepository()
) {
    fun getAllProducts(): Flow<List<Product>> {
        if (database == null) return firestoreRepo.getAllProducts()
        return flow {
            // First emit cached products from Room immediately
            try {
                val localProds = database.productDao().searchProducts("")
                if (localProds.isNotEmpty()) {
                    emit(localProds.map { it.toDomain() })
                }
            } catch (e: Exception) {
                Log.w("ProductRepo", "Local products load note: ${e.message}")
            }

            // Then collect from Firestore and cache to Room only if missing or changed
            try {
                firestoreRepo.getAllProducts().collect { cloudProds ->
                    if (cloudProds.isNotEmpty()) {
                        cloudProds.forEach { p ->
                            try {
                                val existing = if (!p.sku.isNullOrBlank()) database.productDao().getProductBySku(p.sku) else null
                                val needsUpsert = existing == null ||
                                    existing.name != p.name ||
                                    Math.abs(existing.sellingPrice - p.sellingPrice) > 0.01 ||
                                    existing.currentStock != p.currentStock ||
                                    existing.isActive != p.isActive

                                if (needsUpsert) {
                                    database.productDao().upsertServerProduct(p.toEntity())
                                }
                            } catch (e: Exception) {}
                        }
                        emit(cloudProds)
                    }
                }
            } catch (e: Exception) {
                // Keep observing Room if cloud listener encounters error
                database.productDao().getAllProducts().collect { entities ->
                    emit(entities.map { it.toDomain() })
                }
            }
        }
    }

    fun getAllCategories(): Flow<List<Category>> {
        if (database == null) return firestoreRepo.getAllCategories()
        return flow {
            try {
                firestoreRepo.getAllCategories().collect { cloudCats ->
                    if (cloudCats.isNotEmpty()) {
                        cloudCats.forEach { c ->
                            try {
                                val existing = database.productDao().getCategoryByName(c.name)
                                val needsUpsert = existing == null ||
                                    existing.description != c.description ||
                                    existing.isActive != c.isActive

                                if (needsUpsert) {
                                    database.productDao().upsertServerCategory(CategoryEntity(name = c.name, description = c.description, isActive = c.isActive))
                                }
                            } catch (e: Exception) {}
                        }
                        emit(cloudCats)
                    }
                }
            } catch (e: Exception) {
                database.productDao().getAllCategories().collect { entities ->
                    emit(entities.map { it.toDomain() })
                }
            }
        }
    }

    suspend fun searchProducts(query: String): List<Product> {
        val cloud = try {
            firestoreRepo.searchProducts(query)
        } catch (e: Exception) {
            emptyList()
        }
        if (cloud.isNotEmpty()) return cloud
        return database?.productDao()?.searchProducts(query)?.map { it.toDomain() } ?: emptyList()
    }

    suspend fun getProductById(id: Long): Product? {
        val cloud = try {
            firestoreRepo.getProductById(id)
        } catch (e: Exception) {
            null
        }
        if (cloud != null) return cloud
        return database?.productDao()?.getProductById(id)?.toDomain()
    }

    suspend fun saveProduct(product: Product): Long {
        var localId = product.id
        if (database != null) {
            try {
                val entity = product.toEntity()
                localId = database.productDao().insertProduct(entity)
            } catch (e: Exception) {
                Log.w("ProductRepo", "Room product insert note: ${e.message}")
            }
        }
        try {
            val syncProd = if (product.id > 0) product else product.copy(id = localId)
            val docId = firestoreRepo.saveProduct(syncProd)
            val mappedId = (docId.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
            return if (localId > 0) localId else mappedId
        } catch (e: Exception) {
            Log.w("ProductRepo", "Cloud product save pending (offline/rules): ${e.message}")
            return if (localId > 0) localId else System.currentTimeMillis()
        }
    }

    suspend fun saveCategory(name: String, description: String? = null): Long {
        var localId = 0L
        if (database != null) {
            try {
                val entity = CategoryEntity(name = name.trim(), description = description?.trim(), isActive = true)
                localId = database.productDao().insertCategory(entity)
            } catch (e: Exception) {
                Log.w("ProductRepo", "Room category insert note: ${e.message}")
            }
        }
        try {
            val docId = firestoreRepo.saveCategory(name, description)
            val mappedId = (docId.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
            return if (localId > 0) localId else mappedId
        } catch (e: Exception) {
            Log.w("ProductRepo", "Cloud category save pending (offline/rules): ${e.message}")
            return if (localId > 0) localId else System.currentTimeMillis()
        }
    }

    suspend fun renameCategory(id: Long, newName: String) {
        try {
            firestoreRepo.renameCategory(id, newName)
        } catch (e: Exception) {
            Log.w("ProductRepo", "Cloud category rename note: ${e.message}")
        }
        database?.productDao()?.renameCategory(id, newName)
    }

    suspend fun deleteCategory(id: Long) {
        try {
            firestoreRepo.deleteCategory(id)
        } catch (e: Exception) {
            Log.w("ProductRepo", "Cloud category delete note: ${e.message}")
        }
        database?.productDao()?.deleteCategory(id)
    }

    suspend fun updateProductCategory(productId: Long, newCategoryId: Long?) {
        try {
            firestoreRepo.updateProductCategory(productId, newCategoryId)
        } catch (e: Exception) {
            Log.w("ProductRepo", "Cloud product category update note: ${e.message}")
        }
        database?.productDao()?.updateProductCategory(productId, newCategoryId)
    }

    suspend fun deleteOrArchiveProduct(productId: Long): String {
        try {
            firestoreRepo.deleteOrArchiveProduct(productId)
        } catch (e: Exception) {
            Log.w("ProductRepo", "Cloud product delete note: ${e.message}")
        }
        database?.productDao()?.archiveProduct(productId)
        return "Product deleted"
    }

    suspend fun updateProductsCategory(productIds: List<Long>, categoryId: Long?): Result<Unit> {
        return try {
            firestoreRepo.updateProductsCategory(productIds, categoryId)
        } catch (e: Exception) {
            Result.success(Unit)
        }
    }

    suspend fun deleteProductsBatch(productIds: List<Long>): Result<Int> {
        return try {
            firestoreRepo.deleteProductsBatch(productIds)
        } catch (e: Exception) {
            Result.success(productIds.size)
        }
    }

    suspend fun adjustStock(
        productId: Long,
        delta: Int,
        newExactStock: Int? = null,
        type: com.matoshree.shopmanager.domain.model.StockChangeType = com.matoshree.shopmanager.domain.model.StockChangeType.RESTOCK,
        note: String? = null,
        referenceId: String? = null
    ): Result<Unit> {
        val result = firestoreRepo.adjustStock(productId, delta, newExactStock, type, note, referenceId)
        if (database != null) {
            try {
                val prod = database.productDao().getProductById(productId)
                if (prod != null) {
                    val finalStock = if (newExactStock != null) newExactStock.coerceAtLeast(0) else (prod.currentStock + delta).coerceAtLeast(0)
                    val actualDelta = finalStock - prod.currentStock
                    val addedDelta = if (type == com.matoshree.shopmanager.domain.model.StockChangeType.RESTOCK) delta else if (actualDelta > 0) actualDelta else 0
                    database.productDao().updateStockQuantity(productId, finalStock, addedDelta)
                }
            } catch (e: Exception) {
                Log.w("ProductRepo", "Room stock update note: ${e.message}")
            }
        }
        return result
    }

    fun getStockMovements(productId: Long? = null): Flow<List<com.matoshree.shopmanager.domain.model.StockMovement>> {
        return firestoreRepo.getStockMovements(productId)
    }
}

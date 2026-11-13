package com.matoshree.shopmanager.data.repository

import android.util.Log
import com.matoshree.shopmanager.data.firebase.*
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.dao.BillWithItems
import com.matoshree.shopmanager.data.local.entity.*
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import java.util.UUID

// Extension mappers between Room Entities and Domain Models
fun CustomerEntity.toDomain(): Customer {
    return Customer(
        id = id,
        shopId = shopId,
        name = name,
        mobile = mobile,
        email = email,
        address = address,
        notes = notes,
        totalBills = totalBills,
        lifetimeSpend = lifetimeSpend,
        creditLimit = null,
        outstandingBalance = 0.0,
        firstPurchaseAt = firstPurchaseAt,
        lastPurchaseAt = lastPurchaseAt,
        isActive = isActive,
        tier = "REGULAR" // VIP tag is never auto-assigned; only set if shop owner assigns it
    )
}

fun Customer.toEntity(): CustomerEntity {
    return CustomerEntity(
        id = if (id > 0) id else 0,
        serverId = null,
        shopId = shopId,
        name = name,
        mobile = mobile,
        email = email,
        address = address,
        notes = notes,
        totalBills = totalBills,
        lifetimeSpend = lifetimeSpend,
        firstPurchaseAt = firstPurchaseAt,
        lastPurchaseAt = lastPurchaseAt,
        isActive = isActive,
        syncStatus = SyncStatus.SYNCED
    )
}

fun ProductEntity.toDomain(): Product {
    val effectiveAdded = if (totalStockAdded > 0) totalStockAdded else (currentStock + totalSold)
    return Product(
        id = id,
        shopId = shopId,
        categoryId = categoryId,
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
    return ProductEntity(
        id = if (id > 0) id else 0,
        serverId = null,
        shopId = shopId,
        categoryId = categoryId,
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
    return Category(
        id = id,
        shopId = shopId,
        name = name,
        description = description,
        isActive = isActive
    )
}

fun BillWithItems.toDomain(): Bill {
    return Bill(
        id = if (bill.serverId != null && bill.serverId > 0) bill.serverId else bill.id,
        shopId = bill.shopId,
        customerId = bill.customerId,
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
        paidAmount = if (bill.paymentStatus == PaymentStatus.PAID) bill.finalAmount else 0.0,
        pendingAmount = if (bill.paymentStatus == PaymentStatus.PAID) 0.0 else bill.finalAmount,
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
        items = items.map {
            BillItem(
                id = it.id,
                billId = it.billId,
                productId = it.productId,
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
    val billEntity = BillEntity(
        id = 0L,
        serverId = bill.id,
        shopId = bill.shopId,
        customerId = bill.customerId,
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
        syncStatus = syncStatus,
        isVoided = bill.isVoided,
        voidReason = bill.voidReason,
        createdAt = effectiveCreatedAt
    )
    val itemEntities = bill.items.map { item ->
        BillItemEntity(
            id = 0,
            billId = 0L,
            productId = item.productId,
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

    fun getAllBills(limit: Int = 500): Flow<List<Bill>> {
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

        database?.let { db ->
            try {
                cacheBillToRoom(db, billToSave, targetStatus)
                items.forEach { item ->
                    item.productId?.let { pid ->
                        db.productDao().recordSaleStockDeduction(pid, item.quantity)
                    }
                }
                if (billToSave.customerId != null && billToSave.customerId > 0) {
                    db.customerDao().incrementCustomerSpend(billToSave.customerId, billToSave.finalAmount, billToSave.billDate)
                }
            } catch (re: Exception) {
                Log.e("BillRepo", "Error persisting bill to Room: ${re.message}")
            }
        }

        return billToSave
    }

    private suspend fun cacheBillToRoom(database: AppDatabase, bill: Bill, syncStatus: SyncStatus = SyncStatus.SYNCED) {
        val pair = toBillEntities(bill, syncStatus)
        database.billDao().upsertServerBill(pair.first, pair.second)
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
    fun getAllCustomers(): Flow<List<Customer>> {
        if (database == null) return firestoreRepo.getAllCustomers()
        return database.customerDao().getAllCustomers().map { entities ->
            entities.map { it.toDomain() }.distinctBy { it.canonicalKey() }
        }.distinctUntilChanged()
    }

    suspend fun searchCustomers(query: String): List<Customer> {
        val cloud = try {
            firestoreRepo.searchCustomers(query)
        } catch (e: Exception) {
            emptyList()
        }
        if (cloud.isNotEmpty()) return cloud
        return database?.customerDao()?.searchCustomers(query)?.map { it.toDomain() } ?: emptyList()
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
        var localId = customer.id
        val isOnline = FirebaseManager.isNetworkAvailable()
        val initialStatus = if (isOnline) SyncStatus.SYNCED else SyncStatus.PENDING

        if (database != null) {
            try {
                val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(customer.mobile)
                val existing = (if (customer.id > 0) database.customerDao().getCustomerById(customer.id) else null)
                    ?: (if (customer.id > 0) database.customerDao().getCustomerByServerId(customer.id) else null)
                    ?: (if (cleanMob.length == 10) database.customerDao().getCustomerByMobileFuzzy(customer.mobile, cleanMob) else null)
                    ?: (if (customer.name.isNotBlank()) database.customerDao().getCustomerByName(customer.name) else null)

                val targetLocalId = existing?.id ?: if (customer.id > 0 && customer.id < 1_000_000_000L) customer.id else 0L
                val entity = customer.toEntity().copy(
                    id = targetLocalId,
                    serverId = existing?.serverId,
                    syncStatus = initialStatus
                )
                localId = database.customerDao().insertCustomer(entity)
            } catch (e: Exception) {
                Log.w("CustomerRepo", "Room customer insert note: ${e.message}")
            }
        }
        try {
            val syncCust = if (customer.id > 0) customer else customer.copy(id = localId)
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

    suspend fun updateCustomerTags(customerId: Long, tags: List<String>): Result<Unit> {
        return try {
            firestoreRepo.updateCustomerTags(customerId, tags)
        } catch (e: Exception) {
            Result.success(Unit)
        }
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
                database.customerDao().hardDeleteCustomerByDetails(cleanMobile, name)
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

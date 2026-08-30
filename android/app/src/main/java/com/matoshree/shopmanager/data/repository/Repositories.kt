package com.matoshree.shopmanager.data.repository

import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.entity.*
import com.matoshree.shopmanager.data.remote.api.MatoshreeApiService
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.domain.usecase.CalculateProfitUseCase
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID

class BillRepository(
    private val database: AppDatabase,
    private val apiService: MatoshreeApiService
) {
    private val billDao = database.billDao()
    private val customerDao = database.customerDao()
    private val syncQueueDao = database.syncQueueDao()
    private val profitCalculator = CalculateProfitUseCase()

    fun getRecentBills(): Flow<List<Bill>> {
        return billDao.getRecentBills().map { list ->
            list.map { item ->
                Bill(
                    id = item.bill.id,
                    shopId = item.bill.shopId,
                    customerId = item.bill.customerId,
                    customerName = item.bill.customerName,
                    customerMobile = item.bill.customerMobile,
                    billNumber = item.bill.billNumber,
                    transactionUuid = item.bill.transactionUuid,
                    saleType = item.bill.saleType,
                    subtotal = item.bill.subtotal,
                    discountAmount = item.bill.discountAmount,
                    taxAmount = item.bill.taxAmount,
                    finalAmount = item.bill.finalAmount,
                    costAmount = item.bill.costAmount,
                    estimatedProfit = item.bill.estimatedProfit,
                    actualProfit = item.bill.actualProfit,
                    profitType = item.bill.profitType,
                    paymentMethod = item.bill.paymentMethod,
                    paymentStatus = item.bill.paymentStatus,
                    note = item.bill.note,
                    billDate = item.bill.billDate,
                    syncStatus = item.bill.syncStatus,
                    isVoided = item.bill.isVoided,
                    voidReason = item.bill.voidReason,
                    items = item.items.map { bi ->
                        BillItem(
                            id = bi.id,
                            billId = bi.billId,
                            productId = bi.productId,
                            productName = bi.productNameSnapshot,
                            sku = bi.skuSnapshot,
                            categoryId = bi.categoryId,
                            quantity = bi.quantity,
                            sellingPrice = bi.sellingPrice,
                            costPrice = bi.costPrice,
                            discountAmount = bi.discountAmount,
                            lineTotal = bi.lineTotal,
                            lineCost = bi.lineCost,
                            lineProfit = bi.lineProfit
                        )
                    }
                )
            }
        }
    }

    suspend fun getBillById(id: Long): Bill? {
        val item = billDao.getBillById(id) ?: return null
        return Bill(
            id = item.bill.id,
            shopId = item.bill.shopId,
            customerId = item.bill.customerId,
            customerName = item.bill.customerName,
            customerMobile = item.bill.customerMobile,
            billNumber = item.bill.billNumber,
            transactionUuid = item.bill.transactionUuid,
            saleType = item.bill.saleType,
            subtotal = item.bill.subtotal,
            discountAmount = item.bill.discountAmount,
            taxAmount = item.bill.taxAmount,
            finalAmount = item.bill.finalAmount,
            costAmount = item.bill.costAmount,
            estimatedProfit = item.bill.estimatedProfit,
            actualProfit = item.bill.actualProfit,
            profitType = item.bill.profitType,
            paymentMethod = item.bill.paymentMethod,
            paymentStatus = item.bill.paymentStatus,
            note = item.bill.note,
            billDate = item.bill.billDate,
            syncStatus = item.bill.syncStatus,
            isVoided = item.bill.isVoided,
            voidReason = item.bill.voidReason,
            items = item.items.map { bi ->
                BillItem(
                    id = bi.id,
                    billId = bi.billId,
                    productId = bi.productId,
                    productName = bi.productNameSnapshot,
                    sku = bi.skuSnapshot,
                    categoryId = bi.categoryId,
                    quantity = bi.quantity,
                    sellingPrice = bi.sellingPrice,
                    costPrice = bi.costPrice,
                    discountAmount = bi.discountAmount,
                    lineTotal = bi.lineTotal,
                    lineCost = bi.lineCost,
                    lineProfit = bi.lineProfit
                )
            }
        )
    }

    /**
     * Creates sale offline-first in Room, commits to sync queue, and updates local customer stats
     */
    suspend fun createSale(
        saleType: SaleType,
        customer: Customer?,
        items: List<BillItem>,
        discountAmount: Double,
        quickSaleAmount: Double?,
        paymentMethod: PaymentMethod,
        note: String?
    ): Bill {
        val txUuid = UUID.randomUUID().toString()
        val nowIso = DateUtils.nowIso()
        val currentYear = nowIso.take(4)
        val seqCount = billDao.getYearlySequenceCount(currentYear) + 1
        val billNumber = "MC-$currentYear-${String.format("%06d", seqCount)}"

        val subtotal: Double
        val finalAmount: Double
        val totalCost: Double
        val estimatedProfit: Double
        val actualProfit: Double
        val profitType: ProfitType
        val entityItems: MutableList<BillItemEntity> = mutableListOf()

        if (saleType == SaleType.DETAILED) {
            var sub = 0.0
            var cost = 0.0
            var estProf = 0.0
            var actProf = 0.0
            var hasActual = false

            for (item in items) {
                val fin = profitCalculator.calculateItemProfit(
                    sellingPrice = item.sellingPrice,
                    quantity = item.quantity,
                    costPrice = item.costPrice,
                    discount = item.discountAmount,
                    marginPercent = 25.0
                )
                sub += (item.sellingPrice * item.quantity)
                cost += fin.lineCost
                if (fin.profitType == ProfitType.ACTUAL) {
                    actProf += fin.lineProfit
                    hasActual = true
                } else {
                    estProf += fin.lineProfit
                }

                entityItems.add(
                    BillItemEntity(
                        billId = 0,
                        productId = item.productId,
                        productNameSnapshot = item.productName,
                        skuSnapshot = item.sku,
                        categoryId = item.categoryId,
                        quantity = item.quantity,
                        sellingPrice = item.sellingPrice,
                        costPrice = item.costPrice,
                        discountAmount = item.discountAmount,
                        lineTotal = fin.lineTotal,
                        lineCost = fin.lineCost,
                        lineProfit = fin.lineProfit
                    )
                )
            }

            subtotal = sub
            finalAmount = (subtotal - discountAmount).coerceAtLeast(0.0)
            totalCost = cost
            estimatedProfit = estProf
            actualProfit = actProf
            profitType = if (hasActual) ProfitType.ACTUAL else ProfitType.ESTIMATED
        } else {
            val amt = quickSaleAmount ?: 0.0
            val qFin = profitCalculator.calculateQuickSaleProfit(amt, 25.0)
            subtotal = amt
            finalAmount = amt
            totalCost = qFin.estimatedCost
            estimatedProfit = qFin.estimatedProfit
            actualProfit = 0.0
            profitType = ProfitType.ESTIMATED
        }

        val billEntity = BillEntity(
            shopId = 1,
            customerId = customer?.id,
            customerName = customer?.name,
            customerMobile = customer?.mobile,
            billNumber = billNumber,
            transactionUuid = txUuid,
            saleType = saleType,
            subtotal = subtotal,
            discountAmount = discountAmount,
            taxAmount = 0.0,
            finalAmount = finalAmount,
            costAmount = totalCost,
            estimatedProfit = estimatedProfit,
            actualProfit = actualProfit,
            profitType = profitType,
            paymentMethod = paymentMethod,
            paymentStatus = PaymentStatus.PAID,
            note = note,
            billDate = nowIso,
            syncStatus = SyncStatus.PENDING
        )

        // 1. Insert atomically to Room
        val localBillId = billDao.insertFullBill(billEntity, entityItems)

        // 2. Update Customer lifetime spend if linked
        if (customer != null && customer.id > 0) {
            customerDao.incrementCustomerSpend(customer.id, finalAmount, nowIso)
        }

        // 3. Enqueue to Sync Queue
        val payloadObj = kotlinx.serialization.json.buildJsonObject {
            put("transaction_uuid", kotlinx.serialization.json.JsonPrimitive(txUuid))
            put("bill_number", kotlinx.serialization.json.JsonPrimitive(billNumber))
            put("sale_type", kotlinx.serialization.json.JsonPrimitive(saleType.name))
            put("customer_id", kotlinx.serialization.json.JsonPrimitive(customer?.id))
            put("discount_amount", kotlinx.serialization.json.JsonPrimitive(discountAmount))
            put("final_amount", kotlinx.serialization.json.JsonPrimitive(finalAmount))
            put("payment_method", kotlinx.serialization.json.JsonPrimitive(paymentMethod.name))
            put("note", kotlinx.serialization.json.JsonPrimitive(note ?: ""))
            put("bill_date", kotlinx.serialization.json.JsonPrimitive(nowIso))
        }

        syncQueueDao.enqueue(
            SyncQueueEntity(
                transactionUuid = txUuid,
                entityType = "SALE",
                operation = "CREATE",
                payloadJson = payloadObj.toString(),
                status = SyncStatus.PENDING
            )
        )

        return getBillById(localBillId)!!
    }

    suspend fun voidBill(billId: Long, reason: String) {
        billDao.voidBill(billId, reason)
    }
}

class CustomerRepository(private val database: AppDatabase) {
    private val customerDao = database.customerDao()

    fun getAllCustomers(): Flow<List<Customer>> {
        return customerDao.getAllCustomers().map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun searchCustomers(query: String): List<Customer> {
        return customerDao.searchCustomers(query).map { it.toDomain() }
    }

    suspend fun saveCustomer(customer: Customer): Long {
        val entity = CustomerEntity(
            id = customer.id,
            shopId = customer.shopId,
            name = customer.name,
            mobile = customer.mobile,
            email = customer.email,
            address = customer.address,
            notes = customer.notes,
            totalBills = customer.totalBills,
            lifetimeSpend = customer.lifetimeSpend,
            firstPurchaseAt = customer.firstPurchaseAt,
            lastPurchaseAt = customer.lastPurchaseAt,
            isActive = customer.isActive
        )
        return customerDao.insertCustomer(entity)
    }

    private fun CustomerEntity.toDomain(): Customer {
        val tier = if (lifetimeSpend >= 25000.0 || totalBills >= 3) "VIP" else "REGULAR"
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
            firstPurchaseAt = firstPurchaseAt,
            lastPurchaseAt = lastPurchaseAt,
            isActive = isActive,
            tier = tier
        )
    }
}

class ProductRepository(private val database: AppDatabase) {
    private val productDao = database.productDao()

    fun getAllProducts(): Flow<List<Product>> {
        return productDao.getAllProducts().map { list ->
            list.map { p ->
                Product(
                    id = p.id,
                    shopId = p.shopId,
                    categoryId = p.categoryId,
                    name = p.name,
                    sku = p.sku,
                    sellingPrice = p.sellingPrice,
                    costPrice = p.costPrice,
                    defaultProfitMargin = p.defaultProfitMargin,
                    trackInventory = p.trackInventory,
                    currentStock = p.currentStock,
                    imageUrl = p.imageUrl,
                    isActive = p.isActive
                )
            }
        }
    }

    suspend fun searchProducts(query: String): List<Product> {
        return productDao.searchProducts(query).map { p ->
            Product(
                id = p.id,
                shopId = p.shopId,
                categoryId = p.categoryId,
                name = p.name,
                sku = p.sku,
                sellingPrice = p.sellingPrice,
                costPrice = p.costPrice,
                defaultProfitMargin = p.defaultProfitMargin,
                trackInventory = p.trackInventory,
                currentStock = p.currentStock,
                imageUrl = p.imageUrl,
                isActive = p.isActive
            )
        }
    }

    suspend fun saveProduct(product: Product): Long {
        return productDao.insertProduct(
            ProductEntity(
                id = product.id,
                shopId = product.shopId,
                categoryId = product.categoryId,
                name = product.name,
                sku = product.sku,
                sellingPrice = product.sellingPrice,
                costPrice = product.costPrice,
                defaultProfitMargin = product.defaultProfitMargin,
                trackInventory = product.trackInventory,
                currentStock = product.currentStock,
                imageUrl = product.imageUrl,
                isActive = product.isActive
            )
        )
    }

    fun getAllCategories(): Flow<List<Category>> {
        return productDao.getAllCategories().map { list ->
            list.map { c ->
                Category(
                    id = c.id,
                    shopId = c.shopId,
                    name = c.name,
                    description = c.description,
                    isActive = c.isActive
                )
            }
        }
    }
}

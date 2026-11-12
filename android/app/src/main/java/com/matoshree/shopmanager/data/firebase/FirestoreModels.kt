package com.matoshree.shopmanager.data.firebase

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.matoshree.shopmanager.domain.model.*

@IgnoreExtraProperties
data class ShopDto(
    var name: String = "मातोश्री कलेक्शन",
    var address: String = "कोंढारकी",
    var mobile: String = "8208610261",
    var email: String = "swarajfugare23@gmail.com",
    var gstin: String = "",
    var showGstin: Boolean = false,
    var upiId: String = "matoshree@upi",
    var upiDisplayName: String = "मातोश्री कलेक्शन",
    var upiMobile: String = "8208610261",
    var logoPath: String? = "android.resource://com.matoshree.collection/drawable/app_logo",
    var logoUrl: String? = null,
    var logoVersion: Long = 1L,
    var updatedAt: Long = System.currentTimeMillis()
)

@IgnoreExtraProperties
data class CustomerDto(
    @DocumentId var id: String = "",
    var legacyId: Long? = null,
    var name: String = "",
    var mobile: String = "",
    var email: String? = null,
    var address: String? = null,
    var notes: String? = null,
    var totalBills: Int = 0,
    var lifetimeSpend: Double = 0.0,
    var creditLimit: Double? = null,
    var outstandingAmount: Double = 0.0,
    var tier: String = "REGULAR",
    var listTags: List<String> = emptyList(),
    var status: String = "ACTIVE", // ACTIVE, ARCHIVED, TRASH
    var deletedAt: Long? = null,
    var firstPurchaseAt: String? = null,
    var lastPurchaseAt: String? = null,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Customer {
        return Customer(
            id = legacyId ?: (id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL),
            shopId = 1,
            name = name,
            mobile = mobile,
            email = email,
            address = address,
            notes = notes,
            totalBills = totalBills,
            lifetimeSpend = lifetimeSpend,
            creditLimit = creditLimit,
            outstandingBalance = outstandingAmount,
            firstPurchaseAt = firstPurchaseAt,
            lastPurchaseAt = lastPurchaseAt,
            isActive = status == "ACTIVE",
            tier = tier,
            listTags = listTags
        )
    }
}

@IgnoreExtraProperties
data class CategoryDto(
    @DocumentId var id: String = "",
    var legacyId: Long? = null,
    var name: String = "",
    var description: String? = null,
    var status: String = "ACTIVE",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Category {
        return Category(
            id = legacyId ?: (id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL),
            shopId = 1,
            name = name,
            description = description,
            isActive = status == "ACTIVE"
        )
    }
}

@IgnoreExtraProperties
data class ProductDto(
    @DocumentId var id: String = "",
    var legacyId: Long? = null,
    var name: String = "",
    var sku: String? = null,
    var categoryId: String? = null,
    var legacyCategoryId: Long? = null,
    var sellingPrice: Double = 0.0,
    var costPrice: Double? = null,
    var defaultProfitMargin: Double = 25.0,
    var trackInventory: Boolean = true,
    var currentStock: Int = 0,
    var totalStockAdded: Int = 0,
    var totalSold: Int = 0,
    var lowStockThreshold: Int = 5,
    var imageUrl: String? = null,
    var status: String = "ACTIVE", // ACTIVE, ARCHIVED, TRASH
    var deletedAt: Long? = null,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Product {
        val effectiveAdded = if (totalStockAdded > 0) totalStockAdded else (currentStock + totalSold)
        return Product(
            id = legacyId ?: (id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL),
            shopId = 1,
            categoryId = legacyCategoryId,
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
            isActive = status == "ACTIVE"
        )
    }
}

@IgnoreExtraProperties
data class StockMovementDto(
    @DocumentId var id: String = "",
    var productId: Long = 0,
    var productName: String = "",
    var sku: String? = null,
    var changeType: String = "RESTOCK",
    var quantityDelta: Int = 0,
    var stockAfter: Int = 0,
    var note: String? = null,
    var referenceId: String? = null,
    var timestamp: Long = System.currentTimeMillis()
) {
    fun toDomain(): com.matoshree.shopmanager.domain.model.StockMovement {
        val type = try {
            com.matoshree.shopmanager.domain.model.StockChangeType.valueOf(changeType)
        } catch (e: Exception) {
            com.matoshree.shopmanager.domain.model.StockChangeType.RESTOCK
        }
        return com.matoshree.shopmanager.domain.model.StockMovement(
            id = id,
            productId = productId,
            productName = productName,
            sku = sku,
            changeType = type,
            quantityDelta = quantityDelta,
            stockAfter = stockAfter,
            note = note,
            referenceId = referenceId,
            timestamp = timestamp
        )
    }
}


@IgnoreExtraProperties
data class BillItemDto(
    var id: String = "",
    var productId: String? = null,
    var legacyProductId: Long? = null,
    var productName: String = "",
    var sku: String? = null,
    var categoryId: String? = null,
    var legacyCategoryId: Long? = null,
    var quantity: Int = 1,
    var sellingPrice: Double = 0.0,
    var costPrice: Double = 0.0,
    var discountAmount: Double = 0.0,
    var lineTotal: Double = 0.0,
    var lineCost: Double = 0.0,
    var lineProfit: Double = 0.0
) {
    fun toDomain(): BillItem {
        return BillItem(
            id = id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL,
            billId = 0L,
            productId = legacyProductId,
            productName = productName,
            sku = sku,
            categoryId = legacyCategoryId,
            quantity = quantity,
            sellingPrice = sellingPrice,
            costPrice = costPrice,
            discountAmount = discountAmount,
            lineTotal = lineTotal,
            lineCost = lineCost,
            lineProfit = lineProfit
        )
    }
}

@IgnoreExtraProperties
data class BillPaymentDto(
    var id: String = "",
    var method: String = "CASH",
    var amount: Double = 0.0,
    var status: String = "PAID",
    var createdAt: Long = System.currentTimeMillis()
)

@IgnoreExtraProperties
data class BillDto(
    @DocumentId var id: String = "",
    var legacyId: Long? = null,
    var billNumber: String = "",
    var transactionUuid: String = "",
    var saleType: String = "QUICK",
    var customerId: String? = null,
    var legacyCustomerId: Long? = null,
    var customerName: String? = null,
    var customerMobile: String? = null,
    var subtotal: Double = 0.0,
    var discountType: String = "NONE",
    var discountValue: Double = 0.0,
    var discountAmount: Double = 0.0,
    var discount: Double = 0.0,
    var taxAmount: Double = 0.0,
    var finalAmount: Double = 0.0,
    var paidAmount: Double = 0.0,
    var pendingAmount: Double = 0.0,
    var dueDate: String? = null,
    var costAmount: Double = 0.0,
    var estimatedProfit: Double = 0.0,
    var actualProfit: Double = 0.0,
    var profitType: String = "ESTIMATED",
    var paymentMethod: String = "CASH",
    var paymentStatus: String = "PAID",
    var status: String = "COMPLETED", // COMPLETED, VOIDED, TRASH
    var deletedAt: Long? = null,
    var voidReason: String? = null,
    var note: String? = null,
    var billDate: String = "",
    var shopNameSnapshot: String = "Matoshree Collection",
    var shopAddressSnapshot: String = "",
    var shopMobileSnapshot: String = "",
    var shopGstinSnapshot: String = "",
    var showGstinSnapshot: Boolean = true,
    var items: List<BillItemDto> = emptyList(),
    var payments: List<BillPaymentDto> = emptyList(),
    var createdByDeviceId: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Bill {
        val sType = try { SaleType.valueOf(saleType) } catch (e: Exception) { SaleType.QUICK }
        val dType = try { DiscountType.valueOf(discountType) } catch (e: Exception) { DiscountType.NONE }
        val pType = try { ProfitType.valueOf(profitType) } catch (e: Exception) { ProfitType.ESTIMATED }
        val pMethod = try { PaymentMethod.valueOf(paymentMethod) } catch (e: Exception) { PaymentMethod.CASH }
        val pStatus = try { PaymentStatus.valueOf(paymentStatus) } catch (e: Exception) { PaymentStatus.PAID }

        val actualPaid = if (paidAmount > 0.0 || pendingAmount > 0.0) paidAmount else if (pStatus == PaymentStatus.PAID) finalAmount else 0.0
        val actualPending = if (paidAmount > 0.0 || pendingAmount > 0.0) pendingAmount else if (pStatus == PaymentStatus.PAID) 0.0 else finalAmount

        return Bill(
            id = legacyId ?: (id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL),
            shopId = 1,
            customerId = legacyCustomerId ?: customerId?.removePrefix("cust_")?.toLongOrNull(),
            customerName = customerName,
            customerMobile = customerMobile,
            billNumber = billNumber,
            transactionUuid = transactionUuid,
            saleType = sType,
            subtotal = subtotal,
            discountType = dType,
            discountValue = discountValue,
            discountAmount = discountAmount,
            taxAmount = taxAmount,
            finalAmount = finalAmount,
            paidAmount = actualPaid,
            pendingAmount = actualPending,
            dueDate = dueDate,
            costAmount = costAmount,
            estimatedProfit = estimatedProfit,
            actualProfit = actualProfit,
            profitType = pType,
            paymentMethod = pMethod,
            paymentStatus = pStatus,
            note = note,
            billDate = billDate,
            shopNameSnapshot = shopNameSnapshot,
            shopAddressSnapshot = shopAddressSnapshot,
            shopMobileSnapshot = shopMobileSnapshot,
            shopGstinSnapshot = shopGstinSnapshot,
            showGstinSnapshot = showGstinSnapshot,
            syncStatus = SyncStatus.SYNCED,
            isVoided = status == "VOIDED" || status == "TRASH",
            voidReason = voidReason,
            items = items.map { it.toDomain() }
        )
    }
}

@IgnoreExtraProperties
data class UdhariTransactionDto(
    @DocumentId var id: String = "",
    var customerId: Long = 0L,
    var customerName: String = "",
    var customerMobile: String = "",
    var customerVillage: String? = null,
    var billId: Long = 0L,
    var billNumber: String = "",
    var type: String = "PAYMENT", // PURCHASE_CREDIT, PAYMENT, REVERSAL
    var amount: Double = 0.0,
    var paymentMethod: String = "CASH",
    var previousOutstanding: Double = 0.0,
    var remainingOutstanding: Double = 0.0,
    var transactionUuid: String = "",
    var date: String = "", // YYYY-MM-DD
    var dueDate: String? = null,
    var note: String? = null,
    var isReversed: Boolean = false,
    var reversalReason: String? = null,
    var reversedAt: Long? = null,
    var status: String = "ACTIVE", // ACTIVE, TRASH
    var deletedAt: Long? = null,
    var createdByDeviceId: String? = null,
    var createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): UdhariTransaction {
        val tType = try { UdhariTransactionType.valueOf(type) } catch (e: Exception) { UdhariTransactionType.PAYMENT }
        val pMethod = try { PaymentMethod.valueOf(paymentMethod) } catch (e: Exception) { PaymentMethod.CASH }
        return UdhariTransaction(
            id = id,
            customerId = customerId,
            customerName = customerName,
            customerMobile = customerMobile,
            customerVillage = customerVillage,
            billId = billId,
            billNumber = billNumber,
            type = tType,
            amount = amount,
            paymentMethod = pMethod,
            previousOutstanding = previousOutstanding,
            remainingOutstanding = remainingOutstanding,
            transactionUuid = transactionUuid,
            date = date,
            dueDate = dueDate,
            note = note,
            isReversed = isReversed,
            reversalReason = reversalReason,
            reversedAt = reversedAt,
            status = status,
            deletedAt = deletedAt,
            createdByDeviceId = createdByDeviceId,
            createdAt = createdAt
        )
    }
}

@IgnoreExtraProperties
data class ExpenseDto(
    @DocumentId var id: String = "",
    var legacyId: Long? = null,
    var category: String = "GENERAL",
    var amount: Double = 0.0,
    var paymentMethod: String = "CASH",
    var expenseDate: String = "",
    var note: String? = null,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Expense {
        val pMethod = try { PaymentMethod.valueOf(paymentMethod) } catch (e: Exception) { PaymentMethod.CASH }
        return Expense(
            id = legacyId ?: (id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL),
            shopId = 1,
            category = category,
            amount = amount,
            paymentMethod = pMethod,
            expenseDate = expenseDate,
            note = note
        )
    }
}

@IgnoreExtraProperties
data class DailyClosingDto(
    @DocumentId var id: String = "", // formatted YYYY-MM-DD
    var closingDate: String = "",
    var totalSales: Double = 0.0,
    var cashSales: Double = 0.0,
    var upiSales: Double = 0.0,
    var otherSales: Double = 0.0,
    var totalExpenses: Double = 0.0,
    var estimatedProfit: Double = 0.0,
    var actualProfit: Double = 0.0,
    var billCount: Int = 0,
    var status: String = "CLOSED",
    var notes: String? = null,
    var closedAt: String = "",
    var createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): DailyClosing {
        return DailyClosing(
            id = id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL,
            shopId = 1,
            closingDate = closingDate,
            totalSales = totalSales,
            totalBills = billCount,
            cashSales = cashSales,
            upiSales = upiSales,
            cardSales = 0.0,
            otherSales = otherSales,
            grossProfit = estimatedProfit + actualProfit,
            totalExpenses = totalExpenses,
            netProfit = (totalSales - totalExpenses).coerceAtLeast(0.0),
            expectedCash = cashSales,
            actualCash = cashSales,
            cashDifference = 0.0,
            notes = notes,
            isClosed = status == "CLOSED",
            closedAt = closedAt
        )
    }
}

@IgnoreExtraProperties
data class SecurityPinDto(
    var pinHash: String = "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4",
    var salt: String = "matoshree_salt_v2",
    var updatedAt: Long = System.currentTimeMillis(),
    var updatedByDevice: String = ""
)

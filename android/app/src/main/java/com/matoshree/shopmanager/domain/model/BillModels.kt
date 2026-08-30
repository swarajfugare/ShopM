package com.matoshree.shopmanager.domain.model

import kotlinx.serialization.Serializable

enum class SaleType {
    DETAILED,
    QUICK
}

enum class ProfitType {
    ESTIMATED,
    ACTUAL
}

enum class PaymentMethod {
    CASH,
    UPI,
    CARD,
    OTHER,
    SPLIT
}

enum class PaymentStatus {
    PAID,
    PARTIAL,
    UNPAID,
    VOID
}

enum class SyncStatus {
    PENDING,
    SYNCING,
    SYNCED,
    FAILED
}

@Serializable
data class BillItem(
    val id: Long = 0,
    val billId: Long = 0,
    val productId: Long? = null,
    val productName: String,
    val sku: String? = null,
    val categoryId: Long? = null,
    val quantity: Int = 1,
    val sellingPrice: Double,
    val costPrice: Double? = null,
    val discountAmount: Double = 0.0,
    val lineTotal: Double,
    val lineCost: Double = 0.0,
    val lineProfit: Double = 0.0
)

@Serializable
data class Bill(
    val id: Long = 0,
    val shopId: Long = 1,
    val customerId: Long? = null,
    val customerName: String? = null,
    val customerMobile: String? = null,
    val billNumber: String,
    val transactionUuid: String,
    val saleType: SaleType,
    val subtotal: Double,
    val discountAmount: Double = 0.0,
    val taxAmount: Double = 0.0,
    val finalAmount: Double,
    val costAmount: Double = 0.0,
    val estimatedProfit: Double = 0.0,
    val actualProfit: Double = 0.0,
    val profitType: ProfitType = ProfitType.ESTIMATED,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val paymentStatus: PaymentStatus = PaymentStatus.PAID,
    val note: String? = null,
    val billDate: String,
    val createdBy: Long? = null,
    val deviceId: String? = null,
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    val isVoided: Boolean = false,
    val voidReason: String? = null,
    val items: List<BillItem> = emptyList()
)

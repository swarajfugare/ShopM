package com.matoshree.shopmanager.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Customer(
    val id: Long = 0,
    val shopId: Long = 1,
    val name: String,
    val mobile: String,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val totalBills: Int = 0,
    val lifetimeSpend: Double = 0.0,
    val creditLimit: Double? = null,
    val outstandingBalance: Double = 0.0,
    val firstPurchaseAt: String? = null,
    val lastPurchaseAt: String? = null,
    val isActive: Boolean = true,
    val tier: String = "REGULAR", // VIP or REGULAR
    val listTags: List<String> = emptyList()
)

@Serializable
data class Category(
    val id: Long = 0,
    val shopId: Long = 1,
    val name: String,
    val description: String? = null,
    val productCount: Int = 0,
    val isActive: Boolean = true
)

@Serializable
data class Product(
    val id: Long = 0,
    val shopId: Long = 1,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val name: String,
    val sku: String? = null,
    val sellingPrice: Double,
    val costPrice: Double? = null,
    val defaultProfitMargin: Double = 25.0,
    val trackInventory: Boolean = true,
    val currentStock: Int = 0,
    val totalStockAdded: Int = 0,
    val totalSold: Int = 0,
    val lowStockThreshold: Int = 5,
    val imageUrl: String? = null,
    val isActive: Boolean = true
) {
    // Helper to calculate effective total stock brought into store
    val effectiveTotalStockAdded: Int
        get() = if (totalStockAdded > 0) totalStockAdded else (currentStock + totalSold)

    // Helper to calculate sell-through rate percentage
    val sellThroughRate: Double
        get() {
            val total = effectiveTotalStockAdded
            return if (total > 0) (totalSold.toDouble() / total.toDouble()) * 100.0 else 0.0
        }

    val isOutOfStock: Boolean
        get() = trackInventory && currentStock <= 0

    val isLowStock: Boolean
        get() = trackInventory && currentStock in 1..lowStockThreshold
}

enum class StockChangeType(val displayName: String) {
    SALE("विक्री (Sale)"),
    RESTOCK("नवीन माल आला (Restock)"),
    ADJUSTMENT("मोजणी दुरुस्ती (Audit Count)"),
    DAMAGE("खराब माल / डॅमेज (Damage)"),
    RETURN("परत आलेला माल (Return / Restored)")
}

@Serializable
data class StockMovement(
    val id: String = "",
    val productId: Long,
    val productName: String,
    val sku: String? = null,
    val changeType: StockChangeType = StockChangeType.RESTOCK,
    val quantityDelta: Int = 0,
    val stockAfter: Int = 0,
    val note: String? = null,
    val referenceId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)


@Serializable
data class Expense(
    val id: Long = 0,
    val shopId: Long = 1,
    val category: String, // RENT, ELECTRICITY, SALARY, TRANSPORT, PACKAGING, MAINTENANCE, OTHER
    val amount: Double,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val expenseDate: String,
    val note: String? = null,
    val createdByName: String? = null
)

@Serializable
data class DailyClosing(
    val id: Long = 0,
    val shopId: Long = 1,
    val closingDate: String,
    val totalSales: Double = 0.0,
    val totalBills: Int = 0,
    val cashSales: Double = 0.0,
    val upiSales: Double = 0.0,
    val cardSales: Double = 0.0,
    val otherSales: Double = 0.0,
    val grossProfit: Double = 0.0,
    val totalExpenses: Double = 0.0,
    val netProfit: Double = 0.0,
    val expectedCash: Double = 0.0,
    val actualCash: Double = 0.0,
    val cashDifference: Double = 0.0,
    val notes: String? = null,
    val isClosed: Boolean = false,
    val closedByName: String? = null,
    val closedAt: String? = null
)

@Serializable
data class DashboardSummary(
    val todaySales: Double = 0.0,
    val todayBillsCount: Int = 0,
    val todayProfit: Double = 0.0,
    val averageOrderValue: Double = 0.0,
    val profitMarginPercent: Double = 25.0,
    val monthlySales: Double = 0.0,
    val monthlyTarget: Double = 500000.0,
    val targetProgressPercent: Double = 0.0,
    val cashPayments: Double = 0.0,
    val upiPayments: Double = 0.0,
    val recentBills: List<Bill> = emptyList(),
    val businessInsight: String = "Welcome to Matoshree Collection."
)

package com.matoshree.shopmanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.domain.model.PaymentStatus
import com.matoshree.shopmanager.domain.model.ProfitType
import com.matoshree.shopmanager.domain.model.SaleType
import com.matoshree.shopmanager.domain.model.SyncStatus

@Entity(
    tableName = "bills",
    indices = [
        Index(value = ["transactionUuid"], unique = true),
        Index(value = ["billNumber"]),
        Index(value = ["billDate"]),
        Index(value = ["customerId"])
    ]
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long? = null,
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
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val isVoided: Boolean = false,
    val voidReason: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "bill_items",
    foreignKeys = [
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["id"],
            childColumns = ["billId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["billId"])]
)
data class BillItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    val productId: Long? = null,
    val productNameSnapshot: String,
    val skuSnapshot: String? = null,
    val categoryId: Long? = null,
    val quantity: Int = 1,
    val sellingPrice: Double,
    val costPrice: Double? = null,
    val discountAmount: Double = 0.0,
    val lineTotal: Double,
    val lineCost: Double = 0.0,
    val lineProfit: Double = 0.0
)

@Entity(
    tableName = "customers",
    indices = [
        Index(value = ["name"]),
        Index(value = ["mobile"])
    ]
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long? = null,
    val shopId: Long = 1,
    val name: String,
    val mobile: String,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val totalBills: Int = 0,
    val lifetimeSpend: Double = 0.0,
    val firstPurchaseAt: String? = null,
    val lastPurchaseAt: String? = null,
    val isActive: Boolean = true,
    val syncStatus: SyncStatus = SyncStatus.SYNCED
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long? = null,
    val shopId: Long = 1,
    val name: String,
    val description: String? = null,
    val isActive: Boolean = true
)

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["sku"]),
        Index(value = ["name"])
    ]
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long? = null,
    val shopId: Long = 1,
    val categoryId: Long? = null,
    val name: String,
    val sku: String? = null,
    val sellingPrice: Double,
    val costPrice: Double? = null,
    val defaultProfitMargin: Double = 25.0,
    val trackInventory: Boolean = false,
    val currentStock: Int = 0,
    val imageUrl: String? = null,
    val isActive: Boolean = true
)

@Entity(
    tableName = "expenses",
    indices = [Index(value = ["expenseDate"])]
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long? = null,
    val shopId: Long = 1,
    val transactionUuid: String,
    val category: String,
    val amount: Double,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val expenseDate: String,
    val note: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING
)

@Entity(
    tableName = "daily_closings",
    indices = [Index(value = ["closingDate"], unique = true)]
)
data class DailyClosingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long? = null,
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
    val closedAt: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING
)

@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["transactionUuid"], unique = true),
        Index(value = ["status"])
    ]
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionUuid: String,
    val entityType: String, // SALE, EXPENSE, CUSTOMER
    val operation: String = "CREATE",
    val payloadJson: String,
    val status: SyncStatus = SyncStatus.PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String
)

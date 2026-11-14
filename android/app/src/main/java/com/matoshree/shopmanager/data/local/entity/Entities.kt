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
        Index(value = ["customerId"]),
        Index(value = ["gid"], unique = true),
        Index(value = ["customerGid"])
    ]
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gid: String = "",
    val docId: String? = null,
    val serverId: Long? = null,
    val shopId: Long = 1,
    val customerId: Long? = null,
    val customerGid: String? = null,
    val customerName: String? = null,
    val customerMobile: String? = null,
    val billNumber: String,
    val transactionUuid: String,
    val saleType: SaleType,
    val subtotal: Double,
    val discountType: com.matoshree.shopmanager.domain.model.DiscountType = com.matoshree.shopmanager.domain.model.DiscountType.NONE,
    val discountValue: Double = 0.0,
    val discountAmount: Double = 0.0,
    val taxAmount: Double = 0.0,
    val finalAmount: Double,
    val paidAmount: Double = 0.0,
    val pendingAmount: Double = 0.0,
    val costAmount: Double = 0.0,
    val estimatedProfit: Double = 0.0,
    val actualProfit: Double = 0.0,
    val profitType: ProfitType = ProfitType.ESTIMATED,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val paymentStatus: PaymentStatus = PaymentStatus.PAID,
    val note: String? = null,
    val billDate: String,
    val shopNameSnapshot: String = "Matoshree Collection",
    val shopAddressSnapshot: String? = null,
    val shopMobileSnapshot: String? = null,
    val shopGstinSnapshot: String? = null,
    val showGstinSnapshot: Boolean = true,
    val createdBy: Long? = null,
    val deviceId: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val isVoided: Boolean = false,
    val voidReason: String? = null,
    val needsReview: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun canonicalKey(): String {
        if (gid.isNotBlank()) return gid
        return if (!transactionUuid.isNullOrBlank()) transactionUuid else if (billNumber.isNotBlank()) billNumber else "bill_$id"
    }
}

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
    indices = [
        Index(value = ["billId"]),
        Index(value = ["gid"], unique = true),
        Index(value = ["billGid"]),
        Index(value = ["productGid"])
    ]
)
data class BillItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gid: String = "",
    val docId: String? = null,
    val billId: Long,
    val billGid: String = "",
    val productId: Long? = null,
    val productGid: String? = null,
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
        Index(value = ["mobile"]),
        Index(value = ["gid"], unique = true)
    ]
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gid: String = "",
    val docId: String? = null,
    val serverId: Long? = null,
    val shopId: Long = 1,
    val name: String,
    val mobile: String,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val totalBills: Int = 0,
    val lifetimeSpend: Double = 0.0,
    val outstandingBalance: Double = 0.0,
    val firstPurchaseAt: String? = null,
    val lastPurchaseAt: String? = null,
    val isActive: Boolean = true,
    val needsReview: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.SYNCED
) {
    fun canonicalKey(): String {
        if (gid.isNotBlank()) return gid
        val clean = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile)
        return when {
            clean.length == 10 -> "mob_$clean"
            name.isNotBlank() -> "name_${name.trim().lowercase()}"
            serverId != null && serverId > 0L -> "cust_srv_$serverId"
            id > 0L -> "cust_$id"
            else -> "cust_unknown_${hashCode()}"
        }
    }
}

@Entity(
    tableName = "customer_aliases",
    indices = [
        Index(value = ["aliasGid"], unique = true),
        Index(value = ["canonicalGid"])
    ]
)
data class CustomerAliasEntity(
    @PrimaryKey val aliasGid: String,
    val canonicalGid: String,
    val mergedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["gid"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gid: String = "",
    val docId: String? = null,
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
        Index(value = ["name"]),
        Index(value = ["gid"], unique = true),
        Index(value = ["categoryGid"])
    ]
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gid: String = "",
    val docId: String? = null,
    val serverId: Long? = null,
    val shopId: Long = 1,
    val categoryId: Long? = null,
    val categoryGid: String? = null,
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
)

@Entity(
    tableName = "expenses",
    indices = [
        Index(value = ["expenseDate"]),
        Index(value = ["gid"], unique = true)
    ]
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gid: String = "",
    val docId: String? = null,
    val serverId: Long? = null,
    val shopId: Long = 1,
    val transactionUuid: String,
    val category: String,
    val categoryGid: String? = null,
    val amount: Double,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val expenseDate: String,
    val note: String? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING
)

@Entity(
    tableName = "daily_closings",
    indices = [
        Index(value = ["closingDate"], unique = true),
        Index(value = ["gid"], unique = true)
    ]
)
data class DailyClosingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gid: String = "",
    val docId: String? = null,
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

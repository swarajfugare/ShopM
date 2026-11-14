package com.matoshree.shopmanager.domain.model

import kotlinx.serialization.Serializable

enum class UdhariTransactionType {
    PURCHASE_CREDIT, // Credit bill created
    PAYMENT,         // Customer made a payment
    REVERSAL         // An authorized reversal of a previous payment
}

@Serializable
data class UdhariTransaction(
    val id: String = "",
    val gid: String = "",
    val customerId: Long,
    val customerGid: String = "",
    val customerName: String,
    val customerMobile: String,
    val customerVillage: String? = null,
    val billId: Long,
    val billGid: String? = null,
    val billNumber: String,
    val type: UdhariTransactionType = UdhariTransactionType.PAYMENT,
    val amount: Double,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val previousOutstanding: Double = 0.0,
    val remainingOutstanding: Double = 0.0,
    val transactionUuid: String,
    val date: String, // YYYY-MM-DD
    val dueDate: String? = null, // YYYY-MM-DD
    val note: String? = null,
    val isReversed: Boolean = false,
    val reversalReason: String? = null,
    val reversedAt: Long? = null,
    val status: String = "ACTIVE",
    val deletedAt: Long? = null,
    val createdByDeviceId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val needsReview: Boolean = false
)

data class CustomerUdhariSummary(
    val customer: Customer,
    val totalOutstanding: Double,
    val totalPurchases: Double,
    val totalPaid: Double,
    val pendingBillsCount: Int,
    val lastPaymentDate: String?,
    val lastPaymentAmount: Double?,
    val oldestDueDate: String?,
    val isOverdue: Boolean,
    val creditLimit: Double?,
    val availableCredit: Double? // null if unlimited
)

data class UdhariLedgerEntry(
    val id: String,
    val date: String,
    val timeMillis: Long,
    val title: String,
    val subtitle: String,
    val billNumber: String?,
    val type: UdhariTransactionType,
    val debitAmount: Double,  // Increases debt (Purchases / Reversals)
    val creditAmount: Double, // Decreases debt (Payments)
    val runningBalance: Double,
    val paymentMethod: PaymentMethod?,
    val note: String?,
    val isReversed: Boolean = false,
    val transaction: UdhariTransaction? = null
)

@Serializable
data class KhatavahiEntry(
    val id: String,
    val date: String,
    val timeMillis: Long,
    val description: String,
    val isCredit: Boolean, // false = खरेदी / उधारी (+), true = जमा (-)
    val amount: Double,
    val runningBalance: Double,
    val billId: Long? = null,
    val billNumber: String? = null,
    val paymentMethod: PaymentMethod? = null
)

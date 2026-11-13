package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.domain.model.PaymentStatus
import com.matoshree.shopmanager.domain.model.UdhariTransaction
import com.matoshree.shopmanager.domain.model.UdhariTransactionType
import com.matoshree.shopmanager.utils.DateUtils

/**
 * Single Authoritative Business Metrics & Udhari Calculation Engine.
 * All screens (Dashboard, Udhari, Customer Profile, Daily Closing, Analytics)
 * MUST compute totals using this shared calculator to guarantee 100% mathematical consistency.
 */
object ShopMetricsCalculator {

    fun calculateTodaySales(bills: List<Bill>, todayDatePrefix: String = DateUtils.todayDate()): Double {
        return bills.filter { !it.isVoided && DateUtils.isToday(it.billDate) }
            .sumOf { it.finalAmount }
    }

    fun calculateMonthlySales(bills: List<Bill>, monthPrefix: String = DateUtils.todayDate().take(7)): Double {
        return bills.filter { !it.isVoided && it.billDate.startsWith(monthPrefix) }
            .sumOf { it.finalAmount }
    }

    fun calculateTodayProfit(bills: List<Bill>, todayDatePrefix: String = DateUtils.todayDate()): Double {
        return bills.filter { !it.isVoided && DateUtils.isToday(it.billDate) }
            .sumOf { bill ->
                if (bill.actualProfit > 0) bill.actualProfit else bill.finalAmount * 0.25
            }
    }

    fun calculateTodayCollectedCredit(
        transactions: List<UdhariTransaction>,
        todayDatePrefix: String = DateUtils.todayDate()
    ): Double {
        return transactions.filter {
            !it.isReversed &&
            it.type == UdhariTransactionType.PAYMENT &&
            it.date.startsWith(todayDatePrefix)
        }.sumOf { it.amount }
    }

    fun calculateCashSales(bills: List<Bill>): Double {
        return bills.filter { !it.isVoided && it.paymentMethod.name == "CASH" }
            .sumOf { it.paidAmount }
    }

    fun calculateUpiSales(bills: List<Bill>): Double {
        return bills.filter { !it.isVoided && it.paymentMethod.name == "UPI" }
            .sumOf { it.paidAmount }
    }

    /**
     * Calculates total shop-wide pending Udhari balance with zero discrepancy.
     * Total Charges = Active Bills + Manual Purchase Credits
     * Total Credits = Udhari Payments (minus reversals) + Pure Cash Checkout Payments
     * Outstanding = (Total Charges - Total Credits).coerceAtLeast(0.0)
     */
    fun calculateTotalPendingUdhari(
        activeBills: List<Bill>,
        transactions: List<UdhariTransaction>
    ): Double {
        val nonVoidBills = activeBills.filter { !it.isVoided }
        val validTxs = transactions.filter { !it.isReversed }

        val txBillIds = validTxs.filter { it.type == UdhariTransactionType.PAYMENT && it.billId != 0L }.map { it.billId }.toSet()
        val txBillNumbers = validTxs.filter { it.type == UdhariTransactionType.PAYMENT && it.billNumber.isNotBlank() }.map { it.billNumber }.toSet()

        val totalBillCharges = nonVoidBills.sumOf { it.finalAmount }
        val manualCredits = validTxs.filter { it.billId == 0L && it.type == UdhariTransactionType.PURCHASE_CREDIT }.sumOf { it.amount }
        val totalCharges = totalBillCharges + manualCredits

        val txPayments = validTxs.filter { it.type == UdhariTransactionType.PAYMENT }.sumOf { it.amount }
        val txReversals = validTxs.filter { it.type == UdhariTransactionType.REVERSAL }.sumOf { it.amount }
        val netTxPayments = (txPayments - txReversals).coerceAtLeast(0.0)

        val pureCashCheckoutPayments = nonVoidBills.filter { b ->
            b.paymentStatus == PaymentStatus.PAID &&
            !txBillIds.contains(b.id) &&
            !txBillNumbers.contains(b.billNumber)
        }.sumOf { it.finalAmount }

        val totalCredits = netTxPayments + pureCashCheckoutPayments
        return (totalCharges - totalCredits).coerceAtLeast(0.0)
    }

    /**
     * Calculates the count of unique customers with outstanding balance > ₹0.01.
     */
    fun calculatePendingCustomersCount(
        activeBills: List<Bill>,
        transactions: List<UdhariTransaction>
    ): Int {
        val nonVoidBills = activeBills.filter { !it.isVoided && it.paymentStatus != PaymentStatus.PAID }
        val validTxs = transactions.filter { !it.isReversed && it.billId == 0L && it.type == UdhariTransactionType.PURCHASE_CREDIT }

        val pendingCustKeys = mutableSetOf<String>()
        nonVoidBills.forEach { bill ->
            val key = when {
                bill.customerId != null && bill.customerId > 0 -> "cust_${bill.customerId}"
                !bill.customerMobile.isNullOrBlank() -> "mob_${bill.customerMobile}"
                !bill.customerName.isNullOrBlank() -> "name_${bill.customerName.trim().lowercase()}"
                else -> null
            }
            if (key != null) pendingCustKeys.add(key)
        }
        validTxs.forEach { tx ->
            val key = when {
                tx.customerId > 0 -> "cust_${tx.customerId}"
                tx.customerMobile.isNotBlank() -> "mob_${tx.customerMobile}"
                tx.customerName.isNotBlank() -> "name_${tx.customerName.trim().lowercase()}"
                else -> null
            }
            if (key != null) pendingCustKeys.add(key)
        }
        return pendingCustKeys.size
    }
}

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
        return bills.filter { !it.isVoided && (it.billDate.startsWith(todayDatePrefix) || DateUtils.isToday(it.billDate)) }
            .sumOf { it.finalAmount }
    }

    fun calculateMonthlySales(bills: List<Bill>, monthPrefix: String = DateUtils.todayDate().take(7)): Double {
        return bills.filter { !it.isVoided && (it.billDate.startsWith(monthPrefix) || DateUtils.isThisMonth(it.billDate)) }
            .sumOf { it.finalAmount }
    }

    fun calculateTodayProfit(bills: List<Bill>, todayDatePrefix: String = DateUtils.todayDate()): Double {
        return bills.filter { !it.isVoided && (it.billDate.startsWith(todayDatePrefix) || DateUtils.isToday(it.billDate)) }
            .sumOf { bill ->
                if (bill.actualProfit > 0) bill.actualProfit else if (bill.estimatedProfit > 0) bill.estimatedProfit else 0.0
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

    fun resolveCustomerKey(mobile: String?, name: String?, localId: Long?): String? {
        val clean = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile ?: "")
        return when {
            clean.length == 10 -> "mob_$clean"
            !name.isNullOrBlank() -> "name_${name.trim().lowercase()}"
            localId != null && localId > 0L -> "cust_$localId"
            else -> null
        }
    }

    /**
     * Calculates customer ledger balances keyed by canonical customer identifier.
     * Guaranteed single source of truth for Dashboard, UdhariScreen, and CustomerProfile.
     */
    fun calculateCustomerLedgerBalances(
        activeBills: List<Bill>,
        transactions: List<UdhariTransaction>
    ): Map<String, Double> {
        val nonVoidBills = activeBills.filter { !it.isVoided }
        val validTxs = transactions.filter { !it.isReversed }

        val txBillIds = validTxs.filter { it.type == UdhariTransactionType.PAYMENT && it.billId != 0L }.map { it.billId }.toSet()
        val txBillNumbers = validTxs.filter { it.type == UdhariTransactionType.PAYMENT && it.billNumber.isNotBlank() }.map { it.billNumber }.toSet()

        val allCustomerKeys = mutableSetOf<String>()
        nonVoidBills.forEach { b ->
            resolveCustomerKey(b.customerMobile, b.customerName, b.customerId)?.let { allCustomerKeys.add(it) }
        }
        validTxs.forEach { tx ->
            resolveCustomerKey(tx.customerMobile, tx.customerName, tx.customerId)?.let { allCustomerKeys.add(it) }
        }

        val balances = mutableMapOf<String, Double>()
        for (key in allCustomerKeys) {
            val custBills = nonVoidBills.filter { b ->
                resolveCustomerKey(b.customerMobile, b.customerName, b.customerId) == key
            }
            val custTxs = validTxs.filter { tx ->
                resolveCustomerKey(tx.customerMobile, tx.customerName, tx.customerId) == key
            }

            val billCharges = custBills.sumOf { it.finalAmount }
            val manualCredits = custTxs.filter { it.billId == 0L && it.type == UdhariTransactionType.PURCHASE_CREDIT }.sumOf { it.amount }
            val totalCharges = billCharges + manualCredits

            val txPayments = custTxs.filter { it.type == UdhariTransactionType.PAYMENT }.sumOf { it.amount }
            val txReversals = custTxs.filter { it.type == UdhariTransactionType.REVERSAL }.sumOf { it.amount }
            val netTxPayments = (txPayments - txReversals).coerceAtLeast(0.0)

            val pureCashCheckoutPayments = custBills.filter { b ->
                b.paymentStatus == PaymentStatus.PAID &&
                !txBillIds.contains(b.id) &&
                !txBillNumbers.contains(b.billNumber)
            }.sumOf { it.finalAmount }

            val totalCredits = netTxPayments + pureCashCheckoutPayments
            val due = (totalCharges - totalCredits).coerceAtLeast(0.0)
            balances[key] = due
        }
        return balances
    }

    /**
     * Calculates total shop-wide pending Udhari balance with zero discrepancy.
     */
    fun calculateTotalPendingUdhari(
        activeBills: List<Bill>,
        transactions: List<UdhariTransaction>
    ): Double {
        val balances = calculateCustomerLedgerBalances(activeBills, transactions)
        return balances.values.sum()
    }

    /**
     * Calculates the count of unique customers with outstanding balance > ₹0.01.
     */
    fun calculatePendingCustomersCount(
        activeBills: List<Bill>,
        transactions: List<UdhariTransaction>
    ): Int {
        val balances = calculateCustomerLedgerBalances(activeBills, transactions)
        return balances.count { it.value > 0.01 }
    }
}

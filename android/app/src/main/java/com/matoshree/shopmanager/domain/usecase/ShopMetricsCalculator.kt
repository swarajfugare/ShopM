package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.domain.model.Customer
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

    fun resolveCustomerKey(mobile: String?, name: String?, localId: Long?): String? =
        UdhariLedger.resolveCustomerKey(mobile, name, localId)

    /**
     * Builds a unified resolver that links customer entities, bills, and transactions
     * so that records sharing mobile, name, or customer ID resolve accurately to the canonical key,
     * while guaranteeing that distinct 10-digit mobile numbers are NEVER merged.
     */
    fun buildCustomerKeyResolver(
        customers: List<Customer> = emptyList(),
        bills: List<Bill> = emptyList(),
        transactions: List<UdhariTransaction> = emptyList()
    ): (mobile: String?, name: String?, localId: Long?) -> String? =
        UdhariLedger.buildCustomerKeyResolver(customers, bills, transactions)

    /**
     * Calculates customer ledger balances keyed by canonical customer identifier.
     * Guaranteed single source of truth for Dashboard, UdhariScreen, and CustomerProfile.
     */
    fun calculateCustomerLedgerBalances(
        activeBills: List<Bill>,
        transactions: List<UdhariTransaction>,
        customers: List<Customer> = emptyList()
    ): Map<String, Double> {
        val summary = UdhariLedger.computeShopLedger(activeBills, transactions, customers)
        return summary.customerResults.mapValues { it.value.balance }
    }

    /**
     * Calculates total shop-wide pending Udhari balance with zero discrepancy.
     */
    fun calculateTotalPendingUdhari(
        activeBills: List<Bill>,
        transactions: List<UdhariTransaction>,
        customers: List<Customer> = emptyList()
    ): Double {
        return UdhariLedger.computeShopLedger(activeBills, transactions, customers).totalOutstanding
    }

    /**
     * Calculates the count of unique customers with outstanding balance > ₹0.01.
     */
    fun calculatePendingCustomersCount(
        activeBills: List<Bill>,
        transactions: List<UdhariTransaction>,
        customers: List<Customer> = emptyList()
    ): Int {
        return UdhariLedger.computeShopLedger(activeBills, transactions, customers).pendingCustomersCount
    }
}

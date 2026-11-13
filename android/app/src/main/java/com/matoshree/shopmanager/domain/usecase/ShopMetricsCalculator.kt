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
     * Builds a unified resolver that links customer entities, bills, and transactions
     * so that records sharing mobile, name, or customer ID resolve accurately to the canonical key,
     * while guaranteeing that distinct 10-digit mobile numbers are NEVER merged.
     */
    fun buildCustomerKeyResolver(
        customers: List<Customer> = emptyList(),
        bills: List<Bill> = emptyList(),
        transactions: List<UdhariTransaction> = emptyList()
    ): (mobile: String?, name: String?, localId: Long?) -> String? {
        val aliasToCanonical = mutableMapOf<String, String>()

        // 1. Seed from known customer entities (only map name if it is unique among customers)
        val nameCount = customers.map { it.name.trim().lowercase() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        for (c in customers) {
            val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(c.mobile)
            val cleanName = c.name.trim().lowercase()
            if (cleanMob.length == 10) {
                if (cleanName.isNotBlank() && (nameCount[cleanName] ?: 0) == 1) {
                    aliasToCanonical["name_$cleanName"] = "mob_$cleanMob"
                }
                if (c.id > 0L) {
                    aliasToCanonical["id_${c.id}"] = "mob_$cleanMob"
                }
            }
        }

        return { mobile, name, localId ->
            val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile ?: "")
            if (cleanMob.length == 10) {
                "mob_$cleanMob"
            } else {
                val cleanName = name?.trim()?.lowercase() ?: ""
                (if (cleanName.isNotBlank()) aliasToCanonical["name_$cleanName"] else null)
                    ?: (if (localId != null && localId > 0L) aliasToCanonical["id_$localId"] else null)
                    ?: resolveCustomerKey(mobile, name, localId)
            }
        }
    }

    /**
     * Calculates customer ledger balances keyed by canonical customer identifier.
     * Guaranteed single source of truth for Dashboard, UdhariScreen, and CustomerProfile.
     */
    fun calculateCustomerLedgerBalances(
        activeBills: List<Bill>,
        transactions: List<UdhariTransaction>,
        customers: List<Customer> = emptyList()
    ): Map<String, Double> {
        val nonVoidBills = activeBills.filter { !it.isVoided }
        val validTxs = transactions.filter { !it.isReversed }

        val keyResolver = buildCustomerKeyResolver(customers, nonVoidBills, validTxs)

        val txBillIds = validTxs.filter { it.type == UdhariTransactionType.PAYMENT && it.billId != 0L }.map { it.billId }.toSet()
        val txBillNumbers = validTxs.filter { it.type == UdhariTransactionType.PAYMENT && it.billNumber.isNotBlank() }.map { it.billNumber }.toSet()

        val allCustomerKeys = mutableSetOf<String>()
        nonVoidBills.forEach { b ->
            keyResolver(b.customerMobile, b.customerName, b.customerId)?.let { allCustomerKeys.add(it) }
        }
        validTxs.forEach { tx ->
            keyResolver(tx.customerMobile, tx.customerName, tx.customerId)?.let { allCustomerKeys.add(it) }
        }

        val balances = mutableMapOf<String, Double>()
        for (key in allCustomerKeys) {
            val custBills = nonVoidBills.filter { b ->
                keyResolver(b.customerMobile, b.customerName, b.customerId) == key
            }
            val custTxs = validTxs.filter { tx ->
                keyResolver(tx.customerMobile, tx.customerName, tx.customerId) == key
            }

            val billCharges = custBills.sumOf { it.finalAmount }
            val manualCredits = custTxs.filter { it.billId == 0L && it.type == UdhariTransactionType.PURCHASE_CREDIT }.sumOf { it.amount }
            val totalCharges = billCharges + manualCredits

            val txPayments = custTxs.filter { it.type == UdhariTransactionType.PAYMENT }.sumOf { it.amount }
            val txReversals = custTxs.filter { it.type == UdhariTransactionType.REVERSAL }.sumOf { it.amount }
            val netTxPayments = (txPayments - txReversals).coerceAtLeast(0.0)

            // CRITICAL: Include upfront checkout payments on all bills (PAID and PARTIAL) when not already represented by a payment transaction!
            val upfrontCheckoutPayments = custBills.filter { b ->
                !txBillIds.contains(b.id) && !txBillNumbers.contains(b.billNumber)
            }.sumOf { b ->
                if (b.paymentStatus == PaymentStatus.PAID) b.finalAmount else b.paidAmount
            }

            val totalCredits = netTxPayments + upfrontCheckoutPayments
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
        transactions: List<UdhariTransaction>,
        customers: List<Customer> = emptyList()
    ): Double {
        val balances = calculateCustomerLedgerBalances(activeBills, transactions, customers)
        return balances.values.sum()
    }

    /**
     * Calculates the count of unique customers with outstanding balance > ₹0.01.
     */
    fun calculatePendingCustomersCount(
        activeBills: List<Bill>,
        transactions: List<UdhariTransaction>,
        customers: List<Customer> = emptyList()
    ): Int {
        val balances = calculateCustomerLedgerBalances(activeBills, transactions, customers)
        return balances.count { it.value > 0.01 }
    }
}

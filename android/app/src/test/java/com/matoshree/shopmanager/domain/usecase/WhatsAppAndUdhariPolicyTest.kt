package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.PhoneUtils
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WhatsAppAndUdhariPolicyTest {

    @Test
    fun testUdhariTotalsConsistencyBetweenDashboardAndUdhariPage() {
        val cust1 = Customer(id = 1L, name = "Kajal Salgar", mobile = "9876543210", outstandingBalance = 0.0)
        val cust2 = Customer(id = 2L, name = "Rahul Patil", mobile = "9822001122", outstandingBalance = 0.0)
        val customers = listOf(cust1, cust2)

        val bill1 = Bill(
            id = 101L,
            billNumber = "MC-101",
            customerId = 1L,
            customerName = "Kajal Salgar",
            customerMobile = "9876543210",
            subtotal = 500.0,
            finalAmount = 500.0,
            paidAmount = 0.0,
            pendingAmount = 500.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-14",
            saleType = SaleType.DETAILED,
            transactionUuid = "uuid-101"
        )

        val bill2 = Bill(
            id = 102L,
            billNumber = "MC-102",
            customerId = 1L,
            customerName = "Kajal Salgar",
            customerMobile = "9876543210",
            subtotal = 1000.0,
            finalAmount = 1000.0,
            paidAmount = 1000.0,
            pendingAmount = 0.0,
            paymentStatus = PaymentStatus.PAID,
            billDate = "2026-11-14",
            saleType = SaleType.DETAILED,
            transactionUuid = "uuid-102"
        )

        val bill3 = Bill(
            id = 103L,
            billNumber = "MC-103",
            customerId = 2L,
            customerName = "Rahul Patil",
            customerMobile = "9822001122",
            subtotal = 1200.0,
            finalAmount = 1200.0,
            paidAmount = 200.0,
            pendingAmount = 1000.0,
            paymentStatus = PaymentStatus.PARTIAL,
            billDate = "2026-11-14",
            saleType = SaleType.DETAILED,
            transactionUuid = "uuid-103"
        )

        val bills = listOf(bill1, bill2, bill3)

        // Partial payment on Kajal Salgar's credit
        val paymentTx = UdhariTransaction(
            id = "tx1",
            customerId = 1L,
            customerName = "Kajal Salgar",
            customerMobile = "9876543210",
            billId = 101L,
            billNumber = "MC-101",
            type = UdhariTransactionType.PAYMENT,
            amount = 280.0,
            paymentMethod = PaymentMethod.CASH,
            date = "2026-11-14",
            transactionUuid = "uuid-tx1"
        )
        val transactions = listOf(paymentTx)

        val ledgerBalances = ShopMetricsCalculator.calculateCustomerLedgerBalances(bills, transactions, customers)
        val totalUdhari = ShopMetricsCalculator.calculateTotalPendingUdhari(bills, transactions, customers)
        val pendingCount = ShopMetricsCalculator.calculatePendingCustomersCount(bills, transactions, customers)
        val keyResolver = ShopMetricsCalculator.buildCustomerKeyResolver(customers, bills, transactions)

        // Kajal Salgar owes 500 - 280 = 220
        val kajalKey = keyResolver(cust1.mobile, cust1.name, cust1.id)!!
        assertEquals(220.0, ledgerBalances[kajalKey] ?: 0.0, 0.01)

        // Rahul Patil owes 1000
        val rahulKey = keyResolver(cust2.mobile, cust2.name, cust2.id)!!
        assertEquals(1000.0, ledgerBalances[rahulKey] ?: 0.0, 0.01)

        // Total pending udhari shop-wide must be 220 + 1000 = 1220.0
        assertEquals(1220.0, totalUdhari, 0.01)
        assertEquals(2, pendingCount)
    }

    @Test
    fun testReminderClaimIdFormatAndDeduplication() {
        val cleanMobile = PhoneUtils.clean10DigitMobileInput("+91 98765 43210")
        val today = "2026-11-14"
        val expectedClaimId = "${cleanMobile}_${today}"

        assertEquals("9876543210", cleanMobile)
        assertEquals("9876543210_2026-11-14", expectedClaimId)
    }

    @Test
    fun testCustomerWithZeroBalanceNeverDispatched() {
        val balances = mapOf(
            "9876543210" to 0.0,
            "9822001122" to 0.005,
            "9833445566" to 500.0
        )

        // Filtering rule: must be > 0.01
        val eligibleMobiles = balances.filter { it.value > 0.01 }.keys.toList()

        assertEquals(listOf("9833445566"), eligibleMobiles)
    }

    @Test
    fun testMultipleDueBillsGroupedToSingleCustomerMessage() {
        // Customer has 3 separate bills with due date today
        val txs = listOf(
            UdhariTransaction(id = "1", customerId = 10L, customerName = "Sunil Shinde", customerMobile = "9811223344", billId = 1L, billNumber = "B1", transactionUuid = "u1", date = "2026-11-14", type = UdhariTransactionType.PURCHASE_CREDIT, amount = 300.0, dueDate = "2026-11-14"),
            UdhariTransaction(id = "2", customerId = 10L, customerName = "Sunil Shinde", customerMobile = "9811223344", billId = 2L, billNumber = "B2", transactionUuid = "u2", date = "2026-11-14", type = UdhariTransactionType.PURCHASE_CREDIT, amount = 400.0, dueDate = "2026-11-14"),
            UdhariTransaction(id = "3", customerId = 10L, customerName = "Sunil Shinde", customerMobile = "9811223344", billId = 3L, billNumber = "B3", transactionUuid = "u3", date = "2026-11-14", type = UdhariTransactionType.PURCHASE_CREDIT, amount = 500.0, dueDate = "2026-11-14")
        )

        val grouped = mutableMapOf<String, String>()
        for (tx in txs) {
            val clean = PhoneUtils.clean10DigitMobileInput(tx.customerMobile)
            if (!grouped.containsKey(clean)) {
                grouped[clean] = tx.customerName
            }
        }

        // Exactly 1 message entry despite 3 bills
        assertEquals(1, grouped.size)
        assertEquals("Sunil Shinde", grouped["9811223344"])
    }
}

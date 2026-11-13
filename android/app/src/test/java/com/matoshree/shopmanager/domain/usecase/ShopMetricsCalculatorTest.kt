package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ShopMetricsCalculatorTest {

    @Test
    fun testTodaySalesCalculation_onlyIncludesNonVoidTodayBills() {
        val today = "2026-11-13"
        val bills = listOf(
            createBill(id = 1, amount = 500.0, date = "2026-11-13 10:00:00", voided = false),
            createBill(id = 2, amount = 1000.0, date = "2026-11-13 11:00:00", voided = false),
            createBill(id = 3, amount = 300.0, date = "2026-11-13 12:00:00", voided = true), // Voided - should be excluded
            createBill(id = 4, amount = 800.0, date = "2026-11-12 15:00:00", voided = false)  // Yesterday - should be excluded
        )

        val todaySales = ShopMetricsCalculator.calculateTodaySales(bills, today)
        assertEquals(1500.0, todaySales, 0.001)
    }

    @Test
    fun testMonthlySalesCalculation() {
        val month = "2026-11"
        val bills = listOf(
            createBill(id = 1, amount = 500.0, date = "2026-11-01 10:00:00", voided = false),
            createBill(id = 2, amount = 1500.0, date = "2026-11-13 11:00:00", voided = false),
            createBill(id = 3, amount = 700.0, date = "2026-10-31 12:00:00", voided = false), // Previous month
            createBill(id = 4, amount = 400.0, date = "2026-11-05 15:00:00", voided = true)  // Voided
        )

        val monthSales = ShopMetricsCalculator.calculateMonthlySales(bills, month)
        assertEquals(2000.0, monthSales, 0.001)
    }

    @Test
    fun testTotalPendingUdhariCalculation() {
        val bills = listOf(
            // Paid in full via checkout cash (pure cash checkout)
            createBill(id = 1, amount = 1000.0, date = "2026-11-10 10:00:00", voided = false, paymentStatus = PaymentStatus.PAID),
            // Udhari bill pending ₹1500
            createBill(id = 2, amount = 1500.0, date = "2026-11-11 11:00:00", voided = false, paymentStatus = PaymentStatus.PENDING),
            // Voided bill - excluded
            createBill(id = 3, amount = 2000.0, date = "2026-11-12 12:00:00", voided = true, paymentStatus = PaymentStatus.PENDING)
        )

        val transactions = listOf(
            // Partial payment of ₹500 on Bill 2
            UdhariTransaction(
                id = "tx1",
                customerId = 101,
                customerName = "Ramesh",
                customerMobile = "9876543210",
                billId = 2,
                billNumber = "BILL-2",
                type = UdhariTransactionType.PAYMENT,
                amount = 500.0,
                paymentMethod = PaymentMethod.CASH,
                previousOutstanding = 1500.0,
                remainingOutstanding = 1000.0,
                transactionUuid = "uuid-1",
                date = "2026-11-12",
                createdAt = System.currentTimeMillis()
            )
        )

        // Total charges: ₹1000 (bill 1) + ₹1500 (bill 2) = ₹2500
        // Total credits: ₹500 (tx payment) + ₹1000 (pure cash bill 1) = ₹1500
        // Expected outstanding: ₹2500 - ₹1500 = ₹1000.0
        val pending = ShopMetricsCalculator.calculateTotalPendingUdhari(bills, transactions)
        assertEquals(1000.0, pending, 0.001)
    }

    private fun createBill(
        id: Long,
        amount: Double,
        date: String,
        voided: Boolean,
        paymentStatus: PaymentStatus = PaymentStatus.PAID
    ): Bill {
        return Bill(
            id = id,
            shopId = 1,
            billNumber = "BILL-$id",
            transactionUuid = "tx-$id",
            saleType = SaleType.QUICK,
            subtotal = amount,
            finalAmount = amount,
            paidAmount = if (paymentStatus == PaymentStatus.PAID) amount else 0.0,
            pendingAmount = if (paymentStatus == PaymentStatus.PAID) 0.0 else amount,
            billDate = date,
            isVoided = voided,
            paymentStatus = paymentStatus
        )
    }
}

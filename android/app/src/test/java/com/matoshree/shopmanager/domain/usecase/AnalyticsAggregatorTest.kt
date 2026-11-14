package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class AnalyticsAggregatorTest {

    private fun createBill(
        id: Long,
        billNumber: String,
        amount: Double,
        paid: Double,
        paymentMethod: PaymentMethod = PaymentMethod.CASH,
        date: String = "2026-11-14 12:00:00",
        isVoided: Boolean = false,
        profit: Double = amount * 0.25,
        items: List<BillItem> = listOf(BillItem(id = id, billId = id, productName = "Saree", quantity = 1, sellingPrice = amount, lineTotal = amount)),
        customerGid: String = "cus_1"
    ): Bill {
        return Bill(
            id = id,
            billNumber = billNumber,
            transactionUuid = "uuid-bill-$id",
            saleType = SaleType.QUICK,
            subtotal = amount,
            finalAmount = amount,
            paidAmount = paid,
            pendingAmount = (amount - paid).coerceAtLeast(0.0),
            paymentStatus = if (paid >= amount) PaymentStatus.PAID else if (paid > 0) PaymentStatus.PARTIAL else PaymentStatus.PENDING,
            paymentMethod = paymentMethod,
            billDate = date,
            isVoided = isVoided,
            estimatedProfit = profit,
            items = items,
            customerGid = customerGid,
            customerMobile = "9822001122",
            customerName = "Ramesh Patil"
        )
    }

    @Test
    fun testAnalyticsAggregator_ComputesHandCheckedMetricsAccurately() {
        val bills = listOf(
            createBill(1L, "MC-1", 1000.0, 1000.0, PaymentMethod.CASH, date = "2026-11-14 10:00:00", profit = 250.0),
            createBill(2L, "MC-2", 2000.0, 1000.0, PaymentMethod.UPI, date = "2026-11-14 14:00:00", profit = 500.0), // 1000 udhari
            createBill(3L, "MC-3", 500.0, 0.0, PaymentMethod.OTHER, date = "2026-11-14 18:00:00", profit = 125.0), // 500 udhari
            createBill(4L, "MC-4", 800.0, 800.0, PaymentMethod.CASH, date = "2026-11-14 19:00:00", isVoided = true) // VOIDED - must be excluded!
        )

        val expenses = listOf(
            Expense(id = 1L, category = "चहा / नाश्ता", amount = 150.0, expenseDate = "2026-11-14", note = "Tea")
        )

        val txs = listOf(
            UdhariTransaction(
                id = "tx1",
                gid = "pay_tx1",
                customerId = 1L,
                customerGid = "cus_1",
                customerName = "Ramesh Patil",
                customerMobile = "9822001122",
                amount = 300.0,
                type = UdhariTransactionType.PAYMENT,
                billId = 0L,
                billNumber = "DIRECT-PAY",
                transactionUuid = "uuid-tx1",
                date = "2026-11-14",
                paymentMethod = PaymentMethod.CASH
            )
        )

        val dateRange = AnalyticsDateRange(
            startDate = "2026-11-14 00:00:00",
            endDate = "2026-11-14 23:59:59",
            preset = AnalyticsPeriodPreset.TODAY
        )

        val result = AnalyticsAggregator.compute(
            bills = bills,
            transactions = txs,
            expenses = expenses,
            products = emptyList(),
            customers = emptyList(),
            dateRange = dateRange,
            compareRange = null
        )

        // Voided bill MC-4 must be excluded
        assertEquals(3, result.sales.billCount)
        assertEquals(3500.0, result.sales.netSales, 0.001) // 1000 + 2000 + 500
        assertEquals(875.0, result.profit.grossProfit, 0.001) // 250 + 500 + 125
        assertEquals(150.0, result.profit.totalExpenses, 0.001)
        assertEquals(725.0, result.profit.netProfit, 0.001) // 875 - 150

        // Payment split
        assertEquals(1000.0, result.paymentSplit.cashAmount, 0.001)
        assertEquals(1000.0, result.paymentSplit.upiAmount, 0.001)
        assertEquals(1500.0, result.paymentSplit.udhariAmount, 0.001)

        // Udhari collections in period
        assertEquals(300.0, result.udhari.recoveredInPeriod, 0.001)
        assertEquals(1500.0, result.udhari.creditGivenInPeriod, 0.001)
    }

    @Test
    fun testPeriodComparison_ComputesDeltasAccurately() {
        val currentSales = 5000.0
        val previousSales = 4000.0

        val delta = AnalyticsAggregator.calculateDelta(currentSales, previousSales)
        assertEquals(1000.0, delta.amountDelta, 0.001)
        assertEquals(25.0, delta.percentageDelta, 0.001)
        assertTrue(delta.isPositive)

        val dropDelta = AnalyticsAggregator.calculateDelta(3000.0, 4000.0)
        assertEquals(-1000.0, dropDelta.amountDelta, 0.001)
        assertEquals(-25.0, dropDelta.percentageDelta, 0.001)
        assertFalse(dropDelta.isPositive)
    }

    @Test
    fun testPresetDateRangeBoundaries_FinancialYearAndMonth() {
        val fy = AnalyticsDateRange.fromPreset(AnalyticsPeriodPreset.FINANCIAL_YEAR, referenceYear = 2026, referenceMonth = 11)
        assertTrue("Financial year must start in April", fy.startDate.startsWith("2026-04-01"))
        assertTrue("Financial year must end in March next year", fy.endDate.startsWith("2027-03-31"))

        val thisMonth = AnalyticsDateRange.fromPreset(AnalyticsPeriodPreset.THIS_MONTH, referenceYear = 2026, referenceMonth = 11)
        assertTrue(thisMonth.startDate.startsWith("2026-11-01"))
        assertTrue(thisMonth.endDate.startsWith("2026-11-30"))
    }
}

package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopMetricsCalculatorTest {

    @Test
    fun testTodaySalesCalculation_usesProvidedTodayDatePrefix() {
        val customDate = "2025-06-15"
        val bills = listOf(
            createBill(id = 1, amount = 500.0, date = "2025-06-15 10:00:00", voided = false),
            createBill(id = 2, amount = 1000.0, date = "2025-06-15 11:00:00", voided = false),
            createBill(id = 3, amount = 300.0, date = "2025-06-15 12:00:00", voided = true), // Voided
            createBill(id = 4, amount = 800.0, date = "2025-06-14 15:00:00", voided = false)  // Other day
        )

        val todaySales = ShopMetricsCalculator.calculateTodaySales(bills, customDate)
        assertEquals(1500.0, todaySales, 0.001)
    }

    @Test
    fun testTodayProfit_neverFabricatesArbitraryTwentyFivePercent() {
        val today = "2026-11-13"
        val bills = listOf(
            // Bill with actual profit recorded
            createBill(id = 1, amount = 1000.0, date = "2026-11-13 10:00:00", voided = false, actualProfit = 220.0),
            // Bill with estimated profit recorded
            createBill(id = 2, amount = 800.0, date = "2026-11-13 11:00:00", voided = false, actualProfit = 0.0, estimatedProfit = 150.0),
            // Bill with no cost/profit data (should return 0.0, NOT 25% fabricated profit)
            createBill(id = 3, amount = 400.0, date = "2026-11-13 12:00:00", voided = false, actualProfit = 0.0, estimatedProfit = 0.0)
        )

        val totalProfit = ShopMetricsCalculator.calculateTodayProfit(bills, today)
        assertEquals(370.0, totalProfit, 0.001)
    }

    @Test
    fun testCrossDeviceCustomerIdCollision_separatedProperlyByMobile() {
        // Phone A has customer "Ramesh" with local id 7, mobile 9876543210
        // Phone B has customer "Suresh" with local id 7, mobile 9999999999
        val bills = listOf(
            createBill(id = 101, amount = 1000.0, date = "2026-11-10 10:00:00", customerId = 7L, customerMobile = "9876543210", customerName = "Ramesh"),
            createBill(id = 102, amount = 2000.0, date = "2026-11-10 11:00:00", customerId = 7L, customerMobile = "9999999999", customerName = "Suresh")
        )

        val balances = ShopMetricsCalculator.calculateCustomerLedgerBalances(bills, emptyList())
        assertEquals(2, balances.size)
        assertEquals(1000.0, balances["mob_9876543210"] ?: 0.0, 0.001)
        assertEquals(2000.0, balances["mob_9999999999"] ?: 0.0, 0.001)
        assertEquals(3000.0, ShopMetricsCalculator.calculateTotalPendingUdhari(bills, emptyList()), 0.001)
        assertEquals(2, ShopMetricsCalculator.calculatePendingCustomersCount(bills, emptyList()))
    }

    @Test
    fun testSameNameDifferentMobile_notMerged() {
        // Two distinct customers named "Ramesh" with different phone numbers
        val bills = listOf(
            createBill(id = 1, amount = 1200.0, date = "2026-11-10 10:00:00", customerName = "Ramesh", customerMobile = "9876543210"),
            createBill(id = 2, amount = 800.0, date = "2026-11-10 11:00:00", customerName = "Ramesh", customerMobile = "9123456789")
        )

        val balances = ShopMetricsCalculator.calculateCustomerLedgerBalances(bills, emptyList())
        assertEquals(2, balances.size)
        assertEquals(1200.0, balances["mob_9876543210"] ?: 0.0, 0.001)
        assertEquals(800.0, balances["mob_9123456789"] ?: 0.0, 0.001)
    }

    @Test
    fun testCustomerPaidInFull_excludedFromPendingCustomersCount() {
        val bills = listOf(
            createBill(id = 1, amount = 1000.0, date = "2026-11-10 10:00:00", customerName = "Ramesh", customerMobile = "9876543210", paymentStatus = PaymentStatus.PENDING),
            createBill(id = 2, amount = 500.0, date = "2026-11-10 11:00:00", customerName = "Suresh", customerMobile = "9999999999", paymentStatus = PaymentStatus.PENDING)
        )

        val transactions = listOf(
            // Ramesh pays ₹1000 in full
            UdhariTransaction(
                id = "tx1",
                customerId = 1,
                customerName = "Ramesh",
                customerMobile = "9876543210",
                billId = 1,
                billNumber = "BILL-1",
                type = UdhariTransactionType.PAYMENT,
                amount = 1000.0,
                paymentMethod = PaymentMethod.UPI,
                previousOutstanding = 1000.0,
                remainingOutstanding = 0.0,
                transactionUuid = "uuid-1",
                date = "2026-11-11",
                createdAt = 1000L
            )
        )

        val balances = ShopMetricsCalculator.calculateCustomerLedgerBalances(bills, transactions)
        assertEquals(0.0, balances["mob_9876543210"] ?: 0.0, 0.001)
        assertEquals(500.0, balances["mob_9999999999"] ?: 0.0, 0.001)

        // Ramesh has ₹0 balance, so pending count must be 1 (only Suresh)
        assertEquals(1, ShopMetricsCalculator.calculatePendingCustomersCount(bills, transactions))
        assertEquals(500.0, ShopMetricsCalculator.calculateTotalPendingUdhari(bills, transactions), 0.001)
    }

    @Test
    fun testMathematicalEquality_dashboardTotalEqualsSumOfCustomerBalances() {
        val bills = listOf(
            createBill(id = 1, amount = 1500.0, date = "2026-11-01 10:00:00", customerName = "Customer A", customerMobile = "9111111111", paymentStatus = PaymentStatus.PENDING),
            createBill(id = 2, amount = 2500.0, date = "2026-11-02 11:00:00", customerName = "Customer B", customerMobile = "9222222222", paymentStatus = PaymentStatus.PENDING),
            createBill(id = 3, amount = 800.0, date = "2026-11-03 12:00:00", customerName = "Customer C", customerMobile = "9333333333", paymentStatus = PaymentStatus.PAID)
        )

        val transactions = listOf(
            UdhariTransaction(
                id = "tx1",
                customerId = 1,
                customerName = "Customer A",
                customerMobile = "9111111111",
                billId = 0L,
                billNumber = "",
                type = UdhariTransactionType.PAYMENT,
                amount = 500.0,
                paymentMethod = PaymentMethod.CASH,
                previousOutstanding = 1500.0,
                remainingOutstanding = 1000.0,
                transactionUuid = "uuid-1",
                date = "2026-11-05",
                createdAt = 2000L
            )
        )

        val balances = ShopMetricsCalculator.calculateCustomerLedgerBalances(bills, transactions)
        val sumOfIndividualBalances = balances.values.sum()
        val dashboardTotalPending = ShopMetricsCalculator.calculateTotalPendingUdhari(bills, transactions)

        assertEquals(sumOfIndividualBalances, dashboardTotalPending, 0.0001)
        assertEquals(3500.0, dashboardTotalPending, 0.001)
    }

    @Test
    fun testPartialPaymentBill_calculatesCorrectOutstanding() {
        // Bill total ₹520, customer pays ₹300 upfront, remaining ₹220 is udhari
        val partialBill = Bill(
            id = 1,
            shopId = 1,
            customerId = 10,
            customerName = "Kajal Salgar",
            customerMobile = "9876543210",
            billNumber = "BILL-1",
            transactionUuid = "uuid-1",
            saleType = SaleType.QUICK,
            subtotal = 520.0,
            discountType = DiscountType.NONE,
            discountValue = 0.0,
            discountAmount = 0.0,
            taxAmount = 0.0,
            finalAmount = 520.0,
            paidAmount = 300.0,
            pendingAmount = 220.0,
            actualProfit = 0.0,
            estimatedProfit = 0.0,
            paymentMethod = PaymentMethod.CASH,
            paymentStatus = PaymentStatus.PARTIAL,
            billDate = "2026-11-13 14:00:00",
            isVoided = false,
            items = emptyList()
        )

        val balances = ShopMetricsCalculator.calculateCustomerLedgerBalances(listOf(partialBill), emptyList())
        assertEquals(220.0, balances["mob_9876543210"] ?: 0.0, 0.001)
        assertEquals(220.0, ShopMetricsCalculator.calculateTotalPendingUdhari(listOf(partialBill), emptyList()), 0.001)
        assertEquals(1, ShopMetricsCalculator.calculatePendingCustomersCount(listOf(partialBill), emptyList()))
    }

    @Test
    fun testLegacyCreditBills_countedFromTransactionsWhenBillAbsent() {
        // Customer has credit transactions from older bills not present in Room bills (e.g. 86 bills only in Room)
        val transactions = listOf(
            UdhariTransaction(
                id = "tx_credit_1",
                customerId = 5,
                customerName = "Older Customer",
                customerMobile = "9811112222",
                billId = 9999L,
                billNumber = "BILL-OLD-1",
                type = UdhariTransactionType.PURCHASE_CREDIT,
                amount = 10000.0,
                paymentMethod = PaymentMethod.CASH,
                previousOutstanding = 0.0,
                remainingOutstanding = 10000.0,
                transactionUuid = "uuid-old-1",
                date = "2026-09-01",
                createdAt = 100L
            ),
            UdhariTransaction(
                id = "tx_pay_1",
                customerId = 5,
                customerName = "Older Customer",
                customerMobile = "9811112222",
                billId = 9999L,
                billNumber = "BILL-OLD-1",
                type = UdhariTransactionType.PAYMENT,
                amount = 3000.0,
                paymentMethod = PaymentMethod.UPI,
                previousOutstanding = 10000.0,
                remainingOutstanding = 7000.0,
                transactionUuid = "uuid-old-2",
                date = "2026-09-15",
                createdAt = 200L
            )
        )

        // Bills list is empty (older bills pruned from cache)
        val balances = ShopMetricsCalculator.calculateCustomerLedgerBalances(emptyList(), transactions)
        assertEquals(7000.0, balances["mob_9811112222"] ?: 0.0, 0.001)
        assertEquals(7000.0, ShopMetricsCalculator.calculateTotalPendingUdhari(emptyList(), transactions), 0.001)
        assertEquals(1, ShopMetricsCalculator.calculatePendingCustomersCount(emptyList(), transactions))
    }

    @Test
    fun testCustomerOpeningBalance_countedWhenNoTransactionsOrBills() {
        val customers = listOf(
            Customer(
                id = 12,
                shopId = 1,
                name = "New Ledger Customer",
                mobile = "9822223333",
                outstandingBalance = 5500.0
            )
        )

        val balances = ShopMetricsCalculator.calculateCustomerLedgerBalances(emptyList(), emptyList(), customers)
        assertEquals(5500.0, balances["mob_9822223333"] ?: 0.0, 0.001)
        assertEquals(5500.0, ShopMetricsCalculator.calculateTotalPendingUdhari(emptyList(), emptyList(), customers), 0.001)
        assertEquals(1, ShopMetricsCalculator.calculatePendingCustomersCount(emptyList(), emptyList(), customers))
    }

    private fun createBill(
        id: Long,
        amount: Double,
        date: String,
        voided: Boolean = false,
        paymentStatus: PaymentStatus = PaymentStatus.PENDING,
        actualProfit: Double = 0.0,
        estimatedProfit: Double = 0.0,
        customerId: Long? = null,
        customerMobile: String? = null,
        customerName: String? = null
    ): Bill {
        return Bill(
            id = id,
            shopId = 1,
            customerId = customerId,
            customerName = customerName,
            customerMobile = customerMobile,
            billNumber = "BILL-$id",
            transactionUuid = "uuid-$id",
            saleType = SaleType.QUICK,
            subtotal = amount,
            discountType = DiscountType.NONE,
            discountValue = 0.0,
            discountAmount = 0.0,
            taxAmount = 0.0,
            finalAmount = amount,
            paidAmount = if (paymentStatus == PaymentStatus.PAID) amount else 0.0,
            pendingAmount = if (paymentStatus == PaymentStatus.PAID) 0.0 else amount,
            actualProfit = actualProfit,
            estimatedProfit = estimatedProfit,
            paymentMethod = PaymentMethod.CASH,
            paymentStatus = paymentStatus,
            billDate = date,
            isVoided = voided,
            items = emptyList()
        )
    }
}

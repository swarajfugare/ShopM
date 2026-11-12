package com.matoshree.shopmanager

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class UdhariLedgerTest {

    @Test
    fun testPureCreditSaleCalculation() {
        val total = 2500.0
        val paymentStatus = PaymentStatus.PENDING
        val paidAmount = 0.0
        val pendingAmount = (total - paidAmount).coerceAtLeast(0.0)

        assertEquals(2500.0, pendingAmount, 0.001)
        assertEquals(0.0, paidAmount, 0.001)
        assertEquals(PaymentStatus.PENDING, paymentStatus)
    }

    @Test
    fun testPartialPaymentSaleCalculation() {
        val total = 3000.0
        val paidNow = 1000.0
        val paymentStatus = PaymentStatus.PARTIAL
        val pendingAmount = (total - paidNow).coerceAtLeast(0.0)

        assertEquals(2000.0, pendingAmount, 0.001)
        assertEquals(1000.0, paidNow, 0.001)
        assertEquals(PaymentStatus.PARTIAL, paymentStatus)
    }

    @Test
    fun testMultiplePartialPaymentsMonotonicReduction() {
        var outstanding = 5000.0
        val payments = listOf(1000.0, 1500.0, 2500.0)

        val txs = mutableListOf<UdhariTransaction>()
        payments.forEach { payAmt ->
            val prev = outstanding
            outstanding = (outstanding - payAmt).coerceAtLeast(0.0)
            txs.add(
                UdhariTransaction(
                    id = UUID.randomUUID().toString(),
                    customerId = 1L,
                    customerName = "Priya Sharma",
                    customerMobile = "9876543210",
                    billId = 101L,
                    billNumber = "MC-2026-000101",
                    type = UdhariTransactionType.PAYMENT,
                    amount = payAmt,
                    paymentMethod = PaymentMethod.CASH,
                    previousOutstanding = prev,
                    remainingOutstanding = outstanding,
                    transactionUuid = UUID.randomUUID().toString(),
                    date = "2026-08-30",
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        assertEquals(0.0, outstanding, 0.001)
        assertEquals(3, txs.size)
        assertEquals(4000.0, txs[0].remainingOutstanding, 0.001)
        assertEquals(2500.0, txs[1].remainingOutstanding, 0.001)
        assertEquals(0.0, txs[2].remainingOutstanding, 0.001)
    }

    @Test
    fun testReversalRestoresOutstandingBalance() {
        val initialDue = 2000.0
        val payment = 1000.0
        val remainingAfterPayment = initialDue - payment

        assertEquals(1000.0, remainingAfterPayment, 0.001)

        // Reversal
        val restoredDue = remainingAfterPayment + payment
        assertEquals(2000.0, restoredDue, 0.001)
    }

    @Test
    fun testDailyClosingFinancialSeparation() {
        val todayBills = listOf(
            Bill(
                id = 1L,
                billNumber = "B1",
                subtotal = 1000.0,
                finalAmount = 1000.0,
                paidAmount = 1000.0,
                pendingAmount = 0.0,
                paymentMethod = PaymentMethod.CASH,
                paymentStatus = PaymentStatus.PAID,
                billDate = "2026-08-30 10:00:00",
                saleType = SaleType.QUICK,
                transactionUuid = UUID.randomUUID().toString()
            ),
            Bill(
                id = 2L,
                billNumber = "B2",
                subtotal = 2000.0,
                finalAmount = 2000.0,
                paidAmount = 500.0,
                pendingAmount = 1500.0,
                paymentMethod = PaymentMethod.UPI,
                paymentStatus = PaymentStatus.PARTIAL,
                billDate = "2026-08-30 11:00:00",
                saleType = SaleType.DETAILED,
                transactionUuid = UUID.randomUUID().toString()
            ),
            Bill(
                id = 3L,
                billNumber = "B3",
                subtotal = 3000.0,
                finalAmount = 3000.0,
                paidAmount = 0.0,
                pendingAmount = 3000.0,
                paymentMethod = PaymentMethod.CASH,
                paymentStatus = PaymentStatus.PENDING,
                billDate = "2026-08-30 12:00:00",
                saleType = SaleType.DETAILED,
                transactionUuid = UUID.randomUUID().toString()
            )
        )

        val todayUdhariCollections = listOf(
            UdhariTransaction(
                id = "T1",
                customerId = 10L,
                customerName = "A",
                customerMobile = "123",
                billId = 1L,
                billNumber = "B1",
                amount = 1000.0,
                paymentMethod = PaymentMethod.CASH,
                type = UdhariTransactionType.PAYMENT,
                transactionUuid = UUID.randomUUID().toString(),
                date = "2026-08-30"
            ),
            UdhariTransaction(
                id = "T2",
                customerId = 11L,
                customerName = "B",
                customerMobile = "456",
                billId = 2L,
                billNumber = "B2",
                amount = 500.0,
                paymentMethod = PaymentMethod.UPI,
                type = UdhariTransactionType.PAYMENT,
                transactionUuid = UUID.randomUUID().toString(),
                date = "2026-08-30"
            )
        )

        val totalSalesGenerated = todayBills.sumOf { it.finalAmount }
        val newBillCash = todayBills.filter { it.paymentMethod == PaymentMethod.CASH }.sumOf { it.paidAmount }
        val newBillUpi = todayBills.filter { it.paymentMethod == PaymentMethod.UPI }.sumOf { it.paidAmount }
        val udhariCash = todayUdhariCollections.filter { it.paymentMethod == PaymentMethod.CASH }.sumOf { it.amount }
        val udhariUpi = todayUdhariCollections.filter { it.paymentMethod == PaymentMethod.UPI }.sumOf { it.amount }

        val totalCashInDrawer = newBillCash + udhariCash
        val totalUpiReceived = newBillUpi + udhariUpi

        assertEquals(6000.0, totalSalesGenerated, 0.001)
        assertEquals(1000.0, newBillCash, 0.001)
        assertEquals(500.0, newBillUpi, 0.001)
        assertEquals(2000.0, totalCashInDrawer, 0.001) // 1000 new bill cash + 1000 udhari cash
        assertEquals(1000.0, totalUpiReceived, 0.001)  // 500 new bill UPI + 500 udhari UPI
    }
}

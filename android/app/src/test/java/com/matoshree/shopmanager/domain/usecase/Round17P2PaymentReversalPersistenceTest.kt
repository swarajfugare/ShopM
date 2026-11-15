package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * P2: Payment representation, cancellation, and reversal persistence tests.
 * Asserts:
 * 1. Clean bill numbers in payments allow proper ledger linking without synthetic checkout masking.
 * 2. Settlement payments are not masked as synthetic checkout payments, enabling cancellation ("रद्द करा").
 * 3. Legacy "MC-1 (₹200)" format is parsed and linked rather than orphaned.
 * 4. Multi-bill payments sharing transactionUuid correctly allocate and balance.
 * 5. Payment reversal restores bill pending and customer due to the exact paisa.
 */
class Round17P2PaymentReversalPersistenceTest {

    private fun createCustomer(id: Long, name: String, mobile: String): Customer {
        return Customer(
            id = id,
            gid = "cust_m_$mobile",
            name = name,
            mobile = mobile,
            isActive = true
        )
    }

    private fun createBill(
        id: Long,
        billNumber: String,
        customer: Customer,
        finalAmount: Double,
        paidAmount: Double = 0.0,
        pendingAmount: Double = (finalAmount - paidAmount).coerceAtLeast(0.0)
    ): Bill {
        return Bill(
            id = id,
            gid = "bil_$billNumber",
            billNumber = billNumber,
            billDate = "2026-11-15",
            transactionUuid = "uuid-$billNumber",
            saleType = SaleType.QUICK,
            subtotal = finalAmount,
            customerId = customer.id,
            customerGid = customer.gid,
            customerName = customer.name,
            customerMobile = customer.mobile,
            finalAmount = finalAmount,
            paidAmount = paidAmount,
            pendingAmount = pendingAmount,
            paymentStatus = if (pendingAmount <= 0.01) PaymentStatus.PAID else if (paidAmount > 0.01) PaymentStatus.PARTIAL else PaymentStatus.PENDING,
            paymentMethod = PaymentMethod.CASH
        )
    }

    private fun createPaymentTx(
        id: String,
        customer: Customer,
        amount: Double,
        billNumber: String,
        billGid: String? = null,
        billId: Long = 0L,
        kind: String = "SETTLEMENT",
        transactionUuid: String = "uuid-$id",
        isReversed: Boolean = false,
        note: String? = null
    ): UdhariTransaction {
        return UdhariTransaction(
            id = id,
            gid = id,
            customerId = customer.id,
            customerGid = customer.gid,
            customerName = customer.name,
            customerMobile = customer.mobile,
            billId = billId,
            billGid = billGid,
            billNumber = billNumber,
            amount = amount,
            type = UdhariTransactionType.PAYMENT,
            kind = kind,
            transactionUuid = transactionUuid,
            date = "2026-11-15",
            isReversed = isReversed,
            note = note
        )
    }

    @Test
    fun testSettlementPayment_NotMaskedAsSyntheticCheckout_AndLeavesDirectTxActive() {
        val cust = createCustomer(1L, "Swaraj", "9226299212")
        // Bill total 1000, 300 paid at checkout, 700 pending. Later settled 200 via settlement tx
        val bill = createBill(101L, "MC-101", cust, finalAmount = 1000.0, paidAmount = 300.0, pendingAmount = 700.0)

        // Real settlement transaction recorded
        val tx = createPaymentTx(
            id = "pay_settle_1",
            customer = cust,
            amount = 200.0,
            billNumber = "MC-101",
            billGid = "bil_MC-101",
            billId = 101L,
            kind = "SETTLEMENT"
        )

        val ledger = UdhariLedger.computeCustomerLedger(cust.gid, listOf(bill), listOf(tx), listOf(cust))

        assertEquals("Balance must be 500", 500.0, ledger.balance, 0.001)
        assertEquals("Total purchases 1000", 1000.0, ledger.totalPurchases, 0.001)
        assertEquals("Total paid 500", 500.0, ledger.totalPaid, 0.001)

        // Checkout paid must be 300 and direct settlement must be 200
        assertEquals("Checkout paid breakdown must be 300", 300.0, ledger.calculationBreakdown.checkoutPaidAmount, 0.001)
        assertEquals("Direct settlement breakdown must be 200", 200.0, ledger.calculationBreakdown.directSettlementAmount, 0.001)

        // Events must contain the real payment tx with id "pay_settle_1" (NOT synthetic "checkout_pay_*")
        val directCreditEvent = ledger.events.find { it.id == "pay_settle_1" }
        assertNotNull("Credit event must carry real transaction id", directCreditEvent)
        assertEquals("Direct credit amount must be 200", 200.0, directCreditEvent?.amount ?: 0.0, 0.001)
    }

    @Test
    fun testLegacyFormatWithBrackets_IsLinkedAndNotOrphaned() {
        val cust = createCustomer(2L, "Ramesh", "9822000000")
        val bill = createBill(102L, "MC-102", cust, finalAmount = 500.0, paidAmount = 100.0, pendingAmount = 400.0)

        // Legacy format recorded earlier: "MC-102 (₹200)"
        val legacyTx = createPaymentTx(
            id = "pay_legacy_1",
            customer = cust,
            amount = 200.0,
            billNumber = "MC-102 (₹200)",
            billGid = null, // legacy had no billGid
            billId = 0L,
            kind = ""
        )

        val ledger = UdhariLedger.computeCustomerLedger(cust.gid, listOf(bill), listOf(legacyTx), listOf(cust))

        // 500 purchase - 100 checkout - 200 direct legacy payment = 200 due
        assertEquals("Balance must be 200", 200.0, ledger.balance, 0.001)
        assertEquals("Total paid must be 300", 300.0, ledger.totalPaid, 0.001)
        assertTrue("Legacy tx must NOT be placed in orphan payments", ledger.orphanPayments.isEmpty())
        assertTrue("Legacy tx must NOT be placed in orphan transactions", ledger.orphanTransactions.isEmpty())
    }

    @Test
    fun testMultiBillSettlementGroup_AndAtomicReversal() {
        val cust = createCustomer(3L, "Suresh", "9822111111")
        val bill1 = createBill(201L, "MC-201", cust, finalAmount = 400.0, paidAmount = 200.0, pendingAmount = 200.0)
        val bill2 = createBill(202L, "MC-202", cust, finalAmount = 600.0, paidAmount = 300.0, pendingAmount = 300.0)

        val commonGroupId = "pay_group_uuid_100"
        val tx1 = createPaymentTx("pay_b1", cust, 200.0, "MC-201", "bil_MC-201", 201L, "SETTLEMENT", commonGroupId)
        val tx2 = createPaymentTx("pay_b2", cust, 300.0, "MC-202", "bil_MC-202", 202L, "SETTLEMENT", commonGroupId)

        val activeLedger = UdhariLedger.computeCustomerLedger(cust.gid, listOf(bill1, bill2), listOf(tx1, tx2), listOf(cust))
        assertEquals("Active due should be 500", 500.0, activeLedger.balance, 0.001)
        assertEquals("Total paid should be 500", 500.0, activeLedger.totalPaid, 0.001)

        // Simulate reversal of the payment group
        val revTx1 = tx1.copy(isReversed = true, reversalReason = "Wrong entry")
        val revTx2 = tx2.copy(isReversed = true, reversalReason = "Wrong entry")
        val restoredBill1 = bill1.copy(paidAmount = 0.0, pendingAmount = 400.0, paymentStatus = PaymentStatus.PENDING)
        val restoredBill2 = bill2.copy(paidAmount = 0.0, pendingAmount = 600.0, paymentStatus = PaymentStatus.PENDING)

        val reversedLedger = UdhariLedger.computeCustomerLedger(cust.gid, listOf(restoredBill1, restoredBill2), listOf(revTx1, revTx2), listOf(cust))
        assertEquals("Reversed due must return to full 1000", 1000.0, reversedLedger.balance, 0.001)
        assertEquals("Total paid must be 0", 0.0, reversedLedger.totalPaid, 0.001)
    }
}

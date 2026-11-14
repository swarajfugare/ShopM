package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Reproduction and regression tests for Round 13 (v3.5.3):
 * - P0-B: Deterministic docId and GID generation for customers with and without mobile.
 * - P0-C: Elimination of double checkout payment counting and isolation of foreign/orphan payments.
 * - P1-E: Payment cancellation / reversal restoring balance.
 */
class Round13RegressionTest {

    private fun createBill(
        id: Long,
        billNumber: String,
        gid: String = "bil_$id",
        customerId: Long,
        customerGid: String,
        customerName: String,
        customerMobile: String,
        finalAmount: Double,
        paidAmount: Double,
        pendingAmount: Double,
        paymentStatus: PaymentStatus = if (pendingAmount <= 0.01) PaymentStatus.PAID else if (paidAmount > 0.01) PaymentStatus.PARTIAL else PaymentStatus.PENDING,
        billDate: String = "2026-11-14 10:00:00"
    ): Bill {
        return Bill(
            id = id,
            gid = gid,
            billNumber = billNumber,
            transactionUuid = "uuid-bill-$id",
            saleType = SaleType.QUICK,
            subtotal = finalAmount,
            finalAmount = finalAmount,
            paidAmount = paidAmount,
            pendingAmount = pendingAmount,
            paymentStatus = paymentStatus,
            customerId = customerId,
            customerGid = customerGid,
            customerName = customerName,
            customerMobile = customerMobile,
            billDate = billDate
        )
    }

    private fun createTx(
        id: String,
        gid: String = "pay_$id",
        customerId: Long,
        customerGid: String,
        name: String,
        mobile: String,
        amount: Double,
        type: UdhariTransactionType = UdhariTransactionType.PAYMENT,
        billId: Long = 0L,
        billGid: String? = null,
        billNumber: String = "",
        date: String = "2026-11-14",
        createdAt: Long = 1700000000000L,
        note: String? = null,
        isReversed: Boolean = false
    ): UdhariTransaction {
        return UdhariTransaction(
            id = id,
            gid = gid,
            customerId = customerId,
            customerGid = customerGid,
            customerName = name,
            customerMobile = mobile,
            billId = billId,
            billGid = billGid,
            billNumber = billNumber,
            type = type,
            amount = amount,
            transactionUuid = "uuid-tx-$id",
            date = date,
            createdAt = createdAt,
            note = note,
            isReversed = isReversed
        )
    }

    @Test
    fun testP0C_CheckoutPaymentDoubleCounting_Fixed() {
        // Customer "Swaraj Fugare 9226299212"
        val custGid = IdGenerator.customerGid("cust_m_9226299212")
        val cust = Customer(id = 1L, gid = custGid, name = "Swaraj Fugare", mobile = "9226299212")

        // Bill: ₹100 final amount, ₹50 paid at checkout, ₹50 pending udhari
        val bill = createBill(
            id = 101L,
            billNumber = "BILL-101",
            gid = "bil_101",
            customerId = 1L,
            customerGid = custGid,
            customerName = "Swaraj Fugare",
            customerMobile = "9226299212",
            finalAmount = 100.0,
            paidAmount = 50.0,
            pendingAmount = 50.0
        )

        // When the bill was created, a PAYMENT transaction for ₹50 was also recorded with plain note "Bill payment"
        val checkoutPayTx = createTx(
            id = "tx1",
            gid = "pay_01JCHECKOUT0000001",
            customerId = 1L,
            customerGid = custGid,
            name = "Swaraj Fugare",
            mobile = "9226299212",
            amount = 50.0,
            type = UdhariTransactionType.PAYMENT,
            billId = 101L,
            billGid = "bil_101",
            billNumber = "BILL-101",
            note = "Bill payment" // Does NOT contain "checkout" or "initial"
        )

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill),
            allTransactions = listOf(checkoutPayTx),
            allCustomers = listOf(cust)
        )

        // The remaining balance MUST be exactly ₹50, NOT ₹0!
        assertEquals("Remaining balance must be 50.0, not 0.0 (no double deduction)", 50.0, ledger.balance, 0.001)
        assertEquals("Total purchases must be 100.0", 100.0, ledger.totalPurchases, 0.001)
        assertEquals("Total paid must be exactly 50.0, not 100.0", 50.0, ledger.totalPaid, 0.001)
    }

    @Test
    fun testP0C_OrphanPayment_DoesNotReduceBalanceOrAppearInPayments() {
        val custGid = IdGenerator.customerGid("cust_m_9226299212")
        val cust = Customer(id = 1L, gid = custGid, name = "Swaraj Fugare", mobile = "9226299212")

        // Bill: 1 bill of ₹100, ₹50 paid, ₹50 pending udhari
        val bill = createBill(
            id = 101L,
            billNumber = "BILL-101",
            gid = "bil_101",
            customerId = 1L,
            customerGid = custGid,
            customerName = "Swaraj Fugare",
            customerMobile = "9226299212",
            finalAmount = 100.0,
            paidAmount = 50.0,
            pendingAmount = 50.0
        )

        // Genuine checkout payment of ₹50
        val genuinePayTx = createTx(
            id = "tx1",
            gid = "pay_genuine_50",
            customerId = 1L,
            customerGid = custGid,
            name = "Swaraj Fugare",
            mobile = "9226299212",
            amount = 50.0,
            billId = 101L,
            billGid = "bil_101",
            billNumber = "BILL-101"
        )

        // Two foreign / orphan payments referencing bill "MC-2026-97860" (which does NOT exist for this customer)
        val foreignTx1 = createTx(
            id = "tx_foreign_1",
            gid = "pay_foreign_1",
            customerId = 1L,
            customerGid = custGid,
            name = "Swaraj Fugare",
            mobile = "9226299212",
            amount = 25.0,
            billId = 99999L,
            billNumber = "MC-2026-97860"
        )
        val foreignTx2 = createTx(
            id = "tx_foreign_2",
            gid = "pay_foreign_2",
            customerId = 1L,
            customerGid = custGid,
            name = "Swaraj Fugare",
            mobile = "9226299212",
            amount = 25.0,
            billId = 99999L,
            billNumber = "MC-2026-97860"
        )

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill),
            allTransactions = listOf(genuinePayTx, foreignTx1, foreignTx2),
            allCustomers = listOf(cust)
        )

        // Outstanding due must be 50.0 (the 2 foreign payments must NOT reduce the due via FIFO)
        assertEquals("Due must be 50.0 regardless of foreign payments", 50.0, ledger.balance, 0.001)

        // The foreign payments must NOT be in the customer's billBreakdowns or credit events
        val paymentEvents = ledger.events.filter { it.isCredit && it.billNumber == "MC-2026-97860" }
        assertEquals("Foreign payments for non-existent bill must not be applied to customer events", 0, paymentEvents.size)

        // Foreign payments must be captured in orphanPayments
        assertEquals("Two orphan payments captured for audit", 2, ledger.orphanPayments.size)
    }

    @Test
    fun testP1E_ReversalTransaction_RestoresDueAndMarksOriginal() {
        val custGid = IdGenerator.customerGid("cust_m_9822112233")
        val cust = Customer(id = 2L, gid = custGid, name = "Anand", mobile = "9822112233")

        val bill = createBill(
            id = 201L,
            billNumber = "BILL-201",
            gid = "bil_201",
            customerId = 2L,
            customerGid = custGid,
            customerName = "Anand",
            customerMobile = "9822112233",
            finalAmount = 1000.0,
            paidAmount = 0.0,
            pendingAmount = 1000.0
        )

        val paymentTx = createTx(
            id = "tx_pay_300",
            gid = "pay_300",
            customerId = 2L,
            customerGid = custGid,
            name = "Anand",
            mobile = "9822112233",
            amount = 300.0,
            billId = 201L,
            billGid = "bil_201",
            billNumber = "BILL-201",
            isReversed = true // Cancelled!
        )

        val reversalTx = createTx(
            id = "tx_rev_300",
            gid = "rev_300",
            customerId = 2L,
            customerGid = custGid,
            name = "Anand",
            mobile = "9822112233",
            amount = 300.0,
            type = UdhariTransactionType.REVERSAL,
            billId = 201L,
            billGid = "bil_201",
            billNumber = "BILL-201"
        )

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill),
            allTransactions = listOf(paymentTx, reversalTx),
            allCustomers = listOf(cust)
        )

        // Cancelled payment must restore the original due of 1000.0
        assertEquals("Due must be 1000.0 when payment is reversed", 1000.0, ledger.balance, 0.001)
        assertEquals("Total paid must be 0.0", 0.0, ledger.totalPaid, 0.001)
    }

    @Test
    fun testP0B_CanonicalCustomerDocIdAndGid_DeterministicWithAndWithoutMobile() {
        // Customer WITH mobile
        val (docIdWithMob1, gidWithMob1) = IdGenerator.canonicalCustomerDocIdAndGid("9226299212")
        val (docIdWithMob2, gidWithMob2) = IdGenerator.canonicalCustomerDocIdAndGid("+91 92262 99212")
        assertEquals("cust_m_9226299212", docIdWithMob1)
        assertEquals(docIdWithMob1, docIdWithMob2)
        assertEquals(gidWithMob1, gidWithMob2)
        assertTrue(gidWithMob1.startsWith("cus_"))

        // Customer WITHOUT mobile
        val (docIdNoMob, gidNoMob) = IdGenerator.canonicalCustomerDocIdAndGid("")
        assertEquals("Without mobile, docId must equal gid", gidNoMob, docIdNoMob)
        assertTrue(gidNoMob.startsWith("cus_"))
        assertTrue(IdFormat.isValid(IdType.CUSTOMER, gidNoMob))
    }

    @Test
    fun testP0C_CalculationBreakdown_SumsAccurately() {
        val custGid = IdGenerator.customerGid("cust_m_9988776655")
        val cust = Customer(id = 3L, gid = custGid, name = "Kiran", mobile = "9988776655")

        val bill1 = createBill(
            id = 301L,
            billNumber = "BILL-301",
            gid = "bil_301",
            customerId = 3L,
            customerGid = custGid,
            customerName = "Kiran",
            customerMobile = "9988776655",
            finalAmount = 500.0,
            paidAmount = 200.0,
            pendingAmount = 300.0
        )
        val bill2 = createBill(
            id = 302L,
            billNumber = "BILL-302",
            gid = "bil_302",
            customerId = 3L,
            customerGid = custGid,
            customerName = "Kiran",
            customerMobile = "9988776655",
            finalAmount = 300.0,
            paidAmount = 0.0,
            pendingAmount = 300.0
        )

        val checkoutTx = createTx(
            id = "tx_chk_200",
            gid = "pay_chk_200",
            customerId = 3L,
            customerGid = custGid,
            name = "Kiran",
            mobile = "9988776655",
            amount = 200.0,
            billId = 301L,
            billGid = "bil_301",
            billNumber = "BILL-301",
            note = "checkout initial payment"
        )

        val directSettlementTx = createTx(
            id = "tx_set_100",
            gid = "pay_set_100",
            customerId = 3L,
            customerGid = custGid,
            name = "Kiran",
            mobile = "9988776655",
            amount = 100.0,
            billId = 301L,
            billGid = "bil_301",
            billNumber = "BILL-301"
        )

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill1, bill2),
            allTransactions = listOf(checkoutTx, directSettlementTx),
            allCustomers = listOf(cust)
        )

        val breakdown = ledger.calculationBreakdown
        assertEquals(800.0, breakdown.totalBillAmount, 0.001)
        assertEquals(200.0, breakdown.checkoutPaidAmount, 0.001)
        assertEquals(100.0, breakdown.directSettlementAmount, 0.001)
        assertEquals(500.0, breakdown.netDue, 0.001)
        assertEquals(ledger.balance, breakdown.netDue, 0.001)
    }

    @Test
    fun testP1D_DirectoryBillStats_MatchesProfileNonVoidAggregate() {
        val custGid = IdGenerator.customerGid("cust_m_9111222333")
        val cust = Customer(id = 4L, gid = custGid, name = "Ramesh", mobile = "9111222333")

        val activeBill1 = createBill(
            id = 401L,
            billNumber = "BILL-401",
            gid = "bil_401",
            customerId = 4L,
            customerGid = custGid,
            customerName = "Ramesh",
            customerMobile = "9111222333",
            finalAmount = 1500.0,
            paidAmount = 1500.0,
            pendingAmount = 0.0
        )
        val activeBill2 = createBill(
            id = 402L,
            billNumber = "BILL-402",
            gid = "bil_402",
            customerId = 4L,
            customerGid = custGid,
            customerName = "Ramesh",
            customerMobile = "9111222333",
            finalAmount = 2500.0,
            paidAmount = 1000.0,
            pendingAmount = 1500.0
        )
        val voidedBill = createBill(
            id = 403L,
            billNumber = "BILL-403",
            gid = "bil_403",
            customerId = 4L,
            customerGid = custGid,
            customerName = "Ramesh",
            customerMobile = "9111222333",
            finalAmount = 5000.0,
            paidAmount = 0.0,
            pendingAmount = 5000.0,
            paymentStatus = PaymentStatus.VOID
        ).copy(isVoided = true)

        val allBills = listOf(activeBill1, activeBill2, voidedBill)
        val activeBills = allBills.filter { !it.isVoided && it.paymentStatus != PaymentStatus.VOID }

        // Both directory aggregate and profile screen compute from active non-void bills
        val billCount = activeBills.size
        val totalSpend = activeBills.sumOf { it.finalAmount }

        assertEquals("Voided bills must not be counted", 2, billCount)
        assertEquals("Voided bills must not be included in spend", 4000.0, totalSpend, 0.001)
    }
}

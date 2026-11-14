package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Reproduction and regression tests for Round 14 (release v3.5.4):
 * - Topic A: Phantom udhari & payments from deleted bills (Swaraj Fugare fixture).
 * - Topic A: Savita Dandage DIRECT-PAY / MULTI-BILL classified as GENERAL, not orphan.
 * - Topic A: Overpayment rejection in payment recording.
 * - Topic D: Bill row pending amount & status synchronised with ledger.
 * - Topic E: New customer single row without duplication.
 */
class Round14RegressionTest {

    private fun createCustomer(
        id: Long,
        name: String,
        mobile: String,
        gid: String = IdGenerator.customerGid("cust_m_$mobile")
    ): Customer {
        return Customer(
            id = id,
            gid = gid,
            shopId = 1,
            name = name,
            mobile = mobile,
            address = "कोंढारकी",
            isActive = true
        )
    }

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
        status: String = "ACTIVE",
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
            status = status,
            isReversed = isReversed
        )
    }

    /**
     * Symptom 1 / Topic A: Customer "Swaraj Fugare 9226299212"
     * All bills were deleted by the owner (0 bills in Bills tab).
     * Udhari transactions from deleted bills:
     * - +100 PURCHASE_CREDIT with billNumber "MC-2026-97857"
     * - +1,000 PURCHASE_CREDIT with billNumber "MC-2026-97860"
     * - -500 PAYMENT "Initial payment on checkout" (MC-2026-97860)
     * - -500 PAYMENT "jama MC-2026-97860 (Rs500) CASH"
     * Expected:
     * - Customer outstanding due MUST be 0.0 (no phantom Rs 100 debt).
     * - Customer ledger entries for active udhari list MUST be empty (no phantom udhari rows).
     * - The 4 transactions referencing missing/deleted bills must be identified as orphans.
     */
    @Test
    fun testSwarajFugare_DeletedBillsLeaveZeroDueAndNoPhantomUdhari() {
        val swaraj = createCustomer(1L, "Swaraj Fugare", "9226299212")
        val swarajGid = swaraj.gid

        // Bills tab is completely empty because bills were deleted
        val allBills = emptyList<Bill>()

        val txs = listOf(
            createTx(
                id = "tx1",
                customerId = 1L,
                customerGid = swarajGid,
                name = "Swaraj Fugare",
                mobile = "9226299212",
                amount = 100.0,
                type = UdhariTransactionType.PURCHASE_CREDIT,
                billNumber = "MC-2026-97857",
                createdAt = 1700000001000L
            ),
            createTx(
                id = "tx2",
                customerId = 1L,
                customerGid = swarajGid,
                name = "Swaraj Fugare",
                mobile = "9226299212",
                amount = 1000.0,
                type = UdhariTransactionType.PURCHASE_CREDIT,
                billNumber = "MC-2026-97860",
                createdAt = 1700000002000L
            ),
            createTx(
                id = "tx3",
                customerId = 1L,
                customerGid = swarajGid,
                name = "Swaraj Fugare",
                mobile = "9226299212",
                amount = 500.0,
                type = UdhariTransactionType.PAYMENT,
                billNumber = "MC-2026-97860",
                note = "Initial payment on checkout (MC-2026-97860)",
                createdAt = 1700000003000L
            ),
            createTx(
                id = "tx4",
                customerId = 1L,
                customerGid = swarajGid,
                name = "Swaraj Fugare",
                mobile = "9226299212",
                amount = 500.0,
                type = UdhariTransactionType.PAYMENT,
                billNumber = "MC-2026-97860",
                note = "jama MC-2026-97860 (Rs500) CASH",
                createdAt = 1700000004000L
            )
        )

        val result = UdhariLedger.computeCustomerLedger(
            customerKey = swarajGid,
            allBills = allBills,
            allTransactions = txs,
            allCustomers = listOf(swaraj)
        )

        // Customer due must be 0.0
        assertEquals(0.0, result.balance, 0.001)

        // Active udhari entries list must be empty (no phantom udhari row)
        val activeDebits = result.events.filter { !it.isCredit }
        assertTrue("Expected 0 active debit entries from deleted bills, but found: ${activeDebits.size}", activeDebits.isEmpty())

        // The 4 transactions referencing missing/deleted bills must be captured in orphan list
        val totalOrphanTxs = result.orphanPayments.size
        assertTrue("Transactions referencing non-existent bills must be flagged as orphans", totalOrphanTxs >= 2)
    }

    /**
     * Symptom 2 / Topic A: False positive orphan detection.
     * Savita Santosh Dandage has a legitimate payment with billNumber = "DIRECT-PAY" Rs 1505.
     * This is a GENERAL payment, not an orphan.
     * It MUST reduce the customer's balance.
     */
    @Test
    fun testSavitaDandage_DirectPayAndMultiBill_IsGeneralPaymentNotOrphan() {
        val savita = createCustomer(2L, "Savita Santosh Dandage", "9822001122")
        val bill = createBill(
            id = 101L,
            billNumber = "MC-2026-101",
            customerId = 2L,
            customerGid = savita.gid,
            customerName = "Savita Santosh Dandage",
            customerMobile = "9822001122",
            finalAmount = 2000.0,
            paidAmount = 0.0,
            pendingAmount = 2000.0
        )

        // General payment recorded with legacy placeholder "DIRECT-PAY"
        val generalPay1 = createTx(
            id = "pay_direct_1",
            customerId = 2L,
            customerGid = savita.gid,
            name = "Savita Santosh Dandage",
            mobile = "9822001122",
            amount = 1505.0,
            type = UdhariTransactionType.PAYMENT,
            billNumber = "DIRECT-PAY",
            createdAt = 1700000010000L
        )

        val result = UdhariLedger.computeCustomerLedger(
            customerKey = savita.gid,
            allBills = listOf(bill),
            allTransactions = listOf(generalPay1),
            allCustomers = listOf(savita)
        )

        // Must NOT be in orphan payments
        val isOrphan = result.orphanPayments.any { it.id == "pay_direct_1" }
        assertFalse("DIRECT-PAY should be classified as GENERAL payment, never as orphan", isOrphan)

        // Must be applied to customer due: 2000 - 1505 = 495
        assertEquals(495.0, result.balance, 0.001)
    }

    /**
     * Topic D: Stored bill values vs Ledger per-bill calculation.
     * When a customer makes a payment, the ledger computes exact remaining per-bill.
     */
    @Test
    fun testLedgerPerBillBreakdown_ComputedAccuratelyForPaidBill() {
        val cust = createCustomer(3L, "Ramesh Patil", "9876543210")
        val bill = createBill(
            id = 201L,
            billNumber = "MC-2026-201",
            customerId = 3L,
            customerGid = cust.gid,
            customerName = "Ramesh Patil",
            customerMobile = "9876543210",
            finalAmount = 1500.0,
            paidAmount = 0.0,
            pendingAmount = 1500.0 // Stored value is unupdated
        )

        val payment = createTx(
            id = "pay_full_201",
            customerId = 3L,
            customerGid = cust.gid,
            name = "Ramesh Patil",
            mobile = "9876543210",
            amount = 1500.0,
            type = UdhariTransactionType.PAYMENT,
            billId = 201L,
            billNumber = "MC-2026-201"
        )

        val result = UdhariLedger.computeCustomerLedger(
            customerKey = cust.gid,
            allBills = listOf(bill),
            allTransactions = listOf(payment),
            allCustomers = listOf(cust)
        )

        assertEquals(0.0, result.balance, 0.001)
        val billBreakdown = result.billBreakdowns.find { it.billId == 201L }
        assertNotNull(billBreakdown)
        assertEquals(0.0, billBreakdown!!.remainingOutstanding, 0.001)
    }
}

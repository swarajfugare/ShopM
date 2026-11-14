package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Reproduction tests for Round 15 (release v3.6.0):
 * - B4: Direct manual udhari ("जुनी उधारी") on customer with 0 bills must NEVER be treated as orphan.
 * - B4: Bill present but customer key mismatch -> Needs review, NOT orphan.
 * - B4: Bill confirmed deleted/voided -> Orphan, leaves customer due 0 (Swaraj Fugare fixture).
 * - B1: Orphan payment converted to GENERAL -> applies FIFO, not orphan.
 */
class Round15RegressionTest {

    private fun createCustomer(
        id: Long,
        name: String,
        mobile: String,
        totalBills: Int = 0,
        gid: String = IdGenerator.customerGid("cust_m_$mobile")
    ): Customer {
        return Customer(
            id = id,
            gid = gid,
            shopId = 1,
            name = name,
            mobile = mobile,
            totalBills = totalBills,
            address = "कोंढारकी",
            isActive = true
        )
    }

    private fun createTx(
        id: String,
        customerId: Long,
        customerGid: String,
        name: String,
        mobile: String,
        amount: Double,
        type: UdhariTransactionType,
        billId: Long = 0L,
        billNumber: String = "",
        billGid: String? = null,
        note: String? = null,
        createdAt: Long = System.currentTimeMillis(),
        status: String = "ACTIVE"
    ): UdhariTransaction {
        return UdhariTransaction(
            id = id,
            gid = "udh_$id",
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
            date = "2026-11-14",
            note = note,
            status = status,
            createdAt = createdAt
        )
    }

    /**
     * B4 Bug Reproduction:
     * A customer with 0 bills who has manual old udhari ("MANUAL-XXXX" / "जुनी बाकी")
     * must retain their due balance and NOT have their udhari classified as an orphan.
     */
    @Test
    fun testCustomerWithOnlyManualOldUdhari_RetainedInDueAndNotOrphan() {
        val cust = createCustomer(10L, "Baburao Shinde", "9890123456", totalBills = 0)
        val manualUdhari = createTx(
            id = "manual_tx_10",
            customerId = 10L,
            customerGid = cust.gid,
            name = "Baburao Shinde",
            mobile = "9890123456",
            amount = 1200.0,
            type = UdhariTransactionType.PURCHASE_CREDIT,
            billId = 0L,
            billNumber = "MANUAL-4819",
            note = "जुनी बाकी / Manual Opening Balance"
        )

        val result = UdhariLedger.computeCustomerLedger(
            customerKey = cust.gid,
            allBills = emptyList(),
            allTransactions = listOf(manualUdhari),
            allCustomers = listOf(cust)
        )

        // In v3.5.4, this failed: balance was 0.0 and manualUdhari was put in orphanTransactions!
        assertEquals("Manual old udhari must be part of customer's active due", 1200.0, result.balance, 0.001)
        assertTrue("Manual old udhari must NOT be classified as an orphan", result.orphanTransactions.isEmpty())
    }

    /**
     * B4 Rule:
     * When a bill exists in active bills but customer key had a mismatch or unsynced reference,
     * it must NOT be orphaned.
     */
    @Test
    fun testBillPresentButKeyMismatch_NotOrphaned() {
        val cust = createCustomer(11L, "Tanaji More", "9822334455", totalBills = 1)
        val activeBill = Bill(
            id = 501L,
            gid = "bil_501",
            billNumber = "MC-2026-501",
            transactionUuid = "uuid-501",
            saleType = SaleType.QUICK,
            subtotal = 3000.0,
            finalAmount = 3000.0,
            paidAmount = 0.0,
            pendingAmount = 3000.0,
            paymentStatus = PaymentStatus.PENDING,
            customerId = 11L,
            customerGid = cust.gid,
            customerName = "Tanaji More",
            customerMobile = "9822334455",
            billDate = "2026-11-14 10:00:00"
        )

        val creditTx = createTx(
            id = "tx_credit_501",
            customerId = 11L,
            customerGid = cust.gid,
            name = "Tanaji More",
            mobile = "9822334455",
            amount = 3000.0,
            type = UdhariTransactionType.PURCHASE_CREDIT,
            billId = 501L,
            billNumber = "MC-2026-501",
            billGid = "bil_501"
        )

        val result = UdhariLedger.computeCustomerLedger(
            customerKey = cust.gid,
            allBills = listOf(activeBill),
            allTransactions = listOf(creditTx),
            allCustomers = listOf(cust)
        )

        assertEquals(3000.0, result.balance, 0.001)
        assertTrue("Active bill credit transaction must not be orphaned", result.orphanTransactions.isEmpty())
    }

    /**
     * B4 Rule:
     * Swaraj Fugare case: all 4 events belong to deleted bills.
     * All deleted-bill transactions are isolated as orphans, ending with due 0.
     */
    @Test
    fun testSwarajFugare_DeletedBills_BalanceZeroAndOrphansIsolated() {
        val swaraj = createCustomer(1L, "Swaraj Fugare", "9226299212", totalBills = 0)

        val orphanCredit = createTx(
            id = "tx_credit_deleted",
            customerId = 1L,
            customerGid = swaraj.gid,
            name = "Swaraj Fugare",
            mobile = "9226299212",
            amount = 600.0,
            type = UdhariTransactionType.PURCHASE_CREDIT,
            billId = 999L,
            billNumber = "MC-2026-999"
        )
        val orphanPayment1 = createTx(
            id = "tx_pay1_deleted",
            customerId = 1L,
            customerGid = swaraj.gid,
            name = "Swaraj Fugare",
            mobile = "9226299212",
            amount = 500.0,
            type = UdhariTransactionType.PAYMENT,
            billId = 999L,
            billNumber = "MC-2026-999"
        )

        val result = UdhariLedger.computeCustomerLedger(
            customerKey = swaraj.gid,
            allBills = emptyList(), // Bills were deleted
            allTransactions = listOf(orphanCredit, orphanPayment1),
            allCustomers = listOf(swaraj)
        )

        assertEquals("When all bills are deleted, customer due must be 0", 0.0, result.balance, 0.001)
        assertEquals(2, result.orphanTransactions.size)
    }

    /**
     * B1: Soft-delete orphan transaction (status = TRASH).
     * Once trashed, it must be completely excluded from the ledger and orphan list.
     */
    @Test
    fun testOrphanAction_SoftDelete_ExcludesFromLedgerAndOrphanList() {
        val swaraj = createCustomer(1L, "Swaraj Fugare", "9226299212")
        val trashedOrphanCredit = createTx(
            id = "tx_trashed_credit",
            customerId = 1L,
            customerGid = swaraj.gid,
            name = "Swaraj Fugare",
            mobile = "9226299212",
            amount = 600.0,
            type = UdhariTransactionType.PURCHASE_CREDIT,
            billId = 999L,
            billNumber = "MC-2026-999",
            status = "TRASH"
        )

        val result = UdhariLedger.computeCustomerLedger(
            customerKey = swaraj.gid,
            allBills = emptyList(),
            allTransactions = listOf(trashedOrphanCredit),
            allCustomers = listOf(swaraj)
        )

        assertEquals(0.0, result.balance, 0.001)
        assertTrue("Trashed transaction must not be in orphan list", result.orphanTransactions.isEmpty())
    }

    /**
     * B1: Ignore orphan transaction (status = IGNORED).
     * Once marked IGNORED, it is excluded from ledger and orphan list.
     */
    @Test
    fun testOrphanAction_Ignore_ExcludesFromLedgerAndOrphanList() {
        val swaraj = createCustomer(1L, "Swaraj Fugare", "9226299212")
        val ignoredOrphanCredit = createTx(
            id = "tx_ignored_credit",
            customerId = 1L,
            customerGid = swaraj.gid,
            name = "Swaraj Fugare",
            mobile = "9226299212",
            amount = 600.0,
            type = UdhariTransactionType.PURCHASE_CREDIT,
            billId = 999L,
            billNumber = "MC-2026-999",
            status = "IGNORED"
        )

        val result = UdhariLedger.computeCustomerLedger(
            customerKey = swaraj.gid,
            allBills = emptyList(),
            allTransactions = listOf(ignoredOrphanCredit),
            allCustomers = listOf(swaraj)
        )

        assertEquals(0.0, result.balance, 0.001)
        assertTrue("Ignored transaction must not be in orphan list", result.orphanTransactions.isEmpty())
    }

    /**
     * B1: Convert orphan payment to GENERAL payment (kind = GENERAL_PAYMENT).
     * When converted, it clears its orphan status and applies FIFO against active debt.
     */
    @Test
    fun testOrphanPaymentAction_ConvertToGeneralPayment_AppliesFifoToCustomerDue() {
        val cust = createCustomer(12L, "Dattatraya Shinde", "9850123456")
        val activeBill = Bill(
            id = 601L,
            gid = "bil_601",
            billNumber = "MC-2026-601",
            transactionUuid = "uuid-601",
            saleType = SaleType.QUICK,
            subtotal = 1000.0,
            finalAmount = 1000.0,
            paidAmount = 0.0,
            pendingAmount = 1000.0,
            paymentStatus = PaymentStatus.PENDING,
            customerId = 12L,
            customerGid = cust.gid,
            customerName = "Dattatraya Shinde",
            customerMobile = "9850123456",
            billDate = "2026-11-14 10:00:00"
        )

        val convertedPayment = createTx(
            id = "pay_converted",
            customerId = 12L,
            customerGid = cust.gid,
            name = "Dattatraya Shinde",
            mobile = "9850123456",
            amount = 400.0,
            type = UdhariTransactionType.PAYMENT,
            billId = 0L,
            billNumber = "GENERAL"
        ).copy(kind = "GENERAL_PAYMENT")

        val result = UdhariLedger.computeCustomerLedger(
            customerKey = cust.gid,
            allBills = listOf(activeBill),
            allTransactions = listOf(convertedPayment),
            allCustomers = listOf(cust)
        )

        assertEquals("Converted general payment must reduce net due: 1000 - 400 = 600", 600.0, result.balance, 0.001)
        assertTrue("Converted payment must not be in orphan list", result.orphanPayments.isEmpty())
    }
}

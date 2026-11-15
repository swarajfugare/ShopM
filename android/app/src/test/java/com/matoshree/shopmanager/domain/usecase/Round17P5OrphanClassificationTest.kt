package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * P5: Orphan classification tests.
 * Asserts:
 * 1. Transactions referencing bills not in memory are NOT prematurely orphaned unless confirmed deleted.
 * 2. Unconfirmed missing bills remain in active due with needsReview = true.
 * 3. Confirmed deleted / voided bills are classified as orphans and excluded from due.
 */
class Round17P5OrphanClassificationTest {

    private fun createCustomer(id: Long, name: String, mobile: String): Customer {
        return Customer(
            id = id,
            gid = "cust_m_$mobile",
            name = name,
            mobile = mobile,
            isActive = true
        )
    }

    private fun createPaymentTx(
        id: String,
        customer: Customer,
        amount: Double,
        billNumber: String,
        billGid: String? = null
    ): UdhariTransaction {
        return UdhariTransaction(
            id = id,
            gid = id,
            customerId = customer.id,
            customerGid = customer.gid,
            customerName = customer.name,
            customerMobile = customer.mobile,
            billId = 0L,
            billGid = billGid,
            billNumber = billNumber,
            amount = amount,
            type = UdhariTransactionType.PAYMENT,
            kind = "SETTLEMENT",
            transactionUuid = "uuid-$id",
            date = "2026-11-15"
        )
    }

    @Test
    fun testUnconfirmedMissingBill_NotOrphaned_KeptInDue_WithNeedsReview() {
        val cust = createCustomer(1L, "Vijay", "9876543210")
        // Payment for MC-500 which is not in allBills and not confirmed deleted
        val tx = createPaymentTx("pay_1", cust, 300.0, "MC-500")

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = cust.gid,
            allBills = emptyList(),
            allTransactions = listOf(tx),
            allCustomers = listOf(cust),
            confirmedDeletedBillNumbers = emptySet(),
            isBillConfirmedDeleted = { _, _ -> false }
        )

        // Must NOT be placed in orphans
        assertTrue("Unconfirmed missing bill payment must NOT be in orphanPayments", ledger.orphanPayments.isEmpty())
        assertTrue("Unconfirmed missing bill payment must NOT be in orphanTransactions", ledger.orphanTransactions.isEmpty())

        // Must be counted in payments (reducing balance / creating advance)
        assertEquals("Total paid must reflect the 300 payment", 300.0, ledger.totalPaid, 0.001)

        // Flagged for review
        assertTrue("Ledger must be flagged as needsReview", ledger.needsReview)
    }

    @Test
    fun testConfirmedDeletedBill_ClassifiedAsOrphan() {
        val cust = createCustomer(2L, "Sanjay", "9876543211")
        // Payment for MC-500 which is confirmed deleted
        val tx = createPaymentTx("pay_2", cust, 300.0, "MC-500")

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = cust.gid,
            allBills = emptyList(),
            allTransactions = listOf(tx),
            allCustomers = listOf(cust),
            confirmedDeletedBillNumbers = setOf("MC-500")
        )

        // MUST be placed in orphans
        assertEquals("Confirmed deleted bill payment must be in orphanPayments", 1, ledger.orphanPayments.size)
        assertEquals("Orphan amount must be 300", 300.0, ledger.calculationBreakdown.orphanAmount, 0.001)
        assertEquals("Total paid must be 0", 0.0, ledger.totalPaid, 0.001)
    }
}

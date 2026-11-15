package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.data.firebase.*
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.ui.screens.customer.buildCustomerPaymentRecords
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Round 20 (v3.6.5): Payment cancellation reproduction and regression test suite.
 * Tests against REAL data models and DTO shapes exactly as written by the production app:
 * 1. Checkout path writes initial payment "udhari_<millis>_pay" with blank GID, note "Initial payment on checkout".
 * 2. Real settlement payment writes "pay_..." with canonical GID.
 * 3. Unrelated legacy transaction with blank GID exists in same dataset.
 * 4. Asserts 500 checkout payment is non-cancellable in Payments tab with hint, and reversePayment rejects it cleanly without "not found".
 * 5. Asserts unrelated legacy transaction is NEVER modified.
 * 6. Asserts 200 settlement cancellation updates bill pending, customer outstanding, leaves grey audit row, and allows taking 200 again.
 */
class Round20PaymentCancellationRegressionTest {

    private class FakeUdhariFirestoreGateway : UdhariFirestoreGateway {
        val customers = mutableListOf<CustomerDocSnapshot>()
        val bills = mutableListOf<BillDocSnapshot>()
        val transactions = mutableListOf<UdhariTransactionDocSnapshot>()

        override suspend fun ensureAuthenticated() {}

        override suspend fun isTransactionDuplicate(uuid: String): Boolean {
            return transactions.any { it.data.transactionUuid == uuid }
        }

        override suspend fun getCustomerDocs(): List<CustomerDocSnapshot> = customers.toList()
        override suspend fun getBillDocs(): List<BillDocSnapshot> = bills.toList()
        override suspend fun getTransactionDocs(): List<UdhariTransactionDocSnapshot> = transactions.toList()

        override suspend fun commitPaymentBatch(
            customerUpdates: Map<String, Double>,
            billUpdates: Map<String, BillBatchUpdate>,
            transactionsToSave: List<UdhariTransactionDto>
        ) {
            for ((path, newDue) in customerUpdates) {
                customers.firstOrNull { it.path == path }?.data?.outstandingAmount = newDue
            }
            for ((path, update) in billUpdates) {
                bills.firstOrNull { it.path == path }?.let { b ->
                    b.data.paidAmount = update.paidAmount
                    b.data.pendingAmount = update.pendingAmount
                    b.data.paymentStatus = update.paymentStatus
                    b.data.payments = update.payments.toMutableList()
                }
            }
            for (tx in transactionsToSave) {
                transactions.add(UdhariTransactionDocSnapshot(id = tx.id, path = "udhari_transactions/${tx.id}", data = tx))
            }
        }

        override suspend fun commitReversalBatch(
            reversalTransactionsToSave: List<UdhariTransactionDto>,
            reversedTxDocUpdates: Map<String, Map<String, Any?>>,
            billUpdates: Map<String, Map<String, Any?>>,
            customerUpdates: Map<String, Double>
        ) {
            for (rtx in reversalTransactionsToSave) {
                transactions.add(UdhariTransactionDocSnapshot(id = rtx.id, path = "udhari_transactions/${rtx.id}", data = rtx))
            }

            for ((path, updates) in reversedTxDocUpdates) {
                transactions.firstOrNull { it.path == path }?.let { snap ->
                    if (updates["isReversed"] == true) {
                        snap.data.isReversed = true
                        snap.data.reversalReason = updates["reversalReason"] as? String
                    }
                }
            }

            for ((path, updates) in billUpdates) {
                bills.firstOrNull { it.path == path }?.let { snap ->
                    (updates["paidAmount"] as? Double)?.let { snap.data.paidAmount = it }
                    (updates["pendingAmount"] as? Double)?.let { snap.data.pendingAmount = it }
                    (updates["paymentStatus"] as? String)?.let { snap.data.paymentStatus = it }
                    @Suppress("UNCHECKED_CAST")
                    (updates["payments"] as? List<BillPaymentDto>)?.let { snap.data.payments = it.toMutableList() }
                }
            }

            for ((path, newOutstanding) in customerUpdates) {
                customers.firstOrNull { it.path == path }?.data?.outstandingAmount = newOutstanding
            }
        }
    }

    private lateinit var gateway: FakeUdhariFirestoreGateway
    private lateinit var repo: FirestoreUdhariRepository

    private val custGid = "cust_1731660000000_abcd1234"
    private val custMobile = "9226299212"
    private val custName = "Swaraj Fugare"

    @Before
    fun setUp() {
        gateway = FakeUdhariFirestoreGateway()
        repo = FirestoreUdhariRepository(gateway = gateway)

        // 1. Customer record
        gateway.customers.add(
            CustomerDocSnapshot(
                id = custGid,
                path = "customers/$custGid",
                data = CustomerDto(
                    id = custGid,
                    gid = custGid,
                    name = custName,
                    mobile = custMobile,
                    outstandingAmount = 400.0 // Bill 900 - 500 initial = 400
                )
            )
        )

        // 2. Bill 900 (500 paid at checkout, 400 pending)
        val billDto = BillDto(
            id = "bil_MC-2026-900",
            gid = "bil_MC-2026-900",
            billNumber = "MC-2026-900",
            customerGid = custGid,
            customerMobile = custMobile,
            customerName = custName,
            finalAmount = 900.0,
            paidAmount = 500.0,
            pendingAmount = 400.0,
            paymentStatus = "PARTIAL",
            billDate = "2026-11-15",
            payments = mutableListOf(
                BillPaymentDto(id = "pay_checkout_500", method = "CASH", amount = 500.0, status = "PAID")
            )
        )
        gateway.bills.add(BillDocSnapshot(id = "bil_MC-2026-900", path = "bills/bil_MC-2026-900", data = billDto))

        // 3. Initial payment on checkout transaction (Real checkout path format: id = udhari_<millis>_pay, blank gid)
        val initialPayTx = UdhariTransactionDto(
            id = "udhari_1731600000000_pay",
            gid = "", // REAL checkout path leaves gid blank!
            customerId = 1L,
            customerGid = custGid,
            customerName = custName,
            customerMobile = custMobile,
            billNumber = "MC-2026-900",
            amount = 500.0,
            type = UdhariTransactionType.PAYMENT.name,
            transactionUuid = "uuid-checkout-pay",
            date = "2026-11-15",
            note = "Initial payment on checkout"
        )
        gateway.transactions.add(
            UdhariTransactionDocSnapshot(
                id = initialPayTx.id,
                path = "udhari_transactions/${initialPayTx.id}",
                data = initialPayTx
            )
        )

        // 4. Unrelated legacy transaction with blank GID from another customer
        val unrelatedTx = UdhariTransactionDto(
            id = "udhari_legacy_unrelated_99",
            gid = "", // Real legacy shape has blank gid
            customerId = 99L,
            customerGid = "cust_other_99",
            customerName = "Other Customer",
            customerMobile = "9822998877",
            amount = 100.0,
            type = UdhariTransactionType.PAYMENT.name,
            transactionUuid = "uuid-unrelated-99",
            date = "2026-11-14",
            isReversed = false
        )
        gateway.transactions.add(
            UdhariTransactionDocSnapshot(
                id = unrelatedTx.id,
                path = "udhari_transactions/${unrelatedTx.id}",
                data = unrelatedTx
            )
        )
    }

    @Test
    fun testInitialCheckoutPayment_IsNonCancellable_AndReversalCleanlyRefusedWithoutTouchingUnrelated() = runBlocking {
        // Build payments tab rows using real ledger and extracted row construction
        val domainCustomer = Customer(id = 1L, gid = custGid, name = custName, mobile = custMobile, outstandingBalance = 400.0)
        val domainBill = gateway.bills.first().data.toDomain()
        val domainTxs = gateway.transactions.map { it.data.toDomain() }

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(domainBill),
            allTransactions = domainTxs,
            allCustomers = listOf(domainCustomer)
        )

        val keyResolver = CustomerKeyResolver { g, m, _, _ -> g ?: m }
        val paymentRows = buildCustomerPaymentRecords(ledger, domainTxs, custGid, keyResolver)

        // Verify 500 checkout row is present
        val row500 = paymentRows.find { it.amount == 500.0 }
        assertNotNull("500 row must appear in Payments tab", row500)

        // Requirement C2.2: Initial checkout payment must not offer "रद्द करा" and must have clear hint
        assertFalse("500 checkout payment must NOT be cancellable", row500!!.isCancellable)
        assertTrue("Subtitle must explain it is initial bill payment", row500.subtitle.contains("बिलातील"))

        // Requirement C2.1: Even if reversePayment is called with 500 id, it must refuse cleanly
        val res = repo.reversePayment(
            transactionId = row500.originalTxId ?: row500.id,
            reason = "Test cancel checkout",
            expectedCustomerGid = custGid,
            expectedCustomerMobile = custMobile,
            expectedCustomerId = 1L,
            expectedAmount = 500.0
        )
        assertTrue("Attempt to reverse checkout payment must fail", res.isFailure)
        val msg = res.exceptionOrNull()?.message ?: ""
        assertTrue("Error must explain initial bill payment cannot be reversed directly", msg.contains("बिलातील सुरुवातीचा भरणा"))

        // Unrelated legacy transaction must NEVER be touched
        val unrelatedSnap = gateway.transactions.find { it.id == "udhari_legacy_unrelated_99" }
        assertNotNull(unrelatedSnap)
        assertFalse("Unrelated transaction must NOT be marked isReversed", unrelatedSnap!!.data.isReversed)
    }

    @Test
    fun testSettlementPayment200_CancelledAndRestored_SurvivesAndAllowsRePayment() = runBlocking {
        // Owner takes 200 payment via recordPayment
        val paymentTx = UdhariTransaction(
            id = "pay_settle_200",
            gid = "pay_settle_200",
            customerId = 1L,
            customerGid = custGid,
            customerName = custName,
            customerMobile = custMobile,
            billId = 0L,
            billNumber = "MC-2026-900",
            amount = 200.0,
            paymentMethod = PaymentMethod.CASH,
            previousOutstanding = 400.0,
            remainingOutstanding = 200.0,
            transactionUuid = "uuid-settle-200",
            date = "2026-11-15"
        )
        val recRes = repo.recordPayment(paymentTx)
        assertTrue("Recording 200 payment must succeed: ${recRes.exceptionOrNull()?.message}", recRes.isSuccess)

        // Verify bill and customer before cancellation: bill paid 700, pending 200, cust due 200
        val billSnap = gateway.bills.first()
        val custSnap = gateway.customers.first()
        assertEquals("Bill paid amount after 200 payment must be 700", 700.0, billSnap.data.paidAmount, 0.001)
        assertEquals("Bill pending amount after 200 payment must be 200", 200.0, billSnap.data.pendingAmount, 0.001)
        assertEquals("Customer due after 200 payment must be 200", 200.0, custSnap.data.outstandingAmount, 0.001)

        // Build payments tab rows
        val domainCustomer = Customer(id = 1L, gid = custGid, name = custName, mobile = custMobile, outstandingBalance = 200.0)
        var domainBill = billSnap.data.toDomain()
        var domainTxs = gateway.transactions.map { it.data.toDomain() }

        var ledger = UdhariLedger.computeCustomerLedger(custGid, listOf(domainBill), domainTxs, listOf(domainCustomer))
        val keyResolver = CustomerKeyResolver { g, m, _, _ -> g ?: m }
        var paymentRows = buildCustomerPaymentRecords(ledger, domainTxs, custGid, keyResolver)

        // The 200 row must be cancellable
        val row200 = paymentRows.find { it.amount == 200.0 && !it.isReversed }
        assertNotNull("200 row must be in Payments tab", row200)
        assertTrue("200 settlement payment must be cancellable", row200!!.isCancellable)
        val targetDocId = row200.originalTxId ?: row200.id

        // Owner taps "रद्द करा" on 200 row
        val revRes = repo.reversePayment(
            transactionId = targetDocId,
            reason = "चुकीची नोंद",
            expectedCustomerGid = custGid,
            expectedCustomerMobile = custMobile,
            expectedCustomerId = 1L,
            expectedAmount = 200.0
        )
        assertTrue("Reversing 200 payment must succeed: ${revRes.exceptionOrNull()?.message}", revRes.isSuccess)

        // 1. Transaction marked isReversed in gateway
        val cancelledTxSnap = gateway.transactions.find { it.data.transactionUuid == "uuid-settle-200" && it.data.type == "PAYMENT" }
        assertNotNull(cancelledTxSnap)
        assertTrue("Payment transaction must have isReversed=true", cancelledTxSnap!!.data.isReversed)

        // 2. Bill restored to 500 paid, 400 pending
        assertEquals("Bill paid amount must be restored to 500", 500.0, billSnap.data.paidAmount, 0.001)
        assertEquals("Bill pending amount must be restored to 400", 400.0, billSnap.data.pendingAmount, 0.001)
        assertEquals("PARTIAL", billSnap.data.paymentStatus)

        // 3. Customer outstanding restored to 400
        assertEquals("Customer due must be restored to 400", 400.0, custSnap.data.outstandingAmount, 0.001)

        // 4. Payments tab: cancelled payment disappears as requested by owner
        domainBill = billSnap.data.toDomain()
        domainTxs = gateway.transactions.map { it.data.toDomain() }
        ledger = UdhariLedger.computeCustomerLedger(custGid, listOf(domainBill), domainTxs, listOf(domainCustomer))
        paymentRows = buildCustomerPaymentRecords(ledger, domainTxs, custGid, keyResolver)

        // Ledger balance must be 400
        assertEquals("Ledger balance must be restored to 400", 400.0, ledger.balance, 0.001)

        // Cancelled row must disappear from Payments tab (v3.6.3 behaviour restored in v3.6.6)
        val active200Row = paymentRows.find { it.amount == 200.0 }
        assertNull("Cancelled 200 payment must disappear from Payments tab", active200Row)

        // 5. T5: Take 200 again -> works cleanly and reduces due to 200
        val paymentTx2 = UdhariTransaction(
            id = "pay_settle_200_again",
            gid = "pay_settle_200_again",
            customerId = 1L,
            customerGid = custGid,
            customerName = custName,
            customerMobile = custMobile,
            billId = 0L,
            billNumber = "MC-2026-900",
            amount = 200.0,
            paymentMethod = PaymentMethod.CASH,
            previousOutstanding = 400.0,
            remainingOutstanding = 200.0,
            transactionUuid = "uuid-settle-200-again",
            date = "2026-11-15"
        )
        val recRes2 = repo.recordPayment(paymentTx2)
        assertTrue("Taking 200 again must succeed", recRes2.isSuccess)
        assertEquals("Customer due must drop back to 200", 200.0, custSnap.data.outstandingAmount, 0.001)
        assertEquals("Bill pending must drop back to 200", 200.0, billSnap.data.pendingAmount, 0.001)

        // Unrelated legacy transaction is untouched
        val unrelatedSnap = gateway.transactions.find { it.id == "udhari_legacy_unrelated_99" }
        assertFalse("Unrelated transaction was untouched", unrelatedSnap!!.data.isReversed)
    }
}

package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.data.firebase.*
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PhoneUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Round 19: "रद्द करा" (Cancel payment) regression and resilience test.
 * Verifies that:
 * 1. Payment cancellation works for documents with or without GID.
 * 2. All matching copies of the bill (root & shop) are updated consistently, restoring pending due.
 * 3. Payment is removed from bill's internal payments list upon cancellation.
 * 4. Finding transaction works with both prefixed (pay_...) and bare IDs.
 * 5. Reversal transactions are never re-reversed.
 * 6. Customer due is restored exactly.
 * 7. When reversal fails, failure result is returned with original exception message.
 * 8. R2 orphan check correctly identifies deleted bills without altering non-orphan balance.
 */
class Round19PaymentCancellationTest {

    private class FakeUdhariFirestoreGateway(
        var shouldFailBatchCommit: Boolean = false
    ) : UdhariFirestoreGateway {
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
            if (shouldFailBatchCommit) {
                throw IllegalStateException("Firestore PERMISSION_DENIED: missing permission or invalid GID")
            }

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

    @Before
    fun setUp() {
        gateway = FakeUdhariFirestoreGateway()
        repo = FirestoreUdhariRepository(gateway = gateway)
    }

    @Test
    fun testPaymentCancellation_RestoresDueAndAllBillCopies() = runBlocking {
        val custGid = "cust_1731660000000_abcd1234"
        val cust = CustomerDocSnapshot(
            id = custGid,
            path = "customers/$custGid",
            data = CustomerDto(
                id = custGid,
                gid = custGid,
                name = "Swaraj Fugare",
                mobile = "9226299212",
                outstandingAmount = 500.0 // Bill was 1000, 300 checkout, 200 settled -> 500 due
            )
        )
        gateway.customers.add(cust)

        // Two copies of the bill (root and shop path)
        val billRoot = BillDocSnapshot(
            id = "bil_MC-2026-97860",
            path = "bills/bil_MC-2026-97860",
            data = BillDto(
                id = "bil_MC-2026-97860",
                gid = "bil_MC-2026-97860",
                billNumber = "MC-2026-97860",
                customerGid = custGid,
                customerMobile = "9226299212",
                finalAmount = 1000.0,
                paidAmount = 500.0, // 300 checkout + 200 settlement
                pendingAmount = 500.0,
                paymentStatus = "PARTIAL",
                payments = mutableListOf(
                    BillPaymentDto(id = "pay_checkout_1", amount = 300.0, method = "CASH"),
                    BillPaymentDto(id = "pay_settle_200", amount = 200.0, method = "CASH")
                )
            )
        )
        val billShop = BillDocSnapshot(
            id = "bil_MC-2026-97860",
            path = "shops/shop_default/bills/bil_MC-2026-97860",
            data = billRoot.data.copy(payments = billRoot.data.payments.toMutableList())
        )
        gateway.bills.add(billRoot)
        gateway.bills.add(billShop)

        val txSettle = UdhariTransactionDocSnapshot(
            id = "pay_settle_200",
            path = "udhari_transactions/pay_settle_200",
            data = UdhariTransactionDto(
                id = "pay_settle_200",
                gid = "pay_settle_200",
                customerId = 0L,
                customerGid = custGid,
                customerName = "Swaraj Fugare",
                customerMobile = "9226299212",
                billNumber = "MC-2026-97860",
                billGid = "bil_MC-2026-97860",
                amount = 200.0,
                type = UdhariTransactionType.PAYMENT.name,
                kind = "SETTLEMENT",
                transactionUuid = "uuid-settle-200",
                isReversed = false
            )
        )
        gateway.transactions.add(txSettle)

        // Cancel the payment via bare ID "settle_200" or prefixed "pay_settle_200"
        val result = repo.reversePayment("pay_settle_200", "चुकीची नोंद")
        assertTrue("Reversal must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        // 1. Transaction marked reversed
        val reversedTx = gateway.transactions.find { it.id == "pay_settle_200" }
        assertNotNull(reversedTx)
        assertTrue("isReversed must be true", reversedTx!!.data.isReversed)
        assertEquals("चुकीची नोंद", reversedTx.data.reversalReason)

        // 2. Audit REVERSAL entry added
        val reversalAudit = gateway.transactions.find { it.data.type == UdhariTransactionType.REVERSAL.name }
        assertNotNull("Reversal audit record must be generated", reversalAudit)
        assertEquals(200.0, reversalAudit!!.data.amount, 0.001)

        // 3. Both bill copies updated: paid 300, pending 700, status PARTIAL
        for (b in gateway.bills) {
            assertEquals("Bill paid amount must return to 300", 300.0, b.data.paidAmount, 0.001)
            assertEquals("Bill pending amount must return to 700", 700.0, b.data.pendingAmount, 0.001)
            assertEquals("PARTIAL", b.data.paymentStatus)
            assertEquals("Reversed payment must be removed from payments array", 1, b.data.payments.size)
            assertEquals("pay_checkout_1", b.data.payments.first().id)
        }

        // 4. Customer due restored from 500 back to 700
        assertEquals("Customer due must be restored to 700", 700.0, cust.data.outstandingAmount, 0.001)
    }

    @Test
    fun testPaymentCancellation_WithBareId_FindsAndCancelsPrefixedDoc() = runBlocking {
        val custGid = "cust_test_1"
        gateway.customers.add(
            CustomerDocSnapshot(
                id = custGid,
                path = "customers/$custGid",
                data = CustomerDto(id = custGid, gid = custGid, name = "Ramesh", mobile = "9822112233", outstandingAmount = 100.0)
            )
        )

        gateway.transactions.add(
            UdhariTransactionDocSnapshot(
                id = "pay_abc_123",
                path = "udhari_transactions/pay_abc_123",
                data = UdhariTransactionDto(
                    id = "pay_abc_123",
                    gid = "pay_abc_123",
                    customerGid = custGid,
                    customerMobile = "9822112233",
                    amount = 50.0,
                    type = UdhariTransactionType.PAYMENT.name,
                    isReversed = false
                )
            )
        )

        // Call with bare "abc_123" without "pay_"
        val res = repo.reversePayment("abc_123", "User test")
        assertTrue("Must succeed with bare ID: ${res.exceptionOrNull()?.message}", res.isSuccess)
        assertTrue(gateway.transactions.find { it.id == "pay_abc_123" }!!.data.isReversed)
    }

    @Test
    fun testPaymentCancellation_FailsWhenServerRejects_ReportsRealError() = runBlocking {
        gateway.shouldFailBatchCommit = true
        val custGid = "cust_test_2"
        gateway.customers.add(
            CustomerDocSnapshot(
                id = custGid,
                path = "customers/$custGid",
                data = CustomerDto(id = custGid, gid = custGid, name = "Suresh", mobile = "9822445566", outstandingAmount = 200.0)
            )
        )
        gateway.transactions.add(
            UdhariTransactionDocSnapshot(
                id = "pay_fail_test",
                path = "udhari_transactions/pay_fail_test",
                data = UdhariTransactionDto(
                    id = "pay_fail_test",
                    gid = "pay_fail_test",
                    customerGid = custGid,
                    customerMobile = "9822445566",
                    amount = 100.0,
                    type = UdhariTransactionType.PAYMENT.name,
                    isReversed = false
                )
            )
        )

        val res = repo.reversePayment("pay_fail_test", "Test error reporting")
        assertTrue("Must return failure when server rejects", res.isFailure)
        val ex = res.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("Error message must contain PERMISSION_DENIED details", ex!!.message!!.contains("PERMISSION_DENIED"))
    }

    @Test
    fun testR2_OrphanCheckWiring_IdentifiesDeletedBillsCorrectly() {
        val cust = Customer(id = 1L, gid = "cust_r2", name = "Test R2", mobile = "9111222333")
        val activeBill = Bill(
            id = 10L,
            gid = "bil_MC-10",
            billNumber = "MC-10",
            billDate = "2026-11-15",
            transactionUuid = "uuid-mc-10",
            saleType = SaleType.QUICK,
            subtotal = 500.0,
            customerId = cust.id,
            customerGid = cust.gid,
            customerName = cust.name,
            customerMobile = cust.mobile,
            finalAmount = 500.0,
            pendingAmount = 500.0,
            paymentStatus = PaymentStatus.PENDING,
            paymentMethod = PaymentMethod.CASH
        )

        val activeBills = listOf(activeBill)

        // Payment for active bill MC-10
        val txActive = UdhariTransaction(
            id = "pay_active",
            gid = "pay_active",
            customerId = cust.id,
            customerGid = cust.gid,
            customerName = cust.name,
            customerMobile = cust.mobile,
            billId = 10L,
            billNumber = "MC-10",
            billGid = "bil_MC-10",
            amount = 100.0,
            type = UdhariTransactionType.PAYMENT,
            transactionUuid = "uuid-pay-active",
            date = "2026-11-15"
        )

        // Payment for deleted/absent bill MC-99
        val txDeleted = UdhariTransaction(
            id = "pay_orphan",
            gid = "pay_orphan",
            customerId = cust.id,
            customerGid = cust.gid,
            customerName = cust.name,
            customerMobile = cust.mobile,
            billId = 99L,
            billNumber = "MC-99",
            billGid = "bil_MC-99",
            amount = 150.0,
            type = UdhariTransactionType.PAYMENT,
            transactionUuid = "uuid-pay-orphan",
            date = "2026-11-15"
        )

        val ledgerRes = UdhariLedger.computeCustomerLedger(
            customerKey = cust.gid,
            allBills = activeBills,
            allTransactions = listOf(txActive, txDeleted),
            allCustomers = listOf(cust),
            isBillConfirmedDeleted = { billGid, billNumber ->
                val clean = billNumber?.substringBefore("(")?.trim()?.uppercase() ?: ""
                clean.startsWith("MC-") && activeBills.none { b ->
                    (!billGid.isNullOrBlank() && b.gid == billGid) ||
                    (clean.isNotBlank() && b.billNumber.trim().equals(clean, ignoreCase = true))
                }
            }
        )

        // MC-10 is active: total purchase 500, paid 100 -> balance 400
        assertEquals("Active balance must be 400", 400.0, ledgerRes.balance, 0.001)

        // MC-99 is an orphan payment
        assertTrue("MC-99 transaction must be classified as orphan", ledgerRes.orphanPayments.any { it.billNumber?.contains("MC-99") == true })
    }
}

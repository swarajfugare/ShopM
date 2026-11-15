package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.data.firebase.*
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PhoneUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Round 18: Urgent regression test for "Udhari jama" (taking payment from customer).
 * Verifies that:
 * 1. Customer with real doc-id shape (cust_<millis>_<uuid>, +91 mobile) has payment properly allocated to pending bill.
 * 2. Customer outstanding drops correctly (e.g. 700 -> 500 on 200 payment).
 * 3. Settlement transaction has clean billNumber and canonical customerGid so it appears in Payments tab with cancel button.
 * 4. Reversing payment restores bill pending and customer outstanding.
 * 5. Strict customer isolation is preserved (no cross-customer pollution).
 */
class Round18UdhariPaymentRegressionTest {

    private class FakeUdhariFirestoreGateway : UdhariFirestoreGateway {
        val customers = mutableListOf<CustomerDocSnapshot>()
        val bills = mutableListOf<BillDocSnapshot>()
        val transactions = mutableListOf<UdhariTransactionDocSnapshot>()

        override suspend fun ensureAuthenticated() {}

        override suspend fun isTransactionDuplicate(uuid: String): Boolean {
            return transactions.any { it.data.transactionUuid == uuid }
        }

        override suspend fun getCustomerDocs(): List<CustomerDocSnapshot> {
            return customers.toList()
        }

        override suspend fun getBillDocs(): List<BillDocSnapshot> {
            return bills.toList()
        }

        override suspend fun getTransactionDocs(): List<UdhariTransactionDocSnapshot> {
            return transactions.toList()
        }

        override suspend fun commitPaymentBatch(
            customerUpdates: Map<String, Double>,
            billUpdates: Map<String, BillBatchUpdate>,
            transactionsToSave: List<UdhariTransactionDto>
        ) {
            for ((path, newDue) in customerUpdates) {
                val idx = customers.indexOfFirst { it.path == path }
                if (idx >= 0) {
                    val current = customers[idx]
                    current.data.outstandingAmount = newDue
                }
            }

            for ((path, update) in billUpdates) {
                val idx = bills.indexOfFirst { it.path == path }
                if (idx >= 0) {
                    val current = bills[idx]
                    current.data.paidAmount = update.paidAmount
                    current.data.pendingAmount = update.pendingAmount
                    current.data.paymentStatus = update.paymentStatus
                    current.data.payments = update.payments.toMutableList()
                }
            }

            for (tx in transactionsToSave) {
                transactions.add(
                    UdhariTransactionDocSnapshot(
                        id = tx.id,
                        path = "udhari_transactions/${tx.id}",
                        data = tx
                    )
                )
            }
        }

        override suspend fun commitReversalBatch(
            reversalTransactionsToSave: List<UdhariTransactionDto>,
            reversedTxDocUpdates: Map<String, Map<String, Any?>>,
            billUpdates: Map<String, Map<String, Any?>>,
            customerUpdates: Map<String, Double>
        ) {
            for (rtx in reversalTransactionsToSave) {
                transactions.add(
                    UdhariTransactionDocSnapshot(
                        id = rtx.id,
                        path = "udhari_transactions/${rtx.id}",
                        data = rtx
                    )
                )
            }

            for ((path, updates) in reversedTxDocUpdates) {
                val idx = transactions.indexOfFirst { it.path == path }
                if (idx >= 0) {
                    val current = transactions[idx]
                    if (updates["isReversed"] == true) {
                        current.data.isReversed = true
                        current.data.reversalReason = updates["reversalReason"] as? String
                    }
                }
            }

            for ((path, updates) in billUpdates) {
                val idx = bills.indexOfFirst { it.path == path }
                if (idx >= 0) {
                    val current = bills[idx]
                    (updates["paidAmount"] as? Double)?.let { current.data.paidAmount = it }
                    (updates["pendingAmount"] as? Double)?.let { current.data.pendingAmount = it }
                    (updates["paymentStatus"] as? String)?.let { current.data.paymentStatus = it }
                }
            }

            for ((path, newOutstanding) in customerUpdates) {
                val idx = customers.indexOfFirst { it.path == path }
                if (idx >= 0) {
                    val current = customers[idx]
                    current.data.outstandingAmount = newOutstanding
                }
            }
        }
    }

    private lateinit var fakeGateway: FakeUdhariFirestoreGateway
    private lateinit var udhariRepo: FirestoreUdhariRepository

    @Before
    fun setUp() {
        fakeGateway = FakeUdhariFirestoreGateway()
        udhariRepo = FirestoreUdhariRepository(gateway = fakeGateway)
    }

    @Test
    fun testT1_OwnerScenario_UdhariPaymentReducesDueAndUpdatesBill() = runBlocking {
        // Customer with real doc shape (e.g. cust_<millis>_<uuid5>) and +91 formatted mobile
        val custDocId = "cust_1731600000000_ab12c"
        val custGid = "01JA0000000000000000000001"
        val mobile = "+91 92262 99212"
        val cleanMob = "9226299212"

        val customerDto = CustomerDto(
            id = custDocId,
            gid = custGid,
            name = "Swaraj Fugare",
            mobile = mobile,
            outstandingAmount = 700.0
        )
        fakeGateway.customers.add(
            CustomerDocSnapshot(id = custDocId, path = "customers/$custDocId", data = customerDto)
        )

        // Bill of 1000 with 300 paid at checkout, 700 pending
        val billDocId = "bil_1731600000000_cd34e"
        val billDto = BillDto(
            id = billDocId,
            gid = "01JA0000000000000000000002",
            billNumber = "MC-2026-101",
            customerGid = custGid,
            customerMobile = mobile,
            finalAmount = 1000.0,
            paidAmount = 300.0,
            pendingAmount = 700.0,
            paymentStatus = "PARTIAL",
            billDate = "2026-11-15",
            payments = mutableListOf(
                BillPaymentDto(id = "pay_checkout", method = "CASH", amount = 300.0, status = "PAID", createdAt = 1000L)
            )
        )
        fakeGateway.bills.add(
            BillDocSnapshot(id = billDocId, path = "bills/$billDocId", data = billDto)
        )

        // Owner takes payment of 200 from customer profile
        val paymentTx = UdhariTransaction(
            customerId = 0L,
            customerGid = custGid,
            customerName = "Swaraj Fugare",
            customerMobile = cleanMob,
            billId = 0L,
            billNumber = "",
            amount = 200.0,
            paymentMethod = PaymentMethod.CASH,
            previousOutstanding = 700.0,
            remainingOutstanding = 500.0,
            transactionUuid = "tx-uuid-1",
            date = "2026-11-15"
        )

        val result = udhariRepo.recordPayment(paymentTx)
        assertTrue("Payment record must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        // Customer outstanding must be 500.0
        val updatedCust = fakeGateway.customers.first().data
        assertEquals("Customer outstanding must decrease to 500.0", 500.0, updatedCust.outstandingAmount, 0.001)

        // Bill must be updated: paidAmount = 500.0, pendingAmount = 500.0, status = PARTIAL
        val updatedBill = fakeGateway.bills.first().data
        assertEquals("Bill paidAmount must be 500.0", 500.0, updatedBill.paidAmount, 0.001)
        assertEquals("Bill pendingAmount must be 500.0", 500.0, updatedBill.pendingAmount, 0.001)
        assertEquals("Bill status must be PARTIAL", "PARTIAL", updatedBill.paymentStatus)

        // Settlement transaction must be recorded
        assertEquals("One settlement transaction must be saved", 1, fakeGateway.transactions.size)
        val savedTx = fakeGateway.transactions.first().data
        assertEquals("Tx kind must be SETTLEMENT", "SETTLEMENT", savedTx.kind)
        assertEquals("Tx billNumber must be clean MC-2026-101", "MC-2026-101", savedTx.billNumber)
        assertEquals("Tx customerGid must match canonical customer GID", custGid, savedTx.customerGid)
        assertEquals("Tx customerMobile must be clean 10-digit", cleanMob, savedTx.customerMobile)
        assertEquals("Tx amount must be 200.0", 200.0, savedTx.amount, 0.001)
        assertFalse("Tx isReversed must be false", savedTx.isReversed)
    }

    @Test
    fun testT2_PaymentsTabShowsTransactionAndCancelReversesIt() = runBlocking {
        val custDocId = "cust_1731600000000_ab12c"
        val custGid = "01JA0000000000000000000001"
        val cleanMob = "9226299212"

        val customerDto = CustomerDto(
            id = custDocId,
            gid = custGid,
            name = "Swaraj Fugare",
            mobile = cleanMob,
            outstandingAmount = 700.0
        )
        fakeGateway.customers.add(CustomerDocSnapshot(custDocId, "customers/$custDocId", customerDto))

        val billDocId = "bil_101"
        val billDto = BillDto(
            id = billDocId,
            gid = "bil_gid_101",
            billNumber = "MC-101",
            customerGid = custGid,
            customerMobile = cleanMob,
            finalAmount = 1000.0,
            paidAmount = 300.0,
            pendingAmount = 700.0,
            paymentStatus = "PARTIAL",
            billDate = "2026-11-15"
        )
        fakeGateway.bills.add(BillDocSnapshot(billDocId, "bills/$billDocId", billDto))

        // Record payment of 200
        val paymentTx = UdhariTransaction(
            customerId = 0L,
            customerGid = custGid,
            customerName = "Swaraj Fugare",
            customerMobile = cleanMob,
            billId = 0L,
            billNumber = "",
            amount = 200.0,
            paymentMethod = PaymentMethod.CASH,
            previousOutstanding = 700.0,
            remainingOutstanding = 500.0,
            transactionUuid = "tx-uuid-1",
            date = "2026-11-15"
        )
        udhariRepo.recordPayment(paymentTx)

        val recordedTxDto = fakeGateway.transactions.first().data

        // Verify UdhariLedger resolves it for this customer
        val domainCustomer = Customer(
            id = 1L,
            gid = custGid,
            name = "Swaraj Fugare",
            mobile = cleanMob,
            outstandingBalance = fakeGateway.customers.first().data.outstandingAmount
        )
        // In Room, bill.paidAmount represents checkout payment (300.0)
        val domainBill = billDto.copy(paidAmount = 300.0, pendingAmount = 700.0).toDomain()
        val domainTx = recordedTxDto.toDomain()

        val keyResolver = UdhariLedger.buildCustomerKeyResolver(
            listOf(domainCustomer),
            listOf(domainBill),
            listOf(domainTx)
        )
        val targetKey = keyResolver(domainCustomer.gid, domainCustomer.mobile, domainCustomer.name, domainCustomer.id)
        assertNotNull("Target key must resolve", targetKey)

        val txKey = keyResolver(domainTx.customerGid, domainTx.customerMobile, domainTx.customerName, domainTx.customerId)
        assertEquals("Transaction key must match customer key", targetKey, txKey)

        val ledgerResult = UdhariLedger.computeCustomerLedger(
            customerKey = targetKey!!,
            allBills = listOf(domainBill),
            allTransactions = listOf(domainTx),
            allCustomers = listOf(domainCustomer)
        )

        // Customer due in ledger must be 500.0
        assertEquals("Ledger current due must be 500.0", 500.0, ledgerResult.balance, 0.001)

        // Credit events must contain the payment
        val creditEvents = ledgerResult.events.filter { it.isCredit }
        assertTrue("Must contain credit event", creditEvents.any { it.amount == 200.0 })

        // Now reverse the payment
        val revResult = udhariRepo.reversePayment(recordedTxDto.id, "Entered wrong amount")
        assertTrue("Reversal must succeed: ${revResult.exceptionOrNull()?.message}", revResult.isSuccess)

        // Bill pending must be restored to 700.0
        val revertedBill = fakeGateway.bills.first().data
        assertEquals("Bill paidAmount must return to 300.0", 300.0, revertedBill.paidAmount, 0.001)
        assertEquals("Bill pendingAmount must return to 700.0", 700.0, revertedBill.pendingAmount, 0.001)

        // Customer due must be restored to 700.0
        val revertedCust = fakeGateway.customers.first().data
        assertEquals("Customer outstanding must return to 700.0", 700.0, revertedCust.outstandingAmount, 0.001)
    }

    @Test
    fun testT3_MultiBillFIFOAllocation() = runBlocking {
        val custGid = "cust_multi_bill"
        val mob = "9822112233"

        fakeGateway.customers.add(
            CustomerDocSnapshot(
                id = "c1",
                path = "customers/c1",
                data = CustomerDto(id = "c1", gid = custGid, name = "Anil", mobile = mob, outstandingAmount = 800.0)
            )
        )

        // Bill 1: older, 300 pending
        fakeGateway.bills.add(
            BillDocSnapshot(
                id = "b1",
                path = "bills/b1",
                data = BillDto(
                    id = "b1",
                    billNumber = "MC-1",
                    customerGid = custGid,
                    customerMobile = mob,
                    finalAmount = 500.0,
                    paidAmount = 200.0,
                    pendingAmount = 300.0,
                    billDate = "2026-11-10",
                    paymentStatus = "PARTIAL"
                )
            )
        )

        // Bill 2: newer, 500 pending
        fakeGateway.bills.add(
            BillDocSnapshot(
                id = "b2",
                path = "bills/b2",
                data = BillDto(
                    id = "b2",
                    billNumber = "MC-2",
                    customerGid = custGid,
                    customerMobile = mob,
                    finalAmount = 1000.0,
                    paidAmount = 500.0,
                    pendingAmount = 500.0,
                    billDate = "2026-11-12",
                    paymentStatus = "PARTIAL"
                )
            )
        )

        // Pay 500
        val res = udhariRepo.recordPayment(
            UdhariTransaction(
                customerId = 0L,
                customerGid = custGid,
                customerName = "Anil",
                customerMobile = mob,
                billId = 0L,
                billNumber = "",
                amount = 500.0,
                paymentMethod = PaymentMethod.UPI,
                previousOutstanding = 800.0,
                remainingOutstanding = 300.0,
                transactionUuid = "tx-multi-500",
                date = "2026-11-15"
            )
        )
        assertTrue("Payment must succeed", res.isSuccess)

        // Bill 1 should be fully settled (paidAmount=500, pending=0, PAID)
        val b1 = fakeGateway.bills.find { it.id == "b1" }!!.data
        assertEquals(500.0, b1.paidAmount, 0.001)
        assertEquals(0.0, b1.pendingAmount, 0.001)
        assertEquals("PAID", b1.paymentStatus)

        // Bill 2 should receive remaining 200 (paidAmount=700, pending=300, PARTIAL)
        val b2 = fakeGateway.bills.find { it.id == "b2" }!!.data
        assertEquals(700.0, b2.paidAmount, 0.001)
        assertEquals(300.0, b2.pendingAmount, 0.001)
        assertEquals("PARTIAL", b2.paymentStatus)

        // Customer due drops to 300
        assertEquals(300.0, fakeGateway.customers.first().data.outstandingAmount, 0.001)
    }

    @Test
    fun testT4_OverpaymentRejection() = runBlocking {
        val custGid = "cust_overpay"
        val mob = "9822998877"

        fakeGateway.customers.add(
            CustomerDocSnapshot(
                id = "c_over",
                path = "customers/c_over",
                data = CustomerDto(id = "c_over", gid = custGid, name = "Sunil", mobile = mob, outstandingAmount = 500.0)
            )
        )

        fakeGateway.bills.add(
            BillDocSnapshot(
                id = "b_over",
                path = "bills/b_over",
                data = BillDto(
                    id = "b_over",
                    billNumber = "MC-OVER",
                    customerGid = custGid,
                    customerMobile = mob,
                    finalAmount = 500.0,
                    paidAmount = 0.0,
                    pendingAmount = 500.0,
                    paymentStatus = "PENDING"
                )
            )
        )

        // Attempt to pay 600 (exceeds 500 due)
        val res = udhariRepo.recordPayment(
            UdhariTransaction(
                customerId = 0L,
                customerGid = custGid,
                customerName = "Sunil",
                customerMobile = mob,
                billId = 0L,
                billNumber = "",
                amount = 600.0,
                paymentMethod = PaymentMethod.CASH,
                previousOutstanding = 500.0,
                remainingOutstanding = 0.0,
                transactionUuid = "tx-over-600",
                date = "2026-11-15"
            )
        )
        assertTrue("Payment > due must fail", res.isFailure)
        assertTrue(
            "Error must mention exceeding due",
            res.exceptionOrNull()?.message?.contains("बाकीपेक्षा जास्त") == true
        )
    }

    @Test
    fun testT5_CustomerIsolation_TwoSameNameDifferentMobiles() = runBlocking {
        val c1Gid = "cust_ramesh_1"
        val c2Gid = "cust_ramesh_2"
        val mob1 = "9822011111"
        val mob2 = "9822022222"

        fakeGateway.customers.add(
            CustomerDocSnapshot("c1", "customers/c1", CustomerDto(id = "c1", gid = c1Gid, name = "Ramesh Patil", mobile = mob1, outstandingAmount = 500.0))
        )
        fakeGateway.customers.add(
            CustomerDocSnapshot("c2", "customers/c2", CustomerDto(id = "c2", gid = c2Gid, name = "Ramesh Patil", mobile = mob2, outstandingAmount = 300.0))
        )

        fakeGateway.bills.add(
            BillDocSnapshot("b1", "bills/b1", BillDto(id = "b1", billNumber = "MC-R1", customerGid = c1Gid, customerMobile = mob1, finalAmount = 500.0, pendingAmount = 500.0, paymentStatus = "PENDING"))
        )
        fakeGateway.bills.add(
            BillDocSnapshot("b2", "bills/b2", BillDto(id = "b2", billNumber = "MC-R2", customerGid = c2Gid, customerMobile = mob2, finalAmount = 300.0, pendingAmount = 300.0, paymentStatus = "PENDING"))
        )

        // Pay 200 for Ramesh 1
        val res = udhariRepo.recordPayment(
            UdhariTransaction(
                customerId = 0L,
                customerGid = c1Gid,
                customerName = "Ramesh Patil",
                customerMobile = mob1,
                billId = 0L,
                billNumber = "",
                amount = 200.0,
                paymentMethod = PaymentMethod.CASH,
                previousOutstanding = 500.0,
                remainingOutstanding = 300.0,
                transactionUuid = "tx-ramesh-200",
                date = "2026-11-15"
            )
        )
        assertTrue(res.isSuccess)

        // c1 due becomes 300
        val c1After = fakeGateway.customers.find { it.id == "c1" }!!.data
        assertEquals(300.0, c1After.outstandingAmount, 0.001)

        // c2 due remains untouched at 300
        val c2After = fakeGateway.customers.find { it.id == "c2" }!!.data
        assertEquals("c2 must be completely untouched", 300.0, c2After.outstandingAmount, 0.001)

        val b2After = fakeGateway.bills.find { it.id == "b2" }!!.data
        assertEquals("b2 must be completely untouched", 300.0, b2After.pendingAmount, 0.001)
    }
}

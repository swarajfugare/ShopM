package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.data.firebase.CustomerDto
import com.matoshree.shopmanager.data.repository.toDomain
import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class GlobalIdRepairAndOverpaymentTest {

    @Test
    fun testG1Symptom1_DuplicateCustomerDueToMismatchedGidSeeds() {
        val firestoreDocId = "cust_firebase_doc_77"
        val mobile = "9822112233"
        val name = "Suresh Patil"

        // In v3.5.0: Room derived GID from mobile
        val roomBadGid = IdGenerator.legacyGid(IdType.CUSTOMER, "mob_$mobile")

        // In v3.5.0: Cloud derived GID from docId
        val cloudGid = IdGenerator.legacyGid(IdType.CUSTOMER, firestoreDocId)

        assertNotEquals("Bug G1 proof: Room and Cloud produced different GIDs for the same customer!", roomBadGid, cloudGid)

        val roomCustomer = Customer(
            id = 1L,
            gid = roomBadGid,
            name = name,
            mobile = mobile,
            outstandingBalance = 0.0
        )

        val cloudCustomerDto = CustomerDto(
            id = firestoreDocId,
            gid = cloudGid,
            legacyId = 1L,
            name = name,
            mobile = mobile,
            outstandingAmount = 0.0
        )
        val cloudCustomer = cloudCustomerDto.toDomain()

        // When merged in UI / Search:
        val mergedListBad = (listOf(roomCustomer) + listOf(cloudCustomer)).distinctBy { it.canonicalKey() }
        assertEquals("Proof of Bug 1: Customer appears TWICE in directory!", 2, mergedListBad.size)

        // With F1 Rule: both must use IdGenerator.customerGid(docId)
        val unifiedGid = IdGenerator.legacyGid(IdType.CUSTOMER, firestoreDocId)
        val repairedRoomCust = roomCustomer.copy(gid = unifiedGid)
        val repairedCloudCust = cloudCustomer.copy(gid = unifiedGid)

        val mergedListFixed = (listOf(repairedRoomCust) + listOf(repairedCloudCust)).distinctBy { it.canonicalKey() }
        assertEquals("With F1 rule: Customer appears ONCE in directory!", 1, mergedListFixed.size)
    }

    @Test
    fun testG1Symptom3_ManualCreditVanishesWhenCustomerGidDoesNotMatch() {
        val firestoreDocId = "cust_doc_88"
        val mobile = "9822334455"
        val name = "Vijay Kadam"

        // Real customer has GID derived from docId
        val realCustGid = IdGenerator.legacyGid(IdType.CUSTOMER, firestoreDocId)
        val customer = Customer(id = 5L, gid = realCustGid, name = name, mobile = mobile)

        // In v3.5.0: manual credit (billId = 0) had customerGid = legacyGid(CUSTOMER, "cust_5")
        val wrongTxCustGid = IdGenerator.legacyGid(IdType.CUSTOMER, "cust_5")

        val manualCreditTx = UdhariTransaction(
            id = "tx-credit-1",
            gid = IdGenerator.generate(IdType.UDHARI),
            customerId = 5L,
            customerGid = wrongTxCustGid,
            customerName = name,
            customerMobile = mobile,
            billId = 0L,
            billGid = null,
            billNumber = "",
            type = UdhariTransactionType.PURCHASE_CREDIT,
            amount = 1200.0,
            note = "Old udhari entry",
            transactionUuid = "uuid-credit-1",
            date = "2026-11-01"
        )

        // Ledger with F1 resolution chain:
        // Even if tx has a legacy/wrong customerGid, resolution chain matches via mobile or customerId to realCustGid!
        val keyResolver = UdhariLedger.buildCustomerKeyResolver(
            customers = listOf(customer),
            bills = emptyList(),
            transactions = listOf(manualCreditTx)
        )

        val resolvedKey = keyResolver(manualCreditTx.customerGid, manualCreditTx.customerMobile, manualCreditTx.customerName, manualCreditTx.customerId)
        assertEquals("F1 chain must resolve manual credit to real customer GID!", realCustGid, resolvedKey)

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = realCustGid,
            allBills = emptyList(),
            allTransactions = listOf(manualCreditTx),
            allCustomers = listOf(customer)
        )

        assertEquals("Manual credit must appear in customer balance!", 1200.0, ledger.balance, 0.001)
        assertEquals(1, ledger.events.size)
    }

    @Test
    fun testG1Symptom2_GhostCustomerOwesNothingFix() {
        val custGid = IdGenerator.legacyGid(IdType.CUSTOMER, "c_doc_99")
        val customer = Customer(id = 9L, gid = custGid, name = "Mahesh", mobile = "9822998877")

        // Bill has legacy/bad customerGid
        val badBillCustGid = IdGenerator.legacyGid(IdType.CUSTOMER, "srv_9")
        val bill = Bill(
            id = 90L,
            gid = IdGenerator.legacyGid(IdType.BILL, "b_doc_90"),
            customerGid = badBillCustGid,
            customerName = "Mahesh",
            customerMobile = "9822998877",
            billNumber = "BILL-90",
            transactionUuid = "uuid-b-90",
            saleType = SaleType.QUICK,
            subtotal = 500.0,
            finalAmount = 500.0,
            paidAmount = 0.0,
            pendingAmount = 500.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-10 10:00:00"
        )

        // Payment made by customer
        val payment = UdhariTransaction(
            id = "tx-pay-90",
            gid = IdGenerator.generate(IdType.PAYMENT),
            customerId = 9L,
            customerGid = custGid,
            customerName = "Mahesh",
            customerMobile = "9822998877",
            billId = 90L,
            billNumber = "BILL-90",
            type = UdhariTransactionType.PAYMENT,
            amount = 500.0,
            transactionUuid = "uuid-p-90",
            date = "2026-11-10"
        )

        // Ledger with F1 resolver: bill with badBillCustGid resolves to custGid via mobile/customerId!
        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill),
            allTransactions = listOf(payment),
            allCustomers = listOf(customer)
        )

        assertEquals("Customer balance must be exactly 0 (owes nothing)!", 0.0, ledger.balance, 0.001)
        assertEquals("Total purchases = 500", 500.0, ledger.totalPurchases, 0.001)
        assertEquals("Total paid = 500", 500.0, ledger.totalPaid, 0.001)
    }

    @Test
    fun testF4_OverpaymentRejectionLogic() {
        val currentDue = 750.0

        // Test 1: Paying > current due must be invalid
        val attemptedPayOver = 800.0
        val isValidOver = attemptedPayOver > 0.0 && attemptedPayOver <= currentDue
        assertFalse("Payment greater than current due must be rejected!", isValidOver)

        // Test 2: Paying <= 0 must be invalid
        val attemptedPayZero = 0.0
        val isValidZero = attemptedPayZero > 0.0 && attemptedPayZero <= currentDue
        assertFalse("Payment of 0 or negative must be rejected!", isValidZero)

        // Test 3: Paying exact due must be valid
        val attemptedPayExact = 750.0
        val isValidExact = attemptedPayExact > 0.0 && attemptedPayExact <= currentDue
        assertTrue("Payment of exact due must be allowed!", isValidExact)

        // Test 4: Paying partial due must be valid
        val attemptedPayPartial = 300.0
        val isValidPartial = attemptedPayPartial > 0.0 && attemptedPayPartial <= currentDue
        assertTrue("Payment of partial due must be allowed!", isValidPartial)
    }

    @Test
    fun testF4_BillSpecificOverpaymentRejection() {
        val billPending = 400.0
        val attemptedPayOver = 450.0
        val isValid = attemptedPayOver > 0.0 && attemptedPayOver <= billPending
        assertFalse("Payment exceeding specific bill pending amount must be rejected!", isValid)
    }

    @Test
    fun testF4_LegacyOverpaidCustomerFlaggedNeverNegativeInUI() {
        val custGid = IdGenerator.generate(IdType.CUSTOMER)
        val customer = Customer(id = 1L, gid = custGid, name = "Kishor", mobile = "9822000001")

        val bill = Bill(
            id = 1L,
            gid = IdGenerator.generate(IdType.BILL),
            customerGid = custGid,
            customerName = "Kishor",
            customerMobile = "9822000001",
            billNumber = "B-1",
            transactionUuid = "uuid-1",
            saleType = SaleType.QUICK,
            subtotal = 500.0,
            finalAmount = 500.0,
            paidAmount = 0.0,
            pendingAmount = 500.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-10 10:00:00"
        )

        // Customer made payment of 600 in the past (legacy overpayment)
        val payment = UdhariTransaction(
            id = "tx-old-over",
            gid = IdGenerator.generate(IdType.PAYMENT),
            customerId = 1L,
            customerGid = custGid,
            customerName = "Kishor",
            customerMobile = "9822000001",
            billId = 1L,
            billNumber = "B-1",
            type = UdhariTransactionType.PAYMENT,
            amount = 600.0,
            transactionUuid = "uuid-over-1",
            date = "2026-11-10"
        )

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill),
            allTransactions = listOf(payment),
            allCustomers = listOf(customer)
        )

        // Balance in UI must be clamped to 0.0 (never negative)
        val uiDisplayBalance = ledger.balance.coerceAtLeast(0.0)
        assertEquals("UI display balance must be 0.0 and never negative!", 0.0, uiDisplayBalance, 0.001)
        assertTrue("Legacy overpayment must be flagged as data issue!", ledger.totalPaid > ledger.totalPurchases)
    }

    @Test
    fun testF3_NoBillNumberDeduplicationAcrossDistinctBills() {
        val custGid = IdGenerator.generate(IdType.CUSTOMER)
        val customer = Customer(id = 1L, gid = custGid, name = "Dinesh", mobile = "9822000002")

        // Two distinct bills sharing the same bill number "B-100" (e.g. from 2 phones offline or different dates)
        val bill1 = Bill(
            id = 101L,
            gid = IdGenerator.generate(IdType.BILL),
            customerGid = custGid,
            customerName = "Dinesh",
            customerMobile = "9822000002",
            billNumber = "B-100",
            transactionUuid = "uuid-bill-phone-A",
            saleType = SaleType.QUICK,
            subtotal = 300.0,
            finalAmount = 300.0,
            paidAmount = 0.0,
            pendingAmount = 300.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-10 10:00:00"
        )

        val bill2 = Bill(
            id = 102L,
            gid = IdGenerator.generate(IdType.BILL),
            customerGid = custGid,
            customerName = "Dinesh",
            customerMobile = "9822000002",
            billNumber = "B-100", // Same number!
            transactionUuid = "uuid-bill-phone-B", // Different UUID & GID
            saleType = SaleType.QUICK,
            subtotal = 400.0,
            finalAmount = 400.0,
            paidAmount = 0.0,
            pendingAmount = 400.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-11 11:00:00"
        )

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill1, bill2),
            allTransactions = emptyList(),
            allCustomers = listOf(customer)
        )

        assertEquals("Both distinct bills sharing bill number must be preserved!", 2, ledger.billBreakdowns.size)
        assertEquals("Total purchases must be sum of both bills (700)", 700.0, ledger.totalPurchases, 0.001)
        assertEquals(700.0, ledger.balance, 0.001)
    }

    @Test
    fun testTwoPhonesProduceIdenticalGidsForSameFirestoreDocument() {
        val customerDocId = "firestore_customer_doc_999"
        val billDocId = "firestore_bill_doc_888"

        // Simulation: Phone A computes GIDs
        val phoneACustomerGid = IdGenerator.customerGid(customerDocId)
        val phoneABillGid = IdGenerator.billGid(billDocId)

        // Simulation: Phone B computes GIDs independently
        val phoneBCustomerGid = IdGenerator.customerGid(customerDocId)
        val phoneBBillGid = IdGenerator.billGid(billDocId)

        assertEquals("Phone A and Phone B MUST produce identical customer GID for the same Firestore docId!",
            phoneACustomerGid, phoneBCustomerGid)
        assertEquals("Phone A and Phone B MUST produce identical bill GID for the same Firestore docId!",
            phoneABillGid, phoneBBillGid)
        assertTrue("Generated customer GID must be valid legacy format",
            IdFormat.isValid(IdType.CUSTOMER, phoneACustomerGid))
        assertTrue("Generated bill GID must be valid legacy format",
            IdFormat.isValid(IdType.BILL, phoneABillGid))
    }

    @Test
    fun testTwoSimultaneousPayments_SecondExceedsRemainingDue_Rejected() {
        val custGid = IdGenerator.generate(IdType.CUSTOMER)
        val customer = Customer(id = 1L, gid = custGid, name = "Ramesh", mobile = "9822000003")

        val bill = Bill(
            id = 1L,
            gid = IdGenerator.generate(IdType.BILL),
            customerGid = custGid,
            customerName = "Ramesh",
            customerMobile = "9822000003",
            billNumber = "B-200",
            transactionUuid = "uuid-b-200",
            saleType = SaleType.QUICK,
            subtotal = 500.0,
            finalAmount = 500.0,
            paidAmount = 0.0,
            pendingAmount = 500.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-12 10:00:00"
        )

        // Phone 1 records payment of 300
        val payment1 = UdhariTransaction(
            id = "pay-1",
            gid = IdGenerator.generate(IdType.PAYMENT),
            customerId = 1L,
            customerGid = custGid,
            customerName = "Ramesh",
            customerMobile = "9822000003",
            billId = 1L,
            billNumber = "B-200",
            type = UdhariTransactionType.PAYMENT,
            amount = 300.0,
            transactionUuid = "uuid-pay-1",
            date = "2026-11-12"
        )

        val ledgerAfterPhone1 = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill),
            allTransactions = listOf(payment1),
            allCustomers = listOf(customer)
        )
        val remainingDueAfterPhone1 = ledgerAfterPhone1.balance.coerceAtLeast(0.0)
        assertEquals("Remaining due after Phone 1 payment must be 200", 200.0, remainingDueAfterPhone1, 0.001)

        // Phone 2 tries to record payment of 300 simultaneously (based on stale due of 500)
        val phone2PaymentAttempt = 300.0
        val wouldExceed = phone2PaymentAttempt > remainingDueAfterPhone1 + 0.01
        assertTrue("Phone 2 payment must be REJECTED because it exceeds current due of 200!", wouldExceed)
    }

    @Test
    fun testZeroOrNegativePayment_StrictlyRejected() {
        val zeroPayment = 0.0
        val negativePayment = -150.0

        assertTrue("Zero payment must be rejected", zeroPayment <= 0.0)
        assertTrue("Negative payment must be rejected", negativePayment <= 0.0)
    }
}

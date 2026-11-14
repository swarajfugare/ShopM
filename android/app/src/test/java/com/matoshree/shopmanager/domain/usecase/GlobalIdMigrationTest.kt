package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class GlobalIdMigrationTest {

    @Test
    fun testDeterministicLegacyGidDerivationAcrossPhones() {
        // Phone A and Phone B offline independently derive GID for docId "c_123"
        val docId = "cust_abc_9876543210"
        val phoneAGid = IdGenerator.legacyGid(IdType.CUSTOMER, docId)
        val phoneBGid = IdGenerator.legacyGid(IdType.CUSTOMER, docId)

        assertEquals("Both phones must derive identical GID", phoneAGid, phoneBGid)
        assertTrue("Must start with cus_ prefix", phoneAGid.startsWith("cus_"))
        assertTrue("Must be marked as legacy with L", phoneAGid.substring(4).startsWith("L"))
        assertTrue("Must be recognized as valid by IdFormat", IdFormat.isValid(IdType.CUSTOMER, phoneAGid))
        assertTrue("Must be recognized as legacy by IdFormat", IdFormat.isLegacy(phoneAGid))
    }

    @Test
    fun testCustomerRenamePreservesHistoryViaGid() {
        val custGid = IdGenerator.generate(IdType.CUSTOMER)
        val billGid = IdGenerator.generate(IdType.BILL)
        val payGid = IdGenerator.generate(IdType.PAYMENT)

        val initialCustomer = Customer(
            id = 1L,
            gid = custGid,
            name = "Ramesh Pawar",
            mobile = "9822001122",
            outstandingBalance = 1500.0
        )

        val bill = Bill(
            id = 101L,
            gid = billGid,
            customerGid = custGid,
            customerName = "Ramesh Pawar",
            customerMobile = "9822001122",
            billNumber = "BILL-001",
            transactionUuid = "uuid-b-001",
            saleType = SaleType.QUICK,
            subtotal = 1500.0,
            finalAmount = 1500.0,
            paidAmount = 0.0,
            pendingAmount = 1500.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-14 10:00:00"
        )

        val payment = UdhariTransaction(
            id = "tx-1",
            gid = payGid,
            customerId = 1L,
            customerGid = custGid,
            customerName = "Ramesh Pawar",
            customerMobile = "9822001122",
            billId = 101L,
            billGid = billGid,
            billNumber = "BILL-001",
            type = UdhariTransactionType.PAYMENT,
            amount = 500.0,
            paymentMethod = PaymentMethod.CASH,
            transactionUuid = "uuid-p-001",
            date = "2026-11-14",
            createdAt = System.currentTimeMillis()
        )

        // Calculate initial ledger
        val initialLedger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill),
            allTransactions = listOf(payment),
            allCustomers = listOf(initialCustomer)
        )
        assertEquals(1000.0, initialLedger.balance, 0.001)

        // Customer changes name and mobile phone (e.g. Ramesh Pawar updates mobile number to 9899887766 and name to Ramesh S. Pawar)
        val renamedCustomer = initialCustomer.copy(
            name = "Ramesh S. Pawar",
            mobile = "9899887766"
        )

        // In UdhariLedger, identity is preserved via custGid
        val updatedLedger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill),
            allTransactions = listOf(payment),
            allCustomers = listOf(renamedCustomer)
        )
        assertEquals("Renaming customer must not alter outstanding balance", 1000.0, updatedLedger.balance, 0.001)
        assertEquals("Customer reference must be updated customer", "Ramesh S. Pawar", updatedLedger.customer?.name)
    }

    @Test
    fun testOldAppToleranceWithoutGid() {
        val legacyDoc = com.matoshree.shopmanager.data.firebase.CustomerDto(
            id = "legacy_cloud_doc_101",
            legacyId = 101L,
            name = "Old Customer",
            mobile = "9112233445",
            outstandingAmount = 2500.0
        )

        // When domain model is converted, GID is automatically deterministically generated
        val domain = legacyDoc.toDomain()
        assertTrue("Must have non-empty GID", domain.gid.isNotBlank())
        assertTrue("Must start with cus_", domain.gid.startsWith("cus_"))
        assertTrue("Must be marked as legacy", IdFormat.isLegacy(domain.gid))
    }

    @Test
    fun testDeduplicationByGidInLedger() {
        val custGid = IdGenerator.generate(IdType.CUSTOMER)
        val billGid = IdGenerator.generate(IdType.BILL)

        val customer = Customer(id = 1L, gid = custGid, name = "Sunil", mobile = "9000000001")

        // 2 identical bills synced from Room and cloud with same GID
        val bill1 = Bill(
            id = 10L,
            gid = billGid,
            customerGid = custGid,
            billNumber = "B-100",
            transactionUuid = "uuid-1",
            saleType = SaleType.QUICK,
            subtotal = 800.0,
            finalAmount = 800.0,
            pendingAmount = 800.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-14 11:00:00"
        )
        val bill2 = bill1.copy(id = 20L) // different room local id but same GID

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill1, bill2),
            allTransactions = emptyList(),
            allCustomers = listOf(customer)
        )

        assertEquals("Duplicate bills with same GID must be deduplicated to exactly 1 bill", 1, ledger.billBreakdowns.size)
        assertEquals(800.0, ledger.balance, 0.001)
    }

    @Test
    fun testFuzzyMatchingRemovalForGidRecords() {
        val custGid = IdGenerator.generate(IdType.CUSTOMER)
        val billGid = IdGenerator.generate(IdType.BILL)
        val customer = Customer(id = 1L, gid = custGid, name = "Ajay", mobile = "9000000002")

        val bill = Bill(
            id = 50L,
            gid = billGid,
            customerGid = custGid,
            billNumber = "B-500",
            transactionUuid = "uuid-50",
            saleType = SaleType.QUICK,
            subtotal = 1000.0,
            finalAmount = 1000.0,
            pendingAmount = 1000.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-14 12:00:00"
        )

        // An unlinked credit transaction for a different bill, but whose note mentions "B-500"
        // In old fuzzy logic, it would erroneously link. With GID architecture and needsReview=false, it does not fuzzy match.
        val otherCreditTx = UdhariTransaction(
            id = "tx-other",
            gid = IdGenerator.generate(IdType.UDHARI),
            customerId = 1L,
            customerGid = custGid,
            customerName = "Ramesh Pawar",
            customerMobile = "9822001122",
            billId = 0L,
            billGid = null,
            billNumber = "",
            type = UdhariTransactionType.PURCHASE_CREDIT,
            amount = 300.0,
            note = "Old note referencing B-500",
            transactionUuid = "uuid-other",
            date = "2026-11-14",
            needsReview = false
        )

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = custGid,
            allBills = listOf(bill),
            allTransactions = listOf(otherCreditTx),
            allCustomers = listOf(customer)
        )

        // The other credit is treated as a manual debit because it's not bound to billGid, and NOT collapsed into bill B-500!
        assertEquals("Total purchases must be bill amount + manual debit", 1300.0, ledger.totalPurchases, 0.001)
        assertEquals(1300.0, ledger.balance, 0.001)
    }
}

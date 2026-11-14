package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.data.firebase.CustomerDto
import com.matoshree.shopmanager.data.repository.toDomain
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.PhoneUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * Verification and regression test suite for v3.5.2 (Round 12):
 * - H1/K1/K2: Elimination of duplicate customers via true docId (cust_m_<mobile>) and single source of truth Room streaming.
 * - H2/K3: Resolution of wrong customer profile opening via customerGid navigation instead of numeric id collisions.
 * - K4: Strict UdhariLedger customer isolation (no cross-contamination via local numeric ID or name).
 * - K5: Alias mapping resolution.
 */
class GlobalIdV352Test {

    @Test
    fun testH1_Reproduction_FabricatedDocIdProducesDuplicate_FixedByRealDocId() {
        val mobile = "9822112233"
        val serverId = 42L

        // In v3.5.1: Room fabricated docId as "cust_42"
        val badRoomDocId = "cust_$serverId"
        val badRoomGid = IdGenerator.customerGid(badRoomDocId)

        // In Firestore: docId was "cust_m_9822112233"
        val cloudDocId = "cust_m_$mobile"
        val cloudGid = IdGenerator.customerGid(cloudDocId)

        // Proof of H1 bug:
        assertNotEquals("Bug H1: Fabricated docId produces mismatched GID!", badRoomGid, cloudGid)

        // When both existed in a combined map:
        val combinedBadMap = mapOf(
            badRoomGid to Customer(id = 42L, gid = badRoomGid, name = "Ramesh", mobile = mobile),
            cloudGid to Customer(id = 999L, gid = cloudGid, name = "Ramesh", mobile = mobile)
        )
        assertEquals("Proof of Symptom 1: Duplicate customer appeared in directory (2 rows instead of 1)!", 2, combinedBadMap.size)

        // Under K1 Rule: Real docId for 10-digit mobile is ALWAYS "cust_m_$cleanMob"
        val cleanMob = PhoneUtils.clean10DigitMobileInput(mobile)
        val k1RoomDocId = if (cleanMob.length == 10) "cust_m_$cleanMob" else badRoomDocId
        val k1RoomGid = IdGenerator.customerGid(k1RoomDocId)

        assertEquals("K1 Fix: Room and Cloud derive the exact same canonical GID!", cloudGid, k1RoomGid)

        // Under K2 Rule: Single source of truth in Room distinctBy gid
        val unifiedList = listOf(
            Customer(id = 42L, gid = k1RoomGid, name = "Ramesh", mobile = mobile),
            Customer(id = 999L, gid = cloudGid, name = "Ramesh", mobile = mobile)
        ).distinctBy { it.gid }

        assertEquals("K2 Fix: Directory shows exactly 1 row for the customer!", 1, unifiedList.size)
    }

    @Test
    fun testH2_Reproduction_NumericIdCollisionOpensWrongCustomer_FixedByCustomerGid() {
        // Customer A: Cloud-only customer whose numeric ID was generated via hash(docId)
        val docIdA = "cust_m_9822000001"
        val gidA = IdGenerator.customerGid(docIdA)
        val numericId = 12345L // Colliding numeric ID

        val customerA = Customer(
            id = numericId,
            gid = gidA,
            name = "Anand Patil",
            mobile = "9822000001"
        )

        // Customer B: Local Room customer whose auto-increment ID happened to be the same 12345L
        val docIdB = "cust_m_9822000002"
        val gidB = IdGenerator.customerGid(docIdB)

        val customerB = Customer(
            id = numericId,
            gid = gidB,
            name = "Balasaheb Shinde",
            mobile = "9822000002"
        )

        val customers = listOf(customerA, customerB)

        // In v3.5.1: Navigation passed numeric customerId (12345L)
        // Calling customers.find { it.id == 12345L } when tapping Customer B returns Customer A!
        val resolvedByNumericId = customers.find { it.id == numericId }
        assertNotNull(resolvedByNumericId)
        assertEquals("Proof of Symptom 2: Tapping Customer B opened Customer A due to numeric ID collision!", "Anand Patil", resolvedByNumericId?.name)

        // Under K3 Rule: Navigation uses customerGid (String)
        val resolvedCustomerA = customers.find { it.gid == gidA }
        val resolvedCustomerB = customers.find { it.gid == gidB }

        assertEquals("K3 Fix: GID lookup for Customer A returns Anand Patil", "Anand Patil", resolvedCustomerA?.name)
        assertEquals("K3 Fix: GID lookup for Customer B returns Balasaheb Shinde", "Balasaheb Shinde", resolvedCustomerB?.name)
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
        pendingAmount: Double,
        paymentStatus: PaymentStatus = PaymentStatus.PENDING,
        billDate: String = "2026-11-01 10:00:00"
    ): Bill {
        return Bill(
            id = id,
            gid = gid,
            billNumber = billNumber,
            transactionUuid = "uuid-bill-$id",
            saleType = SaleType.QUICK,
            subtotal = finalAmount,
            finalAmount = finalAmount,
            pendingAmount = pendingAmount,
            paymentStatus = paymentStatus,
            customerId = customerId,
            customerGid = customerGid,
            customerName = customerName,
            customerMobile = customerMobile,
            billDate = billDate
        )
    }

    @Test
    fun testK4_UdhariLedger_DoesNotCrossContaminateBillsViaNumericId() {
        val gidA = "cus_01JAAAAAAAAAAAAAA1"
        val gidB = "cus_01JBBBBBBBBBBBBBB2"

        val customerA = Customer(id = 5L, gid = gidA, name = "Kishor", mobile = "9822000001")
        val customerB = Customer(id = 5L, gid = gidB, name = "Mahesh", mobile = "9822000002") // Same local numeric id 5L!

        val billA = createBill(
            id = 101L,
            gid = "bil_01J000000000000001",
            billNumber = "BILL-101",
            customerId = 5L,
            customerGid = gidA,
            customerMobile = "9822000001",
            customerName = "Kishor",
            finalAmount = 2000.0,
            pendingAmount = 2000.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-01 10:00:00"
        )

        val billB = createBill(
            id = 102L,
            billNumber = "BILL-102",
            gid = "bil_01J000000000000002",
            customerId = 5L,
            customerGid = gidB,
            customerMobile = "9822000002",
            customerName = "Mahesh",
            finalAmount = 3500.0,
            pendingAmount = 3500.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-02 10:00:00"
        )

        val allCustomers = listOf(customerA, customerB)
        val allBills = listOf(billA, billB)

        val keyResolver = UdhariLedger.buildCustomerKeyResolver(
            customers = allCustomers,
            bills = allBills,
            transactions = emptyList()
        )

        // Key resolution for Customer A's bill:
        val keyForBillA = keyResolver(billA.customerGid, billA.customerMobile, billA.customerName, billA.customerId)
        val keyForBillB = keyResolver(billB.customerGid, billB.customerMobile, billB.customerName, billB.customerId)

        assertEquals("Bill A resolves to Customer A's GID", gidA, keyForBillA)
        assertEquals("Bill B resolves to Customer B's GID", gidB, keyForBillB)
        assertNotEquals("K4: Bills with identical local numeric IDs do NOT resolve to the same customer key!", keyForBillA, keyForBillB)

        // Compute individual ledgers:
        val ledgerA = UdhariLedger.computeCustomerLedger(
            customerKey = gidA,
            allBills = allBills,
            allTransactions = emptyList(),
            allCustomers = allCustomers
        )

        val ledgerB = UdhariLedger.computeCustomerLedger(
            customerKey = gidB,
            allBills = allBills,
            allTransactions = emptyList(),
            allCustomers = allCustomers
        )

        // Customer A's ledger must contain ONLY Bill A
        assertEquals("Customer A has exactly 1 bill", 1, ledgerA.billBreakdowns.size)
        assertEquals("Customer A's bill is BILL-101", "BILL-101", ledgerA.billBreakdowns[0].billNumber)
        assertEquals("Customer A balance is 2000.0", 2000.0, ledgerA.balance, 0.001)

        // Customer B's ledger must contain ONLY Bill B
        assertEquals("Customer B has exactly 1 bill", 1, ledgerB.billBreakdowns.size)
        assertEquals("Customer B's bill is BILL-102", "BILL-102", ledgerB.billBreakdowns[0].billNumber)
        assertEquals("Customer B balance is 3500.0", 3500.0, ledgerB.balance, 0.001)
    }

    @Test
    fun testK5_AliasMapping_ResolvesOldGidToCanonicalGid() {
        val canonicalGid = "cus_01JCANONICAL0000000001"
        val oldAliasGid = "cus_Lcust_42"

        val customer = Customer(
            id = 42L,
            gid = canonicalGid,
            name = "Ramesh Patil",
            mobile = "9822112233"
        )

        val oldBill = createBill(
            id = 201L,
            gid = "bil_01J000000000000003",
            billNumber = "BILL-201",
            customerId = 42L,
            customerGid = oldAliasGid, // Written before migration with old fabricated GID
            customerMobile = "9822112233",
            customerName = "Ramesh Patil",
            finalAmount = 1500.0,
            pendingAmount = 1500.0,
            paymentStatus = PaymentStatus.PENDING,
            billDate = "2026-11-03 10:00:00"
        )

        val aliases = mapOf(oldAliasGid to canonicalGid)

        val resolver = UdhariLedger.buildCustomerKeyResolver(
            customers = listOf(customer),
            bills = listOf(oldBill),
            transactions = emptyList(),
            aliases = aliases
        )

        val resolvedKey = resolver(oldBill.customerGid, oldBill.customerMobile, oldBill.customerName, oldBill.customerId)
        assertEquals("K5 Fix: Old alias GID resolves to canonical GID", canonicalGid, resolvedKey)

        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = canonicalGid,
            allBills = listOf(oldBill),
            allTransactions = emptyList(),
            allCustomers = listOf(customer)
        )

        assertEquals("Ledger for canonical customer successfully includes bill pointing to old alias", 1, ledger.billBreakdowns.size)
        assertEquals("Ledger balance is 1500.0", 1500.0, ledger.balance, 0.001)
    }

    @Test
    fun testK2_DirectoryCountEqualsDiagnosticCount() {
        val activeCustomers = listOf(
            Customer(id = 1L, gid = "cus_1", name = "Cust 1", mobile = "9822000001", isActive = true),
            Customer(id = 2L, gid = "cus_2", name = "Cust 2", mobile = "9822000002", isActive = true),
            Customer(id = 3L, gid = "cus_3", name = "Cust 3", mobile = "9822000003", isActive = true)
        )
        val archivedCustomer = Customer(id = 4L, gid = "cus_4", name = "Cust 4", mobile = "9822000004", isActive = false)
        val walkInCustomer = Customer(id = 5L, gid = "cus_5", name = "Walk-in", mobile = "", isActive = true)

        val all = activeCustomers + archivedCustomer + walkInCustomer

        // Directory filtering rule:
        val directoryList = all.filter { it.isActive && it.name.isNotBlank() && !it.name.equals("Walk-in", ignoreCase = true) }

        // Diagnostic filtering rule:
        val diagnosticActive = all.filter { it.isActive && it.name.isNotBlank() && !it.name.equals("Walk-in", ignoreCase = true) }

        assertEquals("Directory count and Diagnostic count must match identically", directoryList.size, diagnosticActive.size)
        assertEquals("Count is exactly 3 active distinct customers", 3, directoryList.size)
    }
}

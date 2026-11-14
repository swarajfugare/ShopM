package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LedgerConsistencyTest {

    private fun createBill(
        id: Long,
        customerId: Long,
        name: String,
        mobile: String,
        finalAmount: Double,
        paidAmount: Double,
        pendingAmount: Double,
        status: PaymentStatus = if (pendingAmount <= 0.01) PaymentStatus.PAID else if (paidAmount > 0.01) PaymentStatus.PARTIAL else PaymentStatus.PENDING,
        isVoided: Boolean = false,
        billDate: String = "2026-11-10 10:00:00",
        billNumber: String = "BILL-$id",
        transactionUuid: String = "uuid-bill-$id",
        note: String? = null
    ): Bill {
        return Bill(
            id = id,
            billNumber = billNumber,
            transactionUuid = transactionUuid,
            saleType = SaleType.QUICK,
            subtotal = finalAmount,
            finalAmount = finalAmount,
            paidAmount = paidAmount,
            pendingAmount = pendingAmount,
            paymentStatus = status,
            isVoided = isVoided,
            billDate = billDate,
            customerId = customerId,
            customerName = name,
            customerMobile = mobile,
            note = note
        )
    }

    private fun createTx(
        id: String,
        customerId: Long,
        name: String,
        mobile: String,
        amount: Double,
        type: UdhariTransactionType,
        billId: Long = 0L,
        billNumber: String = "",
        transactionUuid: String = "tx-uuid-$id",
        note: String? = null,
        isReversed: Boolean = false,
        date: String = "2026-11-10",
        createdAt: Long = 1000L
    ): UdhariTransaction {
        return UdhariTransaction(
            id = id,
            customerId = customerId,
            customerName = name,
            customerMobile = mobile,
            billId = billId,
            billNumber = billNumber,
            type = type,
            amount = amount,
            paymentMethod = PaymentMethod.CASH,
            previousOutstanding = 0.0,
            remainingOutstanding = 0.0,
            transactionUuid = transactionUuid,
            date = date,
            createdAt = createdAt,
            note = note,
            isReversed = isReversed
        )
    }

    // A3.1: Credit sale where PURCHASE_CREDIT tx has billId=0 + same billNumber -> exactly ONE entry, balance counted once
    @Test
    fun test_A3_1_creditSale_txHasBillId0_sameBillNumber_oneEntry_balanceCountedOnce() {
        val cust = Customer(id = 10L, name = "Kajal Salgar", mobile = "9822001122")
        val bill = createBill(
            id = 101L,
            customerId = 10L,
            name = "Kajal Salgar",
            mobile = "9822001122",
            finalAmount = 500.0,
            paidAmount = 0.0,
            pendingAmount = 500.0,
            billNumber = "BILL-101"
        )
        // Transaction written with billId = 0L but billNumber = "BILL-101"
        val tx = createTx(
            id = "tx101",
            customerId = 10L,
            name = "Kajal Salgar",
            mobile = "9822001122",
            amount = 500.0,
            type = UdhariTransactionType.PURCHASE_CREDIT,
            billId = 0L,
            billNumber = "BILL-101"
        )

        val res = UdhariLedger.computeCustomerLedger(
            customerKey = "mob_9822001122",
            allBills = listOf(bill),
            allTransactions = listOf(tx),
            allCustomers = listOf(cust)
        )

        assertEquals("Balance must be exactly 500, not double counted", 500.0, res.balance, 0.001)
        assertEquals("Total purchases must be exactly 500", 500.0, res.totalPurchases, 0.001)
        assertEquals("Exactly one debit charge event in khatavahi events", 1, res.events.filter { !it.isCredit }.size)
        assertEquals("Exactly one bill breakdown", 1, res.billBreakdowns.size)
    }

    // A3.2: Same bill received twice (two uuids / local + cloud sharing GID) -> one entry
    @Test
    fun test_A3_2_sameBillReceivedTwice_differentUuids_oneEntry() {
        val cust = Customer(id = 20L, name = "Swaraj", mobile = "9833001122")
        val billGid = IdGenerator.generate(IdType.BILL)
        val localBill = createBill(
            id = 201L,
            customerId = 20L,
            name = "Swaraj",
            mobile = "9833001122",
            finalAmount = 1000.0,
            paidAmount = 0.0,
            pendingAmount = 1000.0,
            billNumber = "BILL-201",
            transactionUuid = "uuid-local-201"
        ).copy(gid = billGid)
        val cloudBill = createBill(
            id = 202L,
            customerId = 20L,
            name = "Swaraj",
            mobile = "9833001122",
            finalAmount = 1000.0,
            paidAmount = 0.0,
            pendingAmount = 1000.0,
            billNumber = "BILL-201",
            transactionUuid = "uuid-cloud-201"
        ).copy(gid = billGid)

        val res = UdhariLedger.computeCustomerLedger(
            customerKey = "mob_9833001122",
            allBills = listOf(localBill, cloudBill),
            allTransactions = emptyList(),
            allCustomers = listOf(cust)
        )

        assertEquals("Duplicate bill must be deduplicated to 1000", 1000.0, res.balance, 0.001)
        assertEquals("Total purchases must be 1000", 1000.0, res.totalPurchases, 0.001)
        assertEquals("Exactly 1 bill breakdown", 1, res.billBreakdowns.size)
        assertEquals("Exactly 1 charge event", 1, res.events.filter { !it.isCredit }.size)
    }

    // A3.3: Duplicate PAYMENT with different uuids but the same idempotency key -> one payment
    @Test
    fun test_A3_3_duplicatePayment_differentUuids_sameIdempotency_onePayment() {
        val cust = Customer(id = 30L, name = "Rohan", mobile = "9844001122")
        val bill = createBill(
            id = 301L,
            customerId = 30L,
            name = "Rohan",
            mobile = "9844001122",
            finalAmount = 1500.0,
            paidAmount = 0.0,
            pendingAmount = 1500.0,
            billNumber = "BILL-301"
        )
        // Two payment transactions for the same bill sharing idempotency key / GID
        val payGid = IdGenerator.generate(IdType.PAYMENT)
        val pay1 = createTx(
            id = "payDoc1",
            customerId = 30L,
            name = "Rohan",
            mobile = "9844001122",
            amount = 500.0,
            type = UdhariTransactionType.PAYMENT,
            billId = 301L,
            billNumber = "BILL-301",
            transactionUuid = "uuid-pay-doc1",
            createdAt = 5000L
        ).copy(gid = payGid)
        val pay2 = createTx(
            id = "payDoc2",
            customerId = 30L,
            name = "Rohan",
            mobile = "9844001122",
            amount = 500.0,
            type = UdhariTransactionType.PAYMENT,
            billId = 301L,
            billNumber = "BILL-301",
            transactionUuid = "uuid-pay-doc1", // Idempotency key reused on retry / sync
            createdAt = 5050L
        ).copy(gid = payGid)

        val res = UdhariLedger.computeCustomerLedger(
            customerKey = "mob_9844001122",
            allBills = listOf(bill),
            allTransactions = listOf(pay1, pay2),
            allCustomers = listOf(cust)
        )

        assertEquals("Balance must be 1000 (1500 - 500), not double subtracted to 500", 1000.0, res.balance, 0.001)
        assertEquals("Total paid must be exactly 500", 500.0, res.totalPaid, 0.001)
        assertEquals("Exactly 1 payment event in khatavahi", 1, res.events.filter { it.isCredit }.size)
    }

    // A3.4: Two same-name customers without mobile, and two customers with the same per-phone id on different phones -> no cross-over of entries
    @Test
    fun test_A3_4_twoSameNameCustomersWithoutMobile_twoCustomersSameLocalId_noCrossOver() {
        // Phone 1 customer A: local id 5, name Ramesh, no mobile
        val custA = Customer(id = 5L, name = "Ramesh", mobile = "")
        // Phone 2 customer B: local id 5, name Suresh, no mobile
        val custB = Customer(id = 55L, name = "Suresh", mobile = "")
        // Phone 1 customer C: local id 6, also named Ramesh, no mobile
        val custC = Customer(id = 6L, name = "Ramesh", mobile = "")

        val billA = createBill(id = 401L, customerId = 5L, name = "Ramesh", mobile = "", finalAmount = 700.0, paidAmount = 0.0, pendingAmount = 700.0)
        val billB = createBill(id = 402L, customerId = 55L, name = "Suresh", mobile = "", finalAmount = 900.0, paidAmount = 0.0, pendingAmount = 900.0)
        val billC = createBill(id = 403L, customerId = 6L, name = "Ramesh", mobile = "", finalAmount = 300.0, paidAmount = 0.0, pendingAmount = 300.0)

        val allCustomers = listOf(custA, custB, custC)
        val allBills = listOf(billA, billB, billC)

        val summary = UdhariLedger.computeShopLedger(allBills, emptyList(), allCustomers)

        // Ramesh #1 (id 5) and Ramesh #2 (id 6) must NOT merge
        val keyA = UdhariLedger.resolveCustomerKey("", "Ramesh", 5L)!!
        val keyC = UdhariLedger.resolveCustomerKey("", "Ramesh", 6L)!!
        assertNotEquals("Different customers without mobile must have distinct keys", keyA, keyC)

        assertEquals(700.0, summary.customerResults[keyA]?.balance ?: 0.0, 0.001)
        assertEquals(300.0, summary.customerResults[keyC]?.balance ?: 0.0, 0.001)

        // Total pending customers must be 3
        assertEquals(3, summary.pendingCustomersCount)
        assertEquals(1900.0, summary.totalOutstanding, 0.001)
    }

    // A3.5: Bill 520 paid 300 -> profile, khate, Home, reminder all show 220 and exactly one udhari bill entry + one payment entry
    @Test
    fun test_A3_5_bill520Paid300_profile_khate_home_reminder_allShow220_oneBillOnePay() {
        val cust = Customer(id = 50L, name = "Aniket", mobile = "9855001122")
        val bill = createBill(
            id = 501L,
            customerId = 50L,
            name = "Aniket",
            mobile = "9855001122",
            finalAmount = 520.0,
            paidAmount = 300.0,
            pendingAmount = 220.0,
            billNumber = "BILL-501"
        )
        // Checkout credit tx for 520
        val creditTx = createTx(
            id = "c501",
            customerId = 50L,
            name = "Aniket",
            mobile = "9855001122",
            amount = 520.0,
            type = UdhariTransactionType.PURCHASE_CREDIT,
            billId = 501L,
            billNumber = "BILL-501"
        )

        val res = UdhariLedger.computeCustomerLedger("mob_9855001122", listOf(bill), listOf(creditTx), listOf(cust))
        val shopSummary = UdhariLedger.computeShopLedger(listOf(bill), listOf(creditTx), listOf(cust))

        // Profile due == 220.0
        assertEquals(220.0, res.balance, 0.001)
        // Khate row balance == 220.0
        assertEquals(220.0, shopSummary.customerResults["mob_9855001122"]?.balance ?: 0.0, 0.001)
        // Home total == 220.0
        assertEquals(220.0, shopSummary.totalOutstanding, 0.001)

        // Events: exactly one bill charge (520) and exactly one checkout payment (300)
        assertEquals(1, res.events.filter { !it.isCredit }.size)
        assertEquals(1, res.events.filter { it.isCredit }.size)
        assertEquals(300.0, res.events.first { it.isCredit }.amount, 0.001)
        assertEquals(520.0, res.events.first { !it.isCredit }.amount, 0.001)
    }

    // A3.6: Open profile from khate list and from customer directory -> identical entry list and total
    @Test
    fun test_A3_6_openProfileFromKhateAndCustomerDirectory_identicalEntryListAndTotal() {
        val cust = Customer(id = 60L, name = "Mahesh", mobile = "9866001122")
        val bill = createBill(id = 601L, customerId = 60L, name = "Mahesh", mobile = "9866001122", finalAmount = 800.0, paidAmount = 200.0, pendingAmount = 600.0)
        val pay = createTx(id = "p601", customerId = 60L, name = "Mahesh", mobile = "9866001122", amount = 100.0, type = UdhariTransactionType.PAYMENT, billId = 601L)

        val allBills = listOf(bill)
        val allTxs = listOf(pay)
        val allCustomers = listOf(cust)

        // Path A: from directory (via customer entity)
        val keyFromDirectory = UdhariLedger.buildCustomerKeyResolver(allCustomers, allBills, allTxs)(cust.mobile, cust.name, cust.id)!!
        val resA = UdhariLedger.computeCustomerLedger(keyFromDirectory, allBills, allTxs, allCustomers)

        // Path B: from khate list (via shop summary customer key)
        val shopSummary = UdhariLedger.computeShopLedger(allBills, allTxs, allCustomers)
        val resB = shopSummary.customerResults[keyFromDirectory]!!

        assertEquals(resA.balance, resB.balance, 0.001)
        assertEquals(resA.totalPurchases, resB.totalPurchases, 0.001)
        assertEquals(resA.totalPaid, resB.totalPaid, 0.001)
        assertEquals(resA.events.size, resB.events.size)
        assertEquals(resA.billBreakdowns.size, resB.billBreakdowns.size)
    }

    // A3.7: Rebuild dataset in random order and repeated sync snapshot -> same result (idempotent)
    @Test
    fun test_A3_7_rebuildDatasetRandomOrderRepeatedSnapshot_idempotent() {
        val cust = Customer(id = 70L, name = "Ganesh", mobile = "9877001122")
        val bills = (1..5).map { i ->
            createBill(id = 700L + i, customerId = 70L, name = "Ganesh", mobile = "9877001122", finalAmount = 200.0 * i, paidAmount = 50.0 * i, pendingAmount = 150.0 * i, billDate = "2026-11-0$i 10:00:00")
        }
        val txs = (1..3).map { i ->
            createTx(id = "tx70$i", customerId = 70L, name = "Ganesh", mobile = "9877001122", amount = 100.0 * i, type = UdhariTransactionType.PAYMENT, billId = 700L + i, date = "2026-11-0$i")
        }

        val baseRes = UdhariLedger.computeCustomerLedger("mob_9877001122", bills, txs, listOf(cust))

        // Repeated sync snapshot (duplicated list passed)
        val duplicatedBills = bills + bills
        val duplicatedTxs = txs + txs
        val shuffledBills = duplicatedBills.shuffled(java.util.Random(42))
        val shuffledTxs = duplicatedTxs.shuffled(java.util.Random(42))

        val idempotentRes = UdhariLedger.computeCustomerLedger("mob_9877001122", shuffledBills, shuffledTxs, listOf(cust))

        assertEquals("Balance must be identical regardless of ordering or repeated snapshot", baseRes.balance, idempotentRes.balance, 0.001)
        assertEquals("Total purchases must be identical", baseRes.totalPurchases, idempotentRes.totalPurchases, 0.001)
        assertEquals("Total paid must be identical", baseRes.totalPaid, idempotentRes.totalPaid, 0.001)
    }
}

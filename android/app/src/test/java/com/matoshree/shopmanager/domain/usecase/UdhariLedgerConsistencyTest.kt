package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UdhariLedgerConsistencyTest {

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
        billNumber: String = "BILL-$id"
    ): Bill {
        return Bill(
            id = id,
            billNumber = billNumber,
            transactionUuid = "uuid-bill-$id",
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
            customerMobile = mobile
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
        note: String? = null,
        isReversed: Boolean = false,
        date: String = "2026-11-10"
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
            date = date,
            transactionUuid = "uuid-$id",
            note = note,
            isReversed = isReversed
        )
    }

    // 1. Bill 520, paid 300 at checkout, udhari 220 (test both historical shapes)
    @Test
    fun test1_bill520_paid300_bothHistoricalShapes() {
        val cust = Customer(id = 1L, name = "Kajal Salgar", mobile = "9822001122")

        // Shape A: Older shape (PURCHASE_CREDIT = 520, no checkout pay tx)
        val billA = createBill(id = 101L, customerId = 1L, name = "Kajal Salgar", mobile = "9822001122", finalAmount = 520.0, paidAmount = 300.0, pendingAmount = 220.0)
        val creditTxA = createTx(id = "txA", customerId = 1L, name = "Kajal Salgar", mobile = "9822001122", amount = 520.0, type = UdhariTransactionType.PURCHASE_CREDIT, billId = 101L, billNumber = "BILL-101")

        val resA = UdhariLedger.computeCustomerLedger("mob_9822001122", listOf(billA), listOf(creditTxA), listOf(cust))
        assertEquals(220.0, resA.balance, 0.001)
        assertEquals(520.0, resA.totalPurchases, 0.001)
        assertEquals(300.0, resA.totalPaid, 0.001)

        // Shape B: Newer shape (PURCHASE_CREDIT = 520 + checkout pay tx = 300)
        val billB = createBill(id = 102L, customerId = 1L, name = "Kajal Salgar", mobile = "9822001122", finalAmount = 520.0, paidAmount = 300.0, pendingAmount = 220.0)
        val creditTxB = createTx(id = "txB1", customerId = 1L, name = "Kajal Salgar", mobile = "9822001122", amount = 520.0, type = UdhariTransactionType.PURCHASE_CREDIT, billId = 102L, billNumber = "BILL-102")
        val payTxB = createTx(id = "txB2", customerId = 1L, name = "Kajal Salgar", mobile = "9822001122", amount = 300.0, type = UdhariTransactionType.PAYMENT, billId = 102L, billNumber = "BILL-102", note = "Initial payment on checkout")

        val resB = UdhariLedger.computeCustomerLedger("mob_9822001122", listOf(billB), listOf(creditTxB, payTxB), listOf(cust))
        assertEquals(220.0, resB.balance, 0.001)
        assertEquals(520.0, resB.totalPurchases, 0.001)
        assertEquals(300.0, resB.totalPaid, 0.001)
    }

    // 2. Three credit bills, partial payments on two of them, one fully paid later
    @Test
    fun test2_threeCreditBills_partialPayments_oneFullyPaidLater() {
        val cust = Customer(id = 2L, name = "Mahesh", mobile = "9822002233")

        val bill1 = createBill(id = 201L, customerId = 2L, name = "Mahesh", mobile = "9822002233", finalAmount = 1000.0, paidAmount = 200.0, pendingAmount = 800.0, billDate = "2026-11-01 10:00:00")
        val bill2 = createBill(id = 202L, customerId = 2L, name = "Mahesh", mobile = "9822002233", finalAmount = 600.0, paidAmount = 100.0, pendingAmount = 500.0, billDate = "2026-11-02 10:00:00")
        val bill3 = createBill(id = 203L, customerId = 2L, name = "Mahesh", mobile = "9822002233", finalAmount = 400.0, paidAmount = 0.0, pendingAmount = 400.0, billDate = "2026-11-03 10:00:00")

        // Bill 1 fully paid later (800 payment)
        val payBill1 = createTx(id = "p1", customerId = 2L, name = "Mahesh", mobile = "9822002233", amount = 800.0, type = UdhariTransactionType.PAYMENT, billId = 201L, billNumber = "BILL-201")
        // Bill 2 partially paid later (200 payment)
        val payBill2 = createTx(id = "p2", customerId = 2L, name = "Mahesh", mobile = "9822002233", amount = 200.0, type = UdhariTransactionType.PAYMENT, billId = 202L, billNumber = "BILL-202")

        val bills = listOf(bill1, bill2, bill3)
        val txs = listOf(payBill1, payBill2)

        val res = UdhariLedger.computeCustomerLedger("mob_9822002233", bills, txs, listOf(cust))
        // Remaining due: Bill 1: 0, Bill 2: (600 - 100 - 200) = 300, Bill 3: 400 -> Total = 700.0
        assertEquals(700.0, res.balance, 0.001)
        assertEquals(2000.0, res.totalPurchases, 0.001)
        assertEquals(1300.0, res.totalPaid, 0.001) // 200 checkout + 100 checkout + 800 + 200 = 1300
    }

    // 3. Payment recorded on the other phone (different Room customer id, same mobile)
    @Test
    fun test3_paymentRecordedOnOtherPhone_differentRoomId_sameMobile() {
        val custPhoneA = Customer(id = 1L, name = "Sunil", mobile = "9899112233")
        val custPhoneB = Customer(id = 99L, name = "Sunil", mobile = "9899112233")

        val billPhoneA = createBill(id = 301L, customerId = 1L, name = "Sunil", mobile = "9899112233", finalAmount = 1500.0, paidAmount = 500.0, pendingAmount = 1000.0)
        // Payment recorded on Phone B with customerId = 99
        val payPhoneB = createTx(id = "pB", customerId = 99L, name = "Sunil", mobile = "9899112233", amount = 400.0, type = UdhariTransactionType.PAYMENT, billId = 0L)

        val res = UdhariLedger.computeCustomerLedger("mob_9899112233", listOf(billPhoneA), listOf(payPhoneB), listOf(custPhoneA, custPhoneB))
        // Bill remaining due: 1500 - 500 - 400 = 600.0
        assertEquals(600.0, res.balance, 0.001)
        assertEquals(1500.0, res.totalPurchases, 0.001)
        assertEquals(900.0, res.totalPaid, 0.001)
    }

    // 4. Bill later voided / moved to trash with its credit tx still present
    @Test
    fun test4_billVoidedOrTrashed_creditTxRemovedFromLedger() {
        val cust = Customer(id = 4L, name = "Pooja", mobile = "9877001122")
        val voidedBill = createBill(id = 401L, customerId = 4L, name = "Pooja", mobile = "9877001122", finalAmount = 2500.0, paidAmount = 0.0, pendingAmount = 2500.0, isVoided = true)
        val orphanCreditTx = createTx(id = "c401", customerId = 4L, name = "Pooja", mobile = "9877001122", amount = 2500.0, type = UdhariTransactionType.PURCHASE_CREDIT, billId = 401L, billNumber = "BILL-401")

        val res = UdhariLedger.computeCustomerLedger("mob_9877001122", listOf(voidedBill), listOf(orphanCreditTx), listOf(cust))
        assertEquals(0.0, res.balance, 0.001)
        assertEquals(0.0, res.totalPurchases, 0.001)
        assertEquals(0.0, res.totalPaid, 0.001)
    }

    // 5. Reversed payment
    @Test
    fun test5_reversedPayment_notCountedAsPayment() {
        val cust = Customer(id = 5L, name = "Anil", mobile = "9866001122")
        val bill = createBill(id = 501L, customerId = 5L, name = "Anil", mobile = "9866001122", finalAmount = 1000.0, paidAmount = 0.0, pendingAmount = 1000.0)
        val reversedPay = createTx(id = "pRev", customerId = 5L, name = "Anil", mobile = "9866001122", amount = 400.0, type = UdhariTransactionType.PAYMENT, billId = 501L, billNumber = "BILL-501", isReversed = true)

        val res = UdhariLedger.computeCustomerLedger("mob_9866001122", listOf(bill), listOf(reversedPay), listOf(cust))
        assertEquals(1000.0, res.balance, 0.001)
        assertEquals(0.0, res.totalPaid, 0.001)
    }

    // 6. Two customers with same name and different mobiles; customer without mobile; customer renamed
    @Test
    fun test6_sameNameDifferentMobiles_customerWithoutMobile_customerRenamed() {
        val c1 = Customer(id = 61L, name = "Suresh Patil", mobile = "9800000001")
        val c2 = Customer(id = 62L, name = "Suresh Patil", mobile = "9800000002")
        val c3 = Customer(id = 63L, name = "Walkin Cash", mobile = "")
        val c4 = Customer(id = 64L, name = "Vijay Kadam", mobile = "9800000004") // was renamed from "Vijay"

        val bill1 = createBill(id = 601L, customerId = 61L, name = "Suresh Patil", mobile = "9800000001", finalAmount = 500.0, paidAmount = 0.0, pendingAmount = 500.0)
        val bill2 = createBill(id = 602L, customerId = 62L, name = "Suresh Patil", mobile = "9800000002", finalAmount = 700.0, paidAmount = 0.0, pendingAmount = 700.0)
        val bill3 = createBill(id = 603L, customerId = 63L, name = "Walkin Cash", mobile = "", finalAmount = 300.0, paidAmount = 0.0, pendingAmount = 300.0)
        // Bill created under old name "Vijay" but with mobile 9800000004
        val bill4 = createBill(id = 604L, customerId = 64L, name = "Vijay", mobile = "9800000004", finalAmount = 450.0, paidAmount = 0.0, pendingAmount = 450.0)

        val allCustomers = listOf(c1, c2, c3, c4)
        val allBills = listOf(bill1, bill2, bill3, bill4)
        val shopSummary = UdhariLedger.computeShopLedger(allBills, emptyList(), allCustomers)

        // c1 and c2 must NEVER be merged
        assertEquals(500.0, shopSummary.customerResults["mob_9800000001"]?.balance ?: 0.0, 0.001)
        assertEquals(700.0, shopSummary.customerResults["mob_9800000002"]?.balance ?: 0.0, 0.001)

        // c3 resolved by name
        assertEquals(300.0, shopSummary.customerResults["name_walkin cash"]?.balance ?: 0.0, 0.001)

        // c4 resolved by mobile despite rename
        assertEquals(450.0, shopSummary.customerResults["mob_9800000004"]?.balance ?: 0.0, 0.001)
    }

    // 7. Unallocated payment (billId 0) -> FIFO allocation
    @Test
    fun test7_unallocatedPayment_fifoAllocation() {
        val cust = Customer(id = 7L, name = "Ganesh", mobile = "9844001122")
        val bill1 = createBill(id = 701L, customerId = 7L, name = "Ganesh", mobile = "9844001122", finalAmount = 500.0, paidAmount = 0.0, pendingAmount = 500.0, billDate = "2026-11-01 10:00:00")
        val bill2 = createBill(id = 702L, customerId = 7L, name = "Ganesh", mobile = "9844001122", finalAmount = 800.0, paidAmount = 0.0, pendingAmount = 800.0, billDate = "2026-11-05 10:00:00")

        // Unallocated payment of 600
        val unallocatedPay = createTx(id = "p7", customerId = 7L, name = "Ganesh", mobile = "9844001122", amount = 600.0, type = UdhariTransactionType.PAYMENT, billId = 0L)

        val res = UdhariLedger.computeCustomerLedger("mob_9844001122", listOf(bill1, bill2), listOf(unallocatedPay), listOf(cust))
        // Bill 1: 500 - 500 = 0
        // Bill 2: 800 - 100 = 700
        assertEquals(700.0, res.balance, 0.001)
        val b1Breakdown = res.billBreakdowns.find { it.billId == 701L }!!
        val b2Breakdown = res.billBreakdowns.find { it.billId == 702L }!!
        assertEquals(0.0, b1Breakdown.remainingOutstanding, 0.001)
        assertEquals(700.0, b2Breakdown.remainingOutstanding, 0.001)
    }

    // 8. Overpayment -> shown as advance, not silently 0
    @Test
    fun test8_overpayment_shownAsAdvance() {
        val cust = Customer(id = 8L, name = "Tanaji", mobile = "9833001122")
        val bill = createBill(id = 801L, customerId = 8L, name = "Tanaji", mobile = "9833001122", finalAmount = 500.0, paidAmount = 0.0, pendingAmount = 500.0)
        val overPay = createTx(id = "p8", customerId = 8L, name = "Tanaji", mobile = "9833001122", amount = 600.0, type = UdhariTransactionType.PAYMENT, billId = 0L)

        val res = UdhariLedger.computeCustomerLedger("mob_9833001122", listOf(bill), listOf(overPay), listOf(cust))
        // Customer has advance of 100 (balance = -100.0)
        assertEquals(-100.0, res.balance, 0.001)
        assertEquals(500.0, res.totalPurchases, 0.001)
        assertEquals(600.0, res.totalPaid, 0.001)
    }

    // 9. Lifetime credit 50,000, paid 49,000 -> khate shows 1,000 (and lifetime/paid shown separately)
    @Test
    fun test9_lifetimeCredit50000_paid49000_balance1000() {
        val cust = Customer(id = 9L, name = "Vikram", mobile = "9822998877")
        val bills = (1..10).map { i ->
            createBill(id = 900L + i, customerId = 9L, name = "Vikram", mobile = "9822998877", finalAmount = 5000.0, paidAmount = 0.0, pendingAmount = 5000.0, billDate = "2026-11-0$i 10:00:00")
        }
        val payTx = createTx(id = "p9", customerId = 9L, name = "Vikram", mobile = "9822998877", amount = 49000.0, type = UdhariTransactionType.PAYMENT, billId = 0L)

        val res = UdhariLedger.computeCustomerLedger("mob_9822998877", bills, listOf(payTx), listOf(cust))
        assertEquals(1000.0, res.balance, 0.001)
        assertEquals(50000.0, res.totalPurchases, 0.001)
        assertEquals(49000.0, res.totalPaid, 0.001)
    }

    // 10. 12,000 bills (limit regression), cold start & total consistency
    @Test
    fun test10_twelveThousandBills_noLimitRegression_andShopWideConsistency() {
        val cust1 = Customer(id = 1L, name = "Kajal", mobile = "9822001122")
        val cust2 = Customer(id = 2L, name = "Rahul", mobile = "9822003344")

        // 12,000 paid cash bills + 2 pending bills
        val largeBillsList = (1..12000).map { i ->
            createBill(id = i.toLong(), customerId = 1L, name = "Kajal", mobile = "9822001122", finalAmount = 100.0, paidAmount = 100.0, pendingAmount = 0.0, status = PaymentStatus.PAID)
        }.toMutableList()

        val pendingBill1 = createBill(id = 12001L, customerId = 1L, name = "Kajal", mobile = "9822001122", finalAmount = 520.0, paidAmount = 300.0, pendingAmount = 220.0)
        val pendingBill2 = createBill(id = 12002L, customerId = 2L, name = "Rahul", mobile = "9822003344", finalAmount = 1000.0, paidAmount = 0.0, pendingAmount = 1000.0)

        largeBillsList.add(pendingBill1)
        largeBillsList.add(pendingBill2)

        val summary = UdhariLedger.computeShopLedger(largeBillsList, emptyList(), listOf(cust1, cust2))

        // Assert no window truncating: totalOutstanding is exactly 220 + 1000 = 1220.0
        assertEquals(1220.0, summary.totalOutstanding, 0.001)
        assertEquals(2, summary.pendingCustomersCount)
        assertEquals(1220.0, summary.customerResults.values.filter { it.balance > 0.01 }.sumOf { it.balance }, 0.001)
    }

    // Verification of Room migration safety: no fallbackToDestructiveMigration
    @Test
    fun test_noFallbackToDestructiveMigrationInCodebase() {
        val projectRoot = File(System.getProperty("user.dir") ?: ".")
        val srcMain = File(projectRoot, "android/app/src/main/java")
        val files = srcMain.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }

        val occurrences = mutableListOf<String>()
        for (f in files) {
            val content = f.readText()
            if (content.contains("fallbackToDestructiveMigration")) {
                occurrences.add(f.absolutePath)
            }
        }

        assertTrue("fallbackToDestructiveMigration must NEVER be used: $occurrences", occurrences.isEmpty())
    }
}

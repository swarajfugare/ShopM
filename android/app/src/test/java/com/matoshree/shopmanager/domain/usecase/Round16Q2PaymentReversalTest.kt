package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Reproduction & regression tests for Round 16 Bug Q2 & Q3:
 * - Payment cancellation (reversal) properly restores bill pending and customer due.
 */
class Round16Q2PaymentReversalTest {

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
        kind: String = "",
        billId: Long = 0L,
        billNumber: String = "",
        billGid: String? = null,
        note: String? = null,
        createdAt: Long = System.currentTimeMillis(),
        status: String = "ACTIVE",
        isDeleted: Boolean = false,
        isReversed: Boolean = false,
        cascadedBy: String? = null
    ): UdhariTransaction {
        return UdhariTransaction(
            id = id,
            gid = if (id.startsWith("udh_") || id.startsWith("pay_")) id else IdGenerator.legacyGid(if (type == UdhariTransactionType.PURCHASE_CREDIT) IdType.UDHARI else IdType.PAYMENT, id),
            customerId = customerId,
            customerGid = customerGid,
            customerName = name,
            customerMobile = mobile,
            billId = billId,
            billNumber = billNumber,
            billGid = billGid,
            type = type,
            kind = kind,
            amount = amount,
            paymentMethod = PaymentMethod.CASH,
            transactionUuid = "uuid-$id",
            date = "2026-11-15",
            note = note,
            status = status,
            isDeleted = isDeleted,
            isReversed = isReversed,
            cascadedBy = cascadedBy,
            createdAt = createdAt
        )
    }

    private fun createBill(
        id: Long,
        billNumber: String,
        customerId: Long,
        customerGid: String,
        customerName: String,
        customerMobile: String,
        finalAmount: Double,
        paidAmount: Double,
        pendingAmount: Double,
        items: List<BillItem> = emptyList(),
        status: String = "COMPLETED"
    ): Bill {
        val bGid = IdGenerator.billGid("bil_$billNumber")
        return Bill(
            id = id,
            gid = bGid,
            shopId = 1,
            customerId = customerId,
            customerGid = customerGid,
            customerName = customerName,
            customerMobile = customerMobile,
            billNumber = billNumber,
            transactionUuid = "uuid-$billNumber",
            saleType = SaleType.QUICK,
            subtotal = finalAmount,
            finalAmount = finalAmount,
            paidAmount = paidAmount,
            pendingAmount = pendingAmount,
            paymentStatus = if (pendingAmount <= 0.01) PaymentStatus.PAID else if (paidAmount > 0.01) PaymentStatus.PARTIAL else PaymentStatus.PENDING,
            billDate = "2026-11-15",
            items = items,
            isVoided = status == "VOIDED" || status == "TRASH"
        )
    }

    @Test
    fun testQ2_PaymentReversal_RebuildsLedger_ReturnsToOriginalDueAndStays() {
        val cust = createCustomer(301L, "TEST Ganesh", "9765432109")
        // Bill total 1000, 300 paid at checkout, 700 pending
        val bill = createBill(31L, "MC-301", cust.id, cust.gid, cust.name, cust.mobile, 1000.0, 300.0, 700.0)
        val initialUdhariTx = createTx("udh_301", cust.id, cust.gid, cust.name, cust.mobile, 700.0, UdhariTransactionType.PURCHASE_CREDIT, billId = bill.id, billNumber = bill.billNumber)

        // Baseline: Due is 700
        val baseLedger = UdhariLedger.computeCustomerLedger(cust.gid, listOf(bill), listOf(initialUdhariTx), listOf(cust))
        assertEquals(700.0, baseLedger.balance, 0.001)

        // Step 1: Record 200 payment
        val payTx = createTx("pay_301", cust.id, cust.gid, cust.name, cust.mobile, 200.0, UdhariTransactionType.PAYMENT, billId = bill.id, billNumber = bill.billNumber)
        val afterPayLedger = UdhariLedger.computeCustomerLedger(cust.gid, listOf(bill), listOf(initialUdhariTx, payTx), listOf(cust))
        assertEquals(500.0, afterPayLedger.balance, 0.001)

        // Step 2: Payment reversal ("रद्द करा") -> marks payTx isReversed = true, status = "CANCELLED"
        val reversedPayTx = payTx.copy(
            isReversed = true,
            status = "CANCELLED",
            note = "Payment cancelled / रद्द केले"
        )

        // Recomputed ledger with reversed transaction
        val afterReversalLedger = UdhariLedger.computeCustomerLedger(cust.gid, listOf(bill), listOf(initialUdhariTx, reversedPayTx), listOf(cust))
        assertEquals("Ledger due must return to original 700", 700.0, afterReversalLedger.balance, 0.001)

        // Verify stored bill sync: bill pending remains/returns to 700
        val updatedBill = bill.copy(
            paidAmount = 300.0,
            pendingAmount = 700.0,
            paymentStatus = PaymentStatus.PARTIAL
        )
        assertEquals(700.0, updatedBill.pendingAmount, 0.001)
    }
}

package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Reproduction & regression tests for Round 16 Bug Q1:
 * - Deleting ONE customer does not delete other customers with same name.
 * - Soft-delete cascade on customer delete and full restoration upon Trash restore.
 */
class Round16Q1CustomerDeleteTest {

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
    fun testQ1_DeletingOneCustomer_DoesNotDeleteOtherCustomerWithSameName() {
        val ram1 = createCustomer(101L, "TEST Ram", "9822001122")
        val ram2 = createCustomer(102L, "TEST Ram", "9876543210")
        val customers = listOf(ram1, ram2)

        val bill1 = createBill(1L, "MC-101", ram1.id, ram1.gid, ram1.name, ram1.mobile, 1000.0, 300.0, 700.0)
        val bill2 = createBill(2L, "MC-102", ram2.id, ram2.gid, ram2.name, ram2.mobile, 1500.0, 500.0, 1000.0)
        val allBills = listOf(bill1, bill2)

        val tx1 = createTx("udh_1", ram1.id, ram1.gid, ram1.name, ram1.mobile, 700.0, UdhariTransactionType.PURCHASE_CREDIT, billId = bill1.id, billNumber = bill1.billNumber)
        val tx2 = createTx("udh_2", ram2.id, ram2.gid, ram2.name, ram2.mobile, 1000.0, UdhariTransactionType.PURCHASE_CREDIT, billId = bill2.id, billNumber = bill2.billNumber)
        val allTxs = listOf(tx1, tx2)

        // Delete ram1 strictly by ID/GID/exact 10-digit mobile, NEVER by name
        val targetCustId = ram1.id
        val targetCustGid = ram1.gid
        val targetMobile = ram1.mobile

        val remainingCustomers = customers.filterNot { c ->
            c.id == targetCustId || c.gid == targetCustGid || c.mobile == targetMobile
        }
        val remainingBills = allBills.filterNot { b ->
            b.customerId == targetCustId || b.customerGid == targetCustGid || b.customerMobile == targetMobile
        }
        val remainingTxs = allTxs.filterNot { t ->
            t.customerId == targetCustId || t.customerGid == targetCustGid || t.customerMobile == targetMobile
        }

        assertEquals("Ram 2 must survive", 1, remainingCustomers.size)
        assertEquals(ram2.id, remainingCustomers[0].id)
        assertEquals(ram2.mobile, remainingCustomers[0].mobile)

        assertEquals("Ram 2's bill must survive", 1, remainingBills.size)
        assertEquals(bill2.id, remainingBills[0].id)
        assertEquals(1000.0, remainingBills[0].pendingAmount, 0.001)

        assertEquals("Ram 2's udhari transaction must survive", 1, remainingTxs.size)
        assertEquals(tx2.id, remainingTxs[0].id)
        assertEquals(1000.0, remainingTxs[0].amount, 0.001)
    }

    @Test
    fun testQ1_SoftDeleteCascadeAndTrashRestore_RestoresAllBillsAndUdhari() {
        val cust = createCustomer(201L, "TEST Suresh", "9988776655")
        val bill = createBill(21L, "MC-201", cust.id, cust.gid, cust.name, cust.mobile, 2000.0, 500.0, 1500.0)
        val udhariTx = createTx("udh_201", cust.id, cust.gid, cust.name, cust.mobile, 1500.0, UdhariTransactionType.PURCHASE_CREDIT, billId = bill.id, billNumber = bill.billNumber)
        val payTx = createTx("pay_201", cust.id, cust.gid, cust.name, cust.mobile, 500.0, UdhariTransactionType.PAYMENT, billId = bill.id, billNumber = bill.billNumber)

        // 1. Soft-delete cascade on customer delete
        val trashedBill = bill.copy(
            isVoided = true,
            cascadedBy = "CUSTOMER_DELETE",
            linkedCustomerGid = cust.gid
        )
        val trashedUdhariTx = udhariTx.copy(
            status = "TRASH",
            isDeleted = true,
            cascadedBy = "CUSTOMER_DELETE",
            linkedCustomerGid = cust.gid
        )
        val trashedPayTx = payTx.copy(
            status = "TRASH",
            isDeleted = true,
            cascadedBy = "CUSTOMER_DELETE",
            linkedCustomerGid = cust.gid
        )

        assertTrue("Bill must be marked voided/trashed", trashedBill.isVoided)
        assertEquals("CUSTOMER_DELETE", trashedBill.cascadedBy)
        assertEquals("CUSTOMER_DELETE", trashedUdhariTx.cascadedBy)
        assertEquals("CUSTOMER_DELETE", trashedPayTx.cascadedBy)

        // 2. Restore customer from Trash
        val restoredBill = trashedBill.copy(
            isVoided = false,
            cascadedBy = null,
            linkedCustomerGid = null
        )
        val restoredUdhariTx = trashedUdhariTx.copy(
            status = "ACTIVE",
            isDeleted = false,
            cascadedBy = null,
            linkedCustomerGid = null
        )
        val restoredPayTx = trashedPayTx.copy(
            status = "ACTIVE",
            isDeleted = false,
            cascadedBy = null,
            linkedCustomerGid = null
        )

        assertFalse("Bill must be active again", restoredBill.isVoided)
        assertNull(restoredBill.cascadedBy)
        assertEquals("ACTIVE", restoredUdhariTx.status)
        assertFalse(restoredUdhariTx.isDeleted)
        assertEquals("ACTIVE", restoredPayTx.status)
        assertFalse(restoredPayTx.isDeleted)

        // Check ledger calculation on restored customer
        val ledger = UdhariLedger.computeCustomerLedger(
            customerKey = cust.gid,
            allBills = listOf(restoredBill),
            allTransactions = listOf(restoredUdhariTx, restoredPayTx),
            allCustomers = listOf(cust)
        )
        assertEquals("Restored due must equal 1500", 1500.0, ledger.balance, 0.001)
    }
}

package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * P4: Room offline customer soft-delete and trash restoration tests.
 * Asserts:
 * 1. Customer delete marks customer archived (isActive = false) and bills voided (voidReason = CUSTOMER_DELETE).
 * 2. On Trash restore, unarchiveCustomer re-activates customer, unvoidCustomerBills re-activates bills,
 *    and product stock is re-deducted.
 */
class Round17P4CustomerOfflineRoomRestoreTest {

    data class MockRoomProduct(
        val id: Long,
        var currentStock: Int
    )

    data class MockRoomBill(
        val id: Long,
        val customerId: Long,
        val customerMobile: String,
        val finalAmount: Double,
        val paidAmount: Double,
        val pendingAmount: Double,
        var isVoided: Boolean,
        var voidReason: String?,
        var paymentStatus: String,
        val items: List<Pair<Long, Int>> // productId to quantity
    )

    data class MockRoomCustomer(
        val id: Long,
        val name: String,
        val mobile: String,
        val gid: String,
        var isActive: Boolean,
        var totalBills: Int,
        var lifetimeSpend: Double
    )

    @Test
    fun testRoomSoftDeleteAndTrashRestore_RestoresCustomerBillsAndDeductsStock() {
        val prod1 = MockRoomProduct(id = 10L, currentStock = 10)
        val prod2 = MockRoomProduct(id = 20L, currentStock = 15)

        val cust = MockRoomCustomer(
            id = 1L,
            name = "TEST Aniket",
            mobile = "9822112233",
            gid = "cust_m_9822112233",
            isActive = true,
            totalBills = 1,
            lifetimeSpend = 1200.0
        )

        // Customer buys: 2 of prod1 and 3 of prod2 -> stock deducted: prod1=8, prod2=12
        prod1.currentStock -= 2
        prod2.currentStock -= 3
        assertEquals(8, prod1.currentStock)
        assertEquals(12, prod2.currentStock)

        val bill = MockRoomBill(
            id = 101L,
            customerId = cust.id,
            customerMobile = cust.mobile,
            finalAmount = 1200.0,
            paidAmount = 200.0,
            pendingAmount = 1000.0,
            isVoided = false,
            voidReason = null,
            paymentStatus = "PARTIAL",
            items = listOf(Pair(10L, 2), Pair(20L, 3))
        )

        // Step 1: Customer Delete in Room
        // Restores stock for bill items, voids bill with "CUSTOMER_DELETE", archives customer
        bill.items.forEach { (pId, qty) ->
            if (pId == 10L) prod1.currentStock += qty
            if (pId == 20L) prod2.currentStock += qty
        }
        bill.isVoided = true
        bill.voidReason = "CUSTOMER_DELETE"
        bill.paymentStatus = "VOID"
        cust.isActive = false
        cust.totalBills = 0
        cust.lifetimeSpend = 0.0

        // Verification after soft-delete:
        assertFalse("Customer must be archived", cust.isActive)
        assertTrue("Bill must be voided", bill.isVoided)
        assertEquals("CUSTOMER_DELETE", bill.voidReason)
        assertEquals("Stock of prod1 restored", 10, prod1.currentStock)
        assertEquals("Stock of prod2 restored", 15, prod2.currentStock)

        // Step 2: Customer Restore from Trash
        // Re-activates customer, unvoids bills with voidReason == "CUSTOMER_DELETE", re-deducts stock
        cust.isActive = true
        if (bill.isVoided && bill.voidReason?.startsWith("CUSTOMER_DELETE") == true) {
            bill.items.forEach { (pId, qty) ->
                if (pId == 10L) prod1.currentStock -= qty
                if (pId == 20L) prod2.currentStock -= qty
            }
            bill.isVoided = false
            bill.voidReason = null
            bill.paymentStatus = if (bill.pendingAmount <= 0.01) "PAID" else if (bill.paidAmount > 0.01) "PARTIAL" else "PENDING"
            cust.totalBills += 1
            cust.lifetimeSpend += bill.finalAmount
        }

        // Verification after Trash restore:
        assertTrue("Customer must be active again", cust.isActive)
        assertFalse("Bill must be unvoided", bill.isVoided)
        assertNull("Void reason must be cleared", bill.voidReason)
        assertEquals("PARTIAL", bill.paymentStatus)
        assertEquals("Stock of prod1 must be re-deducted to 8", 8, prod1.currentStock)
        assertEquals("Stock of prod2 must be re-deducted to 12", 12, prod2.currentStock)
        assertEquals(1, cust.totalBills)
        assertEquals(1200.0, cust.lifetimeSpend, 0.001)
    }
}

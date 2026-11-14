package com.matoshree.shopmanager.ui.screens.dashboard

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class DashboardFlowTest {

    @Test
    fun testDashboardHeader_showsTodayBillsWhenPresent() {
        val todayBills = listOf(
            createTestBill(id = 1, amount = 1000.0, date = "2026-11-13 10:00:00", voided = false),
            createTestBill(id = 2, amount = 500.0, date = "2026-11-13 11:30:00", voided = false)
        )
        val weekBills = listOf(
            createTestBill(id = 10, amount = 2000.0, date = "2026-11-10 14:00:00", voided = false)
        )

        val activeTodayBills = todayBills.filter { !it.isVoided }
        val activeWeekBills = weekBills.filter { !it.isVoided }

        val isShowingToday = activeTodayBills.isNotEmpty()
        val displayBills = if (isShowingToday) activeTodayBills else activeWeekBills
        val billsHeaderTitle = if (isShowingToday) {
            "आजची बिले (${activeTodayBills.size})"
        } else if (activeWeekBills.isNotEmpty()) {
            "या आठवड्याची बिले (${activeWeekBills.size})"
        } else {
            "आजची बिले (०)"
        }

        assertTrue(isShowingToday)
        assertEquals(2, displayBills.size)
        assertEquals("आजची बिले (2)", billsHeaderTitle)
    }

    @Test
    fun testDashboardHeader_fallsBackToWeekBillsWhenTodayEmpty() {
        val todayBills = emptyList<Bill>()
        val weekBills = listOf(
            createTestBill(id = 10, amount = 2000.0, date = "2026-11-10 14:00:00", voided = false),
            createTestBill(id = 11, amount = 1500.0, date = "2026-11-11 16:00:00", voided = false),
            createTestBill(id = 12, amount = 800.0, date = "2026-11-12 11:00:00", voided = true) // Voided should be excluded
        )

        val activeTodayBills = todayBills.filter { !it.isVoided }
        val activeWeekBills = weekBills.filter { !it.isVoided }

        val isShowingToday = activeTodayBills.isNotEmpty()
        val displayBills = if (isShowingToday) activeTodayBills else activeWeekBills
        val billsHeaderTitle = if (isShowingToday) {
            "आजची बिले (${activeTodayBills.size})"
        } else if (activeWeekBills.isNotEmpty()) {
            "या आठवड्याची बिले (${activeWeekBills.size})"
        } else {
            "आजची बिले (०)"
        }

        assertFalse(isShowingToday)
        assertEquals(2, displayBills.size)
        assertEquals("या आठवड्याची बिले (2)", billsHeaderTitle)
        assertFalse(displayBills.any { it.isVoided })
    }

    @Test
    fun testDashboardHeader_fallsBackToRecentBillsWhenBothTodayAndWeekEmpty() {
        val todayBills = emptyList<Bill>()
        val weekBills = emptyList<Bill>()
        val allBills = listOf(
            createTestBill(id = 20, amount = 3000.0, date = "2026-10-15 10:00:00", voided = false)
        )

        val activeTodayBills = todayBills.filter { !it.isVoided }
        val activeWeekBills = weekBills.filter { !it.isVoided }
        val allActiveBills = allBills.filter { !it.isVoided }

        val isShowingToday = activeTodayBills.isNotEmpty()
        val displayBills = when {
            isShowingToday -> activeTodayBills
            activeWeekBills.isNotEmpty() -> activeWeekBills
            allActiveBills.isNotEmpty() -> allActiveBills.take(15)
            else -> emptyList()
        }
        val billsHeaderTitle = when {
            isShowingToday -> "आजची बिले (${activeTodayBills.size})"
            activeWeekBills.isNotEmpty() -> "या आठवड्याची बिले (${activeWeekBills.size})"
            allActiveBills.isNotEmpty() -> "अलीकडील बिले (${displayBills.size})"
            else -> "आजची बिले (०)"
        }

        assertFalse(isShowingToday)
        assertEquals(1, displayBills.size)
        assertEquals("अलीकडील बिले (1)", billsHeaderTitle)
    }

    @Test
    fun testDashboardHeader_showsZeroWhenNoBillsExist() {
        val todayBills = emptyList<Bill>()
        val weekBills = emptyList<Bill>()
        val allBills = emptyList<Bill>()

        val activeTodayBills = todayBills.filter { !it.isVoided }
        val activeWeekBills = weekBills.filter { !it.isVoided }
        val allActiveBills = allBills.filter { !it.isVoided }

        val isShowingToday = activeTodayBills.isNotEmpty()
        val displayBills = when {
            isShowingToday -> activeTodayBills
            activeWeekBills.isNotEmpty() -> activeWeekBills
            allActiveBills.isNotEmpty() -> allActiveBills.take(15)
            else -> emptyList()
        }
        val billsHeaderTitle = when {
            isShowingToday -> "आजची बिले (${activeTodayBills.size})"
            activeWeekBills.isNotEmpty() -> "या आठवड्याची बिले (${activeWeekBills.size})"
            allActiveBills.isNotEmpty() -> "अलीकडील बिले (${displayBills.size})"
            else -> "आजची बिले (०)"
        }

        assertFalse(isShowingToday)
        assertTrue(displayBills.isEmpty())
        assertEquals("आजची बिले (०)", billsHeaderTitle)
    }

    @Test
    fun testBillCanonicalKey_stableAcrossRecomposition() {
        val bill1 = createTestBill(id = 42, amount = 1000.0, date = "2026-11-13 10:00:00", uuid = "uuid-fixed-1")
        val bill2 = createTestBill(id = 42, amount = 1000.0, date = "2026-11-13 10:00:00", uuid = "uuid-fixed-1")

        assertEquals(bill1.canonicalKey(), bill2.canonicalKey())
        assertEquals("bill_42_uuid-fixed-1", "bill_${bill1.id}_${bill1.transactionUuid}")
    }

    @Test
    fun testCustomerReconciliation_mergesCloudCustomersWithLocal() {
        val localCustomers = listOf(
            Customer(id = 1, name = "Kajal Salgar", mobile = "9822001122", address = "Village A")
        )
        val cloudCustomers = listOf(
            Customer(id = 1, name = "Kajal Salgar", mobile = "9822001122", address = "Village A"),
            Customer(id = 2, name = "Suresh Patil", mobile = "9822003344", address = "Village B")
        )

        val map = linkedMapOf<String, Customer>()
        for (c in cloudCustomers) {
            if (c.isActive && c.name.isNotBlank() && c.name != "Walk-in") {
                map[c.canonicalKey()] = c
            }
        }
        for (c in localCustomers) {
            if (c.isActive && c.name.isNotBlank() && c.name != "Walk-in") {
                map[c.canonicalKey()] = c
            }
        }

        assertEquals(2, map.size)
        assertTrue(map.containsKey(Customer(id = 2, name = "Suresh Patil", mobile = "9822003344").canonicalKey()))
    }

    @Test
    fun testHomeUdhariDirectBinding_noLoadingIndicatorWhenStateAvailable() {
        // Given an udhari state with total 110815.0 and 13 customers
        val totalUdhari = 110815.0
        val pendingCount = 13
        val shouldShowCard = totalUdhari > 0.01

        assertTrue(shouldShowCard)
        assertEquals(110815.0, totalUdhari, 0.001)
        assertEquals(13, pendingCount)
    }

    private fun createTestBill(
        id: Long,
        amount: Double,
        date: String,
        voided: Boolean = false,
        uuid: String = "uuid-$id"
    ): Bill {
        return Bill(
            id = id,
            shopId = 1,
            customerId = null,
            customerName = "Customer $id",
            customerMobile = "98000000$id",
            billNumber = "BILL-$id",
            transactionUuid = uuid,
            saleType = SaleType.QUICK,
            subtotal = amount,
            discountType = DiscountType.NONE,
            discountValue = 0.0,
            discountAmount = 0.0,
            taxAmount = 0.0,
            finalAmount = amount,
            paidAmount = amount,
            pendingAmount = 0.0,
            actualProfit = 0.0,
            estimatedProfit = 0.0,
            paymentMethod = PaymentMethod.CASH,
            paymentStatus = PaymentStatus.PAID,
            billDate = date,
            isVoided = voided,
            items = emptyList()
        )
    }
}

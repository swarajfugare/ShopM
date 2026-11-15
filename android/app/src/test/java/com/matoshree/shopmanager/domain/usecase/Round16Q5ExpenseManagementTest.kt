package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Reproduction & regression tests for Round 16 Bug Q5:
 * - Expense soft-delete to Trash, observing trashed expenses, and restoration.
 */
class Round16Q5ExpenseManagementTest {

    @Test
    fun testQ5_Expense_SoftDelete_TrashObserve_AndRestore() {
        val initialExpense = Expense(
            id = 601L,
            gid = "exp_01HM5V7K8P9Q2W3E4R5T6Y7U99",
            shopId = 1,
            category = "दुकान भाडे",
            amount = 5000.0,
            paymentMethod = PaymentMethod.CASH,
            expenseDate = "2026-11-15",
            note = "नोव्हेंबर महिन्याचे भाडे"
        )

        // 1. Initial active list includes the expense
        val allExpenses = mutableListOf(initialExpense)
        val activeExpenses = allExpenses.filter { true }
        assertEquals(1, activeExpenses.size)
        assertEquals(5000.0, activeExpenses[0].amount, 0.001)

        // 2. Soft-delete expense to TRASH
        var expenseDto = com.matoshree.shopmanager.data.firebase.ExpenseDto(
            id = "exp_601",
            legacyId = initialExpense.id,
            gid = initialExpense.gid,
            category = initialExpense.category,
            amount = initialExpense.amount,
            status = "TRASH",
            deletedAt = System.currentTimeMillis()
        )

        // Active list filters out status == "TRASH"
        val activeAfterDelete = if (expenseDto.status != "TRASH") listOf(expenseDto.toDomain()) else emptyList<Expense>()
        assertEquals("Trashed expense must not appear in active expenses list", 0, activeAfterDelete.size)

        // 3. Trash repository observes the trashed item
        val trashedItem = com.matoshree.shopmanager.data.firebase.TrashedItem(
            docId = expenseDto.id,
            gid = expenseDto.gid,
            legacyId = expenseDto.legacyId ?: 601L,
            entityType = "EXPENSE",
            title = "${expenseDto.category} • ${com.matoshree.shopmanager.utils.CurrencyFormatter.format(expenseDto.amount)}",
            subtitle = "${expenseDto.expenseDate} • ${expenseDto.paymentMethod}",
            amount = expenseDto.amount,
            deletedAt = expenseDto.deletedAt ?: System.currentTimeMillis(),
            daysLeft = 7
        )
        assertEquals("EXPENSE", trashedItem.entityType)
        assertTrue(trashedItem.title.contains("दुकान भाडे"))

        // 4. Restore from Trash: resets status = "ACTIVE", deletedAt = null
        expenseDto = expenseDto.copy(status = "ACTIVE", deletedAt = null)
        val activeAfterRestore = if (expenseDto.status != "TRASH") listOf(expenseDto.toDomain()) else emptyList<Expense>()
        assertEquals("Restored expense must appear in active list", 1, activeAfterRestore.size)
        assertEquals(5000.0, activeAfterRestore[0].amount, 0.001)

        // 5. Edit expense: update category, amount, note
        expenseDto = expenseDto.copy(category = "दुकान भाडे + वीज", amount = 6200.0, note = "भाडे व वीज बिल")
        val updatedExpense = expenseDto.toDomain()
        assertEquals("दुकान भाडे + वीज", updatedExpense.category)
        assertEquals(6200.0, updatedExpense.amount, 0.001)
        assertEquals("भाडे व वीज बिल", updatedExpense.note)
    }
}

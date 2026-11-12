package com.matoshree.shopmanager.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyFormatterTest {

    @Test
    fun `test format whole amounts`() {
        val formatted = CurrencyFormatter.format(18450.0)
        assertTrue(formatted.contains("18,450") || formatted.contains("18450"))
        assertTrue(formatted.startsWith("₹"))
    }

    @Test
    fun `test format large Indian amounts`() {
        val formatted = CurrencyFormatter.format(125500.0)
        assertTrue(formatted.startsWith("₹"))
    }

    @Test
    fun `test parse formatted rupee strings`() {
        val parsed = CurrencyFormatter.parse("₹18,450")
        assertEquals(18450.0, parsed, 0.01)
    }
}

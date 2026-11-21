package com.matoshree.shopmanager.invoicescan

import com.matoshree.shopmanager.domain.model.Category
import com.matoshree.shopmanager.domain.usecase.CategoryMatcher
import org.junit.Assert.*
import org.junit.Test

class CategoryMatcherTest {

    private val existing = listOf(
        Category(id = 1, name = "Shirts"),
        Category(id = 2, name = "Pants"),
        Category(id = 3, name = "Sarees"),
        Category(id = 4, name = "कुर्ती"),
        Category(id = 5, name = "Kids Wear")
    )

    @Test
    fun testExactMatch() {
        val result = CategoryMatcher.matchCategory("Shirts", existing)
        assertFalse(result.isNew)
        assertEquals("Shirts", result.effectiveName)
        assertEquals(1L, result.matchedCategory?.id)
    }

    @Test
    fun testCaseAndSpacingInsensitive() {
        val result = CategoryMatcher.matchCategory("  sHirTs  ", existing)
        assertFalse(result.isNew)
        assertEquals("Shirts", result.effectiveName)
        assertEquals(1L, result.matchedCategory?.id)
    }

    @Test
    fun testPluralVsSingular() {
        // Input singular "Shirt", existing has "Shirts"
        val result = CategoryMatcher.matchCategory("Shirt", existing)
        assertFalse(result.isNew)
        assertEquals("Shirts", result.effectiveName)
    }

    @Test
    fun testMarathiToEnglishEquivalence() {
        // "शर्ट" maps to existing "Shirts"
        val result = CategoryMatcher.matchCategory("शर्ट", existing)
        assertFalse(result.isNew)
        assertEquals("Shirts", result.effectiveName)

        // "साडी" maps to existing "Sarees"
        val resultSaree = CategoryMatcher.matchCategory("साडी", existing)
        assertFalse(resultSaree.isNew)
        assertEquals("Sarees", resultSaree.effectiveName)
    }

    @Test
    fun testEnglishToMarathiEquivalence() {
        // "Kurti" maps to existing Marathi "कुर्ती"
        val result = CategoryMatcher.matchCategory("Kurti", existing)
        assertFalse(result.isNew)
        assertEquals("कुर्ती", result.effectiveName)
    }

    @Test
    fun testNearMissMustNotMatch() {
        // "T-Shirts" must NOT match "Shirts"
        val result = CategoryMatcher.matchCategory("T-Shirts", existing)
        assertTrue(result.isNew)
        assertEquals("T-Shirts", result.effectiveName)
        assertNull(result.matchedCategory)
    }

    @Test
    fun testTwoRowsNormalizeToSingleNewCategory() {
        val row1Cat = CategoryMatcher.matchCategory("Night Dress", existing)
        val row2Cat = CategoryMatcher.matchCategory("night dress", existing)

        assertTrue(row1Cat.isNew)
        assertTrue(row2Cat.isNew)
        assertEquals(CategoryMatcher.normalize(row1Cat.effectiveName), CategoryMatcher.normalize(row2Cat.effectiveName))
    }
}

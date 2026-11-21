package com.matoshree.shopmanager.invoicescan

import com.matoshree.shopmanager.domain.model.Product
import com.matoshree.shopmanager.domain.usecase.DuplicateProductMatcher
import org.junit.Assert.*
import org.junit.Test

class DuplicateProductMatcherTest {

    private val existingProducts = listOf(
        Product(id = 101, name = "Cotton Shirt 40", sellingPrice = 600.0, sku = "10001"),
        Product(id = 102, name = "Denim Jeans 32 Blue", sellingPrice = 900.0, sku = "10002"),
        Product(id = 103, name = "Paithani Saree Red", sellingPrice = 2500.0, sku = "10003")
    )

    @Test
    fun testExactMatchIsStrong() {
        val result = DuplicateProductMatcher.match("Cotton Shirt 40", existingProducts)
        assertTrue(result.isStrongMatch)
        assertEquals(101L, result.matchedProduct?.id)
    }

    @Test
    fun testNormalizedMatchIsStrong() {
        val result = DuplicateProductMatcher.match("  cotton shirt 40  ", existingProducts)
        assertTrue(result.isStrongMatch)
        assertEquals(101L, result.matchedProduct?.id)
    }

    @Test
    fun testDifferentSizeMustNotMatch() {
        // "Cotton Shirt 42" has different size token -> must NOT match "Cotton Shirt 40"
        val result = DuplicateProductMatcher.match("Cotton Shirt 42", existingProducts)
        assertFalse(result.isStrongMatch)
        assertNull(result.matchedProduct)
    }

    @Test
    fun testDifferentColorMustNotMatch() {
        // "Denim Jeans 32 Black" has different color -> must NOT match "Denim Jeans 32 Blue"
        val result = DuplicateProductMatcher.match("Denim Jeans 32 Black", existingProducts)
        assertFalse(result.isStrongMatch)
        assertNull(result.matchedProduct)
    }

    @Test
    fun testCompletelyNewProductNoMatch() {
        val result = DuplicateProductMatcher.match("Track Pant M", existingProducts)
        assertFalse(result.isStrongMatch)
        assertNull(result.matchedProduct)
    }
}

package com.matoshree.shopmanager.invoicescan

import com.matoshree.shopmanager.domain.model.PricingMultiplier
import com.matoshree.shopmanager.domain.usecase.InvoicePricingCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class InvoicePricingCalculatorTest {

    @Test
    fun testExactPricing_54_TwoX_Is_108_NeverRounded() {
        val purchasePrice = 54.0
        val selling2x = InvoicePricingCalculator.calculateSellingPrice(purchasePrice, PricingMultiplier.TWO_X)
        assertEquals(108.0, selling2x, 0.0)
        assertEquals("108", InvoicePricingCalculator.formatPrice(selling2x))
    }

    @Test
    fun testExactPricing_54_OnePointFiveX_Is_81() {
        val purchasePrice = 54.0
        val selling1_5x = InvoicePricingCalculator.calculateSellingPrice(purchasePrice, PricingMultiplier.ONE_POINT_FIVE_X)
        assertEquals(81.0, selling1_5x, 0.0)
        assertEquals("81", InvoicePricingCalculator.formatPrice(selling1_5x))
    }

    @Test
    fun testExactPricing_54_OneX_Is_54() {
        val purchasePrice = 54.0
        val selling1x = InvoicePricingCalculator.calculateSellingPrice(purchasePrice, PricingMultiplier.ONE_X)
        assertEquals(54.0, selling1x, 0.0)
        assertEquals("54", InvoicePricingCalculator.formatPrice(selling1x))
    }

    @Test
    fun testExactPricing_33_OnePointFiveX_Is_49_5() {
        val purchasePrice = 33.0
        val selling = InvoicePricingCalculator.calculateSellingPrice(purchasePrice, PricingMultiplier.ONE_POINT_FIVE_X)
        assertEquals(49.5, selling, 0.0)
        assertEquals("49.5", InvoicePricingCalculator.formatPrice(selling))
    }

    @Test
    fun testNeverRoundsTo100Or110() {
        // A common bug is rounding 108 to 110 or 100. Verify strictly.
        val price = 54.0
        val result = InvoicePricingCalculator.calculateSellingPrice(price, PricingMultiplier.TWO_X)
        org.junit.Assert.assertNotEquals(100.0, result, 0.001)
        org.junit.Assert.assertNotEquals(110.0, result, 0.001)
        assertEquals(108.0, result, 0.001)
    }

    @Test
    fun testLargeValuesExactBigDecimal() {
        val purchasePrice = 45525.75
        val selling = InvoicePricingCalculator.calculateSellingPrice(purchasePrice, PricingMultiplier.TWO_X)
        assertEquals(91051.5, selling, 0.001)
        assertEquals("91051.5", InvoicePricingCalculator.formatPrice(selling))
    }

    @Test
    fun testProfitAndMarginCalculations() {
        val cost = 200.0
        val selling = 300.0
        val profit = InvoicePricingCalculator.calculateProfit(cost, selling)
        val margin = InvoicePricingCalculator.calculateMarginPercent(cost, selling)

        assertEquals(100.0, profit, 0.001)
        assertEquals(33.3, margin, 0.1)
    }
}

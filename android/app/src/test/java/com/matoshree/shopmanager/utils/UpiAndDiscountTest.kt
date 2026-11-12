package com.matoshree.shopmanager.utils

import com.matoshree.shopmanager.domain.model.DiscountType
import com.matoshree.shopmanager.domain.model.PaymentMethod
import org.junit.Assert.*
import org.junit.Test

class UpiAndDiscountTest {

    @Test
    fun testUpiPaymentUriGeneration_500Amount() {
        val uri = PrintShareHelper.buildUpiPaymentUri(
            upiId = "matoshree@upi",
            displayName = "Matoshree Collection",
            amount = 500.0,
            billNumber = "MC-2026-000100"
        )
        assertTrue(uri.startsWith("upi://pay?"))
        assertTrue(uri.contains("pa=matoshree@upi"))
        assertTrue(uri.contains("am=500.00"))
        assertTrue(uri.contains("cu=INR"))
        assertTrue(uri.contains("tn=MC-2026-000100"))
    }

    @Test
    fun testUpiPaymentUriGeneration_1800Amount() {
        val uri = PrintShareHelper.buildUpiPaymentUri(
            upiId = "matoshree@upi",
            displayName = "Matoshree Collection",
            amount = 1800.0,
            billNumber = "MC-2026-000101"
        )
        assertTrue(uri.contains("am=1800.00"))
        assertTrue(uri.contains("pa=matoshree@upi"))
    }

    @Test
    fun testUpiPaymentUriGeneration_2450Amount() {
        val uri = PrintShareHelper.buildUpiPaymentUri(
            upiId = "swaraj@okhdfcbank",
            displayName = "Matoshree Boutique",
            amount = 2450.50,
            billNumber = "MC-2026-000102"
        )
        assertTrue(uri.contains("pa=swaraj@okhdfcbank"))
        assertTrue(uri.contains("am=2450.50"))
    }

    @Test
    fun testAmountDiscountCalculation() {
        val subtotal = 2000.0
        val discountAmount = 200.0
        val finalTotal = (subtotal - discountAmount).coerceAtLeast(0.0)

        assertEquals(2000.0, subtotal, 0.001)
        assertEquals(200.0, discountAmount, 0.001)
        assertEquals(1800.0, finalTotal, 0.001)
    }

    @Test
    fun testPercentageDiscountCalculation() {
        val subtotal = 2000.0
        val percent = 10.0
        val discountAmount = (subtotal * percent / 100.0)
        val finalTotal = (subtotal - discountAmount).coerceAtLeast(0.0)

        assertEquals(200.0, discountAmount, 0.001)
        assertEquals(1800.0, finalTotal, 0.001)
    }

    @Test
    fun testDiscountCannotExceedSubtotal() {
        val subtotal = 1500.0
        val requestedDiscount = 2000.0
        val safeDiscount = requestedDiscount.coerceAtMost(subtotal)
        val finalTotal = (subtotal - safeDiscount).coerceAtLeast(0.0)

        assertEquals(1500.0, safeDiscount, 0.001)
        assertEquals(0.0, finalTotal, 0.001)
    }

    @Test
    fun testSupportedPaymentMethodsForNewSales() {
        val newSaleOptions = listOf(PaymentMethod.CASH, PaymentMethod.UPI, PaymentMethod.OTHER)
        assertFalse(newSaleOptions.contains(PaymentMethod.CARD))
        assertEquals(3, newSaleOptions.size)
        assertTrue(newSaleOptions.contains(PaymentMethod.CASH))
        assertTrue(newSaleOptions.contains(PaymentMethod.UPI))
        assertTrue(newSaleOptions.contains(PaymentMethod.OTHER))
    }

    @Test
    fun testPhoneNormalization_AllFormats() {
        assertEquals("9876543210", PrintShareHelper.normalizeIndianMobile("9876543210"))
        assertEquals("9876543210", PrintShareHelper.normalizeIndianMobile("+919876543210"))
        assertEquals("9876543210", PrintShareHelper.normalizeIndianMobile("91 9876543210"))
        assertEquals("9876543210", PrintShareHelper.normalizeIndianMobile("+91 9876543210"))
        assertEquals("9876543210", PrintShareHelper.normalizeIndianMobile("98765 43210"))
        assertEquals("9876543210", PrintShareHelper.normalizeIndianMobile("98765-43210"))
        assertEquals("9876543210", PrintShareHelper.normalizeIndianMobile("09876543210"))
        assertEquals("8208610261", PrintShareHelper.normalizeIndianMobile("8208610261"))
        assertEquals("8208610261", PrintShareHelper.normalizeIndianMobile("+91 82086 10261"))

        assertNull(PrintShareHelper.normalizeIndianMobile(""))
        assertNull(PrintShareHelper.normalizeIndianMobile(null))
        assertNull(PrintShareHelper.normalizeIndianMobile("12345"))
        assertNull(PrintShareHelper.normalizeIndianMobile("invalid"))
    }
}

package com.matoshree.shopmanager

import com.matoshree.shopmanager.domain.model.Customer
import com.matoshree.shopmanager.utils.PhoneUtils
import com.matoshree.shopmanager.utils.PrintShareHelper
import org.junit.Assert.*
import org.junit.Test

class CustomerPhoneNumberPreservationTest {

    @Test
    fun testExact10DigitMobileInputPreserved() {
        val input = "9876543210"
        val cleaned = PhoneUtils.clean10DigitMobileInput(input)
        assertEquals("9876543210", cleaned)
        assertTrue(PhoneUtils.isValidIndianMobile(cleaned))
    }

    @Test
    fun testCountryCodePrefixStrippedTo10Digits() {
        val withPlus91 = "+919876543210"
        val withSpaces = "+91 98765 43210"
        val with91Only = "919876543210"
        val withZero = "09876543210"
        val withDashes = "98765-43210"

        assertEquals("9876543210", PhoneUtils.clean10DigitMobileInput(withPlus91))
        assertEquals("9876543210", PhoneUtils.clean10DigitMobileInput(withSpaces))
        assertEquals("9876543210", PhoneUtils.clean10DigitMobileInput(with91Only))
        assertEquals("9876543210", PhoneUtils.clean10DigitMobileInput(withZero))
        assertEquals("9876543210", PhoneUtils.clean10DigitMobileInput(withDashes))
    }

    @Test
    fun testWhatsAppDestinationSeparation() {
        val customerMobile = "9876543210"
        val customer = Customer(
            id = 101L,
            shopId = 1,
            name = "Suresh Patil",
            mobile = customerMobile,
            address = "Kondharki"
        )

        // Customer stored mobile must NEVER change
        assertEquals("9876543210", customer.mobile)

        // WhatsApp destination format must be 91 + 10 digits
        val waDestination = PhoneUtils.formatWhatsAppDestination(customer.mobile)
        assertEquals("919876543210", waDestination)

        val printHelperNorm = PrintShareHelper.normalizeIndianMobile(customer.mobile)
        assertEquals("9876543210", printHelperNorm)

        // Customer object remains strictly untouched
        assertEquals("9876543210", customer.mobile)
    }

    @Test
    fun testInvalidMobileValidation() {
        assertFalse(PhoneUtils.isValidIndianMobile(""))
        assertFalse(PhoneUtils.isValidIndianMobile(null))
        assertFalse(PhoneUtils.isValidIndianMobile("12345"))
        assertFalse(PhoneUtils.isValidIndianMobile("abcdefghij"))
        assertTrue(PhoneUtils.isValidIndianMobile("8208610261"))
    }
}

package com.matoshree.shopmanager.utils

object PhoneUtils {
    /**
     * Cleans user input for customer phone numbers to pure 10-digit Indian numbers.
     * Preserves exact 10 digits when typed or pasted:
     * - "9876543210" -> "9876543210"
     * - "+919876543210" -> "9876543210"
     * - "+91 98765 43210" -> "9876543210"
     * - "919876543210" -> "9876543210"
     * - "09876543210" -> "9876543210"
     * - "98765-43210" -> "9876543210"
     * - Partial input: "98765" -> "98765"
     */
    fun clean10DigitMobileInput(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
            digits.length == 11 && digits.startsWith("0") -> digits.substring(1)
            digits.length > 10 && digits.startsWith("91") -> digits.takeLast(10)
            digits.length > 10 && digits.startsWith("0") -> digits.takeLast(10)
            digits.length > 10 -> digits.takeLast(10)
            else -> digits
        }
    }

    /**
     * Validates if a phone number is a valid 10-digit Indian mobile number.
     */
    fun isValidIndianMobile(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val clean = clean10DigitMobileInput(raw)
        return clean.length == 10
    }

    /**
     * Constructs WhatsApp destination ONLY (e.g. "919876543210").
     * NEVER stored in customer database record.
     */
    fun formatWhatsAppDestination(raw: String?): String? {
        val clean10 = clean10DigitMobileInput(raw)
        return if (clean10.length == 10) "91$clean10" else null
    }
}

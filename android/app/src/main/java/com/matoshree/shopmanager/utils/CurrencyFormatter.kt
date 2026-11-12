package com.matoshree.shopmanager.utils

import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale

object CurrencyFormatter {
    private val indianFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }

    /**
     * Formats amount to standard Indian Currency Format: ₹1,25,500 or ₹4,612
     */
    fun format(amount: Double, includeDecimalsIfZero: Boolean = false): String {
        return try {
            val formatted = if (includeDecimalsIfZero) {
                val df = DecimalFormat("##,##,##0.00")
                df.format(amount)
            } else {
                if (amount % 1.0 == 0.0) {
                    val df = DecimalFormat("##,##,##0")
                    df.format(amount)
                } else {
                    val df = DecimalFormat("##,##,##0.00")
                    df.format(amount)
                }
            }
            "₹$formatted"
        } catch (e: Exception) {
            "₹$amount"
        }
    }

    fun parse(input: String): Double {
        return try {
            val clean = input.replace("₹", "").replace(",", "").trim()
            clean.toDoubleOrNull() ?: 0.0
        } catch (e: Exception) {
            0.0
        }
    }
}

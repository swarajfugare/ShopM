package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.InvoiceExtractionResult
import com.matoshree.shopmanager.domain.model.InvoiceItemDraft
import com.matoshree.shopmanager.domain.model.InvoiceValidationFlag
import kotlin.math.abs

object InvoiceValidator {

    const val FLAG_QTY_INVALID = "QTY_INVALID"
    const val FLAG_PRICE_NEGATIVE = "PRICE_NEGATIVE"
    const val FLAG_LINE_TOTAL_MISMATCH = "LINE_TOTAL_MISMATCH"
    const val FLAG_BLANK_NAME = "BLANK_NAME"
    const val FLAG_ABSURD_VALUE = "ABSURD_VALUE"
    const val FLAG_DUPLICATE_LINE = "DUPLICATE_LINE"
    const val FLAG_INVOICE_TOTAL_MISMATCH = "INVOICE_TOTAL_MISMATCH"

    fun validateItem(item: InvoiceItemDraft, allItems: List<InvoiceItemDraft> = emptyList()): List<InvoiceValidationFlag> {
        val flags = mutableListOf<InvoiceValidationFlag>()

        // 1. Blank name
        if (item.name.trim().isBlank()) {
            flags.add(
                InvoiceValidationFlag(
                    code = FLAG_BLANK_NAME,
                    messageMarathi = "नाव आवश्यक आहे",
                    messageEnglish = "Name is required",
                    isWarningOnly = false
                )
            )
        }

        // 2. Quantity > 0
        if (item.quantity <= 0) {
            flags.add(
                InvoiceValidationFlag(
                    code = FLAG_QTY_INVALID,
                    messageMarathi = "प्रमाण शून्य पेक्षा जास्त हवे",
                    messageEnglish = "Quantity must be > 0",
                    isWarningOnly = false
                )
            )
        }

        // 3. Purchase price >= 0
        if (item.unitPrice < 0.0) {
            flags.add(
                InvoiceValidationFlag(
                    code = FLAG_PRICE_NEGATIVE,
                    messageMarathi = "किंमत शून्य किंवा अधिक हवी",
                    messageEnglish = "Price cannot be negative",
                    isWarningOnly = false
                )
            )
        }

        // 4. Line total arithmetic: qty * unitPrice ~= lineTotal (within 2% or ₹2 whichever is larger)
        if (item.quantity > 0 && item.unitPrice > 0.0 && item.lineTotal > 0.0) {
            val expectedTotal = item.quantity * item.unitPrice
            val diff = abs(expectedTotal - item.lineTotal)
            val tolerance = (item.lineTotal * 0.02).coerceAtLeast(2.0)
            if (diff > tolerance) {
                flags.add(
                    InvoiceValidationFlag(
                        code = FLAG_LINE_TOTAL_MISMATCH,
                        messageMarathi = "हिशोब जुळत नाही (प्रमाण × दर ≠ एकूण)",
                        messageEnglish = "Line total mismatch (qty × rate ≠ total)",
                        isWarningOnly = true
                    )
                )
            }
        }

        // 5. Absurd values: price > 1,00,000 or qty > 10,000
        if (item.unitPrice > 100_000.0 || item.quantity > 10_000) {
            flags.add(
                InvoiceValidationFlag(
                    code = FLAG_ABSURD_VALUE,
                    messageMarathi = "किंमत किंवा प्रमाण खूप जास्त दिसत आहे",
                    messageEnglish = "Unusually high price or quantity",
                    isWarningOnly = true
                )
            )
        }

        // 6. Duplicate lines in same invoice
        if (item.name.isNotBlank() && allItems.isNotEmpty()) {
            val normalizedThis = item.name.trim().lowercase().replace(Regex("[^a-z0-9\\u0900-\\u097F]"), "")
            val count = allItems.count { other ->
                val normalizedOther = other.name.trim().lowercase().replace(Regex("[^a-z0-9\\u0900-\\u097F]"), "")
                normalizedOther == normalizedThis
            }
            if (count > 1) {
                flags.add(
                    InvoiceValidationFlag(
                        code = FLAG_DUPLICATE_LINE,
                        messageMarathi = "बिलात हेच नाव पुन्हा आले आहे",
                        messageEnglish = "Duplicate item in invoice",
                        isWarningOnly = true
                    )
                )
            }
        }

        return flags
    }

    fun validateInvoice(result: InvoiceExtractionResult): List<InvoiceValidationFlag> {
        val flags = mutableListOf<InvoiceValidationFlag>()
        val total = result.invoiceTotal
        if (total != null && total > 0.0 && result.items.isNotEmpty()) {
            val sumLines = result.items.sumOf {
                if (it.lineTotal > 0.0) it.lineTotal else (it.quantity * it.unitPrice)
            }
            val diff = abs(sumLines - total)
            val tolerance = (total * 0.03).coerceAtLeast(5.0)
            if (diff > tolerance) {
                flags.add(
                    InvoiceValidationFlag(
                        code = FLAG_INVOICE_TOTAL_MISMATCH,
                        messageMarathi = "एकूण बिलाची बेरीज जुळत नाही (एकूण ₹$total, बेरीज ₹$sumLines)",
                        messageEnglish = "Invoice total mismatch (total ₹$total, sum ₹$sumLines)",
                        isWarningOnly = true
                    )
                )
            }
        }
        return flags
    }
}

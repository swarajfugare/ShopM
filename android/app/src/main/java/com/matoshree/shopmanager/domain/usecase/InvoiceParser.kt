package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.InvoiceExtractionResult
import com.matoshree.shopmanager.domain.model.InvoiceItemDraft
import com.matoshree.shopmanager.domain.model.InvoiceMatchChoice
import com.matoshree.shopmanager.domain.model.PricingMultiplier
import kotlinx.serialization.json.*
import java.util.UUID

object InvoiceParser {

    private val jsonHelper = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Parses AI output text into structured InvoiceExtractionResult.
     * Robust against markdown code fences, trailing commas, and partial structures.
     */
    fun parse(rawText: String): Result<InvoiceExtractionResult> {
        return try {
            val cleanJson = extractJsonPayload(rawText)
            val rootElement = jsonHelper.parseToJsonElement(cleanJson).jsonObject

            val supplierName = rootElement["supplierName"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val invoiceNumber = rootElement["invoiceNumber"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val invoiceDate = rootElement["invoiceDate"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val invoiceTotal = rootElement["invoiceTotal"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull
            val notes = rootElement["notes"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

            val itemsArray = rootElement["items"]?.takeUnless { it is JsonNull }?.jsonArray ?: JsonArray(emptyList())
            val drafts = mutableListOf<InvoiceItemDraft>()

            for (element in itemsArray) {
                val itemObj = element.jsonObject

                val rawItemText = itemObj["rawText"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull ?: ""
                val name = itemObj["name"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                var qty = itemObj["quantity"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull ?: 1
                if (qty <= 0) qty = 1

                val unit = itemObj["unit"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.ifBlank { "pcs" } ?: "pcs"
                var unitPrice = itemObj["unitPrice"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull ?: 0.0
                var lineTotal = itemObj["lineTotal"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull ?: 0.0

                // If unitPrice is missing but lineTotal exists, derive unitPrice
                if (unitPrice <= 0.0 && lineTotal > 0.0 && qty > 0) {
                    unitPrice = lineTotal / qty
                }
                // If lineTotal is missing but unitPrice exists, derive lineTotal
                if (lineTotal <= 0.0 && unitPrice > 0.0 && qty > 0) {
                    lineTotal = unitPrice * qty
                }

                val taxPercent = itemObj["taxPercent"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull
                val category = itemObj["category"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                val confidence = itemObj["confidence"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.doubleOrNull ?: 1.0
                val itemNotes = itemObj["notes"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                // Default multiplier is 2x; selling price is exact BigDecimal calculation
                val defaultMultiplier = PricingMultiplier.TWO_X
                val sellingPrice = InvoicePricingCalculator.calculateSellingPrice(unitPrice, defaultMultiplier)

                drafts.add(
                    InvoiceItemDraft(
                        id = UUID.randomUUID().toString(),
                        rawText = rawItemText,
                        name = name,
                        quantity = qty,
                        unit = unit,
                        unitPrice = unitPrice,
                        lineTotal = lineTotal,
                        taxPercent = taxPercent,
                        categoryName = category,
                        isNewCategory = false, // Will be set by CategoryMatcher
                        confidence = confidence,
                        notes = itemNotes,
                        multiplier = defaultMultiplier,
                        sellingPrice = sellingPrice,
                        isManualPrice = false,
                        matchChoice = InvoiceMatchChoice.NEW_PRODUCT,
                        isIncluded = true
                    )
                )
            }

            // Run validator on items
            val validatedDrafts = drafts.map { item ->
                val flags = InvoiceValidator.validateItem(item, drafts)
                item.copy(validationFlags = flags)
            }

            val result = InvoiceExtractionResult(
                supplierName = supplierName,
                invoiceNumber = invoiceNumber,
                invoiceDate = invoiceDate,
                invoiceTotal = invoiceTotal,
                items = validatedDrafts,
                notes = notes,
                rawAiResponse = rawText
            )

            Result.success(result)
        } catch (e: Exception) {
            // Attempt one repair parse
            try {
                val repaired = repairMalformedJson(rawText)
                val rootElement = jsonHelper.parseToJsonElement(repaired).jsonObject
                // If repair succeeds, call parse again on repaired text
                parse(rootElement.toString())
            } catch (repairException: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun extractJsonPayload(text: String): String {
        var trimmed = text.trim()
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.removePrefix("```json")
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.removePrefix("```")
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.removeSuffix("```")
        }
        trimmed = trimmed.trim()

        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        return if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            trimmed.substring(firstBrace, lastBrace + 1)
        } else {
            trimmed
        }
    }

    private fun repairMalformedJson(raw: String): String {
        var s = extractJsonPayload(raw)
        // Remove trailing commas before } or ]
        s = s.replace(Regex(",\\s*\\}"), "}")
        s = s.replace(Regex(",\\s*\\]"), "]")
        // If unclosed braces
        val openBraces = s.count { it == '{' }
        val closeBraces = s.count { it == '}' }
        if (openBraces > closeBraces) {
            s += "}".repeat(openBraces - closeBraces)
        }
        return s
    }
}

package com.matoshree.shopmanager.domain.model

import java.util.UUID

enum class PricingMultiplier(val factor: Double, val label: String) {
    ONE_X(1.0, "1x"),
    ONE_POINT_FIVE_X(1.5, "1.5x"),
    TWO_X(2.0, "2x")
}

enum class InvoiceMatchChoice {
    RESTOCK,
    NEW_PRODUCT
}

data class InvoiceValidationFlag(
    val code: String,
    val messageMarathi: String,
    val messageEnglish: String,
    val isWarningOnly: Boolean = true
)

data class InvoiceItemDraft(
    val id: String = UUID.randomUUID().toString(),
    val rawText: String = "",
    val name: String = "",
    val quantity: Int = 1,
    val unit: String = "pcs",
    val unitPrice: Double = 0.0,
    val lineTotal: Double = 0.0,
    val taxPercent: Double? = null,
    val categoryName: String = "",
    val isNewCategory: Boolean = false,
    val confidence: Double = 1.0,
    val notes: String? = null,
    val multiplier: PricingMultiplier = PricingMultiplier.TWO_X,
    val sellingPrice: Double = 0.0,
    val isManualPrice: Boolean = false,
    val matchChoice: InvoiceMatchChoice = InvoiceMatchChoice.NEW_PRODUCT,
    val matchedProductId: Long? = null,
    val matchedProductName: String? = null,
    val matchedProductSku: String? = null,
    val updateCostPriceOnRestock: Boolean = true,
    val isIncluded: Boolean = true,
    val validationFlags: List<InvoiceValidationFlag> = emptyList(),
    val allocatedSku: String? = null
)

data class InvoiceExtractionResult(
    val supplierName: String? = null,
    val invoiceNumber: String? = null,
    val invoiceDate: String? = null,
    val invoiceTotal: Double? = null,
    val items: List<InvoiceItemDraft> = emptyList(),
    val notes: String? = null,
    val rawAiResponse: String? = null
)

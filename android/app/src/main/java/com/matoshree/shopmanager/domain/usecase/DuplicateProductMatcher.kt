package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.Product

object DuplicateProductMatcher {

    data class MatchResult(
        val isStrongMatch: Boolean,
        val matchedProduct: Product?
    )

    private val SIZE_REGEX = Regex("\\b(2[8-9]|[3-4][0-9]|5[0-2]|xs|s|m|l|xl|xxl|3xl|4xl)\\b", RegexOption.IGNORE_CASE)
    private val COLOR_WORDS = setOf(
        "red", "blue", "green", "black", "white", "yellow", "orange", "pink", "purple", "grey", "gray", "brown", "navy",
        "लाल", "निळा", "हिरवा", "काळा", "पांढरा", "पिवळा", "गुलाबी"
    )

    fun normalize(name: String): String {
        return name.trim().lowercase().replace(Regex("[^a-z0-9\\u0900-\\u097F]"), "")
    }

    private fun extractTokens(name: String): Set<String> {
        return name.trim().lowercase().split(Regex("[^a-z0-9\\u0900-\\u097F]+")).filter { it.isNotBlank() }.toSet()
    }

    fun match(draftName: String, existingProducts: List<Product>): MatchResult {
        val trimmed = draftName.trim()
        if (trimmed.isBlank() || existingProducts.isEmpty()) {
            return MatchResult(false, null)
        }

        val draftNorm = normalize(trimmed)
        val draftTokens = extractTokens(trimmed)
        val draftSizes = SIZE_REGEX.findAll(trimmed.lowercase()).map { it.value }.toSet()
        val draftColors = draftTokens.filter { it in COLOR_WORDS }.toSet()

        // 1. Direct normalized equality
        val exact = existingProducts.firstOrNull { normalize(it.name) == draftNorm }
        if (exact != null) {
            return MatchResult(true, exact)
        }

        // 2. Token overlap search
        for (prod in existingProducts) {
            val prodTokens = extractTokens(prod.name)
            val prodSizes = SIZE_REGEX.findAll(prod.name.lowercase()).map { it.value }.toSet()
            val prodColors = prodTokens.filter { it in COLOR_WORDS }.toSet()

            // If sizes differ, NEVER merge (different sizes are distinct products)
            if (draftSizes.isNotEmpty() && prodSizes.isNotEmpty() && draftSizes != prodSizes) {
                continue
            }
            // If colors differ, NEVER merge
            if (draftColors.isNotEmpty() && prodColors.isNotEmpty() && draftColors != prodColors) {
                continue
            }

            // Check token overlap
            val common = draftTokens.intersect(prodTokens)
            val minTokens = minOf(draftTokens.size, prodTokens.size)
            if (minTokens >= 2 && common.size >= minTokens) {
                return MatchResult(true, prod)
            }
        }

        return MatchResult(false, null)
    }
}

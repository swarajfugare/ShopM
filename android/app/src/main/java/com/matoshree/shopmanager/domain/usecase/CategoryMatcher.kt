package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.Category

object CategoryMatcher {

    data class MatchResult(
        val matchedCategory: Category?,
        val effectiveName: String,
        val isNew: Boolean
    )

    private val MARATHI_ENGLISH_EQUIVALENTS = mapOf(
        "shirt" to listOf("शर्ट", "shirts"),
        "t-shirt" to listOf("टी-शर्ट", "tshirt", "tshirts", "t shirt"),
        "pant" to listOf("पँट", "पॅन्ट", "pants", "trouser", "trousers"),
        "jeans" to listOf("जीन्स", "जिन", "jean"),
        "saree" to listOf("साडी", "साड्या", "sari", "sarees"),
        "kurti" to listOf("कुर्ती", "कुर्त्या", "kurtis", "kurta"),
        "dress" to listOf("ड्रेस", "dresses"),
        "kids" to listOf("लहान मुलांचे", "मुलांचे", "kids wear", "kidswear"),
        "innerwear" to listOf("इनरवेअर", "अंडरगारमेंट्स", "inner"),
        "towel" to listOf("टॉवेल", "towels", "rumal", "रुमाल"),
        "bedsheet" to listOf("चादर", "बेडशीट", "bedsheets")
    )

    fun normalize(name: String): String {
        return name.trim()
            .lowercase()
            .replace(Regex("[^a-z0-9\\u0900-\\u097F]"), "")
    }

    fun matchCategory(rawName: String, existingCategories: List<Category>): MatchResult {
        val trimmed = rawName.trim()
        if (trimmed.isBlank()) {
            val general = existingCategories.find { normalize(it.name) == normalize("General") || normalize(it.name) == normalize("सामान्य") }
            return if (general != null) {
                MatchResult(general, general.name, isNew = false)
            } else {
                MatchResult(null, "General", isNew = true)
            }
        }

        val normInput = normalize(trimmed)

        // 1. Exact normalized match
        val exactMatch = existingCategories.find { normalize(it.name) == normInput }
        if (exactMatch != null) {
            return MatchResult(exactMatch, exactMatch.name, isNew = false)
        }

        // 2. Singular / Plural match (e.g. "Shirts" vs "Shirt")
        val singularMatch = existingCategories.find { existing ->
            val normExisting = normalize(existing.name)
            (normInput.removeSuffix("s") == normExisting) ||
            (normExisting.removeSuffix("s") == normInput)
        }
        if (singularMatch != null) {
            return MatchResult(singularMatch, singularMatch.name, isNew = false)
        }

        // 3. Marathi / English dictionary match
        for ((eng, equivalents) in MARATHI_ENGLISH_EQUIVALENTS) {
            val allInGroup = (listOf(eng) + equivalents).map { normalize(it) }
            if (normInput in allInGroup) {
                val found = existingCategories.find { existing ->
                    normalize(existing.name) in allInGroup
                }
                if (found != null) {
                    return MatchResult(found, found.name, isNew = false)
                }
            }
        }

        // 4. No safe match -> treat as New Category with capitalized / clean name
        val formattedName = formatCategoryName(trimmed)
        return MatchResult(null, formattedName, isNew = true)
    }

    private fun formatCategoryName(name: String): String {
        val words = name.trim().split(Regex("\\s+"))
        return words.joinToString(" ") { word ->
            if (word.isNotEmpty() && word[0].isLetter()) {
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            } else {
                word
            }
        }
    }
}

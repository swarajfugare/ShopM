package com.matoshree.shopmanager.utils

import java.util.Locale

object AlphabetUtils {
    val ALPHABET_LIST: List<String> = ('A'..'Z').map { it.toString() } + listOf("#")

    fun getAlphabetKey(input: String?): String {
        if (input.isNullOrBlank()) return "#"
        val trimmed = input.trim()
        
        // Find first alphanumeric or Devanagari character (skipping punctuation)
        val firstChar = trimmed.firstOrNull { it.isLetterOrDigit() || it.code in 0x0900..0x097F } ?: trimmed.first()

        // English letters A-Z
        if (firstChar in 'A'..'Z') return firstChar.toString()
        if (firstChar in 'a'..'z') return firstChar.uppercaseChar().toString()

        // Devanagari (Marathi / Hindi) Phonetic Mapping to English A-Z
        return when (firstChar) {
            'अ', 'आ', 'ॲ', 'ऑ' -> "A"
            'ब', 'भ' -> "B"
            'च', 'छ' -> "C"
            'ड', 'ढ', 'द', 'ध' -> "D"
            'ए', 'ऐ' -> "E"
            'फ' -> "F"
            'ग', 'घ' -> "G"
            'ह' -> "H"
            'इ', 'ई' -> "I"
            'ज' -> "J"
            'क', 'ख' -> "K"
            'ल', 'ळ' -> "L"
            'म' -> "M"
            'न', 'ण', 'ं' -> "N"
            'ओ', 'औ' -> "O"
            'प' -> "P"
            'र', 'ऋ' -> "R"
            'स', 'श', 'ष' -> "S"
            'त', 'थ', 'ट', 'ठ' -> "T"
            'उ', 'ऊ' -> "U"
            'व' -> "V"
            'य' -> "Y"
            'झ' -> "Z"
            else -> "#"
        }
    }

    /**
     * Compares two names alphabetically:
     * 1. A-Z section first, '#' section at the end.
     * 2. Within the same section, natural case-insensitive comparison.
     */
    fun compareAlphabetically(name1: String?, name2: String?): Int {
        val s1 = name1?.trim() ?: ""
        val s2 = name2?.trim() ?: ""

        val key1 = getAlphabetKey(s1)
        val key2 = getAlphabetKey(s2)

        val isHash1 = key1 == "#"
        val isHash2 = key2 == "#"

        if (isHash1 && !isHash2) return 1
        if (!isHash1 && isHash2) return -1

        val keyComparison = key1.compareTo(key2, ignoreCase = true)
        if (keyComparison != 0) return keyComparison

        return s1.compareTo(s2, ignoreCase = true)
    }

    /**
     * Finds the best target scroll index for a requested letter.
     * If the requested letter has matching items, returns its index.
     * If not, looks forward in ALPHABET_LIST for the next available letter,
     * or backward if at the end.
     */
    fun findBestTargetIndex(
        requestedLetter: String,
        sectionIndices: Map<String, Int>,
        totalItems: Int
    ): Int? {
        if (totalItems == 0 || sectionIndices.isEmpty()) return null
        
        // Exact match
        sectionIndices[requestedLetter]?.let { return it }

        // Find nearest letter forward
        val reqIndex = ALPHABET_LIST.indexOf(requestedLetter).takeIf { it >= 0 } ?: 0
        for (i in (reqIndex + 1) until ALPHABET_LIST.size) {
            val candidate = ALPHABET_LIST[i]
            sectionIndices[candidate]?.let { return it }
        }

        // If nothing forward, find nearest backward
        for (i in (reqIndex - 1) downTo 0) {
            val candidate = ALPHABET_LIST[i]
            sectionIndices[candidate]?.let { return it }
        }

        return 0
    }
}

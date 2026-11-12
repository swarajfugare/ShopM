package com.matoshree.shopmanager.utils

import kotlin.random.Random

object ProductSkuGenerator {

    /**
     * Generates a compact 5-digit numeric SKU code (e.g. 10042, 20519).
     * Numeric codes produce significantly narrower barcodes on 50mm thermal stickers,
     * ensuring 100% scan reliability without taking excess width.
     */
    fun generateSku(productName: String = "", existingSkus: Set<String> = emptySet()): String {
        val seed = if (productName.isNotBlank()) {
            Math.abs(productName.trim().hashCode()) % 90000 + 10000
        } else {
            Random.nextInt(10000, 99999)
        }

        var candidate = seed.toString()
        var offset = 0
        while (existingSkus.contains(candidate) && offset < 500) {
            candidate = ((seed + ++offset - 10000) % 90000 + 10000).toString()
        }

        if (existingSkus.contains(candidate)) {
            candidate = Random.nextInt(10000, 99999).toString()
        }

        return candidate
    }
}

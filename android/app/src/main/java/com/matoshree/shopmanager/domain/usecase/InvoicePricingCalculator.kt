package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.PricingMultiplier
import java.math.BigDecimal
import java.math.RoundingMode

object InvoicePricingCalculator {

    /**
     * Exact calculation: purchasePrice * multiplier.
     * Absolutely NO rounding to nearest 10 or 5.
     * 54 * 2 = 108.
     * 54 * 1.5 = 81.
     * 33 * 1.5 = 49.5.
     */
    fun calculateSellingPrice(purchasePrice: Double, multiplier: PricingMultiplier): Double {
        if (purchasePrice <= 0.0) return 0.0
        val bdPrice = BigDecimal.valueOf(purchasePrice)
        val bdMult = BigDecimal.valueOf(multiplier.factor)
        val result = bdPrice.multiply(bdMult).setScale(2, RoundingMode.HALF_UP)
        return result.toDouble()
    }

    /**
     * Formats price for display up to 2 decimals, trimming trailing zeros (e.g. 108, 49.5, 49.55).
     */
    fun formatPrice(price: Double): String {
        val bd = BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP)
        val stripped = bd.stripTrailingZeros()
        return stripped.toPlainString()
    }

    /**
     * Exact profit = sellingPrice - purchasePrice.
     */
    fun calculateProfit(purchasePrice: Double, sellingPrice: Double): Double {
        val bdSelling = BigDecimal.valueOf(sellingPrice)
        val bdPurchase = BigDecimal.valueOf(purchasePrice)
        return bdSelling.subtract(bdPurchase).setScale(2, RoundingMode.HALF_UP).toDouble()
    }

    /**
     * Profit margin percentage based on selling price: ((selling - cost) / selling) * 100.
     */
    fun calculateMarginPercent(purchasePrice: Double, sellingPrice: Double): Double {
        if (sellingPrice <= 0.0) return 0.0
        val profit = sellingPrice - purchasePrice
        val margin = (profit / sellingPrice) * 100.0
        return BigDecimal.valueOf(margin).setScale(1, RoundingMode.HALF_UP).toDouble()
    }
}

package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.ProfitType

class CalculateProfitUseCase {
    companion object {
        const val DEFAULT_PROFIT_MARGIN_PERCENT = 25.0
    }

    data class ItemProfitResult(
        val lineTotal: Double,
        val lineCost: Double,
        val lineProfit: Double,
        val profitType: ProfitType
    )

    fun calculateItemProfit(
        sellingPrice: Double,
        quantity: Int,
        costPrice: Double? = null,
        discount: Double = 0.0,
        marginPercent: Double = DEFAULT_PROFIT_MARGIN_PERCENT
    ): ItemProfitResult {
        val qty = quantity.coerceAtLeast(1)
        val lineTotal = ((sellingPrice * qty) - discount).coerceAtLeast(0.0)

        return if (costPrice != null && costPrice > 0) {
            val lineCost = costPrice * qty
            val lineProfit = lineTotal - lineCost
            ItemProfitResult(
                lineTotal = lineTotal,
                lineCost = lineCost,
                lineProfit = lineProfit,
                profitType = ProfitType.ACTUAL
            )
        } else {
            val lineProfit = lineTotal * (marginPercent / 100.0)
            val lineCost = lineTotal - lineProfit
            ItemProfitResult(
                lineTotal = lineTotal,
                lineCost = lineCost,
                lineProfit = lineProfit,
                profitType = ProfitType.ESTIMATED
            )
        }
    }

    data class QuickSaleProfitResult(
        val finalAmount: Double,
        val estimatedCost: Double,
        val estimatedProfit: Double,
        val profitType: ProfitType
    )

    fun calculateQuickSaleProfit(
        finalAmount: Double,
        marginPercent: Double = DEFAULT_PROFIT_MARGIN_PERCENT
    ): QuickSaleProfitResult {
        val profit = finalAmount * (marginPercent / 100.0)
        val cost = finalAmount - profit
        return QuickSaleProfitResult(
            finalAmount = finalAmount,
            estimatedCost = cost,
            estimatedProfit = profit,
            profitType = ProfitType.ESTIMATED
        )
    }
}

package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.ProfitType
import org.junit.Assert.assertEquals
import org.junit.Test

class CalculateProfitUseCaseTest {

    private val useCase = CalculateProfitUseCase()

    @Test
    fun `test estimated profit with default 25 percent margin`() {
        val result = useCase.calculateItemProfit(
            sellingPrice = 10000.0,
            quantity = 1,
            costPrice = null,
            discount = 0.0,
            marginPercent = 25.0
        )

        assertEquals(10000.0, result.lineTotal, 0.01)
        assertEquals(7500.0, result.lineCost, 0.01)
        assertEquals(2500.0, result.lineProfit, 0.01)
        assertEquals(ProfitType.ESTIMATED, result.profitType)
    }

    @Test
    fun `test actual profit when cost price is provided`() {
        val result = useCase.calculateItemProfit(
            sellingPrice = 12499.0,
            quantity = 1,
            costPrice = 9374.0,
            discount = 0.0
        )

        assertEquals(12499.0, result.lineTotal, 0.01)
        assertEquals(9374.0, result.lineCost, 0.01)
        assertEquals(3125.0, result.lineProfit, 0.01)
        assertEquals(ProfitType.ACTUAL, result.profitType)
    }

    @Test
    fun `test item profit with discount applied`() {
        val result = useCase.calculateItemProfit(
            sellingPrice = 2000.0,
            quantity = 2, // 4000
            costPrice = 1500.0, // 3000
            discount = 500.0 // total 3500
        )

        assertEquals(3500.0, result.lineTotal, 0.01)
        assertEquals(3000.0, result.lineCost, 0.01)
        assertEquals(500.0, result.lineProfit, 0.01)
        assertEquals(ProfitType.ACTUAL, result.profitType)
    }

    @Test
    fun `test quick sale profit calculation at 25 percent margin`() {
        val result = useCase.calculateQuickSaleProfit(finalAmount = 18450.0, marginPercent = 25.0)

        assertEquals(18450.0, result.finalAmount, 0.01)
        assertEquals(4612.50, result.estimatedProfit, 0.01)
        assertEquals(13837.50, result.estimatedCost, 0.01)
        assertEquals(ProfitType.ESTIMATED, result.profitType)
    }
}

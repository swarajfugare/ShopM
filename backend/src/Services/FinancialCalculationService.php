<?php
declare(strict_types=1);

namespace Matoshree\Services;

class FinancialCalculationService {
    public const DEFAULT_MARGIN_PERCENT = 25.0;

    /**
     * Calculates line financial metrics for a bill item
     */
    public static function calculateItemFinancials(
        float $sellingPrice,
        int $quantity,
        ?float $costPrice = null,
        float $discount = 0.0,
        float $defaultMarginPercent = self::DEFAULT_MARGIN_PERCENT
    ): array {
        $quantity = max(1, $quantity);
        $lineTotal = round(($sellingPrice * $quantity) - $discount, 2);
        if ($lineTotal < 0) {
            $lineTotal = 0.0;
        }

        if ($costPrice !== null && $costPrice > 0) {
            $lineCost = round($costPrice * $quantity, 2);
            $lineProfit = round($lineTotal - $lineCost, 2);
            $profitType = 'ACTUAL';
        } else {
            $lineCost = round($lineTotal * (1 - ($defaultMarginPercent / 100)), 2);
            $lineProfit = round($lineTotal * ($defaultMarginPercent / 100), 2);
            $profitType = 'ESTIMATED';
        }

        return [
            'line_total'  => $lineTotal,
            'line_cost'   => $lineCost,
            'line_profit' => $lineProfit,
            'profit_type' => $profitType
        ];
    }

    /**
     * Calculates quick sale estimated profit based on shop default margin (25%)
     */
    public static function calculateQuickSaleFinancials(
        float $finalAmount,
        float $marginPercent = self::DEFAULT_MARGIN_PERCENT
    ): array {
        $estimatedProfit = round($finalAmount * ($marginPercent / 100), 2);
        $costAmount = round($finalAmount - $estimatedProfit, 2);

        return [
            'subtotal'         => $finalAmount,
            'discount_amount'  => 0.0,
            'tax_amount'       => 0.0,
            'final_amount'     => $finalAmount,
            'cost_amount'      => $costAmount,
            'estimated_profit' => $estimatedProfit,
            'actual_profit'    => 0.0,
            'profit_type'      => 'ESTIMATED'
        ];
    }
}

const DEFAULT_MARGIN_PERCENT = 25.0;

/**
 * Calculates line totals and profits
 */
function calculateItemFinancials(sellingPrice, quantity = 1, costPrice = null, discount = 0.0, marginPercent = DEFAULT_MARGIN_PERCENT) {
  const qty = Math.max(1, parseInt(quantity, 10) || 1);
  const sellPrice = parseFloat(sellingPrice) || 0.0;
  const disc = parseFloat(discount) || 0.0;
  const lineTotal = Math.max(0.0, Math.round(((sellPrice * qty) - disc) * 100) / 100);

  let lineCost = 0.0;
  let lineProfit = 0.0;
  let profitType = 'ESTIMATED';

  if (costPrice !== null && costPrice !== undefined && costPrice !== '' && parseFloat(costPrice) > 0) {
    const cost = parseFloat(costPrice);
    lineCost = Math.round(cost * qty * 100) / 100;
    lineProfit = Math.round((lineTotal - lineCost) * 100) / 100;
    profitType = 'ACTUAL';
  } else {
    lineProfit = Math.round(lineTotal * (marginPercent / 100.0) * 100) / 100;
    lineCost = Math.round((lineTotal - lineProfit) * 100) / 100;
    profitType = 'ESTIMATED';
  }

  return {
    line_total: lineTotal,
    line_cost: lineCost,
    line_profit: lineProfit,
    profit_type: profitType
  };
}

/**
 * Calculates quick sale estimated profit
 */
function calculateQuickSaleFinancials(finalAmount, marginPercent = DEFAULT_MARGIN_PERCENT) {
  const amt = Math.max(0.0, parseFloat(finalAmount) || 0.0);
  const estimatedProfit = Math.round(amt * (marginPercent / 100.0) * 100) / 100;
  const costAmount = Math.round((amt - estimatedProfit) * 100) / 100;

  return {
    subtotal: amt,
    discount_amount: 0.0,
    tax_amount: 0.0,
    final_amount: amt,
    cost_amount: costAmount,
    estimated_profit: estimatedProfit,
    actual_profit: 0.0,
    profit_type: 'ESTIMATED'
  };
}

module.exports = {
  DEFAULT_MARGIN_PERCENT,
  calculateItemFinancials,
  calculateQuickSaleFinancials
};

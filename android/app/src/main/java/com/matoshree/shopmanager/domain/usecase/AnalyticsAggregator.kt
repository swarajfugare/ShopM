package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import java.text.SimpleDateFormat
import java.util.*

enum class AnalyticsPeriodPreset(val label: String) {
    TODAY("आज"),
    YESTERDAY("काल"),
    THIS_WEEK("चालू आठवडा"),
    LAST_WEEK("मागील आठवडा"),
    THIS_MONTH("चालू महिना"),
    LAST_MONTH("मागील महिना"),
    THIS_QUARTER("चालू त्रैमासिक"),
    FINANCIAL_YEAR("आर्थिक वर्ष"),
    THIS_YEAR("चालू वर्ष"),
    LAST_YEAR("मागील वर्ष"),
    CUSTOM("कस्टम तारीख / श्रेणी")
}

data class AnalyticsDateRange(
    val startDate: String,
    val endDate: String,
    val preset: AnalyticsPeriodPreset = AnalyticsPeriodPreset.TODAY
) {
    companion object {
        fun fromPreset(
            preset: AnalyticsPeriodPreset,
            referenceYear: Int = Calendar.getInstance(DateUtils.kolkataTimeZone).get(Calendar.YEAR),
            referenceMonth: Int = Calendar.getInstance(DateUtils.kolkataTimeZone).get(Calendar.MONTH) + 1
        ): AnalyticsDateRange {
            val cal = Calendar.getInstance(DateUtils.kolkataTimeZone)
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { timeZone = DateUtils.kolkataTimeZone }

            return when (preset) {
                AnalyticsPeriodPreset.TODAY -> {
                    val d = sdf.format(cal.time)
                    AnalyticsDateRange("$d 00:00:00", "$d 23:59:59", preset)
                }
                AnalyticsPeriodPreset.YESTERDAY -> {
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                    val d = sdf.format(cal.time)
                    AnalyticsDateRange("$d 00:00:00", "$d 23:59:59", preset)
                }
                AnalyticsPeriodPreset.THIS_WEEK -> {
                    cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    val start = sdf.format(cal.time)
                    cal.add(Calendar.DAY_OF_YEAR, 6)
                    val end = sdf.format(cal.time)
                    AnalyticsDateRange("$start 00:00:00", "$end 23:59:59", preset)
                }
                AnalyticsPeriodPreset.LAST_WEEK -> {
                    cal.add(Calendar.WEEK_OF_YEAR, -1)
                    cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    val start = sdf.format(cal.time)
                    cal.add(Calendar.DAY_OF_YEAR, 6)
                    val end = sdf.format(cal.time)
                    AnalyticsDateRange("$start 00:00:00", "$end 23:59:59", preset)
                }
                AnalyticsPeriodPreset.THIS_MONTH -> {
                    val start = String.format(Locale.US, "%04d-%02d-01", referenceYear, referenceMonth)
                    cal.set(Calendar.YEAR, referenceYear)
                    cal.set(Calendar.MONTH, referenceMonth - 1)
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val end = String.format(Locale.US, "%04d-%02d-%02d", referenceYear, referenceMonth, maxDay)
                    AnalyticsDateRange("$start 00:00:00", "$end 23:59:59", preset)
                }
                AnalyticsPeriodPreset.LAST_MONTH -> {
                    cal.set(Calendar.YEAR, referenceYear)
                    cal.set(Calendar.MONTH, referenceMonth - 1)
                    cal.add(Calendar.MONTH, -1)
                    val y = cal.get(Calendar.YEAR)
                    val m = cal.get(Calendar.MONTH) + 1
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val start = String.format(Locale.US, "%04d-%02d-01", y, m)
                    val end = String.format(Locale.US, "%04d-%02d-%02d", y, m, maxDay)
                    AnalyticsDateRange("$start 00:00:00", "$end 23:59:59", preset)
                }
                AnalyticsPeriodPreset.THIS_QUARTER -> {
                    val q = (referenceMonth - 1) / 3
                    val startMonth = q * 3 + 1
                    val endMonth = startMonth + 2
                    val start = String.format(Locale.US, "%04d-%02d-01", referenceYear, startMonth)
                    cal.set(Calendar.YEAR, referenceYear)
                    cal.set(Calendar.MONTH, endMonth - 1)
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val end = String.format(Locale.US, "%04d-%02d-%02d", referenceYear, endMonth, maxDay)
                    AnalyticsDateRange("$start 00:00:00", "$end 23:59:59", preset)
                }
                AnalyticsPeriodPreset.FINANCIAL_YEAR -> {
                    val fyStartYear = if (referenceMonth >= 4) referenceYear else referenceYear - 1
                    val start = String.format(Locale.US, "%04d-04-01", fyStartYear)
                    val end = String.format(Locale.US, "%04d-03-31", fyStartYear + 1)
                    AnalyticsDateRange("$start 00:00:00", "$end 23:59:59", preset)
                }
                AnalyticsPeriodPreset.THIS_YEAR -> {
                    val start = String.format(Locale.US, "%04d-01-01", referenceYear)
                    val end = String.format(Locale.US, "%04d-12-31", referenceYear)
                    AnalyticsDateRange("$start 00:00:00", "$end 23:59:59", preset)
                }
                AnalyticsPeriodPreset.LAST_YEAR -> {
                    val start = String.format(Locale.US, "%04d-01-01", referenceYear - 1)
                    val end = String.format(Locale.US, "%04d-12-31", referenceYear - 1)
                    AnalyticsDateRange("$start 00:00:00", "$end 23:59:59", preset)
                }
                AnalyticsPeriodPreset.CUSTOM -> {
                    val d = sdf.format(cal.time)
                    AnalyticsDateRange("$d 00:00:00", "$d 23:59:59", preset)
                }
            }
        }
    }
}

data class MetricDelta(
    val current: Double,
    val previous: Double,
    val amountDelta: Double,
    val percentageDelta: Double,
    val isPositive: Boolean
)

data class PeriodComparison(
    val salesDelta: MetricDelta,
    val profitDelta: MetricDelta,
    val billsDelta: MetricDelta,
    val collectionsDelta: MetricDelta,
    val expensesDelta: MetricDelta
)

data class SalesMetrics(
    val grossSales: Double,
    val discounts: Double,
    val netSales: Double,
    val billCount: Int,
    val averageBill: Double,
    val itemsSold: Int,
    val voidedCount: Int,
    val voidedAmount: Double
)

data class ProfitMetrics(
    val grossProfit: Double,
    val totalExpenses: Double,
    val expensesByCategory: Map<String, Double>,
    val netProfit: Double,
    val profitMarginPercent: Double
)

data class PaymentSplit(
    val cashAmount: Double,
    val upiAmount: Double,
    val udhariAmount: Double,
    val otherAmount: Double,
    val cashPercent: Double,
    val upiPercent: Double,
    val udhariPercent: Double
)

data class UdhariMetrics(
    val creditGivenInPeriod: Double,
    val recoveredInPeriod: Double,
    val currentTotalOutstanding: Double,
    val aging0to30: Double,
    val aging31to60: Double,
    val aging61to90: Double,
    val aging90Plus: Double,
    val overdueCount: Int,
    val overdueAmount: Double
)

data class TopProductAnalytics(
    val productId: Long?,
    val name: String,
    val categoryName: String,
    val quantity: Int,
    val revenue: Double,
    val profit: Double
)

data class TopCustomerAnalytics(
    val customerGid: String,
    val name: String,
    val mobile: String,
    val village: String,
    val billsCount: Int,
    val totalSpend: Double,
    val outstandingDue: Double
)

data class StockValuation(
    val totalCostValue: Double,
    val totalSellingValue: Double,
    val lowStockCount: Int,
    val deadStockCount: Int
)

data class DayReport(
    val date: String,
    val bills: List<Bill>,
    val payments: List<UdhariTransaction>,
    val expenses: List<Expense>,
    val totalSales: Double,
    val totalProfit: Double,
    val cashTotal: Double,
    val upiTotal: Double
)

data class AnalyticsFilters(
    val customerGid: String? = null,
    val categoryId: Long? = null,
    val village: String? = null
)

data class AnalyticsResult(
    val dateRange: AnalyticsDateRange,
    val sales: SalesMetrics,
    val profit: ProfitMetrics,
    val paymentSplit: PaymentSplit,
    val udhari: UdhariMetrics,
    val topProducts: List<TopProductAnalytics>,
    val topCustomers: List<TopCustomerAnalytics>,
    val newCustomersCount: Int,
    val repeatCustomersCount: Int,
    val stockValuation: StockValuation,
    val villageSales: Map<String, Double>,
    val hourlySales: Map<Int, Double>,
    val dayReport: DayReport?,
    val comparison: PeriodComparison?,
    val filteredBills: List<Bill>
) {
    fun generateCsvReport(): String {
        val sb = StringBuilder()
        sb.append("Matoshree Collection - Analytics Report\n")
        sb.append("Period,${dateRange.startDate} to ${dateRange.endDate}\n\n")

        sb.append("Metric,Value\n")
        sb.append("Net Sales,₹${CurrencyFormatter.format(sales.netSales)}\n")
        sb.append("Gross Sales,₹${CurrencyFormatter.format(sales.grossSales)}\n")
        sb.append("Discounts,₹${CurrencyFormatter.format(sales.discounts)}\n")
        sb.append("Total Bills,${sales.billCount}\n")
        sb.append("Average Bill,₹${CurrencyFormatter.format(sales.averageBill)}\n")
        sb.append("Gross Profit,₹${CurrencyFormatter.format(profit.grossProfit)}\n")
        sb.append("Expenses,₹${CurrencyFormatter.format(profit.totalExpenses)}\n")
        sb.append("Net Profit,₹${CurrencyFormatter.format(profit.netProfit)}\n")
        sb.append("Cash Sales,₹${CurrencyFormatter.format(paymentSplit.cashAmount)}\n")
        sb.append("UPI Sales,₹${CurrencyFormatter.format(paymentSplit.upiAmount)}\n")
        sb.append("Udhari Created,₹${CurrencyFormatter.format(paymentSplit.udhariAmount)}\n")
        sb.append("Udhari Recovered,₹${CurrencyFormatter.format(udhari.recoveredInPeriod)}\n\n")

        sb.append("Top Products\n")
        sb.append("Product,Quantity,Revenue,Profit\n")
        for (p in topProducts.take(15)) {
            sb.append("\"${p.name}\",${p.quantity},${p.revenue},${p.profit}\n")
        }

        return sb.toString()
    }

    fun generateWhatsAppSummary(): String {
        return """
            📊 *मातोश्री कलेक्शन - व्यवसाय अहवाल*
            📅 कालावधी: ${dateRange.startDate.take(10)} ते ${dateRange.endDate.take(10)}
            
            💰 *विक्री व नफा*
            • निव्वळ विक्री: ₹${CurrencyFormatter.format(sales.netSales)} (${sales.billCount} बिले)
            • सरासरी बिल: ₹${CurrencyFormatter.format(sales.averageBill)}
            • एकूण नफा: ₹${CurrencyFormatter.format(profit.netProfit)} (मार्जिन: ${String.format(Locale.US, "%.1f", profit.profitMarginPercent)}%)
            • एकूण खर्च: ₹${CurrencyFormatter.format(profit.totalExpenses)}
            
            💳 *भरणा पद्धत*
            • रोख (Cash): ₹${CurrencyFormatter.format(paymentSplit.cashAmount)}
            • युपीआय (UPI): ₹${CurrencyFormatter.format(paymentSplit.upiAmount)}
            • नवीन उधारी: ₹${CurrencyFormatter.format(paymentSplit.udhariAmount)}
            
            🤝 *उधारी वसुली*
            • कालावधीतील वसुली: ₹${CurrencyFormatter.format(udhari.recoveredInPeriod)}
            • सध्याची एकूण बाकी: ₹${CurrencyFormatter.format(udhari.currentTotalOutstanding)}
        """.trimIndent()
    }
}

object AnalyticsAggregator {

    fun calculateDelta(current: Double, previous: Double): MetricDelta {
        val amountDelta = current - previous
        val percentageDelta = if (previous > 0.001) {
            (amountDelta / previous) * 100.0
        } else if (current > 0.001) {
            100.0
        } else {
            0.0
        }
        return MetricDelta(
            current = current,
            previous = previous,
            amountDelta = amountDelta,
            percentageDelta = percentageDelta,
            isPositive = amountDelta >= 0.0
        )
    }

    fun compute(
        bills: List<Bill>,
        transactions: List<UdhariTransaction>,
        expenses: List<Expense>,
        products: List<Product>,
        customers: List<Customer>,
        dateRange: AnalyticsDateRange,
        compareRange: AnalyticsDateRange? = null,
        filters: AnalyticsFilters = AnalyticsFilters()
    ): AnalyticsResult {
        // Filter out voided and trashed bills
        val validBills = bills.filter { !it.isVoided && it.paymentStatus != PaymentStatus.VOID }
        val voidedBills = bills.filter { it.isVoided || it.paymentStatus == PaymentStatus.VOID }

        // Date range filtering
        val inRangeBills = validBills.filter { b ->
            b.billDate >= dateRange.startDate && b.billDate <= dateRange.endDate
        }

        val customerVillageMap = customers.filter { it.gid.isNotBlank() }.associate { it.gid to (it.address?.trim()?.ifBlank { "कोंढारकी" } ?: "कोंढारकी") }

        // Apply secondary filters
        val filteredBills = inRangeBills.filter { b ->
            val matchCust = filters.customerGid == null || b.customerGid == filters.customerGid
            val v = b.customerGid?.let { customerVillageMap[it] } ?: "कोंढारकी"
            val matchVillage = filters.village == null || v.equals(filters.village, ignoreCase = true)
            val matchCat = filters.categoryId == null || b.items.any { it.categoryId == filters.categoryId }
            matchCust && matchVillage && matchCat
        }

        // Sales Metrics
        val netSales = filteredBills.sumOf { it.finalAmount }
        val discounts = filteredBills.sumOf { it.discountAmount }
        val grossSales = netSales + discounts
        val billCount = filteredBills.size
        val averageBill = if (billCount > 0) netSales / billCount else 0.0
        val itemsSold = filteredBills.sumOf { b -> b.items.sumOf { it.quantity } }

        val inRangeVoided = voidedBills.filter { it.billDate >= dateRange.startDate && it.billDate <= dateRange.endDate }
        val voidedCount = inRangeVoided.size
        val voidedAmount = inRangeVoided.sumOf { it.finalAmount }

        val salesMetrics = SalesMetrics(
            grossSales = grossSales,
            discounts = discounts,
            netSales = netSales,
            billCount = billCount,
            averageBill = averageBill,
            itemsSold = itemsSold,
            voidedCount = voidedCount,
            voidedAmount = voidedAmount
        )

        // Profit & Expenses
        val grossProfit = filteredBills.sumOf { it.estimatedProfit + it.actualProfit }
        val inRangeExpenses = expenses.filter { e ->
            val d = "${e.expenseDate} 00:00:00"
            d >= dateRange.startDate && d <= dateRange.endDate
        }
        val totalExpenses = inRangeExpenses.sumOf { it.amount }
        val expensesByCategory = inRangeExpenses.groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
        val netProfit = grossProfit - totalExpenses
        val profitMarginPercent = if (netSales > 0.01) (netProfit / netSales) * 100.0 else 0.0

        val profitMetrics = ProfitMetrics(
            grossProfit = grossProfit,
            totalExpenses = totalExpenses,
            expensesByCategory = expensesByCategory,
            netProfit = netProfit,
            profitMarginPercent = profitMarginPercent
        )

        // Payment Split
        var cashAmt = 0.0
        var upiAmt = 0.0
        var udhariAmt = 0.0
        var otherAmt = 0.0

        for (b in filteredBills) {
            when (b.paymentMethod) {
                PaymentMethod.CASH -> {
                    cashAmt += b.paidAmount
                    udhariAmt += b.pendingAmount
                }
                PaymentMethod.UPI -> {
                    upiAmt += b.paidAmount
                    udhariAmt += b.pendingAmount
                }
                PaymentMethod.SPLIT -> {
                    cashAmt += b.paidAmount
                    udhariAmt += b.pendingAmount
                }
                else -> {
                    otherAmt += b.paidAmount
                    udhariAmt += b.pendingAmount
                }
            }
        }
        val totalPaymentConsidered = cashAmt + upiAmt + udhariAmt + otherAmt
        val paymentSplit = PaymentSplit(
            cashAmount = cashAmt,
            upiAmount = upiAmt,
            udhariAmount = udhariAmt,
            otherAmount = otherAmt,
            cashPercent = if (totalPaymentConsidered > 0) (cashAmt / totalPaymentConsidered) * 100.0 else 0.0,
            upiPercent = if (totalPaymentConsidered > 0) (upiAmt / totalPaymentConsidered) * 100.0 else 0.0,
            udhariPercent = if (totalPaymentConsidered > 0) (udhariAmt / totalPaymentConsidered) * 100.0 else 0.0
        )

        // Udhari Metrics
        val validTxs = transactions.filter { !it.isReversed && it.status != "TRASH" && it.status != "IGNORED" }
        val inRangePayments = validTxs.filter { tx ->
            tx.type == UdhariTransactionType.PAYMENT &&
            tx.date >= dateRange.startDate.take(10) && tx.date <= dateRange.endDate.take(10)
        }
        val recoveredInPeriod = inRangePayments.sumOf { it.amount }
        val creditGivenInPeriod = udhariAmt

        // Authoritative ledger for aging & outstanding
        val shopLedger = UdhariLedger.computeShopLedger(validBills, validTxs, customers)
        val currentTotalOutstanding = shopLedger.totalOutstanding

        // Aging buckets computed across active bills
        var aging0to30 = 0.0
        var aging31to60 = 0.0
        var aging61to90 = 0.0
        var aging90Plus = 0.0

        val now = System.currentTimeMillis()
        for ((_, custResult) in shopLedger.customerResults) {
            for (bb in custResult.billBreakdowns) {
                if (bb.remainingOutstanding > 0.01) {
                    val billMillis = try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { timeZone = DateUtils.kolkataTimeZone }
                        sdf.parse(bb.billDate.take(10))?.time ?: now
                    } catch (_: Exception) { now }

                    val ageDays = ((now - billMillis) / (24 * 60 * 60 * 1000L)).coerceAtLeast(0)
                    when {
                        ageDays <= 30 -> aging0to30 += bb.remainingOutstanding
                        ageDays <= 60 -> aging31to60 += bb.remainingOutstanding
                        ageDays <= 90 -> aging61to90 += bb.remainingOutstanding
                        else -> aging90Plus += bb.remainingOutstanding
                    }
                }
            }
        }

        val udhariMetrics = UdhariMetrics(
            creditGivenInPeriod = creditGivenInPeriod,
            recoveredInPeriod = recoveredInPeriod,
            currentTotalOutstanding = currentTotalOutstanding,
            aging0to30 = aging0to30,
            aging31to60 = aging31to60,
            aging61to90 = aging61to90,
            aging90Plus = aging90Plus,
            overdueCount = shopLedger.customerResults.values.count { it.isOverdue },
            overdueAmount = shopLedger.overdueAmount
        )

        // Top Products
        val productMap = mutableMapOf<String, TopProductAnalytics>()
        for (b in filteredBills) {
            for (item in b.items) {
                val key = item.productName.trim()
                val existing = productMap[key]
                if (existing == null) {
                    productMap[key] = TopProductAnalytics(
                        productId = item.productId,
                        name = item.productName,
                        categoryName = "वस्तू",
                        quantity = item.quantity,
                        revenue = item.lineTotal,
                        profit = item.lineProfit
                    )
                } else {
                    productMap[key] = existing.copy(
                        quantity = existing.quantity + item.quantity,
                        revenue = existing.revenue + item.lineTotal,
                        profit = existing.profit + item.lineProfit
                    )
                }
            }
        }
        val topProducts = productMap.values.sortedByDescending { it.revenue }.take(20)

        // Top Customers
        val customerSpendMap = mutableMapOf<String, TopCustomerAnalytics>()
        val keyResolver = UdhariLedger.buildCustomerKeyResolver(customers, validBills, validTxs)

        for (b in filteredBills) {
            val key = keyResolver(b.customerGid, b.customerMobile, b.customerName, b.customerId) ?: (b.customerGid ?: "cust_${b.customerId}")
            val existing = customerSpendMap[key]
            val custBal = shopLedger.customerResults[key]?.balance ?: 0.0
            val v = b.customerGid?.let { customerVillageMap[it] } ?: "कोंढारकी"
            if (existing == null) {
                customerSpendMap[key] = TopCustomerAnalytics(
                    customerGid = key,
                    name = b.customerName ?: "ग्राहक",
                    mobile = b.customerMobile ?: "",
                    village = v,
                    billsCount = 1,
                    totalSpend = b.finalAmount,
                    outstandingDue = custBal
                )
            } else {
                customerSpendMap[key] = existing.copy(
                    billsCount = existing.billsCount + 1,
                    totalSpend = existing.totalSpend + b.finalAmount
                )
            }
        }
        val topCustomers = customerSpendMap.values.sortedByDescending { it.totalSpend }.take(20)

        // New vs Repeat customers
        val customerFirstBillDates = validBills.groupBy { keyResolver(it.customerGid, it.customerMobile, it.customerName, it.customerId) ?: "" }
            .mapValues { (_, bList) -> bList.minOf { it.billDate } }

        var newCustCount = 0
        var repeatCustCount = 0
        for ((cKey, _) in customerSpendMap) {
            val firstDate = customerFirstBillDates[cKey] ?: ""
            if (firstDate >= dateRange.startDate && firstDate <= dateRange.endDate) {
                newCustCount++
            } else {
                repeatCustCount++
            }
        }

        // Stock Valuation
        val totalCostVal = products.sumOf { (it.costPrice ?: (it.sellingPrice * 0.75)) * it.currentStock }
        val totalSellVal = products.sumOf { it.sellingPrice * it.currentStock }
        val lowStockCount = products.count { it.isLowStock }
        val deadStockCount = products.count { it.currentStock > 0 && it.totalSold == 0 }

        val stockValuation = StockValuation(
            totalCostValue = totalCostVal,
            totalSellingValue = totalSellVal,
            lowStockCount = lowStockCount,
            deadStockCount = deadStockCount
        )

        // Village-wise distribution
        val villageSales = filteredBills.groupBy { b ->
            b.customerGid?.let { customerVillageMap[it] } ?: "कोंढारकी"
        }.mapValues { (_, bList) -> bList.sumOf { it.finalAmount } }

        // Hourly distribution
        val hourlyMap = mutableMapOf<Int, Double>()
        for (h in 0..23) hourlyMap[h] = 0.0
        val hourSdf = SimpleDateFormat("HH", Locale.ENGLISH).apply { timeZone = DateUtils.kolkataTimeZone }
        for (b in filteredBills) {
            val h = try {
                val dt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).apply { timeZone = DateUtils.kolkataTimeZone }.parse(b.billDate)
                dt?.let { hourSdf.format(it).toIntOrNull() } ?: 12
            } catch (_: Exception) { 12 }
            hourlyMap[h] = (hourlyMap[h] ?: 0.0) + b.finalAmount
        }

        // Single day view report (if single date selected)
        val dayReport = if (dateRange.startDate.take(10) == dateRange.endDate.take(10)) {
            val d = dateRange.startDate.take(10)
            val dayBills = filteredBills
            val dayPayments = inRangePayments
            val dayExpenses = inRangeExpenses
            DayReport(
                date = d,
                bills = dayBills,
                payments = dayPayments,
                expenses = dayExpenses,
                totalSales = netSales,
                totalProfit = netProfit,
                cashTotal = cashAmt,
                upiTotal = upiAmt
            )
        } else null

        // Comparison if compareRange is provided
        val comparison = if (compareRange != null) {
            val compBills = validBills.filter { it.billDate >= compareRange.startDate && it.billDate <= compareRange.endDate }
            val compNetSales = compBills.sumOf { it.finalAmount }
            val compGrossProfit = compBills.sumOf { it.estimatedProfit + it.actualProfit }
            val compExpenses = expenses.filter { e ->
                val d = "${e.expenseDate} 00:00:00"
                d >= compareRange.startDate && d <= compareRange.endDate
            }.sumOf { it.amount }
            val compNetProfit = compGrossProfit - compExpenses
            val compRecovered = validTxs.filter { tx ->
                tx.type == UdhariTransactionType.PAYMENT &&
                tx.date >= compareRange.startDate.take(10) && tx.date <= compareRange.endDate.take(10)
            }.sumOf { it.amount }

            PeriodComparison(
                salesDelta = calculateDelta(netSales, compNetSales),
                profitDelta = calculateDelta(netProfit, compNetProfit),
                billsDelta = calculateDelta(billCount.toDouble(), compBills.size.toDouble()),
                collectionsDelta = calculateDelta(recoveredInPeriod, compRecovered),
                expensesDelta = calculateDelta(totalExpenses, compExpenses)
            )
        } else null

        return AnalyticsResult(
            dateRange = dateRange,
            sales = salesMetrics,
            profit = profitMetrics,
            paymentSplit = paymentSplit,
            udhari = udhariMetrics,
            topProducts = topProducts,
            topCustomers = topCustomers,
            newCustomersCount = newCustCount,
            repeatCustomersCount = repeatCustCount,
            stockValuation = stockValuation,
            villageSales = villageSales,
            hourlySales = hourlyMap,
            dayReport = dayReport,
            comparison = comparison,
            filteredBills = filteredBills
        )
    }
}

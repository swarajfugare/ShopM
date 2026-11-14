package com.matoshree.shopmanager.ui.screens.analytics

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.*
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.utils.PhoneUtils
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.StatusBadge
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PrintShareHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class AnalyticsPeriod(val label: String) {
    TODAY("आज"),
    THIS_WEEK("चालू आठवडा"),
    THIS_MONTH("चालू महिना"),
    THIS_YEAR("चालू वर्ष"),
    CUSTOM("कस्टम")
}

data class TrendPoint(
    val label: String,
    val sales: Double,
    val billCount: Int
)

data class TopProductStat(
    val productId: Long?,
    val name: String,
    val quantity: Int,
    val revenue: Double,
    val profit: Double
)

data class TopCustomerStat(
    val customerId: Long,
    val customerGid: String = "",
    val name: String,
    val billsCount: Int,
    val totalSpend: Double
)

class AnalyticsViewModel(
    private val billRepository: BillRepository,
    private val customerRepository: CustomerRepository,
    private val productRepository: ProductRepository,
    private val expenseRepository: ExpenseRepository,
    private val dailyClosingRepository: DailyClosingRepository
) : ViewModel() {

    val selectedPeriod = MutableStateFlow(AnalyticsPeriod.TODAY)
    val customStartDate = MutableStateFlow("")
    val customEndDate = MutableStateFlow("")

    val bills = MutableStateFlow<List<Bill>>(emptyList())
    val customers = MutableStateFlow<List<Customer>>(emptyList())
    val products = MutableStateFlow<List<Product>>(emptyList())
    val expenses = MutableStateFlow<List<Expense>>(emptyList())
    val dailyClosings = MutableStateFlow<List<DailyClosing>>(emptyList())

    val lastUpdatedTime = MutableStateFlow(getCurrentTimeDisplay())
    val isRefreshing = MutableStateFlow(false)

    val productSortBy = MutableStateFlow("REVENUE") // REVENUE, QUANTITY, PROFIT

    private val errorHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("AnalyticsViewModel", "Analytics error caught safely: ${throwable.message}", throwable)
    }

    init {
        observeAllData()
    }

    private fun getCurrentTimeDisplay(): String {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return sdf.format(Date())
    }

    private fun observeAllData() {
        viewModelScope.launch(errorHandler) {
            billRepository.getAllBills(limit = 500)
                .distinctUntilChanged()
                .catch { e -> android.util.Log.e("AnalyticsViewModel", "Bills flow error: ${e.message}", e) }
                .collectLatest {
                    if (bills.value != it) {
                        bills.value = it
                        lastUpdatedTime.value = getCurrentTimeDisplay()
                    }
                }
        }
        viewModelScope.launch(errorHandler) {
            customerRepository.getAllCustomers()
                .distinctUntilChanged()
                .catch { e -> android.util.Log.e("AnalyticsViewModel", "Customer flow error: ${e.message}", e) }
                .collectLatest {
                    if (customers.value != it) {
                        customers.value = it
                    }
                }
        }
        viewModelScope.launch(errorHandler) {
            productRepository.getAllProducts()
                .distinctUntilChanged()
                .catch { e -> android.util.Log.e("AnalyticsViewModel", "Product flow error: ${e.message}", e) }
                .collectLatest {
                    if (products.value != it) {
                        products.value = it
                    }
                }
        }
        viewModelScope.launch(errorHandler) {
            expenseRepository.getAllExpenses()
                .distinctUntilChanged()
                .catch { e -> android.util.Log.e("AnalyticsViewModel", "Expense flow error: ${e.message}", e) }
                .collectLatest {
                    if (expenses.value != it) {
                        expenses.value = it
                    }
                }
        }
        viewModelScope.launch(errorHandler) {
            dailyClosingRepository.getAllClosings()
                .distinctUntilChanged()
                .catch { e -> android.util.Log.e("AnalyticsViewModel", "Closing flow error: ${e.message}", e) }
                .collectLatest {
                    if (dailyClosings.value != it) {
                        dailyClosings.value = it
                    }
                }
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            isRefreshing.value = true
            lastUpdatedTime.value = getCurrentTimeDisplay()
            isRefreshing.value = false
        }
    }

    fun setCustomRange(start: String, end: String) {
        customStartDate.value = start
        customEndDate.value = end
        selectedPeriod.value = AnalyticsPeriod.CUSTOM
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel,
    onCustomerClick: (String) -> Unit = {},
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val period by viewModel.selectedPeriod.collectAsState()
    val allBills by viewModel.bills.collectAsState()
    val allCustomers by viewModel.customers.collectAsState()
    val allProducts by viewModel.products.collectAsState()
    val allExpenses by viewModel.expenses.collectAsState()
    val allClosings by viewModel.dailyClosings.collectAsState()
    val lastUpdated by viewModel.lastUpdatedTime.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val prodSort by viewModel.productSortBy.collectAsState()

    var showCustomDateDialog by remember { mutableStateOf(false) }

    // Date filtering based on Asia/Kolkata boundaries
    val filteredBills = remember(allBills, period, viewModel.customStartDate.value, viewModel.customEndDate.value) {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        val todayStr = sdf.format(Date())
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))

        val (startPrefix, endPrefix) = when (period) {
            AnalyticsPeriod.TODAY -> Pair(todayStr, todayStr)
            AnalyticsPeriod.THIS_WEEK -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                val weekStart = sdf.format(cal.time)
                Pair(weekStart, todayStr)
            }
            AnalyticsPeriod.THIS_MONTH -> {
                val monthPrefix = todayStr.take(7)
                Pair("$monthPrefix-01", "$monthPrefix-31")
            }
            AnalyticsPeriod.THIS_YEAR -> {
                val yearPrefix = todayStr.take(4)
                Pair("$yearPrefix-01-01", "$yearPrefix-12-31")
            }
            AnalyticsPeriod.CUSTOM -> {
                Pair(
                    viewModel.customStartDate.value.ifBlank { "2020-01-01" },
                    viewModel.customEndDate.value.ifBlank { "2099-12-31" }
                )
            }
        }

        allBills.filter { bill ->
            val billDay = bill.billDate.take(10)
            billDay in startPrefix..endPrefix
        }
    }

    val validBills = remember(filteredBills) { filteredBills.filter { !it.isVoided } }
    val voidedBills = remember(filteredBills) { filteredBills.filter { it.isVoided } }

    // Aggregate Sales KPIs
    val totalSales = remember(validBills) { validBills.sumOf { it.finalAmount } }
    val totalBillsCount = validBills.size
    val avgBillValue = remember(totalSales, totalBillsCount) {
        if (totalBillsCount > 0) totalSales / totalBillsCount else 0.0
    }
    val highestBill = remember(validBills) { validBills.maxOfOrNull { it.finalAmount } ?: 0.0 }
    val lowestBill = remember(validBills) { if (validBills.isNotEmpty()) validBills.minOf { it.finalAmount } else 0.0 }

    // Expenses in Period
    val filteredExpenses = remember(allExpenses, period, viewModel.customStartDate.value, viewModel.customEndDate.value) {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date())

        val prefix = when (period) {
            AnalyticsPeriod.TODAY -> todayStr
            AnalyticsPeriod.THIS_MONTH -> todayStr.take(7)
            AnalyticsPeriod.THIS_YEAR -> todayStr.take(4)
            else -> ""
        }

        if (prefix.isNotEmpty()) {
            allExpenses.filter { it.expenseDate.startsWith(prefix) }
        } else {
            allExpenses
        }
    }
    val totalExpensesAmount = remember(filteredExpenses) { filteredExpenses.sumOf { it.amount } }

    // Profit Calculations
    val totalCostAmount = remember(validBills) { validBills.sumOf { it.costAmount } }
    val grossProfit = remember(totalSales, totalCostAmount) { (totalSales - totalCostAmount).coerceAtLeast(0.0) }
    val netProfit = remember(grossProfit, totalExpensesAmount) { grossProfit - totalExpensesAmount }
    val actualProfit = remember(validBills) { validBills.sumOf { it.actualProfit } }
    val estimatedProfit = remember(totalSales) { totalSales * 0.25 } // 25% default estimated margin

    val profitMarginPct = remember(totalSales, grossProfit) {
        if (totalSales > 0) (grossProfit / totalSales) * 100 else 0.0
    }

    // Payment Breakdown
    val cashBills = remember(validBills) { validBills.filter { it.paymentMethod == PaymentMethod.CASH } }
    val upiBills = remember(validBills) { validBills.filter { it.paymentMethod == PaymentMethod.UPI } }
    val otherBills = remember(validBills) { validBills.filter { it.paymentMethod != PaymentMethod.CASH && it.paymentMethod != PaymentMethod.UPI } }

    val cashAmount = remember(cashBills) { cashBills.sumOf { it.finalAmount } }
    val upiAmount = remember(upiBills) { upiBills.sumOf { it.finalAmount } }
    val otherAmount = remember(otherBills) { otherBills.sumOf { it.finalAmount } }

    // Expense Categories Breakdown
    val expenseCategories = remember(filteredExpenses) {
        filteredExpenses.groupBy { it.category }.map { (cat, list) ->
            Pair(cat, list.sumOf { it.amount })
        }.sortedByDescending { it.second }
    }

    // Product Analytics Aggregation
    val productStats = remember(validBills, allProducts, prodSort) {
        val map = mutableMapOf<String, TopProductStat>()
        validBills.forEach { bill ->
            bill.items.forEach { item ->
                val key = item.productName
                val existing = map[key] ?: TopProductStat(item.productId, item.productName, 0, 0.0, 0.0)
                map[key] = existing.copy(
                    quantity = existing.quantity + item.quantity,
                    revenue = existing.revenue + item.lineTotal,
                    profit = existing.profit + item.lineProfit
                )
            }
        }
        val list = map.values.toList()
        when (prodSort) {
            "QUANTITY" -> list.sortedByDescending { it.quantity }
            "PROFIT" -> list.sortedByDescending { it.profit }
            else -> list.sortedByDescending { it.revenue }
        }
    }

    // Customer Insights (Robust Matching by ID, Mobile, or Name + Fallback to Shop Top Customers)
    val topCustomers = remember(validBills, allCustomers, allBills) {
        val periodCustomerStats = mutableMapOf<String, TopCustomerStat>()

        validBills.forEach { bill ->
            val cleanPhone = PhoneUtils.clean10DigitMobileInput(bill.customerMobile ?: "")
            val matchedCust = when {
                !bill.customerGid.isNullOrBlank() -> allCustomers.find { it.gid == bill.customerGid }
                bill.customerId != null && bill.customerId > 0L -> allCustomers.find { it.id == bill.customerId }
                cleanPhone.length == 10 -> allCustomers.find { PhoneUtils.clean10DigitMobileInput(it.mobile) == cleanPhone }
                !bill.customerName.isNullOrBlank() -> allCustomers.find { it.name.trim().equals(bill.customerName.trim(), ignoreCase = true) }
                else -> null
            }

            val key = matchedCust?.gid?.ifBlank { null }
                ?: matchedCust?.id?.toString()
                ?: if (cleanPhone.length == 10) cleanPhone
                else bill.customerName?.trim() ?: ""

            val displayName = matchedCust?.name
                ?: bill.customerName?.trim()
                ?: if (cleanPhone.length == 10) cleanPhone else ""

            if (displayName.isNotBlank() && !displayName.equals("Walk-in", ignoreCase = true) && !displayName.equals("थेट कस्टमर", ignoreCase = true)) {
                val existing = periodCustomerStats[key]
                val newCount = (existing?.billsCount ?: 0) + 1
                val newSpend = (existing?.totalSpend ?: 0.0) + bill.finalAmount
                val custId = matchedCust?.id ?: 0L
                val custGid = matchedCust?.gid ?: ""
                periodCustomerStats[key] = TopCustomerStat(custId, custGid, displayName, newCount, newSpend)
            }
        }

        val periodTopList = periodCustomerStats.values.sortedByDescending { it.totalSpend }

        if (periodTopList.isNotEmpty()) {
            periodTopList
        } else {
            // Strategy B: Fallback to all-time active bills across customers
            val allTimeCustomerStats = mutableMapOf<String, TopCustomerStat>()
            val activeBills = allBills.filter { !it.isVoided }

            activeBills.forEach { bill ->
                val cleanPhone = PhoneUtils.clean10DigitMobileInput(bill.customerMobile ?: "")
                val matchedCust = when {
                    !bill.customerGid.isNullOrBlank() -> allCustomers.find { it.gid == bill.customerGid }
                    bill.customerId != null && bill.customerId > 0L -> allCustomers.find { it.id == bill.customerId }
                    cleanPhone.length == 10 -> allCustomers.find { PhoneUtils.clean10DigitMobileInput(it.mobile) == cleanPhone }
                    !bill.customerName.isNullOrBlank() -> allCustomers.find { it.name.trim().equals(bill.customerName.trim(), ignoreCase = true) }
                    else -> null
                }

                val key = matchedCust?.gid?.ifBlank { null }
                    ?: matchedCust?.id?.toString()
                    ?: if (cleanPhone.length == 10) cleanPhone
                    else bill.customerName?.trim() ?: ""

                val displayName = matchedCust?.name
                    ?: bill.customerName?.trim()
                    ?: if (cleanPhone.length == 10) cleanPhone else ""

                if (displayName.isNotBlank() && !displayName.equals("Walk-in", ignoreCase = true) && !displayName.equals("थेट कस्टमर", ignoreCase = true)) {
                    val existing = allTimeCustomerStats[key]
                    val newCount = (existing?.billsCount ?: 0) + 1
                    val newSpend = (existing?.totalSpend ?: 0.0) + bill.finalAmount
                    val custId = matchedCust?.id ?: 0L
                    val custGid = matchedCust?.gid ?: ""
                    allTimeCustomerStats[key] = TopCustomerStat(custId, custGid, displayName, newCount, newSpend)
                }
            }

            val allTimeList = allTimeCustomerStats.values.sortedByDescending { it.totalSpend }
            if (allTimeList.isNotEmpty()) {
                allTimeList
            } else {
                // Strategy C: Directly from customer accounts sorted by lifetime spend
                allCustomers
                    .filter { it.lifetimeSpend > 0.01 || it.totalBills > 0 }
                    .sortedByDescending { it.lifetimeSpend }
                    .map { TopCustomerStat(it.id, it.gid, it.name, it.totalBills, it.lifetimeSpend) }
            }
        }
    }

    // Target Progress (Monthly: ₹5,00,000)
    val monthlyTarget = 500000.0
    val targetAchievementPct = if (monthlyTarget > 0) ((totalSales / monthlyTarget) * 100).coerceAtMost(100.0) else 0.0
    val targetRemaining = (monthlyTarget - totalSales).coerceAtLeast(0.0)

    // Daily Closing Integration
    val latestClosing = remember(allClosings) { allClosings.firstOrNull() }

    val thermalPrinterManager = remember { com.matoshree.shopmanager.printer.ThermalPrinterManager(context) }
    val coroutineScope = rememberCoroutineScope()
    var isPrintingReport by remember { mutableStateOf(false) }
    var showPrintOptionsDialog by remember { mutableStateOf(false) }
    var shopSettings by remember { mutableStateOf(PrintShareHelper.ShopSettingsSnapshot()) }

    LaunchedEffect(Unit) {
        shopSettings = PrintShareHelper.loadShopSettingsSnapshot()
    }

    val periodReportTitle = when (period) {
        AnalyticsPeriod.TODAY -> "आजचा अहवाल (Daily Report)"
        AnalyticsPeriod.THIS_WEEK -> "चालू आठवडा अहवाल (Weekly Report)"
        AnalyticsPeriod.THIS_MONTH -> "चालू महिना अहवाल (Monthly Report)"
        AnalyticsPeriod.THIS_YEAR -> "चालू वर्ष अहवाल (Yearly Report)"
        AnalyticsPeriod.CUSTOM -> "कस्टम कालावधी अहवाल (${viewModel.customStartDate.value} ते ${viewModel.customEndDate.value})"
    }

    val performDirectPrint: () -> Unit = {
        if (!isPrintingReport) {
            coroutineScope.launch {
                isPrintingReport = true
                val res = thermalPrinterManager.printAnalyticsReport(
                    periodLabel = periodReportTitle,
                    totalSales = totalSales,
                    totalBillsCount = totalBillsCount,
                    avgBillValue = avgBillValue,
                    cashAmount = cashAmount,
                    upiAmount = upiAmount,
                    otherAmount = otherAmount,
                    totalExpenses = totalExpensesAmount,
                    netProfit = netProfit,
                    topProducts = productStats.take(5),
                    settings = shopSettings
                )
                isPrintingReport = false
                if (res.isSuccess) {
                    Toast.makeText(context, "प्रिंटरवर अहवाल पाठवला!", Toast.LENGTH_SHORT).show()
                } else {
                    PrintShareHelper.printAnalyticsReportSystem(
                        context = context,
                        periodLabel = periodReportTitle,
                        totalSales = totalSales,
                        totalBillsCount = totalBillsCount,
                        avgBillValue = avgBillValue,
                        cashAmount = cashAmount,
                        upiAmount = upiAmount,
                        otherAmount = otherAmount,
                        totalExpenses = totalExpensesAmount,
                        netProfit = netProfit,
                        topProducts = productStats.take(5),
                        settings = shopSettings
                    )
                }
            }
        }
    }

    // Rush Hour / Peak Sales Distribution
    val hourlyStats = remember(validBills) {
        var morningSales = 0.0
        var morningCount = 0
        var afternoonSales = 0.0
        var afternoonCount = 0
        var eveningSales = 0.0
        var eveningCount = 0

        validBills.forEach { bill ->
            val hour = try {
                if (bill.billDate.contains("T")) {
                    bill.billDate.substringAfter("T").take(2).toIntOrNull() ?: 12
                } else if (bill.billDate.length >= 13) {
                    bill.billDate.substring(11, 13).toIntOrNull() ?: 12
                } else 12
            } catch (e: Exception) { 12 }

            when (hour) {
                in 6..13 -> {
                    morningSales += bill.finalAmount
                    morningCount++
                }
                in 14..17 -> {
                    afternoonSales += bill.finalAmount
                    afternoonCount++
                }
                else -> {
                    eveningSales += bill.finalAmount
                    eveningCount++
                }
            }
        }
        Triple(
            Pair(morningCount, morningSales),
            Pair(afternoonCount, afternoonSales),
            Pair(eveningCount, eveningSales)
        )
    }

    // Category Sales Share
    val categoryBreakdown = remember(validBills, allProducts) {
        val catMap = mutableMapOf<String, Double>()
        val prodCatMap = allProducts.associate { it.name to (it.categoryName ?: "इतर साड्या/ड्रेस") }
        validBills.forEach { bill ->
            bill.items.forEach { item ->
                val cat = prodCatMap[item.productName] ?: "इतर साड्या/ड्रेस"
                catMap[cat] = (catMap[cat] ?: 0.0) + item.lineTotal
            }
        }
        val totalCatSales = catMap.values.sum().coerceAtLeast(1.0)
        catMap.entries.sortedByDescending { it.value }.map { entry ->
            val pct = (entry.value / totalCatSales) * 100
            Triple(entry.key, entry.value, pct)
        }
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "व्यवसाय ॲनालिटिक्स",
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                PrintShareHelper.shareColorAnalyticsReport(
                                    context = context,
                                    periodLabel = periodReportTitle,
                                    totalSales = totalSales,
                                    totalBillsCount = totalBillsCount,
                                    avgBillValue = avgBillValue,
                                    cashAmount = cashAmount,
                                    upiAmount = upiAmount,
                                    otherAmount = otherAmount,
                                    totalExpenses = totalExpensesAmount,
                                    netProfit = netProfit,
                                    topProducts = productStats.take(5),
                                    settings = shopSettings
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "कलर रिपोर्ट शेअर करा", tint = DeepEmerald)
                    }
                    IconButton(onClick = { performDirectPrint() }) {
                        Icon(Icons.Default.Print, contentDescription = "रिपोर्ट प्रिंट करा", tint = DeepEmerald)
                    }
                    IconButton(onClick = { viewModel.refreshData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "रिफ्रेश करा", tint = DeepEmerald)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)
        ) {
            // 1. Period Selector Chips (Part 2)
            item {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AnalyticsPeriod.values().forEach { p ->
                            val isSelected = period == p
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (p == AnalyticsPeriod.CUSTOM) {
                                        showCustomDateDialog = true
                                    } else {
                                        viewModel.selectedPeriod.value = p
                                    }
                                },
                                label = {
                                    Text(
                                        text = p.label,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = DeepEmerald,
                                    selectedLabelColor = WarmWhite
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "शेवटचे अपडेट: $lastUpdated",
                            style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal)
                        )
                        if (period == AnalyticsPeriod.CUSTOM && viewModel.customStartDate.value.isNotBlank()) {
                            Text(
                                text = "${viewModel.customStartDate.value} → ${viewModel.customEndDate.value}",
                                style = MaterialTheme.typography.labelSmall.copy(color = DeepEmerald, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            // 2. Top Summary KPI Cards (Part 3)
            item {
                MatoshreeCard(hasGoldTopBorder = true) {
                    Text(
                        text = "एकूण सारांश (${period.label.uppercase()})",
                        style = MaterialTheme.typography.labelLarge.copy(color = MutedCharcoal, letterSpacing = 1.sp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = CurrencyFormatter.format(totalSales),
                                style = MaterialTheme.typography.displayMedium.copy(
                                    color = DeepEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "एकूण $totalBillsCount बिलांमधून झालेली विक्री",
                                style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(DeepEmeraldContainer.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.TrendingUp, contentDescription = null, tint = DeepEmerald)
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = OutlineVariantGrey.copy(alpha = 0.2f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("सरासरी बिल", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                            Text(CurrencyFormatter.format(avgBillValue), fontWeight = FontWeight.Bold, color = DeepCharcoal)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("कस्टमर्स", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                            Text("${allCustomers.size}", fontWeight = FontWeight.Bold, color = DeepCharcoal)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("एकूण प्रॉडक्ट्स", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                            Text("${allProducts.size}", fontWeight = FontWeight.Bold, color = DeepCharcoal)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { performDirectPrint() },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isPrintingReport) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = DeepEmerald)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("प्रिंट होत आहे...", fontSize = 12.sp, color = DeepEmerald)
                            } else {
                                Icon(Icons.Default.Print, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("प्रिंट करा", fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 13.sp)
                            }
                        }

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    PrintShareHelper.shareColorAnalyticsReport(
                                        context = context,
                                        periodLabel = periodReportTitle,
                                        totalSales = totalSales,
                                        totalBillsCount = totalBillsCount,
                                        avgBillValue = avgBillValue,
                                        cashAmount = cashAmount,
                                        upiAmount = upiAmount,
                                        otherAmount = otherAmount,
                                        totalExpenses = totalExpensesAmount,
                                        netProfit = netProfit,
                                        topProducts = productStats.take(5),
                                        settings = shopSettings
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1.3f)
                                .height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = WarmWhite, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("रंगीत रिपोर्ट शेअर / सेव्ह करा (WhatsApp इमेज)", fontWeight = FontWeight.Bold, color = WarmWhite, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // 3. Profit Analysis Card (Parts 4 & 5)
            item {
                MatoshreeCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "नफा विश्लेषण",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                        )
                        StatusBadge(
                            status = "${String.format("%.1f", profitMarginPct)}% मार्जिन",
                            isSuccess = profitMarginPct >= 20.0,
                            isWarning = profitMarginPct in 10.0..19.9
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("एकूण विक्री", style = MaterialTheme.typography.bodyMedium)
                        Text(CurrencyFormatter.format(totalSales), fontWeight = FontWeight.SemiBold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("खरेदी किंमत (Cost)", style = MaterialTheme.typography.bodyMedium)
                        Text("-${CurrencyFormatter.format(totalCostAmount)}", color = BoutiqueError)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("दुकानाचे खर्च", style = MaterialTheme.typography.bodyMedium)
                        Text("-${CurrencyFormatter.format(totalExpensesAmount)}", color = BoutiqueError)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = OutlineVariantGrey.copy(alpha = 0.2f))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("निव्वळ नफा (Net Profit)", fontWeight = FontWeight.Bold, color = DeepEmerald)
                        Text(CurrencyFormatter.format(netProfit), fontWeight = FontWeight.Bold, color = if (netProfit >= 0) BoutiqueSuccess else BoutiqueError)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "नोंदवलेला प्रत्यक्ष नफा: ${CurrencyFormatter.format(actualProfit)} • अंदाजे नफा (२५%): ${CurrencyFormatter.format(estimatedProfit)}",
                        style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal)
                    )
                }
            }

            // 4. Visual Sales Trend Chart (Part 6)
            item {
                MatoshreeCard {
                    Text(
                        text = "विक्री आलेख (Trend)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val trendPoints = remember(validBills, period) {
                        val map = mutableMapOf<String, Pair<Double, Int>>()
                        validBills.forEach { bill ->
                            val key = when (period) {
                                AnalyticsPeriod.TODAY -> DateUtils.formatTimeOnly(bill.billDate).take(2) + "h"
                                AnalyticsPeriod.THIS_YEAR -> bill.billDate.take(7).drop(5) + "M"
                                else -> bill.billDate.take(10).drop(8) + "d"
                            }
                            val (currSales, currBills) = map[key] ?: Pair(0.0, 0)
                            map[key] = Pair(currSales + bill.finalAmount, currBills + 1)
                        }
                        map.map { TrendPoint(it.key, it.value.first, it.value.second) }.takeLast(7)
                    }

                    if (trendPoints.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("या कालावधीसाठी विक्री डेटा उपलब्ध नाही.", color = MutedCharcoal, fontSize = 13.sp)
                        }
                    } else {
                        val maxSale = (trendPoints.maxOfOrNull { it.sales } ?: 1.0).coerceAtLeast(100.0)
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                trendPoints.forEach { point ->
                                    val barHeightRatio = (point.sales / maxSale).toFloat().coerceIn(0.05f, 1f)
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Bottom,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "₹${point.sales.toInt()}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .width(28.dp)
                                                .fillMaxHeight(barHeightRatio)
                                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                                .background(DeepEmerald)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = point.label,
                                            style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal, fontSize = 10.sp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Payment Breakdown Card (Part 7)
            item {
                MatoshreeCard {
                    Text(
                        text = "पेमेंट पद्धत वर्गीकरण",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val cashPct = if (totalSales > 0) (cashAmount / totalSales) * 100 else 0.0
                    val upiPct = if (totalSales > 0) (upiAmount / totalSales) * 100 else 0.0
                    val otherPct = if (totalSales > 0) (otherAmount / totalSales) * 100 else 0.0

                    // Cash Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("कॅश / रोख (${cashBills.size} बिले)", fontWeight = FontWeight.Medium)
                        }
                        Text("${CurrencyFormatter.format(cashAmount)} (${String.format("%.1f", cashPct)}%)", fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { (cashPct / 100.0).toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = DeepEmerald,
                        trackColor = OutlineVariantGrey.copy(alpha = 0.2f)
                    )

                    // UPI Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = ChampagneGold, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("यूपीआय / क्यूआर (${upiBills.size} बिले)", fontWeight = FontWeight.Medium)
                        }
                        Text("${CurrencyFormatter.format(upiAmount)} (${String.format("%.1f", upiPct)}%)", fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(
                        progress = { (upiPct / 100.0).toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = ChampagneGold,
                        trackColor = OutlineVariantGrey.copy(alpha = 0.2f)
                    )

                    if (otherAmount > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("इतर (${otherBills.size} बिले)", fontWeight = FontWeight.Medium)
                            Text("${CurrencyFormatter.format(otherAmount)} (${String.format("%.1f", otherPct)}%)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Futuristic: Peak Rush Hours Analyzer
            item {
                MatoshreeCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "गर्दी व पीक टाईम विश्लेषण",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                        )
                        val maxSlot = maxOf(hourlyStats.first.second, hourlyStats.second.second, hourlyStats.third.second)
                        val peakLabel = when {
                            maxSlot <= 0.01 -> "थेट नोंद"
                            maxSlot == hourlyStats.first.second -> "सकाळी जास्त विक्री"
                            maxSlot == hourlyStats.second.second -> "दुपारी जास्त विक्री"
                            else -> "संध्याकाळी सर्वाधिक गर्दी 🔥"
                        }
                        StatusBadge(
                            status = peakLabel,
                            isSuccess = true
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    val maxSales = maxOf(hourlyStats.first.second, hourlyStats.second.second, hourlyStats.third.second).coerceAtLeast(1.0)

                    // Morning Slot
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("🌅 सकाळ (१० ते २)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${CurrencyFormatter.format(hourlyStats.first.second)} (${hourlyStats.first.first} बिले)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeepEmerald)
                        }
                        LinearProgressIndicator(
                            progress = { (hourlyStats.first.second / maxSales).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = DeepEmerald,
                            trackColor = OutlineVariantGrey.copy(alpha = 0.2f)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Afternoon Slot
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("☀️ दुपार (२ ते ६)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${CurrencyFormatter.format(hourlyStats.second.second)} (${hourlyStats.second.first} बिले)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ChampagneGold)
                        }
                        LinearProgressIndicator(
                            progress = { (hourlyStats.second.second / maxSales).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = ChampagneGold,
                            trackColor = OutlineVariantGrey.copy(alpha = 0.2f)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Evening Slot
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("🌙 संध्याकाळ (६ ते १०)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${CurrencyFormatter.format(hourlyStats.third.second)} (${hourlyStats.third.first} बिले)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BoutiqueSuccess)
                        }
                        LinearProgressIndicator(
                            progress = { (hourlyStats.third.second / maxSales).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = BoutiqueSuccess,
                            trackColor = OutlineVariantGrey.copy(alpha = 0.2f)
                        )
                    }
                }
            }

            // Futuristic: Category Sales Share
            if (categoryBreakdown.isNotEmpty()) {
                item {
                    MatoshreeCard {
                        Text(
                            text = "कॅटेगरीनुसार विक्री वाटा",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        categoryBreakdown.take(5).forEach { (catName, catSales, catPct) ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(catName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${CurrencyFormatter.format(catSales)} (${String.format("%.1f", catPct)}%)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeepEmerald)
                                }
                                LinearProgressIndicator(
                                    progress = { (catPct / 100.0).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = DeepEmerald,
                                    trackColor = OutlineVariantGrey.copy(alpha = 0.2f)
                                )
                            }
                        }
                    }
                }
            }

            // 6. Expense Analytics (Part 8)
            item {
                MatoshreeCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "खर्च वर्गीकरण",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                        )
                        Text(
                            text = "एकूण: ${CurrencyFormatter.format(totalExpensesAmount)}",
                            fontWeight = FontWeight.Bold,
                            color = BoutiqueError
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    if (expenseCategories.isEmpty()) {
                        Text("या कालावधीत कोणतेही खर्च नोंदवलेले नाहीत.", color = MutedCharcoal, fontSize = 13.sp)
                    } else {
                        expenseCategories.forEach { (cat, amt) ->
                            val pct = if (totalExpensesAmount > 0) (amt / totalExpensesAmount) * 100 else 0.0
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(cat, style = MaterialTheme.typography.bodyMedium)
                                Text("${CurrencyFormatter.format(amt)} (${String.format("%.0f", pct)}%)", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // 7. Top Selling Products (Part 9)
            item {
                MatoshreeCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "सर्वाधिक विकले जाणारे प्रॉडक्ट्स",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = prodSort == "REVENUE",
                                onClick = { viewModel.productSortBy.value = "REVENUE" },
                                label = { Text("रक्कम", fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = prodSort == "QUANTITY",
                                onClick = { viewModel.productSortBy.value = "QUANTITY" },
                                label = { Text("नग", fontSize = 11.sp) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (productStats.isEmpty()) {
                        Text("या कालावधीत कोणतीही विक्री नोंद सापडली नाही.", color = MutedCharcoal, fontSize = 13.sp)
                    } else {
                        productStats.take(5).forEachIndexed { idx, p ->
                            val medal = when(idx) {
                                0 -> "🥇"
                                1 -> "🥈"
                                2 -> "🥉"
                                else -> "#${idx + 1}"
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Text(medal, fontSize = 16.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(p.name, fontWeight = FontWeight.SemiBold)
                                        Text("${p.quantity} नग विक्री", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(CurrencyFormatter.format(p.revenue), fontWeight = FontWeight.Bold, color = DeepEmerald)
                                    if (p.profit > 0) {
                                        Text("+${CurrencyFormatter.format(p.profit)} नफा", fontSize = 11.sp, color = BoutiqueSuccess, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 8. Customer Insights (Part 10)
            item {
                MatoshreeCard {
                    Text(
                        text = "प्रमुख ग्राहक (Top Customers)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    if (topCustomers.isEmpty()) {
                        Text("या कालावधीत कोणताही ग्राहक खरेदी डेटा उपलब्ध नाही.", color = MutedCharcoal, fontSize = 13.sp)
                    } else {
                        topCustomers.take(5).forEach { cust ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = cust.customerGid.isNotBlank() || cust.customerId > 0L) {
                                        onCustomerClick(cust.customerGid.ifBlank { "id_${cust.customerId}" })
                                    }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cust.name, fontWeight = FontWeight.SemiBold, color = DeepCharcoal)
                                    Text("${cust.billsCount} बिले तयार केली", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                }
                                Text(CurrencyFormatter.format(cust.totalSpend), fontWeight = FontWeight.Bold, color = DeepEmerald)
                            }
                        }
                    }
                }
            }

            // 9. Bill Analytics (Part 11)
            item {
                MatoshreeCard {
                    Text(
                        text = "बिल आकडेवारी",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("एकूण तयार केलेली बिले", style = MaterialTheme.typography.bodyMedium)
                        Text("$totalBillsCount", fontWeight = FontWeight.SemiBold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("सरासरी बिल रक्कम", style = MaterialTheme.typography.bodyMedium)
                        Text(CurrencyFormatter.format(avgBillValue), fontWeight = FontWeight.SemiBold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("सर्वाधिक रकमेचे बिल", style = MaterialTheme.typography.bodyMedium)
                        Text(CurrencyFormatter.format(highestBill), fontWeight = FontWeight.SemiBold, color = DeepEmerald)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("किमान रकमेचे बिल", style = MaterialTheme.typography.bodyMedium)
                        Text(CurrencyFormatter.format(lowestBill), fontWeight = FontWeight.SemiBold)
                    }
                    if (voidedBills.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("रद्द केलेली बिले (वगळलेली)", style = MaterialTheme.typography.bodyMedium, color = BoutiqueError)
                            Text("${voidedBills.size}", fontWeight = FontWeight.Bold, color = BoutiqueError)
                        }
                    }
                }
            }

            // 10. Monthly Target Progress (Part 12)
            item {
                MatoshreeCard(hasGoldTopBorder = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("मासिक विक्री उद्दिष्ट", fontWeight = FontWeight.Bold, color = DeepEmerald)
                        Text("${String.format("%.1f", targetAchievementPct)}% पूर्ण", fontWeight = FontWeight.Bold, color = BoutiqueSuccess)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { (targetAchievementPct / 100.0).toFloat().coerceIn(0.02f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = ChampagneGold,
                        trackColor = OutlineVariantGrey.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("उद्दिष्ट: ${CurrencyFormatter.format(monthlyTarget)}", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        Text("शिल्लक: ${CurrencyFormatter.format(targetRemaining)}", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontWeight = FontWeight.Bold))
                    }
                }
            }

            // 11. Daily Closing Status (Part 13)
            latestClosing?.let { closing ->
                item {
                    MatoshreeCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("दैनिक कॅश क्लोजिंग", fontWeight = FontWeight.Bold, color = DeepEmerald)
                            val statusStr = when {
                                closing.cashDifference == 0.0 -> "संतुलित"
                                closing.cashDifference > 0 -> "जास्त रक्कम"
                                else -> "कमी रक्कम"
                            }
                            StatusBadge(
                                status = statusStr,
                                isSuccess = closing.cashDifference == 0.0,
                                isWarning = closing.cashDifference > 0,
                                isError = closing.cashDifference < 0
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("अपेक्षित कॅश", style = MaterialTheme.typography.bodyMedium)
                            Text(CurrencyFormatter.format(closing.expectedCash), fontWeight = FontWeight.SemiBold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("मोजलेली प्रत्यक्ष कॅश", style = MaterialTheme.typography.bodyMedium)
                            Text(CurrencyFormatter.format(closing.actualCash), fontWeight = FontWeight.SemiBold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("फरक", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${if (closing.cashDifference > 0) "+" else ""}${CurrencyFormatter.format(closing.cashDifference)}",
                                fontWeight = FontWeight.Bold,
                                color = if (closing.cashDifference == 0.0) BoutiqueSuccess else BoutiqueError
                            )
                        }
                    }
                }
            }
        }
    }

    // Custom Date Range Dialog
    if (showCustomDateDialog) {
        var startInput by remember { mutableStateOf(viewModel.customStartDate.value.ifBlank { "2026-08-01" }) }
        var endInput by remember { mutableStateOf(viewModel.customEndDate.value.ifBlank { "2026-08-30" }) }

        AlertDialog(
            onDismissRequest = { showCustomDateDialog = false },
            title = { Text("तारीख निवडा (Date Range)", fontWeight = FontWeight.Bold, color = DeepEmerald) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("YYYY-MM-DD फॉरमॅटमध्ये तारीख टाका:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                    OutlinedTextField(
                        value = startInput,
                        onValueChange = { startInput = it },
                        label = { Text("सुरुवातीची तारीख (YYYY-MM-DD)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = endInput,
                        onValueChange = { endInput = it },
                        label = { Text("शेवटची तारीख (YYYY-MM-DD)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setCustomRange(startInput.trim(), endInput.trim())
                        showCustomDateDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("लागू करा")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDateDialog = false }) {
                    Text("रद्द करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Print Options Dialog for Analytics (Thermal 58mm & System Print / PDF)
    if (showPrintOptionsDialog) {
        AlertDialog(
            onDismissRequest = { if (!isPrintingReport) showPrintOptionsDialog = false },
            icon = {
                Icon(
                    Icons.Default.Print,
                    contentDescription = null,
                    tint = DeepEmerald,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "अहवाल प्रिंट करा (Print Report)",
                    fontWeight = FontWeight.Bold,
                    color = DeepEmerald,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "कालावधी: ${period.label}\n($periodReportTitle)",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = DeepCharcoal
                        )
                    )

                    Surface(
                        color = ChampagneGoldContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("एकूण विक्री:", fontSize = 12.sp, color = MutedCharcoal)
                                Text(CurrencyFormatter.format(totalSales), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeepEmerald)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("एकूण बिले:", fontSize = 12.sp, color = MutedCharcoal)
                                Text("$totalBillsCount बिले", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("निव्वळ नफा:", fontSize = 12.sp, color = MutedCharcoal)
                                Text(CurrencyFormatter.format(netProfit), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeepEmerald)
                            }
                        }
                    }

                    Text(
                        text = "प्रिंटरचा प्रकार निवडा:",
                        style = MaterialTheme.typography.labelMedium.copy(color = MutedCharcoal)
                    )

                    // Option 1: 58mm Bluetooth Thermal Printer
                    Button(
                        onClick = {
                            if (isPrintingReport) return@Button
                            coroutineScope.launch {
                                isPrintingReport = true
                                val res = thermalPrinterManager.printAnalyticsReport(
                                    periodLabel = periodReportTitle,
                                    totalSales = totalSales,
                                    totalBillsCount = totalBillsCount,
                                    avgBillValue = avgBillValue,
                                    cashAmount = cashAmount,
                                    upiAmount = upiAmount,
                                    otherAmount = otherAmount,
                                    totalExpenses = totalExpensesAmount,
                                    netProfit = netProfit,
                                    topProducts = productStats.take(5),
                                    settings = shopSettings
                                )
                                isPrintingReport = false
                                if (res.isSuccess) {
                                    Toast.makeText(context, "थर्मल प्रिंट यशस्वी झाली!", Toast.LENGTH_SHORT).show()
                                    showPrintOptionsDialog = false
                                } else {
                                    val err = res.exceptionOrNull()?.message ?: "प्रिंट एरर"
                                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isPrintingReport,
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isPrintingReport) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = WarmWhite)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("प्रिंट पाठवत आहे...")
                        } else {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("५८ मिमी थर्मल प्रिंटरवर प्रिंट करा")
                        }
                    }

                    // Option 2: System Print / PDF Export
                    OutlinedButton(
                        onClick = {
                            PrintShareHelper.printAnalyticsReportSystem(
                                context = context,
                                periodLabel = periodReportTitle,
                                totalSales = totalSales,
                                totalBillsCount = totalBillsCount,
                                avgBillValue = avgBillValue,
                                cashAmount = cashAmount,
                                upiAmount = upiAmount,
                                otherAmount = otherAmount,
                                totalExpenses = totalExpensesAmount,
                                netProfit = netProfit,
                                topProducts = productStats.take(5),
                                settings = shopSettings
                            )
                            showPrintOptionsDialog = false
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp), tint = DeepEmerald)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("सिस्टम प्रिंट / रंगीत PDF सेव्ह करा", color = DeepEmerald)
                    }

                    // Option 3: Share / Save Colorful Report Image
                    OutlinedButton(
                        onClick = {
                            PrintShareHelper.shareColorAnalyticsReport(
                                context = context,
                                periodLabel = periodReportTitle,
                                totalSales = totalSales,
                                totalBillsCount = totalBillsCount,
                                avgBillValue = avgBillValue,
                                cashAmount = cashAmount,
                                upiAmount = upiAmount,
                                otherAmount = otherAmount,
                                totalExpenses = totalExpensesAmount,
                                netProfit = netProfit,
                                topProducts = productStats.take(5),
                                settings = shopSettings
                            )
                            showPrintOptionsDialog = false
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = DeepEmerald)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("रंगीत रिपोर्ट शेअर / सेव्ह करा (WhatsApp/Image)", color = DeepEmerald)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showPrintOptionsDialog = false },
                    enabled = !isPrintingReport
                ) {
                    Text("रद्द करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

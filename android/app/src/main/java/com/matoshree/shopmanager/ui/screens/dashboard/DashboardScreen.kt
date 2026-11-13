package com.matoshree.shopmanager.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.data.repository.UdhariRepository
import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.domain.model.DashboardSummary
import com.matoshree.shopmanager.domain.model.UdhariTransaction
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.StatusBadge
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.domain.usecase.ShopMetricsCalculator
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private data class DashboardMetrics(
    val summary: DashboardSummary,
    val todayCollected: Double,
    val estProfit: Double,
    val totalPending: Double,
    val pendingCustCount: Int
)

class DashboardViewModel(
    private val billRepository: BillRepository,
    private val udhariRepository: UdhariRepository = UdhariRepository(),
    private val productRepository: com.matoshree.shopmanager.data.repository.ProductRepository? = null
) : ViewModel() {

    private val _summary = MutableStateFlow(DashboardSummary())
    val summary: StateFlow<DashboardSummary> = _summary

    val udhariOutstanding = MutableStateFlow(0.0)
    val udhariPendingCount = MutableStateFlow(0)
    val lowStockCount = MutableStateFlow(0)
    val todayCollectedCredit = MutableStateFlow(0.0)
    val todayProfitEstimate = MutableStateFlow(0.0)

    private val errorHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("DashboardViewModel", "Dashboard error caught safely: ${throwable.message}", throwable)
    }

    init {
        observeBills()
        observeProducts()
    }

    private fun observeProducts() {
        if (productRepository != null) {
            viewModelScope.launch(errorHandler) {
                productRepository.getAllProducts()
                    .catch { e -> android.util.Log.e("DashboardViewModel", "Products flow error: ${e.message}", e) }
                    .collectLatest { prods ->
                        val newCount = prods.count { it.isActive && it.trackInventory && it.currentStock <= 5 }
                        if (lowStockCount.value != newCount) {
                            lowStockCount.value = newCount
                        }
                    }
            }
        }
    }

    private val allBillsState = MutableStateFlow<List<Bill>>(emptyList())
    private val allTransactionsState = MutableStateFlow<List<UdhariTransaction>>(emptyList())

    private val currentDateFlow = flow {
        while (true) {
            emit(DateUtils.todayDate())
            delay(60_000L)
        }
    }.distinctUntilChanged()

    private fun observeBills() {
        // 1. Observe all bills from Room immediately (instant UI load, never blocked by network or Udhari)
        viewModelScope.launch(errorHandler) {
            billRepository.getAllBills(limit = 500)
                .distinctUntilChanged()
                .catch { e -> android.util.Log.e("DashboardViewModel", "Bills flow error: ${e.message}", e) }
                .collectLatest { bills ->
                    allBillsState.value = bills
                    recalculateMetrics()
                }
        }

        // 2. Observe Udhari transactions independently in parallel
        viewModelScope.launch(errorHandler) {
            udhariRepository.getAllTransactions()
                .distinctUntilChanged()
                .catch { e -> android.util.Log.e("DashboardViewModel", "Udhari flow error: ${e.message}", e) }
                .collectLatest { txs ->
                    allTransactionsState.value = txs
                    recalculateMetrics()
                }
        }

        // 3. Periodic minute-ticker for date transition (e.g. crossing midnight)
        viewModelScope.launch(errorHandler) {
            currentDateFlow.collectLatest {
                recalculateMetrics()
            }
        }
    }

    private fun recalculateMetrics() {
        try {
            val allBills = allBillsState.value
            val transactions = allTransactionsState.value
            val today = DateUtils.todayDate()
            val monthPrefix = today.take(7)

            val allActiveBills = allBills
                .filter { !it.isVoided }
                .distinctBy { it.canonicalKey() }
                .sortedWith(
                    compareByDescending<Bill> { it.billDate }.thenByDescending { it.id }
                )

            val activeTodayBills = allActiveBills.filter { DateUtils.isToday(it.billDate) }
            val activeWeekBills = allActiveBills.filter { DateUtils.isThisWeek(it.billDate) }

            val isShowingToday = activeTodayBills.isNotEmpty()
            val displayBills = when {
                isShowingToday -> activeTodayBills
                activeWeekBills.isNotEmpty() -> activeWeekBills
                allActiveBills.isNotEmpty() -> allActiveBills.take(15)
                else -> emptyList()
            }

            val billsHeaderTitle = when {
                isShowingToday -> "आजची बिले (${activeTodayBills.size})"
                activeWeekBills.isNotEmpty() -> "या आठवड्याची बिले (${activeWeekBills.size})"
                allActiveBills.isNotEmpty() -> "अलीकडील बिले (${displayBills.size})"
                else -> "आजची बिले (०)"
            }

            val todaySales = ShopMetricsCalculator.calculateTodaySales(activeTodayBills, today)
            val todayBillsCount = activeTodayBills.size
            val avgOrder = if (todayBillsCount > 0) todaySales / todayBillsCount else 0.0

            val cashSales = ShopMetricsCalculator.calculateCashSales(activeTodayBills)
            val upiSales = ShopMetricsCalculator.calculateUpiSales(activeTodayBills)

            val allMonthSales = ShopMetricsCalculator.calculateMonthlySales(allBills, monthPrefix)
            val target = 500000.0
            val progress = ((allMonthSales / target) * 100).coerceAtMost(100.0)

            val totalPending = ShopMetricsCalculator.calculateTotalPendingUdhari(allBills, transactions)
            val pendingCustCount = ShopMetricsCalculator.calculatePendingCustomersCount(allBills, transactions)
            val todayCollected = ShopMetricsCalculator.calculateTodayCollectedCredit(transactions, today)
            val estProfit = ShopMetricsCalculator.calculateTodayProfit(activeTodayBills, today)

            val newSummary = DashboardSummary(
                todaySales = todaySales,
                todayBillsCount = todayBillsCount,
                todayProfit = estProfit,
                averageOrderValue = avgOrder,
                monthlySales = allMonthSales,
                monthlyTarget = target,
                targetProgressPercent = progress,
                cashPayments = cashSales,
                upiPayments = upiSales,
                recentBills = displayBills,
                isShowingTodayBills = isShowingToday,
                billsHeaderTitle = billsHeaderTitle,
                hasLoadedOnce = true,
                businessInsight = if (todayBillsCount > 0) "Today: $todayBillsCount bills generated with avg ₹${String.format(java.util.Locale.US, "%.0f", avgOrder)}" else "Ready for today's sales at Matoshree Collection."
            )

            if (todayCollectedCredit.value != todayCollected) {
                todayCollectedCredit.value = todayCollected
            }
            if (todayProfitEstimate.value != estProfit) {
                todayProfitEstimate.value = estProfit
            }
            if (udhariOutstanding.value != totalPending) {
                udhariOutstanding.value = totalPending
            }
            if (udhariPendingCount.value != pendingCustCount) {
                udhariPendingCount.value = pendingCustCount
            }
            if (_summary.value != newSummary) {
                _summary.value = newSummary
            }
        } catch (e: Exception) {
            android.util.Log.e("DashboardViewModel", "Error recalculating dashboard metrics: ${e.message}", e)
        }
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    syncManager: com.matoshree.shopmanager.sync.SyncManager? = null,
    onNewSaleClick: () -> Unit,
    onQuickSaleClick: () -> Unit,
    onBillClick: (Long) -> Unit,
    onViewAllBillsClick: () -> Unit,
    onProductsClick: () -> Unit = {},
    onExpensesClick: () -> Unit = {},
    onClosingClick: () -> Unit = {},
    onUdhariClick: () -> Unit = {},
    onInventoryClick: () -> Unit = {}
) {
    val summary by viewModel.summary.collectAsState()
    val totalUdhari by viewModel.udhariOutstanding.collectAsState()
    val pendingCustCount by viewModel.udhariPendingCount.collectAsState()
    val todayProfit by viewModel.todayProfitEstimate.collectAsState()
    val todayCreditCollected by viewModel.todayCollectedCredit.collectAsState()
    val lowStockCount by viewModel.lowStockCount.collectAsState()

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "मातोश्री कलेक्शन",
                actions = {
                    IconButton(onClick = onViewAllBillsClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ReceiptLong,
                            contentDescription = "Bills History",
                            tint = DeepEmerald
                        )
                    }
                    IconButton(onClick = onQuickSaleClick) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Quick Sale",
                            tint = ChampagneGold
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewSaleClick,
                containerColor = DeepEmerald,
                contentColor = WarmWhite,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Default.AddShoppingCart, contentDescription = "नवीन बिल") },
                text = { Text("नवीन बिल", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isTablet = configuration.screenWidthDp >= 600

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .padding(horizontal = if (isTablet) 24.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp)
            ) {
            // Live Status Banner
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "डॅशबोर्ड",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = DeepEmerald,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(DeepEmeraldContainer.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(BoutiqueSuccess)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "क्लाउड सिंक सक्रिय",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = DeepEmerald,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }

            // Quick Sales Hero Card with Profit Ticker & Channel Breakdown
            item {
                MatoshreeCard(hasGoldTopBorder = true) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "आजची एकूण विक्री",
                                    style = MaterialTheme.typography.labelMedium.copy(color = MutedCharcoal, letterSpacing = 1.sp)
                                )
                                Text(
                                    text = CurrencyFormatter.format(summary.todaySales),
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        color = DeepEmerald,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Surface(
                                    color = ChampagneGold.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, ChampagneGold.copy(alpha = 0.6f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.TrendingUp, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "+${CurrencyFormatter.format(todayProfit)} नफा",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = DeepEmerald
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "अंदाजे मार्जिन",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = MutedCharcoal)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Live Payment Channels (Cash, UPI, Credit Collection)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(WarmIvory, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("💵 कॅश:", fontSize = 11.sp, color = MutedCharcoal)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(CurrencyFormatter.format(summary.cashPayments), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DeepEmerald)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📱 यूपीआय:", fontSize = 11.sp, color = MutedCharcoal)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(CurrencyFormatter.format(summary.upiPayments), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ChampagneGold)
                            }
                            if (todayCreditCollected > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🤝 जमा:", fontSize = 11.sp, color = MutedCharcoal)
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("+${CurrencyFormatter.format(todayCreditCollected)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BoutiqueSuccess)
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = OutlineVariantGrey.copy(alpha = 0.2f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("एकूण बिले", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 11.sp))
                                Text("${summary.todayBillsCount}", fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 14.sp)
                            }
                            Column {
                                Text("सरासरी बिल", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 11.sp))
                                Text(CurrencyFormatter.format(summary.averageOrderValue), fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 14.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("चालू महिना विक्री", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 11.sp))
                                Text(CurrencyFormatter.format(summary.monthlySales), fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // UDHARI / CREDIT OUTSTANDING CARD
            if (totalUdhari > 0) {
                item {
                    MatoshreeCard(
                        hasGoldTopBorder = true,
                        modifier = Modifier.clickable(onClick = onUdhariClick)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(BoutiqueError.copy(alpha = 0.12f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = "उधारी", tint = BoutiqueError, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("एकूण उधारी बाकी", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text("$pendingCustCount कस्टमर्सची बाकी", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal), maxLines = 1)
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = CurrencyFormatter.format(totalUdhari),
                                    style = MaterialTheme.typography.titleLarge.copy(color = BoutiqueError, fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text("उधारी खाते पहा →", style = MaterialTheme.typography.labelSmall.copy(color = DeepEmerald, fontWeight = FontWeight.SemiBold), maxLines = 1)
                            }
                        }
                    }
                }
            }

            // INVENTORY & STOCK HEALTH CARD
            item {
                MatoshreeCard(
                    hasGoldTopBorder = true,
                    modifier = Modifier.clickable(onClick = onInventoryClick)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(DeepEmeraldContainer.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Inventory2, contentDescription = "इन्व्हेंटरी", tint = DeepEmerald, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("इन्व्हेंटरी व स्टॉक व्यवस्थापन", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text("आणलेला, खपलेला व शिल्लक माल ट्रॅक करा", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal), maxLines = 1)
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(horizontalAlignment = Alignment.End) {
                            Text("स्टॉक डॅशबोर्ड →", style = MaterialTheme.typography.labelSmall.copy(color = DeepEmerald, fontWeight = FontWeight.Bold), maxLines = 1)
                        }
                    }
                }
            }

            // Quick Management Actions (Udhari, Products, Expenses, Daily Closing)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MatoshreeCard(modifier = Modifier.weight(1f), onClick = onUdhariClick) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = "उधारी", tint = if (totalUdhari > 0) BoutiqueError else DeepEmerald, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("उधारी", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = DeepEmerald, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }

                    MatoshreeCard(modifier = Modifier.weight(1f), onClick = onProductsClick) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp)) {
                            Icon(Icons.Outlined.Inventory2, contentDescription = "प्रॉडक्ट्स", tint = DeepEmerald, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("प्रॉडक्ट्स", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = DeepEmerald, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }

                    MatoshreeCard(modifier = Modifier.weight(1f), onClick = onExpensesClick) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp)) {
                            Icon(Icons.Outlined.Receipt, contentDescription = "खर्च", tint = DeepEmerald, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("खर्च", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = DeepEmerald, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }

                    MatoshreeCard(modifier = Modifier.weight(1f), onClick = onClosingClick) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 6.dp)) {
                            Icon(Icons.Outlined.LockClock, contentDescription = "क्लोजिंग", tint = DeepEmerald, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("क्लोजिंग", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = DeepEmerald, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Recent Bills Header (Today's Bills or Week's Bills)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = summary.billsHeaderTitle,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                    )
                    TextButton(onClick = onViewAllBillsClick) {
                        Text("सर्व पहा", color = DeepEmerald, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Recent Bills List: Explicit empty state if no bills
            if (summary.recentBills.isEmpty()) {
                item {
                    MatoshreeCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ReceiptLong,
                                contentDescription = null,
                                tint = MutedCharcoal,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "अद्याप कोणतीही बिले तयार केलेली नाहीत.",
                                color = MutedCharcoal,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Button(
                                onClick = onViewAllBillsClick,
                                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("सर्व बिले पहा →", color = WarmWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                if (isTablet) {
                    val pairs = summary.recentBills.chunked(2)
                    itemsIndexed(pairs, key = { index, pair -> "dash_pair_${index}_${pair[0].id}_${pair[0].transactionUuid}_${pair.getOrNull(1)?.id ?: "end"}" }) { _, pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                DashboardRecentBillCard(bill = pair[0], onBillClick = onBillClick)
                            }
                            if (pair.size > 1) {
                                Box(modifier = Modifier.weight(1f)) {
                                    DashboardRecentBillCard(bill = pair[1], onBillClick = onBillClick)
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    itemsIndexed(summary.recentBills, key = { index, bill -> "dash_b_${index}_${bill.id}_${bill.canonicalKey()}" }) { _, bill ->
                        DashboardRecentBillCard(bill = bill, onBillClick = onBillClick)
                    }
                }
            }
        }
        }
    }
}

@Composable
fun DashboardRecentBillCard(bill: Bill, onBillClick: (Long) -> Unit) {
    MatoshreeCard(onClick = { onBillClick(bill.id) }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bill.billNumber,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = DeepEmerald,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (bill.pendingAmount > 0.01) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(बाकी: ${CurrencyFormatter.format(bill.pendingAmount)})",
                            color = BoutiqueError,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
                Text(
                    text = "${bill.customerName ?: "थेट कस्टमर"} • ${if (bill.paymentMethod.name == "CASH") "कॅश" else "यूपीआय"}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = CurrencyFormatter.format(bill.finalAmount),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.height(4.dp))
                StatusBadge(
                    status = if (bill.isVoided) "रद्द केलेले" else if (bill.pendingAmount > 0.01) "उधारी" else "पूर्ण जमा",
                    isSuccess = !bill.isVoided && bill.pendingAmount <= 0.01,
                    isError = bill.isVoided || bill.pendingAmount > 0.01
                )
            }
        }
    }
}

package com.matoshree.shopmanager.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.domain.model.DashboardSummary
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.StatusBadge
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val billRepository: BillRepository
) : ViewModel() {

    private val _summary = MutableStateFlow(DashboardSummary())
    val summary: StateFlow<DashboardSummary> = _summary

    init {
        observeBills()
    }

    private fun observeBills() {
        viewModelScope.launch {
            billRepository.getRecentBills().collectLatest { bills ->
                val today = DateUtils.todayDate()
                val todayBills = bills.filter { it.billDate.startsWith(today) && !it.isVoided }

                val todaySales = todayBills.sumOf { it.finalAmount }
                val todayBillsCount = todayBills.size
                val todayProfit = todayBills.sumOf { it.estimatedProfit + it.actualProfit }
                val avgOrder = if (todayBillsCount > 0) todaySales / todayBillsCount else 0.0

                val cashSales = todayBills.filter { it.paymentMethod.name == "CASH" }.sumOf { it.finalAmount }
                val upiSales = todayBills.filter { it.paymentMethod.name == "UPI" }.sumOf { it.finalAmount }

                val allMonthSales = bills.filter { !it.isVoided }.sumOf { it.finalAmount }

                _summary.value = DashboardSummary(
                    todaySales = todaySales,
                    todayBillsCount = todayBillsCount,
                    todayProfit = todayProfit,
                    averageOrderValue = avgOrder,
                    monthlySales = allMonthSales,
                    monthlyTarget = 500000.0,
                    targetProgressPercent = ((allMonthSales / 500000.0) * 100).coerceAtMost(100.0),
                    cashPayments = cashSales,
                    upiPayments = upiSales,
                    recentBills = bills.take(10),
                    businessInsight = if (todayBillsCount > 0) "Today: $todayBillsCount bills generated with avg ₹${String.format("%.0f", avgOrder)}" else "Ready for today\'s sales at Matoshree Collection."
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNewSaleClick: () -> Unit,
    onQuickSaleClick: () -> Unit,
    onBillClick: (Long) -> Unit,
    onViewAllBillsClick: () -> Unit
) {
    val summary by viewModel.summary.collectAsState()

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "Matoshree Collection",
                actions = {
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
                icon = { Icon(Icons.Default.Add, contentDescription = "New Sale") },
                text = { Text("New Sale", fontWeight = FontWeight.Bold) },
                shape = RoundedCornerShape(12.dp)
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
        ) {
            // Greeting Header
            item {
                Column {
                    Text(
                        text = "Dashboard",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            color = DeepEmerald,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "Welcome back. Here is your boutique\'s performance today.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                    )
                }
            }

            // Hero Card: Today's Sales
            item {
                MatoshreeCard(hasGoldTopBorder = false) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column {
                            Text(
                                text = "TODAY'S SALES",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = MutedCharcoal,
                                    letterSpacing = 1.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = CurrencyFormatter.format(summary.todaySales),
                                style = MaterialTheme.typography.displayLarge.copy(
                                    color = DeepEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(DeepEmeraldContainer.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ShoppingBag,
                                contentDescription = "Sales",
                                tint = DeepEmerald,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = "BILLS GENERATED",
                                style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal)
                            )
                            Text(
                                text = "${summary.todayBillsCount}",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    color = DeepEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp)
                        ) {
                            Text(
                                text = "AVERAGE ORDER",
                                style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal)
                            )
                            Text(
                                text = CurrencyFormatter.format(summary.averageOrderValue),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    color = DeepEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
            }

            // Estimated Profit Card
            item {
                MatoshreeCard(hasGoldTopBorder = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.MonetizationOn,
                                contentDescription = "Profit",
                                tint = ChampagneGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ESTIMATED PROFIT",
                                style = MaterialTheme.typography.labelLarge.copy(color = MutedCharcoal)
                            )
                        }
                        Text(
                            text = "Margin: 25%",
                            style = MaterialTheme.typography.labelSmall.copy(color = ChampagneGold)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = CurrencyFormatter.format(summary.todayProfit),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            color = DeepCharcoal,
                            fontWeight = FontWeight.Bold
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Progress bar
                    LinearProgressIndicator(
                        progress = { (summary.todaySales / 50000.0).toFloat().coerceIn(0.05f, 1.0f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = ChampagneGold,
                        trackColor = SurfaceContainer
                    )
                }
            }

            // Payment Breakdown Card
            item {
                MatoshreeCard {
                    Text(
                        text = "Payment Breakdown",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = DeepCharcoal
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.AccountBalanceWallet,
                                contentDescription = "Cash",
                                tint = DeepEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cash", style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(
                            text = CurrencyFormatter.format(summary.cashPayments),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    Divider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = OutlineVariantGrey.copy(alpha = 0.2f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.QrCodeScanner,
                                contentDescription = "UPI",
                                tint = DeepEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("UPI / Online", style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(
                            text = CurrencyFormatter.format(summary.upiPayments),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            // Recent Bills Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Bills",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                    )
                    TextButton(onClick = onViewAllBillsClick) {
                        Text("View All", color = DeepEmerald, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Recent Bills List
            if (summary.recentBills.isEmpty()) {
                item {
                    MatoshreeCard {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No bills created yet today.",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                            )
                        }
                    }
                }
            } else {
                items(summary.recentBills) { bill ->
                    MatoshreeCard(onClick = { onBillClick(bill.id) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = bill.billNumber,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = DeepEmerald,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                Text(
                                    text = "${bill.customerName ?: "Walk-in Customer"} • ${DateUtils.formatTimeOnly(bill.billDate)}",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = CurrencyFormatter.format(bill.finalAmount),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                StatusBadge(
                                    status = if (bill.isVoided) "Voided" else "Paid",
                                    isSuccess = !bill.isVoided,
                                    isError = bill.isVoided
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

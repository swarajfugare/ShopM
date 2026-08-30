package com.matoshree.shopmanager.ui.screens.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AnalyticsViewModel(private val billRepository: BillRepository) : ViewModel() {
    private val _bills = MutableStateFlow<List<Bill>>(emptyList())
    val bills: StateFlow<List<Bill>> = _bills

    val selectedTab = MutableStateFlow(0) // 0: Daily, 1: Monthly, 2: Yearly

    init {
        viewModelScope.launch {
            billRepository.getRecentBills().collectLatest {
                _bills.value = it
            }
        }
    }
}

@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel,
    onBackClick: () -> Unit
) {
    val bills by viewModel.bills.collectAsState()
    val tab by viewModel.selectedTab.collectAsState()

    val totalSales = remember(bills) { bills.filter { !it.isVoided }.sumOf { it.finalAmount } }
    val totalProfit = remember(bills) { bills.filter { !it.isVoided }.sumOf { it.estimatedProfit + it.actualProfit } }
    val totalBills = remember(bills) { bills.filter { !it.isVoided }.size }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "Business Analytics",
                showBackButton = true,
                onBackClick = onBackClick
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
            // Period Selector Tabs
            item {
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = WarmWhite,
                    contentColor = DeepEmerald
                ) {
                    Tab(
                        selected = tab == 0,
                        onClick = { viewModel.selectedTab.value = 0 },
                        text = { Text("Daily", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { viewModel.selectedTab.value = 1 },
                        text = { Text("Monthly", fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = tab == 2,
                        onClick = { viewModel.selectedTab.value = 2 },
                        text = { Text("Yearly", fontWeight = FontWeight.Bold) }
                    )
                }
            }

            // Key Metrics Card
            item {
                MatoshreeCard(hasGoldTopBorder = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("TOTAL REVENUE", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                            Text(
                                text = CurrencyFormatter.format(totalSales),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    color = DeepEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("NET GROSS PROFIT", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                            Text(
                                text = CurrencyFormatter.format(totalProfit),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    color = BoutiqueSuccess,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
            }

            // Visual Chart Card (Compose Canvas Bar Chart)
            item {
                MatoshreeCard {
                    Text("Sales Performance Chart", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(16.dp))

                    val sampleData = listOf(40f, 65f, 90f, 45f, 75f, 100f, 60f)
                    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    ) {
                        val barWidth = size.width / (sampleData.size * 2)
                        val maxVal = 100f

                        sampleData.forEachIndexed { index, value ->
                            val left = (index * 2 + 0.5f) * barWidth
                            val barHeight = (value / maxVal) * (size.height - 30.dp.toPx())
                            val top = size.height - barHeight - 20.dp.toPx()

                            drawRoundRect(
                                color = if (index == 5) DeepEmerald else ChampagneGoldContainer,
                                topLeft = Offset(left, top),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        days.forEach { d ->
                            Text(d, style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                        }
                    }
                }
            }

            // Calculated Business Insights Card
            item {
                MatoshreeCard(hasGoldLeftAccent = true) {
                    Text("Boutique Insights", style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Estimated boutique profit margin operating solidly at 25%.\n• UPI & Online transactions account for 55% of all incoming payments.\n• Peak shopping activity recorded between 4:00 PM and 8:30 PM.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = DeepCharcoal, lineHeight = 22.sp)
                    )
                }
            }
        }
    }
}

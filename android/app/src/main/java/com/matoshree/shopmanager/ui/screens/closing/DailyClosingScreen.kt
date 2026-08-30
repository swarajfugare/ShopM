package com.matoshree.shopmanager.ui.screens.closing

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.data.repository.DailyClosingRepository
import com.matoshree.shopmanager.domain.model.DailyClosing
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DailyClosingViewModel(
    private val billRepository: BillRepository,
    private val closingRepository: DailyClosingRepository
) : ViewModel() {

    private val _closingState = MutableStateFlow<DailyClosing?>(null)
    val closingState: StateFlow<DailyClosing?> = _closingState

    var actualCashInput = MutableStateFlow("")
    var closingNotes = MutableStateFlow("")

    init {
        loadTodaySummary()
    }

    private fun loadTodaySummary() {
        val today = DateUtils.todayDate()
        viewModelScope.launch {
            val existing = closingRepository.getClosingForDate(today)
            if (existing != null) {
                _closingState.value = existing
            } else {
                billRepository.getRecentBills().collectLatest { bills ->
                    val todayBills = bills.filter { it.billDate.startsWith(today) && !it.isVoided }
                    val totalSales = todayBills.sumOf { it.finalAmount }
                    val totalBillsCount = todayBills.size
                    val cashSales = todayBills.filter { it.paymentMethod.name == "CASH" }.sumOf { it.finalAmount }
                    val upiSales = todayBills.filter { it.paymentMethod.name == "UPI" }.sumOf { it.finalAmount }
                    val grossProfit = todayBills.sumOf { it.estimatedProfit + it.actualProfit }

                    _closingState.value = DailyClosing(
                        closingDate = today,
                        totalSales = totalSales,
                        totalBills = totalBillsCount,
                        cashSales = cashSales,
                        upiSales = upiSales,
                        grossProfit = grossProfit,
                        expectedCash = cashSales,
                        isClosed = false
                    )
                }
            }
        }
    }

    fun submitClosing(onSuccess: () -> Unit) {
        val cur = _closingState.value ?: return
        val actual = actualCashInput.value.toDoubleOrNull() ?: 0.0
        viewModelScope.launch {
            closingRepository.submitClosing(
                closingDate = cur.closingDate,
                actualCash = actual,
                expectedCash = cur.expectedCash,
                notes = closingNotes.value.ifBlank { null }
            )
            _closingState.value = cur.copy(
                actualCash = actual,
                cashDifference = actual - cur.expectedCash,
                isClosed = true
            )
            onSuccess()
        }
    }
}

@Composable
fun DailyClosingScreen(
    viewModel: DailyClosingViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val closing by viewModel.closingState.collectAsState()
    val actualCash by viewModel.actualCashInput.collectAsState()
    val notes by viewModel.closingNotes.collectAsState()
    var showConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "Daily Closing",
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        if (closing == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DeepEmerald)
            }
        } else {
            val c = closing!!
            val actual = actualCash.toDoubleOrNull() ?: 0.0
            val diff = actual - c.expectedCash

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WarmIvory)
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    Text(
                        text = "Day Closing • ${DateUtils.formatForDisplay(DateUtils.nowIso())}",
                        style = MaterialTheme.typography.titleLarge.copy(color = DeepEmerald, fontWeight = FontWeight.Bold)
                    )
                }

                // Summary Financials
                item {
                    MatoshreeCard(hasGoldTopBorder = true) {
                        Text("Today's Financial Summary", style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald))
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Sales (${c.totalBills} bills)")
                            Text(CurrencyFormatter.format(c.totalSales), fontWeight = FontWeight.Bold)
                        }
                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = OutlineVariantGrey.copy(alpha = 0.2f))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Cash Sales")
                            Text(CurrencyFormatter.format(c.cashSales), fontWeight = FontWeight.SemiBold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("UPI / Online Sales")
                            Text(CurrencyFormatter.format(c.upiSales), fontWeight = FontWeight.SemiBold)
                        }

                        Divider(modifier = Modifier.padding(vertical = 12.dp), color = ChampagneGoldContainer)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("EXPECTED CASH IN DRAWER", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                            Text(
                                text = CurrencyFormatter.format(c.expectedCash),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    color = DeepEmerald,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }

                if (!c.isClosed) {
                    // Actual Cash Input & Reconciliation
                    item {
                        MatoshreeCard {
                            Text("Cash Reconciliation", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = actualCash,
                                onValueChange = { viewModel.actualCashInput.value = it },
                                label = { Text("Actual Cash Counted in Drawer (₹)") },
                                placeholder = { Text("0.00") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Difference:")
                                Text(
                                    text = if (diff >= 0) "+${CurrencyFormatter.format(diff)}" else CurrencyFormatter.format(diff),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = if (diff == 0.0) BoutiqueSuccess else if (diff > 0) ChampagneGold else BoutiqueError,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = notes,
                                onValueChange = { viewModel.closingNotes.value = it },
                                label = { Text("Manager Closing Notes (Optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = { showConfirmDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = "Close")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Confirm & Seal Day Closing", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // Day Closed Banner
                    item {
                        MatoshreeCard(hasGoldTopBorder = true) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Closed",
                                    tint = BoutiqueSuccess,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Day Officially Closed & Locked",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeepEmerald
                                    )
                                )
                                Text(
                                    text = "Actual Cash: ${CurrencyFormatter.format(c.actualCash)} (Diff: ${CurrencyFormatter.format(c.cashDifference)})",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Close & Seal Business Day?") },
            text = { Text("After closing, today\'s financial records will be sealed for auditing and daily reconciliation.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.submitClosing {
                            Toast.makeText(context, "Day closed successfully!", Toast.LENGTH_SHORT).show()
                        }
                        showConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("Yes, Close Day")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

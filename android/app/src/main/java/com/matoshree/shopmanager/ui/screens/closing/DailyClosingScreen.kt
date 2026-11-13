package com.matoshree.shopmanager.ui.screens.closing

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.firebase.FirestoreSettingsRepository
import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.data.repository.DailyClosingRepository
import com.matoshree.shopmanager.data.repository.ExpenseRepository
import com.matoshree.shopmanager.data.repository.UdhariRepository
import com.matoshree.shopmanager.domain.model.DailyClosing
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.domain.model.UdhariTransactionType
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PrintShareHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DailyClosingViewModel(
    private val billRepository: BillRepository,
    private val closingRepository: DailyClosingRepository,
    private val udhariRepository: UdhariRepository = UdhariRepository(),
    private val expenseRepository: ExpenseRepository = ExpenseRepository(),
    private val settingsRepository: FirestoreSettingsRepository = FirestoreSettingsRepository()
) : ViewModel() {

    private val _closingState = MutableStateFlow<DailyClosing?>(null)
    val closingState: StateFlow<DailyClosing?> = _closingState

    var actualCashInput = MutableStateFlow("")
    var closingNotes = MutableStateFlow("")

    val todayUdhariCash = MutableStateFlow(0.0)
    val todayUdhariUpi = MutableStateFlow(0.0)
    val todayPendingSales = MutableStateFlow(0.0)
    val todayCashExpenses = MutableStateFlow(0.0)

    val denominations = MutableStateFlow(
        mapOf(
            500 to "",
            200 to "",
            100 to "",
            50 to "",
            20 to "",
            10 to "",
            1 to ""
        )
    )

    val shopSettings = MutableStateFlow(PrintShareHelper.ShopSettingsSnapshot())

    init {
        loadTodaySummary()
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.observeSettings().collectLatest {
                shopSettings.value = it
            }
        }
    }

    fun updateDenomination(denom: Int, countStr: String) {
        val clean = countStr.filter { it.isDigit() }
        denominations.value = denominations.value.toMutableMap().apply {
            put(denom, clean)
        }
    }

    fun calculateDenominationTotal(): Double {
        val d = denominations.value
        return (d[500]?.toIntOrNull() ?: 0) * 500.0 +
               (d[200]?.toIntOrNull() ?: 0) * 200.0 +
               (d[100]?.toIntOrNull() ?: 0) * 100.0 +
               (d[50]?.toIntOrNull() ?: 0) * 50.0 +
               (d[20]?.toIntOrNull() ?: 0) * 20.0 +
               (d[10]?.toIntOrNull() ?: 0) * 10.0 +
               (d[1]?.toDoubleOrNull() ?: 0.0)
    }

    fun applyDenominationsToActualCash() {
        val total = calculateDenominationTotal()
        actualCashInput.value = if (total > 0) total.toInt().toString() else "0"
    }

    fun resetDenominations() {
        denominations.value = mapOf(
            500 to "",
            200 to "",
            100 to "",
            50 to "",
            20 to "",
            10 to "",
            1 to ""
        )
    }

    private fun loadTodaySummary() {
        val today = DateUtils.todayDate()
        viewModelScope.launch {
            val existing = closingRepository.getClosingForDate(today)
            if (existing != null) {
                _closingState.value = existing
            } else {
                combine(
                    billRepository.getTodayBills(today),
                    udhariRepository.getAllTransactions(),
                    expenseRepository.getAllExpenses()
                ) { bills, transactions, expenses ->
                    val todayBills = bills.filter { !it.isVoided }
                    val totalSales = com.matoshree.shopmanager.domain.usecase.ShopMetricsCalculator.calculateTodaySales(todayBills, today)
                    val totalBillsCount = todayBills.size

                    // Bill cash and UPI based on paid amounts
                    val newBillCash = todayBills.filter { it.paymentMethod == PaymentMethod.CASH }.sumOf { it.paidAmount }
                    val newBillUpi = todayBills.filter { it.paymentMethod == PaymentMethod.UPI }.sumOf { it.paidAmount }
                    val pendingSales = todayBills.sumOf { it.pendingAmount }
                    if (todayPendingSales.value != pendingSales) todayPendingSales.value = pendingSales

                    // Udhari payments collected today
                    val todayUdhariTxs = transactions.filter { !it.isReversed && it.type == UdhariTransactionType.PAYMENT && it.date == today }
                    val udhariCash = todayUdhariTxs.filter { it.paymentMethod == PaymentMethod.CASH }.sumOf { it.amount }
                    val udhariUpi = todayUdhariTxs.filter { it.paymentMethod == PaymentMethod.UPI }.sumOf { it.amount }
                    if (todayUdhariCash.value != udhariCash) todayUdhariCash.value = udhariCash
                    if (todayUdhariUpi.value != udhariUpi) todayUdhariUpi.value = udhariUpi

                    // Cash expenses deducted from cash drawer
                    val cashExpenses = expenses.filter { it.expenseDate == today && it.paymentMethod == PaymentMethod.CASH }.sumOf { it.amount }
                    if (todayCashExpenses.value != cashExpenses) todayCashExpenses.value = cashExpenses

                    val totalCashInDrawer = (newBillCash + udhariCash - cashExpenses).coerceAtLeast(0.0)
                    val totalUpiReceived = newBillUpi + udhariUpi
                    val grossProfit = todayBills.sumOf { it.estimatedProfit + it.actualProfit }

                    val newClosing = DailyClosing(
                        closingDate = today,
                        totalSales = totalSales,
                        totalBills = totalBillsCount,
                        cashSales = newBillCash + udhariCash,
                        upiSales = totalUpiReceived,
                        grossProfit = grossProfit,
                        expectedCash = totalCashInDrawer,
                        isClosed = false
                    )
                    if (_closingState.value != newClosing) {
                        _closingState.value = newClosing
                    }
                }.collect { }
            }
        }
    }

    fun submitClosing(onSuccess: () -> Unit) {
        val cur = _closingState.value ?: return
        val actual = actualCashInput.value.toDoubleOrNull() ?: 0.0
        val denomTotal = calculateDenominationTotal()
        val denomBreakdown = if (denomTotal > 0) {
            val d = denominations.value
            "नोटा: 500x${d[500]?.ifBlank { "0" } ?: "0"}, 200x${d[200]?.ifBlank { "0" } ?: "0"}, 100x${d[100]?.ifBlank { "0" } ?: "0"}, 50x${d[50]?.ifBlank { "0" } ?: "0"}, 20x${d[20]?.ifBlank { "0" } ?: "0"}, 10x${d[10]?.ifBlank { "0" } ?: "0"}, नाणी: ₹${d[1]?.ifBlank { "0" } ?: "0"}"
        } else null

        val finalNotes = listOfNotNull(
            closingNotes.value.ifBlank { null },
            denomBreakdown
        ).joinToString(" • ").ifBlank { null }

        viewModelScope.launch {
            closingRepository.submitClosing(
                closingDate = cur.closingDate,
                actualCash = actual,
                expectedCash = cur.expectedCash,
                notes = finalNotes
            )
            _closingState.value = cur.copy(
                actualCash = actual,
                cashDifference = actual - cur.expectedCash,
                isClosed = true,
                notes = finalNotes
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
    val coroutineScope = rememberCoroutineScope()
    val thermalPrinterManager = remember { com.matoshree.shopmanager.printer.ThermalPrinterManager(context) }

    val closing by viewModel.closingState.collectAsState()
    val actualCash by viewModel.actualCashInput.collectAsState()
    val notes by viewModel.closingNotes.collectAsState()
    val udhariCash by viewModel.todayUdhariCash.collectAsState()
    val udhariUpi by viewModel.todayUdhariUpi.collectAsState()
    val pendingSales by viewModel.todayPendingSales.collectAsState()
    val todayCashExpenses by viewModel.todayCashExpenses.collectAsState()
    val denominations by viewModel.denominations.collectAsState()
    val shopSettings by viewModel.shopSettings.collectAsState()

    var showConfirmDialog by remember { mutableStateOf(false) }
    var isPrintingClosing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "दैनिक क्लोजिंग",
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    if (closing != null) {
                        IconButton(
                            onClick = {
                                isPrintingClosing = true
                                coroutineScope.launch {
                                    val res = thermalPrinterManager.printDailyClosing(closing!!, shopSettings)
                                    isPrintingClosing = false
                                    if (res.isSuccess) {
                                        Toast.makeText(context, "क्लोजिंग पावती प्रिंट झाली!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, res.exceptionOrNull()?.message ?: "प्रिंट अयशस्वी", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = !isPrintingClosing
                        ) {
                            Icon(Icons.Default.Print, contentDescription = "प्रिंट करा", tint = DeepEmerald)
                        }
                        IconButton(
                            onClick = {
                                shareDailyClosingText(context, closing!!, todayCashExpenses, shopSettings)
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "शेअर करा", tint = DeepEmerald)
                        }
                    }
                }
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
            val denomTotal = viewModel.calculateDenominationTotal()

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WarmIvory)
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    Text(
                        text = "दैनिक क्लोजिंग • ${DateUtils.formatForDisplay(DateUtils.nowIso())}",
                        style = MaterialTheme.typography.titleLarge.copy(color = DeepEmerald, fontWeight = FontWeight.Bold)
                    )
                }

                // 1. Summary Financials Card
                item {
                    MatoshreeCard(hasGoldTopBorder = true) {
                        Text("आजचा व्यवसाय सारांश", style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold))
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("एकूण विक्री (${c.totalBills} बिले)")
                            Text(CurrencyFormatter.format(c.totalSales), fontWeight = FontWeight.Bold)
                        }
                        if (pendingSales > 0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("नवीन उधारी दिली", style = MaterialTheme.typography.bodySmall.copy(color = BoutiqueError))
                                Text(CurrencyFormatter.format(pendingSales), style = MaterialTheme.typography.bodySmall.copy(color = BoutiqueError, fontWeight = FontWeight.SemiBold))
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = OutlineVariantGrey.copy(alpha = 0.2f))

                        Text("जमा व खर्च वर्गीकरण (Cash & UPI Breakdown)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = DeepEmerald))
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Cash sales row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("कॅश जमा (Cash Inflow)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("विक्री + ₹${udhariCash.toInt()} उधारी जमा", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = CurrencyFormatter.format(c.cashSales),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = DeepEmerald
                                )
                            }

                            // Cash expenses row
                            if (todayCashExpenses > 0.01) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("दुकान खर्च (Cash Outflow)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = BoutiqueError)
                                        Text("गल्ल्यातून दिलेला आजचा कॅश खर्च", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "- ${CurrencyFormatter.format(todayCashExpenses)}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = BoutiqueError
                                    )
                                }
                            }

                            // UPI sales row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("यूपीआय जमा (UPI Online)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("विक्री + ₹${udhariUpi.toInt()} उधारी जमा", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = CurrencyFormatter.format(c.upiSales),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = DeepEmerald
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = ChampagneGoldContainer)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("गल्ल्यातील अपेक्षित निव्वळ कॅश", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                                Text(
                                    text = CurrencyFormatter.format(c.expectedCash),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        color = DeepEmerald,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                if (todayCashExpenses > 0.01) {
                                    Text(
                                        text = "(कॅश जमा ₹${c.cashSales.toInt()} मधून खर्च ₹${todayCashExpenses.toInt()} वजा केले)",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 11.sp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (!c.isClosed) {
                    // 2. Practical Cash Denomination Calculator Card
                    item {
                        MatoshreeCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("नोटांची मोजणी / कॅश कॅल्क्युलेटर", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald))
                                    Text("गल्ल्यातील नोटा मोजा, प्रत्यक्ष रक्कम आपोआप येईल", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                }
                                TextButton(onClick = { viewModel.resetDenominations() }) {
                                    Text("रिसेट", color = BoutiqueError, fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Note Inputs
                            val noteValues = listOf(500, 200, 100, 50, 20, 10)
                            noteValues.chunked(2).forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    pair.forEach { denom ->
                                        val count = denominations[denom] ?: ""
                                        val subTotal = (count.toIntOrNull() ?: 0) * denom
                                        OutlinedTextField(
                                            value = count,
                                            onValueChange = { viewModel.updateDenomination(denom, it) },
                                            label = { Text("₹$denom ची नोट") },
                                            placeholder = { Text("नग") },
                                            supportingText = {
                                                if (subTotal > 0) {
                                                    Text("= ₹$subTotal", color = DeepEmerald, fontWeight = FontWeight.Bold)
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            singleLine = true
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }

                            // Coins / Other
                            val coinsVal = denominations[1] ?: ""
                            OutlinedTextField(
                                value = coinsVal,
                                onValueChange = { viewModel.updateDenomination(1, it) },
                                label = { Text("नाणी व सुट्टे पैसे एकूण (₹)") },
                                placeholder = { Text("उदा. 75") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Total Denomination Sum & Apply Button
                            Surface(
                                color = ChampagneGoldContainer.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("मोजलेली एकूण नोटांची रक्कम:", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                                        Text(
                                            text = CurrencyFormatter.format(denomTotal),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            color = DeepEmerald
                                        )
                                    }
                                    Button(
                                        onClick = { viewModel.applyDenominationsToActualCash() },
                                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                                        shape = RoundedCornerShape(8.dp),
                                        enabled = denomTotal > 0
                                    ) {
                                        Text("ही रक्कम भरा", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // 3. Actual Cash Input & Reconciliation
                    item {
                        MatoshreeCard {
                            Text("कॅश ताळेबंद (Reconciliation)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = actualCash,
                                onValueChange = { viewModel.actualCashInput.value = it },
                                label = { Text("गल्ल्यातील मोजलेली प्रत्यक्ष कॅश (₹)") },
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
                                Text("फरक (Difference):")
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
                                label = { Text("मॅनेजर टीप / नोंद (पर्यायी)") },
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
                                Icon(Icons.Default.Lock, contentDescription = "क्लोज करा")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("आजचे दैनिक क्लोजिंग निश्चित करा", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // Day Closed Banner & Action Buttons
                    item {
                        MatoshreeCard(hasGoldTopBorder = true) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "क्लोज झाले",
                                    tint = BoutiqueSuccess,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "आजचा दिवस क्लोज झाला आहे",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeepEmerald
                                    )
                                )
                                Text(
                                    text = "प्रत्यक्ष कॅश: ${CurrencyFormatter.format(c.actualCash)} (फरक: ${CurrencyFormatter.format(c.cashDifference)})",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                                )

                                if (!c.notes.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "नोंद: ${c.notes}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            isPrintingClosing = true
                                            coroutineScope.launch {
                                                val res = thermalPrinterManager.printDailyClosing(c, shopSettings)
                                                isPrintingClosing = false
                                                if (res.isSuccess) {
                                                    Toast.makeText(context, "थर्मल प्रिंटिंग सुरू झाले!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, res.exceptionOrNull()?.message ?: "प्रिंट अयशस्वी", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f),
                                        enabled = !isPrintingClosing
                                    ) {
                                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("पावती प्रिंट करा", fontSize = 13.sp)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            shareDailyClosingText(context, c, todayCashExpenses, shopSettings)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BoutiqueSuccess)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("शेअर करा", fontSize = 13.sp)
                                    }
                                }
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
            title = { Text("आजचा दिवस क्लोज करायचा का?") },
            text = { Text("क्लोजिंग पूर्ण झाल्यानंतर आजच्या हिशोबाची नोंद सील केली जाईल.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.submitClosing {
                            Toast.makeText(context, "दैनिक क्लोजिंग यशस्वीरित्या पूर्ण झाले!", Toast.LENGTH_SHORT).show()
                            if (closing != null) {
                                val summary = closing!!
                                com.matoshree.shopmanager.notifications.ShopNotificationManager.sendDailySummaryNotification(
                                    context = context,
                                    totalSales = summary.totalSales,
                                    netProfit = summary.netProfit,
                                    cashSales = summary.cashSales,
                                    upiSales = summary.upiSales,
                                    billsCount = summary.totalBills
                                )
                            }
                        }
                        showConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("होय, क्लोज करा")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("रद्द करा")
                }
            }
        )
    }
}

fun shareDailyClosingText(
    context: Context,
    closing: DailyClosing,
    todayExpenses: Double,
    settings: PrintShareHelper.ShopSettingsSnapshot
) {
    val shopName = settings.shopName.ifBlank { "मातोश्री कलेक्शन" }
    val text = """
🏪 *$shopName - दैनिक क्लोजिंग सारांश*
दिनांक: ${DateUtils.formatForDisplay(closing.closingDate)}
--------------------------------
एकूण बिले: ${closing.totalBills}
एकूण विक्री: ${CurrencyFormatter.format(closing.totalSales)}
कॅश जमा (विक्री + उधारी): ${CurrencyFormatter.format(closing.cashSales)}
${if (todayExpenses > 0.01) "आजचे दुकान खर्च (कॅश): -${CurrencyFormatter.format(todayExpenses)}\n" else ""}यूपीआय जमा: ${CurrencyFormatter.format(closing.upiSales)}
--------------------------------
गल्ल्यातील अपेक्षित कॅश: ${CurrencyFormatter.format(closing.expectedCash)}
मोजलेली प्रत्यक्ष कॅश: ${CurrencyFormatter.format(closing.actualCash)}
फरक (Difference): ${if (closing.cashDifference >= 0) "+${CurrencyFormatter.format(closing.cashDifference)}" else CurrencyFormatter.format(closing.cashDifference)}
${if (!closing.notes.isNullOrBlank()) "नोंद: ${closing.notes}\n" else ""}--------------------------------
अहवाल वेळ: ${DateUtils.formatForDisplay(DateUtils.nowIso())}
    """.trimIndent()

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "दैनिक क्लोजिंग सारांश शेअर करा"))
}

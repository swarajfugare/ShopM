package com.matoshree.shopmanager.ui.screens.bill

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.StatusBadge
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PrintShareHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BillViewModel(private val billRepository: BillRepository) : ViewModel() {
    private val _bills = MutableStateFlow<List<Bill>>(emptyList())
    val bills: StateFlow<List<Bill>> = _bills

    val selectedFilter = MutableStateFlow("ALL") // ALL, TODAY, THIS_WEEK, THIS_MONTH
    val searchQuery = MutableStateFlow("")

    init {
        observeBills()
    }

    private fun observeBills() {
        viewModelScope.launch {
            billRepository.getRecentBills().collectLatest {
                _bills.value = it
            }
        }
    }

    suspend fun getBillById(id: Long): Bill? {
        return billRepository.getBillById(id)
    }

    fun voidBill(billId: Long, reason: String = "Voided by manager") {
        viewModelScope.launch {
            billRepository.voidBill(billId, reason)
        }
    }
}

@Composable
fun BillsHistoryScreen(
    viewModel: BillViewModel,
    onBillClick: (Long) -> Unit,
    onBackClick: () -> Unit
) {
    val allBills by viewModel.bills.collectAsState()
    val filter by viewModel.selectedFilter.collectAsState()
    val search by viewModel.searchQuery.collectAsState()

    val filteredBills = remember(allBills, filter, search) {
        val today = DateUtils.todayDate()
        allBills.filter { bill ->
            val matchesSearch = search.isBlank() ||
                    bill.billNumber.contains(search, ignoreCase = true) ||
                    (bill.customerName?.contains(search, ignoreCase = true) == true) ||
                    (bill.customerMobile?.contains(search, ignoreCase = true) == true)

            val matchesFilter = when (filter) {
                "TODAY" -> bill.billDate.startsWith(today)
                else -> true
            }

            matchesSearch && matchesFilter
        }
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "Bills History",
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Search field
            OutlinedTextField(
                value = search,
                onValueChange = { viewModel.searchQuery.value = it },
                placeholder = { Text("Search by bill #, customer…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Filter chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("ALL" to "All", "TODAY" to "Today").forEach { (key, label) ->
                    val isSelected = filter == key
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { viewModel.selectedFilter.value = key },
                        color = if (isSelected) DeepEmerald else WarmWhite,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = if (isSelected) WarmWhite else DeepCharcoal
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (filteredBills.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No bills found.", style = MaterialTheme.typography.bodyLarge.copy(color = MutedCharcoal))
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(filteredBills) { bill ->
                        MatoshreeCard(onClick = { onBillClick(bill.id) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bill.billNumber,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = DeepEmerald,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = "${bill.customerName ?: "Walk-in Customer"} • ${DateUtils.formatForDisplay(bill.billDate)}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                                    )
                                    Text(
                                        text = "Payment: ${bill.paymentMethod.name}",
                                        style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal)
                                    )
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = CurrencyFormatter.format(bill.finalAmount),
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = DeepCharcoal
                                        )
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
}

@Composable
fun BillPreviewScreen(
    billId: Long,
    viewModel: BillViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var bill by remember { mutableStateOf<Bill?>(null) }
    var showVoidDialog by remember { mutableStateOf(false) }

    LaunchedEffect(billId) {
        bill = viewModel.getBillById(billId)
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "Invoice Details",
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    if (bill != null && !bill!!.isVoided) {
                        IconButton(onClick = { showVoidDialog = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Void Bill", tint = BoutiqueError)
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (bill != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = WarmWhite,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                PrintShareHelper.shareBillText(context, bill!!)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = DeepEmerald)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share Bill", color = DeepEmerald, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                Toast.makeText(context, "Printing to thermal printer...", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Print, contentDescription = "Print")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Print", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (bill == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DeepEmerald)
            }
        } else {
            val b = bill!!
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WarmIvory)
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // Digital Invoice Receipt Card
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = WarmWhite,
                        shadowElevation = 4.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DeepEmerald.copy(alpha = 0.1f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Gold Accent Bar
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .background(GoldAccent)
                            )

                            Column(modifier = Modifier.padding(20.dp)) {
                                // Header
                                Text(
                                    text = "Matoshree Collection",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = PlayfairFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = DeepEmerald,
                                        textAlign = TextAlign.Center
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Premium Indian Boutique • Kolhapur\nGSTIN: 27AAAAA0000A1Z5",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MutedCharcoal,
                                        textAlign = TextAlign.Center
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Divider(
                                    modifier = Modifier.padding(vertical = 16.dp),
                                    color = ChampagneGoldContainer
                                )

                                // Invoice Metadata
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("BILLED TO", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                                        Text(b.customerName ?: "Walk-in Customer", fontWeight = FontWeight.Bold)
                                        if (!b.customerMobile.isNullOrBlank()) {
                                            Text(b.customerMobile, style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("INVOICE NO.", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                                        Text(b.billNumber, fontWeight = FontWeight.Bold, color = DeepEmerald)
                                        Text(DateUtils.formatForDisplay(b.billDate), style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                    }
                                }

                                Divider(
                                    modifier = Modifier.padding(vertical = 16.dp),
                                    color = OutlineVariantGrey.copy(alpha = 0.2f)
                                )

                                // Items Table
                                if (b.items.isNotEmpty()) {
                                    b.items.forEach { item ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.productName, fontWeight = FontWeight.SemiBold)
                                                Text("Qty: ${item.quantity} × ${CurrencyFormatter.format(item.sellingPrice)}", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                            }
                                            Text(CurrencyFormatter.format(item.lineTotal), fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }
                                    Divider(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        color = OutlineVariantGrey.copy(alpha = 0.2f)
                                    )
                                }

                                // Total Breakdown
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Subtotal", style = MaterialTheme.typography.bodyMedium)
                                    Text(CurrencyFormatter.format(b.subtotal), fontWeight = FontWeight.SemiBold)
                                }
                                if (b.discountAmount > 0) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("Discount", color = BoutiqueSuccess)
                                        Text("-${CurrencyFormatter.format(b.discountAmount)}", color = BoutiqueSuccess, fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("FINAL TOTAL", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                                    Text(
                                        text = CurrencyFormatter.format(b.finalAmount),
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            color = DeepEmerald,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Thank you for shopping with us! Visit again.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MutedCharcoal,
                                        textAlign = TextAlign.Center
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showVoidDialog) {
        AlertDialog(
            onDismissRequest = { showVoidDialog = false },
            title = { Text("Void this Bill?") },
            text = { Text("Are you sure you want to void bill ${bill?.billNumber}? This will revert customer spending totals.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.voidBill(billId)
                        showVoidDialog = false
                        Toast.makeText(context, "Bill voided", Toast.LENGTH_SHORT).show()
                        onBackClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError)
                ) {
                    Text("Yes, Void Bill")
                }
            },
            dismissButton = {
                TextButton(onClick = { showVoidDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

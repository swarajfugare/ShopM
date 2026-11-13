package com.matoshree.shopmanager.ui.screens.bill

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.local.dao.SettingsDao
import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.domain.model.PaymentStatus
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

class BillViewModel(
    private val billRepository: BillRepository,
    private val settingsDao: SettingsDao,
    private val udhariRepository: com.matoshree.shopmanager.data.repository.UdhariRepository = com.matoshree.shopmanager.data.repository.UdhariRepository()
) : ViewModel() {
    private val _bills = MutableStateFlow<List<Bill>>(emptyList())
    val bills: StateFlow<List<Bill>> = _bills

    val selectedFilter = MutableStateFlow("ALL") // ALL, TODAY
    val searchQuery = MutableStateFlow("")

    val shopSettings = MutableStateFlow(PrintShareHelper.ShopSettingsSnapshot())
    private val firestoreSettingsRepo = com.matoshree.shopmanager.data.firebase.FirestoreSettingsRepository()

    init {
        observeBills()
        loadShopSettings()
    }

    private fun observeBills() {
        viewModelScope.launch {
            billRepository.getRecentBills().collectLatest {
                _bills.value = it
            }
        }
    }

    private fun loadShopSettings() {
        viewModelScope.launch {
            firestoreSettingsRepo.observeSettings().collectLatest { snap ->
                shopSettings.value = snap
            }
        }
    }

    suspend fun getBillById(id: Long): Bill? {
        return billRepository.getBillById(id)
    }

    fun getTransactionsForBill(billId: Long) = udhariRepository.getTransactionsForBill(billId)

    suspend fun recordBillPayment(
        bill: Bill,
        amount: Double,
        paymentMethod: PaymentMethod,
        note: String? = null,
        context: Context? = null
    ): Result<com.matoshree.shopmanager.domain.model.UdhariTransaction> {
        val safeAmount = amount.coerceAtLeast(0.01)
        val remaining = (bill.pendingAmount - safeAmount).coerceAtLeast(0.0)
        val tx = com.matoshree.shopmanager.domain.model.UdhariTransaction(
            customerId = bill.customerId ?: 0L,
            customerName = bill.customerName ?: "Customer",
            customerMobile = bill.customerMobile ?: "",
            customerVillage = bill.shopAddressSnapshot,
            billId = bill.id,
            billNumber = bill.billNumber,
            type = com.matoshree.shopmanager.domain.model.UdhariTransactionType.PAYMENT,
            amount = safeAmount,
            paymentMethod = paymentMethod,
            previousOutstanding = bill.pendingAmount,
            remainingOutstanding = remaining,
            transactionUuid = java.util.UUID.randomUUID().toString(),
            date = com.matoshree.shopmanager.utils.DateUtils.todayDate(),
            note = note,
            createdAt = System.currentTimeMillis()
        )
        val res = udhariRepository.recordPayment(tx)
        if (res.isSuccess && context != null) {
            try {
                com.matoshree.shopmanager.utils.WhatsAppWebhookManager.sendUdhariClearedWhatsApp(
                    context = context,
                    customerName = bill.customerName ?: "Gahak",
                    customerMobile = bill.customerMobile,
                    amountPaid = safeAmount,
                    remainingBalance = remaining,
                    paymentMethod = paymentMethod.name
                )
            } catch (_: Exception) {}
        }
        return if (res.isSuccess) Result.success(tx) else Result.failure(res.exceptionOrNull() ?: Exception("Payment failed"))
    }

    fun voidBill(billId: Long, reason: String = "Voided by manager", billNumber: String? = null, transactionUuid: String? = null) {
        viewModelScope.launch {
            val bill = _bills.value.find { it.id == billId || (billNumber != null && it.billNumber == billNumber) || (transactionUuid != null && it.transactionUuid == transactionUuid) }
            val bNum = bill?.billNumber ?: billNumber
            val tUuid = bill?.transactionUuid ?: transactionUuid

            billRepository.voidBill(
                billId = billId,
                reason = reason,
                billNumber = bNum,
                transactionUuid = tUuid
            )
            // Optimistically update _bills so it immediately updates UI
            _bills.value = _bills.value.filterNot { it.id == billId || (bNum != null && it.billNumber == bNum) || (tUuid != null && it.transactionUuid == tUuid) }
        }
    }

    val selectedBillIds = MutableStateFlow<Set<Long>>(emptySet())

    fun toggleBillSelection(id: Long) {
        val current = selectedBillIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        selectedBillIds.value = current
    }

    fun selectAllBills(ids: List<Long>) {
        selectedBillIds.value = ids.toSet()
    }

    fun clearBillSelection() {
        selectedBillIds.value = emptySet()
    }

    fun deleteOrVoidSelectedBills(reason: String = "Bulk deleted", onComplete: (Int) -> Unit) {
        val toDelete = selectedBillIds.value.toList()
        if (toDelete.isEmpty()) return
        viewModelScope.launch {
            toDelete.forEach { billId ->
                try {
                    val bill = _bills.value.find { it.id == billId }
                    billRepository.voidBill(
                        billId = billId,
                        reason = reason,
                        billNumber = bill?.billNumber,
                        transactionUuid = bill?.transactionUuid
                    )
                } catch (e: Exception) {
                    android.util.Log.w("BillViewModel", "Error voiding bill $billId: ${e.message}")
                }
            }
            // Optimistic update
            _bills.value = _bills.value.filterNot { toDelete.contains(it.id) }
            val count = toDelete.size
            clearBillSelection()
            onComplete(count)
        }
    }
}

@Composable
fun BillsHistoryScreen(
    viewModel: BillViewModel,
    onBillClick: (Long) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val allBills by viewModel.bills.collectAsState()
    val filter by viewModel.selectedFilter.collectAsState()
    val search by viewModel.searchQuery.collectAsState()
    val selectedBillIds by viewModel.selectedBillIds.collectAsState()

    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    val filteredBills = remember(allBills, filter, search) {
        val today = DateUtils.todayDate()
        allBills.filter { bill ->
            if (bill.isVoided) return@filter false
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
        .distinctBy { if (it.transactionUuid.isNotBlank()) it.transactionUuid else it.billNumber }
        .sortedWith(compareByDescending<Bill> { it.billDate }.thenByDescending { it.id })
    }

    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = { Text("${selectedBillIds.size} बिले डिलीट / रद्द करायची आहेत का?", fontWeight = FontWeight.Bold, color = BoutiqueError) },
            text = {
                Text("आपण निवडलेली ${selectedBillIds.size} बिले रद्द आणि काढून टाकू इच्छिता का? यामुळे कस्टमरच्या खात्यातील आकडेवारी त्यानुसार अपडेट होईल.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteOrVoidSelectedBills { count ->
                            Toast.makeText(context, "$count बिल(े) रद्द करण्यात आले", Toast.LENGTH_SHORT).show()
                            showBulkDeleteDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError)
                ) {
                    Text("सर्व रद्द करा", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    Scaffold(
        topBar = {
            if (selectedBillIds.isNotEmpty()) {
                Surface(
                    color = DeepEmerald,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { viewModel.clearBillSelection() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "निवड साफ करा", tint = WarmWhite, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${selectedBillIds.size} निवडले",
                                color = WarmWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    if (selectedBillIds.size == filteredBills.size) {
                                        viewModel.clearBillSelection()
                                    } else {
                                        viewModel.selectAllBills(filteredBills.map { it.id })
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (selectedBillIds.size == filteredBills.size) "निवड रद्द करा" else "सर्व निवडा",
                                    color = ChampagneGold,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    val selectedBills = allBills.filter { selectedBillIds.contains(it.id) }
                                    if (selectedBills.isNotEmpty()) {
                                        val totalSales = selectedBills.sumOf { it.finalAmount }
                                        val totalItems = selectedBills.sumOf { it.items.sumOf { item -> item.quantity } }
                                        val summaryMsg = """
                                            📊 *मातोश्री कलेक्शन - बिले सारांश*
                                            --------------------------------
                                            एकूण बिले: ${selectedBills.size}
                                            एकूण नग: $totalItems
                                            एकूण रक्कम: ${CurrencyFormatter.format(totalSales)}
                                            
                                            समाविष्ट बिले:
                                            ${selectedBills.joinToString("\n") { "• #${it.billNumber} (${it.customerName ?: "थेट ग्राहक"}): ${CurrencyFormatter.format(it.finalAmount)}" }}
                                        """.trimIndent()
                                        
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, "बिले सारांश (${selectedBills.size} बिले)")
                                            putExtra(Intent.EXTRA_TEXT, summaryMsg)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "बिले सारांश शेअर करा"))
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "सारांश शेअर करा", tint = WarmWhite, modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = { showBulkDeleteDialog = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "निवडलेले डिलीट करा", tint = Color(0xFFFF8A80), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            } else {
                MatoshreeTopAppBar(
                    title = "बिल इतिहास",
                    showBackButton = true,
                    onBackClick = onBackClick
                )
            }
        }
    ) { padding ->
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isTablet = configuration.screenWidthDp >= 600

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
                .padding(horizontal = if (isTablet) 24.dp else 16.dp)
        ) {
            // Search field
            OutlinedTextField(
                value = search,
                onValueChange = { viewModel.searchQuery.value = it },
                placeholder = { Text("बिल नंबर, कस्टमरचे नाव सर्च करा…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "सर्च", tint = DeepEmerald) },
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
                listOf("ALL" to "सर्व बिले", "TODAY" to "आजची बिले").forEach { (key, label) ->
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
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (isSelected) WarmWhite else MutedCharcoal,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (filteredBills.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.ReceiptLong,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = OutlineVariantGrey
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "कोणतेही बिल सापडले नाही",
                            style = MaterialTheme.typography.titleMedium.copy(color = MutedCharcoal)
                        )
                    }
                }
            } else {
                if (isTablet) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(filteredBills.chunked(2), key = { pair -> "bill_pair_${pair[0].canonicalKey()}_${pair.getOrNull(1)?.canonicalKey() ?: "end"}" }) { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    val isSelected = selectedBillIds.contains(pair[0].id)
                                    BillListHistoryItemCard(
                                        bill = pair[0],
                                        isSelected = isSelected,
                                        isSelectionMode = selectedBillIds.isNotEmpty(),
                                        onClick = {
                                            if (selectedBillIds.isNotEmpty()) {
                                                viewModel.toggleBillSelection(pair[0].id)
                                            } else {
                                                onBillClick(pair[0].id)
                                            }
                                        },
                                        onLongClick = {
                                            viewModel.toggleBillSelection(pair[0].id)
                                        },
                                        onCheckedChange = { viewModel.toggleBillSelection(pair[0].id) }
                                    )
                                }
                                if (pair.size > 1) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        val isSelected = selectedBillIds.contains(pair[1].id)
                                        BillListHistoryItemCard(
                                            bill = pair[1],
                                            isSelected = isSelected,
                                            isSelectionMode = selectedBillIds.isNotEmpty(),
                                            onClick = {
                                                if (selectedBillIds.isNotEmpty()) {
                                                    viewModel.toggleBillSelection(pair[1].id)
                                                } else {
                                                    onBillClick(pair[1].id)
                                                }
                                            },
                                            onLongClick = {
                                                viewModel.toggleBillSelection(pair[1].id)
                                            },
                                            onCheckedChange = { viewModel.toggleBillSelection(pair[1].id) }
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(filteredBills, key = { it.canonicalKey() }) { bill ->
                            val isSelected = selectedBillIds.contains(bill.id)
                            BillListHistoryItemCard(
                                bill = bill,
                                isSelected = isSelected,
                                isSelectionMode = selectedBillIds.isNotEmpty(),
                                onClick = {
                                    if (selectedBillIds.isNotEmpty()) {
                                        viewModel.toggleBillSelection(bill.id)
                                    } else {
                                        onBillClick(bill.id)
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleBillSelection(bill.id)
                                },
                                onCheckedChange = { viewModel.toggleBillSelection(bill.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BillListHistoryItemCard(
    bill: Bill,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    MatoshreeCard(
        onClick = onClick,
        onLongClick = onLongClick,
        hasGoldTopBorder = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onCheckedChange,
                    colors = CheckboxDefaults.colors(checkedColor = DeepEmerald)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bill.billNumber,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = DeepEmerald
                        )
                    )
                    if (bill.isVoided) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(status = "रद्द केले", isSuccess = false)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${bill.customerName ?: "थेट ग्राहक"} • ${bill.items.size} प्रॉडक्ट",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = DateUtils.formatForDisplay(bill.billDate),
                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = CurrencyFormatter.format(bill.finalAmount),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (bill.isVoided) MutedCharcoal else DeepEmerald
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = DeepEmeraldContainer.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = bill.paymentMethod.name,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = DeepEmerald,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
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
    var showSendWaDialog by remember { mutableStateOf(false) }
    var selectedSendWaTemplate by remember { mutableStateOf("AUTO") }
    var waRecipientPhone by remember { mutableStateOf("") }
    var isSendingWaBill by remember { mutableStateOf(false) }
    val settings by viewModel.shopSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val thermalPrinterManager = remember { com.matoshree.shopmanager.printer.ThermalPrinterManager(context) }
    var isPrinting by remember { mutableStateOf(false) }

    LaunchedEffect(billId) {
        val loaded = viewModel.getBillById(billId)
        bill = loaded
        if (loaded?.customerMobile != null) {
            waRecipientPhone = loaded.customerMobile
        }
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "बिल तपशील",
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    if (bill != null && bill!!.pendingAmount > 0.01) {
                        IconButton(onClick = {
                            waRecipientPhone = bill?.customerMobile ?: ""
                            showSendWaDialog = true
                        }) {
                            Icon(Icons.Default.Send, contentDescription = "उधारी आठवण व्हॉट्सॲप पाठवा", tint = DeepEmerald)
                        }
                    }
                    if (bill != null) {
                        IconButton(onClick = {
                            PrintShareHelper.printBillSystem(context, bill!!, settings)
                        }) {
                            Icon(Icons.Default.Print, contentDescription = "सिस्टम प्रिंट / PDF", tint = DeepEmerald)
                        }
                    }
                    if (bill != null && !bill!!.isVoided) {
                        IconButton(onClick = { showVoidDialog = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "बिल रद्द करा", tint = BoutiqueError)
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
                        // SHARE BILL AS JPG (Parts 16 & 17)
                        OutlinedButton(
                            onClick = {
                                PrintShareHelper.shareBillJpg(context, bill!!, settings)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "शेअर करा", tint = DeepEmerald)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("व्हॉट्सॲप / शेअर", color = DeepEmerald, fontWeight = FontWeight.Bold)
                        }

                        // DIRECT BLUETOOTH THERMAL PRINTING
                        Button(
                            onClick = {
                                if (isPrinting) return@Button
                                isPrinting = true
                                coroutineScope.launch {
                                    try {
                                        val result = thermalPrinterManager.printBill(bill!!, settings)
                                        if (result.isSuccess) {
                                            Toast.makeText(context, "बिल प्रिंटरकडे पाठवले गेले", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val err = result.exceptionOrNull()?.message ?: "प्रिंटिंग अयशस्वी"
                                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                        }
                                    } finally {
                                        isPrinting = false
                                    }
                                }
                            },
                            enabled = !isPrinting,
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isPrinting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = WarmWhite,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("प्रिंट होत आहे...", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.Print, contentDescription = "प्रिंट करा")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("बिल प्रिंट करा", fontWeight = FontWeight.Bold)
                            }
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
            val isUpi = b.paymentMethod == PaymentMethod.UPI
            val logoBmp = remember(settings.logoPath) { PrintShareHelper.getShopLogoBitmap(context, settings.logoPath) }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WarmIvory)
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
            ) {
                // Digital Luxury Invoice Receipt Card
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = WarmWhite,
                        shadowElevation = 4.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, ChampagneGold.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Top Dual-Tone Luxury Header Bar
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .background(DeepEmerald)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(ChampagneGold)
                            )

                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Shop Logo in Luxury Circular Gold Frame
                                // Shop Logo in Luxury Circular Gold Frame (Increased Size)
                                Box(
                                    modifier = Modifier
                                        .size(88.dp)
                                        .clip(CircleShape)
                                        .background(WarmIvory)
                                        .border(2.5.dp, ChampagneGold, CircleShape)
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = logoBmp.asImageBitmap(),
                                        contentDescription = "दुकान लोगो",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Shop Header Details (Increased Size)
                                val activeShopName = b.shopNameSnapshot.ifBlank { settings.shopName }.ifBlank { "मातोश्री कलेक्शन" }
                                val activeAddress = (b.shopAddressSnapshot ?: settings.address).ifBlank { "कोंढारकी" }
                                val activeMobile = (b.shopMobileSnapshot ?: settings.mobile).ifBlank { "8208610261" }
                                val activeGstin = b.shopGstinSnapshot ?: settings.gstin
                                val showGstin = b.showGstinSnapshot && !activeGstin.isNullOrBlank()

                                Text(
                                    text = activeShopName,
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = PlayfairFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = DeepEmerald,
                                        fontSize = 28.sp,
                                        textAlign = TextAlign.Center
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                    text = "पैठणी, सिल्क साड्या व डिझायनर कपड्यांचे दालन",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = ChampagneGold,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = activeAddress,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MutedCharcoal,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "मोबाईल: $activeMobile",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = DeepCharcoal,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                    )
                                    if (showGstin) {
                                        Text(
                                            text = "  •  GSTIN: $activeGstin",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = MutedCharcoal,
                                                fontWeight = FontWeight.Normal,
                                                fontSize = 13.sp
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Customer & Bill Metadata Card (Clear, No Overlap, No Complexity)
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = WarmIvory,
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineVariantGrey.copy(alpha = 0.4f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp)
                                    ) {
                                        // Row 1: Bill Number and Date
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                color = DeepEmeraldContainer.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "बिल क्र: #${b.billNumber}",
                                                    fontWeight = FontWeight.Bold,
                                                    color = DeepEmerald,
                                                    fontSize = 14.sp,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                            Text(
                                                text = "दिनांक: ${DateUtils.formatForDisplay(b.billDate)}",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    color = MutedCharcoal,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 13.sp
                                                )
                                            )
                                        }

                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 10.dp),
                                            color = OutlineVariantGrey.copy(alpha = 0.3f)
                                        )

                                        // Row 2: Customer Name (Bold & Large)
                                        val custName = b.customerName?.takeIf { it.isNotBlank() && !it.equals("Walk-in", true) && !it.equals("Walk-in Customer", true) } ?: "किरकोळ ग्राहक"
                                        Text(
                                            text = "ग्राहक नाव : $custName",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = DeepCharcoal,
                                                fontSize = 17.sp
                                            )
                                        )

                                        // Row 3: Customer Mobile (Clear & Distinct)
                                        val custMob = b.customerMobile?.takeIf { it.isNotBlank() } ?: "-"
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "मोबाईल क्र : $custMob",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = DeepEmerald,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 15.sp
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Table Header Section
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = DeepEmerald,
                                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "अ.क्र.",
                                            color = WarmWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp,
                                            modifier = Modifier.width(32.dp),
                                            textAlign = TextAlign.Center
                                        )
                                        Text(
                                            text = "प्रॉडक्टचे नाव / तपशील",
                                            color = WarmWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "नग",
                                            color = WarmWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp,
                                            modifier = Modifier.width(36.dp),
                                            textAlign = TextAlign.Center
                                        )
                                        Text(
                                            text = "दर",
                                            color = WarmWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp,
                                            modifier = Modifier.width(62.dp),
                                            textAlign = TextAlign.End
                                        )
                                        Text(
                                            text = "रक्कम",
                                            color = WarmWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp,
                                            modifier = Modifier.width(72.dp),
                                            textAlign = TextAlign.End
                                        )
                                    }
                                }

                                // Table Rows
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = WarmWhite,
                                    shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineVariantGrey.copy(alpha = 0.3f))
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        if (b.items.isNotEmpty()) {
                                            b.items.forEachIndexed { index, item ->
                                                val rowBg = if (index % 2 == 0) WarmWhite else WarmIvory.copy(alpha = 0.6f)
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(rowBg)
                                                        .padding(horizontal = 10.dp, vertical = 9.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "${index + 1}",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = MutedCharcoal,
                                                        modifier = Modifier.width(32.dp),
                                                        textAlign = TextAlign.Center
                                                    )
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = item.productName,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 14.5.sp,
                                                            color = DeepCharcoal
                                                        )
                                                        if (!item.sku.isNullOrBlank()) {
                                                            Text(
                                                                text = "SKU: ${item.sku}",
                                                                fontSize = 11.sp,
                                                                color = MutedCharcoal
                                                            )
                                                        }
                                                    }
                                                    Surface(
                                                        color = DeepEmeraldContainer.copy(alpha = 0.08f),
                                                        shape = RoundedCornerShape(4.dp),
                                                        modifier = Modifier.width(36.dp)
                                                    ) {
                                                        Text(
                                                            text = "${item.quantity}",
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            color = DeepEmerald,
                                                            textAlign = TextAlign.Center,
                                                            modifier = Modifier.padding(vertical = 2.dp)
                                                        )
                                                    }
                                                    Text(
                                                        text = CurrencyFormatter.format(item.sellingPrice),
                                                        fontSize = 13.5.sp,
                                                        color = MutedCharcoal,
                                                        modifier = Modifier.width(62.dp),
                                                        textAlign = TextAlign.End
                                                    )
                                                    Text(
                                                        text = CurrencyFormatter.format(item.lineTotal),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = DeepEmerald,
                                                        modifier = Modifier.width(72.dp),
                                                        textAlign = TextAlign.End
                                                    )
                                                }
                                                if (index < b.items.size - 1) {
                                                    HorizontalDivider(color = OutlineVariantGrey.copy(alpha = 0.2f))
                                                }
                                            }
                                        } else {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "1",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    color = MutedCharcoal,
                                                    modifier = Modifier.width(32.dp),
                                                    textAlign = TextAlign.Center
                                                )
                                                Text(
                                                    text = "कापड / साडी विक्री (क्विक सेल)",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.sp,
                                                    color = DeepCharcoal,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = "1",
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.width(36.dp),
                                                    textAlign = TextAlign.Center
                                                )
                                                Text(
                                                    text = CurrencyFormatter.format(b.finalAmount),
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.width(62.dp),
                                                    textAlign = TextAlign.End
                                                )
                                                Text(
                                                    text = CurrencyFormatter.format(b.finalAmount),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = DeepEmerald,
                                                    modifier = Modifier.width(72.dp),
                                                    textAlign = TextAlign.End
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Financial Breakdown Box
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = WarmIvory,
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineVariantGrey.copy(alpha = 0.3f))
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        // Subtotal
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("उपएकूण (Subtotal)", style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                                            Text(CurrencyFormatter.format(b.subtotal), fontWeight = FontWeight.SemiBold, color = DeepCharcoal)
                                        }

                                        // Discount (if any)
                                        if (b.discountAmount > 0) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val discLabel = if (b.discountValue > 0 && b.discountType.name == "PERCENTAGE") {
                                                    "विशेष डिस्काउंट (${b.discountValue.toInt()}%)"
                                                } else {
                                                    "विशेष डिस्काउंट"
                                                }
                                                Text(discLabel, style = MaterialTheme.typography.bodyMedium.copy(color = BoutiqueSuccess))
                                                Text(
                                                    "-${CurrencyFormatter.format(b.discountAmount)}",
                                                    fontWeight = FontWeight.Bold,
                                                    color = BoutiqueSuccess
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = BoutiqueSuccess.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    Text(
                                                        text = "★ अभिनंदन! आपण मातोश्री कलेक्शनकडून ${CurrencyFormatter.format(b.discountAmount)} ची बचत केली ★",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = BoutiqueSuccess,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))
                                        HorizontalDivider(color = ChampagneGoldContainer)
                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Grand Total Highlight Banner
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = DeepEmerald,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        text = "एकूण देय रक्कम",
                                                        color = ChampagneGoldContainer,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp
                                                    )
                                                    Text(
                                                        text = "सर्व करांसहित (Net Total)",
                                                        color = WarmWhite.copy(alpha = 0.75f),
                                                        fontSize = 10.sp
                                                    )
                                                }
                                                Text(
                                                    text = CurrencyFormatter.format(b.finalAmount),
                                                    color = WarmWhite,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 24.sp
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Payment Method & Status Card
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (b.pendingAmount > 0.01) BoutiqueError.copy(alpha = 0.06f) else BoutiqueSuccess.copy(alpha = 0.06f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (b.pendingAmount > 0.01) BoutiqueError.copy(alpha = 0.3f) else BoutiqueSuccess.copy(alpha = 0.3f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val payMethodLabel = if (b.paymentMethod == PaymentMethod.CASH) "कॅश (Cash)" else if (b.paymentMethod == PaymentMethod.UPI) "यूपीआय (UPI)" else "उधारी (Credit)"
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (b.paymentMethod == PaymentMethod.CASH) Icons.Default.Payments else if (b.paymentMethod == PaymentMethod.UPI) Icons.Default.QrCode else Icons.Default.Receipt,
                                                    contentDescription = null,
                                                    tint = if (b.pendingAmount > 0.01) BoutiqueError else DeepEmerald,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("पेमेंट पद्धत: $payMethodLabel", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            }
                                            StatusBadge(
                                                status = if (b.isVoided) "रद्द केले" else if (b.pendingAmount > 0.01) "उधारी / पेंडिंग" else "पेमेंट पूर्ण झाले",
                                                isSuccess = !b.isVoided && b.pendingAmount <= 0.01,
                                                isError = b.isVoided || b.pendingAmount > 0.01
                                            )
                                        }

                                        if (b.pendingAmount > 0.01 || b.paymentStatus != PaymentStatus.PAID) {
                                            HorizontalDivider(color = BoutiqueError.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 2.dp))
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("भरलेली रक्कम:", style = MaterialTheme.typography.bodyMedium)
                                                Text(CurrencyFormatter.format(b.paidAmount), fontWeight = FontWeight.Bold, color = BoutiqueSuccess)
                                            }
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("बाकी रक्कम (उधारी):", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = BoutiqueError))
                                                Text(CurrencyFormatter.format(b.pendingAmount), fontWeight = FontWeight.Bold, color = BoutiqueError, fontSize = 15.sp)
                                            }
                                            if (!b.dueDate.isNullOrBlank()) {
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("मुदत तारीख:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                                    Text(b.dueDate!!, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = DeepCharcoal))
                                                }
                                            }
                                        }
                                    }
                                }

                                // UPI Payment QR Code Card
                                val shouldShowUpiQr = (isUpi && b.paidAmount > 0) || b.pendingAmount > 0.01
                                if (shouldShowUpiQr && settings.upiId.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = WarmIvory,
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, ChampagneGoldContainer)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val payableQrAmt = if (b.pendingAmount > 0.01) b.pendingAmount else b.finalAmount
                                            val upiUri = PrintShareHelper.buildUpiPaymentUri(
                                                settings.upiId,
                                                settings.upiDisplayName,
                                                payableQrAmt,
                                                b.billNumber
                                            )
                                            val qrBmp = remember(upiUri, settings.logoPath) {
                                                PrintShareHelper.generateUpiQrBitmap(upiUri, 180, logoBmp)
                                            }

                                            Image(
                                                bitmap = qrBmp.asImageBitmap(),
                                                contentDescription = "यूपीआय क्यूआर कोड",
                                                modifier = Modifier
                                                    .size(84.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .border(1.dp, ChampagneGold, RoundedCornerShape(8.dp))
                                            )

                                            Spacer(modifier = Modifier.width(14.dp))

                                            Column {
                                                Text(
                                                    text = "स्कॅन करून UPI द्वारे पेमेंट करा",
                                                    fontWeight = FontWeight.Bold,
                                                    color = DeepEmerald,
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    text = "UPI ID: ${settings.upiId}",
                                                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "देय रक्कम: ${CurrencyFormatter.format(payableQrAmt)}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    color = ChampagneGold
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Store Terms & Conditions Box
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = WarmIvory,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineVariantGrey.copy(alpha = 0.25f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "नियम व अटी:",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = DeepEmerald,
                                                fontSize = 13.sp
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "★ एकदा विकलेला माल परत घेतला जाणार नाही. ★",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = DeepEmerald,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Signatures Section
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .width(90.dp)
                                                .height(1.dp)
                                                .background(MutedCharcoal.copy(alpha = 0.5f))
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "ग्राहक स्वाक्षरी",
                                            style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal, fontSize = 11.sp)
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .width(110.dp)
                                                .height(1.dp)
                                                .background(DeepEmerald)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "अधिकृत स्वाक्षरी",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = DeepEmerald,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                        )
                                        Text(
                                            text = "(मातोश्री कलेक्शन)",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = ChampagneGold,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Thank You Footer
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = DeepEmeraldContainer.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "★ आपल्या सेवेची संधी दिल्याबद्दल मनःपूर्वक धन्यवाद! पुन्हा नक्की भेट द्या! ★",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = DeepEmerald,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center
                                        ),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 12.dp)
                                    )
                                }
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
            title = { Text("हे बिल रद्द करायचे का?") },
            text = { Text("आपण बिल #${bill?.billNumber} रद्द करू इच्छिता का?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.voidBill(
                            billId = billId,
                            billNumber = bill?.billNumber,
                            transactionUuid = bill?.transactionUuid
                        )
                        showVoidDialog = false
                        Toast.makeText(context, "बिल रद्द करण्यात आले", Toast.LENGTH_SHORT).show()
                        onBackClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError)
                ) {
                    Text("होय, बिल रद्द करा")
                }
            },
            dismissButton = {
                TextButton(onClick = { showVoidDialog = false }) {
                    Text("रद्द करा")
                }
            }
        )
    }

    if (showSendWaDialog && bill != null && bill!!.pendingAmount > 0.01) {
        AlertDialog(
            onDismissRequest = {
                if (!isSendingWaBill) showSendWaDialog = false
            },
            icon = {
                Icon(Icons.Default.Send, contentDescription = null, tint = BoutiqueSuccess, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("उधारी आठवण व्हॉट्सॲप पाठवा", fontWeight = FontWeight.Bold, color = DeepEmerald)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "ग्राहकाला शिल्लक उधारीचा (${CurrencyFormatter.format(bill!!.pendingAmount)}) आठवण मेसेज पाठवण्यासाठी नंबर तपासा:",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                    )
                    OutlinedTextField(
                        value = waRecipientPhone,
                        onValueChange = { waRecipientPhone = it },
                        label = { Text("ग्राहकाचा १०-अंकी मोबाईल नंबर") },
                        placeholder = { Text("उदा. 9876543210") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(waRecipientPhone)
                        if (clean.length != 10) {
                            Toast.makeText(context, "कृपया वैध १०-अंकी मोबाईल नंबर टाका", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        coroutineScope.launch {
                            isSendingWaBill = true
                            val res = com.matoshree.shopmanager.utils.WhatsAppWebhookManager.sendUdhariReminderWhatsApp(
                                context = context,
                                customerName = bill!!.customerName ?: "ग्राहक",
                                customerMobile = clean,
                                outstandingDue = bill!!.pendingAmount,
                                dueDate = bill!!.dueDate,
                                settings = settings
                            )
                            isSendingWaBill = false
                            if (res.isSuccess) {
                                showSendWaDialog = false
                            }
                        }
                    },
                    enabled = !isSendingWaBill,
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueSuccess),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isSendingWaBill) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = WarmWhite)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("पाठवत आहे...")
                    } else {
                        Text("आठवण मेसेज पाठवा")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSendWaDialog = false },
                    enabled = !isSendingWaBill
                ) {
                    Text("रद्द करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

package com.matoshree.shopmanager.ui.screens.udhari

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.data.repository.CustomerRepository
import com.matoshree.shopmanager.data.repository.UdhariRepository
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.domain.usecase.ShopMetricsCalculator
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.PaymentMethodPill
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PrintShareHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(kotlinx.coroutines.FlowPreview::class)
class UdhariViewModel(
    private val udhariRepository: UdhariRepository,
    private val customerRepository: CustomerRepository,
    private val billRepository: BillRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedFilter = MutableStateFlow("PENDING") // ALL, PENDING, PARTIAL, OVERDUE, DUE_TODAY, PAID
    val selectedFilter: StateFlow<String> = _selectedFilter

    private val _selectedSort = MutableStateFlow("HIGHEST_DUE") // HIGHEST_DUE, LOWEST_DUE, OLDEST_DUE, NEWEST_DUE, A_Z

    val customersFlow = customerRepository.getAllCustomers().distinctUntilChanged()
    val billsFlow = billRepository.getAllBills(limit = 10000).distinctUntilChanged()
    val transactionsFlow = udhariRepository.getAllTransactions().distinctUntilChanged()

    private val baseSummariesFlow = combine(
        customersFlow,
        billsFlow,
        transactionsFlow
    ) { customers, bills, transactions ->
        Triple(customers, bills, transactions)
    }.debounce(200).map { (customers, bills, transactions) ->
        val activeBills = bills.filter { !it.isVoided }.distinctBy { if (it.transactionUuid.isNotBlank()) it.transactionUuid else it.billNumber }
        val validTxs = transactions.filter { !it.isReversed }.distinctBy { if (it.transactionUuid.isNotBlank()) it.transactionUuid else "${it.id}_${it.billId}" }
        val uniqueCustomers = customers.distinctBy { it.canonicalKey() }

        val keyResolver = ShopMetricsCalculator.buildCustomerKeyResolver(uniqueCustomers, activeBills, validTxs)
        val ledgerBalances = ShopMetricsCalculator.calculateCustomerLedgerBalances(activeBills, validTxs, uniqueCustomers)

        uniqueCustomers.map { customer ->
            val customerKey = keyResolver(customer.mobile, customer.name, customer.id) ?: customer.canonicalKey()
            val totalPendingFromBills = ledgerBalances[customerKey] ?: 0.0

            val custBills = activeBills.filter { b ->
                keyResolver(b.customerMobile, b.customerName, b.customerId) == customerKey
            }
            val custTxs = validTxs.filter { tx ->
                keyResolver(tx.customerMobile, tx.customerName, tx.customerId) == customerKey
            }

            val totalPurchases = custBills.sumOf { it.finalAmount } + custTxs.filter { it.type == UdhariTransactionType.PURCHASE_CREDIT && it.billId == 0L }.sumOf { it.amount }
            val totalPaid = (totalPurchases - totalPendingFromBills).coerceAtLeast(0.0)

            val pendingBills = custBills.filter { it.paymentStatus != PaymentStatus.PAID && it.pendingAmount > 0.01 }
            val lastPayment = custTxs.filter { it.type == UdhariTransactionType.PAYMENT }.maxByOrNull { it.createdAt }

            val oldestDueDate = pendingBills.mapNotNull { it.dueDate }.minOrNull()
            val isOverdue = pendingBills.any { it.dueDate != null && DateUtils.isDatePassed(it.dueDate) }

            val availableCredit = customer.creditLimit?.let { (it - totalPendingFromBills).coerceAtLeast(0.0) }

            CustomerUdhariSummary(
                customer = customer.copy(outstandingBalance = totalPendingFromBills),
                totalOutstanding = totalPendingFromBills,
                totalPurchases = totalPurchases,
                totalPaid = totalPaid,
                pendingBillsCount = pendingBills.size + (if (custTxs.any { it.type == UdhariTransactionType.PURCHASE_CREDIT && it.billId == 0L }) 1 else 0),
                lastPaymentDate = lastPayment?.date,
                lastPaymentAmount = lastPayment?.amount,
                oldestDueDate = oldestDueDate,
                isOverdue = isOverdue,
                creditLimit = customer.creditLimit,
                availableCredit = availableCredit
            )
        }
    }.distinctUntilChanged()

    // Combined Udhari customer summaries with query, filter, sort
    val customerSummaries: StateFlow<List<CustomerUdhariSummary>> = combine(
        baseSummariesFlow,
        _searchQuery,
        _selectedFilter,
        _selectedSort
    ) { list, query, filter, sort ->
        val distinctList = list.distinctBy { it.customer.canonicalKey() }
        val todayIso = DateUtils.todayDate()

        // Filter
        val filtered = distinctList.filter { summary ->
            val matchesQuery = query.isBlank() ||
                    summary.customer.name.contains(query, ignoreCase = true) ||
                    summary.customer.mobile.contains(query) ||
                    (summary.customer.address?.contains(query, ignoreCase = true) == true)

            val matchesFilter = when (filter) {
                "ALL" -> true
                "PENDING" -> summary.totalOutstanding > 0.01
                "PARTIAL" -> summary.totalOutstanding > 0.01 && summary.totalPaid > 0.01
                "OVERDUE" -> summary.isOverdue && summary.totalOutstanding > 0.01
                "DUE_TODAY" -> summary.oldestDueDate == todayIso && summary.totalOutstanding > 0.01
                "PAID" -> summary.totalOutstanding <= 0.01 && summary.totalPurchases > 0.0
                else -> true
            }
            matchesQuery && matchesFilter
        }

        // Sort
        when (sort) {
            "HIGHEST_DUE" -> filtered.sortedByDescending { it.totalOutstanding }
            "LOWEST_DUE" -> filtered.sortedBy { it.totalOutstanding }
            "OLDEST_DUE" -> filtered.sortedWith(compareBy<CustomerUdhariSummary> { it.oldestDueDate == null }.thenBy { it.oldestDueDate ?: "" })
            "NEWEST_DUE" -> filtered.sortedByDescending { it.oldestDueDate ?: "" }
            "A_Z" -> filtered.sortedBy { it.customer.name }
            else -> filtered.sortedByDescending { it.totalOutstanding }
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Overall Metrics
    val udhariMetrics: StateFlow<UdhariOverallMetrics> = combine(
        customersFlow,
        billsFlow,
        transactionsFlow
    ) { customers, bills, transactions ->
        Triple(customers, bills, transactions)
    }.debounce(200).map { (customers, bills, transactions) ->
        val activeBills = bills.filter { !it.isVoided }
        val validTxs = transactions.filter { !it.isReversed }

        val totalOutstanding = ShopMetricsCalculator.calculateTotalPendingUdhari(activeBills, validTxs, customers)
        val pendingCustomersCount = ShopMetricsCalculator.calculatePendingCustomersCount(activeBills, validTxs, customers)
        val pendingBills = activeBills.filter { it.pendingAmount > 0.01 }

        val todayIso = DateUtils.todayDate()
        val currentMonthIso = todayIso.take(7)

        val todayPayments = validTxs.filter { it.type == UdhariTransactionType.PAYMENT && it.date == todayIso }
        val todayCollections = todayPayments.sumOf { it.amount }
        val todayCashCollections = todayPayments.filter { it.paymentMethod == PaymentMethod.CASH }.sumOf { it.amount }
        val todayUpiCollections = todayPayments.filter { it.paymentMethod == PaymentMethod.UPI }.sumOf { it.amount }

        val monthPayments = validTxs.filter { it.type == UdhariTransactionType.PAYMENT && it.date.startsWith(currentMonthIso) }
        val monthCollections = monthPayments.sumOf { it.amount }

        val overdueAmount = pendingBills.filter { it.dueDate != null && DateUtils.isDatePassed(it.dueDate) }.sumOf { it.pendingAmount }

        UdhariOverallMetrics(
            totalOutstanding = totalOutstanding,
            customersWithPending = pendingCustomersCount,
            todayCollections = todayCollections,
            todayCashCollections = todayCashCollections,
            todayUpiCollections = todayUpiCollections,
            thisMonthCollections = monthCollections,
            overdueAmount = overdueAmount,
            pendingBillsCount = pendingBills.size
        )
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UdhariOverallMetrics())

    fun onSearchChange(q: String) {
        _searchQuery.value = q
    }

    fun onFilterSelect(f: String) {
        _selectedFilter.value = f
    }

    fun onSortSelect(s: String) {
        _selectedSort.value = s
    }

    suspend fun recordQuickPayment(
        customer: Customer,
        amount: Double,
        paymentMethod: PaymentMethod,
        note: String? = null
    ): Result<UdhariTransaction> {
        val bills = billRepository.getAllBills().firstOrNull() ?: emptyList()
        val custPendingBills = bills.filter { !it.isVoided && (it.customerId == customer.id || it.customerMobile == customer.mobile) && it.pendingAmount > 0.01 }
            .sortedBy { it.billDate }

        val targetBill = custPendingBills.firstOrNull()
        val safeAmount = amount.coerceIn(0.01, customer.outstandingBalance.coerceAtLeast(0.01))

        val billNumberSummary = when {
            custPendingBills.size > 1 -> "MULTI-BILL (${custPendingBills.size} बिले)"
            targetBill != null -> targetBill.billNumber
            else -> "DIRECT-PAY"
        }

        val txUuid = UUID.randomUUID().toString()
        val transaction = UdhariTransaction(
            customerId = customer.id,
            customerName = customer.name,
            customerMobile = customer.mobile,
            customerVillage = customer.address,
            billId = if (custPendingBills.size == 1) (targetBill?.id ?: 0L) else 0L,
            billNumber = billNumberSummary,
            type = UdhariTransactionType.PAYMENT,
            amount = safeAmount,
            paymentMethod = paymentMethod,
            previousOutstanding = customer.outstandingBalance,
            remainingOutstanding = (customer.outstandingBalance - safeAmount).coerceAtLeast(0.0),
            transactionUuid = txUuid,
            date = DateUtils.todayDate(),
            note = note,
            createdAt = System.currentTimeMillis()
        )

        val result = udhariRepository.recordPayment(transaction)
        return if (result.isSuccess) {
            val newBalance = (customer.outstandingBalance - safeAmount).coerceAtLeast(0.0)
            try {
                customerRepository.saveCustomer(customer.copy(outstandingBalance = newBalance))
            } catch (_: Exception) { }
            Result.success(transaction)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Payment failed"))
        }
    }
}

data class UdhariOverallMetrics(
    val totalOutstanding: Double = 0.0,
    val customersWithPending: Int = 0,
    val todayCollections: Double = 0.0,
    val todayCashCollections: Double = 0.0,
    val todayUpiCollections: Double = 0.0,
    val thisMonthCollections: Double = 0.0,
    val overdueAmount: Double = 0.0,
    val pendingBillsCount: Int = 0
)

@Composable
fun UdhariScreen(
    viewModel: UdhariViewModel,
    onCustomerClick: (Long) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val metrics by viewModel.udhariMetrics.collectAsState()
    val customerList by viewModel.customerSummaries.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            viewModel.onSearchChange("")
        }
    }

    val uniqueCustomerList = remember(customerList) {
        customerList.distinctBy { it.customer.canonicalKey() }
    }

    var activePaymentCustomer by remember { mutableStateOf<CustomerUdhariSummary?>(null) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    var showBulkReminderDialog by remember { mutableStateOf(false) }
    var isSendingBulkReminders by remember { mutableStateOf(false) }
    var bulkReminderStatusText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "उधारी वही (खाते)",
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)
        ) {
            // 1. TOP METRICS SUMMARY CARD
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
                                    text = "एकूण उधारी बाकी",
                                    style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal, letterSpacing = 1.sp)
                                )
                                Text(
                                    text = CurrencyFormatter.format(metrics.totalOutstanding),
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        color = if (metrics.totalOutstanding > 0) BoutiqueError else DeepEmerald,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Surface(
                                color = DeepEmeraldContainer.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("${metrics.customersWithPending}", fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 18.sp)
                                    Text("कस्टमर्स", style = MaterialTheme.typography.labelSmall.copy(color = DeepEmerald))
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = OutlineVariantGrey.copy(alpha = 0.25f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("आज जमा रक्कम", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                Text(CurrencyFormatter.format(metrics.todayCollections), fontWeight = FontWeight.Bold, color = BoutiqueSuccess)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("चालू महिना", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                Text(CurrencyFormatter.format(metrics.thisMonthCollections), fontWeight = FontWeight.SemiBold, color = DeepEmerald)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("थकीत उधारी", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                Text(CurrencyFormatter.format(metrics.overdueAmount), fontWeight = FontWeight.Bold, color = if (metrics.overdueAmount > 0) BoutiqueError else MutedCharcoal)
                            }
                        }
                    }
                }
            }

            // 2. SEARCH BAR
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.onSearchChange(it) },
                    placeholder = { Text("कस्टमरचे नाव, मोबाईल, गाव सर्च करा…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "सर्च", tint = DeepEmerald) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "साफ करा")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = WarmWhite,
                        unfocusedContainerColor = WarmWhite
                    ),
                    singleLine = true
                )
            }

            // 3. FILTER CHIPS
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "PENDING" to "उधारी बाकी",
                        "PARTIAL" to "काही रक्कम जमा",
                        "OVERDUE" to "थकीत उधारी",
                        "DUE_TODAY" to "आजची मुदत",
                        "PAID" to "पूर्ण भरणा (निल)",
                        "ALL" to "सर्व कस्टमर्स"
                    ).forEach { (filterKey, label) ->
                        FilterChip(
                            selected = selectedFilter == filterKey,
                            onClick = { viewModel.onFilterSelect(filterKey) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepEmeraldContainer,
                                selectedLabelColor = WarmWhite
                            )
                        )
                    }
                }
            }

            // 4. CUSTOMER UDHARI CARDS
            if (uniqueCustomerList.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = "क्लिअर", tint = BoutiqueSuccess, modifier = Modifier.size(56.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (selectedFilter == "PENDING") "कोणतीही उधारी बाकी नाही!" else "कोणताही कस्टमर सापडला नाही",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                            )
                            Text(
                                text = if (selectedFilter == "PENDING") "सर्व कस्टमर्सची खाती पूर्णपणे क्लिअर आहेत." else "सर्च किंवा फिल्टर बदलून पहा.",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                            )
                        }
                    }
                }
            } else {
                items(
                    items = uniqueCustomerList,
                    key = { item -> "udhari_${item.customer.id}_${item.customer.canonicalKey()}" }
                ) { summary ->
                    UdhariCustomerCard(
                        summary = summary,
                        onClick = { onCustomerClick(summary.customer.id) },
                        onAddPayment = {
                            activePaymentCustomer = summary
                            showPaymentDialog = true
                        },
                        onRemind = {
                            openWhatsAppReminder(context, summary)
                        },
                        onCall = {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${summary.customer.mobile}"))
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }
    }

    // RECORD PAYMENT DIALOG
    if (showPaymentDialog && activePaymentCustomer != null) {
        UdhariPaymentDialog(
            summary = activePaymentCustomer!!,
            onDismiss = {
                showPaymentDialog = false
                activePaymentCustomer = null
            },
            onConfirm = { amount, method, note ->
                val cust = activePaymentCustomer!!.customer
                val priorBalance = cust.outstandingBalance
                viewModel.viewModelScope.launch {
                    val res = viewModel.recordQuickPayment(cust, amount, method, note)
                    if (res.isSuccess) {
                        Toast.makeText(context, "${CurrencyFormatter.format(amount)} चे पेमेंट यशस्वीरित्या जमा झाले!", Toast.LENGTH_SHORT).show()
                        showPaymentDialog = false
                        activePaymentCustomer = null
                        // Asynchronously dispatch WhatsApp Udhari Cleared / Payment confirmation
                        try {
                            val newDue = (priorBalance - amount).coerceAtLeast(0.0)
                            com.matoshree.shopmanager.utils.WhatsAppWebhookManager.sendUdhariClearedWhatsApp(
                                context = context,
                                customerName = cust.name,
                                customerMobile = cust.mobile,
                                amountPaid = amount,
                                remainingBalance = newDue,
                                paymentMethod = method.name
                            )
                        } catch (we: Exception) {
                            android.util.Log.w("UdhariScreen", "WhatsApp cleared dispatch note: ${we.message}")
                        }
                    } else {
                        Toast.makeText(context, "पेमेंट अयशस्वी: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (showBulkReminderDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isSendingBulkReminders) {
                    showBulkReminderDialog = false
                    bulkReminderStatusText = ""
                }
            },
            icon = {
                Icon(Icons.Default.Send, contentDescription = null, tint = BoutiqueSuccess, modifier = Modifier.size(32.dp))
            },
            title = {
                Text(
                    text = "सर्व थकबाकीदारांना आठवण पाठवायची?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = DeepCharcoal
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "हे सुरू केल्यावर सर्व उधारी शिल्लक असलेल्या ग्राहकांना व्हॉट्सॲपवर उधारी आठवण मेसेज आपोआप पाठवला जाईल.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                    )
                    if (bulkReminderStatusText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = WarmIvory),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSendingBulkReminders) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = BoutiqueSuccess)
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                Text(bulkReminderStatusText, fontSize = 13.sp, color = DeepCharcoal, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!com.matoshree.shopmanager.utils.WhatsAppWebhookManager.isConfigured(context)) {
                            Toast.makeText(context, "कृपया सेटिंग्जमधून Webhook URL सेट करा", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        viewModel.viewModelScope.launch {
                            isSendingBulkReminders = true
                            bulkReminderStatusText = "थकबाकीदार शोधून मेसेज पाठवत आहे..."
                            val result = com.matoshree.shopmanager.utils.WhatsAppWebhookManager.dispatchBulkUdhariReminders(context) { current, total, name ->
                                bulkReminderStatusText = "[$current/$total] $name ला पाठवत आहे..."
                            }
                            val (success, failed) = result.getOrDefault(0 to 0)
                            isSendingBulkReminders = false
                            bulkReminderStatusText = "पूर्ण झाले: $success यशस्वी, $failed अयशस्वी"
                            Toast.makeText(context, "उधारी आठवण पूर्ण: $success पाठवले, $failed अपयशी", Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !isSendingBulkReminders,
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueSuccess),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isSendingBulkReminders) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = WarmWhite)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("पाठवत आहे...")
                    } else {
                        Text("होय, पाठवा")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBulkReminderDialog = false
                        bulkReminderStatusText = ""
                    },
                    enabled = !isSendingBulkReminders
                ) {
                    Text("बंद करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun UdhariCustomerCard(
    summary: CustomerUdhariSummary,
    onClick: () -> Unit,
    onAddPayment: () -> Unit,
    onRemind: () -> Unit,
    onCall: () -> Unit
) {
    val cust = summary.customer
    val hasPending = summary.totalOutstanding > 0.01

    MatoshreeCard(
        hasGoldTopBorder = summary.isOverdue,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (hasPending) DeepEmeraldContainer else BoutiqueSuccess.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = cust.name.take(2).uppercase(),
                            color = if (hasPending) WarmWhite else BoutiqueSuccess,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                cust.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (cust.tier == "VIP" || cust.listTags.contains("VIP")) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "VIP",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ChampagneGold,
                                    modifier = Modifier
                                        .background(ChampagneGoldContainer, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(cust.mobile, style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal), maxLines = 1)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "📍 ${cust.address ?: "गाव"}",
                                style = MaterialTheme.typography.bodySmall.copy(color = DeepEmerald, fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (hasPending) CurrencyFormatter.format(summary.totalOutstanding) else "बाकी नाही",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (summary.isOverdue) BoutiqueError else if (hasPending) DeepEmerald else BoutiqueSuccess
                        ),
                        maxLines = 1,
                        softWrap = false
                    )
                    if (summary.isOverdue) {
                        Text("थकीत", color = BoutiqueError, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                    } else if (hasPending && summary.oldestDueDate != null) {
                        Text("मुदत: ${summary.oldestDueDate}", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal), maxLines = 1)
                    }
                }
            }

            // Payment / Activity Details
            if (summary.lastPaymentAmount != null && summary.lastPaymentDate != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarmIvory, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "शेवटचे जमा: ${CurrencyFormatter.format(summary.lastPaymentAmount)} (${summary.lastPaymentDate})",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${summary.pendingBillsCount} बिले बाकी",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, color = DeepEmerald),
                        maxLines = 1
                    )
                }
            }

            // Action Buttons
            if (hasPending) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onAddPayment,
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1.2f).height(38.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "पेमेंट जमा", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("पेमेंट जमा", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onRemind,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1.2f).height(38.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BoutiqueSuccess)
                    ) {
                        Text("व्हॉट्सॲप", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }

                    IconButton(
                        onClick = onCall,
                        modifier = Modifier
                            .size(38.dp)
                            .background(WarmIvory, RoundedCornerShape(8.dp))
                            .border(1.dp, OutlineVariantGrey.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "कॉल करा", tint = DeepEmerald, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun UdhariPaymentDialog(
    summary: CustomerUdhariSummary,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, method: PaymentMethod, note: String?) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var selectedMethod by remember { mutableStateOf(PaymentMethod.CASH) }
    var paymentNote by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    val outstanding = summary.totalOutstanding
    val enteredAmount = amountText.toDoubleOrNull() ?: 0.0
    val isValid = enteredAmount > 0.0 && enteredAmount <= outstanding && !isSubmitting

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Column {
                Text("पेमेंट जमा करा", fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 18.sp)
                Text(
                    text = "${summary.customer.name} • बाकी: ${CurrencyFormatter.format(outstanding)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Quick Amount Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(500.0, 1000.0, 2000.0, 5000.0).forEach { chipAmt ->
                        if (chipAmt <= outstanding) {
                            FilterChip(
                                selected = amountText == chipAmt.toInt().toString(),
                                onClick = { amountText = chipAmt.toInt().toString() },
                                label = { Text("₹${chipAmt.toInt()}", fontSize = 12.sp) }
                            )
                        }
                    }
                    FilterChip(
                        selected = amountText == outstanding.toString() || amountText == String.format(java.util.Locale.US, "%.2f", outstanding),
                        onClick = { amountText = String.format(java.util.Locale.US, "%.2f", outstanding) },
                        label = { Text("पूर्ण बाकी (${CurrencyFormatter.format(outstanding)})", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DeepEmeraldContainer,
                            selectedLabelColor = WarmWhite
                        )
                    )
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("जमा रक्कम (₹) *") },
                    placeholder = { Text("उदा. 1000") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    isError = enteredAmount > outstanding,
                    supportingText = {
                        if (enteredAmount > outstanding) {
                            Text("रक्कम बाकीपेक्षा जास्त आहे (${CurrencyFormatter.format(outstanding)})", color = BoutiqueError)
                        } else if (enteredAmount > 0) {
                            val remaining = (outstanding - enteredAmount).coerceAtLeast(0.0)
                            Text("शिल्लक बाकी: ${CurrencyFormatter.format(remaining)}", color = DeepEmerald, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )

                // Payment Method Selector
                Text("पेमेंट पद्धत *", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(PaymentMethod.CASH, PaymentMethod.UPI).forEach { method ->
                        Box(modifier = Modifier.weight(1f)) {
                            PaymentMethodPill(
                                method = method,
                                isSelected = selectedMethod == method,
                                onClick = { selectedMethod = method }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = paymentNote,
                    onValueChange = { paymentNote = it },
                    label = { Text("नोंद / टीप (पर्यायी)") },
                    placeholder = { Text("उदा. गुगल पे द्वारे जमा") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid) {
                        isSubmitting = true
                        onConfirm(enteredAmount, selectedMethod, paymentNote.ifBlank { null })
                    }
                },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("पेमेंट निश्चित करा", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text("रद्द करा", color = MutedCharcoal)
            }
        },
        containerColor = WarmWhite
    )
}

fun openWhatsAppReminder(context: Context, summary: CustomerUdhariSummary) {
    val cust = summary.customer
    PrintShareHelper.shareCustomerUdhariStatementJpg(
        context = context,
        customerName = cust.name,
        customerMobile = cust.mobile,
        customerVillage = cust.address,
        totalOutstanding = summary.totalOutstanding,
        lastPaymentAmount = summary.lastPaymentAmount,
        lastPaymentDate = summary.lastPaymentDate,
        pendingBillsCount = summary.pendingBillsCount,
        dueDate = summary.oldestDueDate
    )
}

package com.matoshree.shopmanager.ui.screens.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.ExpenseRepository
import com.matoshree.shopmanager.domain.model.Expense
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExpenseViewModel(private val expenseRepository: ExpenseRepository) : ViewModel() {
    private val _expenses = MutableStateFlow<List<Expense>>(emptyList())
    val expenses: StateFlow<List<Expense>> = _expenses

    private val errorHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("ExpenseViewModel", "Expense error caught safely: ${throwable.message}", throwable)
    }

    init {
        observeExpenses()
    }

    private fun observeExpenses() {
        viewModelScope.launch(errorHandler) {
            expenseRepository.getAllExpenses()
                .catch { e -> android.util.Log.e("ExpenseViewModel", "Expense flow error: ${e.message}", e) }
                .collectLatest {
                    _expenses.value = it
                }
        }
    }

    fun addExpense(category: String, amount: Double, paymentMethod: PaymentMethod, note: String?) {
        viewModelScope.launch {
            expenseRepository.addExpense(
                category = category,
                amount = amount,
                paymentMethod = paymentMethod,
                expenseDate = DateUtils.todayDate(),
                note = note
            )
        }
    }

    fun updateExpense(id: Long, category: String, amount: Double, paymentMethod: PaymentMethod, note: String?, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val res = expenseRepository.updateExpense(id, category, amount, paymentMethod, note)
            onComplete(res)
        }
    }

    fun deleteExpense(id: Long, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            expenseRepository.deleteExpense(id)
            onComplete()
        }
    }
}

@Composable
fun ExpensesListScreen(
    viewModel: ExpenseViewModel,
    onBackClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val expenses by viewModel.expenses.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var expenseToEdit by remember { mutableStateOf<Expense?>(null) }
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }

    val totalExpenses = remember(expenses) {
        expenses.sumOf { it.amount }
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "दुकानाचे खर्च",
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = DeepEmerald,
                contentColor = WarmWhite,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "खर्च जोडा")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)
        ) {
            item {
                MatoshreeCard {
                    Text("एकूण खर्च", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = CurrencyFormatter.format(totalExpenses),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            color = BoutiqueError,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            if (expenses.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("अद्याप कोणतेही खर्च नोंदवलेले नाहीत.", style = MaterialTheme.typography.bodyLarge.copy(color = MutedCharcoal))
                    }
                }
            } else {
                items(expenses, key = { ex -> "exp_${ex.id}" }) { ex ->
                    MatoshreeCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ex.category,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "${ex.expenseDate} • ${ex.paymentMethod.name} ${if (!ex.note.isNullOrBlank()) "• ${ex.note}" else ""}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "-${CurrencyFormatter.format(ex.amount)}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = BoutiqueError,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                IconButton(
                                    onClick = { expenseToEdit = ex },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "बदला", tint = DeepEmerald, modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { expenseToDelete = ex },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "हटवा", tint = BoutiqueError, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit Expense Dialog
    expenseToEdit?.let { ex ->
        var editCategory by remember(ex) { mutableStateOf(ex.category) }
        var editAmountText by remember(ex) { mutableStateOf(String.format(java.util.Locale.US, "%.0f", ex.amount)) }
        var editNote by remember(ex) { mutableStateOf(ex.note ?: "") }

        AlertDialog(
            onDismissRequest = { expenseToEdit = null },
            title = { Text("खर्च बदला", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editAmountText,
                        onValueChange = { editAmountText = it },
                        label = { Text("रक्कम (₹) *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editCategory,
                        onValueChange = { editCategory = it },
                        label = { Text("खर्च प्रकार") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        label = { Text("नोंद / टीप") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = editAmountText.toDoubleOrNull() ?: 0.0
                        if (amt > 0) {
                            viewModel.updateExpense(
                                id = ex.id,
                                category = editCategory.trim(),
                                amount = amt,
                                paymentMethod = ex.paymentMethod,
                                note = editNote.ifBlank { null }
                            ) { success ->
                                if (success) {
                                    android.widget.Toast.makeText(context, "खर्च अपडेट केला", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            expenseToEdit = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("बदल सेव्ह करा")
                }
            },
            dismissButton = {
                TextButton(onClick = { expenseToEdit = null }) {
                    Text("रद्द करा")
                }
            }
        )
    }

    // Delete Expense Confirmation Dialog
    expenseToDelete?.let { ex ->
        AlertDialog(
            onDismissRequest = { expenseToDelete = null },
            title = { Text("खर्च ट्रॅशमध्ये हलवायचा का?", fontWeight = FontWeight.Bold, color = BoutiqueError) },
            text = { Text("आपण '${ex.category} - ${CurrencyFormatter.format(ex.amount)}' हा खर्च ट्रॅशमध्ये हलवू इच्छिता का? आवश्यक असल्यास ट्रॅशमधून तो रिस्टोअर करता येईल.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteExpense(ex.id) {
                            android.widget.Toast.makeText(context, "खर्च ट्रॅशमध्ये हलवला", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        expenseToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError)
                ) {
                    Text("ट्रॅशमध्ये हलवा")
                }
            },
            dismissButton = {
                TextButton(onClick = { expenseToDelete = null }) {
                    Text("रद्द करा")
                }
            }
        )
    }

    if (showAddDialog) {
        var category by remember { mutableStateOf("भाडे (RENT)") }
        var amountText by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("दुकानाचा खर्च नोंदवा", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("रक्कम (₹) *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("खर्च प्रकार (उदा. भाडे, पगार, वीज बिल, इतर)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("नोंद / टीप (पर्यायी)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amountText.toDoubleOrNull() ?: 0.0
                        if (amt > 0) {
                            viewModel.addExpense(category.trim(), amt, PaymentMethod.CASH, note.ifBlank { null })
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("खर्च सेव्ह करा")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("रद्द करा")
                }
            }
        )
    }
}

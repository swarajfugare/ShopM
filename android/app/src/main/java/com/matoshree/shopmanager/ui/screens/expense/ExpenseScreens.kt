package com.matoshree.shopmanager.ui.screens.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExpenseViewModel(private val expenseRepository: ExpenseRepository) : ViewModel() {
    private val _expenses = MutableStateFlow<List<Expense>>(emptyList())
    val expenses: StateFlow<List<Expense>> = _expenses

    init {
        observeExpenses()
    }

    private fun observeExpenses() {
        viewModelScope.launch {
            expenseRepository.getAllExpenses().collectLatest {
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
}

@Composable
fun ExpensesListScreen(
    viewModel: ExpenseViewModel,
    onBackClick: () -> Unit
) {
    val expenses by viewModel.expenses.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    val totalExpenses = remember(expenses) {
        expenses.sumOf { it.amount }
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "Shop Expenses",
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
                Icon(Icons.Default.Add, contentDescription = "Add Expense")
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
                    Text("TOTAL EXPENSES", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
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
                        Text("No expenses recorded yet.", style = MaterialTheme.typography.bodyLarge.copy(color = MutedCharcoal))
                    }
                }
            } else {
                items(expenses) { ex ->
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

                            Text(
                                text = "-${CurrencyFormatter.format(ex.amount)}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = BoutiqueError,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var category by remember { mutableStateOf("RENT") }
        var amountText by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        val categories = listOf("RENT", "ELECTRICITY", "SALARY", "TRANSPORT", "PACKAGING", "MAINTENANCE", "OTHER")

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Record Shop Expense", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount (₹) *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Category (e.g. RENT, SALARY, OTHER)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amountText.toDoubleOrNull() ?: 0.0
                        if (amt > 0) {
                            viewModel.addExpense(category.uppercase(), amt, PaymentMethod.CASH, note.ifBlank { null })
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("Save Expense")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

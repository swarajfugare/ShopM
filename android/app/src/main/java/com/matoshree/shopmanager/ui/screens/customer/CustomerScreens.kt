package com.matoshree.shopmanager.ui.screens.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.CustomerRepository
import com.matoshree.shopmanager.domain.model.Customer
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.StatusBadge
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CustomerViewModel(private val customerRepository: CustomerRepository) : ViewModel() {
    private val _customers = MutableStateFlow<List<Customer>>(emptyList())
    val customers: StateFlow<List<Customer>> = _customers

    val searchQuery = MutableStateFlow("")

    init {
        observeCustomers()
    }

    private fun observeCustomers() {
        viewModelScope.launch {
            customerRepository.getAllCustomers().collectLatest {
                _customers.value = it
            }
        }
    }

    fun addCustomer(name: String, mobile: String, email: String?, address: String?) {
        viewModelScope.launch {
            customerRepository.saveCustomer(
                Customer(
                    name = name,
                    mobile = mobile,
                    email = email,
                    address = address
                )
            )
        }
    }
}

@Composable
fun CustomersListScreen(
    viewModel: CustomerViewModel,
    onCustomerClick: (Long) -> Unit,
    onBackClick: () -> Unit
) {
    val customers by viewModel.customers.collectAsState()
    val search by viewModel.searchQuery.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredCustomers = remember(customers, search) {
        if (search.isBlank()) customers else customers.filter {
            it.name.contains(search, ignoreCase = true) || it.mobile.contains(search, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "Customer Directory",
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
                Icon(Icons.Default.PersonAdd, contentDescription = "Add Customer")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Manage your premium clientele and track their engagement.",
                style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal),
                modifier = Modifier.padding(top = 8.dp)
            )

            OutlinedTextField(
                value = search,
                onValueChange = { viewModel.searchQuery.value = it },
                placeholder = { Text("Search by name or mobile…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(8.dp)
            )

            if (filteredCustomers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No customers found.", style = MaterialTheme.typography.bodyLarge.copy(color = MutedCharcoal))
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(filteredCustomers) { cust ->
                        MatoshreeCard(
                            hasGoldLeftAccent = cust.tier == "VIP",
                            onClick = { onCustomerClick(cust.id) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cust.name,
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            color = DeepCharcoal,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Call,
                                            contentDescription = "Call",
                                            tint = MutedCharcoal,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = cust.mobile,
                                            style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = "Bills: ${cust.totalBills} • Spent: ${CurrencyFormatter.format(cust.lifetimeSpend)}",
                                        style = MaterialTheme.typography.labelSmall.copy(color = DeepEmerald, fontWeight = FontWeight.SemiBold)
                                    )
                                }

                                if (cust.tier == "VIP") {
                                    StatusBadge(status = "VIP", isWarning = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        var mobile by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add New Customer", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Customer Name *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = mobile,
                        onValueChange = { mobile = it },
                        label = { Text("Mobile Number *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Address (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (name.isNotBlank() && mobile.isNotBlank()) {
                            viewModel.addCustomer(name, mobile, email.ifBlank { null }, address.ifBlank { null })
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("Save Customer")
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

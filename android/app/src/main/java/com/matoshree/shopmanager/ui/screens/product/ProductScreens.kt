package com.matoshree.shopmanager.ui.screens.product

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
import com.matoshree.shopmanager.data.repository.ProductRepository
import com.matoshree.shopmanager.domain.model.Category
import com.matoshree.shopmanager.domain.model.Product
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.StatusBadge
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProductViewModel(private val productRepository: ProductRepository) : ViewModel() {
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories

    val searchQuery = MutableStateFlow("")

    init {
        observeCatalog()
    }

    private fun observeCatalog() {
        viewModelScope.launch {
            productRepository.getAllProducts().collectLatest {
                _products.value = it
            }
        }
        viewModelScope.launch {
            productRepository.getAllCategories().collectLatest {
                _categories.value = it
            }
        }
    }

    fun addProduct(name: String, sku: String?, price: Double, costPrice: Double?, categoryId: Long?) {
        viewModelScope.launch {
            productRepository.saveProduct(
                Product(
                    name = name,
                    sku = sku,
                    sellingPrice = price,
                    costPrice = costPrice,
                    categoryId = categoryId
                )
            )
        }
    }
}

@Composable
fun ProductsListScreen(
    viewModel: ProductViewModel,
    onBackClick: () -> Unit
) {
    val products by viewModel.products.collectAsState()
    val search by viewModel.searchQuery.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    val filtered = remember(products, search) {
        if (search.isBlank()) products else products.filter {
            it.name.contains(search, ignoreCase = true) || (it.sku?.contains(search, ignoreCase = true) == true)
        }
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "Boutique Catalog",
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
                Icon(Icons.Default.Add, contentDescription = "Add Product")
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
            OutlinedTextField(
                value = search,
                onValueChange = { viewModel.searchQuery.value = it },
                placeholder = { Text("Search products by name or SKU…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(8.dp)
            )

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No products in catalog.", style = MaterialTheme.typography.bodyLarge.copy(color = MutedCharcoal))
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(filtered) { product ->
                        MatoshreeCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = product.name,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "SKU: ${product.sku ?: "N/A"}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                                    )
                                    if (product.costPrice != null) {
                                        Text(
                                            text = "Cost: ${CurrencyFormatter.format(product.costPrice)} • Margin: ${String.format("%.0f", ((product.sellingPrice - product.costPrice) / product.sellingPrice) * 100)}%",
                                            style = MaterialTheme.typography.labelSmall.copy(color = ChampagneGold)
                                        )
                                    }
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = CurrencyFormatter.format(product.sellingPrice),
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            color = DeepEmerald,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
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
        var sku by remember { mutableStateOf("") }
        var priceText by remember { mutableStateOf("") }
        var costText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add New Product", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Product Name *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sku,
                        onValueChange = { sku = it },
                        label = { Text("SKU / Barcode") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("Selling Price (₹) *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("Cost Price (₹) (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val price = priceText.toDoubleOrNull() ?: 0.0
                        if (name.isNotBlank() && price > 0) {
                            viewModel.addProduct(name, sku.ifBlank { null }, price, costText.toDoubleOrNull(), null)
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("Save Product")
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

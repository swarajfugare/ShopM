package com.matoshree.shopmanager.ui.screens.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.domain.model.Product
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.PaymentMethodPill
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProductSheet(
    products: List<Product>,
    onProductSelected: (Product, Int, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var quantity by remember { mutableIntStateOf(1) }
    var discountText by remember { mutableStateOf("") }

    val filteredProducts = remember(searchQuery, products) {
        if (searchQuery.isBlank()) products else products.filter {
            it.name.contains(searchQuery, ignoreCase = true) || (it.sku?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = WarmWhite,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Gold top accent
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(ChampagneGoldContainer)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Add to Bill",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = PlayfairFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = DeepEmerald
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedProduct == null) {
                // Search Field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by name or SKU…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredProducts) { prod ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, OutlineVariantGrey.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .clickable { selectedProduct = prod }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(prod.name, fontWeight = FontWeight.SemiBold)
                                Text("SKU: ${prod.sku ?: "N/A"}", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            }
                            Text(
                                text = CurrencyFormatter.format(prod.sellingPrice),
                                fontWeight = FontWeight.Bold,
                                color = DeepEmerald
                            )
                        }
                    }
                }
            } else {
                // Selected Product Details & Quantity Adjuster
                val prod = selectedProduct!!
                MatoshreeCard(hasGoldTopBorder = true) {
                    Text(prod.name, style = MaterialTheme.typography.titleLarge.copy(color = DeepEmerald))
                    Text("SKU: ${prod.sku ?: "N/A"}", style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = CurrencyFormatter.format(prod.sellingPrice),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Quantity Stepper
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Quantity", style = MaterialTheme.typography.titleMedium)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .border(1.dp, OutlineGrey.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        ) {
                            IconButton(onClick = { if (quantity > 1) quantity-- }) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease")
                            }
                            Text(
                                text = "$quantity",
                                modifier = Modifier.padding(horizontal = 12.dp),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            IconButton(onClick = { quantity++ }) {
                                Icon(Icons.Default.Add, contentDescription = "Increase")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Discount Field
                    OutlinedTextField(
                        value = discountText,
                        onValueChange = { discountText = it },
                        label = { Text("Discount (₹)") },
                        placeholder = { Text("0.00") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            val disc = discountText.toDoubleOrNull() ?: 0.0
                            onProductSelected(prod, quantity, disc)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text("Add to Cart", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SaleSummaryScreen(
    viewModel: SaleViewModel,
    onCompleteSale: () -> Unit,
    onBackClick: () -> Unit
) {
    val cartItems by viewModel.cartItems.collectAsState()
    val selectedCustomer by viewModel.selectedCustomer.collectAsState()
    val selectedMethod by viewModel.selectedPaymentMethod.collectAsState()

    val subtotal = viewModel.subtotal
    val discount = viewModel.discountAmount.collectAsState().value
    val finalTotal = viewModel.finalTotal
    val estimatedProfit = finalTotal * 0.25

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "Sale Summary",
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = WarmWhite,
                shadowElevation = 8.dp
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = onCompleteSale,
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Text("Complete & Generate Bill", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
        ) {
            // Customer Banner
            if (selectedCustomer != null) {
                item {
                    MatoshreeCard {
                        Text("Billed To", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                        Text(selectedCustomer!!.name, style = MaterialTheme.typography.titleLarge)
                        Text(selectedCustomer!!.mobile, style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                    }
                }
            }

            // Itemized list
            item {
                MatoshreeCard(hasGoldTopBorder = true) {
                    Text("Items Summary", style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald))
                    Spacer(modifier = Modifier.height(12.dp))

                    cartItems.forEach { item ->
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
                        Divider(modifier = Modifier.padding(vertical = 8.dp), color = OutlineVariantGrey.copy(alpha = 0.2f))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Subtotal", style = MaterialTheme.typography.bodyLarge)
                        Text(CurrencyFormatter.format(subtotal), fontWeight = FontWeight.SemiBold)
                    }
                    if (discount > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Discount", color = BoutiqueSuccess)
                            Text("-${CurrencyFormatter.format(discount)}", color = BoutiqueSuccess, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = ChampagneGoldContainer)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("FINAL AMOUNT", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                            Text(
                                text = CurrencyFormatter.format(finalTotal),
                                style = MaterialTheme.typography.headlineLarge.copy(color = DeepEmerald, fontWeight = FontWeight.Bold)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("EST. PROFIT", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                            Text(
                                text = CurrencyFormatter.format(estimatedProfit),
                                style = MaterialTheme.typography.titleLarge.copy(color = BoutiqueSuccess, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            // Payment Mode Selector
            item {
                MatoshreeCard {
                    Text("Payment Method", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PaymentMethod.values().take(3).forEach { method ->
                            Box(modifier = Modifier.weight(1f)) {
                                PaymentMethodPill(
                                    method = method,
                                    isSelected = selectedMethod == method,
                                    onClick = { viewModel.selectedPaymentMethod.value = method }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

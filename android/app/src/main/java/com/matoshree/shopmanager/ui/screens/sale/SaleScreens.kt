package com.matoshree.shopmanager.ui.screens.sale

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.BillRepository
import com.matoshree.shopmanager.data.repository.CustomerRepository
import com.matoshree.shopmanager.data.repository.ProductRepository
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.ui.components.CustomKeypad
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.PaymentMethodPill
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SaleViewModel(
    private val billRepository: BillRepository,
    private val customerRepository: CustomerRepository,
    private val productRepository: ProductRepository
) : ViewModel() {

    // Customer selection
    val searchQuery = MutableStateFlow("")
    val customerSuggestions = MutableStateFlow<List<Customer>>(emptyList())
    val selectedCustomer = MutableStateFlow<Customer?>(null)

    // Detailed Sale Cart
    val cartItems = MutableStateFlow<List<BillItem>>(emptyList())
    val discountAmount = MutableStateFlow(0.0)

    // Quick Sale state
    val quickSaleAmountText = MutableStateFlow("0")
    val selectedPaymentMethod = MutableStateFlow(PaymentMethod.CASH)
    val saleNote = MutableStateFlow("")

    val subtotal: Double
        get() = cartItems.value.sumOf { it.sellingPrice * it.quantity }

    val finalTotal: Double
        get() = (subtotal - discountAmount.value).coerceAtLeast(0.0)

    fun onSearchCustomer(query: String) {
        searchQuery.value = query
        viewModelScope.launch {
            if (query.length >= 2) {
                customerSuggestions.value = customerRepository.searchCustomers(query)
            } else {
                customerSuggestions.value = emptyList()
            }
        }
    }

    fun selectCustomer(customer: Customer?) {
        selectedCustomer.value = customer
        searchQuery.value = customer?.name ?: ""
        customerSuggestions.value = emptyList()
    }

    fun addItemToCart(product: Product, quantity: Int, discount: Double = 0.0) {
        val current = cartItems.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.productId == product.id }

        val lineTotal = ((product.sellingPrice * quantity) - discount).coerceAtLeast(0.0)
        val lineCost = (product.costPrice ?: (product.sellingPrice * 0.75)) * quantity
        val lineProfit = lineTotal - lineCost

        val newItem = BillItem(
            productId = product.id,
            productName = product.name,
            sku = product.sku,
            categoryId = product.categoryId,
            quantity = quantity,
            sellingPrice = product.sellingPrice,
            costPrice = product.costPrice,
            discountAmount = discount,
            lineTotal = lineTotal,
            lineCost = lineCost,
            lineProfit = lineProfit
        )

        if (existingIndex >= 0) {
            current[existingIndex] = newItem
        } else {
            current.add(newItem)
        }
        cartItems.value = current
    }

    fun removeItemFromCart(index: Int) {
        val current = cartItems.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            cartItems.value = current
        }
    }

    // Quick sale keypad input
    fun onKeypadDigit(digit: String) {
        val cur = quickSaleAmountText.value
        if (cur == "0") {
            if (digit != "0" && digit != "00") {
                quickSaleAmountText.value = digit
            }
        } else if (cur.length < 7) {
            quickSaleAmountText.value = cur + digit
        }
    }

    fun onKeypadDelete() {
        val cur = quickSaleAmountText.value
        if (cur.length > 1) {
            quickSaleAmountText.value = cur.dropLast(1)
        } else {
            quickSaleAmountText.value = "0"
        }
    }

    suspend fun completeSale(saleType: SaleType): Bill {
        val quickAmt = if (saleType == SaleType.QUICK) quickSaleAmountText.value.toDoubleOrNull() ?: 0.0 else null
        return billRepository.createSale(
            saleType = saleType,
            customer = selectedCustomer.value,
            items = cartItems.value,
            discountAmount = discountAmount.value,
            quickSaleAmount = quickAmt,
            paymentMethod = selectedPaymentMethod.value,
            note = saleNote.value.ifBlank { null }
        )
    }

    fun reset() {
        selectedCustomer.value = null
        searchQuery.value = ""
        customerSuggestions.value = emptyList()
        cartItems.value = emptyList()
        discountAmount.value = 0.0
        quickSaleAmountText.value = "0"
        selectedPaymentMethod.value = PaymentMethod.CASH
        saleNote.value = ""
    }
}

@Composable
fun NewSaleScreen(
    viewModel: SaleViewModel,
    onAddProductClick: () -> Unit,
    onCheckoutClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val suggestions by viewModel.customerSuggestions.collectAsState()
    val selectedCustomer by viewModel.selectedCustomer.collectAsState()
    val cartItems by viewModel.cartItems.collectAsState()

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "New Sale",
                showBackButton = true,
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = WarmWhite,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineVariantGrey.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TOTAL",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = MutedCharcoal,
                                letterSpacing = 1.sp
                            )
                        )
                        Text(
                            text = CurrencyFormatter.format(viewModel.finalTotal),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                color = DeepEmerald,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Button(
                        onClick = onCheckoutClick,
                        enabled = cartItems.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepEmerald,
                            disabledContainerColor = SurfaceContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Checkout", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            // Customer Search Card
            item {
                MatoshreeCard {
                    Text(
                        text = "CUSTOMER",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = MutedCharcoal,
                            letterSpacing = 1.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.onSearchCustomer(it) },
                        placeholder = { Text("Search by name or phone…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        trailingIcon = {
                            if (selectedCustomer != null) {
                                IconButton(onClick = { viewModel.selectCustomer(null) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DeepEmerald,
                            unfocusedBorderColor = OutlineGrey.copy(alpha = 0.5f)
                        )
                    )

                    // Autocomplete Dropdown List
                    if (suggestions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(WarmWhite)
                                .border(1.dp, ChampagneGoldContainer, RoundedCornerShape(8.dp))
                        ) {
                            suggestions.forEach { cust ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.selectCustomer(cust) }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(DeepEmeraldContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = cust.name.take(2).uppercase(),
                                            color = WarmWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(cust.name, fontWeight = FontWeight.SemiBold)
                                        Text(cust.mobile, style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                                    }
                                }
                                Divider(color = OutlineVariantGrey.copy(alpha = 0.2f))
                            }
                        }
                    }
                }
            }

            // Cart Items Area
            if (cartItems.isEmpty()) {
                item {
                    MatoshreeCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(DeepEmerald.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ShoppingBag,
                                    contentDescription = "Cart",
                                    tint = DeepEmerald,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Cart is Empty",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = PlayfairFontFamily,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Select a customer or add products to start building this sale.",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MutedCharcoal,
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = onAddProductClick,
                                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Add Product", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Cart Items (${cartItems.size})", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onAddProductClick) {
                            Icon(Icons.Default.Add, contentDescription = "Add More", tint = DeepEmerald)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add More", color = DeepEmerald)
                        }
                    }
                }

                items(cartItems) { item ->
                    MatoshreeCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.productName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(
                                    text = "SKU: ${item.sku ?: "N/A"} • Qty: ${item.quantity}",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                                )
                            }
                            Text(
                                text = CurrencyFormatter.format(item.lineTotal),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = DeepEmerald
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickSaleScreen(
    viewModel: SaleViewModel,
    onCompleteClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val amountText by viewModel.quickSaleAmountText.collectAsState()
    val selectedMethod by viewModel.selectedPaymentMethod.collectAsState()
    val note by viewModel.saleNote.collectAsState()

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "Quick Sale",
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
                        onClick = onCompleteClick,
                        enabled = (amountText.toDoubleOrNull() ?: 0.0) > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Text(
                            text = "Save Sale • ₹$amountText",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "ENTER FINAL AMOUNT",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = MutedCharcoal,
                    letterSpacing = 1.5.sp
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "₹$amountText",
                style = MaterialTheme.typography.displayLarge.copy(
                    color = DeepEmerald,
                    fontWeight = FontWeight.Bold,
                    fontSize = 48.sp
                )
            )

            // Gold accent line
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(2.dp)
                    .background(ChampagneGoldContainer)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Payment Selector
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

            Spacer(modifier = Modifier.height(32.dp))

            // Custom Keypad
            CustomKeypad(
                onDigitClick = { viewModel.onKeypadDigit(it) },
                onDeleteClick = { viewModel.onKeypadDelete() },
                showDoubleZero = true
            )
        }
    }
}

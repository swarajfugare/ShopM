package com.matoshree.shopmanager.ui.screens.sale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matoshree.shopmanager.domain.model.Category
import com.matoshree.shopmanager.domain.model.DiscountType
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.domain.model.PaymentStatus
import com.matoshree.shopmanager.domain.model.Product
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.PaymentMethodPill
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProductSheet(
    products: List<Product>,
    categories: List<Category> = emptyList(),
    onProductSelected: (Product, Int, Double) -> Unit,
    onAddNewProduct: (Product) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var quantity by remember { mutableIntStateOf(1) }
    var discountText by remember { mutableStateOf("") }
    var showAddProductDialog by remember { mutableStateOf(false) }

    val filteredProducts = remember(searchQuery, selectedCategoryId, products) {
        products.filter { prod ->
            val matchesSearch = searchQuery.isBlank() || 
                prod.name.contains(searchQuery, ignoreCase = true) || 
                (prod.sku?.contains(searchQuery, ignoreCase = true) == true)
            val matchesCategory = selectedCategoryId == null || prod.categoryId == selectedCategoryId
            matchesSearch && matchesCategory
        }.sortedByDescending { it.id }
    }

    var showBarcodeScanner by remember { mutableStateOf(false) }

    if (showBarcodeScanner) {
        com.matoshree.shopmanager.ui.components.BarcodeScannerDialog(
            title = "बारकोड स्कॅनर",
            subtitle = "",
            isContinuousMode = true,
            onBarcodeScanned = { code ->
                val match = products.find { it.sku.equals(code, ignoreCase = true) || it.name.contains(code, ignoreCase = true) }
                if (match != null) {
                    onProductSelected(match, 1, 0.0)
                } else {
                    searchQuery = code
                }
            },
            onDismiss = { showBarcodeScanner = false }
        )
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = WarmWhite,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Gold top accent
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(ChampagneGoldContainer)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "बिलात जोडा",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = PlayfairFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = DeepEmerald
                    )
                )

                // Add New Product Button (Part 3)
                TextButton(
                    onClick = { showAddProductDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = "नवीन प्रॉडक्ट ॲड करा", tint = DeepEmerald)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("+ नवीन प्रॉडक्ट", color = DeepEmerald, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (selectedProduct == null) {
                // Search Field & Dedicated Scanner Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("नाव किंवा बारकोड सर्च करा…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "सर्च", tint = DeepEmerald) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "साफ करा")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { showBarcodeScanner = true },
                        modifier = Modifier
                            .size(52.dp)
                            .background(DeepEmeraldContainer, RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = "बारकोड स्कॅन करा", tint = WarmWhite)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Category Filter Chips (Part 4)
                if (categories.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategoryId == null,
                            onClick = { selectedCategoryId = null },
                            label = { Text("सर्व") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepEmeraldContainer,
                                selectedLabelColor = WarmWhite
                            )
                        )
                        categories.forEach { cat ->
                            FilterChip(
                                selected = selectedCategoryId == cat.id,
                                onClick = {
                                    selectedCategoryId = if (selectedCategoryId == cat.id) null else cat.id
                                },
                                label = { Text(cat.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = DeepEmeraldContainer,
                                    selectedLabelColor = WarmWhite
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Product List
                if (filteredProducts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "कोणतेही प्रॉडक्ट सापडले नाही",
                                style = MaterialTheme.typography.bodyLarge.copy(color = MutedCharcoal)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showAddProductDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("+ '${searchQuery.ifBlank { "नवीन प्रॉडक्ट" }}' ॲड करा")
                            }
                        }
                    }
                } else {
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(prod.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "SKU: ${prod.sku ?: "N/A"}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                                    )
                                }
                                Text(
                                    text = CurrencyFormatter.format(prod.sellingPrice),
                                    fontWeight = FontWeight.Bold,
                                    color = DeepEmerald
                                )
                            }
                        }
                    }
                }
            } else {
                // Selected Product Details & Quantity Adjuster
                val prod = selectedProduct!!
                MatoshreeCard(hasGoldTopBorder = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(prod.name, style = MaterialTheme.typography.titleLarge.copy(color = DeepEmerald, fontWeight = FontWeight.Bold))
                            Text("SKU: ${prod.sku ?: "N/A"}", style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                        }
                        IconButton(onClick = { selectedProduct = null }) {
                            Icon(Icons.Default.Close, contentDescription = "दुसरे निवडा")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = CurrencyFormatter.format(prod.sellingPrice),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Quantity Stepper
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("नग", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .border(1.dp, OutlineGrey.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        ) {
                            IconButton(onClick = { if (quantity > 1) quantity-- }) {
                                Icon(Icons.Default.Remove, contentDescription = "कमी करा")
                            }
                            Text(
                                text = "$quantity",
                                modifier = Modifier.padding(horizontal = 14.dp),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            IconButton(onClick = { quantity++ }) {
                                Icon(Icons.Default.Add, contentDescription = "वाढवा")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Discount Field
                    OutlinedTextField(
                        value = discountText,
                        onValueChange = { discountText = it },
                        label = { Text("प्रॉडक्ट डिस्काउंट (₹)") },
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
                        Text("कार्टमध्ये जोडा", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    // Add Product Dialog (with Auto-Draft Persistence)
    if (showAddProductDialog) {
        val draft = remember { com.matoshree.shopmanager.utils.ProductDraftManager.loadSaleDraft(context) }
        var newName by remember { mutableStateOf(if (draft.name.isNotBlank()) draft.name else searchQuery) }
        var newPriceText by remember { mutableStateOf(draft.priceText) }
        var newCostText by remember { mutableStateOf(draft.costText) }
        var newSku by remember { mutableStateOf(draft.sku) }
        var trackStock by remember { mutableStateOf(draft.trackStock) }

        // Automatically preserve all entered draft data whenever fields change
        LaunchedEffect(newName, newPriceText, newCostText, newSku, trackStock) {
            com.matoshree.shopmanager.utils.ProductDraftManager.saveSaleDraft(
                context = context,
                name = newName,
                priceText = newPriceText,
                costText = newCostText,
                sku = newSku,
                trackStock = trackStock
            )
        }

        AlertDialog(
            onDismissRequest = { showAddProductDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "नवीन प्रॉडक्ट ॲड करा",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = PlayfairFontFamily,
                            color = DeepEmerald,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    if (newName.isNotBlank() || newPriceText.isNotBlank()) {
                        TextButton(
                            onClick = {
                                com.matoshree.shopmanager.utils.ProductDraftManager.clearSaleDraft(context)
                                newName = searchQuery
                                newPriceText = ""
                                newCostText = ""
                                newSku = ""
                                trackStock = false
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("साफ करा", fontSize = 11.sp, color = MutedCharcoal)
                        }
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (draft.hasContent) {
                        Surface(
                            color = ChampagneGoldContainer.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "📝 मागील न सेव्ह केलेला तपशील रिस्टोअर झाला आहे.",
                                fontSize = 11.sp,
                                color = DeepEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("प्रॉडक्टचे नाव *") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = newPriceText,
                        onValueChange = { newPriceText = it },
                        label = { Text("विक्री किंमत (₹) *") },
                        placeholder = { Text("उदा. 2450.00") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = newCostText,
                        onValueChange = { newCostText = it },
                        label = { Text("खरेदी किंमत (₹, पर्यायी)") },
                        placeholder = { Text("खरेदी दर") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = newSku,
                        onValueChange = { newSku = it },
                        label = { Text("बारकोड / एसकेयू (पर्यायी)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = trackStock, onCheckedChange = { trackStock = it })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("स्टॉक ट्रॅक करा", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val price = newPriceText.toDoubleOrNull() ?: 0.0
                        val cost = newCostText.toDoubleOrNull()
                        if (newName.isNotBlank() && price > 0) {
                            val newProduct = Product(
                                shopId = 1,
                                categoryId = selectedCategoryId,
                                name = newName.trim(),
                                sku = newSku.trim().ifBlank { null },
                                sellingPrice = price,
                                costPrice = cost,
                                trackInventory = trackStock
                            )
                            onAddNewProduct(newProduct)
                            com.matoshree.shopmanager.utils.ProductDraftManager.clearSaleDraft(context)
                            showAddProductDialog = false
                        }
                    },
                    enabled = newName.isNotBlank() && (newPriceText.toDoubleOrNull() ?: 0.0) > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("सेव्ह करा व बिलात जोडा", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddProductDialog = false }) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
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
    val discountType by viewModel.discountType.collectAsState()
    val discountValue by viewModel.discountValue.collectAsState()
    val discountAmount by viewModel.discountAmount.collectAsState()

    val subtotal = remember(cartItems) { cartItems.sumOf { it.sellingPrice * it.quantity } }
    val finalTotal = remember(subtotal, discountAmount) { (subtotal - discountAmount).coerceAtLeast(0.0) }

    val paymentStatus by viewModel.paymentStatus.collectAsState()
    val partialPaidText by viewModel.partialPaidAmountText.collectAsState()
    val selectedDueDate by viewModel.selectedDueDate.collectAsState()

    var discountInputText by remember { mutableStateOf(if (discountValue > 0) discountValue.toString() else "") }
    var discountError by remember { mutableStateOf<String?>(null) }
    var partialPaidError by remember { mutableStateOf<String?>(null) }
    var showCreditConfirmDialog by remember { mutableStateOf(false) }
    var isCompleting by remember { mutableStateOf(false) }

    LaunchedEffect(isCompleting) {
        if (isCompleting) {
            kotlinx.coroutines.delay(8000L)
            isCompleting = false
        }
    }

    val actualPaidAmount = when (paymentStatus) {
        PaymentStatus.PAID -> finalTotal
        PaymentStatus.PARTIAL -> partialPaidText.toDoubleOrNull() ?: 0.0
        PaymentStatus.PENDING, PaymentStatus.UNPAID -> 0.0
        else -> finalTotal
    }
    val actualPendingAmount = (finalTotal - actualPaidAmount).coerceAtLeast(0.0)

    val isCreditSale = paymentStatus != PaymentStatus.PAID
    val hasValidCustomer = selectedCustomer != null && selectedCustomer!!.id > 0
    val canProceed = !isCompleting && cartItems.isNotEmpty() && finalTotal >= 0 &&
            (!isCreditSale || (hasValidCustomer && (paymentStatus != PaymentStatus.PARTIAL || (actualPaidAmount > 0 && actualPaidAmount < finalTotal))))

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "विक्री सारांश",
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
                        onClick = {
                            if (!isCompleting) {
                                if (isCreditSale) {
                                    showCreditConfirmDialog = true
                                } else {
                                    isCompleting = true
                                    onCompleteSale()
                                }
                            }
                        },
                        enabled = canProceed,
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        if (isCompleting) {
                            CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = if (isCreditSale) "उधारी बिल तपासा व कन्फर्म करा" else "बिल पूर्ण करा व प्रिंट करा",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
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
                        Text("कस्टमरची माहिती", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                        Text(selectedCustomer!!.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedCustomer!!.mobile, style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("📍 ${selectedCustomer!!.address ?: "कोंढारकी"}", style = MaterialTheme.typography.bodySmall.copy(color = DeepEmerald, fontWeight = FontWeight.SemiBold))
                        }
                        if (selectedCustomer!!.outstandingBalance > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "सध्याची उधारी बाकी: ${CurrencyFormatter.format(selectedCustomer!!.outstandingBalance)}",
                                style = MaterialTheme.typography.labelMedium.copy(color = BoutiqueError, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            } else if (isCreditSale) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = BoutiqueError.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BoutiqueError.copy(alpha = 0.5f))
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = "आवश्यक", tint = BoutiqueError)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "उधारी बिलासाठी कस्टमर निवडणे आवश्यक आहे. कृपया मागे जाऊन कस्टमर निवडा.",
                                style = MaterialTheme.typography.bodyMedium.copy(color = BoutiqueError, fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }

            // Itemized list
            item {
                MatoshreeCard(hasGoldTopBorder = true) {
                    Text("प्रॉडक्ट तपशील", style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))

                    cartItems.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.productName, fontWeight = FontWeight.SemiBold)
                                Text("नग: ${item.quantity} × ${CurrencyFormatter.format(item.sellingPrice)}", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            }
                            Text(CurrencyFormatter.format(item.lineTotal), fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = OutlineVariantGrey.copy(alpha = 0.2f))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("उपएकूण", style = MaterialTheme.typography.bodyLarge)
                        Text(CurrencyFormatter.format(subtotal), fontWeight = FontWeight.SemiBold)
                    }
                    if (discountAmount > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val label = if (discountType == DiscountType.PERCENTAGE) "डिस्काउंट (${discountValue.toInt()}%)" else "डिस्काउंट"
                            Text(label, color = BoutiqueSuccess)
                            Text("-${CurrencyFormatter.format(discountAmount)}", color = BoutiqueSuccess, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = ChampagneGoldContainer)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("एकूण देय रक्कम", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                            Text(
                                text = CurrencyFormatter.format(finalTotal),
                                style = MaterialTheme.typography.headlineLarge.copy(color = DeepEmerald, fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    // Customer Savings Message (Parts 3 & 4)
                    if (discountAmount > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = DeepEmeraldContainer.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "बचत", tint = DeepEmerald, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "आपण मातोश्री कलेक्शनमधून ${CurrencyFormatter.format(discountAmount)} ची बचत केली",
                                    style = MaterialTheme.typography.labelMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }

            // Checkout Discount Card (Parts 3, 4, 5)
            item {
                MatoshreeCard {
                    Text("बिल डिस्काउंट द्या", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Discount Type Selector (None, Amount, Percentage)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = discountType == DiscountType.NONE,
                            onClick = {
                                discountError = null
                                discountInputText = ""
                                viewModel.setDiscount(DiscountType.NONE, 0.0)
                            },
                            label = { Text("नाही") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepEmeraldContainer,
                                selectedLabelColor = WarmWhite
                            )
                        )
                        FilterChip(
                            selected = discountType == DiscountType.AMOUNT,
                            onClick = {
                                discountError = null
                                val v = discountInputText.toDoubleOrNull() ?: 0.0
                                viewModel.setDiscount(DiscountType.AMOUNT, v)
                            },
                            label = { Text("रक्कम (₹)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepEmeraldContainer,
                                selectedLabelColor = WarmWhite
                            )
                        )
                        FilterChip(
                            selected = discountType == DiscountType.PERCENTAGE,
                            onClick = {
                                discountError = null
                                val v = discountInputText.toDoubleOrNull() ?: 0.0
                                viewModel.setDiscount(DiscountType.PERCENTAGE, v)
                            },
                            label = { Text("टक्केवारी (%)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepEmeraldContainer,
                                selectedLabelColor = WarmWhite
                            )
                        )
                    }

                    if (discountType != DiscountType.NONE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = discountInputText,
                            onValueChange = { input ->
                                discountInputText = input
                                val value = input.toDoubleOrNull()
                                if (value == null) {
                                    if (input.isBlank()) {
                                        discountError = null
                                        viewModel.setDiscount(discountType, 0.0)
                                    } else {
                                        discountError = "कृपया योग्य आकडा टाका"
                                    }
                                } else if (value < 0) {
                                    discountError = "डिस्काउंट उणे असू शकत नाही"
                                } else if (discountType == DiscountType.PERCENTAGE && value > 100) {
                                    discountError = "टक्केवारी १००% पेक्षा जास्त असू शकत नाही"
                                } else if (discountType == DiscountType.AMOUNT && value > subtotal) {
                                    discountError = "डिस्काउंट एकूण रकमेपेक्षा जास्त असू शकत नाही (${CurrencyFormatter.format(subtotal)})"
                                } else {
                                    discountError = null
                                    viewModel.setDiscount(discountType, value)
                                }
                            },
                            label = {
                                Text(if (discountType == DiscountType.PERCENTAGE) "डिस्काउंट टक्केवारी (%)" else "डिस्काउंट रक्कम (₹)")
                            },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            isError = discountError != null,
                            supportingText = {
                                if (discountError != null) {
                                    Text(discountError!!, color = BoutiqueError)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }

            // PAYMENT STATUS CARD (Paid Full, Partial, Pending/Udhari)
            item {
                MatoshreeCard(hasGoldTopBorder = isCreditSale) {
                    Text("पेमेंट स्थिती", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald))
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = paymentStatus == PaymentStatus.PAID,
                            onClick = {
                                viewModel.paymentStatus.value = PaymentStatus.PAID
                                partialPaidError = null
                            },
                            label = { Text("पूर्ण भरणा") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepEmeraldContainer,
                                selectedLabelColor = WarmWhite
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = paymentStatus == PaymentStatus.PARTIAL,
                            onClick = {
                                viewModel.paymentStatus.value = PaymentStatus.PARTIAL
                                partialPaidError = null
                            },
                            label = { Text("काही रक्कम जमा") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepEmeraldContainer,
                                selectedLabelColor = WarmWhite
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = paymentStatus == PaymentStatus.PENDING,
                            onClick = {
                                viewModel.paymentStatus.value = PaymentStatus.PENDING
                                partialPaidError = null
                            },
                            label = { Text("उधारी") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = DeepEmeraldContainer,
                                selectedLabelColor = WarmWhite
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Partial Payment Input Field
                    if (paymentStatus == PaymentStatus.PARTIAL) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Quick Percentage Presets (25%, 50%, 75%)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(0.25 to "२५%", 0.50 to "५०%", 0.75 to "७५%").forEach { (pct, label) ->
                                val calcAmt = (Math.round((finalTotal * pct) / 10.0) * 10).toInt()
                                SuggestionChip(
                                    onClick = {
                                        viewModel.partialPaidAmountText.value = calcAmt.toString()
                                        partialPaidError = null
                                    },
                                    label = { Text("$label (₹$calcAmt)", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = partialPaidText,
                            onValueChange = { input ->
                                viewModel.partialPaidAmountText.value = input
                                val paid = input.toDoubleOrNull()
                                if (paid == null) {
                                    partialPaidError = "आता जमा केलेली रक्कम टाका"
                                } else if (paid <= 0) {
                                    partialPaidError = "रक्कम ₹० पेक्षा जास्त असावी"
                                } else if (paid >= finalTotal) {
                                    partialPaidError = "रक्कम एकूण बिलाइतकी किंवा जास्त आहे. 'पूर्ण भरणा' निवडा."
                                } else {
                                    partialPaidError = null
                                }
                            },
                            label = { Text("आता जमा केलेली रक्कम (₹)") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            isError = partialPaidError != null,
                            supportingText = {
                                if (partialPaidError != null) {
                                    Text(partialPaidError!!, color = BoutiqueError)
                                } else {
                                    Text("शिल्लक उधारी बाकी: ${CurrencyFormatter.format(actualPendingAmount)}", color = DeepEmerald, fontWeight = FontWeight.SemiBold)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )
                    } else if (paymentStatus == PaymentStatus.PENDING) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = ChampagneGoldContainer.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "संपूर्ण बिलाची रक्कम (${CurrencyFormatter.format(finalTotal)}) उधारी म्हणून नोंदवली जाईल.",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium.copy(color = DeepCharcoal)
                            )
                        }
                    }

                    // Due Date Selection for Credit Bills
                    if (isCreditSale) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("मुदत तारीख", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Spacer(modifier = Modifier.height(8.dp))

                        val todayIso = DateUtils.todayDate()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "मुदत नाही" to null,
                                "७ दिवस" to DateUtils.addDaysToDate(todayIso, 7),
                                "१५ दिवस" to DateUtils.addDaysToDate(todayIso, 15),
                                "३० दिवस" to DateUtils.addDaysToDate(todayIso, 30)
                            ).forEach { (label, dateVal) ->
                                FilterChip(
                                    selected = selectedDueDate == dateVal,
                                    onClick = { viewModel.selectedDueDate.value = dateVal },
                                    label = { Text(label, fontSize = 12.sp, maxLines = 1) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = DeepEmeraldContainer,
                                        selectedLabelColor = WarmWhite
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        com.matoshree.shopmanager.ui.components.MatoshreeDatePickerField(
                            value = selectedDueDate ?: "",
                            onValueChange = { viewModel.selectedDueDate.value = it.ifBlank { null } },
                            label = "मुदत तारीख निवडा",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Payment Mode Selector (CASH, UPI) for the amount paid now
            if (actualPaidAmount > 0) {
                item {
                    MatoshreeCard {
                        Text(
                            text = if (isCreditSale) "जमा केलेल्या रकमेची पेमेंट पद्धत (${CurrencyFormatter.format(actualPaidAmount)})" else "पेमेंट पद्धत",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(PaymentMethod.CASH, PaymentMethod.UPI, PaymentMethod.OTHER).forEach { method ->
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

    // Udhari Confirmation Dialog
    if (showCreditConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCreditConfirmDialog = false },
            title = {
                Text("उधारी बिल निश्चित करा", fontWeight = FontWeight.Bold, color = DeepEmerald)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("कस्टमर: ${selectedCustomer?.name ?: "उपलब्ध नाही"}", fontWeight = FontWeight.SemiBold)
                    Text("मोबाईल: ${selectedCustomer?.mobile ?: "उपलब्ध नाही"}")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("एकूण बिल:")
                        Text(CurrencyFormatter.format(finalTotal), fontWeight = FontWeight.Bold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("आता जमा (${selectedMethod.name}):")
                        Text(CurrencyFormatter.format(actualPaidAmount), color = BoutiqueSuccess, fontWeight = FontWeight.Bold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("बाकी उधारी:")
                        Text(CurrencyFormatter.format(actualPendingAmount), color = BoutiqueError, fontWeight = FontWeight.Bold)
                    }
                    if (selectedDueDate != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("मुदत तारीख:")
                            Text(selectedDueDate!!, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCreditConfirmDialog = false
                        isCompleting = true
                        onCompleteSale()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("होय, बिल तयार करा", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreditConfirmDialog = false }) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }
}

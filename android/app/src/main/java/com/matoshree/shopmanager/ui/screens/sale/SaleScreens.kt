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

    // Customer selection & search
    val searchQuery = MutableStateFlow("")
    val customerSuggestions = MutableStateFlow<List<Customer>>(emptyList())
    val selectedCustomer = MutableStateFlow<Customer?>(null)

    // Detailed Sale Cart
    val cartItems = MutableStateFlow<List<BillItem>>(emptyList())
    val discountType = MutableStateFlow(DiscountType.NONE)
    val discountValue = MutableStateFlow(0.0)
    val discountAmount = MutableStateFlow(0.0)

    // Quick Sale state
    val quickSaleAmountText = MutableStateFlow("0")
    val selectedPaymentMethod = MutableStateFlow(PaymentMethod.CASH)
    val saleNote = MutableStateFlow("")

    // Udhari / Credit state
    val paymentStatus = MutableStateFlow(PaymentStatus.PAID) // PAID, PARTIAL, PENDING
    val partialPaidAmountText = MutableStateFlow("")
    val selectedDueDate = MutableStateFlow<String?>(null) // YYYY-MM-DD

    // WhatsApp Send & Template selection for current sale
    val sendWhatsAppOnComplete = MutableStateFlow(true)
    val selectedWhatsAppTemplateType = MutableStateFlow("AUTO") // "AUTO", "BILL", "REMINDER", "CUSTOM"

    val subtotal: Double
        get() = cartItems.value.sumOf { it.sellingPrice * it.quantity }

    val finalTotal: Double
        get() = (subtotal - discountAmount.value).coerceAtLeast(0.0)

    val actualPaidAmount: Double
        get() {
            val total = finalTotal
            return when (paymentStatus.value) {
                PaymentStatus.PAID -> total
                PaymentStatus.PARTIAL -> {
                    val entered = partialPaidAmountText.value.toDoubleOrNull() ?: 0.0
                    entered.coerceIn(0.0, total)
                }
                PaymentStatus.PENDING, PaymentStatus.UNPAID -> 0.0
                else -> total
            }
        }

    val actualPendingAmount: Double
        get() {
            val total = finalTotal
            val paid = actualPaidAmount
            return when (paymentStatus.value) {
                PaymentStatus.PAID -> 0.0
                PaymentStatus.PARTIAL -> {
                    (total - paid).coerceAtLeast(0.0)
                }
                PaymentStatus.PENDING, PaymentStatus.UNPAID -> total
                else -> 0.0
            }
        }

    fun setDiscount(type: DiscountType, value: Double) {
        discountType.value = type
        val safeVal = value.coerceAtLeast(0.0)
        discountValue.value = safeVal
        recalculateDiscount()
    }

    private fun recalculateDiscount() {
        val type = discountType.value
        val curSub = cartItems.value.sumOf { it.sellingPrice * it.quantity }
        val calc = when (type) {
            DiscountType.NONE -> 0.0
            DiscountType.AMOUNT -> discountValue.value.coerceAtMost(curSub)
            DiscountType.PERCENTAGE -> {
                val pct = discountValue.value.coerceAtMost(100.0)
                (curSub * pct / 100.0)
            }
        }
        discountAmount.value = Math.round(calc * 100.0) / 100.0
    }

    fun onSearchCustomer(query: String) {
        searchQuery.value = query
        viewModelScope.launch {
            if (query.isNotBlank()) {
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

    suspend fun createAndSelectCustomer(
        name: String,
        mobile: String,
        village: String,
        email: String? = null,
        notes: String? = null
    ): Result<Customer> {
        val cleanName = name.trim()
        val cleanMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile)
        val cleanVillage = village.trim().ifBlank { "कोंढारकी" }

        if (cleanName.isBlank()) return Result.failure(IllegalArgumentException("कृपया कस्टमरचे नाव टाका"))
        if (cleanMobile.isBlank()) return Result.failure(IllegalArgumentException("कृपया मोबाईल नंबर टाका"))
        if (cleanMobile.length != 10) return Result.failure(IllegalArgumentException("कृपया १० अंकी मोबाईल नंबर टाका"))
        if (cleanVillage.isBlank()) return Result.failure(IllegalArgumentException("कृपया गाव / पत्ता टाका"))

        // Check if customer with this mobile exists
        val existing = customerRepository.findCustomerByMobile(cleanMobile)
        if (existing != null) {
            selectCustomer(existing)
            return Result.success(existing)
        }

        val newCustomer = Customer(
            shopId = 1,
            name = cleanName,
            mobile = cleanMobile,
            email = email?.trim()?.ifBlank { null },
            address = cleanVillage,
            notes = notes?.trim()?.ifBlank { null }
        )
        val generatedId = customerRepository.saveCustomer(newCustomer)
        val savedCustomer = newCustomer.copy(id = generatedId)
        selectCustomer(savedCustomer)
        return Result.success(savedCustomer)
    }

    fun addItemToCart(product: Product, quantity: Int, discount: Double = 0.0) {
        val current = cartItems.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.productId == product.id && it.productId != 0L }

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
            val existing = current[existingIndex]
            val updatedQty = existing.quantity + quantity
            val totalItemDiscount = existing.discountAmount + discount
            val updatedTotal = ((product.sellingPrice * updatedQty) - totalItemDiscount).coerceAtLeast(0.0)
            val updatedCost = (product.costPrice ?: (product.sellingPrice * 0.75)) * updatedQty
            current[existingIndex] = existing.copy(
                quantity = updatedQty,
                discountAmount = totalItemDiscount,
                lineTotal = updatedTotal,
                lineCost = updatedCost,
                lineProfit = updatedTotal - updatedCost
            )
        } else {
            current.add(newItem)
        }
        cartItems.value = current
        recalculateDiscount()
    }

    fun addDirectCustomItem(amount: Double, name: String? = null, quantity: Int = 1) {
        if (amount <= 0.0) return
        val current = cartItems.value.toMutableList()
        val safeQty = quantity.coerceAtLeast(1)
        val finalName = name?.trim()?.ifBlank { "General Item / साडी" } ?: "General Item / साडी"
        val lineTotal = amount * safeQty
        val lineCost = lineTotal * 0.75
        val newItem = BillItem(
            productId = 0L,
            productName = finalName,
            sku = null,
            categoryId = null,
            quantity = safeQty,
            sellingPrice = amount,
            costPrice = amount * 0.75,
            discountAmount = 0.0,
            lineTotal = lineTotal,
            lineCost = lineCost,
            lineProfit = lineTotal - lineCost
        )
        current.add(newItem)
        cartItems.value = current
        recalculateDiscount()
    }

    fun removeItemFromCart(index: Int) {
        val current = cartItems.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            cartItems.value = current
            recalculateDiscount()
        }
    }

    fun updateCartItemQuantity(index: Int, newQuantity: Int) {
        if (newQuantity <= 0) {
            removeItemFromCart(index)
            return
        }
        val current = cartItems.value.toMutableList()
        if (index in current.indices) {
            val item = current[index]
            val lineTotal = ((item.sellingPrice * newQuantity) - item.discountAmount).coerceAtLeast(0.0)
            val lineCost = (item.costPrice ?: (item.sellingPrice * 0.75)) * newQuantity
            current[index] = item.copy(
                quantity = newQuantity,
                lineTotal = lineTotal,
                lineCost = lineCost,
                lineProfit = lineTotal - lineCost
            )
            cartItems.value = current
            recalculateDiscount()
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
        val pStatus = paymentStatus.value
        val total = if (saleType == SaleType.QUICK) quickSaleAmountText.value.toDoubleOrNull() ?: 0.0 else finalTotal
        val partialPaid = when (pStatus) {
            PaymentStatus.PAID -> total
            PaymentStatus.PARTIAL -> partialPaidAmountText.value.toDoubleOrNull() ?: 0.0
            PaymentStatus.PENDING, PaymentStatus.UNPAID -> 0.0
            else -> total
        }
        val quickAmt = if (saleType == SaleType.QUICK) quickSaleAmountText.value.toDoubleOrNull() ?: 0.0 else null

        return billRepository.createSale(
            saleType = saleType,
            customer = selectedCustomer.value,
            items = cartItems.value,
            discountType = discountType.value,
            discountValue = discountValue.value,
            discountAmount = discountAmount.value,
            quickSaleAmount = quickAmt,
            paymentMethod = selectedPaymentMethod.value,
            note = saleNote.value.ifBlank { null },
            paymentStatus = pStatus,
            paidAmount = partialPaid,
            dueDate = selectedDueDate.value
        )
    }

    fun reset() {
        selectedCustomer.value = null
        searchQuery.value = ""
        customerSuggestions.value = emptyList()
        cartItems.value = emptyList()
        discountType.value = DiscountType.NONE
        discountValue.value = 0.0
        discountAmount.value = 0.0
        quickSaleAmountText.value = "0"
        selectedPaymentMethod.value = PaymentMethod.CASH
        saleNote.value = ""
        paymentStatus.value = PaymentStatus.PAID
        partialPaidAmountText.value = ""
        selectedDueDate.value = null
        sendWhatsAppOnComplete.value = true
        selectedWhatsAppTemplateType.value = "AUTO"
    }
}

@Composable
fun NewSaleScreen(
    viewModel: SaleViewModel,
    products: List<Product> = emptyList(),
    onAddProductClick: () -> Unit,
    onCheckoutClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val suggestions by viewModel.customerSuggestions.collectAsState()
    val selectedCustomer by viewModel.selectedCustomer.collectAsState()
    val cartItems by viewModel.cartItems.collectAsState()

    var showAddCustomerDialog by remember { mutableStateOf(false) }
    var showContinuousBarcodeScanner by remember { mutableStateOf(false) }
    var showDirectAmountDialog by remember { mutableStateOf(false) }

    if (showContinuousBarcodeScanner) {
        val totalQty = cartItems.sumOf { it.quantity }
        com.matoshree.shopmanager.ui.components.BarcodeScannerDialog(
            title = "बारकोड स्कॅनर",
            subtitle = "",
            isContinuousMode = true,
            continuousBadgeText = "$totalQty नग (₹${CurrencyFormatter.format(viewModel.finalTotal)})",
            onBarcodeScanned = { code ->
                val match = products.find { it.sku.equals(code, ignoreCase = true) || it.name.contains(code, ignoreCase = true) }
                if (match != null) {
                    viewModel.addItemToCart(match, 1, 0.0)
                }
            },
            onDismiss = { showContinuousBarcodeScanner = false }
        )
    }

    if (showDirectAmountDialog) {
        var directAmtText by remember { mutableStateOf("") }
        var directItemName by remember { mutableStateOf("") }
        var directQtyText by remember { mutableStateOf("1") }
        var directAmtError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showDirectAmountDialog = false },
            title = {
                Text(
                    text = "डायरेक्ट बिल",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = PlayfairFontFamily,
                        color = DeepEmerald,
                        fontWeight = FontWeight.Bold
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = directAmtText,
                        onValueChange = {
                            directAmtText = it
                            directAmtError = null
                        },
                        label = { Text("रक्कम (₹) *") },
                        placeholder = { Text("0.00") },
                        isError = directAmtError != null,
                        supportingText = directAmtError?.let { { Text(it, color = BoutiqueError) } },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = directItemName,
                        onValueChange = { directItemName = it },
                        label = { Text("प्रॉडक्टचे नाव / तपशील") },
                        placeholder = { Text("साडी / कापड") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = directQtyText,
                        onValueChange = { directQtyText = it.filter { c -> c.isDigit() } },
                        label = { Text("नग") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = directAmtText.toDoubleOrNull() ?: 0.0
                        if (amt <= 0.0) {
                            directAmtError = "कृपया योग्य रक्कम टाका"
                        } else {
                            val qty = directQtyText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            viewModel.addDirectCustomItem(amt, directItemName.ifBlank { "कापड / साडी" }, qty)
                            showDirectAmountDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("बिलामध्ये ॲड करा", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDirectAmountDialog = false }) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "नवीन बिल",
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = { showContinuousBarcodeScanner = true }) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = "बारकोड स्कॅन करा", tint = DeepEmerald)
                    }
                }
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
                            text = "एकूण रक्कम",
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
                        Text("बिल पूर्ण करा", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
            // CUSTOMER SECTION (Part 1 & 2)
            item {
                MatoshreeCard(hasGoldTopBorder = selectedCustomer != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "कस्टमर (पर्यायी)",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = MutedCharcoal,
                                letterSpacing = 1.sp
                            )
                        )
                        TextButton(
                            onClick = { showAddCustomerDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = "कस्टमर ॲड करा", tint = DeepEmerald, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("+ कस्टमर ॲड करा", color = DeepEmerald, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (selectedCustomer != null) {
                        val cust = selectedCustomer!!
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = WarmIvory,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, ChampagneGoldContainer)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(DeepEmeraldContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = cust.name.take(2).uppercase(),
                                            color = WarmWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                cust.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                                            Text(cust.mobile, style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                "📍 ${cust.address ?: "Kondharki"}",
                                                style = MaterialTheme.typography.bodySmall.copy(color = DeepEmerald, fontWeight = FontWeight.SemiBold),
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                IconButton(onClick = { viewModel.selectCustomer(null) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove Customer", tint = MutedCharcoal)
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.onSearchCustomer(it) },
                            placeholder = { Text("कस्टमरचे नाव, मोबाईल किंवा गाव सर्च करा…") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "सर्च", tint = DeepEmerald) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = DeepEmerald,
                                unfocusedBorderColor = OutlineGrey.copy(alpha = 0.5f)
                            )
                        )

                        // Autocomplete Suggestions
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
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                cust.name,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(cust.mobile, style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    "📍 ${cust.address ?: "कोंढारकी"}",
                                                    style = MaterialTheme.typography.bodySmall.copy(color = DeepEmerald),
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = OutlineVariantGrey.copy(alpha = 0.2f))
                                }
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
                                    contentDescription = "कार्ट",
                                    tint = DeepEmerald,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "कार्ट रिकामी आहे",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = PlayfairFontFamily,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "बिल सुरू करण्यासाठी कस्टमर निवडा किंवा प्रॉडक्ट ॲड करा.",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MutedCharcoal,
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = onAddProductClick,
                                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "ॲड करा")
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("प्रॉडक्ट ॲड करा", fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { showDirectAmountDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(48.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DeepEmerald)
                                ) {
                                    Icon(Icons.Default.AttachMoney, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("डायरेक्ट रक्कम", fontWeight = FontWeight.Bold)
                                }
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
                        Text(
                            text = "निवडलेले प्रॉडक्ट्स (${cartItems.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { showDirectAmountDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(Icons.Default.AttachMoney, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("डायरेक्ट रक्कम", color = DeepEmerald, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            Button(
                                onClick = onAddProductClick,
                                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "प्रॉडक्ट जोडा", tint = WarmWhite, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("+ प्रॉडक्ट जोडा", color = WarmWhite, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                items(cartItems.indices.toList()) { index ->
                    val item = cartItems[index]
                    MatoshreeCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.productName,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "SKU: ${item.sku ?: "N/A"} • ${CurrencyFormatter.format(item.sellingPrice)} प्रति नग",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                // Quantity adjustment stepper
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .border(1.dp, OutlineGrey.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 4.dp)
                                ) {
                                    IconButton(
                                        onClick = { viewModel.updateCartItemQuantity(index, item.quantity - 1) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = "कमी करा", modifier = Modifier.size(16.dp))
                                    }
                                    Text(
                                        text = "${item.quantity}",
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    IconButton(
                                        onClick = { viewModel.updateCartItemQuantity(index, item.quantity + 1) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "वाढवा", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = CurrencyFormatter.format(item.lineTotal),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = DeepEmerald
                                    )
                                )
                                IconButton(onClick = { viewModel.removeItemFromCart(index) }) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "काढून टाका", tint = Color.Red.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Customer Dialog (Part 2)
    if (showAddCustomerDialog) {
        var newName by remember { mutableStateOf("") }
        var newMobile by remember { mutableStateOf("") }
        var newVillage by remember { mutableStateOf("Kondharki") }
        var newEmail by remember { mutableStateOf("") }

        var nameError by remember { mutableStateOf<String?>(null) }
        var mobileError by remember { mutableStateOf<String?>(null) }
        var villageError by remember { mutableStateOf<String?>(null) }
        var generalError by remember { mutableStateOf<String?>(null) }
        var isSaving by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = { showAddCustomerDialog = false },
            title = {
                Text(
                    text = "नवीन कस्टमर ॲड करा",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = PlayfairFontFamily,
                        color = DeepEmerald,
                        fontWeight = FontWeight.Bold
                    )
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (generalError != null) {
                        Surface(
                            color = BoutiqueError.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = generalError!!,
                                color = BoutiqueError,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = it
                            nameError = if (it.isBlank()) "कृपया कस्टमरचे नाव टाका" else null
                            generalError = null
                        },
                        label = { Text("कस्टमरचे नाव *") },
                        isError = nameError != null,
                        supportingText = { nameError?.let { Text(it, color = BoutiqueError) } },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = newMobile,
                        onValueChange = { input ->
                            val digitsOnly = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(input)
                            newMobile = digitsOnly
                            mobileError = when {
                                digitsOnly.isBlank() -> "कृपया मोबाईल नंबर टाका"
                                digitsOnly.length < 10 -> "कृपया १० अंकी मोबाईल नंबर टाका"
                                else -> null
                            }
                            generalError = null
                        },
                        label = { Text("मोबाईल नंबर (१० अंक) *") },
                        placeholder = { Text("१० अंकी मोबाईल नंबर टाका") },
                        singleLine = true,
                        isError = mobileError != null,
                        supportingText = { mobileError?.let { Text(it, color = BoutiqueError) } },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = newVillage,
                        onValueChange = {
                            newVillage = it
                            villageError = if (it.isBlank()) "कृपया गाव / पत्ता टाका" else null
                            generalError = null
                        },
                        label = { Text("गाव / पत्ता *") },
                        placeholder = { Text("कोंढारकी") },
                        isError = villageError != null,
                        supportingText = { villageError?.let { Text(it, color = BoutiqueError) } },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = newEmail,
                        onValueChange = { newEmail = it },
                        label = { Text("ईमेल (पर्यायी)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        var valid = true
                        if (newName.isBlank()) {
                            nameError = "कृपया कस्टमरचे नाव टाका"
                            valid = false
                        }
                        if (newMobile.isBlank()) {
                            mobileError = "कृपया मोबाईल नंबर टाका"
                            valid = false
                        } else if (newMobile.length != 10) {
                            mobileError = "कृपया १० अंकी मोबाईल नंबर टाका"
                            valid = false
                        }
                        if (newVillage.isBlank()) {
                            villageError = "कृपया गाव / पत्ता टाका"
                            valid = false
                        }

                        if (valid) {
                            isSaving = true
                            coroutineScope.launch {
                                try {
                                    val result = viewModel.createAndSelectCustomer(
                                        name = newName,
                                        mobile = newMobile,
                                        village = newVillage,
                                        email = newEmail.ifBlank { null }
                                    )
                                    result.fold(
                                        onSuccess = {
                                            showAddCustomerDialog = false
                                        },
                                        onFailure = { error ->
                                            generalError = error.message ?: "कस्टमर सेव्ह करताना त्रुटी आली"
                                        }
                                    )
                                } catch (e: Exception) {
                                    generalError = e.message ?: "कस्टमर सेव्ह करताना त्रुटी आली"
                                } finally {
                                    isSaving = false
                                }
                            }
                        }
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("सेव्ह करा व निवडा", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomerDialog = false }) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
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
                title = "जलद विक्री",
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
                            text = "बिल सेव्ह करा • ₹$amountText",
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
                text = "अंतिम रक्कम टाका",
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

package com.matoshree.shopmanager.ui.screens.inventory

import android.content.Context
import android.graphics.*
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.matoshree.shopmanager.data.repository.ProductRepository
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.printer.ThermalPrinterManager
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PrintShareHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

enum class InventoryFilter(val title: String) {
    ALL("सर्व प्रॉडक्ट्स"),
    LOW_STOCK("कमी शिल्लक (≤५)"),
    OUT_OF_STOCK("संपलेला माल (०)"),
    FAST_MOVING("जास्त खपाचा माल 🔥"),
    SLOW_MOVING("पडून राहिलेला माल 💤")
}

class InventoryViewModel(private val productRepository: ProductRepository) : ViewModel() {
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories

    private val _movements = MutableStateFlow<List<StockMovement>>(emptyList())
    val movements: StateFlow<List<StockMovement>> = _movements

    val searchQuery = MutableStateFlow("")
    val selectedFilter = MutableStateFlow(InventoryFilter.ALL)
    val selectedCategoryId = MutableStateFlow<Long?>(null)
    val selectedTab = MutableStateFlow(0) // 0: Overview & Products, 1: Stock Movements Ledger

    init {
        observeData()
    }

    private fun observeData() {
        viewModelScope.launch {
            productRepository.getAllProducts().collectLatest {
                _products.value = it.filter { p -> p.isActive }
            }
        }
        viewModelScope.launch {
            productRepository.getAllCategories().collectLatest {
                _categories.value = it.filter { c -> c.isActive }
            }
        }
        viewModelScope.launch {
            productRepository.getStockMovements().collectLatest {
                _movements.value = it
            }
        }
    }

    fun adjustStock(
        productId: Long,
        delta: Int,
        newExactStock: Int? = null,
        type: StockChangeType,
        note: String? = null,
        onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val result = productRepository.adjustStock(
                productId = productId,
                delta = delta,
                newExactStock = newExactStock,
                type = type,
                note = note
            )
            onComplete(result.isSuccess)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    viewModel: InventoryViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val products by viewModel.products.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val movements by viewModel.movements.collectAsState()
    val search by viewModel.searchQuery.collectAsState()
    val currentFilter by viewModel.selectedFilter.collectAsState()
    val selectedCatId by viewModel.selectedCategoryId.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()

    var productForStockAdjust by remember { mutableStateOf<Product?>(null) }
    var isPrinting by remember { mutableStateOf(false) }

    // Computed Inventory KPIs
    val trackedProducts = remember(products) { products.filter { it.trackInventory } }
    val totalStockAdded = remember(trackedProducts) { trackedProducts.sumOf { it.effectiveTotalStockAdded } }
    val totalUnitsSold = remember(trackedProducts) { trackedProducts.sumOf { it.totalSold } }
    val totalInStock = remember(trackedProducts) { trackedProducts.sumOf { it.currentStock } }
    val overallSellThrough = remember(totalStockAdded, totalUnitsSold) {
        if (totalStockAdded > 0) (totalUnitsSold.toDouble() / totalStockAdded.toDouble()) * 100.0 else 0.0
    }
    val totalCostValue = remember(products) {
        products.sumOf { (it.costPrice ?: 0.0) * it.currentStock }
    }
    val totalRetailValue = remember(products) {
        products.sumOf { it.sellingPrice * it.currentStock }
    }
    val projectedProfit = remember(totalRetailValue, totalCostValue) {
        (totalRetailValue - totalCostValue).coerceAtLeast(0.0)
    }
    val lowStockList = remember(trackedProducts) { trackedProducts.filter { it.isLowStock } }
    val outOfStockList = remember(trackedProducts) { trackedProducts.filter { it.isOutOfStock } }

    // Filtered Products for List
    val filteredProducts = remember(products, search, currentFilter, selectedCatId) {
        var list = products

        if (search.isNotBlank()) {
            list = list.filter {
                it.name.contains(search, ignoreCase = true) || (it.sku?.contains(search, ignoreCase = true) == true)
            }
        }

        if (selectedCatId != null) {
            list = list.filter { it.categoryId == selectedCatId }
        }

        when (currentFilter) {
            InventoryFilter.ALL -> list.sortedByDescending { it.id }
            InventoryFilter.LOW_STOCK -> list.filter { it.isLowStock }.sortedBy { it.currentStock }
            InventoryFilter.OUT_OF_STOCK -> list.filter { it.isOutOfStock }
            InventoryFilter.FAST_MOVING -> list.filter { it.totalSold > 0 }.sortedByDescending { it.totalSold }
            InventoryFilter.SLOW_MOVING -> list.filter { it.totalSold == 0 || it.sellThroughRate < 20.0 }.sortedBy { it.sellThroughRate }
        }
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "इन्व्हेंटरी व स्टॉक व्यवस्थापन",
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    IconButton(
                        onClick = {
                            if (isPrinting) return@IconButton
                            isPrinting = true
                            coroutineScope.launch {
                                try {
                                    val printerManager = ThermalPrinterManager(context)
                                    val selectedPrinter = printerManager.bluetoothManager.getSelectedPrinter()
                                    if (selectedPrinter == null) {
                                        Toast.makeText(context, "कृपया सेटिंगमध्ये ५८ मिमी प्रिंटर निवडा", Toast.LENGTH_LONG).show()
                                    } else {
                                        val res = printInventorySummaryThermal(context, products, totalStockAdded, totalUnitsSold, totalInStock, totalRetailValue)
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "इन्व्हेंटरी पावती प्रिंट होत आहे...", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "प्रिंट एरर: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "प्रिंट एरर: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isPrinting = false
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Print, contentDescription = "थर्मल प्रिंट", tint = DeepEmerald)
                    }

                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                shareColorInventoryReport(
                                    context = context,
                                    products = products,
                                    totalAdded = totalStockAdded,
                                    totalSold = totalUnitsSold,
                                    totalInStock = totalInStock,
                                    costValue = totalCostValue,
                                    retailValue = totalRetailValue,
                                    sellThrough = overallSellThrough
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "रिपोर्ट शेअर करा", tint = DeepEmerald)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
        ) {
            // Segmented Tabs: 1. Overview & Products, 2. Movement Ledger
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = WarmWhite,
                contentColor = DeepEmerald,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = DeepEmerald,
                        height = 3.dp
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { viewModel.selectedTab.value = 0 },
                    text = {
                        Text(
                            "स्टॉक व प्रॉडक्ट्स (${products.size})",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    },
                    icon = { Icon(Icons.Outlined.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { viewModel.selectedTab.value = 1 },
                    text = {
                        Text(
                            "स्टॉक हालचाल वही (${movements.size})",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    },
                    icon = { Icon(Icons.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            if (selectedTab == 0) {
                // TAB 0: OVERVIEW & PRODUCT STOCK LIST
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 40.dp)
                ) {
                    // 1. KPI Cards Grid
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Row 1: Units In, Sold, Remaining
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Total Stock In
                                MatoshreeCard(modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                                        Text("एकूण आणलेला", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal, fontSize = 10.sp), maxLines = 1)
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text("$totalStockAdded", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = DeepEmerald, maxLines = 1)
                                        Text("एकूण नग", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = MutedCharcoal), maxLines = 1)
                                    }
                                }

                                // Total Sold
                                MatoshreeCard(modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                                        Text("विकलेला (खप)", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal, fontSize = 10.sp), maxLines = 1)
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text("$totalUnitsSold", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = BoutiqueSuccess, maxLines = 1)
                                        Text("खप: ${String.format("%.1f", overallSellThrough)}%", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = BoutiqueSuccess, fontWeight = FontWeight.Bold), maxLines = 1)
                                    }
                                }

                                // In Stock
                                MatoshreeCard(modifier = Modifier.weight(1f)) {
                                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                                        Text("शिल्लक माल", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal, fontSize = 10.sp), maxLines = 1)
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text("$totalInStock", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (totalInStock <= 10) BoutiqueError else DeepEmerald, maxLines = 1)
                                        Text("दुकान शिल्लक", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, color = MutedCharcoal), maxLines = 1)
                                    }
                                }
                            }

                            // Row 2: Valuation Card (Investment vs Retail Value)
                            MatoshreeCard(hasGoldTopBorder = true) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("शिल्लक मालाचे विक्री मूल्य", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                                            Text(CurrencyFormatter.format(totalRetailValue), style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold))
                                        }
                                        if (totalCostValue > 0) {
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text("गुंतवणूक भांडवल (Cost)", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                                                Text(CurrencyFormatter.format(totalCostValue), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = DeepCharcoal))
                                            }
                                        }
                                    }
                                    if (totalCostValue > 0 && projectedProfit > 0) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        HorizontalDivider(color = OutlineVariantGrey.copy(alpha = 0.2f))
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("संभाव्य नफा (Projected Margin):", fontSize = 11.sp, color = MutedCharcoal)
                                            Text("+${CurrencyFormatter.format(projectedProfit)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ChampagneGold)
                                        }
                                    }
                                }
                            }

                            // Health Alerts (Out of Stock)
                            if (outOfStockList.isNotEmpty()) {
                                Surface(
                                    color = BoutiqueError.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, BoutiqueError.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        viewModel.selectedFilter.value = InventoryFilter.OUT_OF_STOCK
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = BoutiqueError, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("${outOfStockList.size} संपलेले प्रॉडक्ट्स (Out of Stock)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BoutiqueError)
                                    }
                                }
                            }
                        }
                    }

                    // 2. Search Field
                    item {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { viewModel.searchQuery.value = it },
                            placeholder = { Text("प्रॉडक्टचे नाव किंवा SKU कोड शोधा...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = DeepEmerald) },
                            trailingIcon = {
                                if (search.isNotBlank()) {
                                    IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "साफ करा")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = WarmWhite,
                                unfocusedContainerColor = WarmWhite
                            )
                        )
                    }

                    // 3. Quick Filter Chips
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            InventoryFilter.values().forEach { filter ->
                                val isSelected = currentFilter == filter
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.selectedFilter.value = filter },
                                    label = { Text(filter.title, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = DeepEmerald,
                                        selectedLabelColor = WarmWhite,
                                        containerColor = WarmWhite
                                    )
                                )
                            }
                        }
                    }

                    // 4. Category Filter Chips
                    if (categories.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = selectedCatId == null,
                                    onClick = { viewModel.selectedCategoryId.value = null },
                                    label = { Text("सर्व कॅटेगरी (${products.size})", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = DeepEmeraldContainer,
                                        selectedLabelColor = WarmWhite,
                                        containerColor = WarmWhite
                                    )
                                )
                                categories.forEach { cat ->
                                    val count = products.count { it.categoryId == cat.id }
                                    FilterChip(
                                        selected = selectedCatId == cat.id,
                                        onClick = {
                                            viewModel.selectedCategoryId.value = if (selectedCatId == cat.id) null else cat.id
                                        },
                                        label = { Text("${cat.name} ($count)", fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = DeepEmeraldContainer,
                                            selectedLabelColor = WarmWhite,
                                            containerColor = WarmWhite
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // 5. Products List
                    if (filteredProducts.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = OutlineVariantGrey, modifier = Modifier.size(54.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("या फिल्टरसाठी कोणतेही प्रॉडक्ट सापडले नाही", color = MutedCharcoal, fontSize = 14.sp)
                                }
                            }
                        }
                    } else {
                        itemsIndexed(filteredProducts, key = { index, product -> "inv_prod_${product.id}_$index" }) { _, product ->
                            val catName = categories.find { it.id == product.categoryId }?.name ?: "इतर"
                            ProductInventoryCard(
                                product = product,
                                categoryName = catName,
                                onAdjustStockClick = { productForStockAdjust = product }
                            )
                        }
                    }
                }
            } else {
                // TAB 1: STOCK MOVEMENTS LEDGER
                if (movements.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.ReceiptLong, contentDescription = null, tint = OutlineVariantGrey, modifier = Modifier.size(60.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("अद्याप कोणतीही स्टॉक हालचाल नोंद नाही", fontWeight = FontWeight.Bold, color = DeepCharcoal)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("विक्री झाल्यावर किंवा नवीन स्टॉक ॲड केल्यावर येथे नोंद दिसेल.", fontSize = 12.sp, color = MutedCharcoal, textAlign = TextAlign.Center)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 10.dp, bottom = 40.dp)
                    ) {
                        itemsIndexed(movements, key = { index, movement -> "inv_mov_${movement.id}_$index" }) { _, movement ->
                            StockMovementRow(movement)
                        }
                    }
                }
            }
        }
    }

    // Restock & Stock Adjustment Dialog
    productForStockAdjust?.let { product ->
        StockAdjustmentDialog(
            product = product,
            onDismiss = { productForStockAdjust = null },
            onConfirm = { type, delta, exactCount, note ->
                viewModel.adjustStock(
                    productId = product.id,
                    delta = delta,
                    newExactStock = exactCount,
                    type = type,
                    note = note
                ) { success ->
                    if (success) {
                        Toast.makeText(context, "${product.name} चा स्टॉक यशस्वीरित्या अपडेट झाला", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "स्टॉक अपडेट करण्यात अडचण आली", Toast.LENGTH_SHORT).show()
                    }
                }
                productForStockAdjust = null
            }
        )
    }
}

@Composable
fun ProductInventoryCard(
    product: Product,
    categoryName: String,
    onAdjustStockClick: () -> Unit
) {
    val totalAdded = product.effectiveTotalStockAdded
    val sold = product.totalSold
    val current = product.currentStock
    val sellThrough = product.sellThroughRate

    val stockStatusColor = when {
        current <= 0 -> BoutiqueError
        else -> BoutiqueSuccess
    }

    val stockStatusLabel = when {
        current <= 0 -> "स्टॉक संपला (Out of Stock)"
        else -> "उपलब्ध: $current नग"
    }

    MatoshreeCard {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Name & Selling Price
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$categoryName • SKU: ${product.sku ?: "नाही"}",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 11.sp)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = CurrencyFormatter.format(product.sellingPrice),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                    )
                    if (product.costPrice != null && product.costPrice > 0) {
                        Text(
                            text = "खरेदी: ${CurrencyFormatter.format(product.costPrice)}",
                            style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal, fontSize = 10.sp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Stock Visual Progress Bar (Sold vs In-Stock)
            val progressFraction = if (totalAdded > 0) (sold.toFloat() / totalAdded.toFloat()).coerceIn(0f, 1f) else 0f
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "खपलेला: $sold नग (${String.format("%.1f", sellThrough)}%)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DeepEmerald
                    )
                    Text(
                        text = "शिल्लक: $current नग",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = stockStatusColor
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFEEEEEE))
                ) {
                    // Green sold portion
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = progressFraction)
                            .background(DeepEmerald)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Badges Row and Quick Action Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stock Status Pill
                Surface(
                    color = stockStatusColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, stockStatusColor.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(stockStatusColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stockStatusLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = stockStatusColor
                        )
                    }
                }

                // Quick Stock Adjust Button
                Button(
                    onClick = onAdjustStockClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepEmeraldContainer.copy(alpha = 0.15f),
                        contentColor = DeepEmerald
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.AddBusiness, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("स्टॉक ॲडजस्ट", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StockMovementRow(movement: StockMovement) {
    val isPositive = movement.quantityDelta > 0
    val badgeColor = when (movement.changeType) {
        StockChangeType.RESTOCK -> BoutiqueSuccess
        StockChangeType.SALE -> DeepEmerald
        StockChangeType.RETURN -> ChampagneGold
        StockChangeType.DAMAGE -> BoutiqueError
        StockChangeType.ADJUSTMENT -> Color(0xFF1976D2)
    }

    val icon = when (movement.changeType) {
        StockChangeType.RESTOCK -> Icons.Default.AddCircleOutline
        StockChangeType.SALE -> Icons.Default.ShoppingCart
        StockChangeType.RETURN -> Icons.Default.Replay
        StockChangeType.DAMAGE -> Icons.Default.RemoveCircleOutline
        StockChangeType.ADJUSTMENT -> Icons.Default.EditNote
    }

    MatoshreeCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(badgeColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = movement.productName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = DeepEmerald,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${movement.changeType.displayName} ${if (!movement.note.isNullOrBlank()) "• " + movement.note else ""}",
                    fontSize = 11.sp,
                    color = MutedCharcoal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val dateStr = remember(movement.timestamp) {
                    val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                    sdf.format(Date(movement.timestamp))
                }
                Text(
                    text = dateStr,
                    fontSize = 10.sp,
                    color = MutedCharcoal.copy(alpha = 0.8f)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                val deltaText = if (isPositive) "+${movement.quantityDelta}" else "${movement.quantityDelta}"
                Text(
                    text = "$deltaText नग",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = if (isPositive) BoutiqueSuccess else BoutiqueError
                )
                if (movement.stockAfter > 0) {
                    Text(
                        text = "शिल्लक: ${movement.stockAfter}",
                        fontSize = 11.sp,
                        color = MutedCharcoal
                    )
                }
            }
        }
    }
}

@Composable
fun StockAdjustmentDialog(
    product: Product,
    onDismiss: () -> Unit,
    onConfirm: (type: StockChangeType, delta: Int, exactCount: Int?, note: String?) -> Unit
) {
    var selectedMode by remember { mutableStateOf(0) } // 0: Restock (+), 1: Audit Count (=), 2: Damage/Shrinkage (-)
    var quantityText by remember { mutableStateOf("10") }
    var exactCountText by remember { mutableStateOf(product.currentStock.toString()) }
    var noteText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "स्टॉक ॲडजस्टमेंट / आवक नोंद",
                    fontWeight = FontWeight.Bold,
                    color = DeepEmerald,
                    fontSize = 17.sp
                )
                Text(
                    text = product.name,
                    fontSize = 13.sp,
                    color = MutedCharcoal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Current stock snapshot
                Surface(
                    color = WarmIvory,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("सध्याचा उपलब्ध स्टॉक:", fontSize = 12.sp, color = MutedCharcoal)
                        Text("${product.currentStock} नग", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = DeepEmerald)
                    }
                }

                // Mode Selector Segmented Control
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = selectedMode == 0,
                        onClick = { selectedMode = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = DeepEmerald,
                            activeContentColor = WarmWhite,
                            inactiveContainerColor = WarmWhite,
                            inactiveContentColor = DeepEmerald
                        )
                    ) {
                        Text("+ आवक", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    SegmentedButton(
                        selected = selectedMode == 1,
                        onClick = { selectedMode = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = DeepEmerald,
                            activeContentColor = WarmWhite,
                            inactiveContainerColor = WarmWhite,
                            inactiveContentColor = DeepEmerald
                        )
                    ) {
                        Text("मोजणी", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    SegmentedButton(
                        selected = selectedMode == 2,
                        onClick = { selectedMode = 2 },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = BoutiqueError,
                            activeContentColor = WarmWhite,
                            inactiveContainerColor = WarmWhite,
                            inactiveContentColor = BoutiqueError
                        )
                    ) {
                        Text("- डॅमेज", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }

                when (selectedMode) {
                    0 -> {
                        // Restock / Add Stock
                        OutlinedTextField(
                            value = quantityText,
                            onValueChange = { quantityText = it.filter { c -> c.isDigit() } },
                            label = { Text("नवीन आलेला माल (नग संख्या)") },
                            placeholder = { Text("उदा. 10") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                        val addQty = quantityText.toIntOrNull() ?: 0
                        Text(
                            text = "अपडेटनंतर उपलब्ध स्टॉक: ${product.currentStock + addQty} नग",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BoutiqueSuccess
                        )
                    }
                    1 -> {
                        // Exact Count Audit
                        OutlinedTextField(
                            value = exactCountText,
                            onValueChange = { exactCountText = it.filter { c -> c.isDigit() } },
                            label = { Text("प्रत्यक्ष मोजलेला शिल्लक स्टॉक") },
                            placeholder = { Text("उदा. ${product.currentStock}") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                        val exact = exactCountText.toIntOrNull() ?: product.currentStock
                        val diff = exact - product.currentStock
                        Text(
                            text = "फरक: ${if (diff >= 0) "+$diff" else "$diff"} नग",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (diff >= 0) BoutiqueSuccess else BoutiqueError
                        )
                    }
                    2 -> {
                        // Damage / Shrinkage
                        OutlinedTextField(
                            value = quantityText,
                            onValueChange = { quantityText = it.filter { c -> c.isDigit() } },
                            label = { Text("खराब / कमी करायचे नग") },
                            placeholder = { Text("उदा. 2") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                        val deductQty = quantityText.toIntOrNull() ?: 0
                        Text(
                            text = "अपडेटनंतर उपलब्ध स्टॉक: ${(product.currentStock - deductQty).coerceAtLeast(0)} नग",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BoutiqueError
                        )
                    }
                }

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("कारण / टीप (ऐच्छिक)") },
                    placeholder = {
                        Text(
                            when (selectedMode) {
                                0 -> "उदा. मुंबई नवीन लॉट आवक"
                                1 -> "उदा. महिनाअखेर भौतिक मोजणी दुरुस्ती"
                                else -> "उदा. १ साडी फाटलेली सापडली"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (selectedMode) {
                        0 -> {
                            val qty = quantityText.toIntOrNull() ?: 0
                            if (qty > 0) {
                                onConfirm(StockChangeType.RESTOCK, qty, null, noteText.ifBlank { "नवीन माल आवक (+${qty} नग)" })
                            }
                        }
                        1 -> {
                            val exact = exactCountText.toIntOrNull() ?: product.currentStock
                            val diff = exact - product.currentStock
                            onConfirm(StockChangeType.ADJUSTMENT, diff, exact, noteText.ifBlank { "प्रत्यक्ष मोजणी दुरुस्ती ($exact नग)" })
                        }
                        2 -> {
                            val qty = quantityText.toIntOrNull() ?: 0
                            if (qty > 0) {
                                onConfirm(StockChangeType.DAMAGE, -qty, null, noteText.ifBlank { "खराब माल वजा (-${qty} नग)" })
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("स्टॉक सेव्ह करा", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("रद्द करा", color = MutedCharcoal)
            }
        },
        containerColor = WarmWhite
    )
}

// -------------------------------------------------------------
// INVENTORY SUMMARY 58MM THERMAL PRINT HELPER
// -------------------------------------------------------------
suspend fun printInventorySummaryThermal(
    context: Context,
    products: List<Product>,
    totalAdded: Int,
    totalSold: Int,
    totalInStock: Int,
    totalRetailVal: Double
): Result<Unit> {
    val printerManager = ThermalPrinterManager(context)
    val snap = PrintShareHelper.loadShopSettingsSnapshot()

    val out = java.io.ByteArrayOutputStream()

    // Initialize printer
    out.write(byteArrayOf(0x1B, 0x40)) // ESC @ initialize
    out.write(byteArrayOf(0x1B, 0x37, 0x07, 0xF0.toByte(), 0x02)) // Max thermal darkness

    // Header Center aligned
    out.write(byteArrayOf(0x1B, 0x61, 0x01))
    out.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold ON
    out.write("${snap.shopName}\n".toByteArray(java.nio.charset.Charset.forName("UTF-8")))
    out.write("इन्व्हेंटरी व स्टॉक रिपोर्ट\n".toByteArray(java.nio.charset.Charset.forName("UTF-8")))
    out.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold OFF

    val todayStr = DateUtils.todayFormattedMarathi()
    out.write("तारीख: $todayStr\n".toByteArray(java.nio.charset.Charset.forName("UTF-8")))
    out.write("--------------------------------\n".toByteArray())

    // Summary Left aligned
    out.write(byteArrayOf(0x1B, 0x61, 0x00))
    out.write("एकूण आणलेला स्टॉक : $totalAdded नग\n".toByteArray(java.nio.charset.Charset.forName("UTF-8")))
    out.write("एकूण खपलेला स्टॉक  : $totalSold नग\n".toByteArray(java.nio.charset.Charset.forName("UTF-8")))
    out.write("एकूण शिल्लक स्टॉक   : $totalInStock नग\n".toByteArray(java.nio.charset.Charset.forName("UTF-8")))
    out.write("शिल्लक मालाचे मूल्य : ${CurrencyFormatter.format(totalRetailVal)}\n".toByteArray(java.nio.charset.Charset.forName("UTF-8")))
    out.write("--------------------------------\n".toByteArray())

    // Low stock / Out of stock warnings
    val urgentItems = products.filter { it.trackInventory && it.currentStock <= it.lowStockThreshold }
    if (urgentItems.isNotEmpty()) {
        out.write(byteArrayOf(0x1B, 0x45, 0x01))
        out.write("तातडीचा माल (कमी / संपलेला):\n".toByteArray(java.nio.charset.Charset.forName("UTF-8")))
        out.write(byteArrayOf(0x1B, 0x45, 0x00))
        urgentItems.take(15).forEach { p ->
            val status = if (p.currentStock <= 0) "संपला" else "शिल्लक: ${p.currentStock}"
            out.write("• ${p.name.take(18)} ($status)\n".toByteArray(java.nio.charset.Charset.forName("UTF-8")))
        }
        out.write("--------------------------------\n".toByteArray())
    }

    out.write(byteArrayOf(0x1B, 0x61, 0x01))
    out.write("मातोश्री कलेक्शन - धन्यवाद!\n\n\n\n".toByteArray(java.nio.charset.Charset.forName("UTF-8")))
    out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x10)) // Cut paper

    return printerManager.bluetoothManager.sendBytes(out.toByteArray())
}

// -------------------------------------------------------------
// COLOR INVENTORY REPORT SHARE TO WHATSAPP / SYSTEM
// -------------------------------------------------------------
suspend fun shareColorInventoryReport(
    context: Context,
    products: List<Product>,
    totalAdded: Int,
    totalSold: Int,
    totalInStock: Int,
    costValue: Double,
    retailValue: Double,
    sellThrough: Double
) {
    try {
        val settings = PrintShareHelper.loadShopSettingsSnapshot()
        val width = 760
        val urgentList = products.filter { it.trackInventory && it.currentStock <= it.lowStockThreshold }.take(6)
        val height = 950 + (urgentList.size * 48)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.parseColor("#FFFDF5")) // Warm Ivory

        val leftX = 36f
        val rightX = width - 36f
        var y = 30f

        // 1. Luxury Header Card
        val headerCardH = 190f
        val headerRect = RectF(leftX, y, rightX, y + headerCardH)
        val emeraldBg = Paint().apply { color = android.graphics.Color.parseColor("#00342B"); style = Paint.Style.FILL; isAntiAlias = true }
        val goldBorder = Paint().apply { color = android.graphics.Color.parseColor("#C39B14"); strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true }
        canvas.drawRoundRect(headerRect, 16f, 16f, emeraldBg)
        canvas.drawRoundRect(headerRect, 16f, 16f, goldBorder)

        val goldTitlePaint = Paint().apply {
            color = android.graphics.Color.parseColor("#FED65B")
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText(settings.shopName.ifBlank { "मातोश्री कलेक्शन" }, width / 2f, y + 60f, goldTitlePaint)

        val whiteSubPaint = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("इन्व्हेंटरी व स्टॉक व्यवस्थापन रिपोर्ट", width / 2f, y + 100f, whiteSubPaint)

        val datePaint = Paint().apply {
            color = android.graphics.Color.parseColor("#E0E0E0")
            textSize = 14f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val dateText = "तारीख: ${DateUtils.todayFormattedMarathi()} | संपर्क: ${settings.mobile}"
        canvas.drawText(dateText, width / 2f, y + 138f, datePaint)

        y += headerCardH + 24f

        // 2. Metrics 3-Column Card
        val metricsH = 110f
        val metricsRect = RectF(leftX, y, rightX, y + metricsH)
        val cardBg = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        val cardBorder = Paint().apply { color = android.graphics.Color.parseColor("#E0E0E0"); strokeWidth = 1.5f; style = Paint.Style.STROKE; isAntiAlias = true }
        canvas.drawRoundRect(metricsRect, 12f, 12f, cardBg)
        canvas.drawRoundRect(metricsRect, 12f, 12f, cardBorder)

        val colW = (rightX - leftX) / 3f

        fun drawMetric(cx: Float, label: String, value: String, valColor: Int) {
            val lPaint = Paint().apply { color = android.graphics.Color.parseColor("#616161"); textSize = 13f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
            val vPaint = Paint().apply { color = valColor; textSize = 22f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER; isAntiAlias = true }
            canvas.drawText(label, cx, y + 36f, lPaint)
            canvas.drawText(value, cx, y + 74f, vPaint)
        }

        drawMetric(leftX + colW * 0.5f, "एकूण आणलेला", "$totalAdded नग", android.graphics.Color.parseColor("#00342B"))
        drawMetric(leftX + colW * 1.5f, "विकलेला (खप)", "$totalSold नग", android.graphics.Color.parseColor("#2E7D32"))
        drawMetric(leftX + colW * 2.5f, "शिल्लक माल", "$totalInStock नग", if (totalInStock <= 10) android.graphics.Color.parseColor("#C62828") else android.graphics.Color.parseColor("#00342B"))

        y += metricsH + 20f

        // 3. Stock Valuation Card
        val valCardH = 120f
        val valRect = RectF(leftX, y, rightX, y + valCardH)
        canvas.drawRoundRect(valRect, 12f, 12f, cardBg)
        canvas.drawRoundRect(valRect, 12f, 12f, cardBorder)

        val labelP = Paint().apply { color = android.graphics.Color.parseColor("#424242"); textSize = 14f; isAntiAlias = true }
        val valP = Paint().apply { color = android.graphics.Color.parseColor("#00342B"); textSize = 16f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.RIGHT; isAntiAlias = true }

        canvas.drawText("शिल्लक मालाचे विक्री मूल्य:", leftX + 16f, y + 36f, labelP)
        canvas.drawText(CurrencyFormatter.format(retailValue), rightX - 16f, y + 36f, valP)

        if (costValue > 0) {
            canvas.drawText("एकूण खरेदी भांडवल (गुंतवणूक):", leftX + 16f, y + 70f, labelP)
            canvas.drawText(CurrencyFormatter.format(costValue), rightX - 16f, y + 70f, valP)
        }

        canvas.drawText("एकूण खप दर (Sell-Through Rate):", leftX + 16f, y + 104f, labelP)
        val rateP = Paint().apply { color = android.graphics.Color.parseColor("#2E7D32"); textSize = 16f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.RIGHT; isAntiAlias = true }
        canvas.drawText("${String.format("%.1f", sellThrough)}%", rightX - 16f, y + 104f, rateP)

        y += valCardH + 24f

        // 4. Low stock section
        if (urgentList.isNotEmpty()) {
            val secTitle = Paint().apply { color = android.graphics.Color.parseColor("#C62828"); textSize = 17f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
            canvas.drawText("⚠️ तातडीने मागवायचा माल (कमी / संपलेला स्टॉक):", leftX, y, secTitle)
            y += 18f

            urgentList.forEach { p ->
                y += 34f
                val pRect = RectF(leftX, y - 22f, rightX, y + 18f)
                val pBg = Paint().apply { color = android.graphics.Color.parseColor("#FFF3E0"); style = Paint.Style.FILL; isAntiAlias = true }
                canvas.drawRoundRect(pRect, 8f, 8f, pBg)

                val nameP = Paint().apply { color = android.graphics.Color.parseColor("#212121"); textSize = 14f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
                canvas.drawText(p.name.take(28), leftX + 12f, y, nameP)

                val stP = Paint().apply {
                    color = if (p.currentStock <= 0) android.graphics.Color.parseColor("#C62828") else android.graphics.Color.parseColor("#E65100")
                    textSize = 13f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.RIGHT
                    isAntiAlias = true
                }
                val stText = if (p.currentStock <= 0) "संपला (० नग)" else "फक्त ${p.currentStock} नग शिल्लक"
                canvas.drawText(stText, rightX - 12f, y, stP)
            }
        }

        // Save Bitmap to cache & share
        val cacheDir = java.io.File(context.cacheDir, "reports").apply { mkdirs() }
        val file = java.io.File(cacheDir, "Matoshree_Inventory_Report.png")
        val stream = FileOutputStream(file)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.flush()
        stream.close()

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "मातोश्री कलेक्शन - इन्व्हेंटरी रिपोर्ट")
            putExtra(
                android.content.Intent.EXTRA_TEXT,
                "📊 *मातोश्री कलेक्शन - इन्व्हेंटरी व स्टॉक रिपोर्ट*\n\n" +
                "• एकूण आणलेला स्टॉक: $totalAdded नग\n" +
                "• एकूण विकलेला (खप): $totalSold नग (${String.format("%.1f", sellThrough)}% खप)\n" +
                "• शिल्लक स्टॉक: $totalInStock नग\n" +
                "• शिल्लक विक्री मूल्य: ${CurrencyFormatter.format(retailValue)}\n\n" +
                "सविस्तर माहिती जोडलेल्या फोटोमध्ये पहा."
            )
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(android.content.Intent.createChooser(intent, "इन्व्हेंटरी रिपोर्ट शेअर करा"))
    } catch (e: Exception) {
        Toast.makeText(context, "रिपोर्ट शेअर करताना अडचण आली: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

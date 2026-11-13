package com.matoshree.shopmanager.ui.screens.product

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.ProductRepository
import com.matoshree.shopmanager.domain.model.Category
import com.matoshree.shopmanager.domain.model.Product
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.components.OnePlusAlphabetScroller
import com.matoshree.shopmanager.ui.components.StatusBadge
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.AlphabetUtils
import com.matoshree.shopmanager.utils.CurrencyFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class ProductSortOption(val title: String) {
    NAME_ASC("नाव (A-Z)"),
    PRICE_LOW_HIGH("किंमत: कमी → जास्त"),
    PRICE_HIGH_LOW("किंमत: जास्त → कमी"),
    RECENTLY_ADDED("अलीकडे जोडलेले")
}

class ProductViewModel(private val productRepository: ProductRepository) : ViewModel() {
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories

    val searchQuery = MutableStateFlow("")
    val selectedCategoryId = MutableStateFlow<Long?>(null)
    val sortOption = MutableStateFlow(ProductSortOption.NAME_ASC)

    private val errorHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("ProductViewModel", "ProductViewModel error caught safely: ${throwable.message}", throwable)
    }

    init {
        observeCatalog()
    }

    private fun observeCatalog() {
        viewModelScope.launch(errorHandler) {
            productRepository.getAllProducts()
                .catch { e -> android.util.Log.e("ProductViewModel", "Products flow error: ${e.message}", e) }
                .collectLatest {
                    _products.value = it
                }
        }
        viewModelScope.launch(errorHandler) {
            productRepository.getAllCategories()
                .catch { e -> android.util.Log.e("ProductViewModel", "Categories flow error: ${e.message}", e) }
                .collectLatest {
                    _categories.value = it
                }
        }
    }

    fun addProduct(
        name: String,
        sku: String?,
        price: Double,
        costPrice: Double? = null,
        categoryId: Long? = null,
        trackInventory: Boolean = false,
        stockQuantity: Int = 0
    ) {
        viewModelScope.launch {
            try {
                productRepository.saveProduct(
                    Product(
                        name = name.trim(),
                        sku = sku?.trim()?.ifBlank { null },
                        sellingPrice = price,
                        costPrice = costPrice,
                        categoryId = categoryId,
                        trackInventory = trackInventory,
                        currentStock = stockQuantity
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("ProductVM", "Error adding product: ${e.message}", e)
            }
        }
    }

    fun addCategory(name: String, description: String? = null, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                productRepository.saveCategory(name, description)
                onComplete()
            } catch (e: Exception) {
                android.util.Log.e("ProductVM", "Error adding category: ${e.message}", e)
            }
        }
    }

    fun renameCategory(id: Long, newName: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                productRepository.renameCategory(id, newName)
                _categories.value = _categories.value.map {
                    if (it.id == id) it.copy(name = newName) else it
                }
                onComplete()
            } catch (e: Exception) {
                android.util.Log.e("ProductVM", "Error renaming category: ${e.message}", e)
            }
        }
    }

    fun deleteCategory(id: Long, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                productRepository.deleteCategory(id)
                _categories.value = _categories.value.filter { it.id != id }
                if (selectedCategoryId.value == id) {
                    selectedCategoryId.value = null
                }
                onComplete()
            } catch (e: Exception) {
                android.util.Log.e("ProductVM", "Error deleting category: ${e.message}", e)
            }
        }
    }

    fun updateSingleProductCategory(productId: Long, categoryId: Long?, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                productRepository.updateProductCategory(productId, categoryId)
                _products.value = _products.value.map {
                    if (it.id == productId) it.copy(categoryId = categoryId) else it
                }
                onComplete()
            } catch (e: Exception) {
                android.util.Log.e("ProductVM", "Error updating product category: ${e.message}", e)
            }
        }
    }

    val selectedProductIds = MutableStateFlow<Set<Long>>(emptySet())

    fun toggleProductSelection(productId: Long) {
        val current = selectedProductIds.value.toMutableSet()
        if (current.contains(productId)) current.remove(productId) else current.add(productId)
        selectedProductIds.value = current
    }

    fun selectAllProducts(ids: List<Long>) {
        selectedProductIds.value = ids.toSet()
    }

    fun clearProductSelection() {
        selectedProductIds.value = emptySet()
    }

    fun updateSelectedCategory(categoryId: Long?, onComplete: () -> Unit) {
        val ids = selectedProductIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            productRepository.updateProductsCategory(ids, categoryId)
            clearProductSelection()
            onComplete()
        }
    }

    fun deleteSelectedProducts(onComplete: (Int) -> Unit) {
        val ids = selectedProductIds.value.toList()
        if (ids.isEmpty()) return
        _products.value = _products.value.filter { !ids.contains(it.id) }
        viewModelScope.launch {
            val res = productRepository.deleteProductsBatch(ids)
            clearProductSelection()
            onComplete(res.getOrDefault(ids.size))
        }
    }

    fun deleteOrArchiveProduct(productId: Long, onComplete: (String) -> Unit) {
        _products.value = _products.value.filter { it.id != productId }
        viewModelScope.launch {
            val result = productRepository.deleteOrArchiveProduct(productId)
            onComplete(result)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsListScreen(
    viewModel: ProductViewModel,
    onNavigateToLabels: () -> Unit = {},
    onNavigateToInventory: () -> Unit = {},
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val products by viewModel.products.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val search by viewModel.searchQuery.collectAsState()
    val selectedCatId by viewModel.selectedCategoryId.collectAsState()
    val currentSort by viewModel.sortOption.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showBarcodeScanner by remember { mutableStateOf(false) }
    var selectedProductForDetails by remember { mutableStateOf<Product?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<Product?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showCategoryManageDialog by remember { mutableStateOf(false) }
    var showRenameCategoryDialog by remember { mutableStateOf<Category?>(null) }
    var showDeleteCategoryDialog by remember { mutableStateOf<Category?>(null) }
    var showChangeProductCatDialog by remember { mutableStateOf<Product?>(null) }

    val filtered = remember(products, search, selectedCatId, currentSort) {
        var list = products

        if (search.isNotBlank()) {
            list = list.filter {
                it.name.contains(search, ignoreCase = true) || (it.sku?.contains(search, ignoreCase = true) == true)
            }
        }

        if (selectedCatId != null) {
            list = list.filter { it.categoryId == selectedCatId }
        }

        when (currentSort) {
            ProductSortOption.NAME_ASC -> list.sortedWith { p1, p2 ->
                AlphabetUtils.compareAlphabetically(p1.name, p2.name)
            }
            ProductSortOption.PRICE_LOW_HIGH -> list.sortedBy { it.sellingPrice }
            ProductSortOption.PRICE_HIGH_LOW -> list.sortedByDescending { it.sellingPrice }
            ProductSortOption.RECENTLY_ADDED -> list.sortedByDescending { it.id }
        }
    }

    val productSectionIndices = remember(filtered) {
        val map = mutableMapOf<String, Int>()
        filtered.forEachIndexed { index, prod ->
            val key = AlphabetUtils.getAlphabetKey(prod.name)
            if (!map.containsKey(key)) {
                map[key] = index
            }
        }
        map
    }

    val productListState = rememberLazyListState()

    if (showBarcodeScanner) {
        com.matoshree.shopmanager.ui.components.BarcodeScannerDialog(
            title = "बारकोड / एसकेयू स्कॅन करा",
            subtitle = "प्रॉडक्टचा बारकोड कॅमेऱ्यासमोर धरा",
            onBarcodeScanned = { scannedCode ->
                viewModel.searchQuery.value = scannedCode
                showBarcodeScanner = false
                Toast.makeText(context, "स्कॅन झाले: $scannedCode", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showBarcodeScanner = false }
        )
    }

    val selectedProductIds by viewModel.selectedProductIds.collectAsState()
    var showBulkCategoryDialog by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    // Bulk Category Change Dialog
    if (showBulkCategoryDialog) {
        var selectedTargetCatId by remember { mutableStateOf<Long?>(null) }
        AlertDialog(
            onDismissRequest = { showBulkCategoryDialog = false },
            title = { Text("कॅटेगरी बदला (${selectedProductIds.size} प्रॉडक्ट्स)", fontWeight = FontWeight.Bold, color = DeepEmerald) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("निवडलेल्या प्रॉडक्ट्ससाठी नवीन कॅटेगरी निवडा:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                    Surface(
                        color = if (selectedTargetCatId == null) DeepEmeraldContainer.copy(alpha = 0.2f) else WarmWhite,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedTargetCatId == null) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().clickable { selectedTargetCatId = null }
                    ) {
                        Text("कॅटेगरी नाही (इतर / General)", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
                    }
                    categories.forEach { cat ->
                        Surface(
                            color = if (selectedTargetCatId == cat.id) DeepEmeraldContainer.copy(alpha = 0.2f) else WarmWhite,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedTargetCatId == cat.id) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().clickable { selectedTargetCatId = cat.id }
                        ) {
                            Text(cat.name, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateSelectedCategory(selectedTargetCatId) {
                            Toast.makeText(context, "${selectedProductIds.size} प्रॉडक्ट्सची कॅटेगरी अपडेट झाली", Toast.LENGTH_SHORT).show()
                            showBulkCategoryDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("कॅटेगरी लागू करा", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkCategoryDialog = false }) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    // Bulk Delete Dialog
    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = { Text("${selectedProductIds.size} प्रॉडक्ट्स डिलीट करायचे का?", fontWeight = FontWeight.Bold, color = BoutiqueError) },
            text = {
                Text("आपण निवडलेले ${selectedProductIds.size} प्रॉडक्ट्स कायमचे डिलीट करू इच्छिता का? हे प्रॉडक्ट्स कॅटलॉगमधून काढून टाकले जातील.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val count = selectedProductIds.size
                        viewModel.deleteSelectedProducts { deletedCount ->
                            Toast.makeText(context, "$deletedCount प्रॉडक्ट्स डिलीट झाले", Toast.LENGTH_SHORT).show()
                            showBulkDeleteDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError)
                ) {
                    Text("सर्व डिलीट करा", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    Scaffold(
        topBar = {
            if (selectedProductIds.isNotEmpty()) {
                Surface(
                    color = DeepEmerald,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { viewModel.clearProductSelection() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "निवड साफ करा", tint = WarmWhite, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${selectedProductIds.size} निवडले",
                                color = WarmWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    if (selectedProductIds.size == filtered.size) {
                                        viewModel.clearProductSelection()
                                    } else {
                                        viewModel.selectAllProducts(filtered.map { it.id })
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (selectedProductIds.size == filtered.size) "निवड रद्द करा" else "सर्व निवडा",
                                    color = ChampagneGold,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    val selectedProds = products.filter { selectedProductIds.contains(it.id) }
                                    if (selectedProds.isNotEmpty()) {
                                        coroutineScope.launch {
                                            val labelMgr = com.matoshree.shopmanager.printer.ProductLabelPrinterManager(context)
                                            val snap = com.matoshree.shopmanager.utils.PrintShareHelper.loadShopSettingsSnapshot()
                                            var successCount = 0
                                            selectedProds.forEach { prod ->
                                                val res = labelMgr.printProductLabel(prod, 1, snap)
                                                if (res.isSuccess) successCount++
                                            }
                                            Toast.makeText(context, "$successCount/${selectedProds.size} प्रॉडक्ट लेबल्स प्रिंट झाली", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Print, contentDescription = "निवडलेल्यांचे लेबल्स प्रिंट करा", tint = WarmWhite, modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = { showBulkCategoryDialog = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Category, contentDescription = "कॅटेगरी बदला", tint = WarmWhite, modifier = Modifier.size(20.dp))
                            }
                            IconButton(
                                onClick = { showBulkDeleteDialog = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "निवडलेले डिलीट करा", tint = Color(0xFFFF8A80), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            } else {
                MatoshreeTopAppBar(
                    title = "प्रॉडक्ट्स (कॅटलॉग)",
                    showBackButton = true,
                    onBackClick = onBackClick,
                    actions = {
                        IconButton(onClick = onNavigateToInventory) {
                            Icon(Icons.Filled.Inventory2, contentDescription = "इन्व्हेंटरी व स्टॉक", tint = DeepEmerald)
                        }
                        IconButton(onClick = onNavigateToLabels) {
                            Icon(Icons.Default.Label, contentDescription = "प्रॉडक्ट लेबल्स", tint = DeepEmerald)
                        }
                        IconButton(onClick = { showCategoryManageDialog = true }) {
                            Icon(Icons.Default.Category, contentDescription = "कॅटेगरी व्यवस्थापन", tint = DeepEmerald)
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "सॉर्ट करा", tint = DeepEmerald)
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.background(WarmWhite)
                            ) {
                                ProductSortOption.values().forEach { opt ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = opt.title,
                                                fontWeight = if (opt == currentSort) FontWeight.Bold else FontWeight.Normal,
                                                color = if (opt == currentSort) DeepEmerald else DeepCharcoal
                                            )
                                        },
                                        onClick = {
                                            viewModel.sortOption.value = opt
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (selectedProductIds.isEmpty()) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = DeepEmerald,
                    contentColor = WarmWhite,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Product")
                }
            }
        }
    ) { padding ->
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isTablet = configuration.screenWidthDp >= 600

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
                .padding(horizontal = if (isTablet) 24.dp else 16.dp)
        ) {
            // Search Bar with Dedicated Barcode Scanner Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { viewModel.searchQuery.value = it },
                    placeholder = { Text("नाव किंवा बारकोड सर्च करा…", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "सर्च", tint = DeepEmerald) },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "साफ करा")
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        android.util.Log.d("ProductsScreen", "Scan button clicked")
                        showBarcodeScanner = true
                    },
                    modifier = Modifier
                        .size(54.dp)
                        .background(DeepEmeraldContainer, RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = "बारकोड स्कॅन करा", tint = WarmWhite)
                }
            }

            // Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCatId == null,
                    onClick = { viewModel.selectedCategoryId.value = null },
                    label = { Text("सर्व (${products.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DeepEmerald,
                        selectedLabelColor = WarmWhite
                    )
                )

                categories.forEach { cat ->
                    val count = products.count { it.categoryId == cat.id }
                    FilterChip(
                        selected = selectedCatId == cat.id,
                        onClick = {
                            viewModel.selectedCategoryId.value = if (selectedCatId == cat.id) null else cat.id
                        },
                        label = { Text("${cat.name} ($count)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DeepEmerald,
                            selectedLabelColor = WarmWhite
                        )
                    )
                }

                AssistChip(
                    onClick = { showCategoryManageDialog = true },
                    label = { Text("कॅटेगरी व्यवस्थापन", fontWeight = FontWeight.Bold, color = DeepEmerald) },
                    leadingIcon = {
                        Icon(Icons.Default.Category, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(16.dp))
                    },
                    colors = AssistChipDefaults.assistChipColors(containerColor = ChampagneGoldContainer.copy(alpha = 0.5f))
                )
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = OutlineVariantGrey, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("कोणतेही प्रॉडक्ट सापडले नाही.", style = MaterialTheme.typography.bodyLarge.copy(color = MutedCharcoal))
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = productListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = if (filtered.isNotEmpty()) 24.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(filtered, key = { product -> "prod_${product.id}" }) { product ->
                            val catName = categories.find { it.id == product.categoryId }?.name ?: "इतर"
                            val isSelected = selectedProductIds.contains(product.id)
                            ProductListItemCard(
                                product = product,
                                catName = catName,
                                isSelected = isSelected,
                                isSelectionMode = selectedProductIds.isNotEmpty(),
                                onClick = {
                                    if (selectedProductIds.isNotEmpty()) {
                                        viewModel.toggleProductSelection(product.id)
                                    } else {
                                        selectedProductForDetails = product
                                    }
                                },
                                onLongClick = {
                                    viewModel.toggleProductSelection(product.id)
                                },
                                onCheckedChange = { viewModel.toggleProductSelection(product.id) }
                            )
                        }
                    }

                    if (filtered.isNotEmpty()) {
                        OnePlusAlphabetScroller(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(top = 4.dp, bottom = 96.dp, end = 2.dp),
                            activeLetters = productSectionIndices.keys,
                            onLetterSelected = { letter ->
                                val target = AlphabetUtils.findBestTargetIndex(
                                    letter,
                                    productSectionIndices,
                                    filtered.size
                                )
                                if (target != null) {
                                    coroutineScope.launch {
                                        productListState.scrollToItem(target)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Product Details Dialog
    selectedProductForDetails?.let { product ->
        val catName = categories.find { it.id == product.categoryId }?.name ?: "इतर"
        val margin = if (product.costPrice != null && product.sellingPrice > 0) {
            val marginAmt = product.sellingPrice - product.costPrice
            val marginPct = (marginAmt / product.sellingPrice) * 100
            "₹${String.format("%.2f", marginAmt)} (${String.format("%.1f", marginPct)}%)"
        } else "नोंद नाही"
        var labelPrintQty by remember(product.id) { mutableIntStateOf(1) }

        AlertDialog(
            onDismissRequest = { selectedProductForDetails = null },
            title = {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleLarge.copy(color = DeepEmerald, fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("कॅटेगरी:", fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(catName, color = DeepEmerald, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = ChampagneGoldContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.clickable {
                                    showChangeProductCatDialog = product
                                }
                            ) {
                                Text(
                                    "बदला",
                                    color = DeepEmerald,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("बारकोड / एसकेयू:", fontWeight = FontWeight.SemiBold)
                        Text(product.sku ?: "नाही")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("विक्री किंमत:", fontWeight = FontWeight.SemiBold)
                        Text(CurrencyFormatter.format(product.sellingPrice), color = DeepEmerald, fontWeight = FontWeight.Bold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("खरेदी किंमत:", fontWeight = FontWeight.SemiBold)
                        Text(if (product.costPrice != null) CurrencyFormatter.format(product.costPrice) else "नोंद नाही")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("नफा (Margin):", fontWeight = FontWeight.SemiBold)
                        Text(margin, color = ChampagneGold, fontWeight = FontWeight.Bold)
                    }
                    if (product.trackInventory) {
                        Surface(
                            color = WarmIvory,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("इन्व्हेंटरी व स्टॉक माहिती:", fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 13.sp)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("एकूण आणलेला माल:")
                                    Text("${product.effectiveTotalStockAdded} नग", fontWeight = FontWeight.SemiBold)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("विकलेला (खप):")
                                    Text("${product.totalSold} नग (${String.format("%.1f", product.sellThroughRate)}%)", fontWeight = FontWeight.SemiBold, color = DeepEmerald)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("शिल्लक उपलब्ध स्टॉक:")
                                    val stockColor = when {
                                        product.currentStock <= 0 -> BoutiqueError
                                        else -> DeepEmerald
                                    }
                                    Text("${product.currentStock} नग", fontWeight = FontWeight.Bold, color = stockColor)
                                }
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("स्थिती:", fontWeight = FontWeight.SemiBold)
                        Text(if (product.isActive) "उपलब्ध (Active)" else "आर्काइव्ह केलेले")
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = OutlineVariantGrey.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(6.dp))

                    // Print Quantity with Quick Chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("प्रिंट संख्या:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.border(1.dp, OutlineVariantGrey.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            ) {
                                IconButton(
                                    onClick = { if (labelPrintQty > 1) labelPrintQty-- },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = "कमी करा", modifier = Modifier.size(14.dp))
                                }
                                Text(
                                    text = "$labelPrintQty",
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                IconButton(
                                    onClick = { labelPrintQty++ },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "वाढवा", modifier = Modifier.size(14.dp))
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(1, 5, 10, 20).forEach { chipQty ->
                                Surface(
                                    color = if (labelPrintQty == chipQty) DeepEmerald else WarmWhite,
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (labelPrintQty == chipQty) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { labelPrintQty = chipQty }
                                ) {
                                    Text(
                                        text = "$chipQty",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (labelPrintQty == chipQty) Color.White else DeepEmerald,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val prod = product
                            val qty = labelPrintQty
                            coroutineScope.launch {
                                val labelMgr = com.matoshree.shopmanager.printer.ProductLabelPrinterManager(context)
                                val snap = com.matoshree.shopmanager.utils.PrintShareHelper.loadShopSettingsSnapshot()
                                val res = labelMgr.printProductLabel(prod, qty, snap)
                                if (res.isSuccess) {
                                    Toast.makeText(context, "${prod.name} साठी $qty लेबल्स प्रिंट झाली", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, res.exceptionOrNull()?.message ?: "प्रिंट अयशस्वी", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (labelPrintQty > 1) "प्रिंट ($labelPrintQty)" else "लेबल प्रिंट करा")
                    }

                    OutlinedButton(
                        onClick = {
                            val toDelete = selectedProductForDetails
                            selectedProductForDetails = null
                            showDeleteConfirmDialog = toDelete
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BoutiqueError),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("आर्काइव्ह")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedProductForDetails = null }) {
                    Text("बंद करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Delete / Archive Confirmation Dialog
    showDeleteConfirmDialog?.let { prod ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = null },
            title = {
                Text("प्रॉडक्ट आर्काइव्ह किंवा डिलीट करायचे का?", fontWeight = FontWeight.Bold, color = DeepEmerald)
            },
            text = {
                Text(
                    text = "जर '${prod.name}' पूर्वीच्या बिलांमध्ये वापरले असेल, तर जुनी बिले सुरक्षित ठेवून हे सुरक्षितपणे आर्काइव्ह केले जाईल. जर कधीच वापरले नसेल, तर कायमचे डिलीट होईल.",
                    style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val prodId = prod.id
                        showDeleteConfirmDialog = null
                        viewModel.deleteOrArchiveProduct(prodId) { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("होय, कन्फर्म करा")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = null }) {
                    Text("रद्द करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Change Single Product Category Dialog
    showChangeProductCatDialog?.let { prod ->
        AlertDialog(
            onDismissRequest = { showChangeProductCatDialog = null },
            title = {
                Text(
                    text = "'${prod.name}' ची कॅटेगरी बदला",
                    fontWeight = FontWeight.Bold,
                    color = DeepEmerald,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("नवीन कॅटेगरी निवडा:", style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                    Surface(
                        color = if (prod.categoryId == null) DeepEmeraldContainer.copy(alpha = 0.3f) else WarmIvory,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (prod.categoryId == null) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.updateSingleProductCategory(prod.id, null) {
                                    Toast.makeText(context, "कॅटेगरी 'इतर / General' सेट केली", Toast.LENGTH_SHORT).show()
                                    selectedProductForDetails = selectedProductForDetails?.copy(categoryId = null)
                                    showChangeProductCatDialog = null
                                }
                            }
                    ) {
                        Text("इतर / General (कॅटेगरी नाही)", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
                    }

                    categories.forEach { cat ->
                        val isSelected = prod.categoryId == cat.id
                        Surface(
                            color = if (isSelected) DeepEmeraldContainer.copy(alpha = 0.3f) else WarmIvory,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateSingleProductCategory(prod.id, cat.id) {
                                        Toast.makeText(context, "कॅटेगरी '${cat.name}' सेट केली", Toast.LENGTH_SHORT).show()
                                        selectedProductForDetails = selectedProductForDetails?.copy(categoryId = cat.id)
                                        showChangeProductCatDialog = null
                                    }
                                }
                        ) {
                            Text(cat.name, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChangeProductCatDialog = null }) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Category Management Dialog (Add, Rename, Delete)
    if (showCategoryManageDialog) {
        var newCatName by remember { mutableStateOf("") }
        var isAddingCat by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCategoryManageDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Category, contentDescription = null, tint = DeepEmerald)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("कॅटेगरी व्यवस्थापन", fontWeight = FontWeight.Bold, color = DeepEmerald)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Add New Category Section
                    Text("नवीन कॅटेगरी जोडा:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newCatName,
                            onValueChange = { newCatName = it },
                            placeholder = { Text("उदा. साड्या / कुर्तीज", fontSize = 13.sp) },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newCatName.isNotBlank() && !isAddingCat) {
                                    isAddingCat = true
                                    viewModel.addCategory(newCatName.trim()) {
                                        Toast.makeText(context, "'${newCatName.trim()}' कॅटेगरी जोडली गेली", Toast.LENGTH_SHORT).show()
                                        newCatName = ""
                                        isAddingCat = false
                                    }
                                }
                            },
                            enabled = newCatName.isNotBlank() && !isAddingCat,
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(50.dp)
                        ) {
                            Text("+ जोडा", fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = OutlineVariantGrey.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))

                    Text("विद्यमान कॅटेगरीज (${categories.size}):", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                    if (categories.isEmpty()) {
                        Text("कोणतीही कॅटेगरी नाही.", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(categories, key = { cat -> "cat_${cat.id}" }) { cat ->
                                val pCount = products.count { it.categoryId == cat.id }
                                Surface(
                                    color = WarmIvory,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineVariantGrey.copy(alpha = 0.4f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(cat.name, fontWeight = FontWeight.Bold, color = DeepCharcoal, fontSize = 14.sp)
                                            Text("$pCount प्रॉडक्ट्स", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            // Rename Button
                                            IconButton(
                                                onClick = {
                                                    showRenameCategoryDialog = cat
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "नाव बदला", tint = DeepEmerald, modifier = Modifier.size(18.dp))
                                            }

                                            // Delete Button
                                            IconButton(
                                                onClick = {
                                                    showDeleteCategoryDialog = cat
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = "डिलीट करा", tint = BoutiqueError, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCategoryManageDialog = false }) {
                    Text("पूर्ण झाले", fontWeight = FontWeight.Bold, color = DeepEmerald)
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Rename Category Dialog
    showRenameCategoryDialog?.let { cat ->
        var renameText by remember { mutableStateOf(cat.name) }
        AlertDialog(
            onDismissRequest = { showRenameCategoryDialog = null },
            title = { Text("कॅटेगरीचे नाव बदला", fontWeight = FontWeight.Bold, color = DeepEmerald) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("कॅटेगरीचे नवीन नाव टाका:")
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newN = renameText.trim()
                        if (newN.isNotBlank()) {
                            viewModel.renameCategory(cat.id, newN) {
                                Toast.makeText(context, "कॅटेगरीचे नाव '${newN}' केले", Toast.LENGTH_SHORT).show()
                                showRenameCategoryDialog = null
                            }
                        }
                    },
                    enabled = renameText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("जतन करा", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameCategoryDialog = null }) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    // Delete Category Confirmation Dialog
    showDeleteCategoryDialog?.let { cat ->
        val catProdCount = products.count { it.categoryId == cat.id }
        AlertDialog(
            onDismissRequest = { showDeleteCategoryDialog = null },
            title = { Text("कॅटेगरी डिलीट करायची का?", fontWeight = FontWeight.Bold, color = BoutiqueError) },
            text = {
                Text(
                    "आपण '${cat.name}' ही कॅटेगरी डिलीट करू इच्छिता का?" +
                    if (catProdCount > 0) "\n\nया कॅटेगरीमधील $catProdCount प्रॉडक्ट्स कॅटलॉगमध्ये राहतील व त्यांचे वर्गीकरण 'इतर / General' होईल." else ""
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteCategory(cat.id) {
                            Toast.makeText(context, "'${cat.name}' कॅटेगरी डिलीट झाली", Toast.LENGTH_SHORT).show()
                            showDeleteCategoryDialog = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueError)
                ) {
                    Text("होय, डिलीट करा", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCategoryDialog = null }) {
                    Text("रद्द करा", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite
        )
    }

    // Add Product Dialog (with Auto-Draft Persistence)
    if (showAddDialog) {
        val existingSkus = remember(products) { products.mapNotNull { it.sku }.toSet() }
        val draft = remember { com.matoshree.shopmanager.utils.ProductDraftManager.loadDraft(context) }
        var name by remember { mutableStateOf(draft.name) }
        var sku by remember {
            mutableStateOf(
                if (draft.sku.isNotBlank()) draft.sku
                else com.matoshree.shopmanager.utils.ProductSkuGenerator.generateSku("", existingSkus)
            )
        }
        var priceText by remember { mutableStateOf(draft.priceText) }
        var selectedCatIdForNew by remember { mutableStateOf<Long?>(draft.categoryId) }
        var showCatDropdown by remember { mutableStateOf(false) }
        var trackInventory by remember { mutableStateOf(draft.trackInventory) }
        var costPriceText by remember { mutableStateOf(draft.costPriceText) }
        var stockCountText by remember { mutableStateOf(draft.stockCountText) }

        // Automatically preserve all entered draft data whenever fields change
        LaunchedEffect(name, sku, priceText, costPriceText, selectedCatIdForNew, trackInventory, stockCountText) {
            com.matoshree.shopmanager.utils.ProductDraftManager.saveDraft(
                context = context,
                name = name,
                sku = sku,
                priceText = priceText,
                costPriceText = costPriceText,
                categoryId = selectedCatIdForNew,
                trackInventory = trackInventory,
                stockCountText = stockCountText
            )
        }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("नवीन प्रॉडक्ट ॲड करा", fontWeight = FontWeight.Bold, color = DeepEmerald)
                    if (name.isNotBlank() || priceText.isNotBlank() || costPriceText.isNotBlank()) {
                        TextButton(
                            onClick = {
                                com.matoshree.shopmanager.utils.ProductDraftManager.clearDraft(context)
                                name = ""
                                sku = com.matoshree.shopmanager.utils.ProductSkuGenerator.generateSku("", existingSkus)
                                priceText = ""
                                costPriceText = ""
                                selectedCatIdForNew = null
                                trackInventory = true
                                stockCountText = "5"
                                Toast.makeText(context, "ड्राफ्ट साफ केला", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("साफ करा", fontSize = 11.sp, color = MutedCharcoal)
                        }
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (draft.hasContent) {
                        Surface(
                            color = ChampagneGoldContainer.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "📝 मागील न सेव्ह केलेला तपशील आपोआप रिस्टोअर झाला आहे.",
                                fontSize = 11.sp,
                                color = DeepEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("प्रॉडक्टचे नाव *") },
                        placeholder = { Text("उदा. कांजीवरम सिल्क साडी / पैठणी") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    // Category Selector
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showCatDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            val catTitle = categories.find { it.id == selectedCatIdForNew }?.name ?: "कॅटेगरी निवडा"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(catTitle, color = DeepEmerald, fontWeight = FontWeight.SemiBold)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = DeepEmerald)
                            }
                        }
                        DropdownMenu(
                            expanded = showCatDropdown,
                            onDismissRequest = { showCatDropdown = false },
                            modifier = Modifier.background(WarmWhite)
                        ) {
                            DropdownMenuItem(
                                text = { Text("इतर / General") },
                                onClick = {
                                    selectedCatIdForNew = null
                                    showCatDropdown = false
                                }
                            )
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.name) },
                                    onClick = {
                                        selectedCatIdForNew = cat.id
                                        showCatDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { input: String ->
                            priceText = input.filter { it.isDigit() || it == '.' }
                        },
                        label = { Text("विक्री किंमत (₹) *") },
                        placeholder = { Text("उदा. 1850") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = costPriceText,
                        onValueChange = { input: String ->
                            costPriceText = input.filter { it.isDigit() || it == '.' }
                        },
                        label = { Text("खरेदी किंमत / भांडवल (₹) - ऐच्छिक") },
                        placeholder = { Text("उदा. 1200") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = sku,
                        onValueChange = { input: String ->
                            sku = input.filter { it.isDigit() }.take(5)
                        },
                        label = { Text("बारकोड / एसकेयू कोड (५ अंक)") },
                        placeholder = { Text("10001") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    // Track Inventory Stock Toggle
                    Surface(
                        color = WarmIvory,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = trackInventory,
                                    onCheckedChange = { trackInventory = it },
                                    colors = CheckboxDefaults.colors(checkedColor = DeepEmerald)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("स्टॉक संख्या ठेवा (Track Inventory)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }

                            if (trackInventory) {
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = stockCountText,
                                    onValueChange = { stockCountText = it.filter { c -> c.isDigit() } },
                                    label = { Text("उपलब्ध नग (उदा. 5 नग)") },
                                    placeholder = { Text("5") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val price = priceText.toDoubleOrNull() ?: 0.0
                        val cost = costPriceText.toDoubleOrNull()
                        val stockQty = if (trackInventory) stockCountText.toIntOrNull() ?: 0 else 0
                        if (name.isNotBlank() && price > 0) {
                            viewModel.addProduct(
                                name = name.trim(),
                                sku = sku.ifBlank { null },
                                price = price,
                                costPrice = cost,
                                categoryId = selectedCatIdForNew,
                                trackInventory = trackInventory,
                                stockQuantity = stockQty
                            )
                            // Clear draft upon successful save
                            com.matoshree.shopmanager.utils.ProductDraftManager.clearDraft(context)
                            showAddDialog = false
                            Toast.makeText(context, "प्रॉडक्ट '$name' ॲड झाले", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save Product")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = MutedCharcoal)
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun ProductListItemCard(
    product: Product,
    catName: String,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    MatoshreeCard(
        onClick = onClick,
        onLongClick = onLongClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onCheckedChange,
                    colors = CheckboxDefaults.colors(checkedColor = DeepEmerald)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!product.isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(status = "आर्काइव्ह केले", isError = true)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$catName • SKU: ${product.sku ?: "N/A"}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (product.trackInventory) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val stockColor = when {
                            product.currentStock <= 0 -> BoutiqueError
                            else -> BoutiqueSuccess
                        }
                        val stockText = when {
                            product.currentStock <= 0 -> "संपला (0 शिल्लक)"
                            else -> "शिल्लक: ${product.currentStock} नग"
                        }
                        Surface(
                            color = stockColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = stockText,
                                color = stockColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        if (product.totalSold > 0) {
                            Text(
                                text = "• ${product.totalSold} खपले",
                                style = MaterialTheme.typography.labelSmall.copy(color = DeepEmerald, fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
                if (product.costPrice != null && product.sellingPrice > 0) {
                    val marginPct = ((product.sellingPrice - product.costPrice) / product.sellingPrice) * 100
                    Text(
                        text = "खरेदी: ${CurrencyFormatter.format(product.costPrice)} • नफा: ${String.format("%.1f", marginPct)}%",
                        style = MaterialTheme.typography.labelSmall.copy(color = ChampagneGold),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp)
            ) {
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





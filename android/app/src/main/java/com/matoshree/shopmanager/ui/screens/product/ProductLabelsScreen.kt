package com.matoshree.shopmanager.ui.screens.product

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.ProductRepository
import com.matoshree.shopmanager.domain.model.Category
import com.matoshree.shopmanager.domain.model.Product
import com.matoshree.shopmanager.printer.BarcodeGenerator
import com.matoshree.shopmanager.printer.LabelPrinterProtocol
import com.matoshree.shopmanager.printer.LabelSizeConfig
import com.matoshree.shopmanager.printer.ProductLabelFormatter
import com.matoshree.shopmanager.printer.ProductLabelPrinterManager
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.PrintShareHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class ProductLabelsViewModel(
    private val productRepository: ProductRepository
) : ViewModel() {

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories

    val searchQuery = MutableStateFlow("")
    val selectedCategoryId = MutableStateFlow<Long?>(null)

    val selectedProductIds = MutableStateFlow<Set<Long>>(emptySet())
    val labelQuantities = MutableStateFlow<Map<Long, Int>>(emptyMap())

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

    fun toggleProductSelection(productId: Long) {
        val current = selectedProductIds.value.toMutableSet()
        if (current.contains(productId)) {
            current.remove(productId)
        } else {
            current.add(productId)
        }
        selectedProductIds.value = current
    }

    fun selectAll(select: Boolean) {
        if (select) {
            selectedProductIds.value = _products.value.map { it.id }.toSet()
        } else {
            selectedProductIds.value = emptySet()
        }
    }

    fun setQuantity(productId: Long, qty: Int) {
        val current = labelQuantities.value.toMutableMap()
        current[productId] = qty.coerceIn(1, 99)
        labelQuantities.value = current
    }

    fun setBulkQuantity(qty: Int) {
        val current = labelQuantities.value.toMutableMap()
        val ids = if (selectedProductIds.value.isNotEmpty()) selectedProductIds.value else _products.value.map { it.id }.toSet()
        ids.forEach { id ->
            current[id] = qty.coerceIn(1, 99)
        }
        labelQuantities.value = current
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductLabelsScreen(
    viewModel: ProductLabelsViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val labelPrinterManager = remember { ProductLabelPrinterManager(context) }

    val products by viewModel.products.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val search by viewModel.searchQuery.collectAsState()
    val selectedCatId by viewModel.selectedCategoryId.collectAsState()
    val selectedIds by viewModel.selectedProductIds.collectAsState()
    val quantities by viewModel.labelQuantities.collectAsState()

    var showBarcodeScanner by remember { mutableStateOf(false) }
    var previewProduct by remember { mutableStateOf<Product?>(null) }
    var isPrinting by remember { mutableStateOf(false) }
    var isTestingPrint by remember { mutableStateOf(false) }
    var showCalibrationDialog by remember { mutableStateOf(false) }
    var currentLabelSize by remember { mutableStateOf(labelPrinterManager.getSavedLabelSize()) }
    var currentGapOffset by remember { mutableIntStateOf(labelPrinterManager.getSavedGapOffset()) }
    var currentVerticalOffset by remember { mutableIntStateOf(labelPrinterManager.getSavedVerticalOffset()) }
    var currentProtocol by remember { mutableStateOf(labelPrinterManager.getSavedProtocol()) }
    var settingsSnapshot by remember { mutableStateOf(PrintShareHelper.ShopSettingsSnapshot()) }

    // Load shop settings snapshot
    LaunchedEffect(Unit) {
        settingsSnapshot = PrintShareHelper.loadShopSettingsSnapshot()
    }

    val filtered = remember(products, search, selectedCatId) {
        var list = products
        if (search.isNotBlank()) {
            list = list.filter {
                it.name.contains(search, ignoreCase = true) ||
                        (it.sku?.contains(search, ignoreCase = true) == true)
            }
        }
        if (selectedCatId != null) {
            list = list.filter { it.categoryId == selectedCatId }
        }
        list
    }

    val selectedProductsWithQty = remember(filtered, selectedIds, quantities) {
        filtered.filter { selectedIds.contains(it.id) }.map { prod ->
            prod to (quantities[prod.id] ?: 1)
        }
    }

    val totalLabelsCount = remember(selectedProductsWithQty) {
        selectedProductsWithQty.sumOf { it.second }
    }

    if (showBarcodeScanner) {
        com.matoshree.shopmanager.ui.components.BarcodeScannerDialog(
            title = "बारकोड / एसकेयू स्कॅन करा",
            subtitle = "प्रॉडक्ट शोधण्यासाठी बारकोड कॅमेऱ्यासमोर धरा",
            onBarcodeScanned = { scannedCode ->
                viewModel.searchQuery.value = scannedCode
                showBarcodeScanner = false
                Toast.makeText(context, "स्कॅन झाले: $scannedCode", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showBarcodeScanner = false }
        )
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "बारकोड / प्रॉडक्ट लेबल्स",
                showBackButton = true,
                onBackClick = onBackClick,
                actions = {
                    if (selectedProductsWithQty.isNotEmpty()) {
                        Button(
                            onClick = {
                                if (isPrinting) return@Button
                                isPrinting = true
                                coroutineScope.launch {
                                    try {
                                        val res = labelPrinterManager.printBulkProductLabels(
                                            items = selectedProductsWithQty,
                                            settings = settingsSnapshot
                                        )
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "एकूण $totalLabelsCount लेबल्स यशस्वीरित्या प्रिंट झाली!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, res.exceptionOrNull()?.message ?: "लेबल प्रिंटिंग अयशस्वी", Toast.LENGTH_LONG).show()
                                        }
                                    } finally {
                                        isPrinting = false
                                    }
                                }
                            },
                            enabled = !isPrinting,
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isPrinting) {
                                CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Print, contentDescription = "प्रिंट करा", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("प्रिंट ($totalLabelsCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
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
                .padding(horizontal = 16.dp)
        ) {
            // Search Bar & Barcode Scanner Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { viewModel.searchQuery.value = it },
                    placeholder = { Text("प्रॉडक्टचे नाव किंवा बारकोड सर्च करा…", fontSize = 14.sp) },
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
                    onClick = { showBarcodeScanner = true },
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
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCatId == null,
                    onClick = { viewModel.selectedCategoryId.value = null },
                    label = { Text("सर्व प्रॉडक्ट्स") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DeepEmeraldContainer,
                        selectedLabelColor = WarmWhite
                    )
                )
                categories.forEach { cat ->
                    FilterChip(
                        selected = selectedCatId == cat.id,
                        onClick = { viewModel.selectedCategoryId.value = cat.id },
                        label = { Text(cat.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DeepEmeraldContainer,
                            selectedLabelColor = WarmWhite
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Sticker & Printer Calibration Info Card (HOIN HOP-H58 Custom System)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                color = WarmWhite,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ChampagneGold.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Sell,
                                contentDescription = null,
                                tint = DeepEmerald,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "स्टिकर: ${currentLabelSize.widthMm}x${currentLabelSize.heightMm} मिमी",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = DeepEmerald
                            )
                            if (currentGapOffset != 0) {
                                val offsetMm = String.format(java.util.Locale.US, "%.1f", currentGapOffset / 8.0)
                                val sign = if (currentGapOffset > 0) "+" else ""
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "($sign${offsetMm}mm)",
                                    fontSize = 11.sp,
                                    color = MutedCharcoal,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Text(
                            text = "प्रिंटर: ${if (currentProtocol == LabelPrinterProtocol.ESCPOS_HOIN) "HOIN HOP-H58 (ESC/POS)" else "TSPL (Auto Gap)"}",
                            fontSize = 11.sp,
                            color = MutedCharcoal
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1-Tap Test Print Button
                        OutlinedButton(
                            onClick = {
                                if (isTestingPrint) return@OutlinedButton
                                isTestingPrint = true
                                coroutineScope.launch {
                                    try {
                                        val res = labelPrinterManager.printTestLabel(settingsSnapshot)
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "१ चाचणी स्टिकर प्रिंट झाला!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, res.exceptionOrNull()?.message ?: "चाचणी अयशस्वी", Toast.LENGTH_LONG).show()
                                        }
                                    } finally {
                                        isTestingPrint = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                            modifier = Modifier.height(34.dp),
                            border = BorderStroke(1.dp, DeepEmerald.copy(alpha = 0.5f))
                        ) {
                            if (isTestingPrint) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = DeepEmerald)
                            } else {
                                Text("नमुना प्रिंट", fontSize = 11.sp, color = DeepEmerald, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Settings Adjustment Button
                        Button(
                            onClick = { showCalibrationDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = "कॅलिब्रेशन", modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ॲडजस्ट", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Multi-Select Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val allSelected = filtered.isNotEmpty() && filtered.all { selectedIds.contains(it.id) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { viewModel.selectAll(!allSelected) }
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { viewModel.selectAll(it) }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (allSelected) "निवड रद्द करा" else "सर्व निवडा (${filtered.size})",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }

                if (selectedIds.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("संख्या:", style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal))
                        listOf(1, 5, 10, 20).forEach { bulkQty ->
                            Surface(
                                color = DeepEmeraldContainer.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable { viewModel.setBulkQuantity(bulkQty) }
                            ) {
                                Text(
                                    text = "$bulkQty",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepEmerald,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (selectedIds.isNotEmpty()) {
                Surface(
                    color = DeepEmeraldContainer.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedIds.size} प्रॉडक्ट्स निवडले (एकूण ${totalLabelsCount} लेबल्स)",
                            style = MaterialTheme.typography.labelSmall.copy(color = DeepEmerald, fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "वर उजवीकडे प्रिंट दाबा ↗",
                            style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal, fontSize = 10.sp)
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Products List
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("लेबल छपाईसाठी कोणतेही प्रॉडक्ट सापडले नाही.", color = MutedCharcoal)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filtered, key = { product -> "plabel_${product.id}" }) { product ->
                        val isSelected = selectedIds.contains(product.id)
                        val qty = quantities[product.id] ?: 1

                        ProductLabelCard(
                            product = product,
                            isSelected = isSelected,
                            quantity = qty,
                            settings = settingsSnapshot,
                            onToggleSelect = { viewModel.toggleProductSelection(product.id) },
                            onQuantityChange = { newQty -> viewModel.setQuantity(product.id, newQty) },
                            onPreview = { previewProduct = product },
                            onPrint = {
                                if (isPrinting) return@ProductLabelCard
                                isPrinting = true
                                coroutineScope.launch {
                                    try {
                                        val res = labelPrinterManager.printProductLabel(
                                            product = product,
                                            quantity = qty,
                                            settings = settingsSnapshot
                                        )
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "Printed $qty label(s) for ${product.name}", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, res.exceptionOrNull()?.message ?: "Printing failed", Toast.LENGTH_LONG).show()
                                        }
                                    } finally {
                                        isPrinting = false
                                    }
                                }
                            },
                            onShare = {
                                shareProductLabelJpg(context, product, settingsSnapshot, currentLabelSize)
                            }
                        )
                    }
                }
            }
        }
    }

    // Label Preview Dialog
    if (previewProduct != null) {
        ProductLabelPreviewDialog(
            product = previewProduct!!,
            settings = settingsSnapshot,
            labelSize = currentLabelSize,
            onDismiss = { previewProduct = null },
            onPrint = { qty ->
                coroutineScope.launch {
                    val res = labelPrinterManager.printProductLabel(
                        product = previewProduct!!,
                        quantity = qty,
                        settings = settingsSnapshot
                    )
                    if (res.isSuccess) {
                        Toast.makeText(context, "Printed $qty label(s) for ${previewProduct!!.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, res.exceptionOrNull()?.message ?: "Printing failed", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onShare = {
                shareProductLabelJpg(context, previewProduct!!, settingsSnapshot, currentLabelSize)
            }
        )
    }

    // Sticker & Printer Calibration Dialog
    if (showCalibrationDialog) {
        StickerCalibrationDialog(
            initialSize = currentLabelSize,
            initialGapOffset = currentGapOffset,
            initialVerticalOffset = currentVerticalOffset,
            initialProtocol = currentProtocol,
            onSave = { newSize, newGapOffset, newVertOffset, newProto ->
                labelPrinterManager.saveLabelSize(newSize)
                labelPrinterManager.saveGapOffset(newGapOffset)
                labelPrinterManager.saveVerticalOffset(newVertOffset)
                labelPrinterManager.saveProtocol(newProto)
                currentLabelSize = newSize
                currentGapOffset = newGapOffset
                currentVerticalOffset = newVertOffset
                currentProtocol = newProto
                showCalibrationDialog = false
                Toast.makeText(context, "स्टिकर सेटिंग सेव्ह केली!", Toast.LENGTH_SHORT).show()
            },
            onTestPrint = { testSize, testGapOffset, testVertOffset, testProto ->
                coroutineScope.launch {
                    labelPrinterManager.saveLabelSize(testSize)
                    labelPrinterManager.saveGapOffset(testGapOffset)
                    labelPrinterManager.saveVerticalOffset(testVertOffset)
                    labelPrinterManager.saveProtocol(testProto)
                    currentLabelSize = testSize
                    currentGapOffset = testGapOffset
                    currentVerticalOffset = testVertOffset
                    currentProtocol = testProto
                    val res = labelPrinterManager.printTestLabel(settingsSnapshot)
                    if (res.isSuccess) {
                        Toast.makeText(context, "१ चाचणी स्टिकर प्रिंट झाला!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, res.exceptionOrNull()?.message ?: "चाचणी अयशस्वी", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { showCalibrationDialog = false }
        )
    }
}

@Composable
fun ProductLabelCard(
    product: Product,
    isSelected: Boolean,
    quantity: Int,
    settings: PrintShareHelper.ShopSettingsSnapshot,
    onToggleSelect: () -> Unit,
    onQuantityChange: (Int) -> Unit,
    onPreview: () -> Unit,
    onPrint: () -> Unit,
    onShare: () -> Unit
) {
    MatoshreeCard(
        hasGoldTopBorder = isSelected,
        modifier = Modifier.clickable(onClick = onToggleSelect)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() }
                )
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SKU: ${product.sku ?: "N/A"}",
                            style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontWeight = FontWeight.SemiBold)
                        )
                        if (product.categoryName != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("• ${product.categoryName}", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = CurrencyFormatter.format(product.sellingPrice),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = DeepEmerald),
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            var showCustomQtyDialog by remember { mutableStateOf(false) }
            var customQtyText by remember { mutableStateOf("$quantity") }

            if (showCustomQtyDialog) {
                AlertDialog(
                    onDismissRequest = { showCustomQtyDialog = false },
                    title = { Text("लेबल्सची संख्या टाका", fontWeight = FontWeight.Bold, color = DeepEmerald) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${product.name} साठी किती लेबल्स प्रिंट करायची आहेत?", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            OutlinedTextField(
                                value = customQtyText,
                                onValueChange = { customQtyText = it.filter { c -> c.isDigit() }.take(4) },
                                label = { Text("लेबल संख्या") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val entered = customQtyText.toIntOrNull() ?: 1
                                if (entered > 0) {
                                    onQuantityChange(entered)
                                    showCustomQtyDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                        ) {
                            Text("संख्या निश्चित करा", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCustomQtyDialog = false }) {
                            Text("रद्द करा", color = MutedCharcoal)
                        }
                    },
                    containerColor = WarmWhite
                )
            }

            // Barcode Preview Thumbnail & Quantity Stepper
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WarmIvory, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mini Barcode
                val sku = product.sku ?: "MC-0000"
                val barcodeBmp = remember(sku) {
                    try {
                        BarcodeGenerator.generateCode128BarcodeBitmap(sku, targetWidth = 160, targetHeight = 36)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (barcodeBmp != null) {
                    Image(
                        bitmap = barcodeBmp.asImageBitmap(),
                        contentDescription = "बारकोड",
                        modifier = Modifier
                            .height(26.dp)
                            .width(110.dp)
                    )
                } else {
                    Text(sku, fontSize = 12.sp, color = MutedCharcoal)
                }

                // Quantity Stepper & Quick Chips
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1, 5, 10, 20).forEach { quickQty ->
                        Surface(
                            color = if (quantity == quickQty) DeepEmerald else WarmWhite,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (quantity == quickQty) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.5f)),
                            modifier = Modifier.clickable { onQuantityChange(quickQty) }
                        ) {
                            Text(
                                text = "$quickQty",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (quantity == quickQty) Color.White else DeepEmerald,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Stepper Box with clickable count
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(WarmWhite, RoundedCornerShape(6.dp))
                            .border(1.dp, OutlineVariantGrey.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    ) {
                        IconButton(
                            onClick = { if (quantity > 1) onQuantityChange(quantity - 1) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "कमी करा", modifier = Modifier.size(12.dp), tint = DeepEmerald)
                        }
                        Surface(
                            color = Color.Transparent,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 32.dp)
                                .clickable {
                                    customQtyText = "$quantity"
                                    showCustomQtyDialog = true
                                }
                        ) {
                            Text(
                                text = "$quantity",
                                modifier = Modifier.padding(horizontal = 4.dp),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = DeepEmerald,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        IconButton(
                            onClick = { onQuantityChange(quantity + 1) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "वाढवा", modifier = Modifier.size(12.dp), tint = DeepEmerald)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onPreview,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Icon(Icons.Outlined.Visibility, contentDescription = "प्रिव्ह्यू", modifier = Modifier.size(15.dp), tint = DeepEmerald)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("प्रिव्ह्यू", fontSize = 12.sp, color = DeepEmerald)
                }

                Button(
                    onClick = onPrint,
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Icon(Icons.Default.Print, contentDescription = "प्रिंट करा", modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("प्रिंट ($quantity)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                IconButton(
                    onClick = onShare,
                    modifier = Modifier
                        .size(38.dp)
                        .background(WarmIvory, RoundedCornerShape(8.dp))
                        .border(1.dp, OutlineVariantGrey.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.Share, contentDescription = "शेअर करा", tint = DeepEmerald, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun ProductLabelPreviewDialog(
    product: Product,
    settings: PrintShareHelper.ShopSettingsSnapshot,
    labelSize: LabelSizeConfig = LabelSizeConfig.SIZE_50X38,
    onDismiss: () -> Unit,
    onPrint: (Int) -> Unit,
    onShare: () -> Unit
) {
    var quantity by remember { mutableIntStateOf(1) }
    val labelBitmap = remember(product, settings, labelSize) {
        ProductLabelFormatter.generateProductLabelBitmap(
            product = product,
            settings = settings,
            width = labelSize.totalWidthDots,
            height = labelSize.stickerHeightDots
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("लेबल पूर्वावलोकन (Preview)", fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Accurate Physical Label Render (Pure White with rounded border)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    color = Color.White,
                    shadowElevation = 4.dp
                ) {
                    Image(
                        bitmap = labelBitmap.asImageBitmap(),
                        contentDescription = "लेबल प्रिव्ह्यू",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }

                // Quick Bulk Quantity Chips
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("प्रिंट संख्या:", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1, 5, 10, 20, 50).forEach { qtyChoice ->
                            Surface(
                                color = if (quantity == qtyChoice) DeepEmerald else WarmWhite,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (quantity == qtyChoice) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { quantity = qtyChoice }
                            ) {
                                Text(
                                    text = "$qtyChoice",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (quantity == qtyChoice) Color.White else DeepEmerald,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Custom Quantity Stepper
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("इतर संख्या:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.border(1.dp, OutlineVariantGrey.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    ) {
                        IconButton(
                            onClick = { if (quantity > 1) quantity-- },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "कमी करा", modifier = Modifier.size(16.dp))
                        }
                        Text(
                            text = "$quantity",
                            modifier = Modifier.padding(horizontal = 12.dp),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        IconButton(
                            onClick = { quantity++ },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "वाढवा", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onShare,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = "शेअर करा", modifier = Modifier.size(16.dp), tint = DeepEmerald)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("शेअर JPG", fontSize = 12.sp, color = DeepEmerald)
                }

                Button(
                    onClick = { onPrint(quantity) },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1.2f).height(42.dp)
                ) {
                    Icon(Icons.Default.Print, contentDescription = "प्रिंट करा", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("प्रिंट ($quantity)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("बंद करा", color = MutedCharcoal)
            }
        },
        containerColor = WarmWhite
    )
}

/**
 * Shares high-resolution product label JPG directly to WhatsApp or Android share sheet.
 */
fun shareProductLabelJpg(
    context: Context,
    product: Product,
    settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot(),
    labelSize: LabelSizeConfig = LabelSizeConfig.SIZE_50X38
) {
    try {
        val bitmap = ProductLabelFormatter.generateProductLabelBitmap(
            product = product,
            settings = settings,
            width = labelSize.totalWidthDots,
            height = labelSize.stickerHeightDots
        )
        val cacheDir = File(context.cacheDir, "labels").apply { mkdirs() }
        val cleanName = product.name.replace("[^a-zA-Z0-9]".toRegex(), "_")
        val imageFile = File(cacheDir, "Label_${cleanName}_${product.sku ?: "0"}.jpg")
        val fos = FileOutputStream(imageFile)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        fos.flush()
        fos.close()

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            val shareText = "${settings.shopName}\nप्रॉडक्ट: ${product.name}\nबारकोड: ${product.sku ?: "N/A"}\nकिंमत: ${CurrencyFormatter.format(product.sellingPrice)}"
            putExtra(Intent.EXTRA_SUBJECT, "${product.name} - प्रॉडक्ट लेबल")
            putExtra(Intent.EXTRA_TEXT, shareText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val chooser = Intent.createChooser(intent, "प्रॉडक्ट लेबल शेअर करा")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "लेबल शेअर करता आले नाही: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun StickerCalibrationDialog(
    initialSize: LabelSizeConfig,
    initialGapOffset: Int,
    initialVerticalOffset: Int,
    initialProtocol: LabelPrinterProtocol,
    onSave: (LabelSizeConfig, Int, Int, LabelPrinterProtocol) -> Unit,
    onTestPrint: (LabelSizeConfig, Int, Int, LabelPrinterProtocol) -> Unit,
    onDismiss: () -> Unit
) {
    var size by remember { mutableStateOf(initialSize) }
    var gapOffset by remember { mutableIntStateOf(initialGapOffset) }
    var verticalOffset by remember { mutableIntStateOf(initialVerticalOffset) }
    var protocol by remember { mutableStateOf(initialProtocol) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "स्टिकर व प्रिंटर कॅलिब्रेशन (HOIN HOP-H58)",
                fontWeight = FontWeight.Bold,
                color = DeepEmerald,
                fontSize = 17.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Label Size Selector
                Text("१. स्टिकरचा आकार निवडा:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = DeepEmerald)
                LabelSizeConfig.values().forEach { config ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { size = config },
                        color = if (size == config) DeepEmeraldContainer.copy(alpha = 0.15f) else WarmWhite,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (size == config) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (size == config),
                                onClick = { size = config },
                                colors = RadioButtonDefaults.colors(selectedColor = DeepEmerald)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(config.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text("${config.widthMm}mm रुंदी x ${config.heightMm}mm उंची", fontSize = 11.sp, color = MutedCharcoal)
                            }
                        }
                    }
                }

                // 2. Gap / Feed Fine-tuning
                Text("२. गॅप / रोल फीड ॲडजस्ट करा:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = DeepEmerald)
                Surface(
                    color = WarmWhite,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, OutlineVariantGrey.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        val gapMm = String.format(java.util.Locale.US, "%.1f", gapOffset / 8.0)
                        val sign = if (gapOffset > 0) "+" else ""
                        Text(
                            text = "चालू गॅप फीड: $sign$gapMm मिमी ($gapOffset डॉट्स)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = DeepEmerald
                        )
                        Text(
                            text = "• प्रत्येक प्रिंटनंतर गॅप सरकत असल्यास [-] किंवा [+] दाबा",
                            fontSize = 11.sp,
                            color = MutedCharcoal,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { gapOffset = (gapOffset - 4).coerceAtLeast(-64) },
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("-०.५ mm", fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = { gapOffset = 0 },
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("रीसेट (०)", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { gapOffset = (gapOffset + 4).coerceAtMost(64) },
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("+०.५ mm", fontSize = 11.sp)
                            }
                        }
                    }
                }

                // 3. Vertical Shift / Centering Fine-tuning
                Text("३. प्रिंट वर / खाली पोझिशन ॲडजस्ट करा:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = DeepEmerald)
                Surface(
                    color = WarmWhite,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, OutlineVariantGrey.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        val vertMm = String.format(java.util.Locale.US, "%.1f", verticalOffset / 8.0)
                        val vertSign = if (verticalOffset > 0) "+" else ""
                        Text(
                            text = "चालू पोझिशन: $vertSign$vertMm मिमी ($verticalOffset डॉट्स)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = DeepEmerald
                        )
                        Text(
                            text = "• प्रिंट खाली रिकामी जागा असल्यास [▼ खाली] दाबा\n• प्रिंट जास्त खाली गेल्यास [▲ वर] दाबा",
                            fontSize = 11.sp,
                            color = MutedCharcoal,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { verticalOffset = (verticalOffset - 4).coerceAtLeast(-48) },
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("▲ वर (-०.५ mm)", fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = { verticalOffset = 0 },
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("सेंटर (०)", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { verticalOffset = (verticalOffset + 4).coerceAtMost(48) },
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("▼ खाली (+०.५ mm)", fontSize = 11.sp)
                            }
                        }
                    }
                }

                // 4. Printer Protocol Selector
                Text("४. प्रिंटर कमांड प्रकार:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = DeepEmerald)
                LabelPrinterProtocol.values().forEach { proto ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { protocol = proto },
                        color = if (protocol == proto) DeepEmeraldContainer.copy(alpha = 0.15f) else WarmWhite,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (protocol == proto) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (protocol == proto),
                                onClick = { protocol = proto },
                                colors = RadioButtonDefaults.colors(selectedColor = DeepEmerald)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(proto.title, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { onTestPrint(size, gapOffset, verticalOffset, protocol) },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("नमुना प्रिंट", fontSize = 11.sp, color = DeepEmerald, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { onSave(size, gapOffset, verticalOffset, protocol) },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("सेव्ह करा", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(36.dp)
            ) {
                Text("बंद करा", color = MutedCharcoal, fontSize = 11.sp)
            }
        },
        containerColor = WarmWhite
    )
}

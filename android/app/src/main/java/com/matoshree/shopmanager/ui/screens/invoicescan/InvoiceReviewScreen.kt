package com.matoshree.shopmanager.ui.screens.invoicescan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matoshree.shopmanager.R
import com.matoshree.shopmanager.domain.model.Category
import com.matoshree.shopmanager.domain.model.InvoiceItemDraft
import com.matoshree.shopmanager.domain.model.InvoiceMatchChoice
import com.matoshree.shopmanager.domain.model.PricingMultiplier
import com.matoshree.shopmanager.domain.usecase.InvoicePricingCalculator
import com.matoshree.shopmanager.ui.theme.*

@Composable
fun InvoiceReviewScreen(
    viewModel: InvoiceScanViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLabels: (List<Long>) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showExitDialog by remember { mutableStateOf(false) }
    var selectedItemForCategory by remember { mutableStateOf<InvoiceItemDraft?>(null) }

    // Exit confirmation dialog
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.dialog_unsaved_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.dialog_unsaved_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    onNavigateBack()
                }) {
                    Text(stringResource(R.string.btn_exit), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(stringResource(R.string.btn_stay), color = DeepEmerald)
                }
            }
        )
    }

    // Category Selector Dialog
    if (selectedItemForCategory != null) {
        CategorySelectDialog(
            currentItem = selectedItemForCategory!!,
            existingCategories = uiState.existingCategories,
            onCategorySelected = { newCatName ->
                viewModel.updateItemCategory(selectedItemForCategory!!.id, newCatName)
                selectedItemForCategory = null
            },
            onDismiss = { selectedItemForCategory = null }
        )
    }

    // Success Dialog
    val currentStep = uiState.step
    if (currentStep is ScanStep.Success) {
        val result = currentStep.result
        AlertDialog(
            onDismissRequest = { /* No dismiss outside */ },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_done), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            R.string.label_save_success_format,
                            result.newProductsCount,
                            result.restockedCount,
                            result.newCategoriesCount
                        ),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DeepCharcoal
                    )
                    if (result.savedProductsWithSku.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "तयार झालेले बारकोड:",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepEmerald
                        )
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                            items(result.savedProductsWithSku) { (name, sku) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(sku, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DeepEmerald)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (result.savedProductIds.isNotEmpty()) {
                    Button(
                        onClick = { onNavigateToLabels(result.savedProductIds) },
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.btn_print_labels), color = WarmWhite)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onNavigateBack) {
                    Text(stringResource(R.string.btn_done), color = DeepCharcoal)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DeepEmerald)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showExitDialog = true }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = WarmWhite)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.title_invoice_review),
                            color = WarmWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (uiState.supplierName.isNotBlank() || uiState.invoiceNumber.isNotBlank()) {
                            Text(
                                text = "${uiState.supplierName} ${if (uiState.invoiceNumber.isNotBlank()) "#${uiState.invoiceNumber}" else ""}".trim(),
                                color = WarmWhite.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Global Bar: Multipliers and GST Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DeepEmerald.copy(alpha = 0.85f))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${stringResource(R.string.btn_for_all)}:",
                            color = WarmWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(6.dp))
                        PricingMultiplier.values().forEach { mult ->
                            val isSelected = uiState.globalMultiplier == mult
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) WarmWhite else Color.White.copy(alpha = 0.2f))
                                    .clickable { viewModel.updateGlobalMultiplier(mult) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = mult.label,
                                    color = if (isSelected) DeepEmerald else WarmWhite,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // GST Toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { viewModel.toggleGstIncluded() }
                    ) {
                        Checkbox(
                            checked = uiState.isGstIncluded,
                            onCheckedChange = { viewModel.toggleGstIncluded() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = WarmWhite,
                                checkmarkColor = DeepEmerald,
                                uncheckedColor = WarmWhite.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.label_gst_included),
                            color = WarmWhite,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        bottomBar = {
            // Summary and Save Action Footer
            val includedItems = uiState.items.filter { it.isIncluded }
            val totalPurchase = includedItems.sumOf { it.quantity * it.unitPrice }
            val newProds = includedItems.count { it.matchChoice == InvoiceMatchChoice.NEW_PRODUCT }
            val restocks = includedItems.count { it.matchChoice == InvoiceMatchChoice.RESTOCK }
            val isSaving = uiState.step is ScanStep.Saving

            Surface(
                color = WarmWhite,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${includedItems.size} वस्तू (${newProds} नवीन, ${restocks} स्टॉक)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal
                            )
                            Text(
                                text = "एकूण खरेदी: ₹${InvoicePricingCalculator.formatPrice(totalPurchase)}",
                                fontSize = 12.sp,
                                color = MutedCharcoal
                            )
                        }

                        Button(
                            onClick = { viewModel.saveAll() },
                            enabled = !isSaving && includedItems.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(44.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("सेव्ह करत आहे...", fontSize = 14.sp)
                            } else {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.btn_save_all),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (uiState.errorMessage != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = uiState.errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
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
        ) {
            // Duplicate scan banner if detected
            if (uiState.isDuplicateBillWarning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF3CD))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFF856404), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.label_duplicate_scanned_warning),
                        color = Color(0xFF856404),
                        fontSize = 12.sp
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.items, key = { it.id }) { item ->
                    InvoiceItemCard(
                        item = item,
                        onNameChange = { viewModel.updateItemName(item.id, it) },
                        onQuantityChange = { viewModel.updateItemQuantity(item.id, it) },
                        onPurchasePriceChange = { viewModel.updateItemPurchasePrice(item.id, it) },
                        onMultiplierChange = { viewModel.updateItemMultiplier(item.id, it) },
                        onSellingPriceChange = { viewModel.updateItemSellingPrice(item.id, it) },
                        onCategoryClick = { selectedItemForCategory = item },
                        onMatchChoiceChange = { viewModel.updateItemMatchChoice(item.id, it) },
                        onToggleIncluded = { viewModel.toggleItemInclusion(item.id) },
                        onToggleCostPriceUpdate = { viewModel.toggleUpdateCostPrice(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InvoiceItemCard(
    item: InvoiceItemDraft,
    onNameChange: (String) -> Unit,
    onQuantityChange: (Int) -> Unit,
    onPurchasePriceChange: (Double) -> Unit,
    onMultiplierChange: (PricingMultiplier) -> Unit,
    onSellingPriceChange: (Double) -> Unit,
    onCategoryClick: () -> Unit,
    onMatchChoiceChange: (InvoiceMatchChoice) -> Unit,
    onToggleIncluded: () -> Unit,
    onToggleCostPriceUpdate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = WarmWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // Header Row: Checkbox, Name, Category Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = item.isIncluded,
                    onCheckedChange = { onToggleIncluded() },
                    colors = CheckboxDefaults.colors(checkedColor = DeepEmerald),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))

                OutlinedTextField(
                    value = item.name,
                    onValueChange = onNameChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DeepEmerald,
                        unfocusedBorderColor = Color.LightGray
                    )
                )

                Spacer(Modifier.width(8.dp))

                // Category Chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (item.isNewCategory) Color(0xFFE8F5E9) else Color(0xFFEDE7F6))
                        .border(
                            1.dp,
                            if (item.isNewCategory) DeepEmerald else Color(0xFF7E57C2),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { onCategoryClick() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${item.categoryName.ifBlank { "General" }} (${if (item.isNewCategory) "नवीन" else "आहे"})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.isNewCategory) DeepEmerald else Color(0xFF512DA8)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Quantities, Rates, Multiplier Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Quantity
                OutlinedTextField(
                    value = item.quantity.toString(),
                    onValueChange = { str -> onQuantityChange(str.toIntOrNull() ?: 0) },
                    label = { Text(stringResource(R.string.label_quantity), fontSize = 10.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(0.9f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )

                // Purchase Price
                OutlinedTextField(
                    value = if (item.unitPrice > 0.0) InvoicePricingCalculator.formatPrice(item.unitPrice) else "",
                    onValueChange = { str -> onPurchasePriceChange(str.toDoubleOrNull() ?: 0.0) },
                    label = { Text(stringResource(R.string.label_purchase_price), fontSize = 10.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1.2f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )

                // Selling Price
                OutlinedTextField(
                    value = if (item.sellingPrice > 0.0) InvoicePricingCalculator.formatPrice(item.sellingPrice) else "",
                    onValueChange = { str -> onSellingPriceChange(str.toDoubleOrNull() ?: 0.0) },
                    label = {
                        Text(
                            text = if (item.isManualPrice) "${stringResource(R.string.label_selling_price)} (${stringResource(R.string.label_manual_edit)})"
                            else stringResource(R.string.label_selling_price),
                            fontSize = 10.sp
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1.3f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = DeepEmerald
                    )
                )
            }

            Spacer(Modifier.height(8.dp))

            // Multiplier Chips & Profit Display Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PricingMultiplier.values().forEach { mult ->
                        val isSelected = !item.isManualPrice && item.multiplier == mult
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) DeepEmerald else Color.LightGray.copy(alpha = 0.4f))
                                .clickable { onMultiplierChange(mult) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = mult.label,
                                color = if (isSelected) WarmWhite else DeepCharcoal,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // Profit preview
                val profit = InvoicePricingCalculator.calculateProfit(item.unitPrice, item.sellingPrice)
                val margin = InvoicePricingCalculator.calculateMarginPercent(item.unitPrice, item.sellingPrice)
                Text(
                    text = "नफा: ₹${InvoicePricingCalculator.formatPrice(profit)} ($margin%)",
                    fontSize = 11.sp,
                    color = if (profit >= 0.0) DeepEmerald else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Duplicate Match Resolution (Restock vs New)
            if (item.matchedProductId != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarmIvory, RoundedCornerShape(6.dp))
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "शॉपमधील प्रॉडक्ट: ${item.matchedProductName ?: ""}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DeepCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.matchChoice == InvoiceMatchChoice.RESTOCK) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { onToggleCostPriceUpdate() }
                            ) {
                                Checkbox(
                                    checked = item.updateCostPriceOnRestock,
                                    onCheckedChange = { onToggleCostPriceUpdate() },
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("खरेदी दर अपडेट करा", fontSize = 10.sp, color = MutedCharcoal)
                            }
                        }
                    }

                    Row {
                        FilterChip(
                            selected = item.matchChoice == InvoiceMatchChoice.RESTOCK,
                            onClick = { onMatchChoiceChange(InvoiceMatchChoice.RESTOCK) },
                            label = { Text(stringResource(R.string.btn_restock), fontSize = 10.sp) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        FilterChip(
                            selected = item.matchChoice == InvoiceMatchChoice.NEW_PRODUCT,
                            onClick = { onMatchChoiceChange(InvoiceMatchChoice.NEW_PRODUCT) },
                            label = { Text(stringResource(R.string.btn_new_product), fontSize = 10.sp) }
                        )
                    }
                }
            }

            // Validation Flags
            if (item.validationFlags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Column {
                    item.validationFlags.forEach { flag ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 1.dp)
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = if (flag.isWarningOnly) Color(0xFFE65100) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = flag.messageMarathi,
                                color = if (flag.isWarningOnly) Color(0xFFE65100) else MaterialTheme.colorScheme.error,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySelectDialog(
    currentItem: InvoiceItemDraft,
    existingCategories: List<Category>,
    onCategorySelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var typedCategory by remember { mutableStateOf(currentItem.categoryName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("कॅटेगरी निवडा", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = typedCategory,
                    onValueChange = { typedCategory = it },
                    label = { Text("कॅटेगरीचे नाव") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("आधीपासून असलेल्या कॅटेगरीज:", fontSize = 12.sp, color = MutedCharcoal)
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(existingCategories) { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { typedCategory = cat.name }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(cat.name, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCategorySelected(typedCategory.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
            ) {
                Text("निवडा", color = WarmWhite)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel), color = DeepCharcoal)
            }
        }
    )
}

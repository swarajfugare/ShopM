package com.matoshree.shopmanager.ui.screens.invoicescan

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.ai.FirebaseVertexAiClient
import com.matoshree.shopmanager.data.ai.InvoiceAiClient
import com.matoshree.shopmanager.data.repository.ProductRepository
import com.matoshree.shopmanager.domain.model.Category
import com.matoshree.shopmanager.domain.model.InvoiceExtractionResult
import com.matoshree.shopmanager.domain.model.InvoiceItemDraft
import com.matoshree.shopmanager.domain.model.InvoiceMatchChoice
import com.matoshree.shopmanager.domain.model.PricingMultiplier
import com.matoshree.shopmanager.domain.model.Product
import com.matoshree.shopmanager.domain.usecase.CategoryMatcher
import com.matoshree.shopmanager.domain.usecase.DuplicateProductMatcher
import com.matoshree.shopmanager.domain.usecase.InvoiceFingerprint
import com.matoshree.shopmanager.domain.usecase.InvoicePricingCalculator
import com.matoshree.shopmanager.domain.usecase.InvoiceSavePipeline
import com.matoshree.shopmanager.domain.usecase.InvoiceValidator
import com.matoshree.shopmanager.domain.usecase.SaveProgress
import com.matoshree.shopmanager.domain.usecase.SaveResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed class ScanStep {
    object Capture : ScanStep()
    data class ProcessingAi(val progressMessage: String) : ScanStep()
    object Review : ScanStep()
    data class Saving(val progress: SaveProgress) : ScanStep()
    data class Success(val result: SaveResult) : ScanStep()
    data class Error(val message: String, val canRetry: Boolean) : ScanStep()
}

data class InvoiceScanUiState(
    val step: ScanStep = ScanStep.Capture,
    val capturedImages: List<Bitmap> = emptyList(),
    val draftId: String = UUID.randomUUID().toString(),
    val supplierName: String = "",
    val invoiceNumber: String = "",
    val invoiceDate: String = "",
    val invoiceTotal: Double? = null,
    val isGstIncluded: Boolean = true,
    val items: List<InvoiceItemDraft> = emptyList(),
    val existingCategories: List<Category> = emptyList(),
    val existingProducts: List<Product> = emptyList(),
    val globalMultiplier: PricingMultiplier = PricingMultiplier.TWO_X,
    val alreadySavedItemIds: Set<String> = emptySet(),
    val isDuplicateBillWarning: Boolean = false,
    val errorMessage: String? = null
)

class InvoiceScanViewModel(
    private val productRepository: ProductRepository,
    private val aiClient: InvoiceAiClient = FirebaseVertexAiClient(),
    private val savePipeline: InvoiceSavePipeline = InvoiceSavePipeline(productRepository)
) : ViewModel() {

    private val TAG = "InvoiceScanVM"
    private val _uiState = MutableStateFlow(InvoiceScanUiState())
    val uiState: StateFlow<InvoiceScanUiState> = _uiState.asStateFlow()

    // Local in-memory set of fingerprints to warn on re-scans in this session
    private val scannedFingerprints = mutableSetOf<String>()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                productRepository.getAllCategories()
                    .catch { e -> Log.w(TAG, "Categories load note: ${e.message}") }
                    .collect { cats ->
                        _uiState.update { it.copy(existingCategories = cats) }
                    }
            } catch (e: Throwable) {
                Log.w(TAG, "Categories flow unavailable: ${e.message}")
            }
        }
        viewModelScope.launch {
            try {
                productRepository.getAllProducts()
                    .catch { e -> Log.w(TAG, "Products load note: ${e.message}") }
                    .collect { prods ->
                        _uiState.update { it.copy(existingProducts = prods) }
                    }
            } catch (e: Throwable) {
                Log.w(TAG, "Products flow unavailable: ${e.message}")
            }
        }
    }

    fun addImage(bitmap: Bitmap) {
        if (_uiState.value.capturedImages.size >= 6) return
        _uiState.update {
            it.copy(capturedImages = it.capturedImages + bitmap)
        }
    }

    fun removeImage(index: Int) {
        if (index in _uiState.value.capturedImages.indices) {
            val list = _uiState.value.capturedImages.toMutableList()
            list.removeAt(index)
            _uiState.update { it.copy(capturedImages = list) }
        }
    }

    fun moveImage(from: Int, to: Int) {
        val list = _uiState.value.capturedImages.toMutableList()
        if (from in list.indices && to in list.indices) {
            val item = list.removeAt(from)
            list.add(to, item)
            _uiState.update { it.copy(capturedImages = list) }
        }
    }

    fun startAiExtraction() {
        val images = _uiState.value.capturedImages
        if (images.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "किमान एक फोटो आवश्यक आहे") }
            return
        }

        _uiState.update {
            it.copy(
                step = ScanStep.ProcessingAi("AI बिल तपासत आहे..."),
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val cats = _uiState.value.existingCategories.map { it.name }
            val result = aiClient.extractInvoice(images, cats)

            if (result.isFailure) {
                val err = result.exceptionOrNull()?.message ?: "स्कॅन करताना त्रुटी आली"
                _uiState.update {
                    it.copy(
                        step = ScanStep.Error(err, canRetry = true),
                        errorMessage = err
                    )
                }
                return@launch
            }

            val extracted = result.getOrNull() ?: InvoiceExtractionResult()
            processExtractionResult(extracted)
        }
    }

    private fun processExtractionResult(extracted: InvoiceExtractionResult) {
        val existingCats = _uiState.value.existingCategories
        val existingProds = _uiState.value.existingProducts

        // Check fingerprint
        val fp = InvoiceFingerprint.generateFingerprint(
            supplierName = extracted.supplierName,
            invoiceNumber = extracted.invoiceNumber,
            invoiceDate = extracted.invoiceDate,
            invoiceTotal = extracted.invoiceTotal
        )
        val isDuplicate = scannedFingerprints.contains(fp)
        scannedFingerprints.add(fp)

        // Enhance items with CategoryMatcher and DuplicateProductMatcher
        val enhancedItems = extracted.items.map { draft ->
            val catMatch = CategoryMatcher.matchCategory(draft.categoryName, existingCats)
            val prodMatch = DuplicateProductMatcher.match(draft.name, existingProds)

            val matchChoice = if (prodMatch.isStrongMatch && prodMatch.matchedProduct != null) {
                InvoiceMatchChoice.RESTOCK
            } else {
                InvoiceMatchChoice.NEW_PRODUCT
            }

            val initialMultiplier = PricingMultiplier.TWO_X
            val sellingPrice = InvoicePricingCalculator.calculateSellingPrice(draft.unitPrice, initialMultiplier)

            draft.copy(
                categoryName = catMatch.effectiveName,
                isNewCategory = catMatch.isNew,
                matchChoice = matchChoice,
                matchedProductId = prodMatch.matchedProduct?.id,
                matchedProductName = prodMatch.matchedProduct?.name,
                matchedProductSku = prodMatch.matchedProduct?.sku,
                multiplier = initialMultiplier,
                sellingPrice = sellingPrice,
                isManualPrice = false,
                isIncluded = true
            )
        }

        _uiState.update {
            it.copy(
                step = ScanStep.Review,
                supplierName = extracted.supplierName ?: "",
                invoiceNumber = extracted.invoiceNumber ?: "",
                invoiceDate = extracted.invoiceDate ?: "",
                invoiceTotal = extracted.invoiceTotal,
                items = enhancedItems,
                isDuplicateBillWarning = isDuplicate,
                errorMessage = null
            )
        }
    }

    fun updateGlobalMultiplier(multiplier: PricingMultiplier) {
        _uiState.update { state ->
            val updated = state.items.map { item ->
                if (!item.isManualPrice) {
                    val newPrice = InvoicePricingCalculator.calculateSellingPrice(item.unitPrice, multiplier)
                    item.copy(multiplier = multiplier, sellingPrice = newPrice)
                } else {
                    item
                }
            }
            state.copy(globalMultiplier = multiplier, items = updated)
        }
    }

    fun updateItemMultiplier(itemId: String, multiplier: PricingMultiplier) {
        _uiState.update { state ->
            val updated = state.items.map { item ->
                if (item.id == itemId) {
                    val newPrice = InvoicePricingCalculator.calculateSellingPrice(item.unitPrice, multiplier)
                    item.copy(multiplier = multiplier, sellingPrice = newPrice, isManualPrice = false)
                } else {
                    item
                }
            }
            state.copy(items = updated)
        }
    }

    fun updateItemSellingPrice(itemId: String, newPrice: Double) {
        _uiState.update { state ->
            val updated = state.items.map { item ->
                if (item.id == itemId) {
                    item.copy(sellingPrice = newPrice, isManualPrice = true)
                } else {
                    item
                }
            }
            state.copy(items = updated)
        }
    }

    fun updateItemName(itemId: String, name: String) {
        _uiState.update { state ->
            val existingProds = state.existingProducts
            val updated = state.items.map { item ->
                if (item.id == itemId) {
                    val prodMatch = DuplicateProductMatcher.match(name, existingProds)
                    val matchChoice = if (prodMatch.isStrongMatch && prodMatch.matchedProduct != null) {
                        InvoiceMatchChoice.RESTOCK
                    } else {
                        item.matchChoice
                    }
                    val flags = InvoiceValidator.validateItem(item.copy(name = name), state.items)
                    item.copy(
                        name = name,
                        matchChoice = matchChoice,
                        matchedProductId = prodMatch.matchedProduct?.id,
                        matchedProductName = prodMatch.matchedProduct?.name,
                        matchedProductSku = prodMatch.matchedProduct?.sku,
                        validationFlags = flags
                    )
                } else {
                    item
                }
            }
            state.copy(items = updated)
        }
    }

    fun updateItemQuantity(itemId: String, qty: Int) {
        _uiState.update { state ->
            val updated = state.items.map { item ->
                if (item.id == itemId) {
                    val safeQty = qty.coerceAtLeast(1)
                    val newTotal = safeQty * item.unitPrice
                    val flags = InvoiceValidator.validateItem(item.copy(quantity = safeQty, lineTotal = newTotal), state.items)
                    item.copy(quantity = safeQty, lineTotal = newTotal, validationFlags = flags)
                } else {
                    item
                }
            }
            state.copy(items = updated)
        }
    }

    fun updateItemPurchasePrice(itemId: String, price: Double) {
        _uiState.update { state ->
            val updated = state.items.map { item ->
                if (item.id == itemId) {
                    val safePrice = price.coerceAtLeast(0.0)
                    val newTotal = item.quantity * safePrice
                    val newSellingPrice = if (!item.isManualPrice) {
                        InvoicePricingCalculator.calculateSellingPrice(safePrice, item.multiplier)
                    } else item.sellingPrice
                    val flags = InvoiceValidator.validateItem(item.copy(unitPrice = safePrice, lineTotal = newTotal), state.items)
                    item.copy(unitPrice = safePrice, lineTotal = newTotal, sellingPrice = newSellingPrice, validationFlags = flags)
                } else {
                    item
                }
            }
            state.copy(items = updated)
        }
    }

    fun updateItemCategory(itemId: String, categoryName: String) {
        _uiState.update { state ->
            val match = CategoryMatcher.matchCategory(categoryName, state.existingCategories)
            val updated = state.items.map { item ->
                if (item.id == itemId) {
                    item.copy(categoryName = match.effectiveName, isNewCategory = match.isNew)
                } else {
                    item
                }
            }
            state.copy(items = updated)
        }
    }

    fun updateItemMatchChoice(itemId: String, choice: InvoiceMatchChoice) {
        _uiState.update { state ->
            val updated = state.items.map { item ->
                if (item.id == itemId) {
                    item.copy(matchChoice = choice)
                } else {
                    item
                }
            }
            state.copy(items = updated)
        }
    }

    fun toggleItemInclusion(itemId: String) {
        _uiState.update { state ->
            val updated = state.items.map { item ->
                if (item.id == itemId) {
                    item.copy(isIncluded = !item.isIncluded)
                } else {
                    item
                }
            }
            state.copy(items = updated)
        }
    }

    fun toggleUpdateCostPrice(itemId: String) {
        _uiState.update { state ->
            val updated = state.items.map { item ->
                if (item.id == itemId) {
                    item.copy(updateCostPriceOnRestock = !item.updateCostPriceOnRestock)
                } else {
                    item
                }
            }
            state.copy(items = updated)
        }
    }

    fun toggleGstIncluded() {
        _uiState.update { it.copy(isGstIncluded = !it.isGstIncluded) }
    }

    fun backToCapture() {
        _uiState.update { it.copy(step = ScanStep.Capture) }
    }

    fun saveAll() {
        val state = _uiState.value
        val itemsToSave = state.items.filter { it.isIncluded && !state.alreadySavedItemIds.contains(it.id) }
        if (itemsToSave.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "सेव्ह करण्यासाठी वस्तू निवडलेली नाही") }
            return
        }

        // Validate items before saving: no blank names, no qty <= 0
        val invalidItems = itemsToSave.filter { it.name.trim().isBlank() || it.quantity <= 0 }
        if (invalidItems.isNotEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "काही वस्तूंमध्ये नाव किंवा प्रमाण भरलेले नाही. कृपया दुरुस्त करा.")
            }
            return
        }

        _uiState.update {
            it.copy(
                step = ScanStep.Saving(SaveProgress(itemsToSave.size, 0, "")),
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val result = savePipeline.execute(
                draftId = state.draftId,
                supplierName = state.supplierName,
                invoiceNumber = state.invoiceNumber,
                items = state.items,
                existingCategories = state.existingCategories,
                existingProducts = state.existingProducts,
                alreadySavedItemIds = state.alreadySavedItemIds,
                onProgress = { progress ->
                    _uiState.update { it.copy(step = ScanStep.Saving(progress)) }
                }
            )

            if (result.success) {
                _uiState.update {
                    it.copy(
                        step = ScanStep.Success(result),
                        alreadySavedItemIds = result.savedItemIds
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        step = ScanStep.Review,
                        alreadySavedItemIds = result.savedItemIds,
                        errorMessage = result.errorMessage ?: "काही वस्तू सेव्ह करता आल्या नाहीत. पुन्हा प्रयत्न करा."
                    )
                }
            }
        }
    }
}

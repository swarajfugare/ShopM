package com.matoshree.shopmanager.domain.usecase

import android.util.Log
import com.matoshree.shopmanager.data.repository.ProductRepository
import com.matoshree.shopmanager.domain.model.Category
import com.matoshree.shopmanager.domain.model.InvoiceItemDraft
import com.matoshree.shopmanager.domain.model.InvoiceMatchChoice
import com.matoshree.shopmanager.domain.model.Product
import com.matoshree.shopmanager.domain.model.StockChangeType
import com.matoshree.shopmanager.domain.model.StockMovement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

interface ProductOperationsGateway {
    suspend fun saveProduct(product: Product): Long
    suspend fun saveCategory(name: String, description: String? = null): Long
    suspend fun adjustStock(
        productId: Long,
        delta: Int,
        newExactStock: Int? = null,
        type: StockChangeType = StockChangeType.RESTOCK,
        note: String? = null,
        referenceId: String? = null
    ): Result<Unit>
    suspend fun updateProductPrice(productId: Long, sellingPrice: Double, costPrice: Double?): Boolean
    suspend fun recordStockMovement(movement: StockMovement): Result<Unit>
}

class DefaultProductOperationsGateway(
    private val productRepository: ProductRepository
) : ProductOperationsGateway {
    override suspend fun saveProduct(product: Product): Long = productRepository.saveProduct(product)
    override suspend fun saveCategory(name: String, description: String?): Long = productRepository.saveCategory(name, description)
    override suspend fun adjustStock(
        productId: Long,
        delta: Int,
        newExactStock: Int?,
        type: StockChangeType,
        note: String?,
        referenceId: String?
    ): Result<Unit> = productRepository.adjustStock(productId, delta, newExactStock, type, note, referenceId)
    override suspend fun updateProductPrice(productId: Long, sellingPrice: Double, costPrice: Double?): Boolean =
        productRepository.updateProductPrice(productId, sellingPrice, costPrice)
    override suspend fun recordStockMovement(movement: StockMovement): Result<Unit> =
        productRepository.recordStockMovement(movement)
}

data class SaveProgress(
    val totalItems: Int,
    val savedCount: Int,
    val currentItemName: String
)

data class SaveResult(
    val success: Boolean,
    val newProductsCount: Int,
    val restockedCount: Int,
    val newCategoriesCount: Int,
    val savedProductIds: List<Long>,
    val savedProductsWithSku: List<Pair<String, String>>, // Pair(Name, Sku)
    val savedItemIds: Set<String>,
    val failedItemIds: List<String>,
    val errorMessage: String? = null
)

class InvoiceSavePipeline(
    private val gateway: ProductOperationsGateway,
    private val barcodeAllocator: BarcodeAllocator = BarcodeAllocator()
) {
    constructor(
        productRepository: ProductRepository,
        barcodeAllocator: BarcodeAllocator = BarcodeAllocator()
    ) : this(DefaultProductOperationsGateway(productRepository), barcodeAllocator)

    private val TAG = "InvoiceSavePipeline"

    suspend fun execute(
        draftId: String,
        supplierName: String?,
        invoiceNumber: String?,
        items: List<InvoiceItemDraft>,
        existingCategories: List<Category>,
        existingProducts: List<Product>,
        alreadySavedItemIds: Set<String> = emptySet(),
        onProgress: (SaveProgress) -> Unit = {}
    ): SaveResult = withContext(Dispatchers.IO) {
        val includedItems = items.filter { it.isIncluded && !alreadySavedItemIds.contains(it.id) }
        if (includedItems.isEmpty()) {
            return@withContext SaveResult(
                success = true,
                newProductsCount = 0,
                restockedCount = 0,
                newCategoriesCount = 0,
                savedProductIds = emptyList(),
                savedProductsWithSku = emptyList(),
                savedItemIds = alreadySavedItemIds,
                failedItemIds = emptyList()
            )
        }

        val noteText = buildString {
            append("बिल स्कॅन")
            if (!supplierName.isNullOrBlank()) append(" $supplierName")
            if (!invoiceNumber.isNullOrBlank()) append(" $invoiceNumber")
        }.trim()

        val mutableSavedItemIds = alreadySavedItemIds.toMutableSet()
        val failedItemIds = mutableListOf<String>()
        val savedProductIds = mutableListOf<Long>()
        val savedProductsWithSku = mutableListOf<Pair<String, String>>()
        var newCategoriesCount = 0
        var restockedCount = 0
        var newProductsCount = 0

        // Step 1: Resolve and create new categories idempotently
        val categoryMap = mutableMapOf<String, Long>() // normalized -> categoryId
        existingCategories.forEach { cat ->
            categoryMap[CategoryMatcher.normalize(cat.name)] = cat.id
        }

        val newCategoryDrafts = includedItems
            .filter { it.isNewCategory && it.categoryName.isNotBlank() }
            .distinctBy { CategoryMatcher.normalize(it.categoryName) }

        for (catDraft in newCategoryDrafts) {
            val norm = CategoryMatcher.normalize(catDraft.categoryName)
            if (!categoryMap.containsKey(norm)) {
                try {
                    val newCatId = gateway.saveCategory(catDraft.categoryName.trim())
                    categoryMap[norm] = newCatId
                    newCategoriesCount++
                    Log.d(TAG, "Created new category: ${catDraft.categoryName} with id $newCatId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create category ${catDraft.categoryName}: ${e.message}", e)
                }
            }
        }

        // Step 2: Track existing SKUs across all products for barcode uniqueness
        val allKnownSkus = existingProducts.mapNotNull { it.sku?.trim()?.ifBlank { null } }.toMutableSet()

        // Step 3: Process items
        val totalCount = includedItems.size
        var processedCount = 0

        for (item in includedItems) {
            processedCount++
            onProgress(SaveProgress(totalCount, processedCount, item.name))

            // Validate that item has name and qty > 0
            if (item.name.isBlank() || item.quantity <= 0) {
                failedItemIds.add(item.id)
                continue
            }

            try {
                if (item.matchChoice == InvoiceMatchChoice.RESTOCK && item.matchedProductId != null) {
                    // RESTOCK existing product
                    val adjustResult = gateway.adjustStock(
                        productId = item.matchedProductId,
                        delta = item.quantity,
                        type = StockChangeType.RESTOCK,
                        note = noteText,
                        referenceId = draftId
                    )

                    if (adjustResult.isSuccess) {
                        restockedCount++
                        mutableSavedItemIds.add(item.id)
                        savedProductIds.add(item.matchedProductId)

                        // Update cost price if checked
                        if (item.updateCostPriceOnRestock && item.unitPrice > 0.0) {
                            val matchedProd = existingProducts.find { it.id == item.matchedProductId }
                            val sellingPrice = matchedProd?.sellingPrice ?: item.sellingPrice
                            gateway.updateProductPrice(item.matchedProductId, sellingPrice, item.unitPrice)
                        }
                    } else {
                        failedItemIds.add(item.id)
                    }
                } else {
                    // NEW PRODUCT creation
                    val skuResult = barcodeAllocator.allocateSku(allKnownSkus)
                    if (skuResult.isFailure) {
                        Log.e(TAG, "Barcode allocation failed for ${item.name}: ${skuResult.exceptionOrNull()?.message}")
                        failedItemIds.add(item.id)
                        continue
                    }
                    val allocatedSku = skuResult.getOrThrow()
                    allKnownSkus.add(allocatedSku)

                    // Find category ID
                    val catId = if (item.categoryName.isNotBlank()) {
                        categoryMap[CategoryMatcher.normalize(item.categoryName)]
                    } else null

                    val newProduct = Product(
                        name = item.name.trim(),
                        sku = allocatedSku,
                        sellingPrice = item.sellingPrice,
                        costPrice = item.unitPrice,
                        categoryId = catId,
                        categoryName = item.categoryName.trim().ifBlank { null },
                        trackInventory = true,
                        currentStock = item.quantity,
                        totalStockAdded = item.quantity,
                        isActive = true
                    )

                    val savedId = gateway.saveProduct(newProduct)
                    if (savedId > 0) {
                        newProductsCount++
                        mutableSavedItemIds.add(item.id)
                        savedProductIds.add(savedId)
                        savedProductsWithSku.add(Pair(newProduct.name, allocatedSku))

                        // Record initial stock movement
                        val movement = StockMovement(
                            id = "sm_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}",
                            productId = savedId,
                            productName = newProduct.name,
                            sku = allocatedSku,
                            changeType = StockChangeType.RESTOCK,
                            quantityDelta = item.quantity,
                            stockAfter = item.quantity,
                            note = noteText,
                            referenceId = draftId,
                            timestamp = System.currentTimeMillis()
                        )
                        gateway.recordStockMovement(movement)
                    } else {
                        failedItemIds.add(item.id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving item ${item.name}: ${e.message}", e)
                failedItemIds.add(item.id)
            }
        }

        val allSuccess = failedItemIds.isEmpty()
        SaveResult(
            success = allSuccess,
            newProductsCount = newProductsCount,
            restockedCount = restockedCount,
            newCategoriesCount = newCategoriesCount,
            savedProductIds = savedProductIds,
            savedProductsWithSku = savedProductsWithSku,
            savedItemIds = mutableSavedItemIds,
            failedItemIds = failedItemIds,
            errorMessage = if (!allSuccess) "${failedItemIds.size} वस्तू सेव्ह करताना त्रुटी आली" else null
        )
    }
}

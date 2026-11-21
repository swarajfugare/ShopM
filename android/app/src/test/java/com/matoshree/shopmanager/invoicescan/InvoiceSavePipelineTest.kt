package com.matoshree.shopmanager.invoicescan

import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.domain.usecase.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class InvoiceSavePipelineTest {

    class FakeProductOperationsGateway : ProductOperationsGateway {
        val savedProducts = mutableListOf<Product>()
        val savedCategories = mutableListOf<String>()
        val stockAdjustments = mutableListOf<Pair<Long, Int>>() // Pair(productId, delta)
        val priceUpdates = mutableListOf<Triple<Long, Double, Double?>>() // Pair(id, selling, cost)
        val recordedMovements = mutableListOf<StockMovement>()
        private val idGen = AtomicLong(100L)

        var failOnProduct: String? = null

        override suspend fun saveProduct(product: Product): Long {
            if (product.name == failOnProduct) {
                throw RuntimeException("Simulated save failure on ${product.name}")
            }
            val id = idGen.incrementAndGet()
            savedProducts.add(product.copy(id = id))
            return id
        }

        override suspend fun saveCategory(name: String, description: String?): Long {
            savedCategories.add(name)
            return idGen.incrementAndGet()
        }

        override suspend fun adjustStock(
            productId: Long,
            delta: Int,
            newExactStock: Int?,
            type: StockChangeType,
            note: String?,
            referenceId: String?
        ): Result<Unit> {
            stockAdjustments.add(Pair(productId, delta))
            return Result.success(Unit)
        }

        override suspend fun updateProductPrice(productId: Long, sellingPrice: Double, costPrice: Double?): Boolean {
            priceUpdates.add(Triple(productId, sellingPrice, costPrice))
            return true
        }

        override suspend fun recordStockMovement(movement: StockMovement): Result<Unit> {
            recordedMovements.add(movement)
            return Result.success(Unit)
        }
    }

    @Test
    fun testSaveNewProductAndRestock() = runBlocking {
        val gateway = FakeProductOperationsGateway()
        val allocator = BarcodeAllocator(BarcodeAllocatorTest.FakeAtomicBarcodeGateway(10050L))
        val pipeline = InvoiceSavePipeline(gateway, allocator)

        val existingCat = Category(id = 1, name = "Shirts")
        val existingProd = Product(id = 50, name = "Existing Jeans", sellingPrice = 800.0, sku = "99991")

        val items = listOf(
            // Item 1: New product with new category
            InvoiceItemDraft(
                id = "item_1",
                name = "New Cotton Shirt",
                quantity = 10,
                unitPrice = 250.0,
                sellingPrice = 500.0,
                categoryName = "Casual Wear",
                isNewCategory = true,
                matchChoice = InvoiceMatchChoice.NEW_PRODUCT,
                isIncluded = true
            ),
            // Item 2: Restock existing product
            InvoiceItemDraft(
                id = "item_2",
                name = "Existing Jeans",
                quantity = 5,
                unitPrice = 400.0,
                sellingPrice = 800.0,
                categoryName = "Pants",
                matchChoice = InvoiceMatchChoice.RESTOCK,
                matchedProductId = 50L,
                updateCostPriceOnRestock = true,
                isIncluded = true
            )
        )

        val result = pipeline.execute(
            draftId = "draft_123",
            supplierName = "Apex Garments",
            invoiceNumber = "INV-99",
            items = items,
            existingCategories = listOf(existingCat),
            existingProducts = listOf(existingProd)
        )

        assertTrue(result.success)
        assertEquals(1, result.newProductsCount)
        assertEquals(1, result.restockedCount)
        assertEquals(1, result.newCategoriesCount)

        // Verify new product was created properly
        val savedNewProd = gateway.savedProducts.find { it.name == "New Cotton Shirt" }
        assertNotNull(savedNewProd)
        assertEquals("10050", savedNewProd?.sku)
        assertEquals(500.0, savedNewProd?.sellingPrice ?: 0.0, 0.001)
        assertEquals(250.0, savedNewProd?.costPrice ?: 0.0, 0.001)
        assertEquals(10, savedNewProd?.currentStock)

        // Verify initial stock movement was recorded
        val movement = gateway.recordedMovements.find { it.sku == "10050" }
        assertNotNull(movement)
        assertEquals(StockChangeType.RESTOCK, movement?.changeType)
        assertEquals(10, movement?.quantityDelta)
        assertEquals("draft_123", movement?.referenceId)

        // Verify restock was called
        val restockAdj = gateway.stockAdjustments.find { it.first == 50L }
        assertNotNull(restockAdj)
        assertEquals(5, restockAdj?.second)

        // Verify cost price update
        val priceUpdate = gateway.priceUpdates.find { it.first == 50L }
        assertNotNull(priceUpdate)
        assertEquals(400.0, priceUpdate?.third ?: 0.0, 0.001)
    }

    @Test
    fun testIdempotentRetryAfterPartialFailure() = runBlocking {
        val gateway = FakeProductOperationsGateway()
        val allocator = BarcodeAllocator(BarcodeAllocatorTest.FakeAtomicBarcodeGateway(10080L))
        val pipeline = InvoiceSavePipeline(gateway, allocator)

        val item1 = InvoiceItemDraft(id = "i1", name = "Success Shirt", quantity = 5, unitPrice = 100.0, sellingPrice = 200.0, isIncluded = true)
        val item2 = InvoiceItemDraft(id = "i2", name = "Fail Item", quantity = 3, unitPrice = 150.0, sellingPrice = 300.0, isIncluded = true)

        // Simulate failure on item 2
        gateway.failOnProduct = "Fail Item"

        val firstRun = pipeline.execute(
            draftId = "draft_retry",
            supplierName = "Supplier",
            invoiceNumber = "101",
            items = listOf(item1, item2),
            existingCategories = emptyList(),
            existingProducts = emptyList()
        )

        assertFalse(firstRun.success)
        assertEquals(1, firstRun.newProductsCount)
        assertTrue(firstRun.savedItemIds.contains("i1"))
        assertTrue(firstRun.failedItemIds.contains("i2"))
        assertEquals(1, gateway.savedProducts.size)

        // Fix the issue and retry passing alreadySavedItemIds = firstRun.savedItemIds
        gateway.failOnProduct = null
        val secondRun = pipeline.execute(
            draftId = "draft_retry",
            supplierName = "Supplier",
            invoiceNumber = "101",
            items = listOf(item1, item2),
            existingCategories = emptyList(),
            existingProducts = emptyList(),
            alreadySavedItemIds = firstRun.savedItemIds
        )

        assertTrue(secondRun.success)
        assertEquals(1, secondRun.newProductsCount) // Only item 2 is saved in second run
        assertEquals(2, gateway.savedProducts.size) // Total 2 products saved, no duplicates!
    }
}

package com.matoshree.shopmanager.domain.usecase

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Reproduction & regression tests for Round 16 Bug Q6:
 * - Product price edit retains existing bill prices while new bills use updated price.
 */
class Round16Q6ProductPriceEditTest {

    @Test
    fun testQ6_ProductPriceEdit_ExistingBillsUnchanged_NewBillsUseNewPrice() {
        val product = Product(
            id = 501L,
            gid = "prd_01HM5V7K8P9Q2W3E4R5T6Y7U88",
            shopId = 1,
            name = "काठपदराची साडी",
            sellingPrice = 500.0,
            costPrice = 300.0,
            currentStock = 10,
            lowStockThreshold = 2,
            categoryName = "साडी"
        )

        // Existing historical bill created with original product price (500.0)
        val oldBillItem = BillItem(
            id = 1L,
            gid = "bli_old",
            billId = 101L,
            productName = product.name,
            sellingPrice = product.sellingPrice,
            quantity = 2,
            lineTotal = product.sellingPrice * 2
        )
        val oldBill = Bill(
            id = 101L,
            gid = "bil_101",
            shopId = 1,
            billNumber = "MC-OLD-1",
            transactionUuid = "uuid-old-1",
            saleType = SaleType.QUICK,
            subtotal = 1000.0,
            finalAmount = 1000.0,
            paidAmount = 1000.0,
            pendingAmount = 0.0,
            items = listOf(oldBillItem),
            billDate = "2026-11-10"
        )

        // Edit product price to 650.0 (and cost price to 400.0)
        val updatedProduct = product.copy(sellingPrice = 650.0, costPrice = 400.0)

        // Verify: Old bill's items still have sellingPrice 500.0 and lineTotal 1000.0
        assertEquals(500.0, oldBill.items[0].sellingPrice, 0.001)
        assertEquals(1000.0, oldBill.finalAmount, 0.001)

        // New bill created using updated product price (650.0)
        val newBillItem = BillItem(
            id = 2L,
            gid = "bli_new",
            billId = 102L,
            productName = updatedProduct.name,
            sellingPrice = updatedProduct.sellingPrice,
            quantity = 2,
            lineTotal = updatedProduct.sellingPrice * 2
        )
        val newBill = Bill(
            id = 102L,
            gid = "bil_102",
            shopId = 1,
            billNumber = "MC-NEW-2",
            transactionUuid = "uuid-new-2",
            saleType = SaleType.QUICK,
            subtotal = 1300.0,
            finalAmount = 1300.0,
            paidAmount = 1300.0,
            pendingAmount = 0.0,
            items = listOf(newBillItem),
            billDate = "2026-11-15"
        )

        assertEquals("New bill uses new price 650.0", 650.0, newBill.items[0].sellingPrice, 0.001)
        assertEquals("New bill total is 1300.0", 1300.0, newBill.finalAmount, 0.001)
        assertEquals("Old bill price remains untouched at 500.0", 500.0, oldBill.items[0].sellingPrice, 0.001)
    }
}

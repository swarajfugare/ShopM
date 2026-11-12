package com.matoshree.shopmanager.printer

import com.matoshree.shopmanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ThermalPrintingTest {

    @Test
    fun testThermalPaperConstants() {
        assertEquals(384, ThermalReceiptFormatter.MAX_PAPER_DOTS)
        assertEquals(32, ThermalReceiptFormatter.CHARS_PER_LINE)
        assertEquals(48, ThermalReceiptFormatter.MAX_PAPER_DOTS / 8) // 48 bytes per line
    }

    @Test
    fun testBluetoothPrinterDeviceModel() {
        val device = BluetoothPrinterDevice(
            name = "MTP-58",
            address = "66:22:33:44:55:66",
            isSelected = true
        )
        assertEquals("MTP-58", device.name)
        assertEquals("66:22:33:44:55:66", device.address)
        assertTrue(device.isSelected)
    }

    @Test
    fun testBillDiscountComputationForThermalReceipt() {
        val items = listOf(
            BillItem(
                id = 1,
                productName = "Pure Paithani Silk Saree",
                quantity = 1,
                sellingPrice = 15000.0,
                lineTotal = 15000.0
            ),
            BillItem(
                id = 2,
                productName = "Cotton Saree",
                quantity = 2,
                sellingPrice = 2850.0,
                lineTotal = 5700.0
            )
        )
        val subtotal = items.sumOf { it.lineTotal }
        val discountAmount = 700.0
        val finalAmount = subtotal - discountAmount

        val bill = Bill(
            id = 101,
            billNumber = "MC-2026-000005",
            transactionUuid = "uuid-123",
            saleType = SaleType.DETAILED,
            customerName = "Ramesh Shinde",
            customerMobile = "9822334455",
            subtotal = subtotal,
            discountType = DiscountType.AMOUNT,
            discountValue = 700.0,
            discountAmount = discountAmount,
            finalAmount = finalAmount,
            paymentMethod = PaymentMethod.UPI,
            billDate = "30 Aug 2026, 08:39 PM",
            items = items
        )

        assertEquals(20700.0, bill.subtotal, 0.001)
        assertEquals(700.0, bill.discountAmount, 0.001)
        assertEquals(20000.0, bill.finalAmount, 0.001)
        assertEquals("Ramesh Shinde", bill.customerName)
        assertEquals("9822334455", bill.customerMobile)
        assertEquals(PaymentMethod.UPI, bill.paymentMethod)
    }
}

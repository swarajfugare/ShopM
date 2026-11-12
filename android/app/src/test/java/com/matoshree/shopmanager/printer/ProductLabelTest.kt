package com.matoshree.shopmanager.printer

import com.matoshree.shopmanager.domain.model.Product
import com.matoshree.shopmanager.utils.ProductSkuGenerator
import org.junit.Assert.*
import org.junit.Test

class ProductLabelTest {

    @Test
    fun testNumericSkuGenerationFormat() {
        val sku = ProductSkuGenerator.generateSku("Silk Saree")
        assertEquals(5, sku.length)
        assertTrue(sku.all { it.isDigit() })
        val num = sku.toInt()
        assertTrue(num in 10000..99999)
    }

    @Test
    fun testNumericSkuCollisionResolution() {
        val existingSkus = mutableSetOf<String>()
        val sku1 = ProductSkuGenerator.generateSku("Silk Saree", existingSkus)
        existingSkus.add(sku1)

        val sku2 = ProductSkuGenerator.generateSku("Silk Saree", existingSkus)
        assertNotEquals(sku1, sku2)
        assertEquals(5, sku2.length)
        assertTrue(sku2.all { it.isDigit() })
    }

    @Test
    fun testProductModelWithNumericSku() {
        val product = Product(
            id = 1001,
            name = "Designer Kurti",
            sellingPrice = 1200.0,
            costPrice = 800.0,
            sku = "10542",
            isActive = true
        )

        assertEquals("Designer Kurti", product.name)
        assertEquals("10542", product.sku)
        assertEquals(1200.0, product.sellingPrice, 0.001)
    }

    @Test
    fun testLabelSizeConfigPitchAndDimensions() {
        val config50x38 = LabelSizeConfig.SIZE_50X38
        assertEquals(50, config50x38.widthMm)
        assertEquals(38, config50x38.heightMm)
        assertEquals(384, config50x38.totalWidthDots)
        assertEquals(288, config50x38.stickerHeightDots)
        assertEquals(332, config50x38.totalPitchDots)
        // Verify pitch exceeds content height to provide the gap advance
        assertTrue(config50x38.totalPitchDots > config50x38.stickerHeightDots)
    }

    @Test
    fun testLabelPrinterProtocolValues() {
        assertEquals("HOIN / ESC-POS मोड (HOP-H58 प्रिंटरसाठी)", LabelPrinterProtocol.ESCPOS_HOIN.title)
        assertEquals("TSPL बारकोड प्रिंटर मोड (ऑटो गॅप सेन्सर)", LabelPrinterProtocol.TSPL.title)
    }
}


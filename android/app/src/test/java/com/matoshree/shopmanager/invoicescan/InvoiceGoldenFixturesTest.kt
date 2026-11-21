package com.matoshree.shopmanager.invoicescan

import com.matoshree.shopmanager.domain.usecase.InvoiceParser
import com.matoshree.shopmanager.domain.usecase.InvoiceValidator
import org.junit.Assert.*
import org.junit.Test

class InvoiceGoldenFixturesTest {

    // 1. GST invoice with HSN
    @Test
    fun testFixture1_GstInvoiceWithHsn() {
        val json = """
        {
            "supplierName": "Mahesh Textiles Mumbai",
            "invoiceNumber": "MT-2026-904",
            "invoiceDate": "2026-11-10",
            "invoiceTotal": 15750.0,
            "items": [
                {
                    "rawText": "HSN 5208 Cotton Shirting 50 pcs @ 300 = 15000 GST 5%",
                    "name": "Cotton Shirting",
                    "quantity": 50,
                    "unit": "pcs",
                    "unitPrice": 300.0,
                    "lineTotal": 15000.0,
                    "taxPercent": 5.0,
                    "category": "Shirts",
                    "confidence": 0.98
                }
            ]
        }
        """
        val result = InvoiceParser.parse(json)
        assertTrue(result.isSuccess)
        val invoice = result.getOrThrow()
        assertEquals("Mahesh Textiles Mumbai", invoice.supplierName)
        assertEquals(1, invoice.items.size)
        assertEquals(300.0, invoice.items[0].unitPrice, 0.001)
        assertEquals(600.0, invoice.items[0].sellingPrice, 0.001) // 2x default
    }

    // 2. Simple bill book format
    @Test
    fun testFixture2_SimpleBillBook() {
        val json = """
        {
            "supplierName": "Surat Saree Kendra",
            "invoiceNumber": "512",
            "invoiceDate": "12/11/2026",
            "invoiceTotal": 8400.0,
            "items": [
                {
                    "rawText": "Nauvari Saree 10 pcs 500 5000",
                    "name": "Nauvari Saree",
                    "quantity": 10,
                    "unit": "pcs",
                    "unitPrice": 500.0,
                    "lineTotal": 5000.0,
                    "category": "Sarees"
                },
                {
                    "rawText": "Paithani Saree 2 pcs 1700 3400",
                    "name": "Paithani Saree",
                    "quantity": 2,
                    "unit": "pcs",
                    "unitPrice": 1700.0,
                    "lineTotal": 3400.0,
                    "category": "Sarees"
                }
            ]
        }
        """
        val result = InvoiceParser.parse(json)
        assertTrue(result.isSuccess)
        val invoice = result.getOrThrow()
        assertEquals(2, invoice.items.size)
        assertEquals(8400.0, invoice.invoiceTotal ?: 0.0, 0.001)
    }

    // 3. Handwritten bill with missing columns
    @Test
    fun testFixture3_HandwrittenWithMissingColumns() {
        val json = """
        {
            "supplierName": "Local Trader",
            "items": [
                {
                    "rawText": "Kids Baba Suit 12 2400",
                    "name": "Kids Baba Suit",
                    "quantity": 12,
                    "unitPrice": 0.0,
                    "lineTotal": 2400.0,
                    "category": "Kids Wear"
                }
            ]
        }
        """
        val result = InvoiceParser.parse(json)
        assertTrue(result.isSuccess)
        val invoice = result.getOrThrow()
        // Parser must derive unitPrice = 2400 / 12 = 200
        assertEquals(200.0, invoice.items[0].unitPrice, 0.001)
        assertEquals(400.0, invoice.items[0].sellingPrice, 0.001)
    }

    // 4. Marathi item names
    @Test
    fun testFixture4_MarathiNames() {
        val json = """
        {
            "supplierName": "गणेश गारमेंट्स",
            "invoiceNumber": "GG-101",
            "items": [
                {
                    "rawText": "सुती कुर्ता ३८ - ५ नग - ३५० - १७५०",
                    "name": "सुती कुर्ता ३८",
                    "quantity": 5,
                    "unit": "नग",
                    "unitPrice": 350.0,
                    "lineTotal": 1750.0,
                    "category": "कुर्ती"
                }
            ]
        }
        """
        val result = InvoiceParser.parse(json)
        assertTrue(result.isSuccess)
        val invoice = result.getOrThrow()
        assertEquals("सुती कुर्ता ३८", invoice.items[0].name)
        assertEquals("कुर्ती", invoice.items[0].categoryName)
    }

    // 5. Units in dozen, set, meter
    @Test
    fun testFixture5_DozenAndMeterUnits() {
        val json = """
        {
            "supplierName": "Ichalkaranji Weavers",
            "items": [
                {
                    "rawText": "Handkerchief 2 dozen @ 120/doz = 240",
                    "name": "Cotton Hanky Dozen",
                    "quantity": 2,
                    "unit": "dozen",
                    "unitPrice": 120.0,
                    "lineTotal": 240.0,
                    "category": "Accessories"
                },
                {
                    "rawText": "Shirting Fabric 30 meter @ 90 = 2700",
                    "name": "Shirting Fabric",
                    "quantity": 30,
                    "unit": "meter",
                    "unitPrice": 90.0,
                    "lineTotal": 2700.0,
                    "category": "Fabrics"
                }
            ]
        }
        """
        val result = InvoiceParser.parse(json)
        assertTrue(result.isSuccess)
        val invoice = result.getOrThrow()
        assertEquals("dozen", invoice.items[0].unit)
        assertEquals("meter", invoice.items[1].unit)
    }

    // 6. Price including GST toggle
    @Test
    fun testFixture6_PriceIncludingGst() {
        val json = """
        {
            "supplierName": "Apex Fashion Pune",
            "notes": "Prices are inclusive of 5% GST",
            "items": [
                {
                    "name": "Formal Pant 32",
                    "quantity": 4,
                    "unitPrice": 420.0,
                    "lineTotal": 1680.0,
                    "taxPercent": 5.0,
                    "category": "Pants"
                }
            ]
        }
        """
        val result = InvoiceParser.parse(json)
        assertTrue(result.isSuccess)
        assertEquals(420.0, result.getOrThrow().items[0].unitPrice, 0.001)
    }

    // 7. Discount column present
    @Test
    fun testFixture7_DiscountColumn() {
        val json = """
        {
            "supplierName": "Delhi Wholesale Hub",
            "items": [
                {
                    "rawText": "Jeans 34 List 800 Less 20% Net 640 x 10 = 6400",
                    "name": "Denim Jeans 34",
                    "quantity": 10,
                    "unitPrice": 640.0,
                    "lineTotal": 6400.0,
                    "category": "Jeans"
                }
            ]
        }
        """
        val result = InvoiceParser.parse(json)
        assertTrue(result.isSuccess)
        assertEquals(640.0, result.getOrThrow().items[0].unitPrice, 0.001)
    }

    // 8. Rate and amount swapped or raw order
    @Test
    fun testFixture8_SwappedColumns() {
        val json = """
        {
            "supplierName": "Quick Traders",
            "items": [
                {
                    "rawText": "1200.00 (Total) 3 (Qty) 400.00 (Rate) T-Shirt XL",
                    "name": "T-Shirt XL",
                    "quantity": 3,
                    "unitPrice": 400.0,
                    "lineTotal": 1200.0,
                    "category": "T-Shirts"
                }
            ]
        }
        """
        val result = InvoiceParser.parse(json)
        assertTrue(result.isSuccess)
        assertEquals(400.0, result.getOrThrow().items[0].unitPrice, 0.001)
        assertEquals(1200.0, result.getOrThrow().items[0].lineTotal, 0.001)
    }

    // 9. Multi-page simulated merged items
    @Test
    fun testFixture9_MultiPageMerged() {
        val json = """
        {
            "supplierName": "Grand Mega Mart",
            "invoiceNumber": "PAGE-1-2",
            "items": [
                { "name": "Item Page 1", "quantity": 10, "unitPrice": 50.0, "lineTotal": 500.0 },
                { "name": "Item Page 2", "quantity": 5, "unitPrice": 100.0, "lineTotal": 500.0 }
            ]
        }
        """
        val result = InvoiceParser.parse(json)
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().items.size)
    }

    // 10. Rows with unreadable price derived from total
    @Test
    fun testFixture10_UnreadablePriceDerived() {
        val json = """
        {
            "items": [
                {
                    "rawText": "Nighty 6 pcs Total 900 Rate Blur",
                    "name": "Cotton Nighty",
                    "quantity": 6,
                    "unitPrice": null,
                    "lineTotal": 900.0
                }
            ]
        }
        """
        val result = InvoiceParser.parse(json)
        assertTrue(result.isSuccess)
        assertEquals(150.0, result.getOrThrow().items[0].unitPrice, 0.001)
    }

    // 11. Invoice total mismatch validation flag
    @Test
    fun testFixture11_InvoiceTotalMismatch() {
        val json = """
        {
            "supplierName": "Miscalculated Bills",
            "invoiceTotal": 5000.0,
            "items": [
                { "name": "Item A", "quantity": 1, "unitPrice": 1000.0, "lineTotal": 1000.0 },
                { "name": "Item B", "quantity": 1, "unitPrice": 2000.0, "lineTotal": 2000.0 }
            ]
        }
        """
        val result = InvoiceParser.parse(json).getOrThrow()
        val flags = InvoiceValidator.validateInvoice(result)
        assertTrue(flags.any { it.code == InvoiceValidator.FLAG_INVOICE_TOTAL_MISMATCH })
    }

    // 12. Duplicate lines inside same invoice
    @Test
    fun testFixture12_DuplicateLinesInSameInvoice() {
        val json = """
        {
            "items": [
                { "name": "Cotton Shirt 40", "quantity": 5, "unitPrice": 300.0, "lineTotal": 1500.0 },
                { "name": "Cotton Shirt 40", "quantity": 3, "unitPrice": 300.0, "lineTotal": 900.0 }
            ]
        }
        """
        val result = InvoiceParser.parse(json).getOrThrow()
        val item0Flags = result.items[0].validationFlags
        assertTrue(item0Flags.any { it.code == InvoiceValidator.FLAG_DUPLICATE_LINE })
    }

    // 13. Very long invoice with 80 items
    @Test
    fun testFixture13_LongInvoice80Items() {
        val itemsJson = (1..80).joinToString(",") { i ->
            """{"name": "Product $i", "quantity": 2, "unitPrice": 100.0, "lineTotal": 200.0, "category": "General"}"""
        }
        val json = """{"supplierName": "Mega Wholesaler", "items": [$itemsJson]}"""
        val result = InvoiceParser.parse(json)
        assertTrue(result.isSuccess)
        assertEquals(80, result.getOrThrow().items.size)
    }

    // Robustness: Markdown wrapped json
    @Test
    fun testMarkdownWrappedJson() {
        val raw = "```json\n{\"supplierName\": \"Wrapped In Markdown\", \"items\": []}\n```"
        val result = InvoiceParser.parse(raw)
        assertTrue(result.isSuccess)
        assertEquals("Wrapped In Markdown", result.getOrThrow().supplierName)
    }

    // Robustness: Trailing comma repair
    @Test
    fun testTrailingCommaRepair() {
        val raw = """{"supplierName": "Trailing Comma Store", "items": [{"name": "Test", "quantity": 1, "unitPrice": 10.0,},],}"""
        val result = InvoiceParser.parse(raw)
        assertTrue(result.isSuccess)
    }

    // Robustness: Empty string or non-JSON
    @Test
    fun testInvalidOrEmptyResponse() {
        assertTrue(InvoiceParser.parse("").isFailure)
        assertTrue(InvoiceParser.parse("Hello I am an AI and cannot read this").isFailure)
    }
}

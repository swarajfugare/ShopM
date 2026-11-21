package com.matoshree.shopmanager.invoicescan

import com.matoshree.shopmanager.data.ai.FakeInvoiceAiClient
import com.matoshree.shopmanager.domain.model.InvoiceExtractionResult
import com.matoshree.shopmanager.domain.model.InvoiceItemDraft
import com.matoshree.shopmanager.domain.usecase.InvoiceParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class InvoiceAiClientRobustnessTest {

    @Test
    fun testQuotaExhaustedError() = runBlocking {
        val client = FakeInvoiceAiClient(
            Result.failure(Exception("AI कोटा संपला आहे. काही वेळाने प्रयत्न करा."))
        )
        val result = client.extractInvoice(emptyList(), emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("AI कोटा संपला आहे") == true)
    }

    @Test
    fun testTimeoutError() = runBlocking {
        val client = FakeInvoiceAiClient(
            Result.failure(Exception("वेळ संपली (Timeout). कृपया पुन्हा प्रयत्न करा."))
        )
        val result = client.extractInvoice(emptyList(), emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("वेळ संपली") == true)
    }

    @Test
    fun testNoInternetError() = runBlocking {
        val client = FakeInvoiceAiClient(
            Result.failure(Exception("इंटरनेट कनेक्शन तपासा आणि पुन्हा प्रयत्न करा."))
        )
        val result = client.extractInvoice(emptyList(), emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("इंटरनेट कनेक्शन") == true)
    }

    @Test
    fun testSafetyBlockError() = runBlocking {
        val client = FakeInvoiceAiClient(
            Result.failure(Exception("सुरक्षा नियमांमुळे हे चित्र वाचता आले नाही (Safety block)"))
        )
        val result = client.extractInvoice(emptyList(), emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("सुरक्षा नियमांमुळे") == true)
    }

    @Test
    fun testAppCheckError() = runBlocking {
        val client = FakeInvoiceAiClient(
            Result.failure(Exception("Firebase App Check त्रुटी: कृपया Firebase Console मध्ये App Check > APIs मध्ये जाऊन 'Vertex AI in Firebase' हे Unenforce (सक्ती बंद) करा."))
        )
        val result = client.extractInvoice(emptyList(), emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Firebase App Check त्रुटी") == true)
    }

    @Test
    fun testDirectGeminiAiClientEmptyImages() = runBlocking {
        val client = com.matoshree.shopmanager.data.ai.DirectGeminiAiClient("test-key")
        val result = client.extractInvoice(emptyList(), emptyList())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("किमान एक फोटो") == true)
    }

    @Test
    fun testDirectGeminiAiClientBlankKeyFallback() = runBlocking {
        val mockInvoice = InvoiceExtractionResult(
            supplierName = "Fallback Textiles",
            items = listOf(InvoiceItemDraft(name = "Saree", quantity = 1, unitPrice = 500.0, lineTotal = 500.0))
        )
        val fallback = FakeInvoiceAiClient(Result.success(mockInvoice))
        val client = com.matoshree.shopmanager.data.ai.DirectGeminiAiClient(
            apiKey = "",
            fallbackClient = fallback
        )
        val result = client.extractInvoice(emptyList(), emptyList())
        assertTrue(result.isSuccess)
        assertEquals("Fallback Textiles", result.getOrThrow().supplierName)
    }

    @Test
    fun testInvalidJsonResponseFallback() {
        val rawNonsense = "<html><body>502 Bad Gateway</body></html>"
        val parseResult = InvoiceParser.parse(rawNonsense)
        assertTrue(parseResult.isFailure)
    }

    @Test
    fun testSuccessfulAiClientExtraction() = runBlocking {
        val mockInvoice = InvoiceExtractionResult(
            supplierName = "Shree Ganesh Textiles",
            invoiceNumber = "SGT-2026",
            items = listOf(
                InvoiceItemDraft(name = "Cotton Pant", quantity = 5, unitPrice = 300.0, lineTotal = 1500.0)
            )
        )
        val client = FakeInvoiceAiClient(Result.success(mockInvoice))
        val result = client.extractInvoice(emptyList(), emptyList())
        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals("Shree Ganesh Textiles", data.supplierName)
        assertEquals(1, data.items.size)
    }
}

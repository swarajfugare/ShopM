package com.matoshree.shopmanager.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.vertexai.type.content
import com.google.firebase.vertexai.type.generationConfig
import com.google.firebase.vertexai.vertexAI
import com.matoshree.shopmanager.domain.model.InvoiceExtractionResult
import com.matoshree.shopmanager.domain.usecase.InvoiceParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface InvoiceAiClient {
    suspend fun extractInvoice(
        images: List<Bitmap>,
        existingCategories: List<String>
    ): Result<InvoiceExtractionResult>
}

class FirebaseVertexAiClient(
    private val primaryModelName: String = "gemini-2.0-flash",
    private val fallbackModelNames: List<String> = listOf("gemini-2.5-flash"),
    private val timeoutMs: Long = 60_000L
) : InvoiceAiClient {

    private val TAG = "FirebaseVertexAiClient"

    override suspend fun extractInvoice(
        images: List<Bitmap>,
        existingCategories: List<String>
    ): Result<InvoiceExtractionResult> = withContext(Dispatchers.IO) {
        if (images.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("स्कॅन करण्यासाठी किमान एक फोटो आवश्यक आहे"))
        }

        val allModels = listOf(primaryModelName) + fallbackModelNames
        var lastException: Exception? = null

        for (modelName in allModels) {
            try {
                Log.d(TAG, "Attempting extraction with model: $modelName")
                val generativeModel = Firebase.vertexAI.generativeModel(
                    modelName = modelName,
                    generationConfig = generationConfig {
                        responseMimeType = "application/json"
                        temperature = 0.1f
                    },
                    systemInstruction = content {
                        text(buildSystemPrompt(existingCategories))
                    }
                )

                val promptContent = content {
                    text("Please extract all line items and details from these invoice pages into the required JSON format.")
                    images.forEach { bmp ->
                        image(bmp)
                    }
                }

                val response = withTimeoutOrNull(timeoutMs) {
                    generativeModel.generateContent(promptContent)
                } ?: run {
                    lastException = Exception("वेळ संपली (Timeout). कृपया पुन्हा प्रयत्न करा.")
                    return@run null
                }

                if (response == null) {
                    continue
                }

                val responseText = response.text
                if (responseText.isNullOrBlank()) {
                    lastException = Exception("बिलातून काहीही वाचता आले नाही. कृपया स्पष्ट फोटो काढा.")
                    continue
                }

                val parseResult = InvoiceParser.parse(responseText)
                if (parseResult.isFailure) {
                    Log.w(TAG, "Failed to parse AI output from $modelName: ${parseResult.exceptionOrNull()?.message}")
                    lastException = Exception("बिलाची माहिती समजण्यात अडचण आली. कृपया पुन्हा प्रयत्न करा.")
                    continue
                }

                return@withContext parseResult
            } catch (e: Exception) {
                Log.e(TAG, "Vertex AI extraction failed with $modelName: ${e.message}", e)
                lastException = e
                val isModelRetired = e.message?.contains("retired", ignoreCase = true) == true ||
                        e.message?.contains("discontinued", ignoreCase = true) == true ||
                        e.message?.contains("not found", ignoreCase = true) == true
                if (isModelRetired && modelName != allModels.last()) {
                    Log.w(TAG, "Model $modelName is retired/unavailable. Falling back to next model.")
                    continue
                }
                break
            }
        }

        val e = lastException ?: Exception("स्कॅन अयशस्वी झाले")
        val userMsg = when {
            e.message?.contains("RESOURCE_EXHAUSTED", ignoreCase = true) == true ->
                "AI कोटा संपला आहे. काही वेळाने प्रयत्न करा."
            e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ->
                "Firebase Vertex AI सुरू केलेले नाही. कृपया कन्सोलमध्ये Vertex AI सुरू करा."
            e.message?.contains("app check", ignoreCase = true) == true ||
                    e.message?.contains("appcheck", ignoreCase = true) == true ->
                "Firebase App Check त्रुटी: कृपया Firebase Console मध्ये App Check > APIs मध्ये जाऊन 'Vertex AI in Firebase' हे Unenforce (सक्ती बंद) करा."
            e.message?.contains("UNAVAILABLE", ignoreCase = true) == true ||
                    e.message?.contains("connect", ignoreCase = true) == true ->
                "इंटरनेट कनेक्शन तपासा आणि पुन्हा प्रयत्न करा."
            e.message?.contains("retired", ignoreCase = true) == true ||
                    e.message?.contains("discontinued", ignoreCase = true) == true ->
                "नवीन मॉडेल आवृत्ती वापरली जात आहे. कृपया ॲप अपडेट करा."
            else ->
                "स्कॅन करताना त्रुटी आली: ${e.localizedMessage ?: "अनोळखी चूक"}"
        }
        Result.failure(Exception(userMsg, e))
    }

    private fun buildSystemPrompt(existingCategories: List<String>): String {
        val catListStr = if (existingCategories.isNotEmpty()) {
            existingCategories.joinToString(", ")
        } else {
            "General, Shirts, Pants, Sarees, Kurti, Kids Wear"
        }

        return """
            You are an expert invoice data extractor for a retail textile/clothing shop in Maharashtra, India.
            Read all pages of the invoice carefully and output ONLY a valid JSON object matching this structure:
            {
              "supplierName": string or null,
              "invoiceNumber": string or null,
              "invoiceDate": string or null,
              "invoiceTotal": number or null,
              "notes": string or null,
              "items": [
                {
                  "rawText": string,
                  "name": string,
                  "quantity": integer,
                  "unit": string,
                  "unitPrice": number,
                  "lineTotal": number,
                  "taxPercent": number or null,
                  "category": string,
                  "confidence": number,
                  "notes": string or null
                }
              ]
            }

            Guidelines:
            1. Support printed, handwritten, GST invoices, and simple bill-books in Marathi, Hindi, or English.
            2. The "name" must be clean, concise, as a shopkeeper writes it. Preserve size, color, or style if present (e.g. "Cotton Shirt 40", "Jeans 32").
            3. "quantity" must be an integer >= 1.
            4. "unitPrice" is the purchase cost per unit. If rate is missing but lineTotal and quantity exist, compute unitPrice = lineTotal / quantity.
            5. "category" MUST be chosen from this list if it semantically belongs there: [$catListStr]. If none fits, provide a short Marathi or English category name.
            6. NEVER invent items or prices. Put unreadable fields as null.
            7. Set "confidence" between 0.0 and 1.0.
        """.trimIndent()
    }
}

class FakeInvoiceAiClient(
    private var simulatedResult: Result<InvoiceExtractionResult> = Result.success(InvoiceExtractionResult())
) : InvoiceAiClient {

    fun setSimulatedResult(result: Result<InvoiceExtractionResult>) {
        simulatedResult = result
    }

    override suspend fun extractInvoice(
        images: List<Bitmap>,
        existingCategories: List<String>
    ): Result<InvoiceExtractionResult> {
        return simulatedResult
    }
}

package com.matoshree.shopmanager.data.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.matoshree.shopmanager.domain.model.InvoiceExtractionResult
import com.matoshree.shopmanager.domain.usecase.InvoiceParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class DirectGeminiAiClient(
    private val apiKey: String,
    private val models: List<String> = listOf(
        "gemini-3-flash-preview",
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash-lite"
    ),
    private val fallbackClient: InvoiceAiClient? = null,
    private val timeoutMs: Long = 60_000L
) : InvoiceAiClient {

    private val TAG = "DirectGeminiAiClient"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    override suspend fun extractInvoice(
        images: List<Bitmap>,
        existingCategories: List<String>
    ): Result<InvoiceExtractionResult> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "Direct Gemini API key is blank, falling back if available")
            return@withContext fallbackClient?.extractInvoice(images, existingCategories)
                ?: Result.failure(Exception("Gemini API Key सेट केलेली नाही. कृपया Settings मध्ये API Key सेट करा."))
        }

        if (images.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("स्कॅन करण्यासाठी किमान एक फोटो आवश्यक आहे"))
        }

        var lastException: Exception? = null

        for (model in models) {
            try {
                Log.d(TAG, "Attempting direct extraction with model: $model")
                val responseJson = callGeminiApi(model, images, existingCategories)
                val parseResult = InvoiceParser.parse(responseJson)
                if (parseResult.isSuccess) {
                    return@withContext parseResult
                } else {
                    lastException = Exception("बिलाची माहिती समजण्यात अडचण आली: ${parseResult.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct Gemini API failed with $model: ${e.message}", e)
                lastException = e
                continue
            }
        }

        if (fallbackClient != null) {
            Log.w(TAG, "All direct models failed. Attempting fallback client...")
            val fbResult = fallbackClient.extractInvoice(images, existingCategories)
            if (fbResult.isSuccess) {
                return@withContext fbResult
            }
        }

        val e = lastException ?: Exception("स्कॅन अयशस्वी झाले")
        val userMsg = when {
            e.message?.contains("429", ignoreCase = true) == true ||
                    e.message?.contains("RESOURCE_EXHAUSTED", ignoreCase = true) == true ->
                "AI कोटा संपला आहे. काही वेळाने प्रयत्न करा."
            e.message?.contains("UNAVAILABLE", ignoreCase = true) == true ||
                    e.message?.contains("503", ignoreCase = true) == true ->
                "Google Gemini सर्व्हरवर तात्पुरता जास्त भार आहे. कृपया १ मिनिटाने पुन्हा प्रयत्न करा."
            e.message?.contains("connect", ignoreCase = true) == true ->
                "इंटरनेट कनेक्शन तपासा आणि पुन्हा प्रयत्न करा."
            else ->
                "स्कॅन करताना त्रुटी आली: ${e.localizedMessage ?: "अनोळखी चूक"}"
        }
        Result.failure(Exception(userMsg, e))
    }

    private fun callGeminiApi(
        model: String,
        images: List<Bitmap>,
        existingCategories: List<String>
    ): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val rootElement = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", buildSystemPrompt(existingCategories)) }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("temperature", 0.1)
            }
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", "Please extract all line items and details from these invoice pages into the required JSON format.")
                        }
                        for (bmp in images) {
                            val base64Data = encodeBase64(bitmapToJpeg(bmp))
                            addJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Data)
                                }
                            }
                        }
                    }
                }
            }
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = rootElement.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        val bodyString = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $bodyString")
        }

        val jsonElement = Json.parseToJsonElement(bodyString)
        val text = jsonElement.jsonObject["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.content

        if (text.isNullOrBlank()) {
            throw Exception("बिलातून काहीही वाचता आले नाही. कृपया स्पष्ट फोटो काढा.")
        }

        return text
    }

    private fun bitmapToJpeg(bmp: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return baos.toByteArray()
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (t: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
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
            Rules:
            1. All numbers must be clean numeric values.
            2. Match items into existing shop categories if possible: [$catListStr].
            3. Ensure unitPrice * quantity equals lineTotal.
            4. Output ONLY valid JSON, no markdown backticks, no explanations.
        """.trimIndent()
    }
}

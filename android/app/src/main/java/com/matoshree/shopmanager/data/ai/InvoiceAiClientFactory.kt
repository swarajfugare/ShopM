package com.matoshree.shopmanager.data.ai

import android.content.Context

object InvoiceAiClientFactory {
    fun create(context: Context): InvoiceAiClient {
        val apiKey = GeminiApiKeyManager.getApiKey(context)
        val firebaseFallback = FirebaseVertexAiClient()
        return if (apiKey.isNotBlank()) {
            DirectGeminiAiClient(
                apiKey = apiKey,
                fallbackClient = firebaseFallback
            )
        } else {
            firebaseFallback
        }
    }
}

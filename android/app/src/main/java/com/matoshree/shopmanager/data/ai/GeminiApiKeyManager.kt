package com.matoshree.shopmanager.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.matoshree.shopmanager.BuildConfig

object GeminiApiKeyManager {
    private const val PREFS_NAME = "matoshree_gemini_prefs"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"

    private fun getSecurePrefs(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getApiKey(context: Context): String {
        val saved = getSecurePrefs(context)?.getString(KEY_GEMINI_API_KEY, null)?.trim()
        if (!saved.isNullOrBlank()) {
            return saved
        }
        return BuildConfig.DEFAULT_GEMINI_API_KEY.trim()
    }

    fun setApiKey(context: Context, key: String) {
        getSecurePrefs(context)?.edit()?.putString(KEY_GEMINI_API_KEY, key.trim())?.apply()
    }
}

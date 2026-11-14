package com.matoshree.shopmanager.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Utility to manage and apply app-wide locale (Marathi default, English optional).
 */
object LocaleHelper {
    private const val PREFS_NAME = "shop_locale_prefs"
    private const val KEY_LANGUAGE = "app_language"

    const val LANG_MARATHI = "mr"
    const val LANG_ENGLISH = "en"

    fun getLanguage(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, LANG_MARATHI) ?: LANG_MARATHI
    }

    fun setLocale(context: Context, language: String): Context {
        persist(context, language)
        try {
            val appLocale = LocaleListCompat.forLanguageTags(language)
            AppCompatDelegate.setApplicationLocales(appLocale)
        } catch (_: Exception) {}
        return updateResources(context, language)
    }

    private fun persist(context: Context, language: String) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
    }

    fun applyLocale(context: Context): Context {
        val lang = getLanguage(context)
        return updateResources(context, lang)
    }

    private fun updateResources(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)
        val res = context.resources
        val config = Configuration(res.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}

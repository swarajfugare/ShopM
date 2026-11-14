package com.matoshree.shopmanager.security

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide Application Lock & Security Shield Manager.
 * - Automatically locks the application after a background inactivity timeout.
 * - Enforces PIN / Biometric authentication upon resuming.
 * - Controls WindowManager FLAG_SECURE to prevent unauthorized screenshots and hide
 *   sensitive shop financial data in the Android "Recent Apps" multi-tasking view.
 */
object AppLockManager : DefaultLifecycleObserver {
    private const val PREFS_NAME = "matoshree_lock_prefs"
    private const val KEY_TIMEOUT_SECONDS = "lock_timeout_seconds"
    private const val KEY_SCREEN_PROTECTION = "screen_protection_enabled"
    private const val KEY_SCREEN_PROTECTION_MIGRATED_V353 = "screen_protection_migrated_v353"

    // Default timeout: 30 seconds
    const val DEFAULT_TIMEOUT_SECONDS = 30L

    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private var backgroundTimestamp = 0L
    private var isInitialized = false
    private var appContext: Context? = null

    private fun migrateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SCREEN_PROTECTION_MIGRATED_V353, false)) {
            // Owner requested screenshots allowed by default for normal screens
            prefs.edit()
                .putBoolean(KEY_SCREEN_PROTECTION, false)
                .putBoolean(KEY_SCREEN_PROTECTION_MIGRATED_V353, true)
                .apply()
        }
    }

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        appContext = context.applicationContext
        migrateIfNeeded(context)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundTimestamp = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (backgroundTimestamp > 0L) {
            val elapsed = System.currentTimeMillis() - backgroundTimestamp
            val timeoutSec = appContext?.let { getTimeoutSeconds(it) } ?: DEFAULT_TIMEOUT_SECONDS
            if (timeoutSec == 0L || elapsed >= timeoutSec * 1000L) {
                _isLocked.value = true
            }
        }
    }

    fun unlock() {
        _isLocked.value = false
        backgroundTimestamp = 0L
    }

    fun lockNow() {
        _isLocked.value = true
        backgroundTimestamp = System.currentTimeMillis()
    }

    fun getTimeoutSeconds(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)
    }

    fun setTimeoutSeconds(context: Context, seconds: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_TIMEOUT_SECONDS, seconds).apply()
    }

    fun isScreenProtectionEnabled(context: Context): Boolean {
        migrateIfNeeded(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SCREEN_PROTECTION, false) // Default false (screenshots allowed)
    }

    fun isScreenshotAllowed(context: Context): Boolean {
        return !isScreenProtectionEnabled(context)
    }

    fun setScreenshotAllowed(context: Context, allowed: Boolean, activity: Activity? = null) {
        setScreenProtectionEnabled(context, !allowed)
        if (activity != null) {
            applyScreenProtection(activity)
        }
    }

    fun setScreenProtectionEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SCREEN_PROTECTION, enabled).apply()
    }

    fun applyScreenProtection(activity: Activity) {
        val enabled = isScreenProtectionEnabled(activity)
        if (enabled) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

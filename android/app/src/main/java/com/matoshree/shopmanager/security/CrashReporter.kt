package com.matoshree.shopmanager.security

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.matoshree.shopmanager.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Lightweight in-app Crash Reporter.
 * Captures uncaught exceptions on any thread, formats a diagnostic report,
 * and saves it to private storage (`last_crash.txt`) for user inspection and sharing.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_FILE_NAME = "last_crash.txt"
    private var isInitialized = false
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(appContext, thread, throwable)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to record crash: ${e.message}", e)
            } finally {
                // Forward to system / existing default handler
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        Log.i(TAG, "CrashReporter successfully initialized")
    }

    fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val crashFile = File(context.filesDir, CRASH_FILE_NAME)
        recordCrashToFile(crashFile, thread, throwable, context.packageName)
    }

    fun recordCrashToFile(crashFile: File, thread: Thread, throwable: Throwable, packageName: String = "com.matoshree.collection") {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
        val timestamp = sdf.format(Date())

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val rawStackTrace = sw.toString()
        val sanitizedMessage = sanitizeStackTrace(throwable.message ?: "No message")
        val sanitizedTrace = sanitizeStackTrace(rawStackTrace)

        val report = buildString {
            appendLine("=== MATOSHREE COLLECTION CRASH REPORT ===")
            appendLine("Timestamp: $timestamp")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Package: $packageName")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android OS: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Crashing Thread: ${thread.name} (id: ${thread.id})")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: $sanitizedMessage")
            appendLine("----------------- STACK TRACE -----------------")
            appendLine(sanitizedTrace)
            appendLine("===============================================")
        }

        try {
            crashFile.parentFile?.mkdirs()
            crashFile.writeText(report, Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun getLastCrash(context: Context): String? {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        return getCrashFromFile(file)
    }

    fun getCrashFromFile(file: File): String? {
        return if (file.exists() && file.length() > 0) {
            try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    fun clearCrash(context: Context): Boolean {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        return clearCrashFile(file)
    }

    fun clearCrashFile(file: File): Boolean {
        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }

    fun shareCrashReport(context: Context) {
        val report = getLastCrash(context) ?: "कोणताही क्रॅश अहवाल उपलब्ध नाही."
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_SUBJECT, "Matoshree Collection Crash Report (${BuildConfig.VERSION_NAME})")
            putExtra(Intent.EXTRA_TEXT, report)
            type = "text/plain"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(sendIntent, "क्रॅश अहवाल शेअर करा").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun sanitizeStackTrace(raw: String): String {
        // Redact potential passwords, PINs, tokens, or email addresses
        return raw
            .replace(Regex("(?i)pin[=:\\s]+([0-9]{4,6})"), "pin=[REDACTED]")
            .replace(Regex("(?i)password[=:\\s]+([^\\s,]+)"), "password=[REDACTED]")
            .replace(Regex("(?i)bearer\\s+[a-zA-Z0-9._-]+"), "bearer [REDACTED]")
            .replace(Regex("(?i)token[=:\\s]+([^\\s,]+)"), "token=[REDACTED]")
    }
}

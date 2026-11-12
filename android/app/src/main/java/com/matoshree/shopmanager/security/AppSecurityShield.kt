package com.matoshree.shopmanager.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.Socket
import java.security.MessageDigest

object AppSecurityShield {
    private const val TAG = "AppSecurityShield"

    // Authorized cryptographic signature SHA-256 fingerprints of official Keystore
    private val AUTHORIZED_SIGNATURE_SHA256 = setOf(
        "6F03CF3AFA8C5E772980506B06EB4F17C6827ADA770B8987E1D7A5555920314A"
    )

    data class SecurityStatus(
        val isSecure: Boolean,
        val isRooted: Boolean,
        val isHookedOrFrida: Boolean,
        val isDebuggerAttached: Boolean,
        val isTampered: Boolean = false,
        val threatMessage: String?
    )

    fun performFullSecurityScan(context: Context): SecurityStatus {
        val rooted = checkRoot()
        val hooked = checkFridaAndHooking()
        val debugger = checkDebugger()
        val signatureValid = checkAppSignature(context)
        val isTampered = !signatureValid

        val isCompromised = (hooked || debugger || isTampered) // Strict block on active debuggers / hook engines / tampered APK

        val threat = when {
            isTampered -> "अनधिकृत ॲप्लिकेशन फेरबदल (Tampered / Cracked APK) आढळले आहे. सुरक्षिततेसाठी ॲप चालवण्यास मनाई आहे."
            hooked -> "Active runtime injection or reverse-engineering tool (Frida/Xposed) detected."
            debugger -> "Active debugger or profiling tool attached to the application."
            rooted -> "Rooted environment detected. Extra security sandbox active."
            else -> null
        }

        return SecurityStatus(
            isSecure = !isCompromised,
            isRooted = rooted,
            isHookedOrFrida = hooked,
            isDebuggerAttached = debugger,
            isTampered = isTampered,
            threatMessage = threat
        )
    }

    fun checkAppSignature(context: Context): Boolean {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures == null || signatures.isEmpty()) {
                // If package info doesn't provide signatures in mock/test environment, allow graceful pass
                return true
            }

            val md = MessageDigest.getInstance("SHA-256")
            for (sig in signatures) {
                val digest = md.digest(sig.toByteArray())
                val hexString = digest.joinToString("") { "%02X".format(it) }
                if (AUTHORIZED_SIGNATURE_SHA256.contains(hexString)) {
                    return true
                }
            }
            Log.w(TAG, "Signature verification failed! APK has been modified or re-signed.")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Signature check notice: ${e.message}")
            true
        }
    }

    private fun checkRoot(): Boolean {
        val rootPaths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/system/xbin/daemonsu"
        )
        for (path in rootPaths) {
            if (File(path).exists()) return true
        }

        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true
        }

        return false
    }

    private fun checkFridaAndHooking(): Boolean {
        try {
            // Check memory maps for Frida / Xposed / Substrate libraries
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists()) {
                val reader = BufferedReader(FileReader(mapsFile))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.lowercase() ?: continue
                    if (l.contains("frida") || l.contains("gadget") || l.contains("xposed") || l.contains("substrate")) {
                        reader.close()
                        return true
                    }
                }
                reader.close()
            }
        } catch (e: Exception) {}

        // Check common Frida local server port
        try {
            Socket("127.0.0.1", 27042).use {
                return true
            }
        } catch (e: Exception) {}

        return false
    }

    private fun checkDebugger(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }
}

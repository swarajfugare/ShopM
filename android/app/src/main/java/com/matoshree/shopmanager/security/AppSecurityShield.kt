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

object AppSecurityShield {
    private const val TAG = "AppSecurityShield"

    data class SecurityStatus(
        val isSecure: Boolean,
        val isRooted: Boolean,
        val isHookedOrFrida: Boolean,
        val isDebuggerAttached: Boolean,
        val threatMessage: String?
    )

    fun performFullSecurityScan(context: Context): SecurityStatus {
        val rooted = checkRoot()
        val hooked = checkFridaAndHooking()
        val debugger = checkDebugger()

        val isCompromised = (hooked || debugger) // Strict block on active debuggers / hook engines

        val threat = when {
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
            threatMessage = threat
        )
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

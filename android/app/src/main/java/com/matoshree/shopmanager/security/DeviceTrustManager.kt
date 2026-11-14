package com.matoshree.shopmanager.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.SetOptions
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.UUID

class DeviceTrustManager(private val context: Context) {
    private val TAG = "DeviceTrustManager"
    private val prefs: SharedPreferences = context.getSharedPreferences("matoshree_device_trust_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_IS_AUTHORIZED = "is_device_authorized"
        private const val KEY_DEVICE_TOKEN = "device_security_token"
        private const val KEY_ACTIVATED_AT = "device_activated_at"
    }

    @SuppressLint("HardwareIds")
    fun getHardwareDeviceId(): String {
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
            val raw = "${Build.MANUFACTURER}_${Build.MODEL}_$androidId"
            hashString(raw).take(16).uppercase()
        } catch (e: Exception) {
            "DEV-" + UUID.randomUUID().toString().take(8).uppercase()
        }
    }

    fun getDeviceName(): String {
        return "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    }

    fun isDeviceAuthorized(): Boolean {
        // Check local persisted approval
        if (prefs.getBoolean(KEY_IS_AUTHORIZED, false)) {
            return true
        }

        // On first run on the owner's primary device (identified by default token or seed), auto-authorize
        val token = prefs.getString(KEY_DEVICE_TOKEN, null)
        return token != null
    }

    fun authorizeDeviceFromGoogleLogin(email: String) {
        val devId = getHardwareDeviceId()
        val token = "AUTH-DEV-${UUID.randomUUID().toString().take(12).uppercase()}"
        prefs.edit()
            .putBoolean(KEY_IS_AUTHORIZED, true)
            .putString(KEY_DEVICE_TOKEN, token)
            .putLong(KEY_ACTIVATED_AT, System.currentTimeMillis())
            .putString("authorized_by_email", email)
            .apply()

        // Register/update device authorization in Firestore cloud registry
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirebaseManager.ensureAuthenticated()
                FirebaseManager.firestore.collection("authorized_devices").document(devId).set(
                    mapOf(
                        "deviceId" to devId,
                        "deviceName" to getDeviceName(),
                        "deviceModel" to Build.MODEL,
                        "osVersion" to Build.VERSION.RELEASE,
                        "appVersion" to "3.5.0",
                        "versionCode" to 53,
                        "authorizedAt" to System.currentTimeMillis(),
                        "authorizedBy" to email,
                        "authMethod" to "GOOGLE_SIGN_IN",
                        "status" to "ACTIVE"
                    ),
                    SetOptions.merge()
                ).await()
                Log.d(TAG, "Device successfully authorized via Google Login and registered in Firestore")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register device in cloud: ${e.message}")
            }
        }
    }

    suspend fun checkCloudApproval(): Boolean {
        return try {
            FirebaseManager.ensureAuthenticated()
            val devId = getHardwareDeviceId()
            val doc = FirebaseManager.firestore.collection("authorized_devices").document(devId).get().await()
            if (doc.exists()) {
                val status = doc.getString("status")
                if ("ACTIVE".equals(status, ignoreCase = true) || "APPROVED".equals(status, ignoreCase = true)) {
                    val token = "AUTH-DEV-${UUID.randomUUID().toString().take(12).uppercase()}"
                    prefs.edit()
                        .putBoolean(KEY_IS_AUTHORIZED, true)
                        .putString(KEY_DEVICE_TOKEN, token)
                        .putLong(KEY_ACTIVATED_AT, System.currentTimeMillis())
                        .apply()
                    return true
                }
            } else {
                registerPendingDevice()
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Cloud approval check note: ${e.message}")
            false
        }
    }

    suspend fun registerPendingDevice() {
        try {
            FirebaseManager.ensureAuthenticated()
            val devId = getHardwareDeviceId()
            FirebaseManager.firestore.collection("authorized_devices").document(devId).set(
                mapOf(
                    "deviceId" to devId,
                    "deviceName" to getDeviceName(),
                    "deviceModel" to Build.MODEL,
                    "osVersion" to Build.VERSION.RELEASE,
                    "firstSeenAt" to System.currentTimeMillis(),
                    "status" to "PENDING"
                ),
                SetOptions.merge()
            ).await()
            Log.d(TAG, "Device $devId registered as PENDING in cloud registry")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register pending device in cloud: ${e.message}")
        }
    }

    fun listenForRemoteRevocation(onRevoked: () -> Unit): com.google.firebase.firestore.ListenerRegistration? {
        val devId = getHardwareDeviceId()
        return try {
            FirebaseManager.firestore.collection("authorized_devices").document(devId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    if (snapshot != null && snapshot.exists()) {
                        val status = snapshot.getString("status")
                        if ("BLOCKED".equals(status, ignoreCase = true) || "REVOKED".equals(status, ignoreCase = true)) {
                            revokeDeviceAuthorization()
                            onRevoked()
                        }
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    fun listenForApproval(onApproved: () -> Unit): com.google.firebase.firestore.ListenerRegistration? {
        val devId = getHardwareDeviceId()
        return try {
            FirebaseManager.firestore.collection("authorized_devices").document(devId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    if (snapshot != null && snapshot.exists()) {
                        val status = snapshot.getString("status")
                        if ("ACTIVE".equals(status, ignoreCase = true) || "APPROVED".equals(status, ignoreCase = true)) {
                            val token = "AUTH-DEV-${UUID.randomUUID().toString().take(12).uppercase()}"
                            prefs.edit()
                                .putBoolean(KEY_IS_AUTHORIZED, true)
                                .putString(KEY_DEVICE_TOKEN, token)
                                .putLong(KEY_ACTIVATED_AT, System.currentTimeMillis())
                                .apply()
                            onApproved()
                        }
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    fun revokeDeviceAuthorization() {
        prefs.edit().clear().apply()
    }

    private fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}

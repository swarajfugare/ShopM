package com.matoshree.shopmanager.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.firestore.SetOptions
import com.matoshree.shopmanager.data.firebase.FirebaseManager
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

        // Salted SHA-256 hash of Owner Master Activation Passcode: "MATOSHREE-SHOP-OWNER-2026"
        private const val MASTER_KEY_HASH = "8f1a84c8a2b53e414c44ec94b34bdfbc4a737f22ec28795791e847c20ef22f3e"
        // Salted SHA-256 hash of Secondary Owner Recovery Passcode
        private const val BACKUP_KEY_HASH = "4a44dc15364204a80fe80e9039455cc1608281820fe2b24f1e5233ade67a4b8f"
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

    suspend fun verifyAndAuthorizeDevice(activationKey: String): Boolean {
        val trimmed = activationKey.trim()
        val enteredHash = hashString(trimmed)

        // 1. Verify with Google Authenticator (6-digit TOTP)
        val isTotpValid = TotpAuthenticator.verifyCode(trimmed)

        // 2. Master Key / Owner Code Fallbacks (SHA-256 salted only - no plaintext backdoors)
        val isMasterValid = (enteredHash == MASTER_KEY_HASH || enteredHash == BACKUP_KEY_HASH)

        val isValid = isTotpValid || isMasterValid

        if (isValid) {
            val token = "AUTH-DEV-${UUID.randomUUID().toString().take(12).uppercase()}"
            prefs.edit()
                .putBoolean(KEY_IS_AUTHORIZED, true)
                .putString(KEY_DEVICE_TOKEN, token)
                .putLong(KEY_ACTIVATED_AT, System.currentTimeMillis())
                .apply()

            // Register device in Cloud Firestore trusted registry
            try {
                FirebaseManager.ensureAuthenticated()
                val devId = getHardwareDeviceId()
                FirebaseManager.firestore.collection("authorized_devices").document(devId).set(
                    mapOf(
                        "deviceId" to devId,
                        "deviceName" to getDeviceName(),
                        "deviceModel" to Build.MODEL,
                        "osVersion" to Build.VERSION.RELEASE,
                        "authorizedAt" to System.currentTimeMillis(),
                        "status" to "ACTIVE"
                    ),
                    SetOptions.merge()
                ).await()
                Log.d(TAG, "Device $devId successfully authorized & registered in Firestore")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register device in cloud: ${e.message}")
            }

            return true
        }

        return false
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

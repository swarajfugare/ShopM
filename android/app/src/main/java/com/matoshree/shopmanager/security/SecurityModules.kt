package com.matoshree.shopmanager.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

class TokenStorage(context: Context) {
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "matoshree_secure_tokens",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("matoshree_fallback_tokens", Context.MODE_PRIVATE)
        }
    }

    fun saveToken(token: String) {
        prefs.edit().putString("jwt_token", token).apply()
    }

    fun getToken(): String? = prefs.getString("jwt_token", null)

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun saveUser(userId: Long, shopId: Long, name: String, role: String) {
        prefs.edit()
            .putLong("user_id", userId)
            .putLong("shop_id", shopId)
            .putString("user_name", name)
            .putString("user_role", role)
            .apply()
    }

    fun getUserName(): String = prefs.getString("user_name", "Matoshree Admin") ?: "Matoshree Admin"
}

class PinManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("matoshree_security_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_FAILED_PIN_ATTEMPTS = "failed_pin_attempts"
        private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until"
        private const val KEY_FAILED_ATTEMPTS = "failed_recovery_attempts"
        private const val KEY_LOCKOUT_UNTIL = "recovery_lockout_until"
        private const val DEFAULT_PIN = "1234"
        private const val SALT_PREFIX = "matoshree_salt_"
        // Securely pre-computed hash of salted owner recovery credential
        private const val RECOVERY_HASH = "68c7e33914972cf0c08ac55c3088d37059422a7bf80222ab79c9ebeb0d2c33e3"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 60_000L // 60 seconds lockout
    }

    fun isPinLockedOut(): Boolean {
        val until = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
        return System.currentTimeMillis() < until
    }

    fun getPinLockoutRemainingSeconds(): Long {
        val until = prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
        val diff = until - System.currentTimeMillis()
        return if (diff > 0) (diff / 1000) else 0L
    }

    fun recordFailedPinAttempt(): Long {
        val count = prefs.getInt(KEY_FAILED_PIN_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED_PIN_ATTEMPTS, count)
        val lockoutMs = when {
            count >= 10 -> 15 * 60 * 1000L // 15 mins
            count >= 5 -> 5 * 60 * 1000L  // 5 mins
            count >= 3 -> 30 * 1000L      // 30 secs
            else -> 0L
        }
        if (lockoutMs > 0) {
            editor.putLong(KEY_PIN_LOCKOUT_UNTIL, System.currentTimeMillis() + lockoutMs)
        }
        editor.apply()
        return lockoutMs / 1000L
    }

    fun resetPinFailedAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_PIN_ATTEMPTS, 0)
            .remove(KEY_PIN_LOCKOUT_UNTIL)
            .apply()
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun hashRecovery(secret: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val salted = (SALT_PREFIX + secret).toByteArray(Charsets.UTF_8)
        val hash = digest.digest(salted)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun isPinSet(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
        resetPinFailedAttempts()
    }

    fun savePin(pin: String) = setPin(pin)

    fun verifyPin(enteredPin: String): Boolean {
        if (isPinLockedOut()) return false

        val isValid = if (!isPinSet()) {
            enteredPin == DEFAULT_PIN
        } else {
            val storedHash = prefs.getString(KEY_PIN_HASH, "") ?: ""
            hashPin(enteredPin) == storedHash
        }

        if (isValid) {
            resetPinFailedAttempts()
        } else {
            recordFailedPinAttempt()
        }
        return isValid
    }

    fun changePin(currentPin: String, newPin: String): Boolean {
        if (!verifyPin(currentPin)) return false
        if (newPin.length != 4 || !newPin.all { it.isDigit() }) return false
        setPin(newPin)
        return true
    }

    suspend fun changePinServerSync(currentPin: String, newPin: String, apiService: com.matoshree.shopmanager.data.remote.api.MatoshreeApiService?): Boolean {
        if (!verifyPin(currentPin)) return false
        if (newPin.length != 4 || !newPin.all { it.isDigit() }) return false
        setPin(newPin)

        if (apiService != null) {
            try {
                apiService.changePin(com.matoshree.shopmanager.data.remote.dto.ChangePinRequest(current_pin = currentPin, new_pin = newPin))
            } catch (e: Exception) {}
        }
        return true
    }

    fun isRecoveryLockedOut(): Boolean {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        return System.currentTimeMillis() < lockoutUntil
    }

    fun getRemainingLockoutSeconds(): Long {
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val diff = lockoutUntil - System.currentTimeMillis()
        return if (diff > 0) (diff / 1000) else 0L
    }

    fun verifyRecoveryPassword(secret: String): Boolean {
        if (isRecoveryLockedOut()) return false

        val matches = hashRecovery(secret) == RECOVERY_HASH
        if (matches) {
            // Reset failed counter
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .remove(KEY_LOCKOUT_UNTIL)
                .apply()
            return true
        } else {
            val failed = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, failed)
            if (failed >= MAX_FAILED_ATTEMPTS) {
                editor.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_DURATION_MS)
            }
            editor.apply()
            return false
        }
    }

    fun recoverAndSetPin(recoverySecret: String, newPin: String): Boolean {
        if (!verifyRecoveryPassword(recoverySecret)) return false
        if (newPin.length != 4 || !newPin.all { it.isDigit() }) return false
        setPin(newPin)
        return true
    }

    suspend fun recoverAndSetPinServerSync(recoverySecret: String, newPin: String, apiService: com.matoshree.shopmanager.data.remote.api.MatoshreeApiService?): Boolean {
        if (!verifyRecoveryPassword(recoverySecret)) return false
        if (newPin.length != 4 || !newPin.all { it.isDigit() }) return false
        setPin(newPin)

        if (apiService != null) {
            try {
                apiService.recoverPin(com.matoshree.shopmanager.data.remote.dto.RecoverPinRequest(recovery_code = recoverySecret, new_pin = newPin))
            } catch (e: Exception) {}
        }
        return true
    }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean("biometric_enabled", true)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("biometric_enabled", enabled).apply()
    }
}

object BiometricAuthHelper {
    fun canAuthenticate(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String = "Unlock Matoshree Boutique",
        subtitle: String = "Use your fingerprint or face to authenticate",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onError("Biometric authentication failed. Try again.")
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Use PIN")
            .build()

        prompt.authenticate(promptInfo)
    }
}

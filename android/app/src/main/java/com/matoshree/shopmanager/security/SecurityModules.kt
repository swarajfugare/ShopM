package com.matoshree.shopmanager.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Keystore-backed secure storage for authentication tokens.
 * Fails closed without falling back to plaintext SharedPreferences.
 */
class TokenStorage(context: Context) {
    private val prefs: SharedPreferences? by lazy {
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
            // Fail closed: Do NOT fallback to unencrypted SharedPreferences
            null
        }
    }

    fun saveToken(token: String) {
        prefs?.edit()?.putString("jwt_token", token)?.apply()
    }

    fun getToken(): String? = prefs?.getString("jwt_token", null)

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }

    fun saveUser(userId: Long, shopId: Long, name: String, role: String) {
        prefs?.edit()
            ?.putLong("user_id", userId)
            ?.putLong("shop_id", shopId)
            ?.putString("user_name", name)
            ?.putString("user_role", role)
            ?.apply()
    }

    fun getUserName(): String = prefs?.getString("user_name", "Matoshree Admin") ?: "Matoshree Admin"
}

object LegacyPrefsPurge {
    fun purge(context: Context) {
        val legacy = listOf(
            "matoshree_security_prefs",
            "matoshree_secure_pin_store_fallback",
            "matoshree_secure_auth_prefs_fallback",
            "matoshree_fallback_tokens",
            "matoshree_google_auth_prefs"
        )
        for (name in legacy) {
            try {
                context.deleteSharedPreferences(name)
            } catch (_: Exception) {}
        }
    }
}

object AndroidKeystoreHmacSigner : HmacSigner {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PIN_KEY_ALIAS = "MatoshreePinHmacKey"

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(PIN_KEY_ALIAS)) {
            val entry = ks.getEntry(PIN_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        // Try StrongBox backed first on Android P+
        return try {
            generateKey(useStrongBox = true)
        } catch (e: Throwable) {
            // Fall back to standard AndroidKeyStore
            generateKey(useStrongBox = false)
        }
    }

    private fun generateKey(useStrongBox: Boolean): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        val specBuilder = KeyGenParameterSpec.Builder(
            PIN_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN
        ).apply {
            if (useStrongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    setIsStrongBoxBacked(true)
                } catch (_: Throwable) {}
            }
        }
        kg.init(specBuilder.build())
        return kg.generateKey()
    }

    fun deleteKey() {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(PIN_KEY_ALIAS)) {
                ks.deleteEntry(PIN_KEY_ALIAS)
            }
        } catch (_: Exception) {}
    }

    override fun computeHmac(salt: ByteArray, pin: String): ByteArray {
        val secretKey = getOrCreateKey()
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(secretKey)
        mac.update(salt)
        mac.update(pin.toByteArray(Charsets.UTF_8))
        return mac.doFinal()
    }
}

class EncryptedSharedPreferencesPinStorage(private val prefs: SharedPreferences) : PinStorage {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun getLong(key: String, defValue: Long): Long = prefs.getLong(key, defValue)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun getInt(key: String, defValue: Int): Int = prefs.getInt(key, defValue)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun clear() { prefs.edit().clear().apply() }
}

/**
 * Zero-Trust Local PIN Manager.
 * - Stores PIN using non-extractable Android Keystore HMAC-SHA256 with a cryptographically generated 16-byte random salt.
 * - Compares hashes in constant time to defeat side-channel timing attacks.
 * - Stores lockout timestamps and failure counters in Keystore-backed EncryptedSharedPreferences only.
 * - Never uses plaintext fallback: on keystore corruption, purges keys and requires re-login.
 * - Contains NO default PINs, backdoor hashes, or master passwords.
 * - Recovery is exclusively achieved by re-authenticating with the verified Google Account.
 */
class PinManager(private val context: Context) {
    init {
        LegacyPrefsPurge.purge(context)
    }

    private val engine: PinAuthEngine? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                "matoshree_secure_pin_store",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            PinAuthEngine(
                storage = EncryptedSharedPreferencesPinStorage(prefs),
                signer = AndroidKeystoreHmacSigner,
                clock = SystemClock
            )
        } catch (e: Exception) {
            // Fail closed: Never fallback to plaintext preferences!
            try {
                context.deleteSharedPreferences("matoshree_secure_pin_store")
                AndroidKeystoreHmacSigner.deleteKey()
            } catch (_: Exception) {}
            null
        }
    }

    fun isPinLockedOut(): Boolean = engine?.isPinLockedOut() ?: false
    fun getPinLockoutRemainingSeconds(): Long = engine?.getPinLockoutRemainingSeconds() ?: 0L
    fun recordFailedPinAttempt(): Long = engine?.recordFailedPinAttempt() ?: 0L
    fun resetPinFailedAttempts() { engine?.resetPinFailedAttempts() }
    fun isPinSet(): Boolean = engine?.isPinSet() ?: false
    fun setPin(pin: String) {
        val eng = engine ?: throw IllegalStateException("Secure keystore unavailable. Re-authentication required.")
        eng.setPin(pin)
    }
    fun savePin(pin: String) = setPin(pin)
    fun verifyPin(enteredPin: String): Boolean = engine?.verifyPin(enteredPin) ?: false
    fun changePin(currentPin: String, newPin: String): Boolean = engine?.changePin(currentPin, newPin) ?: false
    fun resetPinAfterGoogleAuth(newPin: String): Boolean = engine?.resetPinAfterGoogleAuth(newPin) ?: false
    fun isBiometricEnabled(): Boolean = engine?.isBiometricEnabled() ?: true
    fun setBiometricEnabled(enabled: Boolean) { engine?.setBiometricEnabled(enabled) }
}

object BiometricAuthHelper {
    fun canAuthenticate(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String = "मातोश्री कलेक्शन अनलॉक करा",
        subtitle: String = "फिंगरप्रिंट किंवा फेस ऑथेंटिकेशन वापरा",
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
                onError("बायोमेट्रिक जुळले नाही. कृपया पुन्हा प्रयत्न करा.")
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("पिन वापरा")
            .build()

        prompt.authenticate(promptInfo)
    }
}

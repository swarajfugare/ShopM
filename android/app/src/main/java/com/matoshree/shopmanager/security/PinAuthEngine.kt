package com.matoshree.shopmanager.security

import java.security.MessageDigest
import java.security.SecureRandom

interface PinStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getLong(key: String, defValue: Long): Long
    fun putLong(key: String, value: Long)
    fun getInt(key: String, defValue: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun contains(key: String): Boolean
    fun remove(key: String)
    fun clear()
}

interface HmacSigner {
    fun computeHmac(salt: ByteArray, pin: String): ByteArray
}

interface Clock {
    fun currentTimeMillis(): Long
}

object SystemClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

/**
 * Decoupled PIN Authentication Core.
 * Pure business logic without Android framework dependencies, enabling deterministic JVM unit tests.
 * - Enforces HMAC-SHA256 signature verification with random 16-byte salt.
 * - Constant-time comparison defeats side-channel timing attacks.
 * - Proactive lockout: 3 failures = 30s, 5 failures = 5m, 10 failures = 15m.
 * - Rejects insecure/burned PINs (1234, 2580).
 */
class PinAuthEngine(
    private val storage: PinStorage,
    private val signer: HmacSigner,
    private val clock: Clock = SystemClock
) {
    companion object {
        const val KEY_PIN_HMAC = "secure_pin_hmac_v3"
        const val KEY_PIN_SALT = "secure_pin_salt_v3"
        const val KEY_FAILED_PIN_ATTEMPTS = "failed_pin_attempts"
        const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"

        val DISALLOWED_PINS = setOf("1234", "2580")
    }

    fun isPinLockedOut(): Boolean {
        val until = storage.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
        return clock.currentTimeMillis() < until
    }

    fun getPinLockoutRemainingSeconds(): Long {
        val until = storage.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)
        val diff = until - clock.currentTimeMillis()
        return if (diff > 0) (diff / 1000) else 0L
    }

    fun recordFailedPinAttempt(): Long {
        val count = storage.getInt(KEY_FAILED_PIN_ATTEMPTS, 0) + 1
        storage.putInt(KEY_FAILED_PIN_ATTEMPTS, count)
        val lockoutMs = when {
            count > 10 -> 60 * 60 * 1000L     // 1 hour for persistent brute-force
            count == 10 -> 15 * 60 * 1000L    // 15 mins
            count == 5 -> 5 * 60 * 1000L      // 5 mins
            count == 3 -> 30 * 1000L          // 30 secs
            else -> 0L
        }
        if (lockoutMs > 0) {
            storage.putLong(KEY_PIN_LOCKOUT_UNTIL, clock.currentTimeMillis() + lockoutMs)
        }
        return lockoutMs / 1000L
    }

    fun resetPinFailedAttempts() {
        storage.putInt(KEY_FAILED_PIN_ATTEMPTS, 0)
        storage.remove(KEY_PIN_LOCKOUT_UNTIL)
    }

    fun isPinSet(): Boolean {
        return storage.contains(KEY_PIN_HMAC) && storage.contains(KEY_PIN_SALT)
    }

    fun setPin(pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be exactly 4 digits" }
        require(pin !in DISALLOWED_PINS) { "PIN cannot be a default or previously burned PIN" }

        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val hmac = signer.computeHmac(salt, pin)

        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hmacHex = hmac.joinToString("") { "%02x".format(it) }

        storage.putString(KEY_PIN_SALT, saltHex)
        storage.putString(KEY_PIN_HMAC, hmacHex)
        resetPinFailedAttempts()
    }

    fun verifyPin(enteredPin: String): Boolean {
        if (isPinLockedOut()) return false
        val storedHmacHex = storage.getString(KEY_PIN_HMAC) ?: return false
        val storedSaltHex = storage.getString(KEY_PIN_SALT) ?: return false

        return try {
            val salt = storedSaltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val computedHmac = signer.computeHmac(salt, enteredPin)
            val storedHmac = storedHmacHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            val isValid = MessageDigest.isEqual(computedHmac, storedHmac)
            if (isValid) {
                resetPinFailedAttempts()
            } else {
                recordFailedPinAttempt()
            }
            isValid
        } catch (e: Exception) {
            recordFailedPinAttempt()
            false
        }
    }

    fun changePin(currentPin: String, newPin: String): Boolean {
        if (!verifyPin(currentPin)) return false
        if (newPin.length != 4 || !newPin.all { it.isDigit() } || newPin in DISALLOWED_PINS) return false
        setPin(newPin)
        return true
    }

    fun resetPinAfterGoogleAuth(newPin: String): Boolean {
        if (newPin.length != 4 || !newPin.all { it.isDigit() } || newPin in DISALLOWED_PINS) return false
        setPin(newPin)
        return true
    }

    fun isBiometricEnabled(): Boolean = storage.getBoolean(KEY_BIOMETRIC_ENABLED, true)

    fun setBiometricEnabled(enabled: Boolean) {
        storage.putBoolean(KEY_BIOMETRIC_ENABLED, enabled)
    }

    fun clearAll() {
        storage.clear()
    }
}

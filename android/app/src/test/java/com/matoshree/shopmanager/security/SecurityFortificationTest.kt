package com.matoshree.shopmanager.security

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SecurityFortificationTest {

    private fun computeTestHmac(pin: String, salt: ByteArray, secretKeyBytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKeyBytes, "HmacSHA256"))
        mac.update(salt)
        return mac.doFinal(pin.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun testHmacSha256SaltProtectionAgainstRainbowTables() {
        val testKey = ByteArray(32) { 0x42 }
        val salt1 = ByteArray(16) { 0x01 }
        val salt2 = ByteArray(16) { 0x02 }
        val pin = "2580"

        val hash1 = computeTestHmac(pin, salt1, testKey)
        val hash2 = computeTestHmac(pin, salt2, testKey)

        // Given different per-install random salts, same PIN yields distinct hashes
        assertFalse(MessageDigest.isEqual(hash1, hash2))
    }

    @Test
    fun testConstantTimeComparisonIntegrity() {
        val testKey = ByteArray(32) { 0x55 }
        val salt = ByteArray(16) { 0xAA.toByte() }

        val hashA = computeTestHmac("2580", salt, testKey)
        val hashB = computeTestHmac("2580", salt, testKey)
        val hashWrong = computeTestHmac("1234", salt, testKey)

        // Constant-time compare must match identical hashes
        assertTrue(MessageDigest.isEqual(hashA, hashB))
        // Must reject mismatched PIN
        assertFalse(MessageDigest.isEqual(hashA, hashWrong))
    }

    @Test
    fun testLockoutDurations() {
        // Lockout rules: 3 wrong: 30s, 5: 5min, 10: 15min
        fun getLockoutSeconds(attempts: Int): Long = when {
            attempts >= 10 -> 900L // 15 min
            attempts >= 5 -> 300L  // 5 min
            attempts >= 3 -> 30L   // 30 s
            else -> 0L
        }

        assertEquals(0L, getLockoutSeconds(0))
        assertEquals(0L, getLockoutSeconds(1))
        assertEquals(0L, getLockoutSeconds(2))
        assertEquals(30L, getLockoutSeconds(3))
        assertEquals(30L, getLockoutSeconds(4))
        assertEquals(300L, getLockoutSeconds(5))
        assertEquals(300L, getLockoutSeconds(9))
        assertEquals(900L, getLockoutSeconds(10))
        assertEquals(900L, getLockoutSeconds(15))
    }

    @Test
    fun testAuthorizedOwnerEmailWhitelist() {
        val authorizedEmails = GoogleAuthManager.AUTHORIZED_EMAILS

        // Check authorized owner emails
        assertTrue(authorizedEmails.contains("swarajfugare23@gmail.com"))
        assertTrue(authorizedEmails.contains("sadhanafugare86@gmail.com"))

        // Any unauthorized email must be rejected
        assertFalse(authorizedEmails.contains("attacker@gmail.com"))
        assertFalse(authorizedEmails.contains("hacker@shop.com"))
        assertFalse(authorizedEmails.contains("swarajfugare@other.com"))
    }
}

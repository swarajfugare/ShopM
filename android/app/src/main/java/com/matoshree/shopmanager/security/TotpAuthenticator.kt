package com.matoshree.shopmanager.security

import android.net.Uri
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object TotpAuthenticator {
    const val ACCOUNT_EMAIL = "swarajfugare23@gmail.com"
    const val ISSUER_NAME = "Matoshree Collection"
    // Standard RFC 4648 160-bit (32-character) Base32 Secret Key for swarajfugare23@gmail.com
    const val SECRET_KEY_BASE32 = "JVAVIT2TJBJEKRKTK5AVEQKKGIYDENST"
    private const val DIGITS = 6
    private const val TIME_STEP_SECONDS = 30L

    fun getFormattedSecretKey(): String {
        return SECRET_KEY_BASE32.chunked(4).joinToString(" ")
    }

    fun getOtpAuthUri(): String {
        val label = Uri.encode("$ISSUER_NAME:$ACCOUNT_EMAIL")
        val issuer = Uri.encode(ISSUER_NAME)
        return "otpauth://totp/$label?secret=$SECRET_KEY_BASE32&issuer=$issuer&algorithm=SHA1&digits=$DIGITS&period=$TIME_STEP_SECONDS"
    }

    fun verifyCode(enteredCode: String): Boolean {
        val cleanCode = enteredCode.trim().replace(" ", "").replace("-", "")
        if (cleanCode.length != DIGITS || !cleanCode.all { it.isDigit() }) return false

        val currentEpochSeconds = System.currentTimeMillis() / 1000L
        val currentStep = currentEpochSeconds / TIME_STEP_SECONDS

        // Check current time step, previous (-1), and next (+1) to accommodate 30s clock drift
        for (i in -1L..1L) {
            val step = currentStep + i
            val expectedCode = generateTotpForStep(step, SECRET_KEY_BASE32)
            if (cleanCode == expectedCode) {
                return true
            }
        }
        return false
    }

    fun generateCurrentCode(): String {
        val currentEpochSeconds = System.currentTimeMillis() / 1000L
        val step = currentEpochSeconds / TIME_STEP_SECONDS
        return generateTotpForStep(step, SECRET_KEY_BASE32)
    }

    private fun generateTotpForStep(step: Long, base32Key: String): String {
        val keyBytes = decodeBase32(base32Key)
        val data = ByteBuffer.allocate(8).putLong(step).array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        val hash = mac.doFinal(data)

        // Dynamic truncation (RFC 6238 / RFC 4226)
        val offset = (hash[hash.size - 1].toInt() and 0x0F)
        val truncatedHash = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        val otp = (truncatedHash % 10.0.pow(DIGITS.toDouble()).toInt()).toString()
        return otp.padStart(DIGITS, '0')
    }

    private fun decodeBase32(base32: String): ByteArray {
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleanInput = base32.uppercase().replace("=", "").replace(" ", "").replace("-", "")
        
        var buffer = 0
        var bitsLeft = 0
        val output = mutableListOf<Byte>()

        for (c in cleanInput) {
            val valIndex = base32Chars.indexOf(c)
            if (valIndex < 0) continue

            buffer = (buffer shl 5) or valIndex
            bitsLeft += 5

            if (bitsLeft >= 8) {
                val b = (buffer shr (bitsLeft - 8)) and 0xFF
                output.add(b.toByte())
                bitsLeft -= 8
            }
        }
        return output.toByteArray()
    }
}

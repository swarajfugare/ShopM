package com.matoshree.shopmanager.security

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

class TotpManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("matoshree_totp_prefs", Context.MODE_PRIVATE)

    companion object {
        const val ACCOUNT_EMAIL = "swarajfugare23@gmail.com"
        const val ISSUER = "Matoshree Collection"
        private const val KEY_SECRET = "totp_secret_base32"
        private const val KEY_ENABLED = "totp_enabled"
        private const val TIME_STEP_SECONDS = 30L
        private const val CODE_DIGITS = 6

        // Default deterministic base32 key for swarajfugare23@gmail.com
        // Base32 representation of "MATOSHREE_SWARAJ_2026_SECURE_KEY"
        private const val DEFAULT_SECRET = "JVQW4ZTGONUW64TJONQW64TPOJXXEYLD"
    }

    fun isTotpEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setTotpEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getSecretKey(): String {
        var secret = prefs.getString(KEY_SECRET, null)
        if (secret.isNullOrBlank()) {
            secret = DEFAULT_SECRET
            prefs.edit().putString(KEY_SECRET, secret).apply()
        }
        return secret
    }

    fun generateNewSecretKey(): String {
        val random = SecureRandom()
        val bytes = ByteArray(20)
        random.nextBytes(bytes)
        val base32 = encodeBase32(bytes)
        prefs.edit().putString(KEY_SECRET, base32).apply()
        return base32
    }

    fun getOtpAuthUri(): String {
        val secret = getSecretKey()
        val encodedIssuer = URLEncoder.encode(ISSUER, "UTF-8").replace("+", "%20")
        val encodedAccount = URLEncoder.encode(ACCOUNT_EMAIL, "UTF-8")
        return "otpauth://totp/$encodedIssuer:$encodedAccount?secret=$secret&issuer=$encodedIssuer&algorithm=SHA1&digits=$CODE_DIGITS&period=$TIME_STEP_SECONDS"
    }

    fun generateQrCodeBitmap(size: Int = 512): Bitmap? {
        return try {
            val uri = getOtpAuthUri()
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(uri, BarcodeFormat.QR_CODE, size, size)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun verifyCode(enteredCode: String): Boolean {
        val cleanCode = enteredCode.trim().replace(" ", "")
        if (cleanCode.length != CODE_DIGITS || !cleanCode.all { it.isDigit() }) return false

        val secretBytes = decodeBase32(getSecretKey())
        val currentWindow = (System.currentTimeMillis() / 1000L) / TIME_STEP_SECONDS

        // Check current window and +/- 1 window (for minor clock drift)
        for (i in -1..1) {
            val generated = generateTotp(secretBytes, currentWindow + i)
            if (generated == cleanCode) {
                return true
            }
        }
        return false
    }

    private fun generateTotp(key: ByteArray, timeStep: Long): String {
        val data = ByteBuffer.allocate(8).putLong(timeStep).array()
        val sign = Mac.getInstance("HmacSHA1")
        sign.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = sign.doFinal(data)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % (10.0.pow(CODE_DIGITS.toDouble())).toInt()
        return otp.toString().padStart(CODE_DIGITS, '0')
    }

    private fun encodeBase32(data: ByteArray): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(chars[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            out.append(chars[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return out.toString()
    }

    private fun decodeBase32(base32: String): ByteArray {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = base32.uppercase().filter { chars.contains(it) }
        val out = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (c in clean) {
            val value = chars.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}

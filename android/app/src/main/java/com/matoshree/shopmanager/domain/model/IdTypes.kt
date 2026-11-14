package com.matoshree.shopmanager.domain.model

import java.security.SecureRandom

/**
 * Permanent, globally unique, typed record identifier types.
 * Every entity in ShopM uses a prefixed, sortable, collision-proof ID.
 */
enum class IdType(val prefix: String, val description: String) {
    CUSTOMER("cus_", "Customer"),
    BILL("bil_", "Bill"),
    BILL_ITEM("bli_", "Bill Line Item"),
    PRODUCT("prd_", "Product"),
    CATEGORY("cat_", "Category"),
    STOCK_MOVEMENT("stk_", "Stock Movement"),
    UDHARI("udh_", "Udhari Credit Entry"),
    PAYMENT("pay_", "Payment / Udhari Repayment"),
    REVERSAL("rev_", "Payment Reversal"),
    EXPENSE("exp_", "Expense"),
    DAILY_CLOSING("clo_", "Daily Closing"),
    REMINDER("rmd_", "Reminder Log / Claim"),
    DEVICE("dev_", "Device");

    companion object {
        fun fromPrefix(prefix: String): IdType? {
            return entries.find { it.prefix == prefix }
        }

        fun fromId(id: String?): IdType? {
            if (id == null || id.length < 4) return null
            val p = id.substring(0, 4)
            return fromPrefix(p)
        }
    }
}

/**
 * Validator for typed global IDs.
 */
object IdFormat {
    private const val CROCKFORD_CHARS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun isValid(id: String?): Boolean {
        if (id == null || id.length < 10) return false
        val type = IdType.fromId(id) ?: return false
        return isValid(type, id)
    }

    fun isValid(type: IdType, id: String?): Boolean {
        if (id == null) return false
        if (!id.startsWith(type.prefix)) return false
        val suffix = id.removePrefix(type.prefix)
        if (suffix.isEmpty()) return false

        // Check if legacy ID format: prefix + 'L' + Crockford Base32 string (at least 2 chars)
        if (suffix.startsWith("L")) {
            val legacyEncoded = suffix.substring(1)
            if (legacyEncoded.length < 2) return false
            return legacyEncoded.all { CROCKFORD_CHARS.contains(it, ignoreCase = true) }
        }

        // Native ULID format: exactly 26 Crockford Base32 characters
        if (suffix.length != 26) return false
        return suffix.all { CROCKFORD_CHARS.contains(it, ignoreCase = true) }
    }

    fun isLegacy(id: String?): Boolean {
        if (id == null) return false
        val type = IdType.fromId(id) ?: return false
        val suffix = id.removePrefix(type.prefix)
        return suffix.startsWith("L")
    }

    fun extractType(id: String?): IdType? = IdType.fromId(id)
}

/**
 * Thread-safe, monotonic, collision-proof ULID generator for offline-first multi-phone use.
 * Format: <prefix><26-char Crockford Base32 ULID>
 * Monotonic timestamp (48-bit) + SecureRandom (80-bit)
 */
object IdGenerator {
    private const val CROCKFORD_CHARS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val secureRandom = SecureRandom()

    private val lock = Any()
    private var lastTimestampMs = -1L
    private val lastRandomBytes = ByteArray(10)

    /**
     * Generates a new, time-ordered, globally unique ID with the specified type prefix.
     */
    fun generate(type: IdType, timestampMs: Long = System.currentTimeMillis()): String {
        val entropy = ByteArray(10)
        var ts = timestampMs

        synchronized(lock) {
            if (ts <= lastTimestampMs) {
                ts = lastTimestampMs
                // Increment random bytes monotonically within same millisecond
                var carry = true
                for (i in 9 downTo 0) {
                    if (!carry) break
                    val b = (lastRandomBytes[i].toInt() and 0xFF) + 1
                    lastRandomBytes[i] = (b and 0xFF).toByte()
                    carry = b > 0xFF
                }
                if (carry) {
                    // Millisecond overflow, increment timestamp
                    ts++
                    lastTimestampMs = ts
                    secureRandom.nextBytes(lastRandomBytes)
                }
            } else {
                lastTimestampMs = ts
                secureRandom.nextBytes(lastRandomBytes)
            }
            System.arraycopy(lastRandomBytes, 0, entropy, 0, 10)
        }

        val chars = CharArray(26)
        encodeTimestamp(ts, chars)
        encodeEntropy(entropy, chars)

        return type.prefix + String(chars)
    }

    /**
     * Deterministically derives a permanent legacy GID from an existing Firestore document ID.
     * Both phones running this on the same legacy docId will produce the exact same GID.
     * Format: <prefix>L<base32-encoded docId>
     */
    fun legacyGid(type: IdType, legacyDocId: String): String {
        val trimmed = legacyDocId.trim()
        if (trimmed.isEmpty()) {
            return generate(type)
        }
        // If already a valid GID of this type, keep it as is
        if (IdFormat.isValid(type, trimmed)) {
            return trimmed
        }

        // Encode string bytes into Crockford Base32
        val bytes = trimmed.toByteArray(Charsets.UTF_8)
        val encoded = encodeBytesToBase32(bytes)
        return "${type.prefix}L$encoded"
    }

    // F1: One deterministic rule per entity type used everywhere
    fun customerGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.CUSTOMER, docId) else generate(IdType.CUSTOMER)

    fun newCustomerGid(): String = generate(IdType.CUSTOMER)

    /**
     * Canonical customer docId and gid computation (P0-B).
     * Single function used before Room and Firestore writes.
     * - With 10-digit mobile: docId = cust_m_<mobile>, gid = customerGid(docId)
     * - Without mobile:
     *   - If existing docId or gid exists, preserve it.
     *   - Otherwise, generate new ULID: gid = generate(IdType.CUSTOMER), docId = gid.
     */
    fun canonicalCustomerDocIdAndGid(
        mobile: String?,
        existingDocId: String? = null,
        existingGid: String? = null
    ): Pair<String, String> {
        val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile ?: "")
        return if (cleanMob.length == 10) {
            val docId = "cust_m_$cleanMob"
            Pair(docId, customerGid(docId))
        } else if (!existingDocId.isNullOrBlank()) {
            val gid = if (!existingGid.isNullOrBlank() && IdFormat.isValid(existingGid)) existingGid else customerGid(existingDocId)
            Pair(existingDocId, gid)
        } else if (!existingGid.isNullOrBlank() && IdFormat.isValid(existingGid)) {
            Pair(existingGid, existingGid)
        } else {
            val newGid = generate(IdType.CUSTOMER)
            Pair(newGid, newGid)
        }
    }

    fun billGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.BILL, docId) else generate(IdType.BILL)

    fun newBillGid(): String = generate(IdType.BILL)

    fun billItemGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.BILL_ITEM, docId) else generate(IdType.BILL_ITEM)

    fun productGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.PRODUCT, docId) else generate(IdType.PRODUCT)

    fun categoryGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.CATEGORY, docId) else generate(IdType.CATEGORY)

    fun stockMovementGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.STOCK_MOVEMENT, docId) else generate(IdType.STOCK_MOVEMENT)

    fun udhariGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.UDHARI, docId) else generate(IdType.UDHARI)

    fun paymentGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.PAYMENT, docId) else generate(IdType.PAYMENT)

    fun reversalGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.REVERSAL, docId) else generate(IdType.REVERSAL)

    fun expenseGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.EXPENSE, docId) else generate(IdType.EXPENSE)

    fun dailyClosingGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.DAILY_CLOSING, docId) else generate(IdType.DAILY_CLOSING)

    fun reminderGid(docId: String?): String =
        if (!docId.isNullOrBlank()) legacyGid(IdType.REMINDER, docId) else generate(IdType.REMINDER)

    private fun encodeTimestamp(timeMs: Long, chars: CharArray) {
        var t = timeMs
        for (i in 9 downTo 0) {
            chars[i] = CROCKFORD_CHARS[(t and 0x1FL).toInt()]
            t = t ushr 5
        }
    }

    private fun encodeEntropy(entropy: ByteArray, chars: CharArray) {
        var buffer = 0
        var bitsLeft = 0
        var charIdx = 10

        for (b in entropy) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val idx = (buffer ushr bitsLeft) and 0x1F
                chars[charIdx++] = CROCKFORD_CHARS[idx]
            }
        }
        if (bitsLeft > 0) {
            val idx = (buffer shl (5 - bitsLeft)) and 0x1F
            chars[charIdx] = CROCKFORD_CHARS[idx]
        }
    }

    private fun encodeBytesToBase32(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val idx = (buffer ushr bitsLeft) and 0x1F
                sb.append(CROCKFORD_CHARS[idx])
            }
        }
        if (bitsLeft > 0) {
            val idx = (buffer shl (5 - bitsLeft)) and 0x1F
            sb.append(CROCKFORD_CHARS[idx])
        }
        return sb.toString()
    }
}

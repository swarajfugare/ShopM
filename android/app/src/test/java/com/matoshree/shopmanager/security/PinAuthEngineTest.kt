package com.matoshree.shopmanager.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class FakePinStorage : PinStorage {
    private val stringStore = mutableMapOf<String, String>()
    private val longStore = mutableMapOf<String, Long>()
    private val intStore = mutableMapOf<String, Int>()
    private val boolStore = mutableMapOf<String, Boolean>()

    override fun getString(key: String): String? = stringStore[key]
    override fun putString(key: String, value: String) { stringStore[key] = value }
    override fun getLong(key: String, defValue: Long): Long = longStore[key] ?: defValue
    override fun putLong(key: String, value: Long) { longStore[key] = value }
    override fun getInt(key: String, defValue: Int): Int = intStore[key] ?: defValue
    override fun putInt(key: String, value: Int) { intStore[key] = value }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = boolStore[key] ?: defValue
    override fun putBoolean(key: String, value: Boolean) { boolStore[key] = value }
    override fun contains(key: String): Boolean = stringStore.containsKey(key)
    override fun remove(key: String) {
        stringStore.remove(key)
        longStore.remove(key)
        intStore.remove(key)
        boolStore.remove(key)
    }
    override fun clear() {
        stringStore.clear()
        longStore.clear()
        intStore.clear()
        boolStore.clear()
    }
}

class FakeHmacSigner : HmacSigner {
    private val key = SecretKeySpec("test_hmac_secret_key_32bytes_len".toByteArray(), "HmacSHA256")
    override fun computeHmac(salt: ByteArray, pin: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        mac.update(salt)
        mac.update(pin.toByteArray(Charsets.UTF_8))
        return mac.doFinal()
    }
}

class FakeClock(var currentTime: Long = 1_000_000L) : Clock {
    override fun currentTimeMillis(): Long = currentTime
    fun advanceSeconds(seconds: Long) {
        currentTime += seconds * 1000L
    }
}

class PinAuthEngineTest {

    private lateinit var storage: FakePinStorage
    private lateinit var signer: FakeHmacSigner
    private lateinit var clock: FakeClock
    private lateinit var engine: PinAuthEngine

    @Before
    fun setup() {
        storage = FakePinStorage()
        signer = FakeHmacSigner()
        clock = FakeClock()
        engine = PinAuthEngine(storage, signer, clock)
    }

    @Test
    fun testNoDefaultOrBackdoorPinInitially() {
        assertFalse("PIN must not be set initially", engine.isPinSet())
        assertFalse("Must not verify any PIN when not set", engine.verifyPin("0000"))
        assertFalse("Must not verify default candidate", engine.verifyPin("9999"))
    }

    @Test
    fun testRejectsDisallowedPins() {
        try {
            engine.setPin("1234")
            fail("Expected exception for disallowed PIN 1234")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("burned", ignoreCase = true))
        }

        try {
            engine.setPin("2580")
            fail("Expected exception for disallowed PIN 2580")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("burned", ignoreCase = true))
        }
    }

    @Test
    fun testCorrectPinSucceedsAndWrongPinFails() {
        val validPin = "7391"
        engine.setPin(validPin)
        assertTrue("PIN should now be set", engine.isPinSet())

        // Correct PIN
        assertTrue("Correct PIN must verify true", engine.verifyPin(validPin))

        // Wrong PIN
        assertFalse("Wrong PIN must verify false", engine.verifyPin("8462"))
    }

    @Test
    fun testLockoutProgressionAndRecovery() {
        val pin = "7391"
        engine.setPin(pin)

        // 1st failure
        assertFalse(engine.verifyPin("0001"))
        assertFalse(engine.isPinLockedOut())

        // 2nd failure
        assertFalse(engine.verifyPin("0002"))
        assertFalse(engine.isPinLockedOut())

        // 3rd failure -> 30s lockout
        assertFalse(engine.verifyPin("0003"))
        assertTrue("Must be locked out after 3 failures", engine.isPinLockedOut())
        assertEquals(30L, engine.getPinLockoutRemainingSeconds())

        // During lockout, even correct PIN is rejected
        assertFalse("During lockout correct PIN is blocked", engine.verifyPin(pin))

        // Advance 31 seconds -> lockout expires
        clock.advanceSeconds(31)
        assertFalse("Lockout should expire after 30s", engine.isPinLockedOut())

        // 4th failure
        assertFalse(engine.verifyPin("0004"))

        // 5th failure -> 5 minutes (300s) lockout
        assertFalse(engine.verifyPin("0005"))
        assertTrue("Must be locked out after 5 failures", engine.isPinLockedOut())
        assertEquals(300L, engine.getPinLockoutRemainingSeconds())

        // Advance 301 seconds -> lockout expires
        clock.advanceSeconds(301)
        assertFalse(engine.isPinLockedOut())

        // 5 more failures (total 10) -> 15 minutes (900s) lockout
        for (i in 6..10) {
            assertFalse(engine.verifyPin("00$i"))
        }
        assertTrue("Must be locked out after 10 failures", engine.isPinLockedOut())
        assertEquals(900L, engine.getPinLockoutRemainingSeconds())
    }

    @Test
    fun testCountersResetAfterSuccessfulAuthentication() {
        val pin = "7391"
        engine.setPin(pin)

        // 2 failed attempts
        engine.verifyPin("0001")
        engine.verifyPin("0002")

        // Successful authentication
        assertTrue(engine.verifyPin(pin))

        // Failed attempts must be reset to 0
        assertFalse(engine.isPinLockedOut())
        // Next 2 failed attempts should not lock out
        engine.verifyPin("0001")
        engine.verifyPin("0002")
        assertFalse("Counters were reset so 2 failures should not lock out", engine.isPinLockedOut())
    }

    @Test
    fun testChangePin() {
        val oldPin = "7391"
        val newPin = "8462"
        engine.setPin(oldPin)

        // Wrong current PIN fails
        assertFalse(engine.changePin("0000", newPin))
        // Disallowed new PIN fails
        assertFalse(engine.changePin(oldPin, "1234"))
        assertFalse(engine.changePin(oldPin, "2580"))

        // Valid change succeeds
        assertTrue(engine.changePin(oldPin, newPin))
        assertFalse("Old PIN must no longer verify", engine.verifyPin(oldPin))
        assertTrue("New PIN must verify", engine.verifyPin(newPin))
    }

    @Test
    fun testResetPinAfterGoogleAuth() {
        val newPin = "9632"
        assertTrue(engine.resetPinAfterGoogleAuth(newPin))
        assertTrue(engine.verifyPin(newPin))
    }

    @Test
    fun testSourceCodeSecurityScanForBannedBackdoorTokens() {
        // Scans src/main for banned security tokens: DEFAULT_PIN, MATOSHREE2026, signInAnonymously
        val bannedTokens = listOf(
            "DEFAULT_PIN",
            "MATOSHREE2026",
            "signInAnonymously"
        )

        // Traverse upwards to find android/app/src/main
        var srcMainDir = File("src/main")
        if (!srcMainDir.exists()) {
            srcMainDir = File("android/app/src/main")
        }
        if (!srcMainDir.exists()) {
            srcMainDir = File("../android/app/src/main")
        }
        assertTrue("src/main directory must exist for security scan: ${srcMainDir.absolutePath}", srcMainDir.exists())

        val violations = mutableListOf<String>()
        srcMainDir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { file ->
            val content = file.readText()
            for (token in bannedTokens) {
                if (content.contains(token)) {
                    violations.add("Banned token '$token' found in ${file.name}")
                }
            }
        }

        assertTrue("Security scan found banned backdoor tokens in source code: $violations", violations.isEmpty())
    }
}

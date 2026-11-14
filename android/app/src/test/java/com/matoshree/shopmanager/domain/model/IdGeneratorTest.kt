package com.matoshree.shopmanager.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class IdGeneratorTest {

    @Test
    fun testFormatPerType() {
        for (type in IdType.entries) {
            val id = IdGenerator.generate(type)
            assertTrue("ID must start with ${type.prefix}: $id", id.startsWith(type.prefix))
            assertEquals("ID length must be 30 chars (4 prefix + 26 ULID): $id", 30, id.length)
            assertTrue("IdFormat.isValid must accept generated id: $id", IdFormat.isValid(type, id))
            assertTrue("IdFormat.isValid generic must accept id: $id", IdFormat.isValid(id))
            assertEquals("IdFormat.extractType must match", type, IdFormat.extractType(id))
            assertFalse("Native generated ID must not be legacy: $id", IdFormat.isLegacy(id))
        }
    }

    @Test
    fun testValidatorRejectsWrongPrefixAndInvalidChars() {
        val custId = IdGenerator.generate(IdType.CUSTOMER)
        assertFalse("Bill validator must reject customer ID", IdFormat.isValid(IdType.BILL, custId))
        assertFalse("Validator must reject null", IdFormat.isValid(null))
        assertFalse("Validator must reject empty string", IdFormat.isValid(""))
        assertFalse("Validator must reject short string", IdFormat.isValid("cus_123"))
        assertFalse("Validator must reject invalid base32 characters like 'U'", IdFormat.isValid("cus_01JD8X4N7Q2K9M3T5V6W8Y0ZAU"))
        assertFalse("Validator must reject invalid base32 characters like 'I'", IdFormat.isValid("cus_01JD8X4N7Q2K9M3T5V6W8Y0ZAI"))
    }

    @Test
    fun testLegacyGidDeterministicAndRecognized() {
        val docId = "cust_m_9822001122"
        val legacyGid1 = IdGenerator.legacyGid(IdType.CUSTOMER, docId)
        val legacyGid2 = IdGenerator.legacyGid(IdType.CUSTOMER, docId)

        assertEquals("Legacy GID must be 100% deterministic", legacyGid1, legacyGid2)
        assertTrue("Legacy GID must start with cus_L", legacyGid1.startsWith("cus_L"))
        assertTrue("IdFormat.isLegacy must return true", IdFormat.isLegacy(legacyGid1))
        assertTrue("IdFormat.isValid must accept legacy GID", IdFormat.isValid(IdType.CUSTOMER, legacyGid1))
    }

    @Test
    fun testMonotonicTimeOrdering() {
        val t0 = 1700000000000L
        val id1 = IdGenerator.generate(IdType.BILL, t0)
        val id2 = IdGenerator.generate(IdType.BILL, t0)
        val id3 = IdGenerator.generate(IdType.BILL, t0 + 1000L)

        assertTrue("id2 must be lexicographically greater than id1 in same ms", id2 > id1)
        assertTrue("id3 must be lexicographically greater than id2 in next ms", id3 > id2)
    }

    @Test
    fun testThreadSafetyAndUniquenessAcrossManyIds() {
        val count = 200_000 // High volume test (200k in parallel threads)
        val threads = 8
        val executor = Executors.newFixedThreadPool(threads)
        val generated = ConcurrentHashMap<String, Boolean>()
        val latch = CountDownLatch(threads)

        for (t in 0 until threads) {
            executor.submit {
                try {
                    for (i in 0 until (count / threads)) {
                        val id = IdGenerator.generate(IdType.BILL)
                        val prev = generated.put(id, true)
                        assertNull("Duplicate ID generated: $id", prev)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()
        assertEquals("All generated IDs must be unique", count, generated.size)
    }
}

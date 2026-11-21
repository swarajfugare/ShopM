package com.matoshree.shopmanager.invoicescan

import com.matoshree.shopmanager.domain.usecase.BarcodeAllocator
import com.matoshree.shopmanager.domain.usecase.BarcodeSequenceGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class BarcodeAllocatorTest {

    // In-memory atomic gateway simulating Firestore transaction
    class FakeAtomicBarcodeGateway(
        private val initialCounter: Long = 10000L,
        private val isOffline: Boolean = false
    ) : BarcodeSequenceGateway {
        private val counter = AtomicLong(initialCounter)
        private val lock = Any()

        override suspend fun allocateNextSku(existingSkus: Set<String>): Result<String> {
            if (isOffline) {
                return Result.failure(Exception("Offline / no network"))
            }

            synchronized(lock) {
                var candidate = counter.get()
                while (candidate <= 99999L && existingSkus.contains(candidate.toString())) {
                    candidate++
                }

                if (candidate > 99999L) {
                    var foundHole: Long? = null
                    for (h in 10000L..99999L) {
                        if (!existingSkus.contains(h.toString())) {
                            foundHole = h
                            break
                        }
                    }
                    if (foundHole == null) {
                        return Result.failure(Exception("BARCODE_EXHAUSTED: Range 10000-99999 full"))
                    }
                    candidate = foundHole
                }

                counter.set(candidate + 1)
                return Result.success(candidate.toString())
            }
        }
    }

    @Test
    fun testAllocatedBarcodeIs5Digits() = runBlocking {
        val allocator = BarcodeAllocator(FakeAtomicBarcodeGateway())
        val result = allocator.allocateSku(emptySet())
        assertTrue(result.isSuccess)
        val sku = result.getOrThrow()
        assertEquals(5, sku.length)
        val num = sku.toInt()
        assertTrue(num in 10000..99999)
    }

    @Test
    fun testNeverAllocatesExistingSku() = runBlocking {
        val existing = setOf("10000", "10001", "10002")
        val allocator = BarcodeAllocator(FakeAtomicBarcodeGateway(10000L))
        val result = allocator.allocateSku(existing)
        assertTrue(result.isSuccess)
        assertEquals("10003", result.getOrThrow())
    }

    @Test
    fun testTwoPhonesRacingNeverGetSameBarcode() = runBlocking {
        val gateway = FakeAtomicBarcodeGateway(20000L)
        val allocator1 = BarcodeAllocator(gateway)
        val allocator2 = BarcodeAllocator(gateway)

        val allocated = ConcurrentHashMap.newKeySet<String>()

        // 100 concurrent allocation requests between Phone 1 and Phone 2
        val jobs = (1..50).map {
            async(Dispatchers.Default) {
                val res1 = allocator1.allocateSku(allocated)
                val sku1 = res1.getOrThrow()
                allocated.add(sku1)

                val res2 = allocator2.allocateSku(allocated)
                val sku2 = res2.getOrThrow()
                allocated.add(sku2)
            }
        }
        jobs.awaitAll()

        // 100 requests should produce exactly 100 unique 5-digit SKUs
        assertEquals(100, allocated.size)
        allocated.forEach { sku ->
            assertEquals(5, sku.length)
            assertTrue(sku.toInt() in 20000..20105)
        }
    }

    @Test
    fun testExhaustionWhenRangeIsFull() = runBlocking {
        // Pre-fill all 90,000 SKUs
        val all90000 = (10000..99999).map { it.toString() }.toSet()
        val allocator = BarcodeAllocator(FakeAtomicBarcodeGateway(99999L))
        val result = allocator.allocateSku(all90000)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("BARCODE_EXHAUSTED") == true)
    }

    @Test
    fun testOfflineReturnsGracefulError() = runBlocking {
        val allocator = BarcodeAllocator(FakeAtomicBarcodeGateway(isOffline = true))
        val result = allocator.allocateSku(emptySet())
        assertTrue(result.isFailure)
    }
}

package com.matoshree.shopmanager.domain.usecase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import kotlinx.coroutines.tasks.await

interface BarcodeSequenceGateway {
    suspend fun allocateNextSku(existingSkus: Set<String>): Result<String>
}

class FirestoreBarcodeSequenceGateway(
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseManager.firestore }
) : BarcodeSequenceGateway {

    override suspend fun allocateNextSku(existingSkus: Set<String>): Result<String> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val db = firestoreProvider()
            val docRef = db.collection("settings").document("barcode_sequence")

            val allocated = db.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                var current = snapshot.getLong("nextSku") ?: 10000L
                if (current < 10000L) current = 10000L

                var candidate = current
                while (candidate <= 99999L && existingSkus.contains(candidate.toString())) {
                    candidate++
                }

                if (candidate > 99999L) {
                    // Try to find any unallocated hole in range 10000..99999
                    var foundHole: Long? = null
                    for (h in 10000L..99999L) {
                        if (!existingSkus.contains(h.toString())) {
                            foundHole = h
                            break
                        }
                    }
                    if (foundHole == null) {
                        throw IllegalStateException("BARCODE_EXHAUSTED: सर्व ९०,००० बारकोड कोड संपले आहेत")
                    }
                    candidate = foundHole
                }

                val nextValue = (candidate + 1).coerceAtMost(100000L)
                transaction.set(
                    docRef,
                    mapOf("nextSku" to nextValue, "updatedAt" to System.currentTimeMillis()),
                    SetOptions.merge()
                )

                candidate.toString()
            }.await()

            Result.success(allocated)
        } catch (e: Exception) {
            val msg = if (e.message?.contains("BARCODE_EXHAUSTED") == true) {
                "सर्व बारकोड कोड संपले आहेत (Range 10000-99999 full)"
            } else {
                "बारकोड तयार करताना त्रुटी आली. इंटरनेट चालू आहे का तपासा: ${e.message}"
            }
            Result.failure(Exception(msg, e))
        }
    }
}

class BarcodeAllocator(
    private val gateway: BarcodeSequenceGateway = FirestoreBarcodeSequenceGateway()
) {
    suspend fun allocateSku(existingSkus: Set<String>): Result<String> {
        return gateway.allocateNextSku(existingSkus)
    }

    suspend fun allocateBatch(count: Int, existingSkus: Set<String>): Result<List<String>> {
        val allocatedList = mutableListOf<String>()
        val mutableExisting = existingSkus.toMutableSet()
        for (i in 0 until count) {
            val res = gateway.allocateNextSku(mutableExisting)
            if (res.isFailure) {
                return Result.failure(res.exceptionOrNull() ?: Exception("Barcode allocation failed"))
            }
            val sku = res.getOrThrow()
            allocatedList.add(sku)
            mutableExisting.add(sku)
        }
        return Result.success(allocatedList)
    }
}

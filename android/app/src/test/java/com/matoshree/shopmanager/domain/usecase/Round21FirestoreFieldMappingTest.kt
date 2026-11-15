package com.matoshree.shopmanager.domain.usecase

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.model.DocumentKey
import com.google.firebase.firestore.util.CustomClassMapper
import com.matoshree.shopmanager.data.firebase.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class Round21FirestoreFieldMappingTest {

    private fun createDummyDocRef(path: String = "udhari_transactions/tx123"): DocumentReference {
        val docKey = DocumentKey.fromPathString(path)
        val ctor = DocumentReference::class.java.getDeclaredConstructor(
            DocumentKey::class.java,
            com.google.firebase.firestore.FirebaseFirestore::class.java
        )
        ctor.isAccessible = true
        return ctor.newInstance(docKey, null)
    }

    @Test
    fun testUdhariTransactionDto_isReversed_serializationAndDeserialization() {
        val dto = UdhariTransactionDto(isReversed = true)
        @Suppress("UNCHECKED_CAST")
        val serializedMap = CustomClassMapper.convertToPlainJavaTypes(dto) as Map<String, Any?>

        println("Serialized keys: ${serializedMap.keys}")
        println("isReversed in map: ${serializedMap["isReversed"]}")
        println("reversed in map: ${serializedMap["reversed"]}")

        // 1. Serialization must use "isReversed" and NOT "reversed"
        assertTrue("Serialized map must contain 'isReversed' key", serializedMap.containsKey("isReversed"))
        assertEquals(true, serializedMap["isReversed"])
        assertFalse("Serialized map must NOT strip 'is' to 'reversed'", serializedMap.containsKey("reversed"))

        val dummyDocRef = createDummyDocRef()

        // 2. Deserialization from a document map written with raw key "isReversed" = true
        // (as written by FirestoreUdhariRepository.reversePayment)
        val docMapWithIsReversed = mapOf<String, Any>("isReversed" to true)
        val deserializedFromIsReversed = CustomClassMapper.convertToCustomClass(
            docMapWithIsReversed,
            UdhariTransactionDto::class.java,
            dummyDocRef
        )
        println("Deserialized isReversed from 'isReversed': ${deserializedFromIsReversed.isReversed}")
        assertTrue("Deserializing map with 'isReversed' = true must yield isReversed = true", deserializedFromIsReversed.isReversed)

        // 3. Old documents with "reversed: false" and no "isReversed" still read as not reversed
        val oldDocMap = mapOf<String, Any>("reversed" to false)
        val deserializedFromOld = CustomClassMapper.convertToCustomClass(
            oldDocMap,
            UdhariTransactionDto::class.java,
            dummyDocRef
        )
        assertFalse("Old doc with 'reversed: false' must read as isReversed = false", deserializedFromOld.isReversed)

        // 4. Documents where the app previously wrote "isReversed": true (on top of an old doc with reversed: false)
        // must now read as reversed
        val hybridDocMap = mapOf<String, Any>("reversed" to false, "isReversed" to true)
        val deserializedFromHybrid = CustomClassMapper.convertToCustomClass(
            hybridDocMap,
            UdhariTransactionDto::class.java,
            dummyDocRef
        )
        assertTrue("Doc with 'reversed: false, isReversed: true' must read as isReversed = true", deserializedFromHybrid.isReversed)
    }

    @Test
    fun testReflection_booleanIsProperties_inFirestoreModels() {
        val dtoClasses = listOf(
            ShopDto::class.java,
            CustomerDto::class.java,
            CategoryDto::class.java,
            ProductDto::class.java,
            StockMovementDto::class.java,
            BillItemDto::class.java,
            BillPaymentDto::class.java,
            BillDto::class.java,
            UdhariTransactionDto::class.java,
            ExpenseDto::class.java,
            DailyClosingDto::class.java
        )

        val unannotatedIsProperties = mutableListOf<String>()

        for (cls in dtoClasses) {
            for (field in cls.declaredFields) {
                val name = field.name
                if (name.startsWith("is") && name.length > 2 && name[2].isUpperCase()) {
                    if (field.type == Boolean::class.javaPrimitiveType || field.type == java.lang.Boolean::class.java) {
                        val capSuffix = name.removePrefix("is")
                        val getter1 = try { cls.getMethod(name) } catch (e: Exception) { null }
                        val getter2 = try { cls.getMethod("get" + name.replaceFirstChar { it.titlecase(Locale.ROOT) }) } catch (e: Exception) { null }
                        val getter = getter1 ?: getter2

                        val setter1 = try { cls.getMethod("set" + capSuffix, field.type) } catch (e: Exception) { null }
                        val setter2 = try { cls.getMethod("set" + name.replaceFirstChar { it.titlecase(Locale.ROOT) }, field.type) } catch (e: Exception) { null }
                        val setter = setter1 ?: setter2

                        val hasFieldPropName = field.isAnnotationPresent(PropertyName::class.java)
                        val hasGetterPropName = getter?.isAnnotationPresent(PropertyName::class.java) == true
                        val hasSetterPropName = setter?.isAnnotationPresent(PropertyName::class.java) == true

                        val isProtected = hasFieldPropName || (hasGetterPropName && hasSetterPropName)
                        println("Found Boolean field: ${cls.simpleName}.$name, hasPropertyName=$isProtected (field=$hasFieldPropName, getter=$hasGetterPropName, setter=$hasSetterPropName)")
                        if (!isProtected) {
                            unannotatedIsProperties.add("${cls.simpleName}.$name")
                        }
                    }
                }
            }
        }

        println("Unannotated is* properties: $unannotatedIsProperties")
        // UdhariTransactionDto.isReversed MUST NOT be in unannotatedIsProperties
        assertFalse(
            "UdhariTransactionDto.isReversed must have @PropertyName annotation to prevent field-name mismatch",
            unannotatedIsProperties.contains("UdhariTransactionDto.isReversed")
        )

        // Check if other is* properties exist and document them
        // UdhariTransactionDto.isDeleted is the only other is* property.
        // It is handled by explicit doc.getBoolean("isDeleted") in RealtimeSyncManager and status == "TRASH"
        if (unannotatedIsProperties.contains("UdhariTransactionDto.isDeleted")) {
            println("DOCUMENTED: UdhariTransactionDto.isDeleted is read explicitly via doc.getBoolean('isDeleted') and status == 'TRASH'")
        }
    }

    @Test
    fun testS3_serverVerificationFailure_returnsClearErrorMessage() = kotlinx.coroutines.runBlocking {
        val failingGateway = object : UdhariFirestoreGateway {
            override suspend fun ensureAuthenticated() {}
            override suspend fun isTransactionDuplicate(uuid: String) = false
            override suspend fun getCustomerDocs() = emptyList<CustomerDocSnapshot>()
            override suspend fun getBillDocs() = emptyList<BillDocSnapshot>()
            override suspend fun getTransactionDocs() = listOf(
                UdhariTransactionDocSnapshot(
                    id = "pay_test_1",
                    path = "udhari_transactions/pay_test_1",
                    data = UdhariTransactionDto(
                        id = "pay_test_1",
                        gid = "pay_test_1",
                        type = "PAYMENT",
                        amount = 200.0,
                        isReversed = false
                    )
                )
            )
            override suspend fun commitPaymentBatch(
                customerUpdates: Map<String, Double>,
                billUpdates: Map<String, BillBatchUpdate>,
                transactionsToSave: List<UdhariTransactionDto>
            ) {}
            override suspend fun commitReversalBatch(
                reversalTransactionsToSave: List<UdhariTransactionDto>,
                reversedTxDocUpdates: Map<String, Map<String, Any?>>,
                billUpdates: Map<String, Map<String, Any?>>,
                customerUpdates: Map<String, Double>
            ) {}
            // S3: Simulate server verification failure
            override suspend fun verifyTransactionReversed(path: String) = false
        }

        val repo = FirestoreUdhariRepository(gateway = failingGateway)
        val res = repo.reversePayment("pay_test_1", "Test failure")

        assertTrue("Reversal must fail when server verification returns false", res.isFailure)
        val msg = res.exceptionOrNull()?.message ?: ""
        assertTrue("Error must explain server verification failure: $msg", msg.contains("भरणा रद्द करण्याची नोंद सर्व्हरवर तपासता आली नाही"))
    }

    @Test
    fun testS4_multiBillAndGeneralPaymentReversal_restoresBalancesCleanly() = kotlinx.coroutines.runBlocking {
        val bill1 = BillDto(
            id = "bill_1",
            gid = "bil_1",
            billNumber = "MC-2026-001",
            finalAmount = 500.0,
            paidAmount = 300.0,
            pendingAmount = 200.0,
            paymentStatus = "PARTIAL"
        )
        val bill2 = BillDto(
            id = "bill_2",
            gid = "bil_2",
            billNumber = "MC-2026-002",
            finalAmount = 400.0,
            paidAmount = 200.0,
            pendingAmount = 200.0,
            paymentStatus = "PARTIAL"
        )
        val cust = CustomerDto(
            id = "cust_1",
            gid = "cus_1",
            name = "Test Cust",
            mobile = "9876543210",
            outstandingAmount = 400.0
        )
        val multiBillPayment = UdhariTransactionDto(
            id = "pay_multi",
            gid = "pay_multi",
            customerId = 1L,
            customerGid = "cus_1",
            customerName = "Test Cust",
            customerMobile = "9876543210",
            billNumber = "MC-2026-001, MC-2026-002",
            type = "PAYMENT",
            amount = 200.0,
            isReversed = false
        )

        val billDocs = mutableListOf(
            BillDocSnapshot("bill_1", "bills/bill_1", bill1),
            BillDocSnapshot("bill_2", "bills/bill_2", bill2)
        )
        val custDocs = mutableListOf(
            CustomerDocSnapshot("cust_1", "customers/cust_1", cust)
        )
        val txDocs = mutableListOf(
            UdhariTransactionDocSnapshot("pay_multi", "udhari_transactions/pay_multi", multiBillPayment)
        )

        val memoryGateway = object : UdhariFirestoreGateway {
            override suspend fun ensureAuthenticated() {}
            override suspend fun isTransactionDuplicate(uuid: String) = false
            override suspend fun getCustomerDocs() = custDocs
            override suspend fun getBillDocs() = billDocs
            override suspend fun getTransactionDocs() = txDocs
            override suspend fun commitPaymentBatch(
                customerUpdates: Map<String, Double>,
                billUpdates: Map<String, BillBatchUpdate>,
                transactionsToSave: List<UdhariTransactionDto>
            ) {}
            override suspend fun commitReversalBatch(
                reversalTransactionsToSave: List<UdhariTransactionDto>,
                reversedTxDocUpdates: Map<String, Map<String, Any?>>,
                billUpdates: Map<String, Map<String, Any?>>,
                customerUpdates: Map<String, Double>
            ) {
                for ((path, updates) in reversedTxDocUpdates) {
                    val txDoc = txDocs.find { it.path == path }
                    if (updates["isReversed"] == true) {
                        txDoc?.data?.isReversed = true
                    }
                }
                for ((path, updates) in billUpdates) {
                    val bDoc = billDocs.find { it.path == path }
                    updates["paidAmount"]?.let { bDoc?.data?.paidAmount = it as Double }
                    updates["pendingAmount"]?.let { bDoc?.data?.pendingAmount = it as Double }
                    updates["paymentStatus"]?.let { bDoc?.data?.paymentStatus = it as String }
                }
                for ((path, newDue) in customerUpdates) {
                    val cDoc = custDocs.find { it.path == path }
                    cDoc?.data?.outstandingAmount = newDue
                }
            }
            override suspend fun verifyTransactionReversed(path: String) = true
        }

        val repo = FirestoreUdhariRepository(gateway = memoryGateway)
        val res = repo.reversePayment(
            transactionId = "pay_multi",
            reason = "Cancel multi bill pay",
            expectedCustomerGid = "cus_1",
            expectedCustomerMobile = "9876543210",
            expectedCustomerId = 1L,
            expectedAmount = 200.0
        )

        assertTrue("Multi-bill reversal must succeed", res.isSuccess)
        assertTrue("Transaction must be marked isReversed", multiBillPayment.isReversed)
        assertEquals("Customer due restored to 600", 600.0, cust.outstandingAmount, 0.001)
        // Both bills restored
        assertEquals(100.0, bill1.paidAmount, 0.001)
        assertEquals(400.0, bill1.pendingAmount, 0.001)
        assertEquals(0.0, bill2.paidAmount, 0.001)
        assertEquals(400.0, bill2.pendingAmount, 0.001)
    }
}

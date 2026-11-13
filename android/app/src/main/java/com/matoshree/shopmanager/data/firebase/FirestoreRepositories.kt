package com.matoshree.shopmanager.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.matoshree.shopmanager.domain.model.*
import com.matoshree.shopmanager.domain.usecase.CalculateProfitUseCase
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PrintShareHelper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

// DocumentSnapshot resilient parsers that gracefully handle missing/differently-typed fields
fun DocumentSnapshot.toCustomerDomain(): Customer? {
    try {
        val name = getString("name") ?: getString("customerName") ?: getString("customer_name") ?: ""
        val mobile = getString("mobile") ?: getString("phone") ?: getString("customerMobile") ?: getString("customer_mobile") ?: ""
        if (name.isBlank() && mobile.isBlank()) return null

        val legacyId = getLong("legacyId") ?: getLong("id")
        val calcId = legacyId ?: (id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)

        val email = getString("email")
        val address = getString("address") ?: getString("village") ?: getString("city")
        val notes = getString("notes")

        val totalBills = (getLong("totalBills") ?: getLong("total_bills") ?: getDouble("totalBills")?.toLong() ?: 0L).toInt()
        val lifetimeSpend = getDouble("lifetimeSpend") ?: getDouble("lifetime_spend") ?: getLong("lifetimeSpend")?.toDouble() ?: getLong("lifetime_spend")?.toDouble() ?: 0.0
        val outstandingAmount = getDouble("outstandingAmount") ?: getDouble("outstanding_amount") ?: getLong("outstandingAmount")?.toDouble() ?: 0.0

        val status = getString("status") ?: "ACTIVE"
        val isArchived = status.equals("ARCHIVED", ignoreCase = true) || status.equals("TRASH", ignoreCase = true) || status.equals("DELETED", ignoreCase = true)

        val tier = getString("tier") ?: "REGULAR"
        val tags = (get("listTags") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

        return Customer(
            id = calcId,
            shopId = 1,
            name = name.ifBlank { "Customer" },
            mobile = mobile,
            email = email,
            address = address,
            notes = notes,
            totalBills = totalBills,
            lifetimeSpend = lifetimeSpend,
            creditLimit = getDouble("creditLimit"),
            outstandingBalance = outstandingAmount,
            firstPurchaseAt = getString("firstPurchaseAt"),
            lastPurchaseAt = getString("lastPurchaseAt"),
            isActive = !isArchived,
            tier = tier,
            listTags = tags
        )
    } catch (e: Exception) {
        return toObject(CustomerDto::class.java)?.apply { this.id = this@toCustomerDomain.id }?.toDomain()
    }
}

fun DocumentSnapshot.toProductDomain(): Product? {
    try {
        val name = getString("name") ?: getString("productName") ?: getString("product_name") ?: ""
        if (name.isBlank()) return null

        val legacyId = getLong("legacyId") ?: getLong("id")
        val calcId = legacyId ?: (id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)

        val sku = getString("sku") ?: getString("code")
        val catId = getLong("legacyCategoryId") ?: getLong("categoryId")
        val sellingPrice = getDouble("sellingPrice") ?: getDouble("price") ?: getDouble("selling_price") ?: getLong("sellingPrice")?.toDouble() ?: getLong("price")?.toDouble() ?: 0.0
        val costPrice = getDouble("costPrice") ?: getDouble("cost_price") ?: getLong("costPrice")?.toDouble()
        val defaultMargin = getDouble("defaultProfitMargin") ?: getLong("defaultProfitMargin")?.toDouble() ?: 25.0
        val trackInv = getBoolean("trackInventory") ?: true
        val stock = (getLong("currentStock") ?: getLong("stock") ?: getLong("quantity") ?: getDouble("currentStock")?.toLong() ?: 0L).toInt()
        val totalSold = (getLong("totalSold") ?: getDouble("totalSold")?.toLong() ?: 0L).toInt()
        val rawTotalAdded = (getLong("totalStockAdded") ?: getDouble("totalStockAdded")?.toLong() ?: 0L).toInt()
        val totalStockAdded = if (rawTotalAdded > 0) rawTotalAdded else (stock + totalSold)
        val lowStockThreshold = (getLong("lowStockThreshold") ?: 5L).toInt()
        val imageUrl = getString("imageUrl") ?: getString("image")

        val status = getString("status") ?: "ACTIVE"
        val isArchived = status.equals("ARCHIVED", ignoreCase = true) || status.equals("TRASH", ignoreCase = true) || status.equals("DELETED", ignoreCase = true)

        return Product(
            id = calcId,
            shopId = 1,
            categoryId = catId,
            name = name,
            sku = sku,
            sellingPrice = sellingPrice,
            costPrice = costPrice,
            defaultProfitMargin = defaultMargin,
            trackInventory = trackInv,
            currentStock = stock,
            totalStockAdded = totalStockAdded,
            totalSold = totalSold,
            lowStockThreshold = if (lowStockThreshold > 0) lowStockThreshold else 5,
            imageUrl = imageUrl,
            isActive = !isArchived
        )
    } catch (e: Exception) {
        return toObject(ProductDto::class.java)?.apply { this.id = this@toProductDomain.id }?.toDomain()
    }
}

fun DocumentSnapshot.toCategoryDomain(): Category? {
    try {
        val name = getString("name") ?: getString("categoryName") ?: ""
        if (name.isBlank()) return null
        val legacyId = getLong("legacyId") ?: getLong("id")
        val calcId = legacyId ?: (id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
        val description = getString("description")
        val status = getString("status") ?: "ACTIVE"
        val isActive = !status.equals("ARCHIVED", ignoreCase = true) && !status.equals("TRASH", ignoreCase = true)
        return Category(
            id = calcId,
            shopId = 1,
            name = name,
            description = description,
            isActive = isActive
        )
    } catch (e: Exception) {
        return toObject(CategoryDto::class.java)?.apply { this.id = this@toCategoryDomain.id }?.toDomain()
    }
}

fun DocumentSnapshot.toBillDomain(): Bill? {
    try {
        val billNum = getString("billNumber") ?: getString("bill_number") ?: id
        val legacyId = try { getLong("legacyId") ?: getLong("id") } catch (_: Exception) { null }
            ?: (get("legacyId") as? Number)?.toLong()
            ?: (get("id") as? Number)?.toLong()
            ?: (id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)

        val finalAmt = try { getDouble("finalAmount") ?: getDouble("total") ?: getDouble("grandTotal") ?: getLong("finalAmount")?.toDouble() } catch (_: Exception) { null }
            ?: (get("finalAmount") as? Number)?.toDouble()
            ?: (get("total") as? Number)?.toDouble()
            ?: 0.0

        val subtotal = try { getDouble("subtotal") ?: getLong("subtotal")?.toDouble() } catch (_: Exception) { null }
            ?: (get("subtotal") as? Number)?.toDouble()
            ?: finalAmt

        val paidAmt = try { getDouble("paidAmount") ?: getLong("paidAmount")?.toDouble() } catch (_: Exception) { null }
            ?: (get("paidAmount") as? Number)?.toDouble()
            ?: finalAmt

        val pendingAmt = try { getDouble("pendingAmount") ?: getLong("pendingAmount")?.toDouble() } catch (_: Exception) { null }
            ?: (get("pendingAmount") as? Number)?.toDouble()
            ?: (finalAmt - paidAmt).coerceAtLeast(0.0)

        val custName = getString("customerName") ?: getString("customer_name")
        val custMobile = getString("customerMobile") ?: getString("customer_mobile")
        val billDate = getString("billDate") ?: getString("date") ?: ""

        val status = getString("status") ?: "COMPLETED"
        val isVoid = status.equals("VOIDED", ignoreCase = true) || status.equals("TRASH", ignoreCase = true) || getBoolean("isVoided") == true

        val sTypeStr = getString("saleType") ?: "QUICK"
        val sType = try { SaleType.valueOf(sTypeStr.uppercase()) } catch (e: Exception) { SaleType.QUICK }

        val pMethodStr = getString("paymentMethod") ?: "CASH"
        val pMethod = try { PaymentMethod.valueOf(pMethodStr.uppercase()) } catch (e: Exception) { PaymentMethod.CASH }

        val pStatusStr = getString("paymentStatus") ?: if (pendingAmt <= 0.0) "PAID" else "PENDING"
        val pStatus = try { PaymentStatus.valueOf(pStatusStr.uppercase()) } catch (e: Exception) { PaymentStatus.PAID }

        val parsedCustomerId: Long? = try {
            (get("legacyCustomerId") as? Number)?.toLong()
                ?: getLong("legacyCustomerId")
                ?: (get("customerId") as? Number)?.toLong()
                ?: getString("customerId")?.removePrefix("cust_")?.toLongOrNull()
        } catch (_: Exception) {
            getString("customerId")?.removePrefix("cust_")?.toLongOrNull()
        }

        val itemsRaw = get("items") as? List<*>
        val parsedItems = itemsRaw?.mapNotNull { itemObj ->
            if (itemObj is Map<*, *>) {
                val pName = itemObj["productName"]?.toString() ?: "Item"
                val qty = (itemObj["quantity"] as? Number)?.toInt() ?: 1
                val sPrice = (itemObj["sellingPrice"] as? Number)?.toDouble() ?: 0.0
                val cPrice = (itemObj["costPrice"] as? Number)?.toDouble() ?: 0.0
                val disc = (itemObj["discountAmount"] as? Number)?.toDouble() ?: 0.0
                val lTotal = (itemObj["lineTotal"] as? Number)?.toDouble() ?: (sPrice * qty - disc)
                BillItem(
                    id = System.currentTimeMillis(),
                    billId = legacyId,
                    productId = (itemObj["legacyProductId"] as? Number)?.toLong(),
                    productName = pName,
                    sku = itemObj["sku"]?.toString(),
                    categoryId = (itemObj["legacyCategoryId"] as? Number)?.toLong(),
                    quantity = qty,
                    sellingPrice = sPrice,
                    costPrice = cPrice,
                    discountAmount = disc,
                    lineTotal = lTotal,
                    lineCost = (cPrice * qty),
                    lineProfit = (lTotal - (cPrice * qty))
                )
            } else null
        } ?: emptyList()

        return Bill(
            id = legacyId,
            shopId = 1,
            customerId = parsedCustomerId,
            customerName = custName,
            customerMobile = custMobile,
            billNumber = billNum,
            transactionUuid = getString("transactionUuid") ?: id,
            saleType = sType,
            subtotal = subtotal,
            discountType = DiscountType.NONE,
            discountValue = getDouble("discountValue") ?: 0.0,
            discountAmount = getDouble("discountAmount") ?: 0.0,
            taxAmount = getDouble("taxAmount") ?: 0.0,
            finalAmount = finalAmt,
            paidAmount = paidAmt,
            pendingAmount = pendingAmt,
            dueDate = getString("dueDate"),
            costAmount = getDouble("costAmount") ?: 0.0,
            estimatedProfit = getDouble("estimatedProfit") ?: 0.0,
            actualProfit = getDouble("actualProfit") ?: 0.0,
            profitType = ProfitType.ESTIMATED,
            paymentMethod = pMethod,
            paymentStatus = pStatus,
            note = getString("note"),
            billDate = billDate,
            shopNameSnapshot = getString("shopNameSnapshot") ?: "मातोश्री कलेक्शन",
            shopAddressSnapshot = getString("shopAddressSnapshot") ?: "",
            shopMobileSnapshot = getString("shopMobileSnapshot") ?: "",
            shopGstinSnapshot = getString("shopGstinSnapshot"),
            showGstinSnapshot = getBoolean("showGstinSnapshot") ?: true,
            syncStatus = SyncStatus.SYNCED,
            isVoided = isVoid,
            voidReason = getString("voidReason"),
            items = parsedItems
        )
    } catch (e: Exception) {
        return toObject(BillDto::class.java)?.toDomain()
    }
}

// 1. FIRESTORE CUSTOMER REPOSITORY
class FirestoreCustomerRepository {
    private val TAG = "FirestoreCustRepo"

    fun getAllCustomers(): Flow<List<Customer>> = callbackFlow {
        val rootMap = mutableMapOf<String, Customer>()
        val shopMap = mutableMapOf<String, Customer>()

        var debounceJob: kotlinx.coroutines.Job? = null
        fun emitCombined() {
            debounceJob?.cancel()
            debounceJob = launch {
                kotlinx.coroutines.delay(100)
                val combined = (rootMap.values + shopMap.values)
                    .distinctBy { it.canonicalKey() }
                    .sortedByDescending { it.id }
                trySend(combined)
            }
        }

        val rootListener = FirebaseManager.customersCollection()
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Customer root snapshot notice: ${error.message} (code: ${error.code})")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    rootMap.clear()
                    snapshot.documents.forEach { doc ->
                        doc.toCustomerDomain()?.let { cust ->
                            if (cust.isActive && cust.name.isNotBlank() && cust.name != "Walk-in") {
                                rootMap[doc.id] = cust
                            }
                        }
                    }
                    emitCombined()
                }
            }

        val shopListener = FirebaseManager.shopCustomersCollection()
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Customer shop snapshot notice: ${error.message} (code: ${error.code})")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    shopMap.clear()
                    snapshot.documents.forEach { doc ->
                        doc.toCustomerDomain()?.let { cust ->
                            if (cust.isActive && cust.name.isNotBlank() && cust.name != "Walk-in") {
                                shopMap[doc.id] = cust
                            }
                        }
                    }
                    emitCombined()
                }
            }

        awaitClose {
            rootListener.remove()
            shopListener.remove()
        }
    }.distinctUntilChanged()

    suspend fun findCustomerByMobile(mobile: String): Customer? {
        return try {
            val clean = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile)
            if (clean.length != 10) return null
            val isOnline = FirebaseManager.isNetworkAvailable()
            val rootSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(800) {
                        FirebaseManager.customersCollection().get().await()
                    } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val shopSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(800) {
                        FirebaseManager.shopCustomersCollection().get().await()
                    } ?: FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val allDocs = (rootSnap?.documents ?: emptyList()) + (shopSnap?.documents ?: emptyList())
            allDocs.mapNotNull { doc ->
                doc.toCustomerDomain()
            }.find { it.isActive && it.name.isNotBlank() && com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(it.mobile) == clean }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun findAnyCustomerByMobile(mobile: String): Customer? {
        return try {
            val clean = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mobile)
            if (clean.length != 10) return null
            val isOnline = FirebaseManager.isNetworkAvailable()
            val rootSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(800) {
                        FirebaseManager.customersCollection().get().await()
                    } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val shopSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(800) {
                        FirebaseManager.shopCustomersCollection().get().await()
                    } ?: FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val allDocs = (rootSnap?.documents ?: emptyList()) + (shopSnap?.documents ?: emptyList())
            allDocs.mapNotNull { doc ->
                val dto = doc.toObject(CustomerDto::class.java)?.apply { id = doc.id }
                if (dto != null && dto.mobile.isNotBlank() && com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(dto.mobile) == clean) {
                    doc.toCustomerDomain()
                } else null
            }.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun searchCustomers(query: String): List<Customer> {
        return try {
            val cleanQuery = query.trim()
            val isOnline = FirebaseManager.isNetworkAvailable()
            val rootSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(1000) {
                        FirebaseManager.customersCollection().get().await()
                    } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val shopSnap = try {
                if (isOnline) {
                    kotlinx.coroutines.withTimeoutOrNull(1000) {
                        FirebaseManager.shopCustomersCollection().get().await()
                    } ?: FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                } else {
                    FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
                }
            } catch (e: Exception) { null }

            val allDocs = (rootSnap?.documents ?: emptyList()) + (shopSnap?.documents ?: emptyList())
            val list = allDocs.mapNotNull { doc ->
                doc.toCustomerDomain()
            }.filter { it.isActive && it.name.isNotBlank() && it.name != "Walk-in" }
                .distinctBy { if (it.mobile.isNotBlank()) it.mobile else it.id.toString() }

            if (cleanQuery.isBlank()) list else list.filter {
                it.name.contains(cleanQuery, ignoreCase = true) ||
                it.mobile.contains(cleanQuery) ||
                (it.address?.contains(cleanQuery, ignoreCase = true) == true)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveCustomer(customer: Customer): String {
        val cleanMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(customer.mobile)
        var docId = if (cleanMobile.length == 10) {
            "cust_m_$cleanMobile"
        } else if (customer.id > 0) {
            "cust_${customer.id}"
        } else {
            "cust_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}"
        }

        val snapshot = try {
            if (FirebaseManager.isNetworkAvailable()) {
                kotlinx.coroutines.withTimeoutOrNull(2500) {
                    FirebaseManager.customersCollection().get().await()
                } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } else {
                FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            }
        } catch (e: Exception) {
            null
        }

        val existingDoc = snapshot?.documents?.find {
            val dto = it.toObject(CustomerDto::class.java)
            (cleanMobile.length == 10 && dto?.mobile?.isNotBlank() == true && com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(dto.mobile) == cleanMobile) ||
            (customer.id > 0 && (dto?.legacyId == customer.id || (it.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == customer.id)) ||
            (customer.name.isNotBlank() && dto?.name?.isNotBlank() == true && dto.name.trim().equals(customer.name.trim(), ignoreCase = true))
        }
        if (existingDoc != null) {
            docId = existingDoc.id
        }

        val dto = CustomerDto(
            id = docId,
            legacyId = customer.id.takeIf { it > 0 } ?: existingDoc?.toObject(CustomerDto::class.java)?.legacyId ?: (docId.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL),
            name = customer.name.trim(),
            mobile = cleanMobile.ifBlank { customer.mobile.trim() },
            email = customer.email?.trim()?.ifBlank { null },
            address = customer.address?.trim()?.ifBlank { null },
            notes = customer.notes?.trim()?.ifBlank { null },
            totalBills = customer.totalBills,
            lifetimeSpend = customer.lifetimeSpend,
            tier = customer.tier,
            listTags = customer.listTags,
            status = if (customer.isActive) "ACTIVE" else "ARCHIVED",
            deletedAt = null,
            firstPurchaseAt = customer.firstPurchaseAt,
            lastPurchaseAt = customer.lastPurchaseAt,
            updatedAt = System.currentTimeMillis()
        )
        val docPath = "customers/$docId"
        try {
            FirebaseManager.ensureAuthenticated()
            val task = FirebaseManager.customersCollection().document(docId).set(dto)
            FirebaseManager.awaitWriteTask(task)
            Log.d(TAG, "FIRESTORE_WRITE entity=CUSTOMER path=$docPath result=SUCCESS")
        } catch (e: Exception) {
            Log.e(TAG, "FIRESTORE_WRITE entity=CUSTOMER path=$docPath result=FAILURE code=${e.javaClass.simpleName}", e)
        }
        return docId
    }

    suspend fun updateCustomerTags(customerId: Long, tags: List<String>): Result<Unit> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val snapshot = try {
                kotlinx.coroutines.withTimeoutOrNull(600) {
                    FirebaseManager.customersCollection().get().await()
                } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) {
                null
            }
            val matchDoc = snapshot?.documents?.find {
                val dto = it.toObject(CustomerDto::class.java)
                dto?.legacyId == customerId || (it.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == customerId
            }
            if (matchDoc != null) {
                val updateMap = mutableMapOf<String, Any?>(
                    "listTags" to tags,
                    "updatedAt" to System.currentTimeMillis()
                )
                // If VIP removed, demote tier to REGULAR
                if (!tags.contains("VIP")) {
                    updateMap["tier"] = "REGULAR"
                }
                // If Kondharki removed, clear address if it was Kondharki
                val currentDto = matchDoc.toObject(CustomerDto::class.java)
                if (!tags.contains("Kondharki") && currentDto?.address?.trim().equals("Kondharki", ignoreCase = true)) {
                    updateMap["address"] = ""
                }

                FirebaseManager.customersCollection().document(matchDoc.id).update(updateMap).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteOrArchiveCustomer(customerId: Long, hintMobile: String = "", hintName: String = ""): String {
        FirebaseManager.ensureAuthenticated()
        val isOnline = FirebaseManager.isNetworkAvailable()
        val snapshot = try {
            if (isOnline) {
                kotlinx.coroutines.withTimeoutOrNull(800) {
                    FirebaseManager.customersCollection().get().await()
                } ?: FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } else {
                FirebaseManager.customersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            }
        } catch (e: Exception) {
            null
        }

        val shopSnap = try {
            if (isOnline) {
                kotlinx.coroutines.withTimeoutOrNull(800) {
                    FirebaseManager.shopCustomersCollection().get().await()
                } ?: FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } else {
                FirebaseManager.shopCustomersCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            }
        } catch (e: Exception) { null }

        val allDocs = (snapshot?.documents ?: emptyList()) + (shopSnap?.documents ?: emptyList())
        val cleanMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(hintMobile)
        val cleanName = hintName.trim()

        val matchingDocs = allDocs.filter { doc ->
            val dto = doc.toObject(CustomerDto::class.java) ?: return@filter false
            val dMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(dto.mobile)
            val dName = dto.name.trim()

            val mobileMatches = cleanMobile.length == 10 && dMobile == cleanMobile
            val nameMatches = cleanName.isNotBlank() && dName.equals(cleanName, ignoreCase = true)
            val docIdMatches = doc.id == "cust_$customerId" || doc.id == customerId.toString()
            val idMatches = dto.legacyId == customerId && (cleanName.isBlank() || nameMatches)

            mobileMatches || nameMatches || docIdMatches || idMatches
        }

        val matchDoc = matchingDocs.firstOrNull()
        val custMobile = matchDoc?.toObject(CustomerDto::class.java)?.mobile?.ifBlank { hintMobile } ?: hintMobile
        val custName = matchDoc?.toObject(CustomerDto::class.java)?.name?.trim() ?: cleanName
        val effectiveCleanMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(custMobile)

        try {
            val batch = FirebaseManager.firestore.batch()

            // 1. Find all bills of this customer across root bills AND shop bills collections
            val rootBills = try { FirebaseManager.billsCollection().get().await().documents } catch (_: Exception) { emptyList() }
            val shopBills = try { FirebaseManager.shopBillsCollection().get().await().documents } catch (_: Exception) { emptyList() }
            val allBillDocs = rootBills + shopBills

            val billsToDelete = allBillDocs.filter { doc ->
                val bDto = doc.toObject(BillDto::class.java) ?: return@filter false
                val idMatch = (customerId > 0 && bDto.legacyCustomerId == customerId) ||
                        bDto.customerId == "cust_$customerId" ||
                        bDto.customerId == customerId.toString() ||
                        (matchDoc != null && bDto.customerId == matchDoc.id)
                val bMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(bDto.customerMobile ?: "")
                val mobileMatch = cleanMobile.length == 10 &&
                        (bMobile == cleanMobile || (bDto.customerMobile != null && bDto.customerMobile!!.contains(cleanMobile)))
                val nameMatch = custName.isNotBlank() && bDto.customerName != null && bDto.customerName!!.trim().equals(custName, ignoreCase = true)
                idMatch || mobileMatch || nameMatch
            }

            // 2. For each active bill, restore product stock in Firestore
            billsToDelete.forEach { billDoc ->
                val bDto = billDoc.toObject(BillDto::class.java)
                val isVoid = bDto?.status.equals("VOIDED", ignoreCase = true) ||
                        bDto?.status.equals("TRASH", ignoreCase = true) ||
                        bDto?.paymentStatus.equals("VOID", ignoreCase = true)
                if (!isVoid) {
                    bDto?.items?.forEach { item ->
                        val pId = item.legacyProductId ?: item.productId?.removePrefix("prod_")?.toLongOrNull()
                        if (pId != null && pId > 0 && item.quantity > 0) {
                            val prodRef = FirebaseManager.productsCollection().document("prod_$pId")
                            val qty = item.quantity.toLong()
                            batch.set(
                                prodRef,
                                mapOf(
                                    "currentStock" to FieldValue.increment(qty),
                                    "totalSold" to FieldValue.increment(-qty),
                                    "updatedAt" to System.currentTimeMillis()
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                        }
                    }
                }
                // Delete bill so it's completely cleared
                batch.delete(billDoc.reference)
            }

            // 3. Find and delete all udhari transactions for this customer
            val txSnap = FirebaseManager.udhariTransactionsCollection().get().await()
            txSnap.documents.forEach { txDoc ->
                val txDto = txDoc.toObject(UdhariTransactionDto::class.java)
                val idMatch = (customerId > 0 && txDto?.customerId == customerId) || (matchDoc != null && txDto?.customerId == (matchDoc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL))
                val tMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(txDto?.customerMobile ?: "")
                val mobileMatch = cleanMobile.length == 10 &&
                        (tMobile == cleanMobile || (txDto?.customerMobile != null && txDto.customerMobile.contains(cleanMobile)))
                val nameMatch = custName.isNotBlank() && txDto?.customerName != null && txDto.customerName.trim().equals(custName, ignoreCase = true)
                if (idMatch || mobileMatch || nameMatch) {
                    batch.delete(txDoc.reference)
                }
            }

            // 4. Soft-delete customer doc to TRASH and reset stats
            if (matchingDocs.isNotEmpty()) {
                val updateData = mapOf(
                    "status" to "TRASH",
                    "isActive" to false,
                    "lifetimeSpend" to 0.0,
                    "totalBills" to 0,
                    "outstandingAmount" to 0.0,
                    "deletedAt" to System.currentTimeMillis(),
                    "updatedAt" to System.currentTimeMillis()
                )
                matchingDocs.forEach { doc ->
                    batch.update(doc.reference, updateData)
                }
            }

            batch.commit().await()
            Log.d(TAG, "FIRESTORE_WRITE entity=CUSTOMER cascade_delete_success bills_deleted=${billsToDelete.size} custId=$customerId")
        } catch (e: Exception) {
            Log.e(TAG, "FIRESTORE_WRITE entity=CUSTOMER cascade_delete_failed: ${e.message}", e)
        }
        return "Customer deleted"
    }

    suspend fun purgeCustomerHistory(customerId: Long, hintMobile: String = "", hintName: String = "") {
        try {
            FirebaseManager.ensureAuthenticated()
            val cleanMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(hintMobile)
            val cleanName = hintName.trim()
            val batch = FirebaseManager.firestore.batch()

            val rootBills = try { FirebaseManager.billsCollection().get().await().documents } catch (_: Exception) { emptyList() }
            val shopBills = try { FirebaseManager.shopBillsCollection().get().await().documents } catch (_: Exception) { emptyList() }
            val allBillDocs = rootBills + shopBills

            var billsDeleted = 0
            allBillDocs.forEach { doc ->
                val bDto = doc.toObject(BillDto::class.java) ?: return@forEach
                val idMatch = (customerId > 0 && (bDto.legacyCustomerId == customerId || bDto.customerId == "cust_$customerId" || bDto.customerId == customerId.toString()))
                val bMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(bDto.customerMobile ?: "")
                val mobileMatch = cleanMobile.length == 10 && (bMobile == cleanMobile || (bDto.customerMobile != null && bDto.customerMobile!!.contains(cleanMobile)))
                val nameMatch = cleanName.isNotBlank() && bDto.customerName != null && bDto.customerName!!.trim().equals(cleanName, ignoreCase = true)
                if (idMatch || mobileMatch || nameMatch) {
                    val isVoid = bDto.status.equals("VOIDED", ignoreCase = true) || bDto.status.equals("TRASH", ignoreCase = true) || bDto.paymentStatus.equals("VOID", ignoreCase = true)
                    if (!isVoid) {
                        bDto.items?.forEach { item ->
                            val pId = item.legacyProductId ?: item.productId?.removePrefix("prod_")?.toLongOrNull()
                            if (pId != null && pId > 0 && item.quantity > 0) {
                                val prodRef = FirebaseManager.productsCollection().document("prod_$pId")
                                val qty = item.quantity.toLong()
                                batch.set(
                                    prodRef,
                                    mapOf(
                                        "currentStock" to FieldValue.increment(qty),
                                        "totalSold" to FieldValue.increment(-qty),
                                        "updatedAt" to System.currentTimeMillis()
                                    ),
                                    com.google.firebase.firestore.SetOptions.merge()
                                )
                            }
                        }
                    }
                    batch.delete(doc.reference)
                    billsDeleted++
                }
            }

            val txSnap = FirebaseManager.udhariTransactionsCollection().get().await()
            var txsDeleted = 0
            txSnap.documents.forEach { txDoc ->
                val txDto = txDoc.toObject(UdhariTransactionDto::class.java)
                val idMatch = customerId > 0 && txDto?.customerId == customerId
                val tMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(txDto?.customerMobile ?: "")
                val mobileMatch = cleanMobile.length == 10 && (tMobile == cleanMobile || (txDto?.customerMobile != null && txDto.customerMobile.contains(cleanMobile)))
                val nameMatch = cleanName.isNotBlank() && txDto?.customerName != null && txDto.customerName.trim().equals(cleanName, ignoreCase = true)
                if (idMatch || mobileMatch || nameMatch) {
                    batch.delete(txDoc.reference)
                    txsDeleted++
                }
            }

            if (billsDeleted > 0 || txsDeleted > 0) {
                batch.commit().await()
                Log.d(TAG, "Purged customer history: bills=$billsDeleted txs=$txsDeleted for custId=$customerId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Purge customer history error: ${e.message}")
        }
    }

    suspend fun purgeSpecificCustomerPermanently(targetName: String, targetMobile: String) {
        try {
            FirebaseManager.ensureAuthenticated()
            val cleanTargetMobile = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(targetMobile)
            val cleanTargetName = targetName.trim().lowercase()

            val batch = FirebaseManager.firestore.batch()

            // 1. Purge from customers & shopCustomers
            val rootCusts = try { FirebaseManager.customersCollection().get().await().documents } catch (_: Exception) { emptyList() }
            val shopCusts = try { FirebaseManager.shopCustomersCollection().get().await().documents } catch (_: Exception) { emptyList() }
            val matchedCustIds = mutableSetOf<Long>()
            (rootCusts + shopCusts).forEach { doc ->
                val c = doc.toObject(CustomerDto::class.java)
                val cMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(c?.mobile ?: "")
                val cName = (c?.name ?: "").trim().lowercase()
                val isMobMatch = cleanTargetMobile.length == 10 && (cMob == cleanTargetMobile || (c?.mobile != null && c.mobile.contains(cleanTargetMobile)))
                val isNameMatch = cleanTargetName.isNotBlank() && (cName == cleanTargetName || cName.contains(cleanTargetName))
                if (isMobMatch || isNameMatch) {
                    batch.delete(doc.reference)
                    c?.legacyId?.let { matchedCustIds.add(it) }
                    (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL).let { matchedCustIds.add(it) }
                }
            }

            // 2. Purge from bills & shopBills (and restore stock!)
            val rootBills = try { FirebaseManager.billsCollection().get().await().documents } catch (_: Exception) { emptyList() }
            val shopBills = try { FirebaseManager.shopBillsCollection().get().await().documents } catch (_: Exception) { emptyList() }
            (rootBills + shopBills).forEach { doc ->
                val b = doc.toObject(BillDto::class.java)
                val bMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(b?.customerMobile ?: "")
                val bName = (b?.customerName ?: "").trim().lowercase()
                val isIdMatch = b?.legacyCustomerId != null && matchedCustIds.contains(b.legacyCustomerId)
                val isMobMatch = cleanTargetMobile.length == 10 && (bMob == cleanTargetMobile || (b?.customerMobile != null && b.customerMobile!!.contains(cleanTargetMobile)))
                val isNameMatch = cleanTargetName.isNotBlank() && (bName == cleanTargetName || bName.contains(cleanTargetName))
                if (isIdMatch || isMobMatch || isNameMatch) {
                    val isVoid = b?.status.equals("VOIDED", ignoreCase = true) || b?.status.equals("TRASH", ignoreCase = true) || b?.paymentStatus.equals("VOID", ignoreCase = true)
                    if (!isVoid) {
                        b?.items?.forEach { item ->
                            val pId = item.legacyProductId ?: item.productId?.removePrefix("prod_")?.toLongOrNull()
                            if (pId != null && pId > 0 && item.quantity > 0) {
                                val prodRef = FirebaseManager.productsCollection().document("prod_$pId")
                                val qty = item.quantity.toLong()
                                batch.set(
                                    prodRef,
                                    mapOf(
                                        "currentStock" to FieldValue.increment(qty),
                                        "totalSold" to FieldValue.increment(-qty),
                                        "updatedAt" to System.currentTimeMillis()
                                    ),
                                    com.google.firebase.firestore.SetOptions.merge()
                                )
                            }
                        }
                    }
                    batch.delete(doc.reference)
                }
            }

            // 3. Purge from udhari_transactions
            val txDocs = try { FirebaseManager.udhariTransactionsCollection().get().await().documents } catch (_: Exception) { emptyList() }
            txDocs.forEach { doc ->
                val t = doc.toObject(UdhariTransactionDto::class.java)
                val tMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(t?.customerMobile ?: "")
                val tName = (t?.customerName ?: "").trim().lowercase()
                val isIdMatch = t?.customerId != null && matchedCustIds.contains(t.customerId)
                val isMobMatch = cleanTargetMobile.length == 10 && (tMob == cleanTargetMobile || (t?.customerMobile != null && t.customerMobile.contains(cleanTargetMobile)))
                val isNameMatch = cleanTargetName.isNotBlank() && (tName == cleanTargetName || tName.contains(cleanTargetName))
                if (isIdMatch || isMobMatch || isNameMatch) {
                    batch.delete(doc.reference)
                }
            }

            batch.commit().await()
            Log.d(TAG, "PURGED specific customer permanently")
        } catch (e: Exception) {
            Log.w(TAG, "Purge specific customer error: ${e.message}")
        }
    }

    suspend fun unTrashCustomerIfAccidental(customerName: String) {
        try {
            FirebaseManager.ensureAuthenticated()
            val rootCusts = try { FirebaseManager.customersCollection().whereEqualTo("status", "TRASH").get().await().documents } catch (_: Exception) { emptyList() }
            val shopCusts = try { FirebaseManager.shopCustomersCollection().whereEqualTo("status", "TRASH").get().await().documents } catch (_: Exception) { emptyList() }
            val allTrashDocs = rootCusts + shopCusts

            for (doc in allTrashDocs) {
                val name = doc.getString("name") ?: ""
                if (name.contains(customerName, ignoreCase = true)) {
                    doc.reference.update(
                        mapOf(
                            "status" to "ACTIVE",
                            "isActive" to true,
                            "deletedAt" to null,
                            "updatedAt" to System.currentTimeMillis()
                        )
                    ).await()
                    Log.d(TAG, "Restored accidentally trashed customer: $name")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Un-trash customer notice: ${e.message}")
        }
    }
}

// 2. FIRESTORE PRODUCT REPOSITORY
class FirestoreProductRepository {
    private val TAG = "FirestoreProdRepo"

    fun getAllProducts(): Flow<List<Product>> = callbackFlow {
        val rootMap = mutableMapOf<String, Product>()
        val shopMap = mutableMapOf<String, Product>()

        fun emitCombined() {
            val combined = (rootMap.values + shopMap.values)
                .distinctBy { if (it.sku?.isNotBlank() == true) it.sku else it.id.toString() }
                .sortedByDescending { it.id }
            trySend(combined)
        }

        val rootListener = FirebaseManager.productsCollection()
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Product root snapshot notice: ${error.message} (code: ${error.code})")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    rootMap.clear()
                    snapshot.documents.forEach { doc ->
                        doc.toProductDomain()?.let { prod ->
                            if (prod.isActive && prod.name.isNotBlank()) {
                                rootMap[doc.id] = prod
                            }
                        }
                    }
                    emitCombined()
                }
            }

        val shopListener = FirebaseManager.shopProductsCollection()
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Product shop snapshot notice: ${error.message} (code: ${error.code})")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    shopMap.clear()
                    snapshot.documents.forEach { doc ->
                        doc.toProductDomain()?.let { prod ->
                            if (prod.isActive && prod.name.isNotBlank()) {
                                shopMap[doc.id] = prod
                            }
                        }
                    }
                    emitCombined()
                }
            }

        awaitClose {
            rootListener.remove()
            shopListener.remove()
        }
    }.distinctUntilChanged()

    fun getAllCategories(): Flow<List<Category>> = callbackFlow {
        val rootMap = mutableMapOf<String, Category>()
        val shopMap = mutableMapOf<String, Category>()

        fun emitCombined() {
            val combined = (rootMap.values + shopMap.values)
                .distinctBy { it.name.trim().lowercase() }
                .sortedBy { it.name }
            trySend(combined)
        }

        val rootListener = FirebaseManager.categoriesCollection()
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Category root snapshot notice: ${error.message} (code: ${error.code})")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    rootMap.clear()
                    snapshot.documents.forEach { doc ->
                        doc.toCategoryDomain()?.let { cat ->
                            if (cat.isActive && cat.name.isNotBlank()) {
                                rootMap[doc.id] = cat
                            }
                        }
                    }
                    emitCombined()
                }
            }

        val shopListener = FirebaseManager.shopCategoriesCollection()
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Category shop snapshot notice: ${error.message} (code: ${error.code})")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    shopMap.clear()
                    snapshot.documents.forEach { doc ->
                        doc.toCategoryDomain()?.let { cat ->
                            if (cat.isActive && cat.name.isNotBlank()) {
                                shopMap[doc.id] = cat
                            }
                        }
                    }
                    emitCombined()
                }
            }

        awaitClose {
            rootListener.remove()
            shopListener.remove()
        }
    }.distinctUntilChanged()

    suspend fun searchProducts(query: String): List<Product> {
        return try {
            val rootSnap = try {
                kotlinx.coroutines.withTimeoutOrNull(1500) {
                    FirebaseManager.productsCollection().get().await()
                } ?: FirebaseManager.productsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) { null }

            val shopSnap = try {
                kotlinx.coroutines.withTimeoutOrNull(1500) {
                    FirebaseManager.shopProductsCollection().get().await()
                } ?: FirebaseManager.shopProductsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) { null }

            val allDocs = (rootSnap?.documents ?: emptyList()) + (shopSnap?.documents ?: emptyList())
            val list = allDocs.mapNotNull { doc ->
                doc.toProductDomain()
            }.filter { it.isActive }.distinctBy { if (it.sku?.isNotBlank() == true) it.sku else it.id.toString() }

            if (query.isBlank()) list else list.filter {
                it.name.contains(query, ignoreCase = true) || (it.sku?.contains(query, ignoreCase = true) == true)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getProductById(id: Long): Product? {
        val all = searchProducts("")
        return all.find { it.id == id }
    }

    suspend fun saveProduct(product: Product): String {
        val existingProducts = searchProducts("")
        val existingSkus = existingProducts.mapNotNull { it.sku }.toSet()
        val finalSku = product.sku?.trim()?.ifBlank { null } ?: com.matoshree.shopmanager.utils.ProductSkuGenerator.generateSku(product.name, existingSkus)

        val docId = if (product.id > 0) "prod_${product.id}" else "prod_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(5)}"
        val dto = ProductDto(
            id = docId,
            legacyId = product.id.takeIf { it > 0 } ?: (docId.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL),
            name = product.name.trim(),
            sku = finalSku,
            legacyCategoryId = product.categoryId,
            categoryId = product.categoryId?.let { "cat_$it" },
            sellingPrice = product.sellingPrice,
            costPrice = product.costPrice,
            defaultProfitMargin = product.defaultProfitMargin,
            trackInventory = product.trackInventory,
            currentStock = product.currentStock,
            totalStockAdded = if (product.totalStockAdded > 0) product.totalStockAdded else (product.currentStock + product.totalSold),
            totalSold = product.totalSold,
            lowStockThreshold = if (product.lowStockThreshold > 0) product.lowStockThreshold else 5,
            imageUrl = product.imageUrl,
            status = if (product.isActive) "ACTIVE" else "ARCHIVED",
            updatedAt = System.currentTimeMillis()
        )
        val docPath = "products/$docId"
        try {
            FirebaseManager.ensureAuthenticated()
            val task = FirebaseManager.productsCollection().document(docId).set(dto)
            FirebaseManager.awaitWriteTask(task)
            Log.d(TAG, "FIRESTORE_WRITE entity=PRODUCT path=$docPath result=SUCCESS")
        } catch (e: Exception) {
            Log.e(TAG, "FIRESTORE_WRITE entity=PRODUCT path=$docPath result=FAILURE code=${e.javaClass.simpleName}", e)
        }
        return docId
    }

    suspend fun adjustStock(
        productId: Long,
        delta: Int,
        newExactStock: Int? = null,
        type: com.matoshree.shopmanager.domain.model.StockChangeType = com.matoshree.shopmanager.domain.model.StockChangeType.RESTOCK,
        note: String? = null,
        referenceId: String? = null
    ): Result<Unit> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val allProducts = searchProducts("")
            val target = allProducts.find { it.id == productId }
                ?: return Result.failure(Exception("Product with ID $productId not found"))

            val docId = "prod_${target.id}"
            val prodRef = FirebaseManager.productsCollection().document(docId)

            val finalStock = if (newExactStock != null) {
                newExactStock.coerceAtLeast(0)
            } else {
                (target.currentStock + delta).coerceAtLeast(0)
            }
            val actualDelta = finalStock - target.currentStock

            val updateMap = mutableMapOf<String, Any>(
                "currentStock" to finalStock,
                "updatedAt" to System.currentTimeMillis()
            )
            if (type == com.matoshree.shopmanager.domain.model.StockChangeType.RESTOCK ||
                (type == com.matoshree.shopmanager.domain.model.StockChangeType.ADJUSTMENT && actualDelta > 0)
            ) {
                val addDelta = if (type == com.matoshree.shopmanager.domain.model.StockChangeType.RESTOCK) delta else actualDelta
                if (addDelta > 0) {
                    updateMap["totalStockAdded"] = FieldValue.increment(addDelta.toLong())
                }
            }

            prodRef.set(updateMap, com.google.firebase.firestore.SetOptions.merge()).await()

            // Also record stock movement
            val movementId = "sm_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
            val movementDto = StockMovementDto(
                id = movementId,
                productId = target.id,
                productName = target.name,
                sku = target.sku,
                changeType = type.name,
                quantityDelta = if (newExactStock != null) actualDelta else delta,
                stockAfter = finalStock,
                note = note,
                referenceId = referenceId,
                timestamp = System.currentTimeMillis()
            )
            FirebaseManager.stockMovementsCollection().document(movementId).set(movementDto).await()

            Log.d(TAG, "Stock adjusted for ${target.name}: newStock=$finalStock, delta=$actualDelta, type=$type")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust stock for product $productId: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun getStockMovements(productId: Long? = null): Flow<List<com.matoshree.shopmanager.domain.model.StockMovement>> = callbackFlow {
        val listener = FirebaseManager.stockMovementsCollection()
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Stock movements snapshot note: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(StockMovementDto::class.java)?.apply { this.id = doc.id }?.toDomain()
                    }.filter {
                        productId == null || it.productId == productId
                    }.sortedByDescending { it.timestamp }
                    trySend(list)
                }
            }
        awaitClose { listener.remove() }
    }


    suspend fun saveCategory(name: String, description: String? = null): String {
        val docId = "cat_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
        val dto = CategoryDto(
            id = docId,
            legacyId = (docId.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL),
            name = name.trim(),
            description = description?.trim()?.ifBlank { null },
            status = "ACTIVE",
            updatedAt = System.currentTimeMillis()
        )
        val docPath = "categories/$docId"
        try {
            FirebaseManager.ensureAuthenticated()
            FirebaseManager.categoriesCollection().document(docId).set(dto).await()
            Log.d(TAG, "FIRESTORE_WRITE entity=CATEGORY path=$docPath result=SUCCESS")
        } catch (e: Exception) {
            Log.e(TAG, "FIRESTORE_WRITE entity=CATEGORY path=$docPath result=FAILURE code=${e.javaClass.simpleName}", e)
        }
        return docId
    }

    suspend fun renameCategory(categoryId: Long, newName: String) {
        try {
            FirebaseManager.ensureAuthenticated()
            val snapshot = try {
                kotlinx.coroutines.withTimeoutOrNull(1000) {
                    FirebaseManager.categoriesCollection().get().await()
                } ?: FirebaseManager.categoriesCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) { null }

            snapshot?.documents?.forEach { doc ->
                val dto = doc.toObject(CategoryDto::class.java)
                val id = dto?.legacyId ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
                if (id == categoryId || doc.id == "cat_$categoryId" || doc.id == categoryId.toString()) {
                    doc.reference.update(mapOf(
                        "name" to newName.trim(),
                        "updatedAt" to System.currentTimeMillis()
                    )).await()
                    Log.d(TAG, "FIRESTORE_UPDATE entity=CATEGORY path=${doc.reference.path} result=SUCCESS")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming category $categoryId in Firestore: ${e.message}", e)
        }
    }

    suspend fun deleteCategory(categoryId: Long) {
        try {
            FirebaseManager.ensureAuthenticated()
            val snapshot = try {
                kotlinx.coroutines.withTimeoutOrNull(1000) {
                    FirebaseManager.categoriesCollection().get().await()
                } ?: FirebaseManager.categoriesCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) { null }

            snapshot?.documents?.forEach { doc ->
                val dto = doc.toObject(CategoryDto::class.java)
                val id = dto?.legacyId ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
                if (id == categoryId || doc.id == "cat_$categoryId" || doc.id == categoryId.toString()) {
                    doc.reference.delete().await()
                    Log.d(TAG, "FIRESTORE_DELETE entity=CATEGORY path=${doc.reference.path} result=SUCCESS")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting category $categoryId in Firestore: ${e.message}", e)
        }
    }

    suspend fun updateProductCategory(productId: Long, newCategoryId: Long?): Result<Unit> {
        return updateProductsCategory(listOf(productId), newCategoryId)
    }

    suspend fun deleteOrArchiveProduct(productId: Long): String {
        FirebaseManager.ensureAuthenticated()
        val snapshot = try {
            kotlinx.coroutines.withTimeoutOrNull(800) {
                FirebaseManager.productsCollection().get().await()
            } ?: FirebaseManager.productsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
        } catch (e: Exception) {
            null
        }
        val matchDoc = snapshot?.documents?.find { doc ->
            val dto = doc.toObject(ProductDto::class.java)?.apply { id = doc.id }
            val id = dto?.legacyId ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
            id == productId || doc.id == "prod_$productId" || doc.id == productId.toString()
        }
        if (matchDoc != null) {
            val docPath = "products/${matchDoc.id}"
            try {
                // Soft delete to TRASH so it appears in Trash Bin!
                val updateData = mapOf(
                    "status" to "TRASH",
                    "isActive" to false,
                    "deletedAt" to System.currentTimeMillis(),
                    "updatedAt" to System.currentTimeMillis()
                )
                FirebaseManager.productsCollection().document(matchDoc.id).update(updateData).await()
                Log.d(TAG, "FIRESTORE_WRITE entity=PRODUCT path=$docPath result=SUCCESS (MOVED_TO_TRASH)")
            } catch (e: Exception) {
                Log.e(TAG, "FIRESTORE_WRITE entity=PRODUCT path=$docPath result=FAILURE code=${e.javaClass.simpleName}", e)
            }
        }
        return "Product deleted"
    }

    suspend fun updateProductsCategory(productIds: List<Long>, categoryId: Long?): Result<Unit> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val snapshot = try {
                kotlinx.coroutines.withTimeoutOrNull(800) {
                    FirebaseManager.productsCollection().get().await()
                } ?: FirebaseManager.productsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) {
                null
            } ?: return Result.failure(IllegalStateException("Failed to query products"))

            val batch = FirebaseManager.firestore.batch()
            snapshot.documents.forEach { doc ->
                val dto = doc.toObject(ProductDto::class.java)?.apply { id = doc.id }
                val id = dto?.legacyId ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
                if (productIds.contains(id) || productIds.any { doc.id == "prod_$it" || doc.id == it.toString() }) {
                    batch.update(
                        FirebaseManager.productsCollection().document(doc.id),
                        mapOf(
                            "categoryId" to categoryId,
                            "updatedAt" to System.currentTimeMillis()
                        )
                    )
                }
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteProductsBatch(productIds: List<Long>): Result<Int> {
        return try {
            FirebaseManager.ensureAuthenticated()
            val snapshot = try {
                kotlinx.coroutines.withTimeoutOrNull(800) {
                    FirebaseManager.productsCollection().get().await()
                } ?: FirebaseManager.productsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) {
                null
            } ?: return Result.failure(IllegalStateException("Failed to query products"))

            val batch = FirebaseManager.firestore.batch()
            var count = 0
            snapshot.documents.forEach { doc ->
                val dto = doc.toObject(ProductDto::class.java)?.apply { id = doc.id }
                val id = dto?.legacyId ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
                if (productIds.contains(id) || productIds.any { doc.id == "prod_$it" || doc.id == it.toString() }) {
                    batch.delete(doc.reference)
                    count++
                }
            }
            if (count > 0) {
                batch.commit().await()
            }
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// 3. FIRESTORE BILL REPOSITORY
class FirestoreBillRepository {
    private val TAG = "FirestoreBillRepo"
    private val profitCalculator = CalculateProfitUseCase()

    fun getRecentBills(limit: Int = 100): Flow<List<Bill>> = callbackFlow {
        val rootMap = mutableMapOf<String, Bill>()
        val shopMap = mutableMapOf<String, Bill>()

        var debounceJob: kotlinx.coroutines.Job? = null
        fun emitCombined() {
            debounceJob?.cancel()
            debounceJob = launch {
                kotlinx.coroutines.delay(100)
                val combined = (rootMap.values + shopMap.values)
                    .distinctBy { it.canonicalKey() }
                    .sortedWith(compareByDescending<Bill> { it.billDate }.thenByDescending { it.id })
                    .take(limit)
                trySend(combined)
            }
        }

        val rootListener = FirebaseManager.billsCollection()
            .limit(limit.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Bills root snapshot notice: ${error.message} (code: ${error.code})")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    rootMap.clear()
                    snapshot.documents.forEach { doc ->
                        doc.toBillDomain()?.let { bill ->
                            rootMap[doc.id] = bill
                        }
                    }
                    emitCombined()
                }
            }

        val shopListener = FirebaseManager.shopBillsCollection()
            .limit(limit.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Bills shop snapshot notice: ${error.message} (code: ${error.code})")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    shopMap.clear()
                    snapshot.documents.forEach { doc ->
                        doc.toBillDomain()?.let { bill ->
                            shopMap[doc.id] = bill
                        }
                    }
                    emitCombined()
                }
            }

        awaitClose {
            rootListener.remove()
            shopListener.remove()
        }
    }.distinctUntilChanged()

    fun getAllBills(limit: Int = 500): Flow<List<Bill>> = getRecentBills(limit)

    suspend fun getBillById(id: Long): Bill? {
        return try {
            val rootSnap = try { FirebaseManager.billsCollection().get().await() } catch (e: Exception) { null }
            val shopSnap = try { FirebaseManager.shopBillsCollection().get().await() } catch (e: Exception) { null }
            val allDocs = (rootSnap?.documents ?: emptyList()) + (shopSnap?.documents ?: emptyList())
            allDocs.mapNotNull { doc -> doc.toBillDomain() }.find { it.id == id }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun createSale(
        saleType: SaleType,
        customer: Customer?,
        items: List<BillItem>,
        discountType: DiscountType = DiscountType.NONE,
        discountValue: Double = 0.0,
        discountAmount: Double = 0.0,
        quickSaleAmount: Double?,
        paymentMethod: PaymentMethod,
        note: String?,
        paymentStatus: PaymentStatus = PaymentStatus.PAID,
        paidAmount: Double? = null,
        dueDate: String? = null
    ): Bill {
        FirebaseManager.ensureAuthenticated()

        val txUuid = UUID.randomUUID().toString()
        val nowIso = DateUtils.nowIso()
        val currentYear = nowIso.take(4)
        val billDocId = "bill_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"

        val isOnline = FirebaseManager.isNetworkAvailable()

        // Read current shop settings for snapshot
        val shopSnapshot = try {
            if (isOnline) {
                kotlinx.coroutines.withTimeoutOrNull(600) {
                    FirebaseManager.shopDoc().get().await()
                }?.toObject(ShopDto::class.java)
            } else null
        } catch (e: Exception) { null }
            ?: try {
                FirebaseManager.shopDoc().get(com.google.firebase.firestore.Source.CACHE).await().toObject(ShopDto::class.java)
            } catch (e: Exception) { null }
            ?: ShopDto()

        // Count bills for year sequence
        val existingBills = try {
            if (isOnline) {
                kotlinx.coroutines.withTimeoutOrNull(1500) {
                    FirebaseManager.billsCollection().get().await()
                }
            } else null
        } catch (e: Exception) { null }
            ?: try {
                FirebaseManager.billsCollection().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) { null }

        var maxSeq = 0
        existingBills?.documents?.forEach { doc ->
            val num = doc.getString("billNumber") ?: ""
            if (num.startsWith("MC-$currentYear-")) {
                val seqPart = num.removePrefix("MC-$currentYear-").takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                if (seqPart > maxSeq) maxSeq = seqPart
            }
        }
        val billNumber = if (maxSeq > 0) {
            "MC-$currentYear-${maxSeq + 1}"
        } else {
            val count = (existingBills?.size() ?: 0)
            if (count > 0) {
                "MC-$currentYear-${count + 1}"
            } else {
                val timeSeq = ((System.currentTimeMillis() / 1000) % 900000 + 100000).toInt()
                "MC-$currentYear-$timeSeq"
            }
        }

        val subtotal: Double
        val finalAmount: Double
        val totalCost: Double
        val estimatedProfit: Double
        val actualProfit: Double
        val profitType: ProfitType
        val entityItems: MutableList<BillItemDto> = mutableListOf()

        if (saleType == SaleType.DETAILED) {
            var sub = 0.0
            var cost = 0.0
            var estProf = 0.0
            var actProf = 0.0
            var hasActual = false

            for (item in items) {
                val fin = profitCalculator.calculateItemProfit(
                    sellingPrice = item.sellingPrice,
                    quantity = item.quantity,
                    costPrice = item.costPrice,
                    discount = item.discountAmount,
                    marginPercent = 25.0
                )
                sub += (item.sellingPrice * item.quantity)
                cost += fin.lineCost
                if (fin.profitType == ProfitType.ACTUAL) {
                    actProf += fin.lineProfit
                    hasActual = true
                } else {
                    estProf += fin.lineProfit
                }

                entityItems.add(
                    BillItemDto(
                        id = UUID.randomUUID().toString(),
                        productId = item.productId?.let { "prod_$it" },
                        legacyProductId = item.productId,
                        productName = item.productName,
                        sku = item.sku,
                        categoryId = item.categoryId?.let { "cat_$it" },
                        legacyCategoryId = item.categoryId,
                        quantity = item.quantity,
                        sellingPrice = item.sellingPrice,
                        costPrice = item.costPrice ?: 0.0,
                        discountAmount = item.discountAmount,
                        lineTotal = fin.lineTotal,
                        lineCost = fin.lineCost,
                        lineProfit = fin.lineProfit
                    )
                )
            }

            subtotal = sub
            finalAmount = (subtotal - discountAmount).coerceAtLeast(0.0)
            totalCost = cost
            estimatedProfit = estProf
            actualProfit = actProf
            profitType = if (hasActual) ProfitType.ACTUAL else ProfitType.ESTIMATED
        } else {
            val amt = quickSaleAmount ?: 0.0
            val qFin = profitCalculator.calculateQuickSaleProfit(amt, 25.0)
            subtotal = amt
            finalAmount = amt
            totalCost = qFin.estimatedCost
            estimatedProfit = qFin.estimatedProfit
            actualProfit = 0.0
            profitType = ProfitType.ESTIMATED
        }

        val actualPaid = when (paymentStatus) {
            PaymentStatus.PAID -> finalAmount
            PaymentStatus.PARTIAL -> (paidAmount ?: 0.0).coerceIn(0.0, finalAmount)
            PaymentStatus.PENDING, PaymentStatus.UNPAID -> 0.0
            else -> finalAmount
        }
        val actualPending = (finalAmount - actualPaid).coerceAtLeast(0.0)
        val finalPaymentStatus = if (actualPending <= 0.01) PaymentStatus.PAID else if (actualPaid > 0.0) PaymentStatus.PARTIAL else PaymentStatus.PENDING

        val paymentList = if (actualPaid > 0.0) {
            listOf(
                BillPaymentDto(
                    id = UUID.randomUUID().toString(),
                    method = paymentMethod.name,
                    amount = actualPaid,
                    status = "PAID",
                    createdAt = System.currentTimeMillis()
                )
)
        } else {
            emptyList()
        }

        val hasRealCustomer = customer != null && customer.name.isNotBlank() && !customer.name.equals("Walk-in", ignoreCase = true)

        val legacyBillId = (billDocId.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)

        val billDto = BillDto(
            id = billDocId,
            legacyId = legacyBillId,
            billNumber = billNumber,
            transactionUuid = txUuid,
            saleType = saleType.name,
            customerId = if (hasRealCustomer && customer!!.id > 0) "cust_${customer.id}" else null,
            legacyCustomerId = if (hasRealCustomer && customer!!.id > 0) customer.id else null,
            customerName = if (hasRealCustomer) customer!!.name else null,
            customerMobile = if (hasRealCustomer) customer!!.mobile.ifBlank { null } else null,
            subtotal = subtotal,
            discountType = discountType.name,
            discountValue = discountValue,
            discountAmount = discountAmount,
            discount = discountAmount,
            taxAmount = 0.0,
            finalAmount = finalAmount,
            paidAmount = actualPaid,
            pendingAmount = actualPending,
            dueDate = dueDate,
            costAmount = totalCost,
            estimatedProfit = estimatedProfit,
            actualProfit = actualProfit,
            profitType = profitType.name,
            paymentMethod = paymentMethod.name,
            paymentStatus = finalPaymentStatus.name,
            status = "COMPLETED",
            note = note,
            billDate = nowIso,
            shopNameSnapshot = shopSnapshot.name,
            shopAddressSnapshot = shopSnapshot.address,
            shopMobileSnapshot = shopSnapshot.mobile,
            shopGstinSnapshot = shopSnapshot.gstin,
            showGstinSnapshot = shopSnapshot.showGstin,
            items = entityItems,
            payments = paymentList,
            createdByDeviceId = android.os.Build.MODEL ?: "Android",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // Atomic write to Firestore (both root and shop collections so all devices receive updates)
        val batch = FirebaseManager.firestore.batch()
        val billRef = FirebaseManager.billsCollection().document(billDocId)
        batch.set(billRef, billDto)
        val shopBillRef = FirebaseManager.shopBillsCollection().document(billDocId)
        batch.set(shopBillRef, billDto)

        // Atomically update customer stats if attached
        if (hasRealCustomer && customer != null && customer.id > 0) {
            val custDocId = "cust_${customer.id}"
            val custRef = FirebaseManager.customersCollection().document(custDocId)
            val updateMap = mutableMapOf<String, Any>(
                "totalBills" to FieldValue.increment(1),
                "lifetimeSpend" to FieldValue.increment(finalAmount),
                "lastPurchaseAt" to nowIso,
                "updatedAt" to System.currentTimeMillis()
            )
            if (actualPending > 0.0) {
                updateMap["outstandingAmount"] = FieldValue.increment(actualPending)
            }
            batch.set(custRef, updateMap, com.google.firebase.firestore.SetOptions.merge())

            // Create initial Udhari transaction record if credit/partial
            if (actualPending > 0.0) {
                val creditTxId = "udhari_${System.currentTimeMillis()}_credit"
                val creditDto = UdhariTransactionDto(
                    id = creditTxId,
                    customerId = customer!!.id,
                    customerName = customer!!.name,
                    customerMobile = customer!!.mobile,
                    customerVillage = customer!!.address,
                    billId = legacyBillId,
                    billNumber = billNumber,
                    type = UdhariTransactionType.PURCHASE_CREDIT.name,
                    amount = finalAmount,
                    paymentMethod = paymentMethod.name,
                    previousOutstanding = 0.0,
                    remainingOutstanding = actualPending,
                    transactionUuid = UUID.randomUUID().toString(),
                    date = DateUtils.todayDate(),
                    dueDate = dueDate,
                    note = note,
                    createdAt = System.currentTimeMillis()
                )
                batch.set(FirebaseManager.udhariTransactionsCollection().document(creditTxId), creditDto)

                // If partial payment was made upfront, record the upfront payment transaction
                if (actualPaid > 0.0) {
                    val initialPayTxId = "udhari_${System.currentTimeMillis()}_pay"
                    val payDto = UdhariTransactionDto(
                        id = initialPayTxId,
                        customerId = customer.id,
                        customerName = customer.name,
                        customerMobile = customer.mobile,
                        customerVillage = customer.address,
                        billId = legacyBillId,
                        billNumber = billNumber,
                        type = UdhariTransactionType.PAYMENT.name,
                        amount = actualPaid,
                        paymentMethod = paymentMethod.name,
                        previousOutstanding = finalAmount,
                        remainingOutstanding = actualPending,
                        transactionUuid = UUID.randomUUID().toString(),
                        date = DateUtils.todayDate(),
                        dueDate = dueDate,
                        note = "Initial payment on checkout",
                        createdAt = System.currentTimeMillis() + 10L
                    )
                    batch.set(FirebaseManager.udhariTransactionsCollection().document(initialPayTxId), payDto)
                }
            }
        }

        // Atomically deduct product stock and increment totalSold for each sold item
        for (item in items) {
            val pId = item.productId
            if (pId != null && pId > 0) {
                val qty = item.quantity.toLong()
                val prodDocId = "prod_$pId"
                val prodRef = FirebaseManager.productsCollection().document(prodDocId)
                batch.set(
                    prodRef,
                    mapOf(
                        "currentStock" to FieldValue.increment(-qty),
                        "totalSold" to FieldValue.increment(qty),
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )

                val smDocId = "sm_${System.currentTimeMillis()}_${pId}_sale"
                val movementDto = StockMovementDto(
                    id = smDocId,
                    productId = pId,
                    productName = item.productName,
                    sku = item.sku,
                    changeType = "SALE",
                    quantityDelta = -item.quantity,
                    stockAfter = 0,
                    note = "विक्री: बिल #${billNumber}",
                    referenceId = billNumber,
                    timestamp = System.currentTimeMillis()
                )
                batch.set(FirebaseManager.stockMovementsCollection().document(smDocId), movementDto)
            }
        }

        try {
            val commitTask = batch.commit()
            val ack = FirebaseManager.awaitWriteTask(commitTask)
            if (ack == null && FirebaseManager.isNetworkAvailable()) {
                commitTask.await()
            }
            Log.d(TAG, "FIRESTORE_WRITE entity=BILL path=bills/$billDocId result=SUCCESS (PAID: $actualPaid, DUE: $actualPending)")
        } catch (e: Exception) {
            Log.e(TAG, "FIRESTORE_WRITE entity=BILL path=bills/$billDocId result=FAILURE code=${e.javaClass.simpleName}", e)
            throw e
        }

        return billDto.toDomain()
    }

    suspend fun voidBill(
        billId: Long,
        reason: String = "Voided by manager",
        billNumber: String? = null,
        transactionUuid: String? = null
    ) {
        FirebaseManager.ensureAuthenticated()
        val rootBills = try { FirebaseManager.billsCollection().get().await().documents } catch (e: Exception) { emptyList() }
        val shopBills = try { FirebaseManager.shopBillsCollection().get().await().documents } catch (e: Exception) { emptyList() }
        val allDocs = rootBills + shopBills

        // STRICT MATCHING: Target only the intended bill, never match by loose hashCode or raw integers
        val matchingDocs = allDocs.filter { doc ->
            val dto = doc.toObject(BillDto::class.java) ?: return@filter false
            if (!transactionUuid.isNullOrBlank()) {
                dto.transactionUuid == transactionUuid || doc.id == "bill_$transactionUuid" || doc.id == transactionUuid
            } else if (!billNumber.isNullOrBlank()) {
                dto.billNumber.equals(billNumber, ignoreCase = true)
            } else {
                doc.id == "bill_$billId" || (billId > 0 && dto.legacyId == billId)
            }
        }

        if (matchingDocs.isNotEmpty()) {
            val batch = FirebaseManager.firestore.batch()
            val primaryDoc = matchingDocs.first()
            val billDto = primaryDoc.toObject(BillDto::class.java)

            val updateMap = mapOf(
                "status" to "TRASH",
                "paymentStatus" to "VOID",
                "isVoided" to true,
                "voidReason" to reason,
                "deletedAt" to System.currentTimeMillis(),
                "updatedAt" to System.currentTimeMillis()
            )

            matchingDocs.forEach { doc ->
                batch.update(doc.reference, updateMap)
            }

            // Adjust customer lifetime spend ONLY if it's a real registered customer
            val targetCustId = billDto?.customerId
            if (!targetCustId.isNullOrBlank() &&
                billDto.customerName != null &&
                billDto.customerName != "Walk-in" &&
                (billDto.legacyCustomerId ?: 0L) > 0L &&
                (billDto.finalAmount > 0)
            ) {
                try {
                    val custDoc = FirebaseManager.customersCollection().document(targetCustId).get().await()
                    if (custDoc.exists()) {
                        batch.update(
                            custDoc.reference,
                            mapOf(
                                "lifetimeSpend" to FieldValue.increment(-billDto.finalAmount),
                                "updatedAt" to System.currentTimeMillis()
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to adjust customer stats on void: ${e.message}")
                }
            }

            // Restore product stock and decrement totalSold
            billDto?.items?.forEach { item ->
                val pId = item.legacyProductId ?: item.productId?.removePrefix("prod_")?.toLongOrNull()
                if (pId != null && pId > 0) {
                    val qty = item.quantity.toLong()
                    val prodRef = FirebaseManager.productsCollection().document("prod_$pId")
                    batch.set(
                        prodRef,
                        mapOf(
                            "currentStock" to FieldValue.increment(qty),
                            "totalSold" to FieldValue.increment(-qty),
                            "updatedAt" to System.currentTimeMillis()
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )

                    val smDocId = "sm_${System.currentTimeMillis()}_${pId}_void"
                    val movementDto = StockMovementDto(
                        id = smDocId,
                        productId = pId,
                        productName = item.productName,
                        sku = item.sku,
                        changeType = "RETURN",
                        quantityDelta = item.quantity,
                        stockAfter = 0,
                        note = "बिल रद्द: #${billDto.billNumber ?: billId} ($reason)",
                        referenceId = billDto.billNumber ?: billId.toString(),
                        timestamp = System.currentTimeMillis()
                    )
                    batch.set(FirebaseManager.stockMovementsCollection().document(smDocId), movementDto)
                }
            }

            try {
                batch.commit().await()
                Log.d(TAG, "FIRESTORE_WRITE entity=BILL void_success count=${matchingDocs.size} billId=$billId")
            } catch (e: Exception) {
                Log.e(TAG, "FIRESTORE_WRITE entity=BILL void_failed: ${e.message}", e)
            }
        }
    }

    suspend fun uploadLocalBill(bill: Bill): Boolean {
        return try {
            FirebaseManager.ensureAuthenticated()
            val billDocId = "bill_${bill.billDate.replace(Regex("[^0-9]"), "")}_${bill.billNumber.replace("-", "_")}"
            val legacyBillId = (billDocId.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)

            val entityItems = bill.items.map { item ->
                BillItemDto(
                    id = UUID.randomUUID().toString(),
                    productId = item.productId?.let { "prod_$it" },
                    legacyProductId = item.productId,
                    productName = item.productName,
                    sku = item.sku,
                    categoryId = item.categoryId?.let { "cat_$it" },
                    legacyCategoryId = item.categoryId,
                    quantity = item.quantity,
                    sellingPrice = item.sellingPrice,
                    costPrice = item.costPrice ?: 0.0,
                    discountAmount = item.discountAmount,
                    lineTotal = item.lineTotal,
                    lineCost = item.lineCost,
                    lineProfit = item.lineProfit
                )
            }

            val paymentList = if (bill.paidAmount > 0.0) {
                listOf(
                    BillPaymentDto(
                        id = UUID.randomUUID().toString(),
                        method = bill.paymentMethod.name,
                        amount = bill.paidAmount,
                        status = "PAID",
                        createdAt = System.currentTimeMillis()
                    )
                )
            } else {
                emptyList()
            }

            val hasRealCustomer = bill.customerId != null && bill.customerId > 0 && !bill.customerName.isNullOrBlank() && bill.customerName != "Walk-in"

            val billDto = BillDto(
                id = billDocId,
                legacyId = legacyBillId,
                billNumber = bill.billNumber,
                transactionUuid = bill.transactionUuid,
                saleType = bill.saleType.name,
                customerId = if (hasRealCustomer) "cust_${bill.customerId}" else null,
                legacyCustomerId = if (hasRealCustomer) bill.customerId else null,
                customerName = if (hasRealCustomer) bill.customerName else null,
                customerMobile = if (hasRealCustomer) bill.customerMobile?.ifBlank { null } else null,
                subtotal = bill.subtotal,
                discountType = bill.discountType.name,
                discountValue = bill.discountValue,
                discountAmount = bill.discountAmount,
                discount = bill.discountAmount,
                taxAmount = bill.taxAmount,
                finalAmount = bill.finalAmount,
                paidAmount = bill.paidAmount,
                pendingAmount = bill.pendingAmount,
                dueDate = bill.dueDate,
                costAmount = bill.costAmount,
                estimatedProfit = bill.estimatedProfit,
                actualProfit = bill.actualProfit,
                profitType = bill.profitType.name,
                paymentMethod = bill.paymentMethod.name,
                paymentStatus = bill.paymentStatus.name,
                status = if (bill.isVoided) "VOID" else "COMPLETED",
                note = bill.note,
                billDate = bill.billDate,
                shopNameSnapshot = bill.shopNameSnapshot,
                shopAddressSnapshot = bill.shopAddressSnapshot ?: "",
                shopMobileSnapshot = bill.shopMobileSnapshot ?: "",
                shopGstinSnapshot = bill.shopGstinSnapshot ?: "",
                showGstinSnapshot = bill.showGstinSnapshot,
                items = entityItems,
                payments = paymentList,
                createdByDeviceId = android.os.Build.MODEL ?: "Android",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            val batch = FirebaseManager.firestore.batch()
            val billRef = FirebaseManager.billsCollection().document(billDocId)
            batch.set(billRef, billDto)
            val shopBillRef = FirebaseManager.shopBillsCollection().document(billDocId)
            batch.set(shopBillRef, billDto)

            if (hasRealCustomer) {
                val custDocId = "cust_${bill.customerId}"
                val custRef = FirebaseManager.customersCollection().document(custDocId)
                val updateMap = mutableMapOf<String, Any>(
                    "totalBills" to FieldValue.increment(1),
                    "lifetimeSpend" to FieldValue.increment(bill.finalAmount),
                    "lastPurchaseAt" to bill.billDate,
                    "updatedAt" to System.currentTimeMillis()
                )
                if (bill.pendingAmount > 0.0) {
                    updateMap["outstandingAmount"] = FieldValue.increment(bill.pendingAmount)
                }
                batch.set(custRef, updateMap, com.google.firebase.firestore.SetOptions.merge())
            }

            batch.commit().await()
            Log.d(TAG, "uploadLocalBill SUCCESS: #${bill.billNumber} to bills/$billDocId")
            true
        } catch (e: Exception) {
            Log.w(TAG, "uploadLocalBill error for #${bill.billNumber}: ${e.message}")
            false
        }
    }

}

// 4. FIRESTORE SETTINGS REPOSITORY
class FirestoreSettingsRepository {
    private val TAG = "FirestoreSettingsRepo"

    fun observeSettings(): Flow<PrintShareHelper.ShopSettingsSnapshot> = callbackFlow {
        var currentSnap = PrintShareHelper.ShopSettingsSnapshot()

        val rootListener = FirebaseManager.shopDoc()
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Shop settings snapshot notice: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val dto = snapshot.toObject(ShopDto::class.java) ?: ShopDto()
                    currentSnap = PrintShareHelper.ShopSettingsSnapshot(
                        shopName = snapshot.getString("name") ?: dto.name,
                        mobile = snapshot.getString("mobile") ?: dto.mobile,
                        email = snapshot.getString("email") ?: dto.email,
                        address = snapshot.getString("address") ?: dto.address,
                        gstin = snapshot.getString("gstin") ?: dto.gstin,
                        showGstin = snapshot.getBoolean("showGstin") ?: dto.showGstin,
                        upiId = snapshot.getString("upiId") ?: dto.upiId,
                        upiDisplayName = snapshot.getString("upiDisplayName") ?: dto.upiDisplayName,
                        logoPath = snapshot.getString("logoUrl") ?: snapshot.getString("logoPath") ?: dto.logoUrl ?: dto.logoPath
                    )
                    trySend(currentSnap)
                }
            }

        val shopListener = FirebaseManager.shopRootDoc()
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Shop root doc snapshot notice: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val dto = snapshot.toObject(ShopDto::class.java) ?: ShopDto()
                    currentSnap = PrintShareHelper.ShopSettingsSnapshot(
                        shopName = snapshot.getString("name") ?: dto.name,
                        mobile = snapshot.getString("mobile") ?: dto.mobile,
                        email = snapshot.getString("email") ?: dto.email,
                        address = snapshot.getString("address") ?: dto.address,
                        gstin = snapshot.getString("gstin") ?: dto.gstin,
                        showGstin = snapshot.getBoolean("showGstin") ?: dto.showGstin,
                        upiId = snapshot.getString("upiId") ?: dto.upiId,
                        upiDisplayName = snapshot.getString("upiDisplayName") ?: dto.upiDisplayName,
                        logoPath = snapshot.getString("logoUrl") ?: snapshot.getString("logoPath") ?: dto.logoUrl ?: dto.logoPath
                    )
                    trySend(currentSnap)
                }
            }

        awaitClose {
            rootListener.remove()
            shopListener.remove()
        }
    }.distinctUntilChanged()

    suspend fun saveShopSettings(name: String, mobile: String, email: String, address: String, gstin: String, showGstin: Boolean) {
        val payload = mapOf(
            "name" to name.trim(),
            "mobile" to mobile.trim(),
            "email" to email.trim(),
            "address" to address.trim(),
            "gstin" to gstin.trim(),
            "showGstin" to showGstin,
            "updatedAt" to System.currentTimeMillis()
        )
        try {
            FirebaseManager.ensureAuthenticated()
            FirebaseManager.shopDoc().set(payload, com.google.firebase.firestore.SetOptions.merge()).await()
            FirebaseManager.shopRootDoc().set(payload, com.google.firebase.firestore.SetOptions.merge()).await()
            Log.d(TAG, "FIRESTORE_WRITE entity=SETTINGS result=SUCCESS")
        } catch (e: Exception) {
            Log.e(TAG, "FIRESTORE_WRITE entity=SETTINGS result=FAILURE code=${e.javaClass.simpleName}", e)
        }
    }

    suspend fun savePaymentSettings(id: String, displayName: String, mobile: String) {
        val payload = mapOf(
            "upiId" to id.trim(),
            "upiDisplayName" to displayName.trim(),
            "upiMobile" to mobile.trim(),
            "updatedAt" to System.currentTimeMillis()
        )
        try {
            FirebaseManager.ensureAuthenticated()
            FirebaseManager.shopDoc().set(payload, com.google.firebase.firestore.SetOptions.merge()).await()
            FirebaseManager.shopRootDoc().set(payload, com.google.firebase.firestore.SetOptions.merge()).await()
            Log.d(TAG, "FIRESTORE_WRITE entity=PAYMENT_SETTINGS result=SUCCESS")
        } catch (e: Exception) {
            Log.e(TAG, "FIRESTORE_WRITE entity=PAYMENT_SETTINGS result=FAILURE code=${e.javaClass.simpleName}", e)
        }
    }
}

// 5. FIRESTORE EXPENSE REPOSITORY
class FirestoreExpenseRepository {
    private val TAG = "FirestoreExpenseRepo"

    fun getAllExpenses(): Flow<List<Expense>> = callbackFlow {
        val listener = FirebaseManager.expensesCollection()
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Expenses snapshot error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(ExpenseDto::class.java)?.toDomain()
                    }
                    trySend(list)
                }
            }
        awaitClose { listener.remove() }
    }

    suspend fun addExpense(category: String, amount: Double, paymentMethod: PaymentMethod, note: String?, date: String = DateUtils.todayDate()): Long {
        val docId = "exp_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}"
        val legacyId = docId.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL
        val dto = ExpenseDto(
            id = docId,
            legacyId = legacyId,
            category = category,
            amount = amount,
            paymentMethod = paymentMethod.name,
            expenseDate = date,
            note = note,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val docPath = "expenses/$docId"
        try {
            FirebaseManager.ensureAuthenticated()
            FirebaseManager.expensesCollection().document(docId).set(dto).await()
            Log.d(TAG, "FIRESTORE_WRITE entity=EXPENSE path=$docPath result=SUCCESS")
        } catch (e: Exception) {
            Log.e(TAG, "FIRESTORE_WRITE entity=EXPENSE path=$docPath result=FAILURE code=${e.javaClass.simpleName}", e)
        }
        return legacyId
    }

    suspend fun deleteExpense(id: Long) {
        FirebaseManager.ensureAuthenticated()
        val snapshot = FirebaseManager.expensesCollection().get().await()
        val matchDoc = snapshot.documents.find {
            val dto = it.toObject(ExpenseDto::class.java)
            dto?.legacyId == id || (it.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL) == id
        }
        if (matchDoc != null) {
            val docPath = "expenses/${matchDoc.id}"
            try {
                FirebaseManager.expensesCollection().document(matchDoc.id).delete().await()
                Log.d(TAG, "FIRESTORE_WRITE entity=EXPENSE path=$docPath result=SUCCESS (DELETED)")
            } catch (e: Exception) {
                Log.e(TAG, "FIRESTORE_WRITE entity=EXPENSE path=$docPath result=FAILURE code=${e.javaClass.simpleName}", e)
            }
        }
    }
}

// 6. FIRESTORE DAILY CLOSING REPOSITORY
class FirestoreDailyClosingRepository {
    private val TAG = "FirestoreClosingRepo"

    fun getAllClosings(): Flow<List<DailyClosing>> = callbackFlow {
        val listener = FirebaseManager.dailyClosingsCollection()
            .orderBy("closingDate", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Closings snapshot error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val list = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(DailyClosingDto::class.java)?.toDomain()
                    }
                    trySend(list)
                }
            }
        awaitClose { listener.remove() }
    }

    suspend fun saveClosing(closing: DailyClosing) {
        val docId = closing.closingDate
        val dto = DailyClosingDto(
            id = docId,
            closingDate = closing.closingDate,
            totalSales = closing.totalSales,
            cashSales = closing.cashSales,
            upiSales = closing.upiSales,
            otherSales = closing.otherSales,
            totalExpenses = closing.totalExpenses,
            estimatedProfit = closing.grossProfit,
            actualProfit = closing.netProfit,
            billCount = closing.totalBills,
            status = if (closing.isClosed) "CLOSED" else "OPEN",
            notes = closing.notes,
            closedAt = closing.closedAt ?: DateUtils.nowIso(),
            createdAt = System.currentTimeMillis()
        )
        val docPath = "daily_closings/$docId"
        try {
            FirebaseManager.ensureAuthenticated()
            FirebaseManager.dailyClosingsCollection().document(docId).set(dto).await()
            Log.d(TAG, "FIRESTORE_WRITE entity=DAILY_CLOSING path=$docPath result=SUCCESS")
        } catch (e: Exception) {
            Log.e(TAG, "FIRESTORE_WRITE entity=DAILY_CLOSING path=$docPath result=FAILURE code=${e.javaClass.simpleName}", e)
        }
    }
}


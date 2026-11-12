package com.matoshree.shopmanager.data.repository

import android.content.Context
import com.matoshree.shopmanager.data.firebase.*
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.domain.model.DailyClosing
import com.matoshree.shopmanager.domain.model.Expense
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import com.matoshree.shopmanager.domain.model.SyncStatus

class ExpenseRepository(
    private val database: AppDatabase? = null,
    private val firestoreRepo: FirestoreExpenseRepository = FirestoreExpenseRepository()
) {
    fun getAllExpenses(): Flow<List<Expense>> = firestoreRepo.getAllExpenses()

    suspend fun addExpense(
        category: String,
        amount: Double,
        paymentMethod: PaymentMethod,
        expenseDate: String,
        note: String?
    ): Long {
        return firestoreRepo.addExpense(
            category = category,
            amount = amount,
            paymentMethod = paymentMethod,
            note = note,
            date = expenseDate
        )
    }

    suspend fun deleteExpense(id: Long) {
        firestoreRepo.deleteExpense(id)
    }
}

class DailyClosingRepository(
    private val database: AppDatabase? = null,
    private val firestoreRepo: FirestoreDailyClosingRepository = FirestoreDailyClosingRepository()
) {
    fun getAllClosings(): Flow<List<DailyClosing>> = firestoreRepo.getAllClosings()

    suspend fun getClosingForDate(date: String): DailyClosing? {
        return try {
            val snapshot = FirebaseManager.dailyClosingsCollection().document(date).get().await()
            snapshot.toObject(DailyClosingDto::class.java)?.toDomain()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun submitClosing(
        closingDate: String,
        actualCash: Double,
        expectedCash: Double,
        notes: String?
    ): Long {
        val cashDiff = actualCash - expectedCash
        val closing = DailyClosing(
            id = closingDate.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL,
            shopId = 1,
            closingDate = closingDate,
            expectedCash = expectedCash,
            actualCash = actualCash,
            cashDifference = cashDiff,
            notes = notes,
            isClosed = true,
            closedAt = DateUtils.nowIso()
        )
        firestoreRepo.saveClosing(closing)
        return closing.id
    }
}

class SyncRepository(
    private val database: AppDatabase? = null,
    private val context: Context? = null,
    private val firestoreBillRepo: FirestoreBillRepository = FirestoreBillRepository(),
    private val firestoreCustRepo: FirestoreCustomerRepository = FirestoreCustomerRepository()
) {
    val pendingSyncCount: Flow<Int> = if (database != null) {
        combine(
            database.billDao().getPendingBillsCount(),
            database.customerDao().getPendingCustomersCount()
        ) { bills, custs -> bills + custs }
    } else flowOf(0)

    suspend fun performSync(deviceId: String = "android_pos_1"): Boolean {
        if (!FirebaseManager.isNetworkAvailable()) return false
        if (database == null) return false

        return try {
            FirebaseManager.ensureAuthenticated()

            // 1. Upload pending customers first so bills have linked customers
            val pendingCusts = database.customerDao().getPendingSyncCustomers()
            for (cEntity in pendingCusts) {
                try {
                    val custDomain = cEntity.toDomain()
                    val docId = firestoreCustRepo.saveCustomer(custDomain)
                    val mappedId = (docId.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
                    database.customerDao().updateSyncStatus(cEntity.id, mappedId, SyncStatus.SYNCED)
                    android.util.Log.d("SyncRepository", "Synced offline customer: id=${cEntity.id}")
                } catch (ce: Exception) {
                    android.util.Log.w("SyncRepository", "Customer upload note: ${ce.message}")
                }
            }

            // 2. Upload pending bills
            val pendingBills = database.billDao().getPendingSyncBills()
            for (billWithItems in pendingBills) {
                try {
                    val domainBill = billWithItems.toDomain()
                    val ok = firestoreBillRepo.uploadLocalBill(domainBill)
                    if (ok) {
                        val legacyBillId = (domainBill.billNumber.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
                        database.billDao().updateSyncStatus(
                            billWithItems.bill.transactionUuid,
                            SyncStatus.SYNCED,
                            legacyBillId
                        )
                        android.util.Log.d("SyncRepository", "Synced offline bill: #${domainBill.billNumber}")
                    }
                } catch (be: Exception) {
                    android.util.Log.w("SyncRepository", "Bill upload note: ${be.message}")
                }
            }

            // 3. Download and cache any missing cloud bills from other devices
            try {
                val rootSnap = try { FirebaseManager.billsCollection().get().await() } catch (e: Exception) { null }
                val shopSnap = try { FirebaseManager.shopBillsCollection().get().await() } catch (e: Exception) { null }
                val allDocs = (rootSnap?.documents ?: emptyList()) + (shopSnap?.documents ?: emptyList())
                val cloudBills = allDocs.mapNotNull { doc -> doc.toBillDomain() }.distinctBy { if (it.transactionUuid.isNotBlank()) it.transactionUuid else it.billNumber }

                val cloudUuids = cloudBills.mapNotNull { it.transactionUuid.ifBlank { null } }.toSet()
                val cloudBillNumbers = cloudBills.map { it.billNumber }.toSet()

                // Check local recent bills to see if any bill is missing on Cloud
                val localRecentBills = try {
                    database.billDao().getBillsInDateRange(DateUtils.todayDate().take(7) + "-01", DateUtils.todayDate())
                } catch (_: Exception) { emptyList() }

                for (localB in localRecentBills) {
                    val uuid = localB.bill.transactionUuid
                    val bNum = localB.bill.billNumber
                    if (!cloudUuids.contains(uuid) && !cloudBillNumbers.contains(bNum)) {
                        try {
                            val domainB = localB.toDomain()
                            val ok = firestoreBillRepo.uploadLocalBill(domainB)
                            if (ok) {
                                val legacyBillId = (domainB.billNumber.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
                                database.billDao().updateSyncStatus(uuid, SyncStatus.SYNCED, legacyBillId)
                                android.util.Log.d("SyncRepository", "Reconciled missing local bill to cloud: #$bNum")
                            }
                        } catch (ue: Exception) {
                            android.util.Log.w("SyncRepository", "Error reconciling missing local bill: ${ue.message}")
                        }
                    }
                }

                // Cache cloud bills into Room with collision-proof IDs (only missing or modified)
                for (b in cloudBills) {
                    try {
                        val existing = (if (b.transactionUuid.isNotBlank()) database.billDao().getBillByUuid(b.transactionUuid) else null)
                            ?: (if (b.id > 0) database.billDao().getBillByServerId(b.id) else null)
                            ?: (if (b.billNumber.isNotBlank()) database.billDao().getBillByNumber(b.billNumber) else null)

                        if (existing != null &&
                            existing.bill.isVoided == b.isVoided &&
                            existing.bill.paymentStatus == b.paymentStatus &&
                            Math.abs(existing.bill.finalAmount - b.finalAmount) < 0.01 &&
                            existing.items.isNotEmpty()
                        ) {
                            continue // Skip writing unchanged bill to prevent Room invalidation loops
                        }

                        val localRoomId = existing?.bill?.id ?: 0L

                        val billEntity = com.matoshree.shopmanager.data.local.entity.BillEntity(
                            id = localRoomId,
                            serverId = b.id,
                            shopId = b.shopId,
                            customerId = b.customerId,
                            customerName = b.customerName,
                            customerMobile = b.customerMobile,
                            billNumber = b.billNumber,
                            transactionUuid = b.transactionUuid,
                            saleType = b.saleType,
                            subtotal = b.subtotal,
                            discountType = b.discountType,
                            discountValue = b.discountValue,
                            discountAmount = b.discountAmount,
                            taxAmount = b.taxAmount,
                            finalAmount = b.finalAmount,
                            costAmount = b.costAmount,
                            estimatedProfit = b.estimatedProfit,
                            actualProfit = b.actualProfit,
                            profitType = b.profitType,
                            paymentMethod = b.paymentMethod,
                            paymentStatus = b.paymentStatus,
                            note = b.note,
                            billDate = b.billDate,
                            shopNameSnapshot = b.shopNameSnapshot,
                            shopAddressSnapshot = b.shopAddressSnapshot,
                            shopMobileSnapshot = b.shopMobileSnapshot,
                            shopGstinSnapshot = b.shopGstinSnapshot,
                            showGstinSnapshot = b.showGstinSnapshot,
                            syncStatus = SyncStatus.SYNCED,
                            isVoided = b.isVoided,
                            voidReason = b.voidReason,
                            createdAt = System.currentTimeMillis()
                        )
                        val itemEntities = b.items.map { item ->
                            com.matoshree.shopmanager.data.local.entity.BillItemEntity(
                                id = 0,
                                billId = localRoomId,
                                productId = item.productId,
                                productNameSnapshot = item.productName,
                                skuSnapshot = item.sku,
                                categoryId = item.categoryId,
                                quantity = item.quantity,
                                sellingPrice = item.sellingPrice,
                                costPrice = item.costPrice,
                                discountAmount = item.discountAmount,
                                lineTotal = item.lineTotal,
                                lineCost = item.lineCost,
                                lineProfit = item.lineProfit
                            )
                        }
                        database.billDao().upsertServerBill(billEntity, itemEntities)
                    } catch (_: Exception) {}
                }
            } catch (de: Exception) {
                android.util.Log.w("SyncRepository", "Cloud bills pull note: ${de.message}")
            }

            true
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "Sync execution error: ${e.message}", e)
            false
        }
    }
}

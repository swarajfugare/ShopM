package com.matoshree.shopmanager.data.repository

import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.entity.DailyClosingEntity
import com.matoshree.shopmanager.data.local.entity.ExpenseEntity
import com.matoshree.shopmanager.data.local.entity.SyncQueueEntity
import com.matoshree.shopmanager.data.remote.api.MatoshreeApiService
import com.matoshree.shopmanager.data.remote.dto.BatchSyncRequest
import com.matoshree.shopmanager.data.remote.dto.SyncItemDto
import com.matoshree.shopmanager.domain.model.DailyClosing
import com.matoshree.shopmanager.domain.model.Expense
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.domain.model.SyncStatus
import com.matoshree.shopmanager.utils.DateUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID

class ExpenseRepository(private val database: AppDatabase) {
    private val expenseDao = database.expenseDao()
    private val syncQueueDao = database.syncQueueDao()

    fun getAllExpenses(): Flow<List<Expense>> {
        return expenseDao.getAllExpenses().map { list ->
            list.map { e ->
                Expense(
                    id = e.id,
                    shopId = e.shopId,
                    category = e.category,
                    amount = e.amount,
                    paymentMethod = e.paymentMethod,
                    expenseDate = e.expenseDate,
                    note = e.note
                )
            }
        }
    }

    suspend fun addExpense(
        category: String,
        amount: Double,
        paymentMethod: PaymentMethod,
        expenseDate: String,
        note: String?
    ): Long {
        val txUuid = UUID.randomUUID().toString()
        val id = expenseDao.insertExpense(
            ExpenseEntity(
                shopId = 1,
                transactionUuid = txUuid,
                category = category,
                amount = amount,
                paymentMethod = paymentMethod,
                expenseDate = expenseDate,
                note = note,
                syncStatus = SyncStatus.PENDING
            )
        )

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("transaction_uuid", kotlinx.serialization.json.JsonPrimitive(txUuid))
            put("category", kotlinx.serialization.json.JsonPrimitive(category))
            put("amount", kotlinx.serialization.json.JsonPrimitive(amount))
            put("payment_method", kotlinx.serialization.json.JsonPrimitive(paymentMethod.name))
            put("expense_date", kotlinx.serialization.json.JsonPrimitive(expenseDate))
            put("note", kotlinx.serialization.json.JsonPrimitive(note ?: ""))
        }

        syncQueueDao.enqueue(
            SyncQueueEntity(
                transactionUuid = txUuid,
                entityType = "EXPENSE",
                operation = "CREATE",
                payloadJson = payload.toString(),
                status = SyncStatus.PENDING
            )
        )

        return id
    }
}

class DailyClosingRepository(private val database: AppDatabase) {
    private val closingDao = database.dailyClosingDao()
    private val billDao = database.billDao()
    private val expenseDao = database.expenseDao()

    fun getAllClosings(): Flow<List<DailyClosing>> {
        return closingDao.getAllClosings().map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun getClosingForDate(date: String): DailyClosing? {
        return closingDao.getClosingByDate(date)?.toDomain()
    }

    suspend fun submitClosing(
        closingDate: String,
        actualCash: Double,
        expectedCash: Double,
        notes: String?
    ): Long {
        val cashDiff = actualCash - expectedCash
        val entity = DailyClosingEntity(
            shopId = 1,
            closingDate = closingDate,
            expectedCash = expectedCash,
            actualCash = actualCash,
            cashDifference = cashDiff,
            notes = notes,
            isClosed = true,
            closedAt = DateUtils.nowIso(),
            syncStatus = SyncStatus.PENDING
        )
        return closingDao.insertClosing(entity)
    }

    private fun DailyClosingEntity.toDomain(): DailyClosing {
        return DailyClosing(
            id = id,
            shopId = shopId,
            closingDate = closingDate,
            totalSales = totalSales,
            totalBills = totalBills,
            cashSales = cashSales,
            upiSales = upiSales,
            cardSales = cardSales,
            otherSales = otherSales,
            grossProfit = grossProfit,
            totalExpenses = totalExpenses,
            netProfit = netProfit,
            expectedCash = expectedCash,
            actualCash = actualCash,
            cashDifference = cashDifference,
            notes = notes,
            isClosed = isClosed,
            closedAt = closedAt
        )
    }
}

class SyncRepository(
    private val database: AppDatabase,
    private val apiService: MatoshreeApiService
) {
    private val syncQueueDao = database.syncQueueDao()
    private val billDao = database.billDao()

    val pendingSyncCount: Flow<Int> = syncQueueDao.getPendingCount()

    suspend fun performSync(deviceId: String = "android_pos_1"): Boolean {
        val pending = syncQueueDao.getPendingItems()
        if (pending.isEmpty()) return true

        val syncItems = pending.mapNotNull { item ->
            try {
                val jsonElement = Json.parseToJsonElement(item.payloadJson).jsonObject
                SyncItemDto(
                    transaction_uuid = item.transactionUuid,
                    entity_type = item.entityType,
                    operation = item.operation,
                    payload = jsonElement
                )
            } catch (e: Exception) {
                null
            }
        }

        if (syncItems.isEmpty()) return true

        return try {
            val response = apiService.syncBatch(
                BatchSyncRequest(
                    device_id = deviceId,
                    sync_items = syncItems
                )
            )

            if (response.isSuccessful && response.body()?.data != null) {
                val results = response.body()!!.data!!.results
                for (res in results) {
                    if (res.status == "SUCCESS" || res.status == "DUPLICATE") {
                        syncQueueDao.deleteByUuid(res.transaction_uuid)
                        billDao.updateSyncStatus(res.transaction_uuid, SyncStatus.SYNCED, res.server_id)
                    } else {
                        // Mark failed
                        val item = pending.find { it.transactionUuid == res.transaction_uuid }
                        if (item != null) {
                            syncQueueDao.updateItemStatus(item.id, SyncStatus.FAILED, res.error)
                        }
                    }
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}

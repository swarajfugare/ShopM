package com.matoshree.shopmanager.data.local.dao

import androidx.room.*
import com.matoshree.shopmanager.data.local.entity.*
import com.matoshree.shopmanager.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

data class BillWithItems(
    @Embedded val bill: BillEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "billId"
    )
    val items: List<BillItemEntity>
)

@Dao
interface BillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBill(bill: BillEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBillItems(items: List<BillItemEntity>)

    @Transaction
    suspend fun insertFullBill(bill: BillEntity, items: List<BillItemEntity>): Long {
        val billId = insertBill(bill)
        val itemsWithId = items.map { it.copy(billId = billId) }
        insertBillItems(itemsWithId)
        return billId
    }

    @Transaction
    @Query("SELECT * FROM bills WHERE isVoided = 0 ORDER BY billDate DESC, id DESC LIMIT :limit")
    fun getRecentBills(limit: Int = 50): Flow<List<BillWithItems>>

    @Transaction
    @Query("SELECT * FROM bills WHERE id = :id LIMIT 1")
    suspend fun getBillById(id: Long): BillWithItems?

    @Transaction
    @Query("SELECT * FROM bills WHERE transactionUuid = :uuid LIMIT 1")
    suspend fun getBillByUuid(uuid: String): BillWithItems?

    @Query("SELECT COUNT(id) FROM bills WHERE billDate LIKE :yearPrefix || '%'")
    suspend fun getYearlySequenceCount(yearPrefix: String): Int

    @Query("SELECT COALESCE(SUM(finalAmount), 0.0) FROM bills WHERE billDate LIKE :datePrefix || '%' AND isVoided = 0")
    fun getSalesSumForDate(datePrefix: String): Flow<Double>

    @Query("SELECT COUNT(id) FROM bills WHERE billDate LIKE :datePrefix || '%' AND isVoided = 0")
    fun getBillsCountForDate(datePrefix: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(estimatedProfit + actualProfit), 0.0) FROM bills WHERE billDate LIKE :datePrefix || '%' AND isVoided = 0")
    fun getProfitSumForDate(datePrefix: String): Flow<Double>

    @Query("UPDATE bills SET isVoided = 1, paymentStatus = 'VOID', voidReason = :reason WHERE id = :id")
    suspend fun voidBill(id: Long, reason: String)

    @Query("UPDATE bills SET syncStatus = :status, serverId = :serverId WHERE transactionUuid = :uuid")
    suspend fun updateSyncStatus(uuid: String, status: SyncStatus, serverId: Long?)
}

@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomers(customers: List<CustomerEntity>)

    @Query("SELECT * FROM customers WHERE isActive = 1 ORDER BY lastPurchaseAt DESC, lifetimeSpend DESC")
    fun getAllCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE isActive = 1 AND (name LIKE '%' || :query || '%' OR mobile LIKE '%' || :query || '%') ORDER BY lifetimeSpend DESC LIMIT 20")
    suspend fun searchCustomers(query: String): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun getCustomerById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE mobile = :mobile LIMIT 1")
    suspend fun getCustomerByMobile(mobile: String): CustomerEntity?

    @Query("UPDATE customers SET totalBills = totalBills + 1, lifetimeSpend = lifetimeSpend + :amount, lastPurchaseAt = :purchaseDate WHERE id = :id")
    suspend fun incrementCustomerSpend(id: Long, amount: Double, purchaseDate: String)
}

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: ProductEntity): Long

    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY name ASC")
    fun getAllProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE isActive = 1 AND (name LIKE '%' || :query || '%' OR sku LIKE '%' || :query || '%') ORDER BY name ASC")
    suspend fun searchProducts(query: String): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getProductById(id: Long): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories WHERE isActive = 1 ORDER BY name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>
}

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Query("SELECT * FROM expenses ORDER BY expenseDate DESC, id DESC")
    fun getAllExpenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM expenses WHERE expenseDate = :date")
    suspend fun getDailyExpenseTotal(date: String): Double
}

@Dao
interface DailyClosingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClosing(closing: DailyClosingEntity): Long

    @Query("SELECT * FROM daily_closings WHERE closingDate = :date LIMIT 1")
    suspend fun getClosingByDate(date: String): DailyClosingEntity?

    @Query("SELECT * FROM daily_closings ORDER BY closingDate DESC")
    fun getAllClosings(): Flow<List<DailyClosingEntity>>
}

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(item: SyncQueueEntity): Long

    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY id ASC")
    suspend fun getPendingItems(): List<SyncQueueEntity>

    @Query("UPDATE sync_queue SET status = :status, retryCount = retryCount + 1, lastError = :error WHERE id = :id")
    suspend fun updateItemStatus(id: Long, status: SyncStatus, error: String? = null)

    @Query("DELETE FROM sync_queue WHERE transactionUuid = :uuid")
    suspend fun deleteByUuid(uuid: String)

    @Query("SELECT COUNT(id) FROM sync_queue WHERE status = 'PENDING' OR status = 'FAILED'")
    fun getPendingCount(): Flow<Int>
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(setting: SettingsEntity)

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?
}

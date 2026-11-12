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
    @Query("SELECT * FROM bills WHERE serverId = :serverId LIMIT 1")
    suspend fun getBillByServerId(serverId: Long): BillWithItems?

    @Transaction
    @Query("SELECT * FROM bills WHERE transactionUuid = :uuid LIMIT 1")
    suspend fun getBillByUuid(uuid: String): BillWithItems?

    @Transaction
    @Query("SELECT * FROM bills WHERE billNumber = :billNumber LIMIT 1")
    suspend fun getBillByNumber(billNumber: String): BillWithItems?

    @Query("SELECT COUNT(id) FROM bills WHERE billDate LIKE :yearPrefix || '%'")
    suspend fun getYearlySequenceCount(yearPrefix: String): Int

    @Query("SELECT COALESCE(SUM(finalAmount), 0.0) FROM bills WHERE billDate LIKE :datePrefix || '%' AND isVoided = 0")
    fun getSalesSumForDate(datePrefix: String): Flow<Double>

    @Query("SELECT COUNT(id) FROM bills WHERE billDate LIKE :datePrefix || '%' AND isVoided = 0")
    fun getBillsCountForDate(datePrefix: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(estimatedProfit + actualProfit), 0.0) FROM bills WHERE billDate LIKE :datePrefix || '%' AND isVoided = 0")
    fun getProfitSumForDate(datePrefix: String): Flow<Double>

    @Transaction
    @Query("SELECT * FROM bills ORDER BY billDate DESC, id DESC LIMIT :limit")
    fun getAllBills(limit: Int = 200): Flow<List<BillWithItems>>

    @Transaction
    @Query("SELECT * FROM bills WHERE isVoided = 0 ORDER BY id DESC LIMIT :limit")
    suspend fun getAllBillsDirect(limit: Int = 200): List<BillWithItems>

    @Transaction
    @Query("SELECT * FROM bills WHERE billDate >= :startDate AND billDate <= :endDate")
    suspend fun getBillsInDateRange(startDate: String, endDate: String): List<BillWithItems>

    @Query("UPDATE bills SET isVoided = 1, paymentStatus = 'VOID', voidReason = :reason WHERE id = :id")
    suspend fun voidBill(id: Long, reason: String)

    @Query("UPDATE bills SET syncStatus = :status, serverId = :serverId WHERE transactionUuid = :uuid")
    suspend fun updateSyncStatus(uuid: String, status: SyncStatus, serverId: Long?)

    @Transaction
    @Query("SELECT * FROM bills WHERE syncStatus = 'PENDING' ORDER BY id ASC")
    suspend fun getPendingSyncBills(): List<BillWithItems>

    @Query("SELECT COUNT(id) FROM bills WHERE syncStatus = 'PENDING'")
    fun getPendingBillsCount(): Flow<Int>

    @Query("DELETE FROM bill_items WHERE billId = :billId")
    suspend fun deleteBillItems(billId: Long)

    @Transaction
    @Query("SELECT * FROM bills WHERE customerId = :customerId OR (customerMobile IS NOT NULL AND customerMobile != '' AND (customerMobile = :mobile OR customerMobile LIKE '%' || :mobile || '%')) OR (customerName IS NOT NULL AND customerName != '' AND LOWER(customerName) = LOWER(:name))")
    suspend fun getBillsByCustomerOrDetails(customerId: Long, mobile: String, name: String): List<BillWithItems>

    @Query("DELETE FROM bills WHERE customerId = :customerId OR (customerMobile IS NOT NULL AND customerMobile != '' AND (customerMobile = :mobile OR customerMobile LIKE '%' || :mobile || '%')) OR (customerName IS NOT NULL AND customerName != '' AND LOWER(customerName) = LOWER(:name))")
    suspend fun deleteBillsByCustomerOrDetails(customerId: Long, mobile: String, name: String)

    @Transaction
    @Query("SELECT * FROM bills WHERE customerId = :customerId OR (customerMobile IS NOT NULL AND customerMobile != '' AND customerMobile = :mobile)")
    suspend fun getBillsByCustomer(customerId: Long, mobile: String): List<BillWithItems>

    @Query("DELETE FROM bills WHERE customerId = :customerId OR (customerMobile IS NOT NULL AND customerMobile != '' AND customerMobile = :mobile)")
    suspend fun deleteBillsByCustomer(customerId: Long, mobile: String)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteBillById(id: Long)

    @Transaction
    suspend fun upsertServerBill(bill: BillEntity, items: List<BillItemEntity>) {
        val existing = (if (bill.transactionUuid.isNotBlank()) getBillByUuid(bill.transactionUuid) else null)
            ?: (if (bill.serverId != null && bill.serverId > 0) getBillByServerId(bill.serverId) else null)
            ?: (if (bill.billNumber.isNotBlank()) getBillByNumber(bill.billNumber) else null)

        if (existing != null) {
            // Update existing bill with its existing local Room ID only if data changed
            val isDataChanged = existing.bill.isVoided != bill.isVoided ||
                existing.bill.paymentStatus != bill.paymentStatus ||
                Math.abs(existing.bill.finalAmount - bill.finalAmount) > 0.01

            if (isDataChanged) {
                val updated = bill.copy(id = existing.bill.id, syncStatus = SyncStatus.SYNCED)
                insertBill(updated)
            } else if (existing.bill.syncStatus != SyncStatus.SYNCED && bill.syncStatus == SyncStatus.SYNCED) {
                updateSyncStatus(existing.bill.transactionUuid, SyncStatus.SYNCED, existing.bill.serverId ?: bill.serverId)
            }

            if (existing.items.isEmpty() && items.isNotEmpty()) {
                val itemsWithId = items.map { it.copy(id = 0, billId = existing.bill.id) }
                insertBillItems(itemsWithId)
            }
            return
        }

        // Fresh bill from server: ensure local id is 0 so SQLite auto-generates unique primary key
        val freshBill = bill.copy(id = 0)
        val billId = insertBill(freshBill)
        if (items.isNotEmpty()) {
            val itemsWithId = items.map { it.copy(id = 0, billId = billId) }
            insertBillItems(itemsWithId)
        }
    }
}

@Dao
interface CustomerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomers(customers: List<CustomerEntity>)

    @Query("SELECT * FROM customers WHERE isActive = 1 ORDER BY id DESC")
    fun getAllCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE isActive = 1 AND (name LIKE '%' || :query || '%' OR mobile LIKE '%' || :query || '%') ORDER BY lifetimeSpend DESC LIMIT 20")
    suspend fun searchCustomers(query: String): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun getCustomerById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE serverId = :serverId LIMIT 1")
    suspend fun getCustomerByServerId(serverId: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE mobile = :mobile AND isActive = 1 LIMIT 1")
    suspend fun getCustomerByMobile(mobile: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE mobile = :mobile LIMIT 1")
    suspend fun getAnyCustomerByMobile(mobile: String): CustomerEntity?

    @Query("UPDATE customers SET totalBills = totalBills + 1, lifetimeSpend = lifetimeSpend + :amount, lastPurchaseAt = :purchaseDate WHERE id = :id")
    suspend fun incrementCustomerSpend(id: Long, amount: Double, purchaseDate: String)

    @Query("UPDATE customers SET totalBills = MAX(0, totalBills - 1), lifetimeSpend = MAX(0.0, lifetimeSpend - :amount) WHERE id = :customerId")
    suspend fun adjustCustomerOnVoid(customerId: Long, amount: Double)

    @Query("SELECT COUNT(id) FROM bills WHERE customerId = :customerId")
    suspend fun getCustomerBillCount(customerId: Long): Int

    @Query("UPDATE customers SET isActive = 0 WHERE id = :id")
    suspend fun archiveCustomer(id: Long)

    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun hardDeleteCustomer(id: Long)

    @Query("DELETE FROM customers WHERE (mobile IS NOT NULL AND mobile != '' AND (mobile = :mobile OR mobile LIKE '%' || :mobile || '%')) OR (name IS NOT NULL AND LOWER(name) = LOWER(:name))")
    suspend fun hardDeleteCustomerByDetails(mobile: String, name: String)

    @Query("UPDATE customers SET totalBills = 0, lifetimeSpend = 0.0 WHERE id = :id")
    suspend fun resetCustomerStats(id: Long)

    @Query("UPDATE customers SET isActive = 1 WHERE id = :id")
    suspend fun unarchiveCustomer(id: Long)

    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun deleteCustomer(id: Long)

    @Query("UPDATE customers SET serverId = :serverId, syncStatus = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, serverId: Long, status: SyncStatus)

    @Query("SELECT * FROM customers WHERE syncStatus = 'PENDING' ORDER BY id ASC")
    suspend fun getPendingSyncCustomers(): List<CustomerEntity>

    @Query("SELECT COUNT(id) FROM customers WHERE syncStatus = 'PENDING'")
    fun getPendingCustomersCount(): Flow<Int>

    @Transaction
    suspend fun upsertServerCustomer(customer: CustomerEntity) {
        val existingByServer = if (customer.serverId != null) getCustomerByServerId(customer.serverId) else null
        val existingByMobile = if (existingByServer == null && customer.mobile.isNotBlank()) getCustomerByMobile(customer.mobile) else null
        val existing = existingByServer ?: existingByMobile

        if (existing != null) {
            // Only update Room if fields actually changed (avoids invalidation loops and list jitter)
            if (existing.name != customer.name ||
                existing.mobile != customer.mobile ||
                existing.address != customer.address ||
                existing.totalBills != customer.totalBills ||
                Math.abs(existing.lifetimeSpend - customer.lifetimeSpend) > 0.01 ||
                existing.isActive != customer.isActive
            ) {
                val updated = customer.copy(id = existing.id, serverId = existing.serverId ?: customer.serverId)
                insertCustomer(updated)
            }
        } else {
            insertCustomer(customer.copy(id = 0))
        }
    }
}

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: ProductEntity): Long

    @Update
    suspend fun updateProduct(product: ProductEntity)

    @Query("UPDATE products SET serverId = :serverId WHERE id = :id")
    suspend fun updateServerId(id: Long, serverId: Long)

    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY id DESC")
    fun getAllProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE isActive = 1 AND (name LIKE '%' || :query || '%' OR sku LIKE '%' || :query || '%') ORDER BY id DESC")
    suspend fun searchProducts(query: String): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun getProductById(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE serverId = :serverId LIMIT 1")
    suspend fun getProductByServerId(serverId: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE sku = :sku LIMIT 1")
    suspend fun getProductBySku(sku: String): ProductEntity?

    @Query("SELECT COUNT(id) FROM bill_items WHERE productId = :productId")
    suspend fun getProductBillItemCount(productId: Long): Int

    @Query("UPDATE products SET isActive = 0 WHERE id = :id")
    suspend fun archiveProduct(id: Long)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteProduct(id: Long)

    @Query("UPDATE products SET currentStock = MAX(0, currentStock - :quantity), totalSold = totalSold + :quantity WHERE id = :productId")
    suspend fun recordSaleStockDeduction(productId: Long, quantity: Int)

    @Query("UPDATE products SET currentStock = currentStock + :quantity, totalSold = MAX(0, totalSold - :quantity) WHERE id = :productId")
    suspend fun recordSaleStockRestore(productId: Long, quantity: Int)

    @Query("UPDATE products SET currentStock = :newStock, totalStockAdded = totalStockAdded + :stockAddedDelta WHERE id = :productId")
    suspend fun updateStockQuantity(productId: Long, newStock: Int, stockAddedDelta: Int)

    @Query("SELECT * FROM products WHERE isActive = 1 AND trackInventory = 1 AND currentStock <= :threshold ORDER BY currentStock ASC")
    fun getLowStockProducts(threshold: Int = 5): Flow<List<ProductEntity>>

    @Transaction
    suspend fun upsertServerProduct(product: ProductEntity) {
        val existingByServer = if (product.serverId != null) getProductByServerId(product.serverId) else null
        val existingBySku = if (existingByServer == null && !product.sku.isNullOrBlank()) getProductBySku(product.sku) else null
        val existing = existingByServer ?: existingBySku

        if (existing != null) {
            // Only update Room if fields actually changed
            if (existing.name != product.name ||
                existing.sku != product.sku ||
                existing.sellingPrice != product.sellingPrice ||
                existing.costPrice != product.costPrice ||
                existing.currentStock != product.currentStock ||
                existing.totalSold != product.totalSold ||
                existing.isActive != product.isActive ||
                existing.serverId != product.serverId
            ) {
                val updated = product.copy(id = existing.id)
                insertProduct(updated)
            }
        } else {
            insertProduct(product.copy(id = 0))
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("UPDATE categories SET serverId = :serverId WHERE id = :id")
    suspend fun updateCategoryServerId(id: Long, serverId: Long)

    @Query("SELECT * FROM categories WHERE serverId = :serverId LIMIT 1")
    suspend fun getCategoryByServerId(serverId: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun getCategoryByName(name: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE isActive = 1 ORDER BY name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Transaction
    suspend fun upsertServerCategory(category: CategoryEntity) {
        val existingByServer = if (category.serverId != null) getCategoryByServerId(category.serverId) else null
        val existingByName = if (existingByServer == null) getCategoryByName(category.name) else null
        val existing = existingByServer ?: existingByName

        if (existing != null) {
            if (existing.name != category.name || existing.description != category.description || existing.isActive != category.isActive) {
                val updated = category.copy(id = existing.id, serverId = existing.serverId ?: category.serverId)
                insertCategory(updated)
            }
        } else {
            insertCategory(category)
        }
    }

    @Query("UPDATE categories SET name = :newName WHERE id = :id OR serverId = :id")
    suspend fun renameCategory(id: Long, newName: String)

    @Query("UPDATE categories SET isActive = 0 WHERE id = :id OR serverId = :id")
    suspend fun archiveCategory(id: Long)

    @Query("DELETE FROM categories WHERE id = :id OR serverId = :id")
    suspend fun deleteCategory(id: Long)

    @Query("UPDATE products SET categoryId = :newCategoryId WHERE id = :productId")
    suspend fun updateProductCategory(productId: Long, newCategoryId: Long?)
}

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Query("SELECT * FROM expenses ORDER BY expenseDate DESC, id DESC")
    fun getAllExpenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE transactionUuid = :uuid LIMIT 1")
    suspend fun getExpenseByUuid(uuid: String): ExpenseEntity?

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM expenses WHERE expenseDate = :date")
    suspend fun getDailyExpenseTotal(date: String): Double

    @Transaction
    suspend fun upsertServerExpense(expense: ExpenseEntity) {
        val existing = getExpenseByUuid(expense.transactionUuid)
        if (existing != null) {
            val updated = expense.copy(id = existing.id)
            insertExpense(updated)
        } else {
            insertExpense(expense)
        }
    }
}

@Dao
interface DailyClosingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClosing(closing: DailyClosingEntity): Long

    @Query("SELECT * FROM daily_closings WHERE closingDate = :date LIMIT 1")
    suspend fun getClosingByDate(date: String): DailyClosingEntity?

    @Query("SELECT * FROM daily_closings ORDER BY closingDate DESC")
    fun getAllClosings(): Flow<List<DailyClosingEntity>>

    @Transaction
    suspend fun upsertServerClosing(closing: DailyClosingEntity) {
        val existing = getClosingByDate(closing.closingDate)
        if (existing != null) {
            val updated = closing.copy(id = existing.id)
            insertClosing(updated)
        } else {
            insertClosing(closing)
        }
    }
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

    @Query("SELECT COUNT(id) FROM sync_queue WHERE status = 'PENDING' OR status = 'FAILED'")
    suspend fun getPendingCountDirect(): Int
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(setting: SettingsEntity)

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<String?>

    @Query("SELECT * FROM app_settings")
    fun observeAll(): Flow<List<SettingsEntity>>
}


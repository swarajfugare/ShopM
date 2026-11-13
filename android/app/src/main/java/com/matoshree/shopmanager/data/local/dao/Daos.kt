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

    @Update
    suspend fun updateBill(bill: BillEntity)

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
    @Query("SELECT * FROM bills WHERE billDate LIKE :datePrefix || '%' AND isVoided = 0 ORDER BY billDate DESC, id DESC")
    fun getTodayBillsFlow(datePrefix: String): Flow<List<BillWithItems>>

    @Transaction
    @Query("SELECT * FROM bills WHERE billDate >= :startDate AND isVoided = 0 ORDER BY billDate DESC, id DESC")
    fun getWeekBillsFlow(startDate: String): Flow<List<BillWithItems>>

    @Transaction
    @Query("SELECT * FROM bills WHERE id = :id LIMIT 1")
    suspend fun getBillById(id: Long): BillWithItems?

    @Query("DELETE FROM bills WHERE id NOT IN (SELECT MIN(id) FROM bills GROUP BY CASE WHEN transactionUuid IS NOT NULL AND transactionUuid != '' THEN transactionUuid ELSE billNumber END)")
    suspend fun deduplicateBillRows()

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
    fun getAllBills(limit: Int = 10000): Flow<List<BillWithItems>>

    @Transaction
    @Query("SELECT * FROM bills WHERE isVoided = 0 ORDER BY id DESC LIMIT :limit")
    suspend fun getAllBillsDirect(limit: Int = 10000): List<BillWithItems>

    @Transaction
    @Query("SELECT * FROM bills WHERE billDate >= :startDate AND billDate <= :endDate")
    suspend fun getBillsInDateRange(startDate: String, endDate: String): List<BillWithItems>

    @Query("UPDATE bills SET isVoided = 1, paymentStatus = 'VOID', voidReason = :reason WHERE (:id > 0 AND (id = :id OR serverId = :id)) OR (transactionUuid IS NOT NULL AND transactionUuid != '' AND transactionUuid = :uuid) OR (billNumber IS NOT NULL AND billNumber != '' AND billNumber = :billNumber)")
    suspend fun voidBillComprehensive(id: Long, uuid: String?, billNumber: String?, reason: String)

    @Query("UPDATE bills SET isVoided = 1, paymentStatus = 'VOID', voidReason = :reason WHERE transactionUuid = :uuid")
    suspend fun voidBillByUuid(uuid: String, reason: String)

    @Query("DELETE FROM bills WHERE transactionUuid = :uuid")
    suspend fun deleteBillByUuid(uuid: String)

    @Query("DELETE FROM bills WHERE id = :id OR (serverId IS NOT NULL AND serverId = :id) OR (transactionUuid IS NOT NULL AND transactionUuid != '' AND transactionUuid = :uuid) OR (billNumber IS NOT NULL AND billNumber != '' AND billNumber = :billNumber)")
    suspend fun deleteBillComprehensive(id: Long, uuid: String?, billNumber: String?)

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
    @Query("SELECT * FROM bills WHERE (customerId IS NOT NULL AND customerId > 0 AND customerId = :customerId) OR (customerMobile IS NOT NULL AND customerMobile != '' AND (customerMobile = :mobile OR customerMobile LIKE '%' || :mobile || '%')) OR (customerName IS NOT NULL AND customerName != '' AND LOWER(customerName) = LOWER(:name))")
    suspend fun getBillsByCustomerOrDetails(customerId: Long, mobile: String, name: String): List<BillWithItems>

    @Query("DELETE FROM bills WHERE (customerId IS NOT NULL AND customerId > 0 AND customerId = :customerId) OR (customerMobile IS NOT NULL AND customerMobile != '' AND (customerMobile = :mobile OR customerMobile LIKE '%' || :mobile || '%')) OR (customerName IS NOT NULL AND customerName != '' AND LOWER(customerName) = LOWER(:name))")
    suspend fun deleteBillsByCustomerOrDetails(customerId: Long, mobile: String, name: String)

    @Transaction
    @Query("SELECT * FROM bills WHERE (customerId IS NOT NULL AND customerId > 0 AND customerId = :customerId) OR (customerMobile IS NOT NULL AND customerMobile != '' AND customerMobile = :mobile)")
    suspend fun getBillsByCustomer(customerId: Long, mobile: String): List<BillWithItems>

    @Query("DELETE FROM bills WHERE (customerId IS NOT NULL AND customerId > 0 AND customerId = :customerId) OR (customerMobile IS NOT NULL AND customerMobile != '' AND customerMobile = :mobile)")
    suspend fun deleteBillsByCustomer(customerId: Long, mobile: String)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteBillById(id: Long)

    @Query("UPDATE bills SET customerId = NULL, customerMobile = NULL WHERE customerId = :customerId")
    suspend fun disassociateCustomerBills(customerId: Long)

    @Transaction
    suspend fun upsertServerBill(bill: BillEntity, items: List<BillItemEntity>): Long {
        val existingByUuid = if (bill.transactionUuid.isNotBlank()) getBillByUuid(bill.transactionUuid) else null
        val existingByServer = if (existingByUuid == null && bill.serverId != null && bill.serverId > 0) getBillByServerId(bill.serverId) else null
        val existing = existingByUuid ?: existingByServer

        if (existing != null) {
            val localId = existing.bill.id
            // Update existing bill with its existing local Room ID only if data changed
            val isDataChanged = existing.bill.isVoided != bill.isVoided ||
                existing.bill.paymentStatus != bill.paymentStatus ||
                Math.abs(existing.bill.finalAmount - bill.finalAmount) > 0.01 ||
                existing.bill.customerName != bill.customerName ||
                existing.bill.customerMobile != bill.customerMobile ||
                existing.bill.note != bill.note ||
                existing.bill.paymentMethod != bill.paymentMethod ||
                existing.bill.billDate != bill.billDate ||
                existing.bill.voidReason != bill.voidReason ||
                (items.isNotEmpty() && existing.items.isEmpty())

            if (isDataChanged) {
                val updated = bill.copy(
                    id = existing.bill.id,
                    serverId = existing.bill.serverId ?: bill.serverId,
                    syncStatus = SyncStatus.SYNCED,
                    billDate = if (bill.billDate.isNotBlank()) bill.billDate else existing.bill.billDate,
                    customerId = bill.customerId ?: existing.bill.customerId,
                    customerMobile = if (!bill.customerMobile.isNullOrBlank()) bill.customerMobile else existing.bill.customerMobile,
                    customerName = if (!bill.customerName.isNullOrBlank()) bill.customerName else existing.bill.customerName
                )
                // IN-PLACE UPDATE! Do NOT use REPLACE so SQLite foreign key cascade delete on items is NOT triggered!
                updateBill(updated)

                // Only replace items if items actually differ to prevent unnecessary DB churn
                if (items.isNotEmpty()) {
                    val sortedInc = items.sortedBy { it.productId?.toString() ?: it.productNameSnapshot }
                    val sortedCur = existing.items.sortedBy { it.productId?.toString() ?: it.productNameSnapshot }
                    val itemsDiffer = existing.items.size != items.size ||
                        sortedInc.zip(sortedCur).any { (inc, cur) ->
                            inc.quantity != cur.quantity ||
                            Math.abs(inc.sellingPrice - cur.sellingPrice) > 0.01 ||
                            inc.productNameSnapshot != cur.productNameSnapshot
                        }
                    if (itemsDiffer) {
                        deleteBillItems(existing.bill.id)
                        val itemsWithId = items.map { it.copy(id = 0, billId = existing.bill.id) }
                        insertBillItems(itemsWithId)
                    }
                }
            } else {
                if (existing.bill.syncStatus != SyncStatus.SYNCED && bill.syncStatus == SyncStatus.SYNCED) {
                    updateSyncStatus(existing.bill.transactionUuid, SyncStatus.SYNCED, existing.bill.serverId ?: bill.serverId)
                }
                if (existing.items.isEmpty() && items.isNotEmpty()) {
                    val itemsWithId = items.map { it.copy(id = 0, billId = existing.bill.id) }
                    insertBillItems(itemsWithId)
                }
            }
            return localId
        }

        // Fresh bill: ensure local id is 0 so SQLite auto-generates unique primary key
        val freshBill = bill.copy(id = 0)
        val billId = insertBill(freshBill)
        if (items.isNotEmpty()) {
            val itemsWithId = items.map { it.copy(id = 0, billId = billId) }
            insertBillItems(itemsWithId)
        }
        return billId
    }

    @Transaction
    suspend fun upsertServerBills(billsWithItems: List<Pair<BillEntity, List<BillItemEntity>>>) {
        for ((bill, items) in billsWithItems) {
            upsertServerBill(bill, items)
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

    @Query("SELECT * FROM customers WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) AND isActive = 1 LIMIT 1")
    suspend fun getCustomerByName(name: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE (mobile = :mobile OR mobile LIKE '%' || :cleanMobile) AND isActive = 1 LIMIT 1")
    suspend fun getCustomerByMobileFuzzy(mobile: String, cleanMobile: String): CustomerEntity?

    @Query("DELETE FROM customers WHERE id NOT IN (SELECT MIN(id) FROM customers WHERE isActive = 1 GROUP BY CASE WHEN mobile IS NOT NULL AND LENGTH(TRIM(mobile)) >= 10 THEN SUBSTR(TRIM(mobile), -10) ELSE LOWER(TRIM(name)) END)")
    suspend fun deduplicateCustomerRows()

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

    @Query("UPDATE customers SET isActive = 0 WHERE (:serverId IS NOT NULL AND serverId > 0 AND serverId = :serverId) OR (:cleanMobile != '' AND (mobile = :cleanMobile OR mobile LIKE '%' || :cleanMobile)) OR (:name != '' AND LOWER(TRIM(name)) = LOWER(TRIM(:name)))")
    suspend fun archiveCustomerComprehensive(serverId: Long?, cleanMobile: String, name: String)

    @Query("DELETE FROM customers WHERE (:serverId IS NOT NULL AND serverId > 0 AND serverId = :serverId) OR (:cleanMobile != '' AND (mobile = :cleanMobile OR mobile LIKE '%' || :cleanMobile)) OR (:name != '' AND LOWER(TRIM(name)) = LOWER(TRIM(:name)))")
    suspend fun hardDeleteCustomerComprehensive(serverId: Long?, cleanMobile: String, name: String)

    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun hardDeleteCustomer(id: Long)

    @Query("DELETE FROM customers WHERE (mobile IS NOT NULL AND mobile != '' AND (mobile = :mobile OR mobile LIKE '%' || :mobile || '%')) OR (name IS NOT NULL AND LOWER(name) = LOWER(:name))")
    suspend fun hardDeleteCustomerByDetails(mobile: String, name: String)

    @Query("UPDATE customers SET totalBills = 0, lifetimeSpend = 0.0 WHERE id = :id")
    suspend fun resetCustomerStats(id: Long)

    @Query("UPDATE customers SET isActive = 1 WHERE id = :id")
    suspend fun unarchiveCustomer(id: Long)

    @Query("UPDATE customers SET isActive = 1 WHERE name LIKE '%' || :name || '%'")
    suspend fun unarchiveCustomerByName(name: String)

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
        val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(customer.mobile)
        val existingByServer = if (customer.serverId != null && customer.serverId > 0) getCustomerByServerId(customer.serverId) else null
        val existingByMobile = if (existingByServer == null && cleanMob.length == 10) getCustomerByMobileFuzzy(customer.mobile, cleanMob) else null
        val existingByName = if (existingByServer == null && existingByMobile == null && cleanMob.isBlank() && customer.name.isNotBlank()) {
            val byName = getCustomerByName(customer.name)
            if (byName != null && com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(byName.mobile).isBlank()) {
                byName
            } else null
        } else null
        val existing = existingByServer ?: existingByMobile ?: existingByName

        if (existing != null) {
            val existingCleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(existing.mobile)
            // Only update Room if fields actually changed (avoids invalidation loops and list jitter)
            if (existing.name != customer.name ||
                existingCleanMob != cleanMob ||
                existing.address != customer.address ||
                existing.totalBills != customer.totalBills ||
                Math.abs(existing.lifetimeSpend - customer.lifetimeSpend) > 0.01 ||
                existing.isActive != customer.isActive
            ) {
                val updated = customer.copy(
                    id = existing.id,
                    serverId = existing.serverId ?: customer.serverId,
                    mobile = if (cleanMob.length == 10) cleanMob else existing.mobile
                )
                insertCustomer(updated)
            }
        } else {
            val normalized = customer.copy(
                id = 0,
                mobile = if (cleanMob.length == 10) cleanMob else customer.mobile
            )
            insertCustomer(normalized)
        }
    }

    @Transaction
    suspend fun upsertServerCustomers(customers: List<CustomerEntity>) {
        for (customer in customers) {
            upsertServerCustomer(customer)
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


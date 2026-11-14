package com.matoshree.shopmanager.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.matoshree.shopmanager.data.local.dao.*
import com.matoshree.shopmanager.data.local.entity.*

@Database(
    entities = [
        BillEntity::class,
        BillItemEntity::class,
        CustomerEntity::class,
        ProductEntity::class,
        CategoryEntity::class,
        ExpenseEntity::class,
        DailyClosingEntity::class,
        SyncQueueEntity::class,
        SettingsEntity::class,
        ReminderLogEntity::class,
        CustomerAliasEntity::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun billDao(): BillDao
    abstract fun customerDao(): CustomerDao
    abstract fun productDao(): ProductDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun dailyClosingDao(): DailyClosingDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun settingsDao(): SettingsDao
    abstract fun reminderLogDao(): ReminderLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN paidAmount REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE bills ADD COLUMN pendingAmount REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE customers ADD COLUMN outstandingBalance REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_3_5 = object : androidx.room.migration.Migration(3, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN paidAmount REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE bills ADD COLUMN pendingAmount REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE customers ADD COLUMN outstandingBalance REAL NOT NULL DEFAULT 0.0")
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reminder_logs (
                        id TEXT PRIMARY KEY NOT NULL,
                        customerName TEXT NOT NULL,
                        customerMobile TEXT NOT NULL,
                        amount REAL NOT NULL,
                        type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        errorMessage TEXT,
                        timestamp INTEGER NOT NULL,
                        date TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_logs_timestamp ON reminder_logs (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_logs_date ON reminder_logs (date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_logs_customerMobile ON reminder_logs (customerMobile)")
            }
        }

        val MIGRATION_4_6 = object : androidx.room.migration.Migration(4, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
            }
        }

        val MIGRATION_3_6 = object : androidx.room.migration.Migration(3, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_3_5.migrate(db)
                MIGRATION_5_6.migrate(db)
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Add new columns
                db.execSQL("ALTER TABLE bills ADD COLUMN gid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE bills ADD COLUMN customerGid TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN needsReview INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE bill_items ADD COLUMN gid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE bill_items ADD COLUMN billGid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE bill_items ADD COLUMN productGid TEXT DEFAULT NULL")

                db.execSQL("ALTER TABLE customers ADD COLUMN gid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE customers ADD COLUMN needsReview INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE categories ADD COLUMN gid TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE products ADD COLUMN gid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE products ADD COLUMN categoryGid TEXT DEFAULT NULL")

                db.execSQL("ALTER TABLE expenses ADD COLUMN gid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE expenses ADD COLUMN categoryGid TEXT DEFAULT NULL")

                db.execSQL("ALTER TABLE daily_closings ADD COLUMN gid TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE reminder_logs ADD COLUMN customerGid TEXT DEFAULT NULL")

                // 2. Deterministic Backfill of GIDs before creating UNIQUE indices
                val custCursor = db.query("SELECT id, serverId, mobile, name FROM customers")
                while (custCursor.moveToNext()) {
                    val id = custCursor.getLong(0)
                    val srvId = if (!custCursor.isNull(1)) custCursor.getLong(1) else null
                    val mob = custCursor.getString(2) ?: ""
                    val name = custCursor.getString(3) ?: ""
                    val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(mob)
                    val seed = when {
                        srvId != null && srvId > 0 -> "srv_$srvId"
                        cleanMob.length == 10 -> "mob_$cleanMob"
                        name.isNotBlank() -> "name_${name.trim().lowercase()}"
                        else -> "cust_$id"
                    }
                    val gid = com.matoshree.shopmanager.domain.model.IdGenerator.legacyGid(com.matoshree.shopmanager.domain.model.IdType.CUSTOMER, seed)
                    db.execSQL("UPDATE customers SET gid = ? WHERE id = ?", arrayOf(gid, id))
                }
                custCursor.close()

                val catCursor = db.query("SELECT id, name FROM categories")
                while (catCursor.moveToNext()) {
                    val id = catCursor.getLong(0)
                    val name = catCursor.getString(1) ?: ""
                    val seed = if (name.isNotBlank()) "cat_${name.trim().lowercase()}" else "cat_$id"
                    val gid = com.matoshree.shopmanager.domain.model.IdGenerator.legacyGid(com.matoshree.shopmanager.domain.model.IdType.CATEGORY, seed)
                    db.execSQL("UPDATE categories SET gid = ? WHERE id = ?", arrayOf(gid, id))
                }
                catCursor.close()

                val prodCursor = db.query("SELECT id, sku, name, categoryId FROM products")
                while (prodCursor.moveToNext()) {
                    val id = prodCursor.getLong(0)
                    val sku = prodCursor.getString(1)
                    val name = prodCursor.getString(2) ?: ""
                    val seed = if (!sku.isNullOrBlank()) "sku_${sku.trim().uppercase()}" else if (name.isNotBlank()) "prod_${name.trim().lowercase()}" else "prod_$id"
                    val gid = com.matoshree.shopmanager.domain.model.IdGenerator.legacyGid(com.matoshree.shopmanager.domain.model.IdType.PRODUCT, seed)
                    db.execSQL("UPDATE products SET gid = ? WHERE id = ?", arrayOf(gid, id))
                }
                prodCursor.close()
                db.execSQL("UPDATE products SET categoryGid = (SELECT gid FROM categories WHERE categories.id = products.categoryId) WHERE categoryId IS NOT NULL")

                val billCursor = db.query("SELECT id, serverId, transactionUuid, billNumber, customerId FROM bills")
                while (billCursor.moveToNext()) {
                    val id = billCursor.getLong(0)
                    val srvId = if (!billCursor.isNull(1)) billCursor.getLong(1) else null
                    val uuid = billCursor.getString(2) ?: ""
                    val num = billCursor.getString(3) ?: ""
                    val seed = when {
                        uuid.isNotBlank() -> uuid.trim()
                        srvId != null && srvId > 0 -> "srv_$srvId"
                        num.isNotBlank() -> "num_${num.trim().uppercase()}"
                        else -> "bill_$id"
                    }
                    val gid = com.matoshree.shopmanager.domain.model.IdGenerator.legacyGid(com.matoshree.shopmanager.domain.model.IdType.BILL, seed)
                    db.execSQL("UPDATE bills SET gid = ? WHERE id = ?", arrayOf(gid, id))
                }
                billCursor.close()
                db.execSQL("UPDATE bills SET customerGid = (SELECT gid FROM customers WHERE customers.id = bills.customerId) WHERE customerId IS NOT NULL")

                val itemCursor = db.query("SELECT id, billId, productId FROM bill_items")
                while (itemCursor.moveToNext()) {
                    val id = itemCursor.getLong(0)
                    val billId = itemCursor.getLong(1)
                    val seed = "item_${billId}_$id"
                    val gid = com.matoshree.shopmanager.domain.model.IdGenerator.legacyGid(com.matoshree.shopmanager.domain.model.IdType.BILL_ITEM, seed)
                    db.execSQL("UPDATE bill_items SET gid = ? WHERE id = ?", arrayOf(gid, id))
                }
                itemCursor.close()
                db.execSQL("UPDATE bill_items SET billGid = (SELECT gid FROM bills WHERE bills.id = bill_items.billId) WHERE billId IS NOT NULL")
                db.execSQL("UPDATE bill_items SET productGid = (SELECT gid FROM products WHERE products.id = bill_items.productId) WHERE productId IS NOT NULL")

                val expCursor = db.query("SELECT id, transactionUuid FROM expenses")
                while (expCursor.moveToNext()) {
                    val id = expCursor.getLong(0)
                    val uuid = expCursor.getString(1) ?: ""
                    val seed = if (uuid.isNotBlank()) uuid.trim() else "exp_$id"
                    val gid = com.matoshree.shopmanager.domain.model.IdGenerator.legacyGid(com.matoshree.shopmanager.domain.model.IdType.EXPENSE, seed)
                    db.execSQL("UPDATE expenses SET gid = ? WHERE id = ?", arrayOf(gid, id))
                }
                expCursor.close()

                val cloCursor = db.query("SELECT id, closingDate FROM daily_closings")
                while (cloCursor.moveToNext()) {
                    val id = cloCursor.getLong(0)
                    val date = cloCursor.getString(1) ?: ""
                    val seed = if (date.isNotBlank()) date.trim() else "clo_$id"
                    val gid = com.matoshree.shopmanager.domain.model.IdGenerator.legacyGid(com.matoshree.shopmanager.domain.model.IdType.DAILY_CLOSING, seed)
                    db.execSQL("UPDATE daily_closings SET gid = ? WHERE id = ?", arrayOf(gid, id))
                }
                cloCursor.close()

                db.execSQL("UPDATE reminder_logs SET customerGid = (SELECT gid FROM customers WHERE customers.mobile = reminder_logs.customerMobile LIMIT 1) WHERE customerMobile IS NOT NULL AND customerMobile != ''")

                // 3. Create indices
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bills_gid ON bills (gid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bills_customerGid ON bills (customerGid)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bill_items_gid ON bill_items (gid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bill_items_billGid ON bill_items (billGid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bill_items_productGid ON bill_items (productGid)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_customers_gid ON customers (gid)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_gid ON categories (gid)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_gid ON products (gid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_products_categoryGid ON products (categoryGid)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_expenses_gid ON expenses (gid)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_daily_closings_gid ON daily_closings (gid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reminder_logs_customerGid ON reminder_logs (customerGid)")
            }
        }

        val MIGRATION_5_7 = object : androidx.room.migration.Migration(5, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_5_6.migrate(db)
                MIGRATION_6_7.migrate(db)
            }
        }

        val MIGRATION_4_7 = object : androidx.room.migration.Migration(4, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                MIGRATION_6_7.migrate(db)
            }
        }

        val MIGRATION_3_7 = object : androidx.room.migration.Migration(3, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_3_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                MIGRATION_6_7.migrate(db)
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Add docId columns
                db.execSQL("ALTER TABLE customers ADD COLUMN docId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN docId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bill_items ADD COLUMN docId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE categories ADD COLUMN docId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE products ADD COLUMN docId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE expenses ADD COLUMN docId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE daily_closings ADD COLUMN docId TEXT DEFAULT NULL")

                // 2. Customers Repair:
                db.execSQL("UPDATE customers SET docId = 'cust_' || serverId WHERE (docId IS NULL OR docId = '') AND serverId IS NOT NULL AND serverId > 0")

                val custCursor = db.query("SELECT id, docId, mobile, name, gid FROM customers ORDER BY id ASC")
                val custGidMap = mutableMapOf<Long, String>()
                val seenCustGids = mutableMapOf<String, Long>()

                while (custCursor.moveToNext()) {
                    val id = custCursor.getLong(0)
                    val docId = custCursor.getString(1)
                    val existingGid = custCursor.getString(4) ?: ""

                    val newGid = if (!docId.isNullOrBlank()) {
                        com.matoshree.shopmanager.domain.model.IdGenerator.customerGid(docId)
                    } else if (existingGid.isNotBlank() && !com.matoshree.shopmanager.domain.model.IdFormat.isLegacy(existingGid)) {
                        existingGid
                    } else {
                        com.matoshree.shopmanager.domain.model.IdGenerator.generate(com.matoshree.shopmanager.domain.model.IdType.CUSTOMER)
                    }

                    val conflictId = seenCustGids[newGid]
                    if (conflictId != null) {
                        db.execSQL("UPDATE bills SET customerId = ? WHERE customerId = ?", arrayOf(conflictId, id))
                        db.execSQL("UPDATE reminder_logs SET customerGid = ? WHERE customerGid = ?", arrayOf(newGid, existingGid))
                        db.execSQL("DELETE FROM customers WHERE id = ?", arrayOf(id))
                    } else {
                        seenCustGids[newGid] = id
                        custGidMap[id] = newGid
                        db.execSQL("UPDATE customers SET gid = ? WHERE id = ?", arrayOf(newGid, id))
                    }
                }
                custCursor.close()

                // 3. Bills Repair:
                db.execSQL("UPDATE bills SET docId = transactionUuid WHERE (docId IS NULL OR docId = '') AND transactionUuid IS NOT NULL AND transactionUuid != ''")
                db.execSQL("UPDATE bills SET docId = 'bill_' || serverId WHERE (docId IS NULL OR docId = '') AND serverId IS NOT NULL AND serverId > 0")

                val billCursor = db.query("SELECT id, docId, gid, customerId FROM bills ORDER BY id ASC")
                val seenBillGids = mutableSetOf<String>()
                while (billCursor.moveToNext()) {
                    val id = billCursor.getLong(0)
                    val docId = billCursor.getString(1)
                    val existingGid = billCursor.getString(2) ?: ""
                    val customerId = if (!billCursor.isNull(3)) billCursor.getLong(3) else null

                    var newGid = if (!docId.isNullOrBlank()) {
                        com.matoshree.shopmanager.domain.model.IdGenerator.billGid(docId)
                    } else if (existingGid.isNotBlank() && !com.matoshree.shopmanager.domain.model.IdFormat.isLegacy(existingGid)) {
                        existingGid
                    } else {
                        com.matoshree.shopmanager.domain.model.IdGenerator.generate(com.matoshree.shopmanager.domain.model.IdType.BILL)
                    }

                    if (seenBillGids.contains(newGid)) {
                        newGid = com.matoshree.shopmanager.domain.model.IdGenerator.generate(com.matoshree.shopmanager.domain.model.IdType.BILL)
                        db.execSQL("UPDATE bills SET gid = ?, needsReview = 1 WHERE id = ?", arrayOf(newGid, id))
                    } else {
                        seenBillGids.add(newGid)
                        db.execSQL("UPDATE bills SET gid = ? WHERE id = ?", arrayOf(newGid, id))
                    }

                    val custGid = if (customerId != null) custGidMap[customerId] else null
                    if (custGid != null) {
                        db.execSQL("UPDATE bills SET customerGid = ? WHERE id = ?", arrayOf(custGid, id))
                    }
                }
                billCursor.close()

                // 4. Categories, Products, Bill Items, Expenses, Daily Closings Repair:
                val catCursor = db.query("SELECT id, docId, gid FROM categories ORDER BY id ASC")
                val seenCatGids = mutableSetOf<String>()
                while (catCursor.moveToNext()) {
                    val id = catCursor.getLong(0)
                    val docId = catCursor.getString(1)
                    val existingGid = catCursor.getString(2) ?: ""
                    var newGid = if (!docId.isNullOrBlank()) com.matoshree.shopmanager.domain.model.IdGenerator.categoryGid(docId)
                                 else if (existingGid.isNotBlank() && !com.matoshree.shopmanager.domain.model.IdFormat.isLegacy(existingGid)) existingGid
                                 else com.matoshree.shopmanager.domain.model.IdGenerator.generate(com.matoshree.shopmanager.domain.model.IdType.CATEGORY)
                    if (seenCatGids.contains(newGid)) newGid = com.matoshree.shopmanager.domain.model.IdGenerator.generate(com.matoshree.shopmanager.domain.model.IdType.CATEGORY)
                    seenCatGids.add(newGid)
                    db.execSQL("UPDATE categories SET gid = ? WHERE id = ?", arrayOf(newGid, id))
                }
                catCursor.close()

                val prodCursor = db.query("SELECT id, docId, serverId, gid FROM products ORDER BY id ASC")
                val seenProdGids = mutableSetOf<String>()
                while (prodCursor.moveToNext()) {
                    val id = prodCursor.getLong(0)
                    val docId = prodCursor.getString(1)
                    val serverId = if (!prodCursor.isNull(2)) prodCursor.getLong(2) else null
                    val existingGid = prodCursor.getString(3) ?: ""
                    val effectiveDocId = if (!docId.isNullOrBlank()) docId else if (serverId != null && serverId > 0) "prod_$serverId" else null

                    var newGid = if (effectiveDocId != null) com.matoshree.shopmanager.domain.model.IdGenerator.productGid(effectiveDocId)
                                 else if (existingGid.isNotBlank() && !com.matoshree.shopmanager.domain.model.IdFormat.isLegacy(existingGid)) existingGid
                                 else com.matoshree.shopmanager.domain.model.IdGenerator.generate(com.matoshree.shopmanager.domain.model.IdType.PRODUCT)
                    if (seenProdGids.contains(newGid)) newGid = com.matoshree.shopmanager.domain.model.IdGenerator.generate(com.matoshree.shopmanager.domain.model.IdType.PRODUCT)
                    seenProdGids.add(newGid)
                    db.execSQL("UPDATE products SET gid = ? WHERE id = ?", arrayOf(newGid, id))
                }
                prodCursor.close()
                db.execSQL("UPDATE products SET categoryGid = (SELECT gid FROM categories WHERE categories.id = products.categoryId) WHERE categoryId IS NOT NULL")

                db.execSQL("UPDATE bill_items SET billGid = (SELECT gid FROM bills WHERE bills.id = bill_items.billId) WHERE billId IS NOT NULL")
                db.execSQL("UPDATE bill_items SET productGid = (SELECT gid FROM products WHERE products.id = bill_items.productId) WHERE productId IS NOT NULL")

                val expCursor = db.query("SELECT id, transactionUuid, gid FROM expenses ORDER BY id ASC")
                val seenExpGids = mutableSetOf<String>()
                while (expCursor.moveToNext()) {
                    val id = expCursor.getLong(0)
                    val uuid = expCursor.getString(1)
                    val existingGid = expCursor.getString(2) ?: ""
                    var newGid = if (!uuid.isNullOrBlank()) com.matoshree.shopmanager.domain.model.IdGenerator.expenseGid(uuid.trim())
                                 else if (existingGid.isNotBlank() && !com.matoshree.shopmanager.domain.model.IdFormat.isLegacy(existingGid)) existingGid
                                 else com.matoshree.shopmanager.domain.model.IdGenerator.generate(com.matoshree.shopmanager.domain.model.IdType.EXPENSE)
                    if (seenExpGids.contains(newGid)) newGid = com.matoshree.shopmanager.domain.model.IdGenerator.generate(com.matoshree.shopmanager.domain.model.IdType.EXPENSE)
                    seenExpGids.add(newGid)
                    db.execSQL("UPDATE expenses SET gid = ? WHERE id = ?", arrayOf(newGid, id))
                }
                expCursor.close()

                val cloCursor = db.query("SELECT id, closingDate, gid FROM daily_closings ORDER BY id ASC")
                val seenCloGids = mutableSetOf<String>()
                while (cloCursor.moveToNext()) {
                    val id = cloCursor.getLong(0)
                    val cDate = cloCursor.getString(1)
                    val existingGid = cloCursor.getString(2) ?: ""
                    var newGid = if (!cDate.isNullOrBlank()) com.matoshree.shopmanager.domain.model.IdGenerator.dailyClosingGid(cDate.trim())
                                 else if (existingGid.isNotBlank() && !com.matoshree.shopmanager.domain.model.IdFormat.isLegacy(existingGid)) existingGid
                                 else com.matoshree.shopmanager.domain.model.IdGenerator.generate(com.matoshree.shopmanager.domain.model.IdType.DAILY_CLOSING)
                    if (seenCloGids.contains(newGid)) newGid = com.matoshree.shopmanager.domain.model.IdGenerator.generate(com.matoshree.shopmanager.domain.model.IdType.DAILY_CLOSING)
                    seenCloGids.add(newGid)
                    db.execSQL("UPDATE daily_closings SET gid = ? WHERE id = ?", arrayOf(newGid, id))
                }
                cloCursor.close()

                db.execSQL("UPDATE reminder_logs SET customerGid = (SELECT gid FROM customers WHERE customers.mobile = reminder_logs.customerMobile LIMIT 1) WHERE customerMobile IS NOT NULL AND customerMobile != ''")
            }
        }

        val MIGRATION_6_8 = object : androidx.room.migration.Migration(6, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_6_7.migrate(db)
                MIGRATION_7_8.migrate(db)
            }
        }

        val MIGRATION_5_8 = object : androidx.room.migration.Migration(5, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_5_6.migrate(db)
                MIGRATION_6_7.migrate(db)
                MIGRATION_7_8.migrate(db)
            }
        }

        val MIGRATION_4_8 = object : androidx.room.migration.Migration(4, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_4_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                MIGRATION_6_7.migrate(db)
                MIGRATION_7_8.migrate(db)
            }
        }

        val MIGRATION_3_8 = object : androidx.room.migration.Migration(3, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_3_5.migrate(db)
                MIGRATION_5_6.migrate(db)
                MIGRATION_6_7.migrate(db)
                MIGRATION_7_8.migrate(db)
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Create customer_aliases table
                db.execSQL("CREATE TABLE IF NOT EXISTS `customer_aliases` (`aliasGid` TEXT NOT NULL, `canonicalGid` TEXT NOT NULL, `mergedAt` INTEGER NOT NULL, PRIMARY KEY(`aliasGid`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_customer_aliases_aliasGid` ON `customer_aliases` (`aliasGid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_customer_aliases_canonicalGid` ON `customer_aliases` (`canonicalGid`)")

                // 2. Real docId and GID calculation for all customers:
                // If 10-digit mobile exists: docId MUST be 'cust_m_' || cleanMobile
                // GID MUST be IdGenerator.customerGid(docId)
                val custCursor = db.query("SELECT id, docId, mobile, name, gid, serverId FROM customers ORDER BY id ASC")
                data class CustRow(val id: Long, val docId: String?, val mobile: String, val name: String, val gid: String, val serverId: Long?)
                val rows = mutableListOf<CustRow>()
                while (custCursor.moveToNext()) {
                    rows.add(
                        CustRow(
                            id = custCursor.getLong(0),
                            docId = if (custCursor.isNull(1)) null else custCursor.getString(1),
                            mobile = custCursor.getString(2) ?: "",
                            name = custCursor.getString(3) ?: "",
                            gid = custCursor.getString(4) ?: "",
                            serverId = if (custCursor.isNull(5)) null else custCursor.getLong(5)
                        )
                    )
                }
                custCursor.close()

                // Group by clean 10-digit mobile when available, or by real docId
                val phoneGroups = rows.groupBy {
                    val clean = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(it.mobile)
                    if (clean.length == 10) "mob_$clean"
                    else if (!it.docId.isNullOrBlank() && !it.docId.startsWith("cust_") && it.docId.length > 10) "doc_${it.docId}"
                    else "id_${it.id}"
                }

                val remappedCustomerGids = mutableMapOf<String, String>() // oldGid -> canonicalGid

                for ((groupKey, groupRows) in phoneGroups) {
                    val survivor = groupRows.first()
                    val cleanMob = com.matoshree.shopmanager.utils.PhoneUtils.clean10DigitMobileInput(survivor.mobile)
                    val canonicalDocId = when {
                        cleanMob.length == 10 -> "cust_m_$cleanMob"
                        !survivor.docId.isNullOrBlank() && !survivor.docId.startsWith("cust_") -> survivor.docId
                        survivor.serverId != null && survivor.serverId > 0 -> "cust_${survivor.serverId}"
                        else -> "cust_${System.currentTimeMillis()}_${survivor.id}"
                    }
                    val canonicalGid = com.matoshree.shopmanager.domain.model.IdGenerator.customerGid(canonicalDocId)

                    // Update survivor
                    db.execSQL("UPDATE customers SET docId = ?, gid = ? WHERE id = ?", arrayOf(canonicalDocId, canonicalGid, survivor.id))

                    if (survivor.gid.isNotBlank() && survivor.gid != canonicalGid) {
                        remappedCustomerGids[survivor.gid] = canonicalGid
                        db.execSQL("INSERT OR REPLACE INTO customer_aliases (aliasGid, canonicalGid, mergedAt) VALUES (?, ?, ?)",
                            arrayOf(survivor.gid, canonicalGid, System.currentTimeMillis()))
                    }

                    // Merge duplicates in this group
                    if (groupRows.size > 1) {
                        for (i in 1 until groupRows.size) {
                            val dup = groupRows[i]
                            if (dup.gid.isNotBlank() && dup.gid != canonicalGid) {
                                remappedCustomerGids[dup.gid] = canonicalGid
                                db.execSQL("INSERT OR REPLACE INTO customer_aliases (aliasGid, canonicalGid, mergedAt) VALUES (?, ?, ?)",
                                    arrayOf(dup.gid, canonicalGid, System.currentTimeMillis()))
                            }
                            // Re-point bills attached to duplicate row
                            db.execSQL("UPDATE bills SET customerId = ?, customerGid = ? WHERE customerId = ?", arrayOf(survivor.id, canonicalGid, dup.id))
                            if (dup.gid.isNotBlank()) {
                                db.execSQL("UPDATE bills SET customerId = ?, customerGid = ? WHERE customerGid = ?", arrayOf(survivor.id, canonicalGid, dup.gid))
                                db.execSQL("UPDATE reminder_logs SET customerGid = ? WHERE customerGid = ?", arrayOf(canonicalGid, dup.gid))
                            }
                            // Safely remove the redundant duplicate customer row now that all references are re-pointed
                            db.execSQL("DELETE FROM customers WHERE id = ?", arrayOf(dup.id))
                        }
                    }
                }

                // 3. Re-point any bills and reminder_logs whose customerGid is an alias
                for ((oldGid, newGid) in remappedCustomerGids) {
                    db.execSQL("UPDATE bills SET customerGid = ? WHERE customerGid = ?", arrayOf(newGid, oldGid))
                    db.execSQL("UPDATE reminder_logs SET customerGid = ? WHERE customerGid = ?", arrayOf(newGid, oldGid))
                }
            }
        }

        val MIGRATION_7_9 = object : androidx.room.migration.Migration(7, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_7_8.migrate(db)
                MIGRATION_8_9.migrate(db)
            }
        }

        val MIGRATION_6_9 = object : androidx.room.migration.Migration(6, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_6_8.migrate(db)
                MIGRATION_8_9.migrate(db)
            }
        }

        val MIGRATION_5_9 = object : androidx.room.migration.Migration(5, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_5_8.migrate(db)
                MIGRATION_8_9.migrate(db)
            }
        }

        val MIGRATION_4_9 = object : androidx.room.migration.Migration(4, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_4_8.migrate(db)
                MIGRATION_8_9.migrate(db)
            }
        }

        val MIGRATION_3_9 = object : androidx.room.migration.Migration(3, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                MIGRATION_3_8.migrate(db)
                MIGRATION_8_9.migrate(db)
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "matoshree_shop_manager.db"
                )
                .addMigrations(
                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_3_5, MIGRATION_5_6, MIGRATION_4_6, MIGRATION_3_6,
                    MIGRATION_6_7, MIGRATION_5_7, MIGRATION_4_7, MIGRATION_3_7,
                    MIGRATION_7_8, MIGRATION_6_8, MIGRATION_5_8, MIGRATION_4_8, MIGRATION_3_8,
                    MIGRATION_8_9, MIGRATION_7_9, MIGRATION_6_9, MIGRATION_5_9, MIGRATION_4_9, MIGRATION_3_9
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

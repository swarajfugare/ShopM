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
        ReminderLogEntity::class
    ],
    version = 6,
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "matoshree_shop_manager.db"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_3_5, MIGRATION_5_6, MIGRATION_4_6, MIGRATION_3_6)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

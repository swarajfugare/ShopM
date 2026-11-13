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
        SettingsEntity::class
    ],
    version = 5,
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "matoshree_shop_manager.db"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_3_5)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

package com.matoshree.shopmanager

import android.app.Application
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.entity.CategoryEntity
import com.matoshree.shopmanager.data.local.entity.CustomerEntity
import com.matoshree.shopmanager.data.local.entity.ProductEntity
import com.matoshree.shopmanager.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MatoshreeApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Room Local Database & Clean legacy mock seed data
        val database = AppDatabase.getInstance(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                cleanMockDataIfPresent(database)
            } catch (e: Exception) {
                android.util.Log.w("MatoshreeApp", "Mock data cleanup notice: ${e.message}")
            }
        }

        // 2. Initialize Firebase Cloud Firestore & Offline Persistence
        com.matoshree.shopmanager.data.firebase.FirebaseManager.init(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val diag = com.matoshree.shopmanager.data.firebase.FirebaseManager.runDiagnostics()
                android.util.Log.d("MatoshreeApp", "Firebase Diagnostic Results: ${diag.details}")
            } catch (e: Exception) {
                android.util.Log.w("MatoshreeApp", "Firebase Diagnostics notice: ${e.message}")
            }
        }

        // 3. Initialize Version 2 Smart Notifications Engine
        com.matoshree.shopmanager.notifications.ShopNotificationManager.initNotificationChannels(this)
        com.matoshree.shopmanager.notifications.NotificationScheduler.scheduleAllDailyReminders(this)
        // Note: Realtime multi-device notifications are natively dispatched via RealtimeSyncManager once authenticated
    }

    private suspend fun cleanMockDataIfPresent(database: AppDatabase) {
        val productDao = database.productDao()
        val customerDao = database.customerDao()

        // Purge mock customers if seeded previously
        val existingCust = customerDao.searchCustomers("")
        val mockCusts = existingCust.filter { it.email?.endsWith("@example.com") == true }
        if (mockCusts.isNotEmpty()) {
            mockCusts.forEach { customerDao.deleteCustomer(it.id) }
            android.util.Log.d("MatoshreeApp", "Removed ${mockCusts.size} legacy mock customers from Room")
        }

        // Purge mock products if seeded previously
        val existingProds = productDao.searchProducts("")
        val mockSkus = setOf("MC-SK-9082", "MC-PS-4011", "MC-KD-004", "MC-CC-102", "MC-JW-441")
        val mockProds = existingProds.filter { mockSkus.contains(it.sku) }
        if (mockProds.isNotEmpty()) {
            mockProds.forEach { productDao.deleteProduct(it.id) }
            android.util.Log.d("MatoshreeApp", "Removed ${mockProds.size} legacy mock products from Room")
        }
    }
}

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

        // 1. Initialize Room Local Database
        val database = AppDatabase.getInstance(this)

        // 2. Schedule background WorkManager sync
        SyncManager.schedulePeriodicSync(this)

        // 3. Seed initial offline boutique data if local database is fresh
        CoroutineScope(Dispatchers.IO).launch {
            seedInitialDataIfEmpty(database)
        }
    }

    private suspend fun seedInitialDataIfEmpty(database: AppDatabase) {
        val productDao = database.productDao()
        val customerDao = database.customerDao()

        // Check if customers table is empty
        val existingCust = customerDao.searchCustomers("")
        if (existingCust.isEmpty()) {
            customerDao.insertCustomers(
                listOf(
                    CustomerEntity(
                        name = "Priya Sharma",
                        mobile = "+91 98765 43210",
                        email = "priya.sharma@example.com",
                        address = "Flat 302, Raj Residency, Kolhapur",
                        totalBills = 4,
                        lifetimeSpend = 38450.0
                    ),
                    CustomerEntity(
                        name = "Sunita Patil",
                        mobile = "+91 98765 43211",
                        email = "sunita.patil@example.com",
                        address = "B-12, Tarabai Park, Kolhapur",
                        totalBills = 2,
                        lifetimeSpend = 22500.0
                    ),
                    CustomerEntity(
                        name = "Sushma Deshmukh",
                        mobile = "+91 87654 32109",
                        email = "sushma.d@example.com",
                        address = "Nagala Park, Kolhapur",
                        totalBills = 1,
                        lifetimeSpend = 18500.0
                    ),
                    CustomerEntity(
                        name = "Sujata Kulkarni",
                        mobile = "+91 76543 21098",
                        email = "sujata.k@example.com",
                        address = "Rajarampuri 5th Lane, Kolhapur",
                        totalBills = 3,
                        lifetimeSpend = 31200.0
                    )
                )
            )
        }

        // Check if products table is empty
        val existingProds = productDao.searchProducts("")
        if (existingProds.isEmpty()) {
            productDao.insertCategories(
                listOf(
                    CategoryEntity(id = 1, name = "Silk Sarees"),
                    CategoryEntity(id = 2, name = "Cotton Sarees"),
                    CategoryEntity(id = 3, name = "Designer Lehengas"),
                    CategoryEntity(id = 4, name = "Kurtis & Suits"),
                    CategoryEntity(id = 5, name = "Dupattas & Stoles"),
                    CategoryEntity(id = 6, name = "Accessories & Jewelry")
                )
            )

            productDao.insertProducts(
                listOf(
                    ProductEntity(
                        name = "Emerald Silk Kanjeevaram Saree",
                        sku = "MC-SK-9082",
                        sellingPrice = 12499.0,
                        costPrice = 9374.0,
                        categoryId = 1,
                        currentStock = 14,
                        trackInventory = true
                    ),
                    ProductEntity(
                        name = "Royal Paithani Silk Saree (Gold Zari)",
                        sku = "MC-PS-4011",
                        sellingPrice = 18500.0,
                        costPrice = 13875.0,
                        categoryId = 1,
                        currentStock = 8,
                        trackInventory = true
                    ),
                    ProductEntity(
                        name = "Kanjeevaram Gold Dupatta",
                        sku = "MC-KD-004",
                        sellingPrice = 4000.0,
                        costPrice = 3000.0,
                        categoryId = 5,
                        currentStock = 20,
                        trackInventory = true
                    ),
                    ProductEntity(
                        name = "Chanderi Pure Cotton Saree",
                        sku = "MC-CC-102",
                        sellingPrice = 2850.0,
                        costPrice = 2100.0,
                        categoryId = 2,
                        currentStock = 25,
                        trackInventory = true
                    ),
                    ProductEntity(
                        name = "Temple Gold Finish Choker",
                        sku = "MC-JW-441",
                        sellingPrice = 4200.0,
                        costPrice = 3150.0,
                        categoryId = 6,
                        currentStock = 5,
                        trackInventory = true
                    )
                )
            )
        }
    }
}

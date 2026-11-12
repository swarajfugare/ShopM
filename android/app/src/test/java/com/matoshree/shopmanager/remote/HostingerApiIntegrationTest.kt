package com.matoshree.shopmanager.remote

import com.matoshree.shopmanager.data.remote.api.MatoshreeApiService
import com.matoshree.shopmanager.data.remote.dto.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Ignore

@Ignore("Requires live external Hostinger test server")
class HostingerApiIntegrationTest {

    private lateinit var apiService: MatoshreeApiService
    private val baseUrl = "https://blueviolet-ibis-158713.hostingersite.com/"

    @Before
    fun setup() {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        apiService = retrofit.create(MatoshreeApiService::class.java)
    }

    @Test
    fun testHealthEndpoint() = runBlocking {
        val response = apiService.checkHealth()
        assertTrue("Health response should be HTTP successful", response.isSuccessful)
        val body = response.body()
        assertNotNull("Body should not be null", body)
        assertEquals("status should be success", "success", body?.status)
    }

    @Test
    fun testDbStatusEndpoint() = runBlocking {
        val response = apiService.checkDbStatus()
        assertTrue("DB status response should be HTTP successful", response.isSuccessful)
        val body = response.body()
        assertNotNull("Body should not be null", body)
        assertEquals("status should be success", "success", body?.status)
    }

    @Test
    fun testLoginAndAuthFlow() = runBlocking {
        val loginResp = apiService.login(LoginRequest(mobile = "9876543210", pin = "1234"))
        assertTrue("Login should succeed", loginResp.isSuccessful)
        val loginData = loginResp.body()?.data
        assertNotNull("Login data should be present", loginData)
        assertFalse("JWT token should not be blank", loginData?.token.isNullOrBlank())
        assertEquals("User mobile should match", "+919876543210", loginData?.user?.mobile)
    }

    @Test
    fun testBatchSyncSaleIdempotency() = runBlocking {
        // 1. Authenticate
        val loginResp = apiService.login(LoginRequest(mobile = "9876543210", pin = "1234"))
        assertTrue(loginResp.isSuccessful)
        val token = loginResp.body()!!.data!!.token

        // 2. Create authenticated service
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        val authClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .build()
                chain.proceed(req)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()

        val authApiService = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(authClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MatoshreeApiService::class.java)

        // 3. Send Batch Sync with unique transaction UUID
        val txUuid = "test-sync-${UUID.randomUUID()}"
        val billNumber = "MC-2026-TEST-${System.currentTimeMillis().toString().takeLast(6)}"

        val payload = buildJsonObject {
            put("transaction_uuid", txUuid)
            put("bill_number", billNumber)
            put("sale_type", "QUICK")
            put("final_amount", 100.0)
            put("payment_method", "CASH")
            put("bill_date", "2026-08-30 14:25:00")
            put("note", "Automated Idempotency Test")
        }

        val syncRequest = BatchSyncRequest(
            device_id = "test_unit_runner",
            sync_items = listOf(
                SyncItemDto(
                    transaction_uuid = txUuid,
                    entity_type = "SALE",
                    operation = "CREATE",
                    payload = payload
                )
            )
        )

        // First sync -> SUCCESS
        val firstSync = authApiService.syncBatch(syncRequest)
        assertTrue("First sync should succeed", firstSync.isSuccessful)
        val firstResult = firstSync.body()?.data?.results?.firstOrNull()
        assertNotNull("First result should exist", firstResult)
        assertEquals("Status should be SUCCESS", "SUCCESS", firstResult?.status)
        assertNotNull("Server ID should be returned", firstResult?.server_id)

        // Second sync with SAME transaction_uuid -> DUPLICATE or SUCCESS (Idempotent)
        val secondSync = authApiService.syncBatch(syncRequest)
        assertTrue("Second sync should succeed", secondSync.isSuccessful)
        val secondResult = secondSync.body()?.data?.results?.firstOrNull()
        assertNotNull("Second result should exist", secondResult)
        assertTrue("Status should be DUPLICATE or SUCCESS", secondResult?.status == "DUPLICATE" || secondResult?.status == "SUCCESS")
        assertNotNull("Server ID should be returned", secondResult?.server_id)
    }

    @Test
    fun testRealtimeCrudEndpoints() = runBlocking {
        // 1. Authenticate
        val loginResp = apiService.login(LoginRequest(mobile = "9876543210", pin = "1234"))
        assertTrue(loginResp.isSuccessful)
        val token = loginResp.body()!!.data!!.token

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        val authClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .build()
                chain.proceed(req)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()

        val authApiService = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(authClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MatoshreeApiService::class.java)

        // 2. Test Customer Real-time Creation
        val testCustMobile = "+9198${(10000000..99999999).random()}"
        val custResp = authApiService.createCustomer(
            CreateCustomerRequest(
                name = "Integration Customer 001",
                mobile = testCustMobile,
                email = "integration@matoshree.com",
                address = "Kolhapur Textile Hub",
                notes = "Auto-test Customer"
            )
        )
        assertTrue("Create customer should succeed", custResp.isSuccessful)
        val createdCust = custResp.body()?.data?.customer
        assertNotNull("Customer data returned", createdCust)
        assertTrue("Customer ID > 0", (createdCust?.id ?: 0) > 0)
        assertEquals("Name matches", "Integration Customer 001", createdCust?.name)

        // 3. Test Category Real-time Creation
        val catResp = authApiService.createCategory(
            CreateCategoryRequest(
                name = "Integration Category 001",
                description = "Automated Test Category"
            )
        )
        assertTrue("Create category should succeed", catResp.isSuccessful)
        val createdCat = catResp.body()?.data?.category
        assertNotNull("Category data returned", createdCat)
        assertTrue("Category ID > 0", (createdCat?.id ?: 0) > 0)

        // 4. Test Product Real-time Creation
        val prodResp = authApiService.createProduct(
            CreateProductRequest(
                name = "Integration Product 001",
                sku = "INT-PROD-${System.currentTimeMillis().toString().takeLast(4)}",
                category_id = createdCat?.id,
                selling_price = 500.0,
                cost_price = 350.0,
                track_inventory = true,
                current_stock = 25
            )
        )
        assertTrue("Create product should succeed", prodResp.isSuccessful)
        val createdProd = prodResp.body()?.data?.product
        assertNotNull("Product data returned", createdProd)
        assertTrue("Product ID > 0", (createdProd?.id ?: 0) > 0)
        assertEquals("Price matches", 500.0, createdProd?.selling_price ?: 0.0, 0.01)

        // 5. Test Detailed Sale Real-time Creation
        val detailedTxUuid = "detailed-sale-${UUID.randomUUID()}"
        val detailedBillResp = authApiService.createSale(
            CreateSaleRequest(
                transaction_uuid = detailedTxUuid,
                sale_type = "DETAILED",
                customer_id = createdCust?.id,
                discount_amount = 0.0,
                final_amount = 500.0,
                payment_method = "UPI",
                note = "Real-time Detailed Sale Test",
                bill_date = "2026-08-30 14:40:00",
                device_id = "unit_test_device",
                items = listOf(
                    CreateSaleItemDto(
                        product_id = createdProd?.id,
                        name = createdProd?.name ?: "Integration Product 001",
                        quantity = 1,
                        selling_price = 500.0,
                        cost_price = 350.0
                    )
                )
            )
        )
        assertTrue("Detailed sale should succeed: code=${detailedBillResp.code()}, error=${detailedBillResp.errorBody()?.string()}", detailedBillResp.isSuccessful)
        val detailedBill = detailedBillResp.body()?.data?.bill
        assertNotNull("Detailed bill returned", detailedBill)
        assertTrue("Bill ID > 0", (detailedBill?.id ?: 0) > 0)
        assertEquals("Amount matches", 500.0, detailedBill?.final_amount ?: 0.0, 0.01)

        // 6. Test Quick Sale Real-time Creation
        val quickTxUuid = "quick-sale-${UUID.randomUUID()}"
        val quickBillResp = authApiService.createSale(
            CreateSaleRequest(
                transaction_uuid = quickTxUuid,
                sale_type = "QUICK",
                final_amount = 250.0,
                payment_method = "CASH",
                note = "Real-time Quick Sale Test",
                bill_date = "2026-08-30 14:42:00",
                device_id = "unit_test_device"
            )
        )
        assertTrue("Quick sale should succeed", quickBillResp.isSuccessful)
        val quickBill = quickBillResp.body()?.data?.bill
        assertNotNull("Quick bill returned", quickBill)
        assertTrue("Quick bill ID > 0", (quickBill?.id ?: 0) > 0)
        assertEquals("Quick bill amount matches", 250.0, quickBill?.final_amount ?: 0.0, 0.01)

        // 7. Test Expense Real-time Creation
        val expenseResp = authApiService.createExpense(
            CreateExpenseRequest(
                category = "TEA_REFRESHMENT",
                amount = 80.0,
                payment_method = "CASH",
                note = "Daily tea expense"
            )
        )
        assertTrue("Expense should succeed", expenseResp.isSuccessful)
        val createdExpense = expenseResp.body()?.data?.expense
        assertNotNull("Expense data returned", createdExpense)
        assertTrue("Expense ID > 0", (createdExpense?.id ?: 0) > 0)
    }
}

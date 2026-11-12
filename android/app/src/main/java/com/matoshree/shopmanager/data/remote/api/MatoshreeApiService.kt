package com.matoshree.shopmanager.data.remote.api

import com.matoshree.shopmanager.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface MatoshreeApiService {

    @GET("api/v1/health")
    suspend fun checkHealth(): Response<ApiResponse<Map<String, kotlinx.serialization.json.JsonElement>>>

    @GET("api/v1/db-status")
    suspend fun checkDbStatus(): Response<ApiResponse<Map<String, kotlinx.serialization.json.JsonElement>>>

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<ApiResponse<LoginResponseData>>

    @GET("api/v1/auth/me")
    suspend fun getMe(): Response<ApiResponse<UserDto>>

    @GET("api/v1/dashboard")
    suspend fun getDashboard(): Response<ApiResponse<Map<String, kotlinx.serialization.json.JsonElement>>>

    @POST("api/v1/sales")
    suspend fun createSale(@Body request: CreateSaleRequest): Response<ApiResponse<SaleResponseData>>

    @POST("api/v1/customers")
    suspend fun createCustomer(@Body request: CreateCustomerRequest): Response<ApiResponse<CustomerResponseData>>

    @POST("api/v1/products")
    suspend fun createProduct(@Body request: CreateProductRequest): Response<ApiResponse<ProductResponseData>>

    @POST("api/v1/categories")
    suspend fun createCategory(@Body request: CreateCategoryRequest): Response<ApiResponse<CategoryResponseData>>

    @POST("api/v1/expenses")
    suspend fun createExpense(@Body request: CreateExpenseRequest): Response<ApiResponse<ExpenseResponseData>>

    @GET("api/v1/bills")
    suspend fun getBills(
        @Query("filter") filter: String? = null,
        @Query("search") search: String? = null
    ): Response<ApiResponse<Map<String, List<kotlinx.serialization.json.JsonElement>>>>

    @POST("api/v1/bills/{id}/void")
    suspend fun voidBill(
        @Path("id") id: Long,
        @Body body: Map<String, String>
    ): Response<ApiResponse<Unit>>

    @GET("api/v1/customers")
    suspend fun getCustomers(
        @Query("search") search: String? = null
    ): Response<ApiResponse<Map<String, List<kotlinx.serialization.json.JsonElement>>>>

    @GET("api/v1/products")
    suspend fun getProducts(
        @Query("search") search: String? = null
    ): Response<ApiResponse<Map<String, List<kotlinx.serialization.json.JsonElement>>>>

    @POST("api/v1/sync")
    suspend fun syncBatch(@Body request: BatchSyncRequest): Response<ApiResponse<BatchSyncResponseData>>

    @GET("api/v1/sync/changes")
    suspend fun getSyncChanges(
        @Query("cursor") cursor: Long = 0,
        @Query("limit") limit: Int = 200,
        @Query("device_id") deviceId: String? = null
    ): Response<ApiResponse<SyncChangesData>>

    @POST("api/v1/auth/pin/change")
    suspend fun changePin(@Body request: ChangePinRequest): Response<ApiResponse<Map<String, kotlinx.serialization.json.JsonElement>>>

    @POST("api/v1/auth/pin/recover")
    suspend fun recoverPin(@Body request: RecoverPinRequest): Response<ApiResponse<Map<String, kotlinx.serialization.json.JsonElement>>>

    @POST("api/v1/customers/{id}/archive")
    suspend fun archiveCustomer(@Path("id") id: Long): Response<ApiResponse<Unit>>

    @POST("api/v1/products/{id}/archive")
    suspend fun archiveProduct(@Path("id") id: Long): Response<ApiResponse<Unit>>

    @PUT("api/v1/settings")
    suspend fun updateSettings(@Body request: UpdateSettingsRequest): Response<ApiResponse<Map<String, kotlinx.serialization.json.JsonElement>>>

    @POST("api/v1/devices/register")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): Response<ApiResponse<Map<String, String>>>

    @GET("api/v1/sync/diagnostics")
    suspend fun getDiagnostics(): Response<ApiResponse<SyncDiagnosticsData>>
}

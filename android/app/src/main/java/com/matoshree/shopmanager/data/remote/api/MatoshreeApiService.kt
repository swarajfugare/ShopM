package com.matoshree.shopmanager.data.remote.api

import com.matoshree.shopmanager.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface MatoshreeApiService {

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<ApiResponse<LoginResponseData>>

    @GET("api/v1/auth/me")
    suspend fun getMe(): Response<ApiResponse<UserDto>>

    @GET("api/v1/dashboard")
    suspend fun getDashboard(): Response<ApiResponse<Map<String, kotlinx.serialization.json.JsonElement>>>

    @POST("api/v1/sales")
    suspend fun createSale(@Body request: CreateSaleRequest): Response<ApiResponse<Map<String, kotlinx.serialization.json.JsonElement>>>

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
}

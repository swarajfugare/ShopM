package com.matoshree.shopmanager.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val status: String,
    val message: String? = null,
    val data: T? = null,
    val timestamp: Long? = null
)

@Serializable
data class LoginRequest(
    val mobile: String,
    val password: String? = null,
    val pin: String? = null
)

@Serializable
data class LoginResponseData(
    val token: String,
    val user: UserDto
)

@Serializable
data class UserDto(
    val id: Long,
    val shop_id: Long,
    val name: String,
    val mobile: String,
    val email: String? = null,
    val role: String,
    val shop_name: String,
    val currency: String = "INR",
    val gst_number: String? = null
)

@Serializable
data class CreateSaleItemDto(
    val product_id: Long? = null,
    val name: String,
    val sku: String? = null,
    val category_id: Long? = null,
    val quantity: Int,
    val selling_price: Double,
    val cost_price: Double? = null,
    val discount_amount: Double = 0.0
)

@Serializable
data class CreateSaleRequest(
    val transaction_uuid: String,
    val sale_type: String, // DETAILED, QUICK
    val customer_id: Long? = null,
    val discount_amount: Double = 0.0,
    val final_amount: Double? = null,
    val payment_method: String,
    val note: String? = null,
    val bill_date: String,
    val device_id: String? = null,
    val items: List<CreateSaleItemDto> = emptyList()
)

@Serializable
data class SyncItemDto(
    val transaction_uuid: String,
    val entity_type: String, // SALE, EXPENSE, CUSTOMER
    val operation: String = "CREATE",
    val payload: kotlinx.serialization.json.JsonObject
)

@Serializable
data class BatchSyncRequest(
    val device_id: String,
    val sync_items: List<SyncItemDto>
)

@Serializable
data class SyncResultItem(
    val transaction_uuid: String,
    val status: String, // SUCCESS, DUPLICATE, FAILED
    val server_id: Long? = null,
    val bill_number: String? = null,
    val error: String? = null
)

@Serializable
data class BatchSyncResponseData(
    val synced_at: String,
    val results: List<SyncResultItem>
)

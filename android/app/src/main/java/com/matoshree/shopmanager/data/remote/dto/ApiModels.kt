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

@Serializable
data class CreateCustomerRequest(
    val name: String,
    val mobile: String,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null
)

@Serializable
data class CustomerDto(
    val id: Long,
    val shop_id: Long = 1,
    val name: String,
    val mobile: String,
    val email: String? = null,
    val address: String? = null,
    val total_bills: Int = 0,
    val lifetime_spend: Double = 0.0,
    val tier: String = "REGULAR",
    val is_active: Int = 1
)

@Serializable
data class CustomerResponseData(
    val customer: CustomerDto,
    val is_existing: Boolean? = null
)

@Serializable
data class CreateProductRequest(
    val name: String,
    val sku: String? = null,
    val category_id: Long? = null,
    val selling_price: Double,
    val cost_price: Double? = null,
    val default_profit_margin: Double? = null,
    val track_inventory: Boolean = true,
    val current_stock: Int = 10
)

@Serializable
data class ProductDto(
    val id: Long,
    val shop_id: Long = 1,
    val category_id: Long? = null,
    val name: String,
    val sku: String? = null,
    val selling_price: Double,
    val cost_price: Double? = null,
    val current_stock: Int = 10,
    val is_active: Int = 1
)

@Serializable
data class ProductResponseData(
    val product: ProductDto
)

@Serializable
data class CreateCategoryRequest(
    val name: String,
    val description: String? = null
)

@Serializable
data class CategoryDto(
    val id: Long,
    val shop_id: Long = 1,
    val name: String,
    val description: String? = null
)

@Serializable
data class CategoryResponseData(
    val category: CategoryDto,
    val is_existing: Boolean? = null
)

@Serializable
data class CreateExpenseRequest(
    val category: String,
    val amount: Double,
    val payment_method: String = "CASH",
    val expense_date: String? = null,
    val note: String? = null
)

@Serializable
data class ExpenseDto(
    val id: Long,
    val shop_id: Long = 1,
    val category: String,
    val amount: Double,
    val payment_method: String,
    val expense_date: String,
    val note: String? = null
)

@Serializable
data class ExpenseResponseData(
    val expense: ExpenseDto
)

@Serializable
data class CreatedBillDto(
    val id: Long,
    val bill_number: String,
    val transaction_uuid: String,
    val customer_name: String? = null,
    val final_amount: Double,
    val estimated_profit: Double = 0.0,
    val payment_method: String,
    val payment_status: String = "PAID",
    val bill_date: String
)

@Serializable
data class SaleResponseData(
    val bill: CreatedBillDto
)

@Serializable
data class BillItemDto(
    val id: Long = 0,
    val bill_id: Long = 0,
    val product_id: Long? = null,
    val product_name_snapshot: String? = null,
    val sku_snapshot: String? = null,
    val quantity: Int = 1,
    val selling_price: Double = 0.0,
    val cost_price: Double? = null,
    val discount_amount: Double = 0.0,
    val line_total: Double = 0.0,
    val line_profit: Double = 0.0
)

@Serializable
data class PaymentDto(
    val id: Long = 0,
    val bill_id: Long = 0,
    val payment_method: String = "CASH",
    val amount: Double = 0.0,
    val payment_status: String = "PAID"
)

@Serializable
data class BillDetailDto(
    val id: Long,
    val shop_id: Long = 1,
    val customer_id: Long? = null,
    val customer_name: String? = null,
    val customer_mobile: String? = null,
    val bill_number: String,
    val transaction_uuid: String,
    val sale_type: String = "DETAILED",
    val subtotal: Double = 0.0,
    val discount_type: String = "NONE",
    val discount_value: Double = 0.0,
    val discount_amount: Double = 0.0,
    val final_amount: Double = 0.0,
    val cost_amount: Double = 0.0,
    val estimated_profit: Double = 0.0,
    val actual_profit: Double = 0.0,
    val profit_type: String = "ESTIMATED",
    val payment_method: String = "CASH",
    val payment_status: String = "PAID",
    val note: String? = null,
    val bill_date: String,
    val is_voided: Int = 0,
    val void_reason: String? = null,
    val shop_name_snapshot: String? = null,
    val shop_address_snapshot: String? = null,
    val shop_mobile_snapshot: String? = null,
    val shop_gstin_snapshot: String? = null,
    val show_gstin_snapshot: Int = 1,
    val items: List<BillItemDto> = emptyList(),
    val payments: List<PaymentDto> = emptyList()
)

@Serializable
data class DailyClosingDto(
    val id: Long = 0,
    val closing_date: String,
    val total_sales: Double = 0.0,
    val total_bills: Int = 0,
    val cash_sales: Double = 0.0,
    val upi_sales: Double = 0.0,
    val gross_profit: Double = 0.0,
    val expected_cash: Double = 0.0,
    val actual_cash: Double = 0.0,
    val cash_difference: Double = 0.0,
    val notes: String? = null,
    val is_closed: Int = 1
)

@Serializable
data class ShopSettingsDto(
    val id: Long = 1,
    val name: String? = null,
    val address: String? = null,
    val mobile: String? = null,
    val email: String? = null,
    val gst_number: String? = null,
    val show_gstin: Int = 1,
    val upi_id: String? = null,
    val upi_display_name: String? = null,
    val logo_url: String? = null,
    val logo_data: String? = null,
    val default_profit_margin: Double = 25.0
)

@Serializable
data class RegisterDeviceRequest(
    val device_id: String,
    val device_name: String = "Android Phone",
    val app_version: String = "1.0.0"
)

@Serializable
data class SyncTombstoneDto(
    val entity_type: String,
    val entity_id: Long,
    val operation: String
)

@Serializable
data class SyncChangesData(
    val cursor: Long = 0,
    val server_timestamp: Long = 0,
    val categories: List<CategoryDto> = emptyList(),
    val products: List<ProductDto> = emptyList(),
    val customers: List<CustomerDto> = emptyList(),
    val bills: List<BillDetailDto> = emptyList(),
    val expenses: List<ExpenseDto> = emptyList(),
    val daily_closings: List<DailyClosingDto> = emptyList(),
    val settings: ShopSettingsDto? = null,
    val deletions: List<SyncTombstoneDto> = emptyList()
)

@Serializable
data class ChangePinRequest(
    val current_pin: String,
    val new_pin: String
)

@Serializable
data class RecoverPinRequest(
    val recovery_code: String,
    val new_pin: String
)

@Serializable
data class UpdateSettingsRequest(
    val name: String? = null,
    val address: String? = null,
    val mobile: String? = null,
    val email: String? = null,
    val gstin: String? = null,
    val show_gstin: Boolean? = null,
    val upi_id: String? = null,
    val upi_display_name: String? = null,
    val logo_data: String? = null,
    val logo_url: String? = null,
    val default_profit_margin: Double? = null
)

@Serializable
data class DiagnosticCountsDto(
    val customers: Int = 0,
    val products: Int = 0,
    val bills: Int = 0,
    val expenses: Int = 0
)

@Serializable
data class SyncDiagnosticsData(
    val server_cursor: Long = 0,
    val server_timestamp: Long = 0,
    val counts: DiagnosticCountsDto = DiagnosticCountsDto()
)


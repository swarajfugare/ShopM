package com.matoshree.shopmanager.ui.navigation

sealed class Screen(val route: String) {
    object AppLock : Screen("app_lock")
    object Dashboard : Screen("dashboard")
    object NewSale : Screen("new_sale")
    object QuickSale : Screen("quick_sale")
    object SaleSummary : Screen("sale_summary")
    object BillPreview : Screen("bill_preview/{billId}") {
        fun createRoute(billId: Long) = "bill_preview/$billId"
    }
    object BillsHistory : Screen("bills_history")
    object Customers : Screen("customers")
    object CustomerProfile : Screen("customer_profile/{customerId}") {
        fun createRoute(customerId: Long) = "customer_profile/$customerId"
    }
    object Products : Screen("products")
    object AddProduct : Screen("add_product")
    object Expenses : Screen("expenses")
    object DailyClosing : Screen("daily_closing")
    object Analytics : Screen("analytics")
    object Settings : Screen("settings")
    object SyncStatus : Screen("sync_status")
}

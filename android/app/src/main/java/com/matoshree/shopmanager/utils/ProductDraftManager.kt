package com.matoshree.shopmanager.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages auto-saving and restoring draft details when adding a new product.
 * If the user accidentally leaves the popup (presses back / dismisses) without saving,
 * all entered data is securely remembered and automatically restored when they reopen.
 * Cleared only upon successful product save or explicit reset.
 */
object ProductDraftManager {

    private const val PREFS_NAME = "matoshree_product_draft_prefs"

    // Catalog Add Product Draft Keys
    private const val KEY_DRAFT_NAME = "draft_product_name"
    private const val KEY_DRAFT_SKU = "draft_product_sku"
    private const val KEY_DRAFT_PRICE = "draft_product_price"
    private const val KEY_DRAFT_COST_PRICE = "draft_product_cost_price"
    private const val KEY_DRAFT_CATEGORY_ID = "draft_product_category_id"
    private const val KEY_DRAFT_TRACK_INVENTORY = "draft_product_track_inventory"
    private const val KEY_DRAFT_STOCK_QTY = "draft_product_stock_qty"
    private const val KEY_DRAFT_EXISTS = "draft_product_exists"

    // Sale / Billing Quick Add Product Draft Keys
    private const val KEY_SALE_NAME = "draft_sale_product_name"
    private const val KEY_SALE_PRICE = "draft_sale_product_price"
    private const val KEY_SALE_COST = "draft_sale_product_cost"
    private const val KEY_SALE_SKU = "draft_sale_product_sku"
    private const val KEY_SALE_TRACK = "draft_sale_product_track"
    private const val KEY_SALE_EXISTS = "draft_sale_product_exists"

    data class ProductDraft(
        val name: String = "",
        val sku: String = "",
        val priceText: String = "",
        val costPriceText: String = "",
        val categoryId: Long? = null,
        val trackInventory: Boolean = true,
        val stockCountText: String = "5",
        val hasContent: Boolean = false
    )

    data class SaleProductDraft(
        val name: String = "",
        val priceText: String = "",
        val costText: String = "",
        val sku: String = "",
        val trackStock: Boolean = false,
        val hasContent: Boolean = false
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Main Catalog Add Product Draft ---

    fun saveDraft(
        context: Context,
        name: String,
        sku: String,
        priceText: String,
        costPriceText: String,
        categoryId: Long?,
        trackInventory: Boolean,
        stockCountText: String
    ) {
        val hasContent = name.isNotBlank() || priceText.isNotBlank() || costPriceText.isNotBlank()
        getPrefs(context).edit().apply {
            putString(KEY_DRAFT_NAME, name)
            putString(KEY_DRAFT_SKU, sku)
            putString(KEY_DRAFT_PRICE, priceText)
            putString(KEY_DRAFT_COST_PRICE, costPriceText)
            if (categoryId != null) {
                putLong(KEY_DRAFT_CATEGORY_ID, categoryId)
            } else {
                remove(KEY_DRAFT_CATEGORY_ID)
            }
            putBoolean(KEY_DRAFT_TRACK_INVENTORY, trackInventory)
            putString(KEY_DRAFT_STOCK_QTY, stockCountText)
            putBoolean(KEY_DRAFT_EXISTS, hasContent)
            apply()
        }
    }

    fun loadDraft(context: Context): ProductDraft {
        val prefs = getPrefs(context)
        val hasExists = prefs.getBoolean(KEY_DRAFT_EXISTS, false)
        val name = prefs.getString(KEY_DRAFT_NAME, "") ?: ""
        val sku = prefs.getString(KEY_DRAFT_SKU, "") ?: ""
        val priceText = prefs.getString(KEY_DRAFT_PRICE, "") ?: ""
        val costPriceText = prefs.getString(KEY_DRAFT_COST_PRICE, "") ?: ""
        val categoryId = if (prefs.contains(KEY_DRAFT_CATEGORY_ID)) prefs.getLong(KEY_DRAFT_CATEGORY_ID, -1L).takeIf { it != -1L } else null
        val trackInventory = prefs.getBoolean(KEY_DRAFT_TRACK_INVENTORY, true)
        val stockCountText = prefs.getString(KEY_DRAFT_STOCK_QTY, "5") ?: "5"

        val hasContent = hasExists || name.isNotBlank() || priceText.isNotBlank() || costPriceText.isNotBlank()

        return ProductDraft(
            name = name,
            sku = sku,
            priceText = priceText,
            costPriceText = costPriceText,
            categoryId = categoryId,
            trackInventory = trackInventory,
            stockCountText = stockCountText,
            hasContent = hasContent
        )
    }

    fun clearDraft(context: Context) {
        getPrefs(context).edit().apply {
            remove(KEY_DRAFT_NAME)
            remove(KEY_DRAFT_SKU)
            remove(KEY_DRAFT_PRICE)
            remove(KEY_DRAFT_COST_PRICE)
            remove(KEY_DRAFT_CATEGORY_ID)
            remove(KEY_DRAFT_TRACK_INVENTORY)
            remove(KEY_DRAFT_STOCK_QTY)
            putBoolean(KEY_DRAFT_EXISTS, false)
            apply()
        }
    }

    // --- Sale Quick Add Product Draft ---

    fun saveSaleDraft(
        context: Context,
        name: String,
        priceText: String,
        costText: String,
        sku: String,
        trackStock: Boolean
    ) {
        val hasContent = name.isNotBlank() || priceText.isNotBlank() || costText.isNotBlank()
        getPrefs(context).edit().apply {
            putString(KEY_SALE_NAME, name)
            putString(KEY_SALE_PRICE, priceText)
            putString(KEY_SALE_COST, costText)
            putString(KEY_SALE_SKU, sku)
            putBoolean(KEY_SALE_TRACK, trackStock)
            putBoolean(KEY_SALE_EXISTS, hasContent)
            apply()
        }
    }

    fun loadSaleDraft(context: Context): SaleProductDraft {
        val prefs = getPrefs(context)
        val hasExists = prefs.getBoolean(KEY_SALE_EXISTS, false)
        val name = prefs.getString(KEY_SALE_NAME, "") ?: ""
        val priceText = prefs.getString(KEY_SALE_PRICE, "") ?: ""
        val costText = prefs.getString(KEY_SALE_COST, "") ?: ""
        val sku = prefs.getString(KEY_SALE_SKU, "") ?: ""
        val trackStock = prefs.getBoolean(KEY_SALE_TRACK, false)

        val hasContent = hasExists || name.isNotBlank() || priceText.isNotBlank() || costText.isNotBlank()

        return SaleProductDraft(
            name = name,
            priceText = priceText,
            costText = costText,
            sku = sku,
            trackStock = trackStock,
            hasContent = hasContent
        )
    }

    fun clearSaleDraft(context: Context) {
        getPrefs(context).edit().apply {
            remove(KEY_SALE_NAME)
            remove(KEY_SALE_PRICE)
            remove(KEY_SALE_COST)
            remove(KEY_SALE_SKU)
            remove(KEY_SALE_TRACK)
            putBoolean(KEY_SALE_EXISTS, false)
            apply()
        }
    }
}

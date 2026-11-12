package com.matoshree.shopmanager.printer

import android.content.Context
import com.matoshree.shopmanager.domain.model.Product
import com.matoshree.shopmanager.utils.PrintShareHelper
import java.io.ByteArrayOutputStream

class ProductLabelPrinterManager(private val context: Context) {

    val bluetoothManager = BluetoothPrinterManager(context)
    private val prefs = context.getSharedPreferences("matoshree_printer_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LABEL_SIZE = "label_size_config"
        private const val KEY_GAP_OFFSET = "label_gap_offset_dots"
        private const val KEY_VERTICAL_OFFSET = "label_vertical_offset_dots"
        private const val KEY_PROTOCOL = "label_printer_protocol"
    }

    /**
     * Retrieves the saved label size config (default 50x38mm for HOIN HOP-H58).
     */
    fun getSavedLabelSize(): LabelSizeConfig {
        val name = prefs.getString(KEY_LABEL_SIZE, LabelSizeConfig.SIZE_50X38.name)
        return try {
            LabelSizeConfig.valueOf(name ?: LabelSizeConfig.SIZE_50X38.name)
        } catch (_: Exception) {
            LabelSizeConfig.SIZE_50X38
        }
    }

    fun saveLabelSize(size: LabelSizeConfig) {
        prefs.edit().putString(KEY_LABEL_SIZE, size.name).apply()
    }

    /**
     * Retrieves fine-tuned gap offset in dots (e.g. -24 to +24 dots, ±3 mm).
     */
    fun getSavedGapOffset(): Int {
        return prefs.getInt(KEY_GAP_OFFSET, 0)
    }

    fun saveGapOffset(offsetDots: Int) {
        prefs.edit().putInt(KEY_GAP_OFFSET, offsetDots).apply()
    }

    /**
     * Retrieves fine-tuned vertical offset in dots (e.g. -24 to +24 dots, ±3 mm).
     */
    fun getSavedVerticalOffset(): Int {
        return prefs.getInt(KEY_VERTICAL_OFFSET, 0)
    }

    fun saveVerticalOffset(offsetDots: Int) {
        prefs.edit().putInt(KEY_VERTICAL_OFFSET, offsetDots).apply()
    }

    /**
     * Retrieves the printer protocol (default ESCPOS_HOIN for HOIN HOP-H58).
     */
    fun getSavedProtocol(): LabelPrinterProtocol {
        val name = prefs.getString(KEY_PROTOCOL, LabelPrinterProtocol.ESCPOS_HOIN.name)
        return try {
            LabelPrinterProtocol.valueOf(name ?: LabelPrinterProtocol.ESCPOS_HOIN.name)
        } catch (_: Exception) {
            LabelPrinterProtocol.ESCPOS_HOIN
        }
    }

    fun saveProtocol(protocol: LabelPrinterProtocol) {
        prefs.edit().putString(KEY_PROTOCOL, protocol.name).apply()
    }

    /**
     * Prints a single product label with the calibrated size, protocol, gap feed, and vertical position.
     */
    suspend fun printProductLabel(
        product: Product,
        quantity: Int = 1,
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot()
    ): Result<Unit> {
        val selectedPrinter = bluetoothManager.getSelectedPrinter()
            ?: return Result.failure(IllegalStateException("कृपया सेटिंगमध्ये ५८ मिमी थर्मल प्रिंटर सिलेक्ट करा."))

        val size = getSavedLabelSize()
        val gapOffset = getSavedGapOffset()
        val verticalOffset = getSavedVerticalOffset()
        val protocol = getSavedProtocol()

        val labelBytes = if (protocol == LabelPrinterProtocol.TSPL) {
            ProductLabelFormatter.formatTsplProductLabel(
                product = product,
                quantity = quantity,
                settings = settings,
                labelSize = size,
                gapOffsetDots = gapOffset,
                verticalOffsetDots = verticalOffset
            )
        } else {
            ProductLabelFormatter.formatEscPosProductLabel(
                product = product,
                quantity = quantity,
                settings = settings,
                labelSize = size,
                gapOffsetDots = gapOffset,
                verticalOffsetDots = verticalOffset
            )
        }

        return bluetoothManager.sendBytes(labelBytes)
    }

    /**
     * Bulk prints labels for multiple products with their respective quantities.
     */
    suspend fun printBulkProductLabels(
        items: List<Pair<Product, Int>>,
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot()
    ): Result<Unit> {
        val selectedPrinter = bluetoothManager.getSelectedPrinter()
            ?: return Result.failure(IllegalStateException("कृपया सेटिंगमध्ये ५८ मिमी थर्मल प्रिंटर सिलेक्ट करा."))

        if (items.isEmpty()) {
            return Result.failure(IllegalArgumentException("प्रिंट करण्यासाठी प्रॉडक्ट्स सिलेक्ट करा."))
        }

        val size = getSavedLabelSize()
        val gapOffset = getSavedGapOffset()
        val verticalOffset = getSavedVerticalOffset()
        val protocol = getSavedProtocol()

        val out = ByteArrayOutputStream()
        for ((prod, qty) in items) {
            val bytes = if (protocol == LabelPrinterProtocol.TSPL) {
                ProductLabelFormatter.formatTsplProductLabel(
                    product = prod,
                    quantity = qty,
                    settings = settings,
                    labelSize = size,
                    gapOffsetDots = gapOffset,
                    verticalOffsetDots = verticalOffset
                )
            } else {
                ProductLabelFormatter.formatEscPosProductLabel(
                    product = prod,
                    quantity = qty,
                    settings = settings,
                    labelSize = size,
                    gapOffsetDots = gapOffset,
                    verticalOffsetDots = verticalOffset
                )
            }
            out.write(bytes)
        }

        return bluetoothManager.sendBytes(out.toByteArray())
    }

    /**
     * Prints a sample test sticker to verify crisp layout and gap feed alignment.
     */
    suspend fun printTestLabel(
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot()
    ): Result<Unit> {
        val selectedPrinter = bluetoothManager.getSelectedPrinter()
            ?: return Result.failure(IllegalStateException("कृपया सेटिंगमध्ये ५८ मिमी थर्मल प्रिंटर सिलेक्ट करा."))

        val size = getSavedLabelSize()
        val gapOffset = getSavedGapOffset()
        val verticalOffset = getSavedVerticalOffset()
        val protocol = getSavedProtocol()

        val bytes = ProductLabelFormatter.formatTestLabel(
            settings = settings,
            labelSize = size,
            gapOffsetDots = gapOffset,
            verticalOffsetDots = verticalOffset,
            protocol = protocol
        )
        return bluetoothManager.sendBytes(bytes)
    }
}

package com.matoshree.shopmanager.printer

import android.graphics.*
import com.matoshree.shopmanager.domain.model.Product
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.PrintShareHelper
import java.io.ByteArrayOutputStream

/**
 * Pre-calibrated thermal label sizes for retail apparel and jewellery stickers.
 *
 * Calibrated specifically for 58mm thermal printers like HOIN HOP-H58 (203 DPI, 8 dots/mm, 384 max dots width).
 *
 * Note on receipt printers without auto-gap sensors:
 * Total pitch is the physical repeat distance from the top of one sticker to the top of the next
 * (Sticker Height + Physical Die-Cut Gap). By streaming the exact total pitch as a unified raster,
 * the stepper motor advances with 100% hardware precision without relying on gap sensors or ESC J interpretation.
 */
enum class LabelSizeConfig(
    val title: String,
    val widthMm: Int,
    val heightMm: Int,
    val gapMm: Double,
    val totalWidthDots: Int = 384, // 48 bytes across
    val stickerHeightDots: Int,    // Printable content height
    val totalPitchDots: Int        // Full repeat distance (Content + Gap advance = 41.0mm)
) {
    SIZE_50X38(
        title = "५० x ३८ मिमी (२\" x १.५\" स्टँडर्ड कापड स्टिकर)",
        widthMm = 50,
        heightMm = 38,
        gapMm = 3.5,
        totalWidthDots = 384,
        stickerHeightDots = 288, // 36mm printable height inside 38mm sticker
        totalPitchDots = 332    // 38mm + 3.5mm gap = 41.5 mm (332 dots at 203 DPI - calibrated midpoint)
    ),
    SIZE_55X43(
        title = "५५ x ४३ मिमी (लार्ज स्टिकर रोल)",
        widthMm = 55,
        heightMm = 43,
        gapMm = 3.0,
        totalWidthDots = 384,
        stickerHeightDots = 328, // 41mm printable height inside 43mm sticker
        totalPitchDots = 368    // 43mm + 3mm gap = 46.0 mm (368 dots)
    ),
    SIZE_50X30(
        title = "५० x ३० मिमी (कॉम्पॅक्ट स्टिकर)",
        widthMm = 50,
        heightMm = 30,
        gapMm = 3.0,
        totalWidthDots = 384,
        stickerHeightDots = 216, // Printable area inside 30mm sticker
        totalPitchDots = 264    // 30mm + 3mm gap = 33.0 mm (264 dots)
    ),
    SIZE_50X25(
        title = "५० x २५ मिमी (स्मॉल टॅग)",
        widthMm = 50,
        heightMm = 25,
        gapMm = 3.0,
        totalWidthDots = 384,
        stickerHeightDots = 176, // Printable area inside 25mm sticker
        totalPitchDots = 224    // 25mm + 3mm gap = 28.0 mm (224 dots)
    )
}

enum class LabelPrinterProtocol(val title: String) {
    ESCPOS_HOIN("HOIN / ESC-POS मोड (HOP-H58 प्रिंटरसाठी)"),
    TSPL("TSPL बारकोड प्रिंटर मोड (ऑटो गॅप सेन्सर)")
}

object ProductLabelFormatter {

    /**
     * Generates a high-contrast, retail-ready Product Label Bitmap dynamically scaled
     * to fit the exact physical sticker dimensions (e.g. 50mm x 38mm, 55mm x 43mm, 50mm x 30mm).
     *
     * Everything (Shop Name, Village, Product Name, Code 128 Barcode, SKU, MRP, Contact)
     * is placed with mathematical precision so that the entire sticker is utilized without blank bottom space.
     */
    fun generateProductLabelBitmap(
        product: Product,
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot(),
        width: Int = 384,
        height: Int = 288
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background Pure White
        canvas.drawColor(Color.WHITE)

        val borderPaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2.4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val dividerPaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        // Draw Outer Rounded Border padded cleanly from sticker physical edges
        val borderRect = if (height in 236..300) {
            RectF(8f, 12f, width - 8f, height - 12f) // Centered with 12 dots (~1.5mm) top/bottom margin for 50x38mm
        } else {
            RectF(6f, 6f, width - 6f, height - 6f)
        }
        canvas.drawRoundRect(borderRect, 12f, 12f, borderPaint)

        val shopHeader = settings.shopName.ifBlank { "मातोश्री कलेक्शन" }
        val village = settings.address.ifBlank { "कोंढारकी" }.split(",").firstOrNull()?.trim() ?: "कोंढारकी"
        val skuVal = product.sku?.ifBlank { "MC-0000" } ?: "MC-0000"
        val cleanMobile = settings.mobile.filter { it.isDigit() }.takeLast(10)
        val contactMobile = if (cleanMobile.length == 10) cleanMobile else "8208610261"
        val mrpFormatted = CurrencyFormatter.format(product.sellingPrice)

        if (height <= 185) {
            // Compact 50x25mm Layout (height ~ 176 dots)
            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(shopHeader, width / 2f, 22f, titlePaint)

            val namePaint = Paint().apply {
                color = Color.BLACK
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val dispName = if (product.name.length > 20) product.name.take(18) + "…" else product.name
            canvas.drawText("$dispName • $village", width / 2f, 40f, namePaint)

            // Barcode
            val barcodeY = 46f
            val barcodeHeight = 34
            try {
                val barcodeBmp = BarcodeGenerator.generateCode128BarcodeBitmap(
                    contents = skuVal,
                    targetWidth = 250,
                    targetHeight = barcodeHeight
                )
                val barcodeX = (width - barcodeBmp.width) / 2f
                canvas.drawBitmap(barcodeBmp, barcodeX, barcodeY, null)
            } catch (_: Exception) {
                canvas.drawLine(30f, barcodeY + 17f, width - 30f, barcodeY + 17f, borderPaint)
            }

            val skuPaint = Paint().apply {
                color = Color.BLACK
                textSize = 12f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(skuVal, width / 2f, 94f, skuPaint)

            canvas.drawLine(14f, 102f, width - 14f, 102f, dividerPaint)

            val mrpPaint = Paint().apply {
                color = Color.BLACK
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("MRP : $mrpFormatted", 16f, 132f, mrpPaint)

            val contactPaint = Paint().apply {
                color = Color.BLACK
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("संपर्क : $contactMobile", 16f, 156f, contactPaint)

        } else if (height <= 235) {
            // Medium 50x30mm Layout (height ~ 216 dots)
            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(shopHeader, width / 2f, 26f, titlePaint)

            val subPaint = Paint().apply {
                color = Color.BLACK
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(village, width / 2f, 46f, subPaint)

            val namePaint = Paint().apply {
                color = Color.BLACK
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val dispName = if (product.name.length > 24) product.name.take(22) + "…" else product.name
            canvas.drawText(dispName, width / 2f, 68f, namePaint)

            // Barcode
            val barcodeY = 76f
            val barcodeHeight = 40
            try {
                val barcodeBmp = BarcodeGenerator.generateCode128BarcodeBitmap(
                    contents = skuVal,
                    targetWidth = 270,
                    targetHeight = barcodeHeight
                )
                val barcodeX = (width - barcodeBmp.width) / 2f
                canvas.drawBitmap(barcodeBmp, barcodeX, barcodeY, null)
            } catch (_: Exception) {
                canvas.drawLine(30f, barcodeY + 20f, width - 30f, barcodeY + 20f, borderPaint)
            }

            val skuPaint = Paint().apply {
                color = Color.BLACK
                textSize = 13f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(skuVal, width / 2f, 132f, skuPaint)

            canvas.drawLine(14f, 142f, width - 14f, 142f, dividerPaint)

            val mrpPaint = Paint().apply {
                color = Color.BLACK
                textSize = 23f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("MRP : $mrpFormatted", 18f, 172f, mrpPaint)

            val contactPaint = Paint().apply {
                color = Color.BLACK
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("संपर्क : $contactMobile", 18f, 198f, contactPaint)

        } else if (height <= 300) {
            // Standard 50x38mm (2" x 1.5") Layout (height = 288 dots) - Centered to eliminate 1-2mm upward shift
            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 27f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(shopHeader, width / 2f, 38f, titlePaint)

            val subPaint = Paint().apply {
                color = Color.BLACK
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(village, width / 2f, 62f, subPaint)

            val namePaint = Paint().apply {
                color = Color.BLACK
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val dispName = if (product.name.length > 24) product.name.take(22) + "…" else product.name
            canvas.drawText(dispName, width / 2f, 88f, namePaint)

            // Barcode (Tall, 48 dots high, centered)
            val barcodeY = 102f
            val barcodeHeight = 48
            try {
                val barcodeBmp = BarcodeGenerator.generateCode128BarcodeBitmap(
                    contents = skuVal,
                    targetWidth = 280,
                    targetHeight = barcodeHeight
                )
                val barcodeX = (width - barcodeBmp.width) / 2f
                canvas.drawBitmap(barcodeBmp, barcodeX, barcodeY, null)
            } catch (_: Exception) {
                canvas.drawLine(30f, barcodeY + 24f, width - 30f, barcodeY + 24f, borderPaint)
            }

            val skuTextPaint = Paint().apply {
                color = Color.BLACK
                textSize = 15f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(skuVal, width / 2f, 170f, skuTextPaint)

            canvas.drawLine(16f, 182f, width - 16f, 182f, dividerPaint)

            val mrpPaint = Paint().apply {
                color = Color.BLACK
                textSize = 32f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("MRP : $mrpFormatted", 22f, 224f, mrpPaint)

            val contactPaint = Paint().apply {
                color = Color.BLACK
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("संपर्क : $contactMobile", 22f, 260f, contactPaint)

        } else {
            // Large 55x43mm Layout (height ~ 328 dots)
            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 29f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(shopHeader, width / 2f, 36f, titlePaint)

            val subPaint = Paint().apply {
                color = Color.BLACK
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(village, width / 2f, 62f, subPaint)

            val namePaint = Paint().apply {
                color = Color.BLACK
                textSize = 23f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val dispName = if (product.name.length > 24) product.name.take(22) + "…" else product.name
            canvas.drawText(dispName, width / 2f, 92f, namePaint)

            // Barcode
            val barcodeY = 108f
            val barcodeHeight = 58
            try {
                val barcodeBmp = BarcodeGenerator.generateCode128BarcodeBitmap(
                    contents = skuVal,
                    targetWidth = 290,
                    targetHeight = barcodeHeight
                )
                val barcodeX = (width - barcodeBmp.width) / 2f
                canvas.drawBitmap(barcodeBmp, barcodeX, barcodeY, null)
            } catch (_: Exception) {
                canvas.drawLine(30f, barcodeY + 29f, width - 30f, barcodeY + 29f, borderPaint)
            }

            val barcodeTextPaint = Paint().apply {
                color = Color.BLACK
                textSize = 16f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(skuVal, width / 2f, 186f, barcodeTextPaint)

            canvas.drawLine(16f, 200f, width - 16f, 200f, dividerPaint)

            val mrpPaint = Paint().apply {
                color = Color.BLACK
                textSize = 34f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("MRP : $mrpFormatted", 22f, 252f, mrpPaint)

            val contactPaint = Paint().apply {
                color = Color.BLACK
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("संपर्क : $contactMobile", 22f, 296f, contactPaint)
        }

        return bitmap
    }

    /**
     * Builds ESC/POS thermal command stream for printing product labels on receipt printers like HOIN HOP-H58.
     *
     * Master Fix for Printers Without Auto-Gap Sensors:
     * By combining the printable label content and the trailing physical gap into ONE UNIFIED RASTER STREAM
     * matching the exact roll pitch (41.0mm = 328 dots), the printer's stepper motor steps line-by-line
     * with 100% mechanical repeatability.
     *
     * No firmware interpretation error, no ESC J calculation drift, and no cumulative misalignment.
     */
    fun formatEscPosProductLabel(
        product: Product,
        quantity: Int = 1,
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot(),
        labelSize: LabelSizeConfig = LabelSizeConfig.SIZE_50X38,
        gapOffsetDots: Int = 0,
        verticalOffsetDots: Int = 0
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val widthDots = labelSize.totalWidthDots // 384 dots (48 bytes across)
        val contentHeightDots = labelSize.stickerHeightDots // e.g. 288 dots for 50x38mm
        val pitchDots = (labelSize.totalPitchDots + gapOffsetDots).coerceIn(contentHeightDots + 8, 480)

        // Master Vertical Centering:
        // Physical 50x38 sticker is 304 dots high (38mm). Content bitmap is 288 dots (36mm).
        // Drawing content with baseTopOffset of 4 dots (~0.5mm) perfectly centers the printed border and text,
        // avoiding both upward clipping and downward overrun.
        val baseTopOffset = when (labelSize) {
            LabelSizeConfig.SIZE_50X38 -> 4
            LabelSizeConfig.SIZE_55X43 -> 6
            LabelSizeConfig.SIZE_50X30 -> 3
            LabelSizeConfig.SIZE_50X25 -> 2
        }
        val maxTopOffset = (pitchDots - contentHeightDots).coerceAtLeast(0)
        val effectiveTopOffset = (baseTopOffset + verticalOffsetDots).coerceIn(0, maxTopOffset)

        // 1. Create full-pitch bitmap: top margin, centered content, white gap advance at the bottom
        val fullPitchBitmap = Bitmap.createBitmap(widthDots, pitchDots, Bitmap.Config.ARGB_8888)
        val pitchCanvas = Canvas(fullPitchBitmap)
        pitchCanvas.drawColor(Color.WHITE)

        val contentBitmap = generateProductLabelBitmap(
            product = product,
            settings = settings,
            width = widthDots,
            height = contentHeightDots
        )
        pitchCanvas.drawBitmap(contentBitmap, 0f, effectiveTopOffset.toFloat(), null)

        val bytesWidth = widthDots / 8 // 48 bytes
        val rasterData = ByteArray(bytesWidth * pitchDots)
        var idx = 0

        for (y in 0 until pitchDots) {
            for (byteIdx in 0 until bytesWidth) {
                var currentByte = 0
                for (bit in 0 until 8) {
                    val x = byteIdx * 8 + bit
                    val pixel = fullPitchBitmap.getPixel(x, y)
                    val alpha = Color.alpha(pixel)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    // 1 = Black dot in thermal burning
                    if (alpha > 30 && luminance < 205) {
                        currentByte = currentByte or (1 shl (7 - bit))
                    }
                }
                rasterData[idx++] = currentByte.toByte()
            }
        }

        // 2. Initial ESC/POS Setup (Once per transmission)
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @ (Initialize)
        out.write(byteArrayOf(0x1B, 0x33, 0x00)) // ESC 3 0 (Zero line spacing)

        // 3. Stream each sticker with exact dot pitch
        for (i in 0 until quantity.coerceAtLeast(1)) {
            // Unified GS v 0 0 xL xH yL yH for the complete pitch (content + physical gap)
            out.write(0x1D)
            out.write(0x76)
            out.write(0x30)
            out.write(0x00) // Mode 0 (Normal)
            out.write(bytesWidth % 256)
            out.write(bytesWidth / 256)
            out.write(pitchDots % 256)
            out.write(pitchDots / 256)
            out.write(rasterData)
        }

        // 4. Restore default line spacing
        out.write(byteArrayOf(0x1B, 0x32))

        return out.toByteArray()
    }

    /**
     * Builds TSPL command stream for dedicated Barcode / Label printers (e.g. Xprinter, TVS, TSC).
     * Uses automatic hardware gap detection.
     */
    fun formatTsplProductLabel(
        product: Product,
        quantity: Int = 1,
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot(),
        labelSize: LabelSizeConfig = LabelSizeConfig.SIZE_50X38,
        gapOffsetDots: Int = 0,
        verticalOffsetDots: Int = 0
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val widthDots = labelSize.totalWidthDots
        val heightDots = labelSize.stickerHeightDots
        val labelBitmap = generateProductLabelBitmap(
            product = product,
            settings = settings,
            width = widthDots,
            height = heightDots
        )

        val widthBytes = widthDots / 8
        val gapMm = (labelSize.gapMm + (gapOffsetDots / 8.0)).coerceAtLeast(0.0)
        val baseTopOffset = when (labelSize) {
            LabelSizeConfig.SIZE_50X38 -> 4
            LabelSizeConfig.SIZE_55X43 -> 6
            LabelSizeConfig.SIZE_50X30 -> 3
            LabelSizeConfig.SIZE_50X25 -> 2
        }
        val effectiveTopOffset = (baseTopOffset + verticalOffsetDots).coerceAtLeast(0)

        val header = "SIZE ${labelSize.widthMm} mm, ${labelSize.heightMm} mm\r\n" +
                "GAP ${String.format(java.util.Locale.US, "%.1f", gapMm)} mm, 0 mm\r\n" +
                "DIRECTION 1\r\n" +
                "CLS\r\n" +
                "BITMAP 0,$effectiveTopOffset,$widthBytes,$heightDots,0,"
        out.write(header.toByteArray(Charsets.US_ASCII))

        for (y in 0 until heightDots) {
            for (byteIdx in 0 until widthBytes) {
                var currentByte = 0
                for (bit in 0 until 8) {
                    val x = byteIdx * 8 + bit
                    val pixel = labelBitmap.getPixel(x, y)
                    val alpha = Color.alpha(pixel)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    if (alpha > 30 && luminance < 205) {
                        currentByte = currentByte or (1 shl (7 - bit))
                    }
                }
                out.write(currentByte)
            }
        }

        val footer = "\r\nPRINT $quantity,1\r\n"
        out.write(footer.toByteArray(Charsets.US_ASCII))

        return out.toByteArray()
    }

    /**
     * Generates a sample Test Label to test alignment, gap calibration, and crisp printing.
     */
    fun formatTestLabel(
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot(),
        labelSize: LabelSizeConfig = LabelSizeConfig.SIZE_50X38,
        gapOffsetDots: Int = 0,
        verticalOffsetDots: Int = 0,
        protocol: LabelPrinterProtocol = LabelPrinterProtocol.ESCPOS_HOIN
    ): ByteArray {
        val sampleProduct = Product(
            id = 99999,
            name = "पूर्णिमा साडी (चाचणी)",
            sellingPrice = 700.0,
            costPrice = 450.0,
            sku = "21216",
            isActive = true
        )
        return if (protocol == LabelPrinterProtocol.TSPL) {
            formatTsplProductLabel(sampleProduct, 1, settings, labelSize, gapOffsetDots, verticalOffsetDots)
        } else {
            formatEscPosProductLabel(sampleProduct, 1, settings, labelSize, gapOffsetDots, verticalOffsetDots)
        }
    }
}

package com.matoshree.shopmanager.printer

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.oned.Code128Writer
import java.util.EnumMap

object BarcodeGenerator {

    /**
     * Generates a crisp, scannable CODE 128 barcode Bitmap for product SKU / label.
     */
    fun generateCode128BarcodeBitmap(
        contents: String,
        targetWidth: Int = 400,
        targetHeight: Int = 120
    ): Bitmap {
        val safeContents = contents.trim().ifBlank { "MC-0000" }
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.MARGIN, 2)
        }

        val writer = Code128Writer()
        val bitMatrix = writer.encode(
            safeContents,
            BarcodeFormat.CODE_128,
            targetWidth,
            targetHeight,
            hints
        )

        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}

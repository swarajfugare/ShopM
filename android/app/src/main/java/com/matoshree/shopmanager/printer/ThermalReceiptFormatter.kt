package com.matoshree.shopmanager.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.domain.model.Customer
import com.matoshree.shopmanager.domain.model.DailyClosing
import com.matoshree.shopmanager.domain.model.DiscountType
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.domain.model.PaymentStatus
import com.matoshree.shopmanager.domain.model.UdhariTransaction
import com.matoshree.shopmanager.domain.model.UdhariTransactionType
import com.matoshree.shopmanager.ui.screens.analytics.TopProductStat
import com.matoshree.shopmanager.utils.PrintShareHelper
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.HashMap

object ThermalReceiptFormatter {

    const val MAX_PAPER_DOTS = 384 // 58mm standard printable dots (48mm @ 203 DPI)
    const val CHARS_PER_LINE = 32  // Standard Font A width on 58mm
    private const val SLICE_HEIGHT = 64 // 64 rows per slice: perfectly balanced for thermal buffer & zero motor slip
    private val CP437 = Charset.forName("CP437")

    /**
     * Builds complete monochrome ESC/POS byte payload for a Bill on 58mm thermal paper in pure Marathi-first format.
     * Uses a high-contrast, razor-sharp 384-dot monochrome bitmap so that all Marathi Unicode typography,
     * tables, borders, QR codes, and signatures print with 100% clarity without any printer charset limitations.
     */
    fun formatBillReceipt(
        context: Context,
        bill: Bill,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. Initialize & Reset printer buffer
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @
        // Set maximum heating density for dark & sharp black thermal burn
        out.write(byteArrayOf(0x1B, 0x37, 0x07, 0xF0.toByte(), 0x02))
        out.write(byteArrayOf(0x1B, 0x32)) // ESC 2 (Default line spacing)

        // 2. Render and stream the complete high-contrast thermal bill bitmap
        val receiptBitmap = renderThermalBillBitmap(context, bill, settings)
        writeSlicedMonochromeImage(out, receiptBitmap, targetWidth = MAX_PAPER_DOTS)

        // 3. Feed & Cut
        out.write(byteArrayOf(0x1B, 0x64, 0x04)) // Feed 4 lines
        out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x00)) // GS V A 0 (Partial Cut)

        return out.toByteArray()
    }

    /**
     * Renders the complete Bill as a professional 384-dot monochrome bitmap for 58mm thermal printers.
     * Mathematically spaced with clean container bounds and Devanagari line heights to guarantee
     * that headings, table rows, totals, QR codes, and signatures never overlap.
     */
    fun renderThermalBillBitmap(
        context: Context,
        bill: Bill,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): Bitmap {
        val width = MAX_PAPER_DOTS // 384
        val hasDiscount = bill.discountAmount > 0.01
        val hasPending = bill.pendingAmount > 0.01
        val shouldShowQr = (bill.paymentMethod == PaymentMethod.UPI && bill.paidAmount > 0) || hasPending
        val itemsCount = bill.items.size.coerceAtLeast(1)

        // Generous estimated height to prevent clipping before crop
        var estimatedHeight = 520 // Header + Shop Details + Tax Invoice badge + Meta
        estimatedHeight += (itemsCount * 65) + 60 // Items table
        estimatedHeight += if (hasDiscount) 150 else 90 // Subtotal & Discount & Savings
        estimatedHeight += 110 // Grand Total box + dividers
        estimatedHeight += if (hasPending) 140 else 80 // Payment & Udhari
        if (shouldShowQr && settings.upiId.isNotBlank()) {
            estimatedHeight += 320 // QR code + text
        }
        estimatedHeight += 280 // Terms + Signature + Footer + Margins

        val bitmap = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val blackFill = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val solidLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        val thinLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        val dottedLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val subtitlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val boldCenter = Paint().apply {
            color = Color.BLACK
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val boldLeft = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val regularLeft = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val boldRight = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }

        val regularRight = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }

        val smallRegular = Paint().apply {
            color = Color.BLACK
            textSize = 15f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val leftX = 14f
        val rightX = width - 14f
        var y = 14f

        // 1. Centered Boutique Emblem / Logo (Increased size)
        val logoBmp = PrintShareHelper.getShopLogoBitmap(context, settings.logoPath)
            ?: PrintShareHelper.createDefaultBoutiqueEmblem(140)
        val logoSize = 90
        val scaledLogo = Bitmap.createScaledBitmap(logoBmp, logoSize, logoSize, true)
        canvas.drawBitmap(scaledLogo, (width - logoSize) / 2f, y, null)
        y += logoSize + 16f

        // 2. Shop Name (Increased size & prominence)
        val shopNameText = (bill.shopNameSnapshot.ifBlank { settings.shopName }).ifBlank { "मातोश्री कलेक्शन" }
        canvas.drawText(shopNameText, width / 2f, y + 26f, titlePaint)
        y += 38f

        // Tagline
        canvas.drawText("पैठणी, सिल्क साड्या व कपड्यांचे दालन", width / 2f, y + 14f, subtitlePaint)
        y += 24f

        // Address & Contact
        val shopAddress = (bill.shopAddressSnapshot ?: settings.address).ifBlank { "कोंढारकी, पंढरपूर" }
        canvas.drawText(shopAddress, width / 2f, y + 14f, subtitlePaint)
        y += 24f

        val shopMobile = (bill.shopMobileSnapshot ?: settings.mobile).ifBlank { "8208610261" }
        var contactStr = "मोबाईल: $shopMobile"
        val showGstin = bill.showGstinSnapshot && !(bill.shopGstinSnapshot ?: settings.gstin).isNullOrBlank()
        if (showGstin) {
            val gstin = bill.shopGstinSnapshot ?: settings.gstin ?: ""
            contactStr += " • GSTIN: $gstin"
        }
        canvas.drawText(contactStr, width / 2f, y + 14f, subtitlePaint)
        y += 26f

        // Double hairline border
        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawLine(leftX, y + 3f, rightX, y + 3f, solidLine)
        y += 14f

        // 3. Bill & Customer Metadata (Clear, No Overlap, No Complexity)
        canvas.drawText("बिल क्र: #${bill.billNumber}", leftX, y + 17f, boldLeft)
        y += 24f
        val dateDisplay = com.matoshree.shopmanager.utils.DateUtils.formatForDisplay(bill.billDate)
        canvas.drawText("दिनांक: $dateDisplay", leftX, y + 17f, boldLeft)
        y += 26f

        canvas.drawLine(leftX, y, rightX, y, thinLine)
        y += 8f

        // Customer Info: Clearly mentioned without cramped multi-columns or fake addresses
        val custName = bill.customerName?.takeIf {
            it.isNotBlank() && !it.equals("Walk-in", true) && !it.equals("Walk-in Customer", true)
        } ?: "किरकोळ ग्राहक"
        canvas.drawText("ग्राहक नाव: $custName", leftX, y + 17f, boldLeft)
        y += 26f

        val custMob = bill.customerMobile?.takeIf { it.isNotBlank() }
        if (!custMob.isNullOrBlank()) {
            canvas.drawText("मोबाईल क्र: $custMob", leftX, y + 17f, boldLeft)
            y += 26f
        }
        y += 4f

        // 5. Items Table Header (Crisp Double-Rule, Pure Black Text, NO SOLID BLACK FILL)
        val tableHeadH = 28f
        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawText("अ.क्र.", leftX + 4f, y + 19f, boldLeft)
        canvas.drawText("प्रॉडक्टचे नाव", leftX + 44f, y + 19f, boldLeft)
        canvas.drawText("नग", leftX + 220f, y + 19f, boldCenter)
        canvas.drawText("दर", leftX + 285f, y + 19f, boldRight)
        canvas.drawText("रक्कम", rightX - 4f, y + 19f, boldRight)
        canvas.drawLine(leftX, y + tableHeadH, rightX, y + tableHeadH, solidLine)
        y += tableHeadH + 14f

        // 6. Items Rows
        if (bill.items.isNotEmpty()) {
            bill.items.forEachIndexed { index, item ->
                // Line 1: Item Name & Total
                val srNo = "${index + 1}."
                canvas.drawText(srNo, leftX, y + 16f, boldLeft)
                val maxNameLen = 22
                val displayName = if (item.productName.length > maxNameLen) {
                    item.productName.take(maxNameLen - 1) + "…"
                } else {
                    item.productName
                }
                canvas.drawText(displayName, leftX + 30f, y + 16f, boldLeft)

                val itemTotStr = formatCurrencyThermal(item.lineTotal)
                canvas.drawText(itemTotStr, rightX, y + 16f, boldRight)
                y += 22f

                // Line 2: Details: Rate & Quantity
                val rateStr = formatCurrencyThermal(item.sellingPrice)
                val detailStr = "${item.quantity} नग  x  $rateStr"
                canvas.drawText(detailStr, leftX + 30f, y + 14f, smallRegular)
                y += 20f

                // Line separator between items
                canvas.drawLine(leftX + 30f, y, rightX, y, thinLine)
                y += 8f
            }
        } else {
            canvas.drawText("१. कापड / साडी विक्री", leftX, y + 16f, boldLeft)
            canvas.drawText(formatCurrencyThermal(bill.finalAmount), rightX, y + 16f, boldRight)
            y += 22f
            canvas.drawText("१ नग  x  ${formatCurrencyThermal(bill.finalAmount)}", leftX + 24f, y + 14f, smallRegular)
            y += 20f
        }

        // Dotted divider
        canvas.drawLine(leftX, y, rightX, y, dottedLine)
        y += 14f

        // 7. Subtotal & Discount
        canvas.drawText("उपएकूण :", leftX, y + 16f, regularLeft)
        canvas.drawText(formatCurrencyThermal(bill.subtotal), rightX, y + 16f, regularRight)
        y += 24f

        if (hasDiscount) {
            val discLabel = if (bill.discountType == DiscountType.PERCENTAGE && bill.discountValue > 0) {
                "डिस्काउंट (${bill.discountValue.toInt()}%) :"
            } else {
                "डिस्काउंट :"
            }
            canvas.drawText(discLabel, leftX, y + 16f, regularLeft)
            canvas.drawText("-${formatCurrencyThermal(bill.discountAmount)}", rightX, y + 16f, regularRight)
            y += 24f

            // Savings badge banner
            val savingsH = 26f
            val savingsBox = android.graphics.RectF(leftX, y, rightX, y + savingsH)
            val savingsBorder = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            canvas.drawRoundRect(savingsBox, 4f, 4f, savingsBorder)
            val savingsText = "★ तुम्ही ${formatCurrencyThermal(bill.discountAmount)} ची बचत केली! ★"
            val savePaint = Paint().apply {
                color = Color.BLACK
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText(savingsText, width / 2f, y + 18f, savePaint)
            y += savingsH + 14f
        }

        // Double Line before Grand Total
        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawLine(leftX, y + 3f, rightX, y + 3f, solidLine)
        y += 10f

        // 8. GRAND TOTAL (Large bold box)
        val grandTotalH = 50f
        val grandTotalBox = android.graphics.RectF(leftX, y, rightX, y + grandTotalH)
        val grandBorder = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawRoundRect(grandTotalBox, 6f, 6f, grandBorder)

        val gtLabelPaint = Paint().apply {
            color = Color.BLACK
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val gtAmountPaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }

        canvas.drawText("एकूण देय रक्कम :", leftX + 10f, y + 33f, gtLabelPaint)
        canvas.drawText(formatCurrencyThermal(bill.finalAmount), rightX - 10f, y + 33f, gtAmountPaint)
        y += grandTotalH + 16f

        // 9. Payment Method & Status
        val payMethodDisplay = if (bill.paymentMethod == PaymentMethod.CASH) "कॅश (Cash)" else if (bill.paymentMethod == PaymentMethod.UPI) "यूपीआय (UPI)" else "उधारी (Credit)"
        canvas.drawText("पेमेंट पद्धत: $payMethodDisplay", leftX, y + 16f, regularLeft)
        y += 24f

        if (hasPending || bill.paymentStatus != PaymentStatus.PAID) {
            val statusH = 64f
            val statusBox = android.graphics.RectF(leftX, y, rightX, y + statusH)
            val statBorder = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
            }
            canvas.drawRoundRect(statusBox, 5f, 5f, statBorder)
            canvas.drawText("पेमेंट स्थिती: उधारी / पेंडिंग", leftX + 8f, y + 22f, boldLeft)
            canvas.drawText("भरलेली रक्कम: ${formatCurrencyThermal(bill.paidAmount)}", rightX - 8f, y + 22f, regularRight)

            canvas.drawText("बाकी रक्कम: ${formatCurrencyThermal(bill.pendingAmount)}", leftX + 8f, y + 48f, boldLeft)
            if (!bill.dueDate.isNullOrBlank()) {
                canvas.drawText("मुदत तारीख: ${bill.dueDate}", rightX - 8f, y + 48f, regularRight)
            }
            y += statusH + 14f
        } else {
            canvas.drawText("पेमेंट स्थिती: पूर्ण भरणा झाला (Paid)", leftX, y + 16f, boldLeft)
            y += 24f
        }

        // 10. Scannable UPI Payment QR Code
        if (shouldShowQr && settings.upiId.isNotBlank()) {
            canvas.drawLine(leftX, y, rightX, y, dottedLine)
            y += 12f

            val payableAmount = if (bill.pendingAmount > 0.01) bill.pendingAmount else bill.finalAmount
            val upiUri = PrintShareHelper.buildUpiPaymentUri(
                upiId = settings.upiId,
                displayName = settings.upiDisplayName,
                amount = payableAmount,
                billNumber = bill.billNumber
            )
            val qrSize = 160
            val qrBmp = generateMonochromeQrBitmap(upiUri, size = qrSize)
            canvas.drawBitmap(qrBmp, (width - qrSize) / 2f, y, null)
            y += qrSize + 12f

            canvas.drawText("स्कॅन करून UPI द्वारे पेमेंट करा", width / 2f, y + 18f, boldCenter)
            y += 24f
            canvas.drawText("UPI ID: ${settings.upiId}", width / 2f, y + 15f, subtitlePaint)
            y += 22f
            canvas.drawText("देय रक्कम: ${formatCurrencyThermal(payableAmount)}", width / 2f, y + 18f, boldCenter)
            y += 26f
        }

        // 11. Terms & Store Policy
        canvas.drawLine(leftX, y, rightX, y, dottedLine)
        y += 14f
        canvas.drawText("नियम व अटी:", leftX, y + 16f, boldLeft)
        y += 24f
        canvas.drawText("★ एकदा विकलेला माल परत घेतला जाणार नाही. ★", width / 2f, y + 16f, boldCenter)
        y += 28f

        // 12. Signatures Section
        canvas.drawLine(leftX, y, rightX, y, thinLine)
        y += 34f
        canvas.drawText("ग्राहक स्वाक्षरी", leftX + 16f, y, smallRegular)
        canvas.drawText("अधिकृत स्वाक्षरी", rightX - 16f, y, Paint(smallRegular).apply { textAlign = Paint.Align.RIGHT })
        y += 18f
        canvas.drawText("(मातोश्री कलेक्शन)", rightX - 8f, y, Paint(boldRight).apply { textSize = 14f })
        y += 22f

        // 13. Decorative Footer
        canvas.drawLine(leftX, y, rightX, y, solidLine)
        y += 14f
        canvas.drawText("★ धन्यवाद! पुन्हा नक्की भेट द्या! ★", width / 2f, y + 18f, boldCenter)
        y += 30f

        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawLine(leftX, y + 3f, rightX, y + 3f, solidLine)
        y += 16f

        // Crop bitmap cleanly to exact drawn content height
        val finalHeight = y.toInt().coerceAtMost(estimatedHeight).coerceAtLeast(100)
        return Bitmap.createBitmap(bitmap, 0, 0, width, finalHeight)
    }

    /**
     * Builds complete 58mm ESC/POS payment receipt for an Udhari collection or debit addition.
     * Uses a crisp 384-dot monochrome bitmap for razor-sharp typography and exact layout.
     */
    fun formatPaymentReceipt(
        transaction: UdhariTransaction,
        customerName: String,
        customerMobile: String?,
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot()
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. Initialize & Reset printer buffer
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @
        out.write(byteArrayOf(0x1B, 0x32)) // ESC 2

        // 2. Render and stream the complete payment receipt bitmap
        val paymentBmp = renderThermalPaymentBitmap(transaction, customerName, customerMobile, settings)
        writeSlicedMonochromeImage(out, paymentBmp, targetWidth = MAX_PAPER_DOTS)

        // 3. Feed & Cut
        out.write(byteArrayOf(0x1B, 0x64, 0x04)) // Feed 4 lines
        out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x00)) // GS V A 0 (Partial Cut)

        return out.toByteArray()
    }

    /**
     * Renders an Udhari payment receipt or debit addition as a crisp 384-dot monochrome bitmap.
     * Spaced with clean container bounds to guarantee zero overlapping lines.
     */
    fun renderThermalPaymentBitmap(
        transaction: UdhariTransaction,
        customerName: String,
        customerMobile: String?,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): Bitmap {
        val width = MAX_PAPER_DOTS // 384
        val isDebit = transaction.type == UdhariTransactionType.PURCHASE_CREDIT
        val estimatedHeight = 650

        val bitmap = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val blackFill = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val solidLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val dottedLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val boldLeft = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val boldCenter = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val regularLeft = Paint().apply {
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val boldRight = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val regularRight = Paint().apply {
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }

        val leftX = 14f
        val rightX = width - 14f
        var y = 16f

        // Shop Name & Contact
        val shopNameText = settings.shopName.ifBlank { "मातोश्री कलेक्शन" }
        canvas.drawText(shopNameText, width / 2f, y + 24f, titlePaint)
        y += 34f

        canvas.drawText(settings.address.ifBlank { "कोंढारकी, पंढरपूर" }, width / 2f, y + 14f, subtitlePaint)
        y += 22f

        canvas.drawText("मोबाईल: ${settings.mobile.ifBlank { "8208610261" }}", width / 2f, y + 14f, subtitlePaint)
        y += 24f

        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawLine(leftX, y + 3f, rightX, y + 3f, solidLine)
        y += 12f

        // Title Box (Clean Outline, NO SOLID BLACK FILL)
        val titleBoxH = 28f
        val titleBox = android.graphics.RectF(leftX + 24f, y, rightX - 24f, y + titleBoxH)
        canvas.drawRoundRect(titleBox, 6f, 6f, solidLine)
        val headerTitle = if (isDebit) "— उधारी नोंद पावती —" else "— अधिकृत पेमेंट पावती —"
        canvas.drawText(headerTitle, width / 2f, y + 20f, boldCenter)
        y += titleBoxH + 16f

        // Customer Info
        canvas.drawText("कस्टमरचे नाव: $customerName", leftX, y + 16f, boldLeft)
        y += 24f

        if (!customerMobile.isNullOrBlank()) {
            canvas.drawText("मोबाईल नंबर: $customerMobile", leftX, y + 16f, regularLeft)
            y += 24f
        }

        if (!transaction.billNumber.isNullOrBlank()) {
            canvas.drawText("संदर्भ बिल: #${transaction.billNumber}", leftX, y + 16f, regularLeft)
            y += 24f
        }

        canvas.drawText("दिनांक: ${transaction.date}", leftX, y + 16f, regularLeft)
        y += 20f

        canvas.drawLine(leftX, y, rightX, y, dottedLine)
        y += 14f

        // Amount Box
        val amtBoxH = 50f
        val amtBox = android.graphics.RectF(leftX, y, rightX, y + amtBoxH)
        val amtBorder = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawRoundRect(amtBox, 6f, 6f, amtBorder)

        val amtLabel = if (isDebit) "उधारी नोंद रक्कम :" else "जमा पेमेंट रक्कम :"
        canvas.drawText(amtLabel, leftX + 10f, y + 33f, boldLeft)
        canvas.drawText(formatCurrencyThermal(transaction.amount), rightX - 10f, y + 33f, Paint(boldRight).apply { textSize = 24f })
        y += amtBoxH + 16f

        // Balance details
        if (!isDebit) {
            val payMethodText = if (transaction.paymentMethod == PaymentMethod.CASH) "कॅश" else "यूपीआय"
            canvas.drawText("पेमेंट पद्धत:", leftX, y + 16f, regularLeft)
            canvas.drawText(payMethodText, rightX, y + 16f, regularRight)
            y += 24f
        }

        canvas.drawText("मागील बाकी रक्कम:", leftX, y + 16f, regularLeft)
        canvas.drawText(formatCurrencyThermal(transaction.previousOutstanding), rightX, y + 16f, regularRight)
        y += 24f

        canvas.drawText("सध्याची बाकी रक्कम:", leftX, y + 16f, boldLeft)
        canvas.drawText(formatCurrencyThermal(transaction.remainingOutstanding), rightX, y + 16f, boldRight)
        y += 26f

        if (!transaction.note.isNullOrBlank()) {
            canvas.drawText("टीप: ${transaction.note}", leftX, y + 14f, regularLeft)
            y += 22f
        }

        // Signature & Footer
        canvas.drawLine(leftX, y, rightX, y, dottedLine)
        y += 34f

        canvas.drawText("ग्राहक स्वाक्षरी", leftX + 16f, y, regularLeft)
        canvas.drawText("अधिकृत स्वाक्षरी", rightX - 16f, y, regularRight)
        y += 18f
        canvas.drawText("(मातोश्री कलेक्शन)", rightX - 8f, y, Paint(boldRight).apply { textSize = 14f })
        y += 22f

        canvas.drawLine(leftX, y, rightX, y, solidLine)
        y += 14f

        val centerBold = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("★ धन्यवाद! पुन्हा नक्की भेट द्या! ★", width / 2f, y + 18f, centerBold)
        y += 30f

        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawLine(leftX, y + 3f, rightX, y + 3f, solidLine)
        y += 16f

        val finalH = y.toInt().coerceAtMost(estimatedHeight).coerceAtLeast(100)
        return Bitmap.createBitmap(bitmap, 0, 0, width, finalH)
    }

    /**
     * Builds complete 58mm ESC/POS byte payload for a Customer Account Statement / Ledger Report.
     */
    fun formatCustomerStatementReceipt(
        context: Context,
        customer: Customer,
        bills: List<Bill>,
        transactions: List<UdhariTransaction>,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @
        out.write(byteArrayOf(0x1B, 0x37, 0x07, 0xF0.toByte(), 0x02)) // Maximum heat density
        out.write(byteArrayOf(0x1B, 0x32)) // Default line spacing

        val bitmap = renderCustomerStatementBitmap(context, customer, bills, transactions, settings)
        writeSlicedMonochromeImage(out, bitmap, targetWidth = MAX_PAPER_DOTS)

        out.write(byteArrayOf(0x1B, 0x64, 0x04)) // Feed 4 lines
        out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x00)) // Cut
        return out.toByteArray()
    }

    /**
     * Renders a 384-dot high-contrast monochrome customer ledger statement bitmap.
     */
    fun renderCustomerStatementBitmap(
        context: Context,
        customer: Customer,
        bills: List<Bill>,
        transactions: List<UdhariTransaction>,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): Bitmap {
        val width = MAX_PAPER_DOTS
        val validBills = bills.filter { !it.isVoided }
        val recentItemsCount = (validBills.size + transactions.size).coerceAtMost(10).coerceAtLeast(1)
        val estimatedHeight = 650 + (recentItemsCount * 38) + 200

        val bitmap = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val leftX = 14f
        val rightX = width - 14f
        var y = 14f

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val boldCenter = Paint().apply {
            color = Color.BLACK
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val boldLeft = Paint().apply {
            color = Color.BLACK
            textSize = 17.5f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val regularLeft = Paint().apply {
            color = Color.BLACK
            textSize = 16.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val boldRight = Paint().apply {
            color = Color.BLACK
            textSize = 17.5f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val regularRight = Paint().apply {
            color = Color.BLACK
            textSize = 16.5f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val solidLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val thinLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val dottedLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }

        // Shop Logo
        val logoBmp = PrintShareHelper.getShopLogoBitmap(context, settings.logoPath)
        val logoSize = 80
        val scaledLogo = Bitmap.createScaledBitmap(logoBmp, logoSize, logoSize, true)
        canvas.drawBitmap(scaledLogo, (width - logoSize) / 2f, y, null)
        y += logoSize + 16f

        // Shop Header
        val shopNameText = settings.shopName.ifBlank { "मातोश्री कलेक्शन" }
        canvas.drawText(shopNameText, width / 2f, y + 26f, titlePaint)
        y += 38f
        canvas.drawText("पैठणी, सिल्क साड्या व कपड्यांचे दालन", width / 2f, y + 14f, subtitlePaint)
        y += 24f
        canvas.drawText(settings.address.ifBlank { "कोंढारकी, पंढरपूर" }, width / 2f, y + 14f, subtitlePaint)
        y += 24f
        canvas.drawText("मोबाईल: ${settings.mobile.ifBlank { "8208610261" }}", width / 2f, y + 14f, subtitlePaint)
        y += 26f

        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawLine(leftX, y + 3f, rightX, y + 3f, solidLine)
        y += 12f

        // Title Box
        val titleBoxH = 30f
        val titleBox = android.graphics.RectF(leftX + 16f, y, rightX - 16f, y + titleBoxH)
        canvas.drawRoundRect(titleBox, 6f, 6f, solidLine)
        canvas.drawText("— ग्राहक खाते अहवाल (Statement) —", width / 2f, y + 21f, boldCenter)
        y += titleBoxH + 16f

        // Customer Details
        canvas.drawText("ग्राहक नाव: ${customer.name}", leftX, y + 16f, boldLeft)
        y += 24f
        if (customer.mobile.isNotBlank()) {
            canvas.drawText("मोबाईल क्र: ${customer.mobile}", leftX, y + 16f, boldLeft)
            y += 24f
        }
        if (!customer.address.isNullOrBlank()) {
            canvas.drawText("गाव / पत्ता: ${customer.address}", leftX, y + 16f, regularLeft)
            y += 24f
        }
        val printDate = com.matoshree.shopmanager.utils.DateUtils.formatForDisplay(com.matoshree.shopmanager.utils.DateUtils.nowIso())
        canvas.drawText("दिनांक: $printDate", leftX, y + 16f, regularLeft)
        y += 26f

        canvas.drawLine(leftX, y, rightX, y, thinLine)
        y += 10f

        // Financial Summary Box
        val totalPurchases = validBills.sumOf { it.finalAmount }
        val totalBillsCount = validBills.size
        val totalPaid = transactions.filter { !it.isReversed && it.type == UdhariTransactionType.PAYMENT }.sumOf { it.amount } +
                validBills.sumOf { it.paidAmount }
        val outstanding = customer.outstandingBalance

        canvas.drawText("एकूण खरेदी:", leftX, y + 16f, regularLeft)
        canvas.drawText(formatCurrencyThermal(totalPurchases), rightX, y + 16f, boldRight)
        y += 24f

        canvas.drawText("एकूण बिले संख्या:", leftX, y + 16f, regularLeft)
        canvas.drawText("$totalBillsCount बिले", rightX, y + 16f, regularRight)
        y += 24f

        canvas.drawText("एकूण जमा रक्कम:", leftX, y + 16f, regularLeft)
        canvas.drawText(formatCurrencyThermal(totalPaid), rightX, y + 16f, boldRight)
        y += 28f

        // Highlight Outstanding Box
        val outBoxH = 46f
        val outBox = android.graphics.RectF(leftX, y, rightX, y + outBoxH)
        canvas.drawRoundRect(outBox, 6f, 6f, solidLine)
        canvas.drawText("शिल्लक बाकी उधारी:", leftX + 10f, y + 30f, boldLeft)
        canvas.drawText(formatCurrencyThermal(outstanding), rightX - 10f, y + 30f, Paint(boldRight).apply { textSize = 22f })
        y += outBoxH + 16f

        // Recent Entries Table
        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawText("दिनांक", leftX + 4f, y + 18f, boldLeft)
        canvas.drawText("तपशील", leftX + 130f, y + 18f, boldLeft)
        canvas.drawText("रक्कम", rightX - 4f, y + 18f, boldRight)
        canvas.drawLine(leftX, y + 26f, rightX, y + 26f, solidLine)
        y += 38f

        val sortedBills = validBills.take(5)
        sortedBills.forEach { b ->
            val dStr = if (b.billDate.length >= 10) b.billDate.take(10) else b.billDate
            canvas.drawText(dStr, leftX + 4f, y + 14f, regularLeft)
            canvas.drawText("बिल #${b.billNumber}", leftX + 130f, y + 14f, regularLeft)
            canvas.drawText(formatCurrencyThermal(b.finalAmount), rightX - 4f, y + 14f, boldRight)
            y += 22f
        }

        val sortedTxs = transactions.filter { !it.isReversed && it.type == UdhariTransactionType.PAYMENT }.take(5)
        sortedTxs.forEach { tx ->
            val dStr = if (tx.date.length >= 10) tx.date.take(10) else tx.date
            canvas.drawText(dStr, leftX + 4f, y + 14f, regularLeft)
            canvas.drawText("पेमेंट जमा (${if (tx.paymentMethod == PaymentMethod.CASH) "कॅश" else "यूपीआय"})", leftX + 130f, y + 14f, regularLeft)
            canvas.drawText("-${formatCurrencyThermal(tx.amount)}", rightX - 4f, y + 14f, boldRight)
            y += 22f
        }

        y += 8f
        canvas.drawLine(leftX, y, rightX, y, dottedLine)
        y += 34f

        // Signature & Footer
        canvas.drawText("ग्राहक स्वाक्षरी", leftX + 16f, y, regularLeft)
        canvas.drawText("अधिकृत स्वाक्षरी", rightX - 16f, y, regularRight)
        y += 18f
        canvas.drawText("(मातोश्री कलेक्शन)", rightX - 8f, y, Paint(boldRight).apply { textSize = 14f })
        y += 22f

        canvas.drawLine(leftX, y, rightX, y, solidLine)
        y += 14f
        val centerBold = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("★ धन्यवाद! मातोश्री कलेक्शन ★", width / 2f, y + 16f, centerBold)
        y += 28f
        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawLine(leftX, y + 3f, rightX, y + 3f, solidLine)
        y += 16f

        val finalH = y.toInt().coerceAtMost(estimatedHeight).coerceAtLeast(100)
        return Bitmap.createBitmap(bitmap, 0, 0, width, finalH)
    }

    /**
     * Builds complete 58mm ESC/POS byte payload for Daily Closing Receipt.
     */
    fun formatDailyClosingReceipt(
        context: Context,
        closing: DailyClosing,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @
        out.write(byteArrayOf(0x1B, 0x37, 0x07, 0xF0.toByte(), 0x02)) // Maximum heat density
        out.write(byteArrayOf(0x1B, 0x32)) // Default line spacing

        val bitmap = renderDailyClosingBitmap(context, closing, settings)
        writeSlicedMonochromeImage(out, bitmap, targetWidth = MAX_PAPER_DOTS)

        out.write(byteArrayOf(0x1B, 0x64, 0x04)) // Feed 4 lines
        out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x00)) // Cut
        return out.toByteArray()
    }

    /**
     * Renders a 384-dot high-contrast monochrome daily closing receipt bitmap.
     */
    fun renderDailyClosingBitmap(
        context: Context,
        closing: DailyClosing,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): Bitmap {
        val width = MAX_PAPER_DOTS
        val estimatedHeight = 900

        val bitmap = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val leftX = 14f
        val rightX = width - 14f
        var y = 14f

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val boldCenter = Paint().apply {
            color = Color.BLACK
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val boldLeft = Paint().apply {
            color = Color.BLACK
            textSize = 17.5f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val regularLeft = Paint().apply {
            color = Color.BLACK
            textSize = 16.5f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val boldRight = Paint().apply {
            color = Color.BLACK
            textSize = 17.5f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val regularRight = Paint().apply {
            color = Color.BLACK
            textSize = 16.5f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val solidLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val thinLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val dottedLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }

        // Shop Logo
        val logoBmp = PrintShareHelper.getShopLogoBitmap(context, settings.logoPath)
        val logoSize = 80
        val scaledLogo = Bitmap.createScaledBitmap(logoBmp, logoSize, logoSize, true)
        canvas.drawBitmap(scaledLogo, (width - logoSize) / 2f, y, null)
        y += logoSize + 16f

        // Shop Header
        val shopNameText = settings.shopName.ifBlank { "मातोश्री कलेक्शन" }
        canvas.drawText(shopNameText, width / 2f, y + 26f, titlePaint)
        y += 38f
        canvas.drawText("पैठणी, सिल्क साड्या व कपड्यांचे दालन", width / 2f, y + 14f, subtitlePaint)
        y += 24f
        canvas.drawText(settings.address.ifBlank { "कोंढारकी, पंढरपूर" }, width / 2f, y + 14f, subtitlePaint)
        y += 24f
        canvas.drawText("मोबाईल: ${settings.mobile.ifBlank { "8208610261" }}", width / 2f, y + 14f, subtitlePaint)
        y += 26f

        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawLine(leftX, y + 3f, rightX, y + 3f, solidLine)
        y += 12f

        // Title Box
        val titleBoxH = 30f
        val titleBox = android.graphics.RectF(leftX + 16f, y, rightX - 16f, y + titleBoxH)
        canvas.drawRoundRect(titleBox, 6f, 6f, solidLine)
        canvas.drawText("— दैनिक क्लोजिंग अहवाल (Daily Closing) —", width / 2f, y + 21f, boldCenter)
        y += titleBoxH + 16f

        canvas.drawText("क्लोजिंग दिनांक: ${closing.closingDate}", leftX, y + 16f, boldLeft)
        y += 24f
        val nowTime = com.matoshree.shopmanager.utils.DateUtils.formatForDisplay(com.matoshree.shopmanager.utils.DateUtils.nowIso())
        canvas.drawText("वेळ: $nowTime", leftX, y + 16f, regularLeft)
        y += 26f

        canvas.drawLine(leftX, y, rightX, y, thinLine)
        y += 12f

        // Financial Breakdown
        canvas.drawText("एकूण बिले संख्या:", leftX, y + 16f, regularLeft)
        canvas.drawText("${closing.totalBills} बिले", rightX, y + 16f, regularRight)
        y += 24f

        canvas.drawText("एकूण विक्री रक्कम:", leftX, y + 16f, boldLeft)
        canvas.drawText(formatCurrencyThermal(closing.totalSales), rightX, y + 16f, boldRight)
        y += 24f

        canvas.drawText("कॅश जमा (विक्री + उधारी):", leftX, y + 16f, regularLeft)
        canvas.drawText(formatCurrencyThermal(closing.cashSales), rightX, y + 16f, regularRight)
        y += 24f

        canvas.drawText("यूपीआय जमा (विक्री + उधारी):", leftX, y + 16f, regularLeft)
        canvas.drawText(formatCurrencyThermal(closing.upiSales), rightX, y + 16f, regularRight)
        y += 26f

        canvas.drawLine(leftX, y, rightX, y, dottedLine)
        y += 12f

        // Expected Cash vs Actual Cash
        val cashBoxH = 76f
        val cashBox = android.graphics.RectF(leftX, y, rightX, y + cashBoxH)
        canvas.drawRoundRect(cashBox, 6f, 6f, solidLine)
        canvas.drawText("गल्ल्यातील अपेक्षित कॅश:", leftX + 10f, y + 24f, regularLeft)
        canvas.drawText(formatCurrencyThermal(closing.expectedCash), rightX - 10f, y + 24f, boldRight)
        canvas.drawText("प्रत्यक्ष मोजलेली कॅश:", leftX + 10f, y + 48f, boldLeft)
        canvas.drawText(formatCurrencyThermal(closing.actualCash), rightX - 10f, y + 48f, Paint(boldRight).apply { textSize = 20f })
        canvas.drawText("कॅश फरक (Difference):", leftX + 10f, y + 68f, regularLeft)
        val diffStr = if (closing.cashDifference >= 0) "+${formatCurrencyThermal(closing.cashDifference)}" else formatCurrencyThermal(closing.cashDifference)
        canvas.drawText(diffStr, rightX - 10f, y + 68f, boldRight)
        y += cashBoxH + 16f

        if (!closing.notes.isNullOrBlank()) {
            canvas.drawText("टीप: ${closing.notes}", leftX, y + 14f, regularLeft)
            y += 22f
        }

        canvas.drawLine(leftX, y, rightX, y, dottedLine)
        y += 34f

        canvas.drawText("मॅनेजर / ओनर स्वाक्षरी", rightX - 16f, y, regularRight)
        y += 18f
        canvas.drawText("(मातोश्री कलेक्शन)", rightX - 8f, y, Paint(boldRight).apply { textSize = 14f })
        y += 22f

        canvas.drawLine(leftX, y, rightX, y, solidLine)
        y += 14f
        val centerBold = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("★ दैनिक हिशोब सील केला गेला आहे ★", width / 2f, y + 16f, centerBold)
        y += 28f
        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawLine(leftX, y + 3f, rightX, y + 3f, solidLine)
        y += 16f

        val finalH = y.toInt().coerceAtMost(estimatedHeight).coerceAtLeast(100)
        return Bitmap.createBitmap(bitmap, 0, 0, width, finalH)
    }

    /**
     * Builds complete 58mm ESC/POS byte payload for an Analytics Report of a selected period
     * (Today, This Week, This Month, This Year, or Custom date range).
     */
    fun formatAnalyticsReceipt(
        context: Context,
        periodLabel: String,
        totalSales: Double,
        totalBillsCount: Int,
        avgBillValue: Double,
        cashAmount: Double,
        upiAmount: Double,
        otherAmount: Double,
        totalExpenses: Double,
        netProfit: Double,
        topProducts: List<TopProductStat>,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): ByteArray {
        val out = ByteArrayOutputStream()

        out.write(byteArrayOf(0x1B, 0x40)) // ESC @
        out.write(byteArrayOf(0x1B, 0x32)) // ESC 2

        val reportBitmap = renderThermalAnalyticsBitmap(
            context = context,
            periodLabel = periodLabel,
            totalSales = totalSales,
            totalBillsCount = totalBillsCount,
            avgBillValue = avgBillValue,
            cashAmount = cashAmount,
            upiAmount = upiAmount,
            otherAmount = otherAmount,
            totalExpenses = totalExpenses,
            netProfit = netProfit,
            topProducts = topProducts,
            settings = settings
        )
        writeSlicedMonochromeImage(out, reportBitmap, targetWidth = MAX_PAPER_DOTS)

        out.write(byteArrayOf(0x1B, 0x64, 0x04)) // Feed 4 lines
        out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x00)) // Partial Cut

        return out.toByteArray()
    }

    /**
     * Renders an Analytics Report as a crisp 384-dot monochrome bitmap for 58mm thermal printers.
     * Cleanly formatted with outline boxes and dark black text (no background fills).
     */
    fun renderThermalAnalyticsBitmap(
        context: Context,
        periodLabel: String,
        totalSales: Double,
        totalBillsCount: Int,
        avgBillValue: Double,
        cashAmount: Double,
        upiAmount: Double,
        otherAmount: Double,
        totalExpenses: Double,
        netProfit: Double,
        topProducts: List<TopProductStat>,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): Bitmap {
        val width = MAX_PAPER_DOTS // 384
        val topCount = topProducts.take(5).size
        val estimatedHeight = 720 + (topCount * 52)

        val bitmap = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val solidLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val thinLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val dottedLine = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val boldCenter = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val boldLeft = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val regularLeft = Paint().apply {
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val boldRight = Paint().apply {
            color = Color.BLACK
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val regularRight = Paint().apply {
            color = Color.BLACK
            textSize = 16f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val smallRegular = Paint().apply {
            color = Color.BLACK
            textSize = 14f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val leftX = 14f
        val rightX = width - 14f
        var y = 14f

        // 1. Logo
        val logoBmp = PrintShareHelper.getShopLogoBitmap(context, settings.logoPath)
            ?: PrintShareHelper.createDefaultBoutiqueEmblem(120)
        val logoSize = 64
        val scaledLogo = Bitmap.createScaledBitmap(logoBmp, logoSize, logoSize, true)
        canvas.drawBitmap(scaledLogo, (width - logoSize) / 2f, y, null)
        y += logoSize + 14f

        // 2. Shop Name
        val shopNameText = settings.shopName.ifBlank { "मातोश्री कलेक्शन" }
        canvas.drawText(shopNameText, width / 2f, y + 24f, titlePaint)
        y += 34f

        canvas.drawText("पैठणी, सिल्क साड्या व कपड्यांचे दालन", width / 2f, y + 14f, subtitlePaint)
        y += 22f

        canvas.drawText(settings.address.ifBlank { "कोंढारकी, पंढरपूर" }, width / 2f, y + 14f, subtitlePaint)
        y += 22f

        canvas.drawText("मोबाईल: ${settings.mobile.ifBlank { "8208610261" }}", width / 2f, y + 14f, subtitlePaint)
        y += 24f

        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawLine(leftX, y + 3f, rightX, y + 3f, solidLine)
        y += 12f

        // 3. Report Header Box (Clean Outline, NO SOLID BLACK FILL)
        val reportBoxH = 28f
        val reportBox = android.graphics.RectF(leftX + 16f, y, rightX - 16f, y + reportBoxH)
        canvas.drawRoundRect(reportBox, 6f, 6f, solidLine)
        canvas.drawText("★ व्यवसाय ॲनालिटिक्स रिपोर्ट ★", width / 2f, y + 20f, boldCenter)
        y += reportBoxH + 16f

        // 4. Period & Generation Metadata
        canvas.drawText("कालावधी: $periodLabel", leftX, y + 16f, boldLeft)
        val todayStr = com.matoshree.shopmanager.utils.DateUtils.todayDate()
        canvas.drawText("दिनांक: $todayStr", rightX, y + 16f, regularRight)
        y += 24f

        val timeStr = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        canvas.drawText("रिपोर्ट वेळ: $timeStr", leftX, y + 14f, smallRegular)
        canvas.drawText("अधिकृत अहवाल", rightX, y + 14f, regularRight)
        y += 20f

        canvas.drawLine(leftX, y, rightX, y, dottedLine)
        y += 12f

        // 5. Total Sales Box
        val salesBoxH = 48f
        val salesBox = android.graphics.RectF(leftX, y, rightX, y + salesBoxH)
        canvas.drawRoundRect(salesBox, 6f, 6f, solidLine)
        canvas.drawText("एकूण विक्री रक्कम :", leftX + 10f, y + 32f, boldLeft)
        canvas.drawText(formatCurrencyThermal(totalSales), rightX - 10f, y + 32f, Paint(boldRight).apply { textSize = 22f })
        y += salesBoxH + 16f

        // 6. Bills and Avg Bill
        canvas.drawText("एकूण तयार झालेली बिले:", leftX, y + 16f, regularLeft)
        canvas.drawText("$totalBillsCount बिले", rightX, y + 16f, boldRight)
        y += 24f

        canvas.drawText("सरासरी बिल मूल्य:", leftX, y + 16f, regularLeft)
        canvas.drawText(formatCurrencyThermal(avgBillValue), rightX, y + 16f, regularRight)
        y += 24f

        canvas.drawLine(leftX, y, rightX, y, dottedLine)
        y += 14f

        // 7. Payment Breakdown
        canvas.drawText("पेमेंट पद्धत वर्गीकरण:", leftX, y + 16f, boldLeft)
        y += 24f

        canvas.drawText("• रोख / कॅश (Cash):", leftX + 8f, y + 16f, regularLeft)
        canvas.drawText(formatCurrencyThermal(cashAmount), rightX, y + 16f, regularRight)
        y += 22f

        canvas.drawText("• यूपीआय (UPI):", leftX + 8f, y + 16f, regularLeft)
        canvas.drawText(formatCurrencyThermal(upiAmount), rightX, y + 16f, regularRight)
        y += 22f

        canvas.drawText("• उधारी / इतर (Credit):", leftX + 8f, y + 16f, regularLeft)
        canvas.drawText(formatCurrencyThermal(otherAmount), rightX, y + 16f, regularRight)
        y += 24f

        canvas.drawLine(leftX, y, rightX, y, dottedLine)
        y += 14f

        // 8. Financial Summary (Expenses & Net Profit)
        canvas.drawText("एकूण खर्च (Expenses):", leftX, y + 16f, regularLeft)
        canvas.drawText("-${formatCurrencyThermal(totalExpenses)}", rightX, y + 16f, regularRight)
        y += 24f

        val profitBoxH = 46f
        val profitBox = android.graphics.RectF(leftX, y, rightX, y + profitBoxH)
        canvas.drawRoundRect(profitBox, 6f, 6f, solidLine)
        canvas.drawText("अंदाजे निव्वळ नफा (Profit):", leftX + 8f, y + 30f, boldLeft)
        canvas.drawText(formatCurrencyThermal(netProfit), rightX - 8f, y + 30f, Paint(boldRight).apply { textSize = 20f })
        y += profitBoxH + 16f

        // 9. Top Selling Products
        if (topProducts.isNotEmpty()) {
            canvas.drawLine(leftX, y, rightX, y, dottedLine)
            y += 14f
            canvas.drawText("टॉप विक्री प्रॉडक्ट्स (${periodLabel}):", leftX, y + 16f, boldLeft)
            y += 24f

            topProducts.take(5).forEachIndexed { idx, prod ->
                canvas.drawText("${idx + 1}. ${prod.name.take(20)}", leftX + 4f, y + 16f, boldLeft)
                canvas.drawText(formatCurrencyThermal(prod.revenue), rightX, y + 16f, boldRight)
                y += 20f

                canvas.drawText("${prod.quantity} नग विक्री", leftX + 24f, y + 14f, smallRegular)
                y += 18f
                canvas.drawLine(leftX + 24f, y, rightX, y, thinLine)
                y += 6f
            }
        }

        // 10. Signatures
        canvas.drawLine(leftX, y, rightX, y, dottedLine)
        y += 34f
        canvas.drawText("तयार करणारा", leftX + 16f, y, smallRegular)
        canvas.drawText("अधिकृत सही / शिक्का", rightX - 16f, y, Paint(smallRegular).apply { textAlign = Paint.Align.RIGHT })
        y += 18f
        canvas.drawText("(मातोश्री कलेक्शन)", rightX - 8f, y, Paint(boldRight).apply { textSize = 14f })
        y += 22f

        // 11. Footer
        canvas.drawLine(leftX, y, rightX, y, solidLine)
        y += 14f
        canvas.drawText("★ मातोश्री कलेक्शन • व्यवसाय अहवाल ★", width / 2f, y + 18f, boldCenter)
        y += 30f

        canvas.drawLine(leftX, y, rightX, y, solidLine)
        canvas.drawLine(leftX, y + 3f, rightX, y + 3f, solidLine)
        y += 16f

        val finalH = y.toInt().coerceAtMost(estimatedHeight).coerceAtLeast(100)
        return Bitmap.createBitmap(bitmap, 0, 0, width, finalH)
    }

    /**
     * Builds ESC/POS payload for Test Print.
     */
    fun formatTestPrintReceipt(context: Context): ByteArray {
        val out = ByteArrayOutputStream()

        out.write(byteArrayOf(0x1B, 0x40)) // ESC @
        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center align

        val emblem = PrintShareHelper.createDefaultBoutiqueEmblem(120)
        writeSlicedMonochromeImage(out, emblem, targetWidth = 120)

        val headerBmp = renderTextToBitmap("मातोश्री कलेक्शन", textSize = 26f, isBold = true)
        writeSlicedMonochromeImage(out, headerBmp, targetWidth = headerBmp.width.coerceAtMost(MAX_PAPER_DOTS))

        out.write("${"-".repeat(CHARS_PER_LINE)}\n".toByteArray(CP437))
        val testBmp = renderTextToBitmap("ब्लूटूथ प्रिंटर चाचणी यशस्वी!", textSize = 18f, isBold = true)
        writeSlicedMonochromeImage(out, testBmp, targetWidth = testBmp.width.coerceAtMost(MAX_PAPER_DOTS))
        out.write("${"-".repeat(CHARS_PER_LINE)}\n".toByteArray(CP437))

        out.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left align
        out.write("कागद रुंदी: ५८ मिमी (384 dots)\n".toByteArray(CP437))
        out.write("ब्लूटूथ कनेक्शन: सक्रिय\n".toByteArray(CP437))
        out.write("थर्मल प्रिंटिंग: तयार\n".toByteArray(CP437))

        out.write("${"-".repeat(CHARS_PER_LINE)}\n".toByteArray(CP437))
        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
        val readyBmp = renderTextToBitmap("थेट बिलिंगसाठी तयार!", textSize = 20f, isBold = true)
        writeSlicedMonochromeImage(out, readyBmp, targetWidth = readyBmp.width.coerceAtMost(MAX_PAPER_DOTS))

        out.write(byteArrayOf(0x1B, 0x64, 0x04)) // Feed 4 lines
        out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x00)) // Cut

        return out.toByteArray()
    }

    /**
     * Slices any Bitmap into smooth vertical bands (64 rows) and transmits them as standard
     * GS v 0 raster commands with zero line-spacing control to prevent buffer overflows,
     * motor stutter, and physical line overlap on 58mm thermal printers.
     */
    fun writeSlicedMonochromeImage(out: ByteArrayOutputStream, src: Bitmap, targetWidth: Int) {
        val scaledHeight = ((src.height.toFloat() / src.width.toFloat()) * targetWidth).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, targetWidth, scaledHeight, true)

        val fullBmp = Bitmap.createBitmap(MAX_PAPER_DOTS, scaledHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fullBmp)
        canvas.drawColor(Color.WHITE)

        val leftOffset = (MAX_PAPER_DOTS - targetWidth) / 2f
        canvas.drawBitmap(scaled, leftOffset, 0f, null)

        val bytesWidth = MAX_PAPER_DOTS / 8 // 48 bytes
        var yStart = 0

        // 1. Set zero line spacing to eliminate motor backlash & spurious feed gaps between slices
        out.write(byteArrayOf(0x1B, 0x33, 0x00)) // ESC 3 0

        while (yStart < scaledHeight) {
            val sliceH = (scaledHeight - yStart).coerceAtMost(SLICE_HEIGHT)

            // GS v 0 0 xL xH yL yH
            out.write(0x1D)
            out.write(0x76)
            out.write(0x30)
            out.write(0x00) // Mode 0 (Normal)
            out.write((bytesWidth % 256))
            out.write((bytesWidth / 256))
            out.write((sliceH % 256))
            out.write((sliceH / 256))

            for (y in yStart until (yStart + sliceH)) {
                for (byteIdx in 0 until bytesWidth) {
                    var currentByte = 0
                    for (bit in 0 until 8) {
                        val x = byteIdx * 8 + bit
                        val pixel = fullBmp.getPixel(x, y)
                        val alpha = Color.alpha(pixel)
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                        if (alpha > 30 && luminance < 205) {
                            currentByte = currentByte or (1 shl (7 - bit)) // 1 = Black dot
                        }
                    }
                    out.write(currentByte)
                }
            }
            yStart += sliceH
        }

        // 2. Restore default line spacing after all raster slices are transmitted
        out.write(byteArrayOf(0x1B, 0x32)) // ESC 2
    }

    /**
     * Generates a sharp, pure 1-bit black & white QR code bitmap without anti-aliasing blur.
     */
    private fun generateMonochromeQrBitmap(data: String, size: Int = 180): Bitmap {
        val hints = HashMap<EncodeHintType, Any>().apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.MARGIN, 1)
        }

        val qrCodeWriter = QRCodeWriter()
        val bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, size, size, hints)

        val width = bitMatrix.width
        val height = bitMatrix.height
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    /**
     * Renders text (e.g. Marathi or complex fonts) into a clean high-contrast bitmap.
     */
    private fun renderTextToBitmap(text: String, textSize: Float, isBold: Boolean): Bitmap {
        val paint = Paint().apply {
            color = Color.BLACK
            this.textSize = textSize
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            if (isBold) typeface = Typeface.DEFAULT_BOLD
        }

        val textWidth = paint.measureText(text).toInt() + 16
        val textHeight = (paint.descent() - paint.ascent()).toInt() + 8

        val bmp = Bitmap.createBitmap(textWidth.coerceAtLeast(1), textHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(text, textWidth / 2f, -paint.ascent() + 4f, paint)
        return bmp
    }

    private fun containsNonAscii(str: String): Boolean {
        return str.any { it.code > 127 }
    }

    private fun format2Columns(left: String, right: String, totalWidth: Int): String {
        val spaces = (totalWidth - left.length - right.length).coerceAtLeast(1)
        return left + " ".repeat(spaces) + right
    }

    private fun format3Columns(c1: String, c2: String, c3: String, w1: Int, w2: Int, w3: Int): String {
        val col1 = c1.padEnd(w1).take(w1)
        val col2 = c2.padStart(w2).take(w2)
        val col3 = c3.padStart(w3).take(w3)
        return col1 + col2 + col3
    }

    private fun formatCurrencyThermal(amount: Double): String {
        return String.format(java.util.Locale.US, "₹%.2f", amount)
    }
}

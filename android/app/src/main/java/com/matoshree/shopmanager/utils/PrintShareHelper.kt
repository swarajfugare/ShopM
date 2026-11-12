package com.matoshree.shopmanager.utils

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.domain.model.Customer
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.domain.model.PaymentStatus
import com.matoshree.shopmanager.domain.model.UdhariTransaction
import com.matoshree.shopmanager.domain.model.UdhariTransactionType
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object PrintShareHelper {

    data class ShopSettingsSnapshot(
        val shopName: String = "मातोश्री कलेक्शन",
        val mobile: String = "8208610261",
        val email: String? = null,
        val address: String = "कोंढारकी",
        val gstin: String = "",
        val showGstin: Boolean = false,
        val upiId: String = "",
        val upiDisplayName: String = "मातोश्री कलेक्शन",
        val logoPath: String? = null
    )

    suspend fun loadShopSettingsSnapshot(): ShopSettingsSnapshot {
        return try {
            val doc = try {
                kotlinx.coroutines.withTimeoutOrNull(600) {
                    FirebaseManager.shopDoc().get().await()
                } ?: FirebaseManager.shopDoc().get(com.google.firebase.firestore.Source.CACHE).await()
            } catch (e: Exception) {
                null
            }
            if (doc != null && doc.exists()) {
                ShopSettingsSnapshot(
                    shopName = doc.getString("name")?.ifBlank { null } ?: doc.getString("shopName")?.ifBlank { null } ?: "मातोश्री कलेक्शन",
                    mobile = doc.getString("mobile")?.ifBlank { null } ?: doc.getString("phone")?.ifBlank { null } ?: "8208610261",
                    email = doc.getString("email"),
                    address = doc.getString("address")?.ifBlank { null } ?: "कोंढारकी",
                    gstin = doc.getString("gstin") ?: "",
                    showGstin = doc.getBoolean("showGstin") ?: false,
                    upiId = doc.getString("upiId") ?: "",
                    upiDisplayName = doc.getString("upiDisplayName") ?: "मातोश्री कलेक्शन",
                    logoPath = doc.getString("logoUrl") ?: doc.getString("logoPath")
                )
            } else {
                ShopSettingsSnapshot()
            }
        } catch (e: Exception) {
            ShopSettingsSnapshot()
        }
    }

    /**
     * Builds UPI Payment URI
     */
    fun buildUpiPaymentUri(
        upiId: String,
        displayName: String,
        amount: Double,
        billNumber: String
    ): String {
        val cleanUpi = upiId.trim().ifBlank { "matoshree@upi" }
        val encodedName = URLEncoder.encode(displayName.trim().ifBlank { "Matoshree Collection" }, StandardCharsets.UTF_8.toString())
        val formattedAmount = String.format(java.util.Locale.US, "%.2f", amount)
        val encodedTn = URLEncoder.encode(billNumber.trim(), StandardCharsets.UTF_8.toString())
        return "upi://pay?pa=$cleanUpi&pn=$encodedName&am=$formattedAmount&cu=INR&tn=$encodedTn"
    }

    /**
     * Generates a REAL, FUNCTIONAL UPI QR Code using ZXing with HIGH Error Correction (H)
     * and overlays the boutique logo in the center with a safe quiet badge.
     */
    fun generateUpiQrBitmap(
        data: String,
        size: Int = 400,
        logoBitmap: Bitmap? = null
    ): Bitmap {
        val hints = HashMap<EncodeHintType, Any>().apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.MARGIN, 1)
        }

        val qrCodeWriter = QRCodeWriter()
        val bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, size, size, hints)

        val width = bitMatrix.width
        val height = bitMatrix.height
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val primaryColor = Color.parseColor("#00342B") // Deep Emerald
        val backgroundColor = Color.WHITE

        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) primaryColor else backgroundColor)
            }
        }

        val canvas = Canvas(bmp)
        val emblem = logoBitmap ?: createDefaultBoutiqueEmblem(100)

        val logoSize = (size * 0.20f).toInt()
        val scaledLogo = Bitmap.createScaledBitmap(emblem, logoSize, logoSize, true)

        val cx = width / 2f
        val cy = height / 2f
        val radius = (logoSize / 2f) + 6f

        val badgePaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        val borderPaint = Paint().apply {
            color = Color.parseColor("#735C00") // Champagne Gold
            strokeWidth = 3f
            isAntiAlias = true
            style = Paint.Style.STROKE
        }

        canvas.drawCircle(cx, cy, radius, badgePaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)

        val left = cx - (logoSize / 2f)
        val top = cy - (logoSize / 2f)
        canvas.drawBitmap(scaledLogo, left, top, null)

        return bmp
    }

    /**
     * Diagnostic decoder to test QR scannability with ZXing reader
     */
    fun decodeQrContent(bitmap: Bitmap): String? {
        return try {
            val intArray = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(binaryBitmap)
            result.text
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Creates a high-resolution royal boutique emblem for Matoshree Collection
     */
    fun createDefaultBoutiqueEmblem(size: Int = 160): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint().apply {
            color = Color.parseColor("#00342B") // Deep Emerald
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        val goldBorder = Paint().apply {
            color = Color.parseColor("#C39B14") // Champagne Gold
            strokeWidth = size * 0.04f
            isAntiAlias = true
            style = Paint.Style.STROKE
        }
        val textPaint = Paint().apply {
            color = Color.parseColor("#FFFDF5") // Warm Ivory
            textSize = size * 0.42f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val r = size / 2f
        canvas.drawCircle(r, r, r - (size * 0.03f), bgPaint)
        canvas.drawCircle(r, r, r - (size * 0.05f), goldBorder)

        val yPos = (r - ((textPaint.descent() + textPaint.ascent()) / 2))
        canvas.drawText("MC", r, yPos, textPaint)

        return bmp
    }

    fun getShopLogoBitmap(context: Context, pathOrBase64: String?): Bitmap {
        if (!pathOrBase64.isNullOrBlank()) {
            if (pathOrBase64.startsWith("data:image") || pathOrBase64.length > 200) {
                try {
                    val cleanB64 = if (pathOrBase64.contains(",")) pathOrBase64.substringAfter(",") else pathOrBase64
                    val bytes = android.util.Base64.decode(cleanB64, android.util.Base64.DEFAULT)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded != null) return decoded
                } catch (e: Exception) {}
            }
            val file = File(pathOrBase64)
            if (file.exists()) {
                val decoded = BitmapFactory.decodeFile(file.absolutePath)
                if (decoded != null) return decoded
            }
        }
        val cached = File(context.filesDir, "shop_logo.png")
        if (cached.exists()) {
            val decoded = BitmapFactory.decodeFile(cached.absolutePath)
            if (decoded != null) return decoded
        }
        val resLogo = BitmapFactory.decodeResource(context.resources, com.matoshree.shopmanager.R.drawable.app_logo)
        if (resLogo != null) return resLogo
        return createDefaultBoutiqueEmblem(160)
    }

    /**
     * Generates a high-definition bill receipt Bitmap in pure Marathi-First format.
     */
    fun generateBillReceiptBitmap(
        context: Context,
        bill: Bill,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ): Bitmap {
        val width = 760
        val hasDiscount = bill.discountAmount > 0.01
        val hasPending = bill.pendingAmount > 0.01
        val shouldShowQr = (bill.paymentMethod == PaymentMethod.UPI && bill.paidAmount > 0) || hasPending
        val itemsCount = bill.items.size.coerceAtLeast(1)

        var calculatedHeight = 520 // Header + Shop Branding + Tax Invoice badge + Meta
        calculatedHeight += (itemsCount * 65) + 60 // Items Table
        calculatedHeight += if (hasDiscount) 180 else 110 // Subtotal & Discount & Savings
        calculatedHeight += 85 // Grand Total banner
        calculatedHeight += if (hasPending) 120 else 60 // Payment & Udhari
        if (shouldShowQr && settings.upiId.isNotBlank()) {
            calculatedHeight += 310 // UPI QR Code
        }
        calculatedHeight += 220 // Terms + Signatures + Footer + Margins

        val height = calculatedHeight
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background: Clean Warm Ivory
        canvas.drawColor(Color.parseColor("#FFFDF5"))

        val emeraldPaint = Paint().apply { color = Color.parseColor("#00342B"); isAntiAlias = true }
        val goldPaint = Paint().apply { color = Color.parseColor("#735C00"); isAntiAlias = true }
        val goldLinePaint = Paint().apply {
            color = Color.parseColor("#C39B14")
            strokeWidth = 2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val dividerPaint = Paint().apply {
            color = Color.parseColor("#E0E3E1")
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val dottedPaint = Paint().apply {
            color = Color.parseColor("#BFC9C4")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 5f), 0f)
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = Color.parseColor("#1B1C1C")
            textSize = 21f
            isAntiAlias = true
        }
        val boldTextPaint = Paint().apply {
            color = Color.parseColor("#1B1C1C")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val mutedPaint = Paint().apply {
            color = Color.parseColor("#5A605D")
            textSize = 19f
            isAntiAlias = true
        }
        val headerTitlePaint = Paint().apply {
            color = Color.parseColor("#00342B")
            textSize = 44f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val subheaderPaint = Paint().apply {
            color = Color.parseColor("#5A605D")
            textSize = 21f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val goldTaglinePaint = Paint().apply {
            color = Color.parseColor("#735C00")
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // Top Dual-Tone Header Bar
        canvas.drawRect(0f, 0f, width.toFloat(), 10f, emeraldPaint)
        canvas.drawRect(0f, 10f, width.toFloat(), 14f, Paint().apply { color = Color.parseColor("#FED65B") })

        var y = 46f

        // 1. Centered Boutique Logo in Gold Ring (Increased Size)
        val logoBmp = getShopLogoBitmap(context, settings.logoPath)
        val logoSize = 120
        val scaledShopLogo = Bitmap.createScaledBitmap(logoBmp, logoSize, logoSize, true)

        val logoLeft = (width - logoSize) / 2f
        val logoCenter = width / 2f
        val logoRadius = (logoSize / 2f) + 4f
        val ringPaint = Paint().apply {
            color = Color.parseColor("#C39B14")
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        canvas.drawCircle(logoCenter, y + (logoSize / 2f), logoRadius, ringPaint)
        canvas.drawBitmap(scaledShopLogo, logoLeft, y, null)

        y += logoSize + 30f

        // 2. Shop Name & Details (Increased font size & prominence)
        val activeShopName = (bill.shopNameSnapshot.ifBlank { settings.shopName }).ifBlank { "मातोश्री कलेक्शन" }
        canvas.drawText(activeShopName, width / 2f, y, headerTitlePaint)
        y += 32f

        canvas.drawText("पैठणी, सिल्क साड्या व डिझायनर कपड्यांचे दालन", width / 2f, y, goldTaglinePaint)
        y += 26f

        val activeAddress = (bill.shopAddressSnapshot ?: settings.address).ifBlank { "कोंढारकी" }
        val activeMobile = (bill.shopMobileSnapshot ?: settings.mobile).ifBlank { "8208610261" }
        val activeGstin = bill.shopGstinSnapshot ?: settings.gstin
        val showGstin = bill.showGstinSnapshot && !activeGstin.isNullOrBlank()

        canvas.drawText(activeAddress, width / 2f, y, subheaderPaint)
        y += 24f

        var contactLine = "मोबाईल: $activeMobile"
        if (showGstin) {
            contactLine += "  •  GSTIN: $activeGstin"
        }
        canvas.drawText(contactLine, width / 2f, y, subheaderPaint)
        y += 28f

        // 3. Customer & Invoice Metadata Box (Clean, Structured, No Overlap)
        val boxH = 120f
        val metaBoxRect = RectF(36f, y, width - 36f, y + boxH)
        val metaBg = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        val metaBorder = Paint().apply { color = Color.parseColor("#E0E3E1"); strokeWidth = 1.5f; style = Paint.Style.STROKE; isAntiAlias = true }
        canvas.drawRoundRect(metaBoxRect, 10f, 10f, metaBg)
        canvas.drawRoundRect(metaBoxRect, 10f, 10f, metaBorder)

        val leftColX = 56f
        val rightColX = width - 56f

        // Row 1: Bill Number and Date
        val metaY1 = y + 32f
        val billNumStr = "बिल क्र: #${bill.billNumber}"
        val billNumPaint = Paint().apply { color = Color.parseColor("#00342B"); textSize = 22f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        canvas.drawText(billNumStr, leftColX, metaY1, billNumPaint)

        val dateDisplay = "दिनांक: ${DateUtils.formatForDisplay(bill.billDate)}"
        val datePaint = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 20f; textAlign = Paint.Align.RIGHT; isAntiAlias = true }
        canvas.drawText(dateDisplay, rightColX, metaY1, datePaint)

        // Divider inside box
        canvas.drawLine(leftColX, y + 46f, rightColX, y + 46f, Paint().apply { color = Color.parseColor("#F0F2F1"); strokeWidth = 1f })

        // Row 2: Customer Name (Prominent & Clear)
        val metaY2 = y + 74f
        val custName = bill.customerName?.takeIf { it.isNotBlank() && !it.equals("Walk-in", true) && !it.equals("Walk-in Customer", true) } ?: "किरकोळ ग्राहक"
        val custNamePaint = Paint().apply { color = Color.parseColor("#1B1C1C"); textSize = 22f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        canvas.drawText("ग्राहक नाव : $custName", leftColX, metaY2, custNamePaint)

        // Row 3: Customer Mobile
        val metaY3 = y + 104f
        val custMobile = bill.customerMobile?.takeIf { it.isNotBlank() } ?: "-"
        val custMobPaint = Paint().apply { color = Color.parseColor("#00342B"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        canvas.drawText("मोबाईल क्र : $custMobile", leftColX, metaY3, custMobPaint)

        y += boxH + 24f

        // 4. Products Table
        val thHeight = 40f
        val thRect = RectF(36f, y, width - 36f, y + thHeight)
        canvas.drawRoundRect(thRect, 8f, 8f, emeraldPaint)

        val thWhite = Paint().apply { color = Color.WHITE; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val thCenter = Paint(thWhite).apply { textAlign = Paint.Align.CENTER }
        val thRight = Paint(thWhite).apply { textAlign = Paint.Align.RIGHT }

        canvas.drawText("अ.क्र.", 52f, y + 26f, thWhite)
        canvas.drawText("प्रॉडक्टचे नाव / तपशील", 110f, y + 26f, thWhite)
        canvas.drawText("नग", width - 210f, y + 26f, thCenter)
        canvas.drawText("दर", width - 125f, y + 26f, thRight)
        canvas.drawText("रक्कम", rightColX, y + 26f, thRight)
        y += thHeight + 8f

        // Item Rows
        val rowRightBold = Paint().apply { color = Color.parseColor("#00342B"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT; isAntiAlias = true }
        val rowRightMuted = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 18f; textAlign = Paint.Align.RIGHT; isAntiAlias = true }
        val rowCenterText = Paint().apply { color = Color.parseColor("#00342B"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; isAntiAlias = true }

        if (bill.items.isNotEmpty()) {
            bill.items.forEachIndexed { index, item ->
                val srStr = "${index + 1}"
                canvas.drawText(srStr, 52f, y + 24f, boldTextPaint)

                val nameDisplay = if (item.productName.length > 26) item.productName.take(24) + "…" else item.productName
                canvas.drawText(nameDisplay, 110f, y + 24f, boldTextPaint)

                canvas.drawText("${item.quantity}", width - 210f, y + 24f, rowCenterText)
                canvas.drawText(CurrencyFormatter.format(item.sellingPrice), width - 125f, y + 24f, rowRightMuted)
                canvas.drawText(CurrencyFormatter.format(item.lineTotal), rightColX, y + 24f, rowRightBold)

                y += 34f
                if (!item.sku.isNullOrBlank()) {
                    canvas.drawText("SKU: ${item.sku}", 110f, y + 14f, Paint(mutedPaint).apply { textSize = 15f })
                    y += 20f
                }
                y += 6f
                canvas.drawLine(36f, y, width - 36f, y, dividerPaint)
                y += 8f
            }
        } else {
            canvas.drawText("1", 52f, y + 24f, boldTextPaint)
            canvas.drawText("कापड / साडी विक्री (क्विक सेल)", 110f, y + 24f, boldTextPaint)
            canvas.drawText("1", width - 210f, y + 24f, rowCenterText)
            canvas.drawText(CurrencyFormatter.format(bill.finalAmount), width - 125f, y + 24f, rowRightMuted)
            canvas.drawText(CurrencyFormatter.format(bill.finalAmount), rightColX, y + 24f, rowRightBold)
            y += 40f
            canvas.drawLine(36f, y, width - 36f, y, dividerPaint)
            y += 8f
        }

        y += 10f

        // 5. Subtotal & Discount
        canvas.drawText("उपएकूण (Subtotal)", 52f, y + 22f, textPaint)
        canvas.drawText(CurrencyFormatter.format(bill.subtotal), rightColX, y + 22f, Paint(boldTextPaint).apply { textAlign = Paint.Align.RIGHT })
        y += 32f

        if (hasDiscount) {
            val discLabel = if (bill.discountValue > 0 && bill.discountType.name == "PERCENTAGE") {
                "विशेष डिस्काउंट (${bill.discountValue.toInt()}%)"
            } else {
                "विशेष डिस्काउंट"
            }
            val discColor = Color.parseColor("#1B7E5A")
            canvas.drawText(discLabel, 52f, y + 22f, Paint(textPaint).apply { color = discColor })
            val discAmtStr = "-${CurrencyFormatter.format(bill.discountAmount)}"
            canvas.drawText(discAmtStr, rightColX, y + 22f, Paint(boldTextPaint).apply { color = discColor; textAlign = Paint.Align.RIGHT })
            y += 34f

            // Savings Ribbon Banner
            val saveRibbon = RectF(36f, y, width - 36f, y + 36f)
            val saveBg = Paint().apply { color = Color.parseColor("#E6F4EA"); style = Paint.Style.FILL; isAntiAlias = true }
            canvas.drawRoundRect(saveRibbon, 6f, 6f, saveBg)
            val saveBorder = Paint().apply { color = Color.parseColor("#80CBA5"); strokeWidth = 1f; style = Paint.Style.STROKE; isAntiAlias = true }
            canvas.drawRoundRect(saveRibbon, 6f, 6f, saveBorder)

            val saveTextPaint = Paint().apply {
                color = Color.parseColor("#137333")
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText("★ अभिनंदन! आपण मातोश्री कलेक्शनकडून ${CurrencyFormatter.format(bill.discountAmount)} ची बचत केली ★", width / 2f, y + 24f, saveTextPaint)
            y += 44f
        }

        // Gold divider before Grand Total
        canvas.drawLine(36f, y, width - 36f, y, goldLinePaint)
        y += 14f

        // 6. Grand Total Banner
        val totalBoxH = 64f
        val totalBoxRect = RectF(36f, y, width - 36f, y + totalBoxH)
        canvas.drawRoundRect(totalBoxRect, 10f, 10f, emeraldPaint)

        val totalTitlePaint = Paint().apply {
            color = Color.parseColor("#FED65B")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val totalSubPaint = Paint().apply {
            color = Color.parseColor("#D4DCD8")
            textSize = 14f
            isAntiAlias = true
        }
        val totalAmountPaint = Paint().apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }

        canvas.drawText("एकूण देय रक्कम", 56f, y + 28f, totalTitlePaint)
        canvas.drawText("सर्व करांसहित (Net Payable)", 56f, y + 50f, totalSubPaint)
        canvas.drawText(CurrencyFormatter.format(bill.finalAmount), rightColX - 6f, y + 42f, totalAmountPaint)

        y += totalBoxH + 18f

        // 7. Payment Mode & Udhari Status
        val payMethodLabel = if (bill.paymentMethod == PaymentMethod.CASH) "कॅश (Cash)" else if (bill.paymentMethod == PaymentMethod.UPI) "यूपीआय (UPI)" else "उधारी (Credit)"
        canvas.drawText("पेमेंट पद्धत: $payMethodLabel", 52f, y + 20f, textPaint)

        val statusText = if (bill.pendingAmount > 0.01) "उधारी / पेंडिंग" else "पेमेंट पूर्ण झाले"
        val statusColor = if (bill.pendingAmount > 0.01) Color.parseColor("#B3261E") else Color.parseColor("#1B7E5A")
        val statusPaint = Paint().apply {
            color = statusColor
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        canvas.drawText("पेमेंट स्थिती: $statusText", rightColX, y + 20f, statusPaint)
        y += 32f

        if (hasPending || bill.paymentStatus != PaymentStatus.PAID) {
            val udhariBox = RectF(36f, y, width - 36f, y + 68f)
            val udhariBg = Paint().apply { color = Color.parseColor("#FDECEC"); style = Paint.Style.FILL; isAntiAlias = true }
            canvas.drawRoundRect(udhariBox, 8f, 8f, udhariBg)
            val udhariBorder = Paint().apply { color = Color.parseColor("#ECA5A5"); strokeWidth = 1f; style = Paint.Style.STROKE; isAntiAlias = true }
            canvas.drawRoundRect(udhariBox, 8f, 8f, udhariBorder)

            val paidText = "भरलेली रक्कम: ${CurrencyFormatter.format(bill.paidAmount)}"
            canvas.drawText(paidText, 52f, y + 26f, Paint(boldTextPaint).apply { color = Color.parseColor("#1B7E5A") })

            val balText = "बाकी रक्कम (उधारी): ${CurrencyFormatter.format(bill.pendingAmount)}"
            canvas.drawText(balText, rightColX, y + 26f, Paint(boldTextPaint).apply { color = Color.parseColor("#B3261E"); textAlign = Paint.Align.RIGHT })

            if (!bill.dueDate.isNullOrBlank()) {
                canvas.drawText("मुदत तारीख: ${bill.dueDate}", 52f, y + 52f, mutedPaint)
            }
            y += 80f
        }

        // 8. UPI Payment QR Code Section
        if (shouldShowQr && settings.upiId.isNotBlank()) {
            y += 6f
            val qrBoxH = 210f
            val qrBoxRect = RectF(36f, y, width - 36f, y + qrBoxH)
            val qrBoxPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
            val qrBoxBorder = Paint().apply { color = Color.parseColor("#C39B14"); strokeWidth = 1.2f; style = Paint.Style.STROKE; isAntiAlias = true }
            canvas.drawRoundRect(qrBoxRect, 12f, 12f, qrBoxPaint)
            canvas.drawRoundRect(qrBoxRect, 12f, 12f, qrBoxBorder)

            val payableUpiAmt = if (bill.pendingAmount > 0.01) bill.pendingAmount else bill.finalAmount
            val upiUri = buildUpiPaymentUri(settings.upiId, settings.upiDisplayName, payableUpiAmt, bill.billNumber)
            val upiQr = generateUpiQrBitmap(upiUri, size = 170, logoBitmap = logoBmp)

            canvas.drawBitmap(upiQr, 56f, y + 20f, null)

            val qrHeaderPaint = Paint().apply {
                color = Color.parseColor("#00342B")
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            val qrSubPaint = Paint().apply {
                color = Color.parseColor("#5A605D")
                textSize = 18f
                isAntiAlias = true
            }
            val qrAmtPaint = Paint().apply {
                color = Color.parseColor("#735C00")
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }

            canvas.drawText("स्कॅन करून UPI द्वारे पेमेंट करा", 250f, y + 60f, qrHeaderPaint)
            canvas.drawText("UPI ID: ${settings.upiId}", 250f, y + 95f, qrSubPaint)
            canvas.drawText("देय रक्कम: ${CurrencyFormatter.format(payableUpiAmt)}", 250f, y + 138f, qrAmtPaint)

            y += qrBoxH + 20f
        }

        // 9. Store Terms & Return Policy
        y += 10f
        canvas.drawLine(36f, y, width - 36f, y, dottedPaint)
        y += 24f

        val termsTitlePaint = Paint().apply { color = Color.parseColor("#00342B"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        canvas.drawText("नियम व अटी:", 52f, y, termsTitlePaint)
        y += 28f

        val termsBodyPaint = Paint().apply {
            color = Color.parseColor("#00342B")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("★ एकदा विकलेला माल परत घेतला जाणार नाही. ★", width / 2f, y, termsBodyPaint)
        y += 32f

        // 10. Signatures Section
        canvas.drawLine(36f, y, width - 36f, y, dividerPaint)
        y += 36f

        val sigLabelPaint = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 16f; isAntiAlias = true }
        canvas.drawText("ग्राहक स्वाक्षरी", 52f, y, sigLabelPaint)

        val sigAuthPaint = Paint().apply {
            color = Color.parseColor("#00342B")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        canvas.drawText("अधिकृत स्वाक्षरी", rightColX, y, sigAuthPaint)
        y += 18f
        val sigShopPaint = Paint().apply {
            color = Color.parseColor("#735C00")
            textSize = 14f
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        canvas.drawText("(मातोश्री कलेक्शन)", rightColX, y, sigShopPaint)
        y += 26f

        // 11. Thank You Footer
        canvas.drawLine(36f, y, width - 36f, y, goldLinePaint)
        y += 30f

        val footerTextPaint = Paint().apply {
            color = Color.parseColor("#00342B")
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("★ आपल्या सेवेची संधी दिल्याबद्दल धन्यवाद! पुन्हा नक्की भेट द्या! ★", width / 2f, y, footerTextPaint)
        y += 25f

        val croppedHeight = y.toInt().coerceAtMost(height)
        return Bitmap.createBitmap(bitmap, 0, 0, width, croppedHeight)
    }

    /**
     * Generates high-resolution Payment Receipt Bitmap in pure Marathi
     */
    fun generatePaymentReceiptBitmap(
        context: Context,
        transaction: UdhariTransaction,
        customerName: String,
        customerMobile: String?,
        customerVillage: String? = null,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ): Bitmap {
        val width = 720
        val height = 700
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.parseColor("#FFFDF5"))

        val goldLinePaint = Paint().apply {
            color = Color.parseColor("#C39B14")
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val dividerPaint = Paint().apply {
            color = Color.parseColor("#E0E3E1")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        val textPaint = Paint().apply {
            color = Color.parseColor("#1B1C1C")
            textSize = 24f
            isAntiAlias = true
        }
        val boldTextPaint = Paint().apply {
            color = Color.parseColor("#1B1C1C")
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val mutedPaint = Paint().apply {
            color = Color.parseColor("#5A605D")
            textSize = 20f
            isAntiAlias = true
        }
        val headerTitlePaint = Paint().apply {
            color = Color.parseColor("#00342B")
            textSize = 34f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val subheaderPaint = Paint().apply {
            color = Color.parseColor("#5A605D")
            textSize = 20f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        canvas.drawRect(0f, 0f, width.toFloat(), 8f, Paint().apply { color = Color.parseColor("#735C00") })

        var y = 40f
        val logoBmp = getShopLogoBitmap(context, settings.logoPath)
        val logoDisplaySize = 80
        val scaledShopLogo = Bitmap.createScaledBitmap(logoBmp, logoDisplaySize, logoDisplaySize, true)
        canvas.drawBitmap(scaledShopLogo, (width - logoDisplaySize) / 2f, y, null)

        y += logoDisplaySize + 25f
        canvas.drawText(settings.shopName, width / 2f, y, headerTitlePaint)
        y += 28f
        canvas.drawText(settings.address, width / 2f, y, subheaderPaint)
        y += 24f
        canvas.drawText("मोबाईल: ${settings.mobile}", width / 2f, y, subheaderPaint)

        y += 20f
        canvas.drawLine(40f, y, width - 40f, y, goldLinePaint)

        val isDebit = transaction.type == UdhariTransactionType.PURCHASE_CREDIT

        y += 35f
        val receiptBadgePaint = Paint().apply {
            color = Color.parseColor("#00342B")
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val tagTitle = if (isDebit) "— उधारी नोंद पावती —" else "— पेमेंट पावती —"
        canvas.drawText(tagTitle, width / 2f, y, receiptBadgePaint)

        // Customer Details
        y += 35f
        canvas.drawText("कस्टमरचे नाव:", 40f, y, mutedPaint)
        canvas.drawText(customerName, 220f, y, boldTextPaint)

        y += 26f
        val mobStr = customerMobile?.takeIf { it.isNotBlank() } ?: "उपलब्ध नाही"
        canvas.drawText("मोबाईल:", 40f, y, mutedPaint)
        canvas.drawText(mobStr, 220f, y, textPaint)

        if (!customerVillage.isNullOrBlank()) {
            y += 26f
            canvas.drawText("गाव:", 40f, y, mutedPaint)
            canvas.drawText(customerVillage, 220f, y, textPaint)
        }

        y += 26f
        canvas.drawText("दिनांक:", 40f, y, mutedPaint)
        canvas.drawText(transaction.date, 220f, y, textPaint)

        if (!transaction.dueDate.isNullOrBlank()) {
            y += 26f
            canvas.drawText("मुदत तारीख:", 40f, y, mutedPaint)
            canvas.drawText(transaction.dueDate!!, 220f, y, boldTextPaint.apply { color = Color.parseColor("#B3261E") })
        }

        if (!transaction.billNumber.isNullOrBlank()) {
            y += 26f
            canvas.drawText("बिल नंबर:", 40f, y, mutedPaint)
            canvas.drawText(transaction.billNumber, 220f, y, boldTextPaint.apply { color = Color.parseColor("#00342B") })
        }

        y += 25f
        canvas.drawLine(40f, y, width - 40f, y, dividerPaint)

        // Highlight Amount Box
        y += 35f
        val amountBoxRect = RectF(40f, y, width - 40f, y + 100f)
        val amountBoxBg = Paint().apply {
            color = if (isDebit) Color.parseColor("#FFF3E0") else Color.parseColor("#E6F4EA")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(amountBoxRect, 12f, 12f, amountBoxBg)

        val rcvLabelPaint = Paint().apply {
            color = if (isDebit) Color.parseColor("#E65100") else Color.parseColor("#137333")
            textSize = 22f
            isAntiAlias = true
        }
        val labelStr = if (isDebit) "नोंद केलेली उधारी:" else "मिळालेले पेमेंट:"
        canvas.drawText(labelStr, 60f, y + 42f, rcvLabelPaint)

        val rcvAmtPaint = Paint().apply {
            color = if (isDebit) Color.parseColor("#B3261E") else Color.parseColor("#137333")
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(CurrencyFormatter.format(transaction.amount), width - 60f, y + 55f, rcvAmtPaint)

        val payMethodText = if (transaction.paymentMethod == PaymentMethod.CASH) "कॅश" else "यूपीआय"
        val methodLabel = if (isDebit) "प्रकार: जुनी बाकी नोंद" else "पेमेंट पद्धत: $payMethodText"
        canvas.drawText(methodLabel, 60f, y + 80f, Paint(mutedPaint).apply { textSize = 18f })

        y += 130f

        // Previous & Remaining Balance
        if (transaction.previousOutstanding > 0.0 || transaction.remainingOutstanding > 0.0) {
            canvas.drawText("मागील बाकी:", 40f, y, textPaint)
            val prevStr = CurrencyFormatter.format(transaction.previousOutstanding)
            val prevWidth = boldTextPaint.measureText(prevStr)
            canvas.drawText(prevStr, width - 40f - prevWidth, y, boldTextPaint)

            y += 30f
            val remPaint = Paint(boldTextPaint).apply {
                color = if (transaction.remainingOutstanding > 0.01) Color.parseColor("#B3261E") else Color.parseColor("#137333")
            }
            val remLabel = "उधारीची बाकी रक्कम:"
            canvas.drawText(remLabel, 40f, y, remPaint)
            val remStr = CurrencyFormatter.format(transaction.remainingOutstanding)
            val remWidth = remPaint.measureText(remStr)
            canvas.drawText(remStr, width - 40f - remWidth, y, remPaint)

            y += 25f
            canvas.drawLine(40f, y, width - 40f, y, dividerPaint)
        }

        // Footer
        y += 35f
        val footerPaint = Paint().apply {
            color = Color.parseColor("#5A605D")
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("★ धन्यवाद! ★", width / 2f, y, footerPaint)

        return bitmap
    }

    /**
     * Shares high-res bill receipt JPG through Android Intent and FileProvider with direct WhatsApp chat integration.
     */
    fun shareBillJpg(
        context: Context,
        bill: Bill,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ) {
        try {
            val bitmap = generateBillReceiptBitmap(context, bill, settings)

            val cacheDir = File(context.cacheDir, "receipts").apply { mkdirs() }
            val imageFile = File(cacheDir, "Bill_${bill.billNumber}.jpg")
            val fos = FileOutputStream(imageFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            fos.flush()
            fos.close()

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )

            val customerName = bill.customerName ?: "कस्टमर"
            val pendingText = if (bill.pendingAmount > 0.01) "बाकी रक्कम (उधारी): ${CurrencyFormatter.format(bill.pendingAmount)}" else "पेमेंट स्थिती: पूर्ण भरणा झाला"
            val shareBody = """
नमस्कार $customerName,

मातोश्री कलेक्शन कडून आपले बिल तपशील:
दिनांक: ${bill.billDate}
एकूण देय रक्कम: ${CurrencyFormatter.format(bill.finalAmount)}
भरलेली रक्कम: ${CurrencyFormatter.format(bill.paidAmount)}
$pendingText

तुमच्या बिलाची प्रिंट शॉपमधून घ्यावी.

धन्यवाद!
पुन्हा नक्की भेट द्या!

दुकान: ${settings.shopName}, ${settings.address}
संपर्क: ${settings.mobile}
            """.trimIndent()

            openDirectWhatsAppChat(context, bill.customerMobile, shareBody, uri)
        } catch (e: Exception) {
            Toast.makeText(context, "बिल शेअर करताना त्रुटी आली: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Shares payment receipt JPG image through Android Intent / direct WhatsApp
     */
    fun sharePaymentReceiptJpg(
        context: Context,
        transaction: UdhariTransaction,
        customerName: String,
        customerMobile: String?,
        customerVillage: String? = null,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ) {
        try {
            val bitmap = generatePaymentReceiptBitmap(context, transaction, customerName, customerMobile, customerVillage, settings)

            val cacheDir = File(context.cacheDir, "receipts").apply { mkdirs() }
            val imageFile = File(cacheDir, "Receipt_${transaction.id}.jpg")
            val fos = FileOutputStream(imageFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            fos.flush()
            fos.close()

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )

            val isDebit = transaction.type == UdhariTransactionType.PURCHASE_CREDIT
            val payMethodText = if (transaction.paymentMethod == PaymentMethod.CASH) "कॅश" else "यूपीआय"
            val shareBody = if (isDebit) {
                """
नमस्कार $customerName,

उधारी नोंद पावती

कस्टमरचे नाव: $customerName
नोंद केलेली उधारी: ${CurrencyFormatter.format(transaction.amount)}
तारीख: ${transaction.date}
${if (!transaction.dueDate.isNullOrBlank()) "मुदत तारीख: ${transaction.dueDate}\n" else ""}उधारीची बाकी रक्कम: ${CurrencyFormatter.format(transaction.remainingOutstanding)}

धन्यवाद!
मातोश्री कलेक्शन
दुकान: ${settings.shopName}, ${settings.address}
संपर्क: ${settings.mobile}
                """.trimIndent()
            } else {
                """
नमस्कार $customerName,

पेमेंट पावती

कस्टमरचे नाव: $customerName
मिळालेले पेमेंट: ${CurrencyFormatter.format(transaction.amount)}
पेमेंट पद्धत: $payMethodText
दिनांक: ${transaction.date}
उधारीची बाकी रक्कम: ${CurrencyFormatter.format(transaction.remainingOutstanding)}

धन्यवाद!
मातोश्री कलेक्शन
दुकान: ${settings.shopName}, ${settings.address}
संपर्क: ${settings.mobile}
                """.trimIndent()
            }

            openDirectWhatsAppChat(context, customerMobile, shareBody, uri)
        } catch (e: Exception) {
            Toast.makeText(context, "पावती शेअर करताना त्रुटी आली: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Generates a high-definition Customer Udhari Account Statement Bitmap
     */
    fun generateCustomerUdhariStatementBitmap(
        context: Context,
        customerName: String,
        customerMobile: String?,
        customerVillage: String? = null,
        totalOutstanding: Double,
        lastPaymentAmount: Double? = null,
        lastPaymentDate: String? = null,
        pendingBillsCount: Int = 1,
        dueDate: String? = null,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ): Bitmap {
        val width = 760
        var estimatedHeight = 920
        if (settings.upiId.isNotBlank() && totalOutstanding > 0.01) {
            estimatedHeight += 260
        }
        if (lastPaymentAmount != null && lastPaymentDate != null) {
            estimatedHeight += 70
        }

        val bitmap = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Warm Ivory background
        canvas.drawColor(Color.parseColor("#FFFDF5"))

        val leftX = 36f
        val rightX = width - 36f
        var y = 28f

        // 1. Luxury Emerald Header Card
        val headerCardH = 200f
        val headerRect = RectF(leftX, y, rightX, y + headerCardH)
        val emeraldBg = Paint().apply { color = Color.parseColor("#00342B"); style = Paint.Style.FILL; isAntiAlias = true }
        val goldBorder = Paint().apply { color = Color.parseColor("#C39B14"); strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true }
        canvas.drawRoundRect(headerRect, 16f, 16f, emeraldBg)
        canvas.drawRoundRect(headerRect, 16f, 16f, goldBorder)

        val logoSize = 90
        val shopLogo = getShopLogoBitmap(context, settings.logoPath)
        val scaledLogo = Bitmap.createScaledBitmap(shopLogo, logoSize, logoSize, true)
        canvas.drawBitmap(scaledLogo, (width - logoSize) / 2f, y + 14f, null)

        val activeShopName = settings.shopName.ifBlank { "मातोश्री कलेक्शन" }
        val goldShopNamePaint = Paint().apply {
            color = Color.parseColor("#FED65B")
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText(activeShopName, width / 2f, y + 138f, goldShopNamePaint)

        val ivorySubtitlePaint = Paint().apply {
            color = Color.parseColor("#FFFDF5")
            textSize = 16f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val shopAddr = settings.address.ifBlank { "कोंढारकी, पंढरपूर" }
        val shopMob = settings.mobile.ifBlank { "8208610261" }
        canvas.drawText("$shopAddr  •  मोबाईल: $shopMob", width / 2f, y + 170f, ivorySubtitlePaint)
        y += headerCardH + 18f

        // 2. Banner Card
        val bannerH = 46f
        val bannerRect = RectF(leftX, y, rightX, y + bannerH)
        canvas.drawRoundRect(bannerRect, 10f, 10f, Paint().apply { color = Color.parseColor("#FFF0C2"); style = Paint.Style.FILL; isAntiAlias = true })
        canvas.drawRoundRect(bannerRect, 10f, 10f, Paint().apply { color = Color.parseColor("#C39B14"); strokeWidth = 1.5f; style = Paint.Style.STROKE; isAntiAlias = true })

        val bannerTextPaint = Paint().apply {
            color = Color.parseColor("#00342B")
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("★ उधारी बाकी हिशोब पत्रक (CREDIT STATEMENT) ★", width / 2f, y + 31f, bannerTextPaint)
        y += bannerH + 16f

        // 3. Customer Info Luxury Card
        val custCardH = if (!customerVillage.isNullOrBlank()) 118f else 96f
        val custRect = RectF(leftX, y, rightX, y + custCardH)
        canvas.drawRoundRect(custRect, 12f, 12f, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true })
        canvas.drawRoundRect(custRect, 12f, 12f, Paint().apply { color = Color.parseColor("#E0E3E1"); strokeWidth = 1.2f; style = Paint.Style.STROKE; isAntiAlias = true })

        val labelPaint = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 17f; isAntiAlias = true }
        val boldValuePaint = Paint().apply { color = Color.parseColor("#00342B"); textSize = 22f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val regularValuePaint = Paint().apply { color = Color.parseColor("#1B1C1C"); textSize = 18f; isAntiAlias = true }

        var custY = y + 32f
        canvas.drawText("नाव:", leftX + 16f, custY, labelPaint)
        canvas.drawText(customerName, leftX + 70f, custY, boldValuePaint)

        val dateLabel = "दिनांक: ${DateUtils.todayDate()}"
        val datePaint = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 16f; textAlign = Paint.Align.RIGHT; isAntiAlias = true }
        canvas.drawText(dateLabel, rightX - 16f, custY, datePaint)

        custY += 30f
        val mobStr = customerMobile?.takeIf { it.isNotBlank() } ?: "उपलब्ध नाही"
        canvas.drawText("मोबाईल:", leftX + 16f, custY, labelPaint)
        canvas.drawText(mobStr, leftX + 90f, custY, regularValuePaint)

        if (!customerVillage.isNullOrBlank()) {
            custY += 28f
            canvas.drawText("गाव / पत्ता:", leftX + 16f, custY, labelPaint)
            canvas.drawText(customerVillage, leftX + 115f, custY, regularValuePaint)
        }
        y += custCardH + 18f

        // 4. Outstanding Amount Highlight Card (Luxury Burgundy/Crimson Soft Card)
        val outCardH = 120f
        val outRect = RectF(leftX, y, rightX, y + outCardH)
        canvas.drawRoundRect(outRect, 14f, 14f, Paint().apply { color = Color.parseColor("#FCE8E6"); style = Paint.Style.FILL; isAntiAlias = true })
        canvas.drawRoundRect(outRect, 14f, 14f, Paint().apply { color = Color.parseColor("#E57373"); strokeWidth = 1.8f; style = Paint.Style.STROKE; isAntiAlias = true })

        val outLabelPaint = Paint().apply {
            color = Color.parseColor("#B3261E")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        canvas.drawText("एकूण उधारी बाकी रक्कम (Due Amount)", leftX + 22f, y + 42f, outLabelPaint)

        val outAmtPaint = Paint().apply {
            color = Color.parseColor("#B3261E")
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        canvas.drawText(CurrencyFormatter.format(totalOutstanding), rightX - 22f, y + 54f, outAmtPaint)

        val pendingInfo = "$pendingBillsCount उधारी बिले" + if (!dueDate.isNullOrBlank()) " • अंतिम मुदत: $dueDate" else ""
        canvas.drawText(pendingInfo, leftX + 22f, y + 90f, Paint().apply { color = Color.parseColor("#5A605D"); textSize = 17f; isAntiAlias = true })

        y += outCardH + 16f

        // 5. Last Payment Row (if exists)
        if (lastPaymentAmount != null && lastPaymentDate != null) {
            val lpCardH = 50f
            val lpRect = RectF(leftX, y, rightX, y + lpCardH)
            canvas.drawRoundRect(lpRect, 10f, 10f, Paint().apply { color = Color.parseColor("#E8F5E9"); style = Paint.Style.FILL; isAntiAlias = true })
            canvas.drawRoundRect(lpRect, 10f, 10f, Paint().apply { color = Color.parseColor("#81C784"); strokeWidth = 1.2f; style = Paint.Style.STROKE; isAntiAlias = true })

            val lpTextPaint = Paint().apply { color = Color.parseColor("#1B5E20"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            canvas.drawText("✓ शेवटचे जमा पेमेंट: ${CurrencyFormatter.format(lastPaymentAmount)}", leftX + 20f, y + 32f, lpTextPaint)
            val lpDatePaint = Paint().apply { color = Color.parseColor("#2E7D32"); textSize = 16f; textAlign = Paint.Align.RIGHT; isAntiAlias = true }
            canvas.drawText("दिनांक: $lastPaymentDate", rightX - 20f, y + 32f, lpDatePaint)

            y += lpCardH + 16f
        }

        // 6. UPI QR Code Box
        if (settings.upiId.isNotBlank() && totalOutstanding > 0.01) {
            val qrBoxH = 230f
            val qrBoxRect = RectF(leftX, y, rightX, y + qrBoxH)
            canvas.drawRoundRect(qrBoxRect, 14f, 14f, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true })
            canvas.drawRoundRect(qrBoxRect, 14f, 14f, Paint().apply { color = Color.parseColor("#C39B14"); strokeWidth = 1.8f; style = Paint.Style.STROKE; isAntiAlias = true })

            val upiUri = buildUpiPaymentUri(settings.upiId, settings.upiDisplayName, totalOutstanding, "")
            val upiQr = generateUpiQrBitmap(upiUri, size = 180, logoBitmap = shopLogo)
            canvas.drawBitmap(upiQr, leftX + 24f, y + 25f, null)

            val qrHeaderPaint = Paint().apply {
                color = Color.parseColor("#00342B")
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("यूपीआयने पेमेंट करण्यासाठी स्कॅन करा", leftX + 230f, y + 55f, qrHeaderPaint)

            val qrSubPaint = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 17f; isAntiAlias = true }
            canvas.drawText("UPI ID: ${settings.upiId}", leftX + 230f, y + 90f, qrSubPaint)

            val qrPayAmtPaint = Paint().apply {
                color = Color.parseColor("#735C00")
                textSize = 25f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText("देय रक्कम: ${CurrencyFormatter.format(totalOutstanding)}", leftX + 230f, y + 130f, qrPayAmtPaint)

            val qrAppListPaint = Paint().apply {
                color = Color.parseColor("#388E3C")
                textSize = 15f
                isAntiAlias = true
            }
            canvas.drawText("Google Pay • PhonePe • Paytm द्वारे स्कॅन करा", leftX + 230f, y + 165f, qrAppListPaint)

            y += qrBoxH + 16f
        }

        // 7. Polite Courtesy Banner
        val alertH = 46f
        val alertRect = RectF(leftX, y, rightX, y + alertH)
        canvas.drawRoundRect(alertRect, 8f, 8f, Paint().apply { color = Color.parseColor("#FFF3CD"); style = Paint.Style.FILL; isAntiAlias = true })
        canvas.drawRoundRect(alertRect, 8f, 8f, Paint().apply { color = Color.parseColor("#C39B14"); strokeWidth = 1.2f; style = Paint.Style.STROKE; isAntiAlias = true })
        val alertTextPaint = Paint().apply {
            color = Color.parseColor("#856404")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("⚠️ कृपया लवकरात लवकर उधारी जमा करून सहकार्य करावे ⚠️", width / 2f, y + 29f, alertTextPaint)
        y += alertH + 26f

        // 8. Sign & Footer
        canvas.drawLine(leftX, y, rightX, y, Paint().apply { color = Color.parseColor("#D0D5D2"); strokeWidth = 1.2f; isAntiAlias = true })
        y += 28f

        val footLeft = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 15f; isAntiAlias = true }
        val footRight = Paint().apply { color = Color.parseColor("#00342B"); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT; isAntiAlias = true }
        canvas.drawText("अहवाल तारीख: ${DateUtils.todayDate()}", leftX, y, footLeft)
        canvas.drawText("अधिकृत स्वाक्षरी: ________________", rightX, y, footRight)
        y += 24f
        canvas.drawText("($activeShopName, $shopAddr)", rightX, y, Paint(footRight).apply { textSize = 13f; typeface = Typeface.DEFAULT })
        y += 36f

        val footCenter = Paint().apply {
            color = Color.parseColor("#735C00")
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("★ धन्यवाद! पुन्हा भेट द्या ★", width / 2f, y, footCenter)
        y += 32f

        val finalH = y.toInt().coerceIn(100, bitmap.height)
        return Bitmap.createBitmap(bitmap, 0, 0, width, finalH)
    }

    /**
     * Shares customer Udhari statement image directly to WhatsApp / Android share sheet
     */
    fun shareCustomerUdhariStatementJpg(
        context: Context,
        customerName: String,
        customerMobile: String?,
        customerVillage: String? = null,
        totalOutstanding: Double,
        lastPaymentAmount: Double? = null,
        lastPaymentDate: String? = null,
        pendingBillsCount: Int = 1,
        dueDate: String? = null,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ) {
        try {
            val bitmap = generateCustomerUdhariStatementBitmap(
                context = context,
                customerName = customerName,
                customerMobile = customerMobile,
                customerVillage = customerVillage,
                totalOutstanding = totalOutstanding,
                lastPaymentAmount = lastPaymentAmount,
                lastPaymentDate = lastPaymentDate,
                pendingBillsCount = pendingBillsCount,
                dueDate = dueDate,
                settings = settings
            )

            val cacheDir = File(context.cacheDir, "receipts").apply { mkdirs() }
            val cleanName = customerName.replace("[^a-zA-Z0-9]".toRegex(), "_")
            val imageFile = File(cacheDir, "Statement_${cleanName}.jpg")
            val fos = FileOutputStream(imageFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            fos.flush()
            fos.close()

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )

            val amountFormatted = CurrencyFormatter.format(totalOutstanding)
            val shareMessage = """
नमस्कार $customerName,

मातोश्री कलेक्शनकडील आपल्या उधारीची
बाकी रक्कम $amountFormatted आहे.

कृपया पेमेंट करण्याची सोय करावी.

धन्यवाद!
मातोश्री कलेक्शन
दुकान: ${settings.shopName}, ${settings.address}
संपर्क: ${settings.mobile}
            """.trimIndent()

            openDirectWhatsAppChat(context, customerMobile, shareMessage, uri)
        } catch (e: Exception) {
            Toast.makeText(context, "खाते उतारा शेअर करताना त्रुटी आली: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Centralized phone-number normalization utility for Indian numbers.
     * Safely handles:
     * - 9876543210
     * - +919876543210
     * - 91 9876543210
     * - +91 9876543210
     * - 98765 43210
     * - 98765-43210
     * - 09876543210
     * Returns clean 10-digit string "9876543210" or null if invalid.
     */
    fun normalizeIndianMobile(raw: String?): String? {
        val clean10 = PhoneUtils.clean10DigitMobileInput(raw)
        return if (clean10.length == 10) clean10 else null
    }

    fun cleanIndian10DigitMobile(raw: String?): String? = normalizeIndianMobile(raw)

    fun isValidIndianPhoneNumber(raw: String?): Boolean = normalizeIndianMobile(raw) != null

    /**
     * Directly opens WhatsApp chat for a customer with pre-filled message and optional attached image.
     * When customer has a valid 10-digit number, opens DIRECTLY to that customer's chat without opening general chat list.
     */
    fun openDirectWhatsAppChat(
        context: Context,
        mobile: String?,
        message: String,
        imageUri: Uri? = null
    ) {
        val clean10 = normalizeIndianMobile(mobile)
        val hasRawMobile = !mobile.isNullOrBlank()

        if (hasRawMobile && clean10 == null) {
            Toast.makeText(context, "कस्टमरचा मोबाईल नंबर उपलब्ध नाही किंवा चुकीचा आहे.", Toast.LENGTH_SHORT).show()
        }

        // 1. When imageUri is present (JPG Bill / Statement / Receipt sharing)
        if (imageUri != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                putExtra(Intent.EXTRA_TEXT, message)
                if (clean10 != null) {
                    putExtra("jid", "91$clean10@s.whatsapp.net")
                    putExtra(Intent.EXTRA_PHONE_NUMBER, "91$clean10")
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.grantUriPermission("com.whatsapp", imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {}
            try {
                context.grantUriPermission("com.whatsapp.w4b", imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {}

            try {
                shareIntent.setPackage("com.whatsapp")
                context.startActivity(shareIntent)
                return
            } catch (e: Exception) {
                try {
                    shareIntent.setPackage("com.whatsapp.w4b") // WhatsApp Business
                    context.startActivity(shareIntent)
                    return
                } catch (e2: Exception) {
                    try {
                        shareIntent.setPackage(null)
                        val chooser = Intent.createChooser(shareIntent, "पावती शेअर करा (Share via)")
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(chooser)
                        return
                    } catch (e3: Exception) {
                        Toast.makeText(context, "शेअर करण्यासाठी ॲप उपलब्ध नाही.", Toast.LENGTH_SHORT).show()
                        return
                    }
                }
            }
        }

        // 2. Text-only message (e.g. Udhari Reminder / Chat)
        val encodedMessage = try {
            URLEncoder.encode(message, StandardCharsets.UTF_8.toString())
        } catch (e: Exception) {
            ""
        }

        if (clean10 != null) {
            val phoneFormatted = "91$clean10"
            val waUri = Uri.parse("https://api.whatsapp.com/send?phone=$phoneFormatted&text=$encodedMessage")
            val directChatIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                directChatIntent.setPackage("com.whatsapp")
                context.startActivity(directChatIntent)
                return
            } catch (e: Exception) {
                try {
                    directChatIntent.setPackage("com.whatsapp.w4b") // WhatsApp Business
                    context.startActivity(directChatIntent)
                    return
                } catch (e2: Exception) {
                    try {
                        // Fallback to whatsapp scheme
                        val waSchemeUri = Uri.parse("whatsapp://send?phone=$phoneFormatted&text=$encodedMessage")
                        val schemeIntent = Intent(Intent.ACTION_VIEW, waSchemeUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(schemeIntent)
                        return
                    } catch (e3: Exception) {
                        try {
                            directChatIntent.setPackage(null)
                            context.startActivity(directChatIntent)
                            return
                        } catch (e4: Exception) {
                            Toast.makeText(context, "व्हॉट्सॲप या डिव्हाइसवर उपलब्ध नाही.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // 3. Fallback when no number or direct launch fails: General Share Intent
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "शेअर करा (Share via)")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * Prints bill using Android PrintManager
     */
    fun printBillSystem(
        context: Context,
        bill: Bill,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            Toast.makeText(context, "Printing not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        val jobName = "Invoice_${bill.billNumber}"
        val adapter = object : PrintDocumentAdapter() {
            private var pdfDocument: PrintedPdfDocument? = null

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                pdfDocument = PrintedPdfDocument(context, newAttributes ?: PrintAttributes.Builder().build())
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }

                val info = PrintDocumentInfo.Builder("Bill_${bill.billNumber}.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build()

                callback?.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                val page = pdfDocument?.startPage(0) ?: return
                val canvas = page.canvas

                val receiptBmp = generateBillReceiptBitmap(context, bill, settings)
                val scale = (page.info.pageWidth.toFloat() / receiptBmp.width).coerceAtMost(page.info.pageHeight.toFloat() / receiptBmp.height)

                val scaledBmp = Bitmap.createScaledBitmap(
                    receiptBmp,
                    (receiptBmp.width * scale).toInt(),
                    (receiptBmp.height * scale).toInt(),
                    true
                )

                canvas.drawBitmap(scaledBmp, 0f, 0f, null)
                pdfDocument?.finishPage(page)

                try {
                    val out: OutputStream = FileOutputStream(destination?.fileDescriptor)
                    pdfDocument?.writeTo(out)
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                } finally {
                    pdfDocument?.close()
                    pdfDocument = null
                }
            }
        }

        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
    }

    /**
     * Prints an Analytics Report using Android PrintManager (WiFi / USB / Save as PDF)
     */
    fun printAnalyticsReportSystem(
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
        topProducts: List<com.matoshree.shopmanager.ui.screens.analytics.TopProductStat>,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            Toast.makeText(context, "Printing not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        val jobName = "Report_${periodLabel}_${System.currentTimeMillis()}"
        val adapter = object : PrintDocumentAdapter() {
            private var pdfDocument: PrintedPdfDocument? = null

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                pdfDocument = PrintedPdfDocument(context, newAttributes ?: PrintAttributes.Builder().build())
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }

                val info = PrintDocumentInfo.Builder("Analytics_Report_${periodLabel}.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build()

                callback?.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                val page = pdfDocument?.startPage(0) ?: return
                val canvas = page.canvas

                val reportBmp = renderColorAnalyticsReportBitmap(
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
                val scale = (page.info.pageWidth.toFloat() / reportBmp.width).coerceAtMost(page.info.pageHeight.toFloat() / reportBmp.height)

                val scaledBmp = Bitmap.createScaledBitmap(
                    reportBmp,
                    (reportBmp.width * scale).toInt(),
                    (reportBmp.height * scale).toInt(),
                    true
                )

                canvas.drawBitmap(scaledBmp, (page.info.pageWidth - scaledBmp.width) / 2f, 0f, null)
                pdfDocument?.finishPage(page)

                try {
                    val out: OutputStream = FileOutputStream(destination?.fileDescriptor)
                    pdfDocument?.writeTo(out)
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                } finally {
                    pdfDocument?.close()
                    pdfDocument = null
                }
            }
        }

        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
    }

    /**
     * Renders a high-resolution, luxury boutique full-color bitmap for an Analytics Report (Day, Week, Month, etc.).
     */
    fun renderColorAnalyticsReportBitmap(
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
        topProducts: List<com.matoshree.shopmanager.ui.screens.analytics.TopProductStat>,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ): Bitmap {
        val width = 760
        val itemsCount = topProducts.size.coerceAtMost(5)
        val estimatedHeight = 980 + (itemsCount * 52)

        val bmp = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#FFFDF5"))

        var y = 28f
        val leftX = 36f
        val rightX = width - 36f

        // 1. Luxury Emerald Header Card
        val headerCardH = 205f
        val headerRect = RectF(leftX, y, rightX, y + headerCardH)
        val emeraldBg = Paint().apply { color = Color.parseColor("#00342B"); style = Paint.Style.FILL; isAntiAlias = true }
        val goldBorder = Paint().apply { color = Color.parseColor("#C39B14"); strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true }
        canvas.drawRoundRect(headerRect, 16f, 16f, emeraldBg)
        canvas.drawRoundRect(headerRect, 16f, 16f, goldBorder)

        val logoSize = 95
        val shopLogo = getShopLogoBitmap(context, settings.logoPath)
        val scaledLogo = Bitmap.createScaledBitmap(shopLogo, logoSize, logoSize, true)
        canvas.drawBitmap(scaledLogo, (width - logoSize) / 2f, y + 14f, null)

        val activeShopName = settings.shopName.ifBlank { "मातोश्री कलेक्शन" }
        val goldShopNamePaint = Paint().apply {
            color = Color.parseColor("#FED65B")
            textSize = 33f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText(activeShopName, width / 2f, y + 140f, goldShopNamePaint)

        val ivorySubtitlePaint = Paint().apply {
            color = Color.parseColor("#FFFDF5")
            textSize = 17f
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val shopAddr = settings.address.ifBlank { "कोंढारकी, पंढरपूर" }
        val shopMob = settings.mobile.ifBlank { "8208610261" }
        canvas.drawText("$shopAddr  •  मोबाईल: $shopMob", width / 2f, y + 172f, ivorySubtitlePaint)
        y += headerCardH + 18f

        // 2. Period Banner
        val bannerH = 48f
        val bannerRect = RectF(leftX, y, rightX, y + bannerH)
        canvas.drawRoundRect(bannerRect, 10f, 10f, Paint().apply { color = Color.parseColor("#FFF0C2"); style = Paint.Style.FILL; isAntiAlias = true })
        canvas.drawRoundRect(bannerRect, 10f, 10f, Paint().apply { color = Color.parseColor("#C39B14"); strokeWidth = 1.5f; style = Paint.Style.STROKE; isAntiAlias = true })

        val bannerTextPaint = Paint().apply {
            color = Color.parseColor("#00342B")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("★ $periodLabel ★", width / 2f, y + 32f, bannerTextPaint)
        y += bannerH + 18f

        // 3. KPI 4-Card Grid
        val gridGap = 14f
        val colW = (rightX - leftX - gridGap) / 2f
        val rowH = 92f

        fun drawKpi(x: Float, yPos: Float, title: String, value: String, bgHex: String, valColorHex: String, borderHex: String) {
            val r = RectF(x, yPos, x + colW, yPos + rowH)
            canvas.drawRoundRect(r, 12f, 12f, Paint().apply { color = Color.parseColor(bgHex); style = Paint.Style.FILL; isAntiAlias = true })
            canvas.drawRoundRect(r, 12f, 12f, Paint().apply { color = Color.parseColor(borderHex); strokeWidth = 1.2f; style = Paint.Style.STROKE; isAntiAlias = true })
            canvas.drawText(title, x + 16f, yPos + 30f, Paint().apply { color = Color.parseColor("#5A605D"); textSize = 15f; isAntiAlias = true })
            canvas.drawText(value, x + 16f, yPos + 68f, Paint().apply { color = Color.parseColor(valColorHex); textSize = 23f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true })
        }

        drawKpi(leftX, y, "एकूण विक्री (Revenue)", CurrencyFormatter.format(totalSales), "#E8F5E9", "#00342B", "#A5D6A7")
        val netColor = if (netProfit >= 0) "#1B5E20" else "#C62828"
        val netBg = if (netProfit >= 0) "#E8F5E9" else "#FFEBEE"
        val netBorder = if (netProfit >= 0) "#C8E6C9" else "#FFCDD2"
        drawKpi(leftX + colW + gridGap, y, "निव्वळ नफा (Net Profit)", CurrencyFormatter.format(netProfit), netBg, netColor, netBorder)
        y += rowH + gridGap

        drawKpi(leftX, y, "दुकानाचे खर्च (Expenses)", CurrencyFormatter.format(totalExpenses), "#FFEBEE", "#C62828", "#FFCDD2")
        drawKpi(leftX + colW + gridGap, y, "बिले संख्या / सरासरी", "$totalBillsCount बिले (${CurrencyFormatter.format(avgBillValue)})", "#FFF8E1", "#00342B", "#FFE082")
        y += rowH + 20f

        // 4. Payment Modes Breakdown Card
        val payBoxH = 105f
        val payRect = RectF(leftX, y, rightX, y + payBoxH)
        canvas.drawRoundRect(payRect, 12f, 12f, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true })
        canvas.drawRoundRect(payRect, 12f, 12f, Paint().apply { color = Color.parseColor("#E0E3E1"); strokeWidth = 1.2f; style = Paint.Style.STROKE; isAntiAlias = true })

        val payTitlePaint = Paint().apply { color = Color.parseColor("#00342B"); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        canvas.drawText("पेमेंट पद्धत वर्गीकरण (Payment Modes)", leftX + 16f, y + 26f, payTitlePaint)

        val pColW = (rightX - leftX) / 3f
        val pY = y + 68f

        fun drawPay(idx: Int, label: String, amount: Double, colorHex: String) {
            val px = leftX + (idx * pColW) + 16f
            canvas.drawCircle(px + 4f, pY - 8f, 6f, Paint().apply { color = Color.parseColor(colorHex); style = Paint.Style.FILL; isAntiAlias = true })
            canvas.drawText(label, px + 18f, pY - 3f, Paint().apply { color = Color.parseColor("#5A605D"); textSize = 15f; isAntiAlias = true })
            canvas.drawText(CurrencyFormatter.format(amount), px + 18f, pY + 20f, Paint().apply { color = Color.parseColor("#1B1C1C"); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true })
        }

        drawPay(0, "कॅश जमा", cashAmount, "#00897B")
        drawPay(1, "यूपीआय जमा", upiAmount, "#1E88E5")
        drawPay(2, "इतर / उधारी", otherAmount, "#FB8C00")
        y += payBoxH + 20f

        // 5. Top 5 Products Table
        if (topProducts.isNotEmpty()) {
            val thH = 38f
            val thRect = RectF(leftX, y, rightX, y + thH)
            canvas.drawRoundRect(thRect, 8f, 8f, emeraldBg)

            val thWhite = Paint().apply { color = Color.WHITE; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val thRight = Paint(thWhite).apply { textAlign = Paint.Align.RIGHT }
            val thCenter = Paint(thWhite).apply { textAlign = Paint.Align.CENTER }

            canvas.drawText("अ.क्र.", leftX + 16f, y + 25f, thWhite)
            canvas.drawText("सर्वाधिक विक्री झालेले प्रॉडक्ट्स", leftX + 70f, y + 25f, thWhite)
            canvas.drawText("नग", rightX - 160f, y + 25f, thCenter)
            canvas.drawText("एकूण विक्री", rightX - 16f, y + 25f, thRight)
            y += thH + 6f

            topProducts.take(5).forEachIndexed { i, p ->
                val rowRect = RectF(leftX, y, rightX, y + 40f)
                val rowBg = if (i % 2 == 0) Color.WHITE else Color.parseColor("#F7F9F8")
                canvas.drawRoundRect(rowRect, 6f, 6f, Paint().apply { color = rowBg; style = Paint.Style.FILL; isAntiAlias = true })

                val cellPaint = Paint().apply { color = Color.parseColor("#1B1C1C"); textSize = 15.5f; isAntiAlias = true }
                val cellRight = Paint().apply { color = Color.parseColor("#00342B"); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT; isAntiAlias = true }
                val cellCenter = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 15.5f; textAlign = Paint.Align.CENTER; isAntiAlias = true }

                canvas.drawText("${i + 1}.", leftX + 16f, y + 26f, cellPaint)
                val maxLen = 28
                val pName = if (p.name.length > maxLen) p.name.take(maxLen - 1) + "…" else p.name
                canvas.drawText(pName, leftX + 70f, y + 26f, cellPaint)
                canvas.drawText("${p.quantity}", rightX - 160f, y + 26f, cellCenter)
                canvas.drawText(CurrencyFormatter.format(p.revenue), rightX - 16f, y + 26f, cellRight)
                y += 44f
            }
            y += 12f
        }

        // 6. Footer & Authorization
        y += 8f
        canvas.drawLine(leftX, y, rightX, y, Paint().apply { color = Color.parseColor("#D0D5D2"); strokeWidth = 1.2f; isAntiAlias = true })
        y += 34f

        val footLeft = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 14.5f; isAntiAlias = true }
        val footRight = Paint().apply { color = Color.parseColor("#00342B"); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT; isAntiAlias = true }

        val printTime = DateUtils.formatForDisplay(DateUtils.nowIso())
        canvas.drawText("अहवाल निर्मिती: $printTime", leftX, y, footLeft)
        canvas.drawText("अधिकृत स्वाक्षरी: ________________", rightX, y, footRight)
        y += 24f
        canvas.drawText("(मातोश्री कलेक्शन, पंढरपूर)", rightX, y, Paint(footRight).apply { textSize = 13.5f; typeface = Typeface.DEFAULT })
        y += 34f

        val finalH = y.toInt().coerceAtMost(estimatedHeight).coerceAtLeast(100)
        return Bitmap.createBitmap(bmp, 0, 0, width, finalH)
    }

    /**
     * Shares a full-color styled Analytics Report image via WhatsApp or Android Share Sheet.
     */
    fun shareColorAnalyticsReport(
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
        topProducts: List<com.matoshree.shopmanager.ui.screens.analytics.TopProductStat>,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ) {
        try {
            val bmp = renderColorAnalyticsReportBitmap(
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

            val cacheDir = File(context.cacheDir, "receipts").apply { mkdirs() }
            val cleanPeriod = periodLabel.replace("[^a-zA-Z0-9]".toRegex(), "_").take(20)
            val imageFile = File(cacheDir, "Report_${cleanPeriod}_${System.currentTimeMillis()}.jpg")
            val fos = FileOutputStream(imageFile)
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            fos.flush()
            fos.close()

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "मातोश्री कलेक्शन - $periodLabel अहवाल")
                putExtra(
                    Intent.EXTRA_TEXT,
                    """
★ मातोश्री कलेक्शन, पंढरपूर ★
व्यवसाय अहवाल: $periodLabel
एकूण विक्री: ${CurrencyFormatter.format(totalSales)} (${totalBillsCount} बिले)
निव्वळ नफा: ${CurrencyFormatter.format(netProfit)}
एकूण खर्च: ${CurrencyFormatter.format(totalExpenses)}
कॅश जमा: ${CurrencyFormatter.format(cashAmount)} • यूपीआय: ${CurrencyFormatter.format(upiAmount)}
                    """.trimIndent()
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "अहवाल शेअर करा"))
        } catch (e: Exception) {
            Toast.makeText(context, "रिपोर्ट शेअर करताना त्रुटी आली: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Renders a high-resolution, full-color Customer Account Statement / Ledger Bitmap.
     */
    fun renderColorCustomerStatementBitmap(
        context: Context,
        customer: Customer,
        bills: List<Bill>,
        transactions: List<UdhariTransaction>,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ): Bitmap {
        val width = 760
        val validBills = bills.filter { !it.isVoided }
        val recentItemsCount = (validBills.size + transactions.size).coerceAtMost(8).coerceAtLeast(1)
        val estimatedHeight = 960 + (recentItemsCount * 50)

        val bmp = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#FFFDF5"))

        var y = 28f
        val leftX = 36f
        val rightX = width - 36f

        val headerCardH = 200f
        val headerRect = RectF(leftX, y, rightX, y + headerCardH)
        val emeraldBg = Paint().apply { color = Color.parseColor("#00342B"); style = Paint.Style.FILL; isAntiAlias = true }
        val goldBorder = Paint().apply { color = Color.parseColor("#C39B14"); strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true }
        canvas.drawRoundRect(headerRect, 16f, 16f, emeraldBg)
        canvas.drawRoundRect(headerRect, 16f, 16f, goldBorder)

        val logoSize = 90
        val shopLogo = getShopLogoBitmap(context, settings.logoPath)
        val scaledLogo = Bitmap.createScaledBitmap(shopLogo, logoSize, logoSize, true)
        canvas.drawBitmap(scaledLogo, (width - logoSize) / 2f, y + 14f, null)

        val shopNameText = settings.shopName.ifBlank { "मातोश्री कलेक्शन" }
        val goldTitlePaint = Paint().apply { color = Color.parseColor("#FED65B"); textSize = 32f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; isAntiAlias = true }
        canvas.drawText(shopNameText, width / 2f, y + 134f, goldTitlePaint)

        val ivorySub = Paint().apply { color = Color.parseColor("#FFFDF5"); textSize = 16f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
        val shopAddr = settings.address.ifBlank { "कोंढारकी, पंढरपूर" }
        val shopMob = settings.mobile.ifBlank { "8208610261" }
        canvas.drawText("$shopAddr  •  मोबाईल: $shopMob", width / 2f, y + 164f, ivorySub)
        y += headerCardH + 18f

        // Title Banner
        val bannerH = 46f
        val bannerRect = RectF(leftX, y, rightX, y + bannerH)
        canvas.drawRoundRect(bannerRect, 10f, 10f, Paint().apply { color = Color.parseColor("#FFF0C2"); style = Paint.Style.FILL; isAntiAlias = true })
        canvas.drawRoundRect(bannerRect, 10f, 10f, Paint().apply { color = Color.parseColor("#C39B14"); strokeWidth = 1.2f; style = Paint.Style.STROKE; isAntiAlias = true })
        val bannerText = Paint().apply { color = Color.parseColor("#00342B"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; isAntiAlias = true }
        canvas.drawText("★ ग्राहक खाते अहवाल (Customer Account Statement) ★", width / 2f, y + 31f, bannerText)
        y += bannerH + 18f

        // Customer Details Box
        val custBoxH = 96f
        val custRect = RectF(leftX, y, rightX, y + custBoxH)
        canvas.drawRoundRect(custRect, 12f, 12f, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true })
        canvas.drawRoundRect(custRect, 12f, 12f, Paint().apply { color = Color.parseColor("#E0E3E1"); strokeWidth = 1.2f; style = Paint.Style.STROKE; isAntiAlias = true })

        val cLabel = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 16f; isAntiAlias = true }
        val cVal = Paint().apply { color = Color.parseColor("#1B1C1C"); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }

        canvas.drawText("ग्राहक नाव: ${customer.name}", leftX + 16f, y + 32f, cVal)
        val mobDisplay = if (customer.mobile.isNotBlank()) customer.mobile else "-"
        canvas.drawText("मोबाईल: $mobDisplay", leftX + 16f, y + 60f, cLabel)
        val villDisplay = customer.address?.ifBlank { null } ?: "-"
        canvas.drawText("गाव / पत्ता: $villDisplay", leftX + 16f, y + 84f, cLabel)

        val printDate = DateUtils.formatForDisplay(DateUtils.nowIso())
        val dateRight = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 15f; textAlign = Paint.Align.RIGHT; isAntiAlias = true }
        canvas.drawText("दिनांक: $printDate", rightX - 16f, y + 32f, dateRight)
        y += custBoxH + 18f

        // Financial Summary 3-Card Grid
        val totalPurchases = validBills.sumOf { it.finalAmount }
        val totalPaid = transactions.filter { !it.isReversed && it.type == UdhariTransactionType.PAYMENT }.sumOf { it.amount } +
                validBills.sumOf { it.paidAmount }
        val outstanding = customer.outstandingBalance

        val gap = 12f
        val cardW = (rightX - leftX - (gap * 2)) / 3f
        val sH = 88f

        fun drawSumCard(x: Float, title: String, value: String, bgHex: String, borderHex: String, valColorHex: String) {
            val r = RectF(x, y, x + cardW, y + sH)
            canvas.drawRoundRect(r, 10f, 10f, Paint().apply { color = Color.parseColor(bgHex); style = Paint.Style.FILL; isAntiAlias = true })
            canvas.drawRoundRect(r, 10f, 10f, Paint().apply { color = Color.parseColor(borderHex); strokeWidth = 1.2f; style = Paint.Style.STROKE; isAntiAlias = true })
            canvas.drawText(title, x + 12f, y + 28f, Paint().apply { color = Color.parseColor("#5A605D"); textSize = 14f; isAntiAlias = true })
            canvas.drawText(value, x + 12f, y + 66f, Paint().apply { color = Color.parseColor(valColorHex); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true })
        }

        drawSumCard(leftX, "एकूण खरेदी", CurrencyFormatter.format(totalPurchases), "#E8F5E9", "#C8E6C9", "#00342B")
        drawSumCard(leftX + cardW + gap, "एकूण जमा", CurrencyFormatter.format(totalPaid), "#E3F2FD", "#BBDEFB", "#1565C0")
        val outColor = if (outstanding > 0.01) "#C62828" else "#2E7D32"
        val outBg = if (outstanding > 0.01) "#FFEBEE" else "#E8F5E9"
        val outBorder = if (outstanding > 0.01) "#FFCDD2" else "#C8E6C9"
        drawSumCard(leftX + (cardW + gap) * 2, "शिल्लक बाकी", CurrencyFormatter.format(outstanding), outBg, outBorder, outColor)
        y += sH + 20f

        // Recent Entries Table
        val thH = 36f
        val thRect = RectF(leftX, y, rightX, y + thH)
        canvas.drawRoundRect(thRect, 8f, 8f, emeraldBg)

        val thWhite = Paint().apply { color = Color.WHITE; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val thRight = Paint(thWhite).apply { textAlign = Paint.Align.RIGHT }

        canvas.drawText("दिनांक", leftX + 16f, y + 24f, thWhite)
        canvas.drawText("तपशील / पावती क्र", leftX + 180f, y + 24f, thWhite)
        canvas.drawText("प्रकार", rightX - 160f, y + 24f, thWhite)
        canvas.drawText("रक्कम", rightX - 16f, y + 24f, thRight)
        y += thH + 6f

        var rowIdx = 0
        validBills.take(5).forEach { b ->
            val rowRect = RectF(leftX, y, rightX, y + 38f)
            val bg = if (rowIdx % 2 == 0) Color.WHITE else Color.parseColor("#F7F9F8")
            canvas.drawRoundRect(rowRect, 6f, 6f, Paint().apply { color = bg; style = Paint.Style.FILL; isAntiAlias = true })

            val txt = Paint().apply { color = Color.parseColor("#1B1C1C"); textSize = 15f; isAntiAlias = true }
            val txtRight = Paint(txt).apply { textAlign = Paint.Align.RIGHT; typeface = Typeface.DEFAULT_BOLD; color = Color.parseColor("#00342B") }

            val dStr = if (b.billDate.length >= 10) b.billDate.take(10) else b.billDate
            canvas.drawText(dStr, leftX + 16f, y + 25f, txt)
            canvas.drawText("बिल #${b.billNumber}", leftX + 180f, y + 25f, txt)
            canvas.drawText("खरेदी (Debit)", rightX - 160f, y + 25f, Paint(txt).apply { color = Color.parseColor("#C62828") })
            canvas.drawText(CurrencyFormatter.format(b.finalAmount), rightX - 16f, y + 25f, txtRight)
            y += 42f
            rowIdx++
        }

        transactions.filter { !it.isReversed && it.type == UdhariTransactionType.PAYMENT }.take(4).forEach { tx ->
            val rowRect = RectF(leftX, y, rightX, y + 38f)
            val bg = if (rowIdx % 2 == 0) Color.WHITE else Color.parseColor("#F7F9F8")
            canvas.drawRoundRect(rowRect, 6f, 6f, Paint().apply { color = bg; style = Paint.Style.FILL; isAntiAlias = true })

            val txt = Paint().apply { color = Color.parseColor("#1B1C1C"); textSize = 15f; isAntiAlias = true }
            val txtRight = Paint(txt).apply { textAlign = Paint.Align.RIGHT; typeface = Typeface.DEFAULT_BOLD; color = Color.parseColor("#1B5E20") }

            val dStr = if (tx.date.length >= 10) tx.date.take(10) else tx.date
            canvas.drawText(dStr, leftX + 16f, y + 25f, txt)
            canvas.drawText("पेमेंट (${if (tx.paymentMethod == PaymentMethod.CASH) "कॅश" else "यूपीआय"})", leftX + 180f, y + 25f, txt)
            canvas.drawText("जमा (Credit)", rightX - 160f, y + 25f, Paint(txt).apply { color = Color.parseColor("#1B5E20") })
            canvas.drawText("-${CurrencyFormatter.format(tx.amount)}", rightX - 16f, y + 25f, txtRight)
            y += 42f
            rowIdx++
        }

        y += 14f
        canvas.drawLine(leftX, y, rightX, y, Paint().apply { color = Color.parseColor("#D0D5D2"); strokeWidth = 1.2f; isAntiAlias = true })
        y += 34f

        val footLeft = Paint().apply { color = Color.parseColor("#5A605D"); textSize = 14f; isAntiAlias = true }
        val footRight = Paint().apply { color = Color.parseColor("#00342B"); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT; isAntiAlias = true }
        canvas.drawText("अहवाल तारीख: $printDate", leftX, y, footLeft)
        canvas.drawText("अधिकृत स्वाक्षरी: ________________", rightX, y, footRight)
        y += 24f
        canvas.drawText("(मातोश्री कलेक्शन, पंढरपूर)", rightX, y, Paint(footRight).apply { textSize = 13f; typeface = Typeface.DEFAULT })
        y += 32f

        val finalH = y.toInt().coerceIn(100, bmp.height)
        return Bitmap.createBitmap(bmp, 0, 0, width, finalH)
    }

    /**
     * Prints a Customer Account Statement / Ledger using Android PrintManager (WiFi / USB / Save as PDF).
     */
    fun printCustomerStatementSystem(
        context: Context,
        customer: Customer,
        bills: List<Bill>,
        transactions: List<UdhariTransaction>,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            Toast.makeText(context, "Printing not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        val cleanName = customer.name.replace("[^a-zA-Z0-9]".toRegex(), "_")
        val jobName = "Statement_${cleanName}_${System.currentTimeMillis()}"
        val adapter = object : PrintDocumentAdapter() {
            private var pdfDocument: PrintedPdfDocument? = null

            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                pdfDocument = PrintedPdfDocument(context, newAttributes ?: PrintAttributes.Builder().build())
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }

                val info = PrintDocumentInfo.Builder("Statement_${cleanName}.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build()

                callback?.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                val page = pdfDocument?.startPage(0) ?: return
                val canvas = page.canvas

                val statementBmp = renderColorCustomerStatementBitmap(
                    context = context,
                    customer = customer,
                    bills = bills,
                    transactions = transactions,
                    settings = settings
                )
                val scale = (page.info.pageWidth.toFloat() / statementBmp.width).coerceAtMost(page.info.pageHeight.toFloat() / statementBmp.height)

                val scaledBmp = Bitmap.createScaledBitmap(
                    statementBmp,
                    (statementBmp.width * scale).toInt(),
                    (statementBmp.height * scale).toInt(),
                    true
                )

                canvas.drawBitmap(scaledBmp, (page.info.pageWidth - scaledBmp.width) / 2f, 0f, null)
                pdfDocument?.finishPage(page)

                try {
                    val out: OutputStream = FileOutputStream(destination?.fileDescriptor)
                    pdfDocument?.writeTo(out)
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                } finally {
                    pdfDocument?.close()
                    pdfDocument = null
                }
            }
        }

        printManager.print(jobName, adapter, PrintAttributes.Builder().build())
    }

    /**
     * Shares a full-color styled Customer Statement image directly to WhatsApp or Android Share Sheet.
     */
    fun shareColorCustomerStatement(
        context: Context,
        customer: Customer,
        bills: List<Bill>,
        transactions: List<UdhariTransaction>,
        settings: ShopSettingsSnapshot = ShopSettingsSnapshot()
    ) {
        try {
            val bmp = renderColorCustomerStatementBitmap(
                context = context,
                customer = customer,
                bills = bills,
                transactions = transactions,
                settings = settings
            )

            val cacheDir = File(context.cacheDir, "statements").apply { mkdirs() }
            val cleanCustName = customer.name.replace("[^a-zA-Z0-9_]".toRegex(), "_")
            val imageFile = File(cacheDir, "Statement_${cleanCustName}.jpg")
            val fos = FileOutputStream(imageFile)
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            fos.flush()
            fos.close()

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )

            val sName = settings.shopName.ifBlank { "मातोश्री कलेक्शन" }
            val sAddr = settings.address.ifBlank { "कोंढारकी, पंढरपूर" }
            val sMob = settings.mobile.ifBlank { "8208610261" }
            val shareMessage = """
नमस्कार ${customer.name},

$sName - आपले खाते विवरण (Customer Account Statement).
सध्याची बाकी रक्कम: ${CurrencyFormatter.format(customer.outstandingBalance)}

धन्यवाद!
दुकान: $sName, $sAddr
मोबाईल: $sMob
            """.trimIndent()

            openDirectWhatsAppChat(context, customer.mobile, shareMessage, uri)
        } catch (e: Exception) {
            Toast.makeText(context, "खाते अहवाल शेअर करताना त्रुटी आली: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * ESC/POS Thermal command builder for 58mm / 80mm bluetooth receipt printers
     */
    fun buildEscPosCommands(bill: Bill, shopName: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @ initialize
        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center align

        val title = "$shopName\n--------------------------------\n"
        out.write(title.toByteArray(StandardCharsets.UTF_8))

        out.write(byteArrayOf(0x1B, 0x61, 0x00)) // Left align
        val meta = "बिल नंबर: ${bill.billNumber}\nदिनांक: ${bill.billDate}\nकस्टमरचे नाव: ${bill.customerName ?: "किरकोळ ग्राहक"}\n--------------------------------\n"
        out.write(meta.toByteArray(StandardCharsets.UTF_8))

        for (item in bill.items) {
            val itemLine = "${item.productName} x${item.quantity} = ${CurrencyFormatter.format(item.lineTotal)}\n"
            out.write(itemLine.toByteArray(StandardCharsets.UTF_8))
        }

        out.write("--------------------------------\n".toByteArray(StandardCharsets.UTF_8))
        if (bill.discountAmount > 0) {
            out.write("उपएकूण: ${CurrencyFormatter.format(bill.subtotal)}\n".toByteArray(StandardCharsets.UTF_8))
            out.write("डिस्काउंट: -${CurrencyFormatter.format(bill.discountAmount)}\n".toByteArray(StandardCharsets.UTF_8))
        }
        out.write("एकूण रक्कम: ${CurrencyFormatter.format(bill.finalAmount)}\n".toByteArray(StandardCharsets.UTF_8))
        val payMethodStr = if (bill.paymentMethod == PaymentMethod.CASH) "कॅश" else if (bill.paymentMethod == PaymentMethod.UPI) "यूपीआय" else "उधारी"
        out.write("पेमेंट पद्धत: $payMethodStr\n\n".toByteArray(StandardCharsets.UTF_8))

        out.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
        out.write("धन्यवाद! पुन्हा नक्की भेट द्या!\n\n\n".toByteArray(StandardCharsets.UTF_8))
        out.write(byteArrayOf(0x1D, 0x56, 0x41, 0x10)) // Cut paper

        return out.toByteArray()
    }
}

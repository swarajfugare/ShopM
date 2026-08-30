package com.matoshree.shopmanager.utils

import android.content.Context
import android.content.Intent
import com.matoshree.shopmanager.domain.model.Bill
import java.io.ByteArrayOutputStream

object PrintShareHelper {

    /**
     * Creates customer-facing plain text receipt for instant WhatsApp sharing
     * NOTE: Internal profit is STRICTLY EXCLUDED as per Phase 11 requirements.
     */
    fun createCustomerShareText(bill: Bill, shopName: String = "Matoshree Collection"): String {
        val sb = StringBuilder()
        sb.append("✨ $shopName ✨\n")
        sb.append("--------------------------------\n")
        sb.append("Bill No: ${bill.billNumber}\n")
        sb.append("Date: ${DateUtils.formatForDisplay(bill.billDate)}\n")
        if (!bill.customerName.isNullOrBlank()) {
            sb.append("Customer: ${bill.customerName}\n")
        }
        sb.append("--------------------------------\n")

        if (bill.items.isNotEmpty()) {
            sb.append(String.format("%-18s %3s %9s\n", "Item", "Qty", "Amount"))
            sb.append("--------------------------------\n")
            for (item in bill.items) {
                val name = if (item.productName.length > 18) item.productName.take(16) + ".." else item.productName
                sb.append(String.format("%-18s %3d %9s\n", name, item.quantity, CurrencyFormatter.format(item.lineTotal)))
            }
            sb.append("--------------------------------\n")
        }

        sb.append("Subtotal: ${CurrencyFormatter.format(bill.subtotal)}\n")
        if (bill.discountAmount > 0) {
            sb.append("Discount: -${CurrencyFormatter.format(bill.discountAmount)}\n")
        }
        sb.append("FINAL TOTAL: ${CurrencyFormatter.format(bill.finalAmount)}\n")
        sb.append("Payment Mode: ${bill.paymentMethod.name}\n")
        sb.append("Status: ${bill.paymentStatus.name}\n")
        sb.append("--------------------------------\n")
        sb.append("Thank you for shopping with us!\nVisit Again!\n")
        return sb.toString()
    }

    fun shareBillText(context: Context, bill: Bill, shopName: String = "Matoshree Collection") {
        val text = createCustomerShareText(bill, shopName)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Invoice ${bill.billNumber} - $shopName")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share Bill via"))
    }

    /**
     * Generates standard ESC/POS bytes for 58mm / 80mm Bluetooth thermal printers
     */
    fun generateEscPosBytes(bill: Bill, shopName: String = "Matoshree Collection"): ByteArray {
        val stream = ByteArrayOutputStream()

        // Init printer
        stream.write(byteArrayOf(0x1B, 0x40))

        // Center align & Bold header
        stream.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center
        stream.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold ON
        stream.write("$shopName\n".toByteArray(Charsets.UTF_8))
        stream.write("Premium Boutique\n".toByteArray(Charsets.UTF_8))
        stream.write(byteArrayOf(0x1B, 0x45, 0x00)) // Bold OFF

        // Left align
        stream.write(byteArrayOf(0x1B, 0x61, 0x00))
        stream.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
        stream.write("Bill: ${bill.billNumber}\n".toByteArray(Charsets.UTF_8))
        stream.write("Date: ${DateUtils.formatForDisplay(bill.billDate)}\n".toByteArray(Charsets.UTF_8))
        if (!bill.customerName.isNullOrBlank()) {
            stream.write("Customer: ${bill.customerName}\n".toByteArray(Charsets.UTF_8))
        }
        stream.write("--------------------------------\n".toByteArray(Charsets.UTF_8))

        for (item in bill.items) {
            val name = if (item.productName.length > 16) item.productName.take(14) + ".." else item.productName
            stream.write(String.format("%-16s %2d %10s\n", name, item.quantity, CurrencyFormatter.format(item.lineTotal)).toByteArray(Charsets.UTF_8))
        }

        stream.write("--------------------------------\n".toByteArray(Charsets.UTF_8))
        stream.write(byteArrayOf(0x1B, 0x45, 0x01)) // Bold
        stream.write("TOTAL: ${CurrencyFormatter.format(bill.finalAmount)}\n".toByteArray(Charsets.UTF_8))
        stream.write(byteArrayOf(0x1B, 0x45, 0x00))
        stream.write("Payment: ${bill.paymentMethod.name}\n".toByteArray(Charsets.UTF_8))

        // Center footer
        stream.write(byteArrayOf(0x1B, 0x61, 0x01))
        stream.write("Thank you! Visit again.\n\n\n".toByteArray(Charsets.UTF_8))
        stream.write(byteArrayOf(0x1D, 0x56, 0x41, 0x10)) // Cut paper

        return stream.toByteArray()
    }
}

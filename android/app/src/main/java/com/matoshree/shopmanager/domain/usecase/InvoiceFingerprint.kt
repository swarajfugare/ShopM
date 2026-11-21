package com.matoshree.shopmanager.domain.usecase

import java.security.MessageDigest

object InvoiceFingerprint {

    fun generateFingerprint(
        supplierName: String?,
        invoiceNumber: String?,
        invoiceDate: String?,
        invoiceTotal: Double?,
        firstPageBytes: ByteArray? = null
    ): String {
        val normSupplier = supplierName?.trim()?.lowercase() ?: ""
        val normInvoice = invoiceNumber?.trim()?.lowercase() ?: ""
        val normDate = invoiceDate?.trim()?.lowercase() ?: ""
        val normTotal = invoiceTotal?.let { "%.2f".format(it) } ?: ""

        val raw = StringBuilder()
        raw.append(normSupplier).append("|")
            .append(normInvoice).append("|")
            .append(normDate).append("|")
            .append(normTotal)

        val md = MessageDigest.getInstance("SHA-256")
        if (firstPageBytes != null && firstPageBytes.isNotEmpty()) {
            md.update(firstPageBytes.take(4096).toByteArray())
        }
        val digest = md.digest(raw.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

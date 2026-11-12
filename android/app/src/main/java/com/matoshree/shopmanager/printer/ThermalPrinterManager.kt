package com.matoshree.shopmanager.printer

import android.content.Context
import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.domain.model.Customer
import com.matoshree.shopmanager.domain.model.DailyClosing
import com.matoshree.shopmanager.domain.model.UdhariTransaction
import com.matoshree.shopmanager.utils.PrintShareHelper

class ThermalPrinterManager(private val context: Context) {

    val bluetoothManager = BluetoothPrinterManager(context)

    /**
     * Directly prints a Bill to the configured Bluetooth 58mm thermal printer.
     */
    suspend fun printBill(
        bill: Bill,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): Result<Unit> {
        val selectedPrinter = bluetoothManager.getSelectedPrinter()
            ?: return Result.failure(IllegalStateException("Please select a thermal printer in Settings."))

        val receiptBytes = ThermalReceiptFormatter.formatBillReceipt(
            context = context,
            bill = bill,
            settings = settings
        )

        return bluetoothManager.sendBytes(receiptBytes)
    }

    /**
     * Directly prints a Payment Receipt for an Udhari collection to Bluetooth 58mm thermal printer.
     */
    suspend fun printPaymentReceipt(
        transaction: com.matoshree.shopmanager.domain.model.UdhariTransaction,
        customerName: String,
        customerMobile: String,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): Result<Unit> {
        val selectedPrinter = bluetoothManager.getSelectedPrinter()
            ?: return Result.failure(IllegalStateException("Please select a thermal printer in Settings."))

        val receiptBytes = ThermalReceiptFormatter.formatPaymentReceipt(
            transaction = transaction,
            customerName = customerName,
            customerMobile = customerMobile,
            settings = settings
        )

        return bluetoothManager.sendBytes(receiptBytes)
    }

    suspend fun printUdhariPaymentReceipt(
        transaction: com.matoshree.shopmanager.domain.model.UdhariTransaction,
        customerName: String,
        customerMobile: String,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): Result<Unit> = printPaymentReceipt(transaction, customerName, customerMobile, settings)

    /**
     * Directly prints an Analytics Report for a selected period to Bluetooth 58mm thermal printer.
     */
    suspend fun printAnalyticsReport(
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
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): Result<Unit> {
        val selectedPrinter = bluetoothManager.getSelectedPrinter()
            ?: return Result.failure(IllegalStateException("Please select a thermal printer in Settings."))

        val receiptBytes = ThermalReceiptFormatter.formatAnalyticsReceipt(
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

        return bluetoothManager.sendBytes(receiptBytes)
    }

    /**
     * Sends a quick test receipt to verify connection and paper alignment.
     */
    suspend fun printTestReceipt(): Result<Unit> {
        val selectedPrinter = bluetoothManager.getSelectedPrinter()
            ?: return Result.failure(IllegalStateException("Please select a thermal printer in Settings."))

        val testBytes = ThermalReceiptFormatter.formatTestPrintReceipt(context)
        return bluetoothManager.sendBytes(testBytes)
    }

    /**
     * Prints a comprehensive Customer Account Statement / Ledger to the Bluetooth 58mm printer.
     */
    suspend fun printCustomerStatement(
        customer: Customer,
        bills: List<Bill>,
        transactions: List<UdhariTransaction>,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): Result<Unit> {
        val selectedPrinter = bluetoothManager.getSelectedPrinter()
            ?: return Result.failure(IllegalStateException("कृपया सेटिंगमध्ये ५८ मिमी थर्मल प्रिंटर सिलेक्ट करा."))

        val receiptBytes = ThermalReceiptFormatter.formatCustomerStatementReceipt(
            context = context,
            customer = customer,
            bills = bills,
            transactions = transactions,
            settings = settings
        )

        return bluetoothManager.sendBytes(receiptBytes)
    }

    /**
     * Prints a Daily Closing Statement to the Bluetooth 58mm printer.
     */
    suspend fun printDailyClosing(
        closing: DailyClosing,
        settings: PrintShareHelper.ShopSettingsSnapshot
    ): Result<Unit> {
        val selectedPrinter = bluetoothManager.getSelectedPrinter()
            ?: return Result.failure(IllegalStateException("कृपया सेटिंगमध्ये ५८ मिमी थर्मल प्रिंटर सिलेक्ट करा."))

        val receiptBytes = ThermalReceiptFormatter.formatDailyClosingReceipt(
            context = context,
            closing = closing,
            settings = settings
        )

        return bluetoothManager.sendBytes(receiptBytes)
    }
}

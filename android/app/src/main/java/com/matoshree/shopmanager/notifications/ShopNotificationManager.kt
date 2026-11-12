package com.matoshree.shopmanager.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.matoshree.shopmanager.MainActivity
import com.matoshree.shopmanager.R
import com.matoshree.shopmanager.domain.model.Bill
import com.matoshree.shopmanager.utils.CurrencyFormatter

object ShopNotificationManager {

    const val CHANNEL_BILLS = "channel_bills"
    const val CHANNEL_UDHARI = "channel_udhari"
    const val CHANNEL_CLOSING = "channel_closing"
    const val CHANNEL_STOCK = "channel_stock"
    const val CHANNEL_SYNC = "channel_sync"

    const val EXTRA_NAV_ROUTE = "extra_nav_route"
    const val EXTRA_BILL_ID = "extra_bill_id"
    const val EXTRA_CUSTOMER_ID = "extra_customer_id"

    private fun getAppLogoBitmap(context: Context): Bitmap? {
        return try {
            BitmapFactory.decodeResource(context.resources, R.drawable.app_logo)
        } catch (e: Exception) {
            null
        }
    }

    fun initNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // 1. New Bills & High Value Sales Channel (High Priority)
            val billsChannel = NotificationChannel(
                CHANNEL_BILLS,
                "नवीन बिले आणि विक्री",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "नवीन विक्री, बिले आणि मोठ्या ऑर्डर्सचे अलर्ट"
                enableVibration(true)
                enableLights(true)
            }

            // 2. Udhari Due Dates & Payment Receipts (High Priority)
            val udhariChannel = NotificationChannel(
                CHANNEL_UDHARI,
                "उधारी आणि पेमेंट अलर्ट",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "कस्टमर उधारी देय तारीख, पेमेंट पावती आणि उधारी मर्यादेचे अलर्ट"
                enableVibration(true)
            }

            // 3. Daily Register Closing & Reports (Default Priority)
            val closingChannel = NotificationChannel(
                CHANNEL_CLOSING,
                "दैनिक क्लोजिंग आणि रिपोर्ट्स",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "रात्री ९:३० वाजता क्लोजिंग रिमाइंडर आणि व्यवसाय सारांश रिपोर्ट"
            }

            // 4. Low Stock Inventory Channel
            val stockChannel = NotificationChannel(
                CHANNEL_STOCK,
                "स्टॉक आणि इन्व्हेंटरी अलर्ट",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "प्रॉडक्ट स्टॉक कमी झाल्यावर अलर्ट"
            }

            // 5. Cloud Sync & Backup Channel
            val syncChannel = NotificationChannel(
                CHANNEL_SYNC,
                "क्लाउड बॅकअप आणि सिंक",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ऑफलाईन डेटा क्लाउडवर सुरक्षितपणे सिंक झाल्याचे नोटिफिकेशन"
            }

            manager.createNotificationChannels(listOf(billsChannel, udhariChannel, closingChannel, stockChannel, syncChannel))
        }
    }

    // 1. NEW BILL / HIGH-VALUE SALE NOTIFICATION
    fun sendNewBillNotification(context: Context, bill: Bill, deviceName: String = "Counter") {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ROUTE, "bill_preview")
            putExtra(EXTRA_BILL_ID, bill.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (bill.id % 10000).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val customerText = if (!bill.customerName.isNullOrBlank()) bill.customerName else "थेट कस्टमर"
        val totalText = CurrencyFormatter.format(bill.finalAmount)
        val isHighValue = bill.finalAmount >= 5000.0
        val methodText = if (bill.paymentMethod.name == "CASH") "कॅश" else "यूपीआय"

        val title = if (isHighValue) "🌟 मोठी विक्री: $totalText (#${bill.billNumber})" else "🧾 नवीन विक्री: $totalText (#${bill.billNumber})"
        val logoBmp = getAppLogoBitmap(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_BILLS)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(0xFF1B4D3E.toInt()) // DeepEmerald
            .setContentTitle(title)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentText("$customerText साठी $methodText द्वारे बिल केले ($deviceName)")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "बिल क्र #${bill.billNumber}\nकस्टमर: $customerText\nरक्कम: $totalText ($methodText)\nडिव्हाइस: $deviceName" +
                    if (isHighValue) "\n⭐ अभिनंदन! मोठी विक्री नोंदवली गेली." else ""
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (logoBmp != null) {
            builder.setLargeIcon(logoBmp)
        }

        notifySafely(context, (bill.id % 10000).toInt(), builder.build())
    }

    // 2. UDHARI PAYMENT RECEIVED ALERT (Multi-Device)
    fun sendUdhariPaymentReceivedNotification(
        context: Context,
        customerId: Long,
        customerName: String,
        amountPaid: Double,
        remainingBalance: Double,
        deviceName: String = "Counter"
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ROUTE, "customer_profile")
            putExtra(EXTRA_CUSTOMER_ID, customerId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (customerId % 10000).toInt() + 2000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val paidStr = CurrencyFormatter.format(amountPaid)
        val remStr = CurrencyFormatter.format(remainingBalance)
        val logoBmp = getAppLogoBitmap(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_UDHARI)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(0xFF1B4D3E.toInt())
            .setContentTitle("💰 पेमेंट जमा: $paidStr")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentText("$customerName कडून $paidStr जमा ($deviceName). शिल्लक बाकी: $remStr")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "पेमेंट जमा: $paidStr\nकस्टमर: $customerName\nशिल्लक बाकी: $remStr\nकाउंटर: $deviceName\nकस्टमर खाते पाहण्यासाठी टॅप करा."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (logoBmp != null) {
            builder.setLargeIcon(logoBmp)
        }

        notifySafely(context, (customerId % 10000).toInt() + 2000, builder.build())
    }

    // 3. CREDIT LIMIT EXCEEDED WARNING
    fun sendCreditLimitExceededNotification(
        context: Context,
        customerId: Long,
        customerName: String,
        currentBalance: Double,
        creditLimit: Double
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ROUTE, "customer_profile")
            putExtra(EXTRA_CUSTOMER_ID, customerId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (customerId % 10000).toInt() + 3000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val balStr = CurrencyFormatter.format(currentBalance)
        val limitStr = CurrencyFormatter.format(creditLimit)
        val logoBmp = getAppLogoBitmap(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_UDHARI)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(0xFFD32F2F.toInt()) // Red warning
            .setContentTitle("⚠️ उधारी मर्यादा अलर्ट: $customerName")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentText("बाकी रक्कम ($balStr) उधारी मर्यादेपेक्षा ($limitStr) जास्त झाली आहे.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "कस्टमर: $customerName\nचालू उधारी बाकी: $balStr\nठरवलेली उधारी मर्यादा: $limitStr\nतपासण्यासाठी किंवा रिमाइंडर पाठवण्यासाठी टॅप करा."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (logoBmp != null) {
            builder.setLargeIcon(logoBmp)
        }

        notifySafely(context, (customerId % 10000).toInt() + 3000, builder.build())
    }

    // 4. UDHARI DUE TODAY REMINDER
    fun sendUdhariDueNotification(
        context: Context,
        customerId: Long,
        customerName: String,
        amount: Double,
        dueDate: String
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ROUTE, "customer_profile")
            putExtra(EXTRA_CUSTOMER_ID, customerId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (customerId % 10000).toInt() + 1000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val amountStr = CurrencyFormatter.format(amount)
        val logoBmp = getAppLogoBitmap(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_UDHARI)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(0xFF1B4D3E.toInt())
            .setContentTitle("⏳ आज उधारी देय तारीख: $customerName")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentText("$amountStr पेमेंट आज ($dueDate) देय आहे.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "कस्टमर: $customerName\nबाकी रक्कम: $amountStr\nमुदत तारीख: $dueDate\nव्हॉट्सॲप स्टेटमेंट पाठवण्यासाठी किंवा जमा नोंदवण्यासाठी टॅप करा."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (logoBmp != null) {
            builder.setLargeIcon(logoBmp)
        }

        notifySafely(context, (customerId % 10000).toInt() + 1000, builder.build())
    }

    // 5. OVERDUE UDHARI AGING SUMMARY
    fun sendOverdueUdhariSummaryNotification(
        context: Context,
        overdueCount: Int,
        totalOverdueAmount: Double
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ROUTE, "customers")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val totalStr = CurrencyFormatter.format(totalOverdueAmount)
        val logoBmp = getAppLogoBitmap(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_UDHARI)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(0xFF1B4D3E.toInt())
            .setContentTitle("⚠️ थकीत उधारी अलर्ट")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentText("$overdueCount कस्टमर्सची उधारी थकीत आहे (>१५ दिवस). एकूण: $totalStr")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "एकूण थकीत बाकी: $totalStr\nकस्टमर्स: $overdueCount खाती\nव्हॉट्सॲप रिमाइंडर पाठवण्यासाठी टॅप करा."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (logoBmp != null) {
            builder.setLargeIcon(logoBmp)
        }

        notifySafely(context, 2001, builder.build())
    }

    // 6. DAILY REGISTER CLOSING REMINDER (9:30 PM)
    fun sendDailyClosingReminderNotification(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ROUTE, "daily_closing")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            3001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val logoBmp = getAppLogoBitmap(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_CLOSING)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(0xFF1B4D3E.toInt())
            .setContentTitle("⏰ दैनिक रजिस्टर क्लोजिंग रिमाइंडर")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentText("रात्रीचे ९:३० वाजले आहेत — आजचा कॅश गल्ला तपासा आणि दिवस क्लोज करा.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "रात्रीचे ९:३० वाजले आहेत. आजचा गल्ला तपासण्यासाठी आणि विक्री सुरक्षित करण्यासाठी कृपया दैनिक क्लोजिंग करा."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (logoBmp != null) {
            builder.setLargeIcon(logoBmp)
        }

        notifySafely(context, 3001, builder.build())
    }

    // 7. DAILY SUMMARY REPORT (On Day Close)
    fun sendDailySummaryNotification(
        context: Context,
        totalSales: Double,
        netProfit: Double,
        cashSales: Double,
        upiSales: Double,
        billsCount: Int
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ROUTE, "daily_closing")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            3002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val salesStr = CurrencyFormatter.format(totalSales)
        val profitStr = CurrencyFormatter.format(netProfit)
        val cashStr = CurrencyFormatter.format(cashSales)
        val upiStr = CurrencyFormatter.format(upiSales)
        val logoBmp = getAppLogoBitmap(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_CLOSING)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(0xFF1B4D3E.toInt())
            .setContentTitle("📊 रजिस्टर क्लोज केले: आज $salesStr विक्री ($billsCount बिले)")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentText("निव्वळ नफा: $profitStr • कॅश: $cashStr • यूपीआय: $upiStr")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "आजचा व्यवसाय सारांश:\n• एकूण विक्री: $salesStr ($billsCount बिले)\n• निव्वळ नफा: $profitStr\n• कॅश जमा: $cashStr\n• यूपीआय जमा: $upiStr"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (logoBmp != null) {
            builder.setLargeIcon(logoBmp)
        }

        notifySafely(context, 3002, builder.build())
    }

    // 8. MORNING BUSINESS MOTIVATION & KICKOFF (9:00 AM)
    fun sendMorningKickoffNotification(
        context: Context,
        yesterdaySales: Double,
        yesterdayBills: Int
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ROUTE, "dashboard")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            3003,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val salesStr = CurrencyFormatter.format(yesterdaySales)
        val logoBmp = getAppLogoBitmap(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_CLOSING)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(0xFF1B4D3E.toInt())
            .setContentTitle("🌅 शुभ सकाळ — मातोश्री कलेक्शन!")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentText("कालची एकूण विक्री: $salesStr ($yesterdayBills बिले). आजचा दिवस फायदेशीर जावो!")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "शुभ सकाळ! काल मातोश्री कलेक्शनवर $salesStr ची विक्री झाली ($yesterdayBills बिले). आजच्या दिवसासाठी हार्दिक शुभेच्छा!"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (logoBmp != null) {
            builder.setLargeIcon(logoBmp)
        }

        notifySafely(context, 3003, builder.build())
    }

    // 9. LOW STOCK INVENTORY ALERT
    fun sendLowStockNotification(context: Context, productName: String, currentStock: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAV_ROUTE, "inventory")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            4001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val logoBmp = getAppLogoBitmap(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_STOCK)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(0xFF1B4D3E.toInt())
            .setContentTitle("📦 कमी स्टॉक अलर्ट: $productName")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentText("स्टॉकमध्ये फक्त $currentStock नग शिल्लक आहेत. इन्व्हेंटरी तपासण्यासाठी टॅप करा.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (logoBmp != null) {
            builder.setLargeIcon(logoBmp)
        }

        notifySafely(context, (productName.hashCode() % 10000).coerceAtLeast(4000), builder.build())
    }

    // 10. OFFLINE SYNC COMPLETED ALERT
    fun sendOfflineSyncCompleteNotification(context: Context, syncedCount: Int) {
        if (syncedCount <= 0) return
        val logoBmp = getAppLogoBitmap(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(0xFF1B4D3E.toInt())
            .setContentTitle("☁️ क्लाउड सिंक पूर्ण झाले")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentText("$syncedCount ऑफलाईन विक्री आणि व्यवहार क्लाउडवर सुरक्षितपणे सेव्ह झाले.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)

        if (logoBmp != null) {
            builder.setLargeIcon(logoBmp)
        }

        notifySafely(context, 5001, builder.build())
    }

    private fun notifySafely(context: Context, notificationId: Int, notification: android.app.Notification) {
        try {
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) {
                manager.notify(notificationId, notification)
            }
        } catch (e: SecurityException) {
            android.util.Log.w("ShopNotificationManager", "Notification permission not granted: ${e.message}")
        } catch (e: Exception) {
            android.util.Log.e("ShopNotificationManager", "Error sending notification: ${e.message}", e)
        }
    }
}

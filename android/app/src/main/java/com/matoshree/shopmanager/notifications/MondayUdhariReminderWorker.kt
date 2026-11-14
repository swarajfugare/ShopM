package com.matoshree.shopmanager.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.WhatsAppWebhookManager
import java.util.Calendar

/**
 * WorkManager worker that runs automatically every Monday (and on-demand)
 * to send automated polite WhatsApp payment reminders to all customers
 * with outstanding credit balance (Udhari).
 */
class MondayUdhariReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val TAG = "MondayReminderWorker"

    override suspend fun doWork(): Result {
        Log.i(TAG, "Checking Monday automated Udhari WhatsApp reminder prerequisites...")

        return try {
            // 1. Single designated device check: Only run automated messages from the device enabled by owner
            if (!WhatsAppWebhookManager.isAutoRemindersFromThisDeviceEnabled(context)) {
                Log.d(TAG, "Automated reminders from this device are turned OFF. Skipping Monday worker.")
                return Result.success()
            }

            // 2. Feature enabled check
            val isEnabled = WhatsAppWebhookManager.isUdhariReminderEnabled(context)
            val webhookUrl = WhatsAppWebhookManager.getWebhookUrl(context)

            if (!isEnabled || webhookUrl.isBlank()) {
                Log.d(TAG, "WhatsApp Udhari reminder is disabled or Webhook URL is empty. Skipping dispatch.")
                return Result.success()
            }

            // 3. IST (Asia/Kolkata) calendar verification: Ensure today is Monday
            val cal = Calendar.getInstance(DateUtils.kolkataTimeZone)
            if (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                Log.d(TAG, "Today is not Monday in Asia/Kolkata timezone (DAY_OF_WEEK=${cal.get(Calendar.DAY_OF_WEEK)}). Skipping.")
                return Result.success()
            }

            Log.i(TAG, "Starting Monday automated Udhari WhatsApp reminder run...")
            val result = WhatsAppWebhookManager.dispatchBulkUdhariReminders(context)
            val (sentCount, failedCount) = result.getOrNull() ?: (0 to 0)

            // Notify shop owner via system notification
            if (sentCount > 0) {
                ShopNotificationManager.sendOverdueUdhariSummaryNotification(
                    context = context,
                    overdueCount = sentCount,
                    totalOverdueAmount = 0.0
                )
            }

            Log.i(TAG, "Monday reminder run complete: Sent $sentCount, Failed $failedCount")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Monday Udhari reminder worker failed: ${e.message}", e)
            Result.success()
        }
    }
}

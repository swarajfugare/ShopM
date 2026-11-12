package com.matoshree.shopmanager.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.matoshree.shopmanager.data.firebase.CustomerDto
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.PhoneUtils
import com.matoshree.shopmanager.utils.WhatsAppWebhookManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

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
        Log.i(TAG, "Starting Monday automated Udhari WhatsApp reminder run...")

        return try {
            val isEnabled = WhatsAppWebhookManager.isUdhariReminderEnabled(context)
            val webhookUrl = WhatsAppWebhookManager.getWebhookUrl(context)

            if (!isEnabled || webhookUrl.isBlank()) {
                Log.d(TAG, "WhatsApp Udhari reminder is disabled or Webhook URL is empty. Skipping dispatch.")
                return Result.success()
            }

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

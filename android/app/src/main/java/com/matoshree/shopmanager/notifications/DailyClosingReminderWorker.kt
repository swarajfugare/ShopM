package com.matoshree.shopmanager.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailyClosingReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            
            // Check if closing document already exists for today
            val querySnapshot = FirebaseManager.dailyClosingsCollection()
                .whereEqualTo("closingDate", todayStr)
                .get()
                .await()

            val isAlreadyClosed = !querySnapshot.isEmpty

            if (!isAlreadyClosed) {
                Log.d("DailyClosingWorker", "Register not closed for $todayStr. Triggering 9:30 PM reminder.")
                ShopNotificationManager.sendDailyClosingReminderNotification(context)
            } else {
                Log.d("DailyClosingWorker", "Register already closed for $todayStr. Skipping reminder.")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("DailyClosingWorker", "Error checking daily closing: ${e.message}", e)
            Result.success()
        }
    }
}

package com.matoshree.shopmanager.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exact Alarm BroadcastReceiver triggered at exactly 9:30 PM (21:30 IST - Indian Standard Time)
 * to remind the shop owner to perform daily closing if not already done.
 */
class DailyClosingExactAlarmReceiver : BroadcastReceiver() {
    private val TAG = "DailyClosingAlarm"

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Exact 9:30 PM IST Alarm fired! Checking today's closing status...")

        // Immediately reschedule for tomorrow 9:30 PM IST
        NotificationScheduler.scheduleDailyClosingExactAlarm(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                FirebaseManager.ensureAuthenticated()
                val snap = FirebaseManager.dailyClosingsCollection()
                    .whereEqualTo("closingDate", todayStr)
                    .get()
                    .await()

                if (snap.isEmpty) {
                    Log.d(TAG, "Register not closed for $todayStr. Sending 9:30 PM IST reminder notification.")
                    ShopNotificationManager.sendDailyClosingReminderNotification(context)
                } else {
                    Log.d(TAG, "Register already closed for $todayStr. Skipping reminder.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Daily closing check notice: ${e.message}. Sending fallback notification.")
                ShopNotificationManager.sendDailyClosingReminderNotification(context)
            }
        }
    }
}

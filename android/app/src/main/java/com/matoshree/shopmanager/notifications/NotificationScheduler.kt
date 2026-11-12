package com.matoshree.shopmanager.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object NotificationScheduler {
    private const val TAG = "NotificationScheduler"
    private const val WORK_DAILY_CLOSING = "work_daily_closing_reminder"
    private const val WORK_UDHARI_DUE = "work_udhari_due_reminder"
    private const val WORK_MONDAY_REMINDER = "work_monday_udhari_reminder"
    val INDIAN_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    fun scheduleAllDailyReminders(context: Context) {
        try {
            scheduleDailyClosingExactAlarm(context)
            scheduleDailyClosingReminder(context)
            scheduleDailyUdhariReminder(context)
            scheduleMondayUdhariReminder(context)
            Log.d(TAG, "All Indian Standard Time (IST) notification workers and exact alarms scheduled.")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling daily reminders: ${e.message}", e)
        }
    }

    /**
     * Schedules an exact AlarmManager trigger to fire at precisely 9:30 PM (21:30 IST)
     * waking up the device from Doze mode if necessary.
     */
    fun scheduleDailyClosingExactAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val now = Calendar.getInstance(INDIAN_TIME_ZONE)
            val target = Calendar.getInstance(INDIAN_TIME_ZONE).apply {
                set(Calendar.HOUR_OF_DAY, 21) // 9 PM
                set(Calendar.MINUTE, 30)      // 30 mins -> 9:30 PM IST
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (now.timeInMillis >= target.timeInMillis) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }

            val intent = Intent(context, DailyClosingExactAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                2130,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    target.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    target.timeInMillis,
                    pendingIntent
                )
            }
            Log.d(TAG, "Exact 9:30 PM IST Alarm scheduled for: ${target.time}")
        } catch (e: Exception) {
            Log.w(TAG, "Exact alarm schedule notice: ${e.message}")
        }
    }

    private fun scheduleDailyClosingReminder(context: Context) {
        val now = Calendar.getInstance(INDIAN_TIME_ZONE)
        val target = Calendar.getInstance(INDIAN_TIME_ZONE).apply {
            set(Calendar.HOUR_OF_DAY, 21) // 9:30 PM IST
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (now.after(target)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        val initialDelay = target.timeInMillis - now.timeInMillis

        val dailyClosingWork = PeriodicWorkRequestBuilder<DailyClosingReminderWorker>(
            24, TimeUnit.HOURS,
            15, TimeUnit.MINUTES
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_DAILY_CLOSING,
            ExistingPeriodicWorkPolicy.UPDATE,
            dailyClosingWork
        )
    }

    private fun scheduleDailyUdhariReminder(context: Context) {
        val now = Calendar.getInstance(INDIAN_TIME_ZONE)
        val target = Calendar.getInstance(INDIAN_TIME_ZONE).apply {
            set(Calendar.HOUR_OF_DAY, 9) // 9:00 AM IST
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (now.after(target)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        val initialDelay = target.timeInMillis - now.timeInMillis

        val udhariDueWork = PeriodicWorkRequestBuilder<UdhariDueReminderWorker>(
            24, TimeUnit.HOURS,
            15, TimeUnit.MINUTES
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_UDHARI_DUE,
            ExistingPeriodicWorkPolicy.UPDATE,
            udhariDueWork
        )
    }

    private fun scheduleMondayUdhariReminder(context: Context) {
        val now = Calendar.getInstance(INDIAN_TIME_ZONE)
        val target = Calendar.getInstance(INDIAN_TIME_ZONE).apply {
            set(Calendar.HOUR_OF_DAY, 10) // 10:00 AM IST on Monday
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY || before(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val initialDelay = (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)

        val mondayWork = PeriodicWorkRequestBuilder<MondayUdhariReminderWorker>(
            7, TimeUnit.DAYS,
            1, TimeUnit.HOURS
        )
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_MONDAY_REMINDER,
            ExistingPeriodicWorkPolicy.UPDATE,
            mondayWork
        )
    }

    fun triggerImmediateMondayReminder(context: Context) {
        val oneTimeWork = androidx.work.OneTimeWorkRequestBuilder<MondayUdhariReminderWorker>().build()
        WorkManager.getInstance(context).enqueue(oneTimeWork)
    }
}

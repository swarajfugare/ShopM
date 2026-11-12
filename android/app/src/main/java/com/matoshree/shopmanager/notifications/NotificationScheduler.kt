package com.matoshree.shopmanager.notifications

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

object NotificationScheduler {
    private const val TAG = "NotificationScheduler"
    private const val WORK_DAILY_CLOSING = "work_daily_closing_reminder"
    private const val WORK_UDHARI_DUE = "work_udhari_due_reminder"
    private const val WORK_MONDAY_REMINDER = "work_monday_udhari_reminder"

    fun scheduleAllDailyReminders(context: Context) {
        try {
            scheduleDailyClosingReminder(context)
            scheduleDailyUdhariReminder(context)
            scheduleMondayUdhariReminder(context)
            Log.d(TAG, "All background notification workers scheduled successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling daily reminders: ${e.message}", e)
        }
    }

    private fun scheduleDailyClosingReminder(context: Context) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21) // 9:30 PM
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
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9) // 9:00 AM
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
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10) // 10:00 AM on Monday
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

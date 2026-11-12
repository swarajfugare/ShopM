package com.matoshree.shopmanager.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ShopBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("ShopBootReceiver", "Device boot / update detected. Re-scheduling notification workers.")
            ShopNotificationManager.initNotificationChannels(context)
            NotificationScheduler.scheduleAllDailyReminders(context)
        }
    }
}

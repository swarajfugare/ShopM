package com.matoshree.shopmanager.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.matoshree.shopmanager.data.firebase.BillDto
import com.matoshree.shopmanager.data.firebase.CustomerDto
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import com.matoshree.shopmanager.data.firebase.UdhariTransactionDto
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class UdhariDueReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val nowMs = System.currentTimeMillis()
            val fifteenDaysMs = 15L * 24 * 60 * 60 * 1000L

            // 1. Fetch active customers
            val customersSnapshot = FirebaseManager.customersCollection().get().await()
            val customerMap = mutableMapOf<Long, String>() // id to name
            val customerMobileMap = mutableMapOf<Long, String>() // id to mobile

            customersSnapshot.documents.forEach { doc ->
                val cust = doc.toObject(CustomerDto::class.java)
                if (cust != null && cust.status != "ARCHIVED") {
                    val custId = cust.legacyId?.takeIf { it > 0 } ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
                    customerMap[custId] = cust.name
                    if (!cust.mobile.isNullOrBlank()) {
                        customerMobileMap[custId] = cust.mobile
                    }
                }
            }

            // 2. Fetch Udhari transactions
            val txSnapshot = FirebaseManager.udhariTransactionsCollection()
                .whereEqualTo("type", "PURCHASE_CREDIT")
                .get()
                .await()

            var overdueCount = 0
            var totalOverdueAmount = 0.0

            txSnapshot.documents.forEach { doc ->
                val tx = doc.toObject(UdhariTransactionDto::class.java)
                if (tx != null && !tx.isReversed) {
                    val customerName = customerMap[tx.customerId] ?: tx.customerName.ifBlank { "Customer" }

                    // Check if due date is today
                    if (tx.dueDate != null && tx.dueDate!!.startsWith(todayStr)) {
                        ShopNotificationManager.sendUdhariDueNotification(
                            context = context,
                            customerId = tx.customerId,
                            customerName = customerName,
                            amount = tx.amount,
                            dueDate = tx.dueDate!!
                        )

                        // Automated WhatsApp Due Reminder via Webhook
                        if (com.matoshree.shopmanager.utils.WhatsAppWebhookManager.isUdhariReminderEnabled(context)) {
                            val custMob = customerMobileMap[tx.customerId] ?: tx.customerMobile
                            if (!custMob.isNullOrBlank() && tx.remainingOutstanding > 0.01) {
                                try {
                                    com.matoshree.shopmanager.utils.WhatsAppWebhookManager.sendUdhariReminderWhatsApp(
                                        context = context,
                                        customerName = customerName,
                                        customerMobile = custMob,
                                        outstandingDue = tx.remainingOutstanding,
                                        dueDate = tx.dueDate
                                    )
                                } catch (e: Exception) {
                                    Log.e("UdhariDueWorker", "Failed to dispatch automated WhatsApp reminder: ${e.message}")
                                }
                            }
                        }
                    }

                    // Check if overdue by more than 15 days
                    val isOld = (nowMs - tx.createdAt) > fifteenDaysMs
                    if (isOld && tx.remainingOutstanding > 0.0) {
                        overdueCount++
                        totalOverdueAmount += tx.remainingOutstanding
                    }
                }
            }

            if (overdueCount > 0 && totalOverdueAmount > 0.0) {
                ShopNotificationManager.sendOverdueUdhariSummaryNotification(
                    context = context,
                    overdueCount = overdueCount,
                    totalOverdueAmount = totalOverdueAmount
                )
            }

            // 3. Morning Business Motivation & Yesterday Summary
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            val yesterdayBillsSnapshot = FirebaseManager.billsCollection().get().await()

            var yesterdaySales = 0.0
            var yesterdayCount = 0

            yesterdayBillsSnapshot.documents.forEach { doc ->
                val bill = doc.toObject(BillDto::class.java)
                if (bill != null && bill.status != "VOIDED") {
                    val billDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(bill.createdAt))
                    if (billDate == yesterdayStr) {
                        yesterdaySales += bill.finalAmount
                        yesterdayCount++
                    }
                }
            }

            if (yesterdayCount > 0 && yesterdaySales > 0.0) {
                ShopNotificationManager.sendMorningKickoffNotification(
                    context = context,
                    yesterdaySales = yesterdaySales,
                    yesterdayBills = yesterdayCount
                )
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("UdhariDueWorker", "Error processing morning reminders: ${e.message}", e)
            Result.success()
        }
    }
}

package com.matoshree.shopmanager.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.matoshree.shopmanager.data.firebase.BillDto
import com.matoshree.shopmanager.data.firebase.CustomerDto
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import com.matoshree.shopmanager.data.firebase.UdhariTransactionDto
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.repository.toDomain
import com.matoshree.shopmanager.domain.usecase.ShopMetricsCalculator
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.PhoneUtils
import com.matoshree.shopmanager.utils.WhatsAppWebhookManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class UdhariDueReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private data class DueCustomerInfo(
        val name: String,
        val mobile: String,
        val dueDate: String,
        val customerId: Long
    )

    override suspend fun doWork(): Result {
        return try {
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
                timeZone = DateUtils.kolkataTimeZone
            }.format(Date())
            val nowMs = System.currentTimeMillis()
            val fifteenDaysMs = 15L * 24 * 60 * 60 * 1000L

            // 1. Fetch active customers from Cloud Firestore
            val customersSnapshot = FirebaseManager.customersCollection().get().await()
            val customerMap = mutableMapOf<Long, String>() // id to name
            val customerMobileMap = mutableMapOf<Long, String>() // id to mobile
            val cloudCustomers = mutableListOf<com.matoshree.shopmanager.domain.model.Customer>()

            customersSnapshot.documents.forEach { doc ->
                val cust = doc.toObject(CustomerDto::class.java)
                if (cust != null && cust.status != "ARCHIVED" && cust.status != "TRASH") {
                    val custId = cust.legacyId?.takeIf { it > 0 } ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL)
                    customerMap[custId] = cust.name
                    if (!cust.mobile.isNullOrBlank()) {
                        customerMobileMap[custId] = cust.mobile
                    }
                    cloudCustomers.add(cust.apply { id = doc.id }.toDomain())
                }
            }

            // 2. Fetch Udhari transactions
            val txSnapshot = FirebaseManager.udhariTransactionsCollection()
                .whereEqualTo("type", "PURCHASE_CREDIT")
                .get()
                .await()

            var overdueCount = 0
            var totalOverdueAmount = 0.0

            val dueCustomersMap = mutableMapOf<String, DueCustomerInfo>()
            val allTxsDomain = mutableListOf<com.matoshree.shopmanager.domain.model.UdhariTransaction>()

            txSnapshot.documents.forEach { doc ->
                val tx = doc.toObject(UdhariTransactionDto::class.java)?.apply { id = doc.id }
                if (tx != null && !tx.isReversed) {
                    val txDomain = tx.toDomain()
                    allTxsDomain.add(txDomain)

                    val customerName = customerMap[tx.customerId] ?: tx.customerName.ifBlank { "Customer" }
                    val custMob = customerMobileMap[tx.customerId] ?: tx.customerMobile

                    // Check if due date is today
                    if (tx.dueDate != null && tx.dueDate!!.startsWith(todayStr)) {
                        ShopNotificationManager.sendUdhariDueNotification(
                            context = context,
                            customerId = tx.customerId,
                            customerName = customerName,
                            amount = tx.amount,
                            dueDate = tx.dueDate!!
                        )

                        // Collect unique customer for WhatsApp due reminder
                        val clean = PhoneUtils.clean10DigitMobileInput(custMob ?: "")
                        if (clean.length == 10 && PhoneUtils.isValidIndianMobile(clean)) {
                            if (!dueCustomersMap.containsKey(clean)) {
                                dueCustomersMap[clean] = DueCustomerInfo(
                                    name = customerName,
                                    mobile = clean,
                                    dueDate = tx.dueDate!!,
                                    customerId = tx.customerId
                                )
                            }
                        }
                    }
                }
            }

            // Unified Shop Ledger calculation for rock-solid overdue & reminder amounts
            val db = AppDatabase.getInstance(context)
            val localBills = db.billDao().getAllBillsDirect(50000).map { it.toDomain() }
            val localCustomers = db.customerDao().getAllCustomersDirect().map { it.toDomain() }
            val mergedCustomers = (localCustomers + cloudCustomers).distinctBy { it.canonicalKey() }

            val shopSummary = com.matoshree.shopmanager.domain.usecase.UdhariLedger.computeShopLedger(
                allBills = localBills,
                allTransactions = allTxsDomain,
                allCustomers = mergedCustomers
            )
            val keyResolver = com.matoshree.shopmanager.domain.usecase.UdhariLedger.buildCustomerKeyResolver(
                customers = mergedCustomers,
                bills = localBills,
                transactions = allTxsDomain
            )

            overdueCount = shopSummary.customerResults.values.count { it.isOverdue }
            totalOverdueAmount = shopSummary.overdueAmount

            // Automated WhatsApp Due Reminders (Grouped by customer with current true balance)
            val isAutoThisDevice = WhatsAppWebhookManager.isAutoRemindersFromThisDeviceEnabled(context)
            val isReminderEnabled = WhatsAppWebhookManager.isUdhariReminderEnabled(context)
            val webhookConfigured = WhatsAppWebhookManager.isConfigured(context)

            if (isAutoThisDevice && isReminderEnabled && webhookConfigured && dueCustomersMap.isNotEmpty()) {
                try {
                    // Cap safety at 200 per run
                    for ((cleanMobile, info) in dueCustomersMap.entries.take(200)) {
                        val custKey = keyResolver(info.mobile, info.name, info.customerId) ?: "mob_$cleanMobile"
                        val trueBalance = shopSummary.customerResults[custKey]?.balance ?: 0.0

                        // Rule: If balance <= 0.01, do not send reminder
                        if (trueBalance <= 0.01) {
                            Log.d("UdhariDueWorker", "Skipping reminder for ${info.name}: balance is cleared (₹$trueBalance)")
                            continue
                        }

                        // Distributed claim: Claim this customer's reminder for today
                        val canClaim = WhatsAppWebhookManager.claimDailyReminder(
                            cleanMobile = cleanMobile,
                            customerName = info.name,
                            amount = trueBalance,
                            type = "DUE_DATE"
                        )
                        if (!canClaim) {
                            Log.d("UdhariDueWorker", "Reminder for ${info.name} ($cleanMobile) already claimed/sent today.")
                            continue
                        }

                        try {
                            WhatsAppWebhookManager.sendUdhariReminderWhatsApp(
                                context = context,
                                customerName = info.name,
                                customerMobile = cleanMobile,
                                outstandingDue = trueBalance,
                                dueDate = info.dueDate,
                                reminderType = "DUE_DATE",
                                silent = true
                            )
                        } catch (e: Exception) {
                            Log.e("UdhariDueWorker", "Failed to dispatch automated WhatsApp reminder to ${info.name}: ${e.message}")
                        }

                        // 800ms rate limiting delay
                        delay(800L)
                    }
                } catch (e: Exception) {
                    Log.e("UdhariDueWorker", "Error calculating true balances for due reminders: ${e.message}", e)
                }
            } else if (!isAutoThisDevice) {
                Log.d("UdhariDueWorker", "Automated WhatsApp reminders disabled on this device. Local notifications generated only.")
            }

            if (overdueCount > 0 && totalOverdueAmount > 0.0) {
                ShopNotificationManager.sendOverdueUdhariSummaryNotification(
                    context = context,
                    overdueCount = overdueCount,
                    totalOverdueAmount = totalOverdueAmount
                )
            }

            // 3. Morning Business Motivation & Yesterday Summary
            val cal = Calendar.getInstance(DateUtils.kolkataTimeZone).apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
                timeZone = DateUtils.kolkataTimeZone
            }.format(cal.time)
            val yesterdayBillsSnapshot = FirebaseManager.billsCollection().get().await()

            var yesterdaySales = 0.0
            var yesterdayCount = 0

            yesterdayBillsSnapshot.documents.forEach { doc ->
                val bill = doc.toObject(BillDto::class.java)
                if (bill != null && bill.status != "VOIDED") {
                    val billDate = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
                        timeZone = DateUtils.kolkataTimeZone
                    }.format(Date(bill.createdAt))
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

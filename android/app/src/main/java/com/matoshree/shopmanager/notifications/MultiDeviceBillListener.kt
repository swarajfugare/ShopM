package com.matoshree.shopmanager.notifications

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
import com.matoshree.shopmanager.data.firebase.BillDto
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import com.matoshree.shopmanager.data.firebase.UdhariTransactionDto
import java.util.concurrent.atomic.AtomicBoolean

object MultiDeviceBillListener {
    private const val TAG = "MultiDeviceBillListener"
    private var billListenerRegistration: ListenerRegistration? = null
    private var udhariListenerRegistration: ListenerRegistration? = null
    private val isInitialLoadBills = AtomicBoolean(true)
    private val isInitialLoadUdhari = AtomicBoolean(true)
    private val localDeviceId = Build.MODEL ?: "AndroidDevice"

    fun startListening(context: Context) {
        // Multi-device notifications are now unified inside RealtimeSyncManager once authenticated,
        // eliminating duplicate unauthenticated listeners at app start.
    }

    private fun startListeningBills(context: Context) {
        if (billListenerRegistration != null) return

        try {
            isInitialLoadBills.set(true)
            val collection = FirebaseManager.billsCollection()
            billListenerRegistration = collection.addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.w(TAG, "Bill listener error: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshots == null) return@addSnapshotListener

                if (isInitialLoadBills.getAndSet(false)) {
                    Log.d(TAG, "Initial bills snapshot loaded (${snapshots.size()} items).")
                    return@addSnapshotListener
                }

                for (dc in snapshots.documentChanges) {
                    if (dc.type == DocumentChange.Type.ADDED) {
                        val billDto = dc.document.toObject(BillDto::class.java)
                        val bill = billDto.toDomain()

                        val creator = billDto.createdByDeviceId ?: ""
                        val isFromAnotherDevice = creator.isNotBlank() && creator != localDeviceId && !creator.equals("Counter", ignoreCase = true)
                        val isRecent = billDto.createdAt > 0 && (System.currentTimeMillis() - billDto.createdAt) in 0..120_000L

                        if (isFromAnotherDevice && isRecent) {
                            Log.d(TAG, "New bill from remote device ($creator): #${bill.billNumber}")
                            ShopNotificationManager.sendNewBillNotification(
                                context = context.applicationContext,
                                bill = bill,
                                deviceName = creator
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting multi-device bill listener: ${e.message}", e)
        }
    }

    private fun startListeningUdhari(context: Context) {
        if (udhariListenerRegistration != null) return

        try {
            isInitialLoadUdhari.set(true)
            val collection = FirebaseManager.udhariTransactionsCollection()
            udhariListenerRegistration = collection.addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.w(TAG, "Udhari listener error: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshots == null) return@addSnapshotListener

                if (isInitialLoadUdhari.getAndSet(false)) {
                    return@addSnapshotListener
                }

                for (dc in snapshots.documentChanges) {
                    if (dc.type == DocumentChange.Type.ADDED) {
                        val tx = dc.document.toObject(UdhariTransactionDto::class.java)

                        val creator = tx.createdByDeviceId ?: ""
                        val isFromAnotherDevice = creator.isNotBlank() && creator != localDeviceId && !creator.equals("Counter", ignoreCase = true)
                        val isRecent = tx.createdAt > 0 && (System.currentTimeMillis() - tx.createdAt) in 0..120_000L

                        if (isFromAnotherDevice && isRecent && tx.type == "PAYMENT") {
                            Log.d(TAG, "New udhari payment from remote device")
                            ShopNotificationManager.sendUdhariPaymentReceivedNotification(
                                context = context.applicationContext,
                                customerId = tx.customerId,
                                customerName = tx.customerName.ifBlank { "Customer" },
                                amountPaid = tx.amount,
                                remainingBalance = tx.remainingOutstanding,
                                deviceName = creator
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting multi-device udhari listener: ${e.message}", e)
        }
    }

    fun stopListening() {
        billListenerRegistration?.remove()
        billListenerRegistration = null
        udhariListenerRegistration?.remove()
        udhariListenerRegistration = null
    }
}

package com.matoshree.shopmanager.data.diagnostic

import android.content.Context
import android.util.Log
import com.matoshree.shopmanager.data.firebase.CustomerDto
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.entity.CustomerEntity
import com.matoshree.shopmanager.domain.model.SyncStatus
import com.matoshree.shopmanager.data.repository.toDomain
import com.matoshree.shopmanager.data.repository.toEntity
import com.matoshree.shopmanager.utils.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CustomerDiagnosticReport(
    val totalRoomActive: Int,
    val totalRoomArchived: Int,
    val totalFirestoreActive: Int,
    val erroneouslyArchivedCount: Int,
    val missingFromRoomCount: Int,
    val duplicateMobileCount: Int,
    val details: List<String> = emptyList()
)

data class CustomerRepairResult(
    val reactivatedCount: Int,
    val importedCount: Int,
    val deduplicatedCount: Int,
    val backupPath: String,
    val message: String
)

object CustomerDiagnosticManager {
    private const val TAG = "CustomerDiagnostic"

    suspend fun runDiagnosticScan(context: Context): CustomerDiagnosticReport = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val allRoom = db.customerDao().getAllCustomersDirect()
        val roomActive = allRoom.filter { it.isActive }
        val roomArchived = allRoom.filter { !it.isActive }

        val roomMobileSet = roomActive.map { PhoneUtils.clean10DigitMobileInput(it.mobile) }.filter { it.length == 10 }.toSet()
        val roomArchivedMobileSet = roomArchived.map { PhoneUtils.clean10DigitMobileInput(it.mobile) }.filter { it.length == 10 }.toSet()

        var firestoreActiveCount = 0
        var erroneouslyArchivedCount = 0
        var missingFromRoomCount = 0
        val details = mutableListOf<String>()

        try {
            if (FirebaseManager.isNetworkAvailable()) {
                FirebaseManager.ensureAuthenticated()
                val custSnap = FirebaseManager.customersCollection().get().await()
                val cloudCusts = custSnap.documents.mapNotNull { doc ->
                    doc.toObject(CustomerDto::class.java)?.apply { id = doc.id }
                }.filter { it.status != "ARCHIVED" && it.status != "TRASH" }

                firestoreActiveCount = cloudCusts.size

                for (cust in cloudCusts) {
                    val clean = PhoneUtils.clean10DigitMobileInput(cust.mobile ?: "")
                    if (clean.length == 10) {
                        if (roomArchivedMobileSet.contains(clean) && !roomMobileSet.contains(clean)) {
                            erroneouslyArchivedCount++
                            details.add("चुकून अर्काईव्ह: ${cust.name} ($clean)")
                        } else if (!roomMobileSet.contains(clean) && !roomArchivedMobileSet.contains(clean)) {
                            missingFromRoomCount++
                            details.add("फोनमध्ये नसलेला: ${cust.name} ($clean)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Diagnostic cloud fetch note: ${e.message}")
            details.add("क्लाउड तपासणी करताना त्रुटी: ${e.message}")
        }

        // Check duplicate mobiles in Room
        val mobileGroups = allRoom.filter { it.mobile.isNotBlank() }
            .groupBy { PhoneUtils.clean10DigitMobileInput(it.mobile) }
            .filter { it.key.length == 10 && it.value.size > 1 }
        val duplicateCount = mobileGroups.size

        CustomerDiagnosticReport(
            totalRoomActive = roomActive.size,
            totalRoomArchived = roomArchived.size,
            totalFirestoreActive = firestoreActiveCount,
            erroneouslyArchivedCount = erroneouslyArchivedCount,
            missingFromRoomCount = missingFromRoomCount,
            duplicateMobileCount = duplicateCount,
            details = details.take(20)
        )
    }

    suspend fun performSafeRepair(context: Context): CustomerRepairResult = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val allRoom = db.customerDao().getAllCustomersDirect()

        // 1. Create Safe JSON Backup before any modification
        val backupDir = File(context.filesDir, "customer_backups").apply { mkdirs() }
        val backupFile = File(backupDir, "customers_backup_${System.currentTimeMillis()}.json")

        var reactivatedCount = 0
        var importedCount = 0

        try {
            val backupArray = JSONArray()
            for (c in allRoom) {
                val obj = JSONObject()
                obj.put("id", c.id)
                obj.put("serverId", c.serverId)
                obj.put("name", c.name)
                obj.put("mobile", c.mobile)
                obj.put("address", c.address)
                obj.put("isActive", c.isActive)
                obj.put("outstandingBalance", c.outstandingBalance)
                backupArray.put(obj)
            }
            backupFile.writeText(backupArray.toString(2))
            Log.i(TAG, "Customer backup saved before repair: ${backupFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Backup write note: ${e.message}")
        }

        // 2. Scan and repair with Cloud
        try {
            if (FirebaseManager.isNetworkAvailable()) {
                FirebaseManager.ensureAuthenticated()
                val custSnap = FirebaseManager.customersCollection().get().await()
                val cloudCusts = custSnap.documents.mapNotNull { doc ->
                    doc.toObject(CustomerDto::class.java)?.apply { id = doc.id }
                }.filter { it.status != "ARCHIVED" && it.status != "TRASH" }

                val currentRoom = db.customerDao().getAllCustomersDirect()
                val roomByMobile = currentRoom.filter { it.mobile.isNotBlank() }
                    .associateBy { PhoneUtils.clean10DigitMobileInput(it.mobile) }

                for (cust in cloudCusts) {
                    val clean = PhoneUtils.clean10DigitMobileInput(cust.mobile ?: "")
                    if (clean.length == 10) {
                        val existingRoom = roomByMobile[clean]
                        if (existingRoom != null) {
                            if (!existingRoom.isActive) {
                                db.customerDao().unarchiveCustomerByMobile(existingRoom.mobile, clean)
                                reactivatedCount++
                            }
                        } else {
                            // Missing in Room, import from cloud
                            val domainCust = cust.toDomain()
                            val entity = domainCust.toEntity().copy(
                                id = if (domainCust.id in 1 until 1_000_000_000L) domainCust.id else 0L,
                                isActive = true,
                                syncStatus = SyncStatus.SYNCED
                            )
                            db.customerDao().upsertServerCustomer(entity)
                            importedCount++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Repair cloud pass failed: ${e.message}", e)
        }

        // 3. Deduplicate Room rows
        val deduplicatedCount = try {
            val before = db.customerDao().getAllCustomersDirect().size
            db.customerDao().deduplicateCustomerRows()
            val after = db.customerDao().getAllCustomersDirect().size
            (before - after).coerceAtLeast(0)
        } catch (e: Exception) {
            Log.w(TAG, "Deduplicate error: ${e.message}")
            0
        }

        CustomerRepairResult(
            reactivatedCount = reactivatedCount,
            importedCount = importedCount,
            deduplicatedCount = deduplicatedCount,
            backupPath = backupFile.absolutePath,
            message = "दुरुस्ती यशस्वी: $reactivatedCount ग्राहक पुन्हा सक्रिय केले, $importedCount ग्राहक क्लाउडवरून आणले, $deduplicatedCount डुप्लिकेट नोंदी हटवल्या."
        )
    }
}

package com.matoshree.shopmanager.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.matoshree.shopmanager.data.firebase.CustomerDto
import com.matoshree.shopmanager.data.repository.toDomain
import com.matoshree.shopmanager.domain.model.Bill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Serverless Direct WhatsApp Webhook & API Manager for Matoshree Collection.
 * Enables zero-server direct HTTP/REST dispatch to any WhatsApp Business API or Webhook Gateway
 * (Wati, Interakt, AiSensy, MSG91, Twilio, UltraMsg, Fast2SMS, custom webhook endpoints, etc.).
 */
object WhatsAppWebhookManager {

    private const val TAG = "WhatsAppWebhookManager"
    private const val PREFS_NAME = "matoshree_whatsapp_prefs"

    private const val KEY_ENABLED = "whatsapp_auto_send_enabled"
    private const val KEY_WEBHOOK_URL = "whatsapp_webhook_url"
    private const val KEY_API_KEY = "whatsapp_api_key"
    private const val KEY_HEADER_NAME = "whatsapp_header_name"
    private const val KEY_CUSTOM_TEMPLATE = "whatsapp_payload_template"
    private const val KEY_BILL_TEMPLATE = "whatsapp_bill_payload_template"
    private const val KEY_REMINDER_TEMPLATE = "whatsapp_reminder_payload_template"
    private const val KEY_CLEARED_TEMPLATE = "whatsapp_cleared_payload_template"
    private const val KEY_UDHARI_REMINDER = "whatsapp_udhari_reminder_enabled"
    private const val KEY_AUTO_REMINDERS_FROM_THIS_DEVICE = "whatsapp_auto_reminders_from_this_device"
    private const val KEY_SELECTED_BILL_TEMPLATE_OPTION = "whatsapp_selected_bill_template_option" // "AUTO", "BILL", "REMINDER", "CUSTOM"
    private const val KEY_BILL_TEMPLATE_NAME = "whatsapp_bill_template_name"
    private const val KEY_REMINDER_TEMPLATE_NAME = "whatsapp_reminder_template_name"
    private const val KEY_CLEARED_TEMPLATE_NAME = "whatsapp_cleared_template_name"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getSecurePrefs(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "matoshree_secure_wa_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            try {
                // Recover securely by purging corrupted keys without plaintext fallback
                context.deleteSharedPreferences("matoshree_secure_wa_prefs")
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    "matoshree_secure_wa_prefs",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e2: Exception) {
                null
            }
        }
    }

    fun isConfigured(context: Context): Boolean {
        val url = getWebhookUrl(context)
        val key = getApiKey(context)
        return url.isNotBlank() && key.isNotBlank()
    }

    fun isAutoSendEnabled(context: Context): Boolean {
        return false // Permanently disabled: Do NOT send auto WhatsApp after bill
    }

    fun setAutoSendEnabled(context: Context, enabled: Boolean) {
        // Disabled per user policy
    }

    fun getWebhookUrl(context: Context): String {
        return getSecurePrefs(context)?.getString(KEY_WEBHOOK_URL, "") ?: ""
    }

    fun setWebhookUrl(context: Context, url: String) {
        getSecurePrefs(context)?.edit()?.putString(KEY_WEBHOOK_URL, url.trim())?.apply()
    }

    fun getApiKey(context: Context): String {
        return getSecurePrefs(context)?.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        getSecurePrefs(context)?.edit()?.putString(KEY_API_KEY, key.trim())?.apply()
    }

    const val DEFAULT_REMINDER_PAYLOAD = "{\n  \"to\": \"{phone}\",\n  \"action\": \"udhari_reminder\",\n  \"template_name\": \"udhari_reminder\",\n  \"customerName\": \"{customerName}\",\n  \"amount\": \"{outstandingDue}\",\n  \"dueDate\": \"{dueDate}\",\n  \"shopName\": \"{shopName}\",\n  \"message\": \"{message}\"\n}"

    fun getHeaderName(context: Context): String {
        return getPrefs(context).getString(KEY_HEADER_NAME, "Authorization") ?: "Authorization"
    }

    fun setHeaderName(context: Context, header: String) {
        getPrefs(context).edit().putString(KEY_HEADER_NAME, header.trim()).apply()
    }

    fun getPayloadTemplate(context: Context): String {
        val custom = getPrefs(context).getString(KEY_CUSTOM_TEMPLATE, "") ?: ""
        if (custom.isNotBlank()) return custom
        return getReminderPayloadTemplate(context)
    }

    fun setPayloadTemplate(context: Context, template: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_TEMPLATE, template.trim()).apply()
        setReminderPayloadTemplate(context, template)
    }

    fun getBillPayloadTemplate(context: Context): String {
        val specific = getPrefs(context).getString(KEY_BILL_TEMPLATE, "") ?: ""
        if (specific.isNotBlank()) return specific
        return getReminderPayloadTemplate(context)
    }

    fun setBillPayloadTemplate(context: Context, template: String) {
        getPrefs(context).edit().putString(KEY_BILL_TEMPLATE, template.trim()).apply()
    }

    fun getReminderPayloadTemplate(context: Context): String {
        val specific = getPrefs(context).getString(KEY_REMINDER_TEMPLATE, "") ?: ""
        if (specific.isNotBlank()) return specific
        val custom = getPrefs(context).getString(KEY_CUSTOM_TEMPLATE, "") ?: ""
        if (custom.isNotBlank()) return custom
        return DEFAULT_REMINDER_PAYLOAD
    }

    fun setReminderPayloadTemplate(context: Context, template: String) {
        getPrefs(context).edit().putString(KEY_REMINDER_TEMPLATE, template.trim()).apply()
    }

    fun getClearedPayloadTemplate(context: Context): String {
        val specific = getPrefs(context).getString(KEY_CLEARED_TEMPLATE, "") ?: ""
        if (specific.isNotBlank()) return specific
        return DEFAULT_REMINDER_PAYLOAD
    }

    fun setClearedPayloadTemplate(context: Context, template: String) {
        getPrefs(context).edit().putString(KEY_CLEARED_TEMPLATE, template.trim()).apply()
    }

    fun getSelectedBillTemplateType(context: Context): String {
        return getPrefs(context).getString(KEY_SELECTED_BILL_TEMPLATE_OPTION, "AUTO") ?: "AUTO"
    }

    fun setSelectedBillTemplateType(context: Context, type: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_BILL_TEMPLATE_OPTION, type).apply()
    }

    fun getBillTemplateName(context: Context): String {
        return getPrefs(context).getString(KEY_BILL_TEMPLATE_NAME, "bill_alert") ?: "bill_alert"
    }

    fun setBillTemplateName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_BILL_TEMPLATE_NAME, name.trim()).apply()
    }

    fun getReminderTemplateName(context: Context): String {
        return getPrefs(context).getString(KEY_REMINDER_TEMPLATE_NAME, "udhari_reminder") ?: "udhari_reminder"
    }

    fun setReminderTemplateName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_REMINDER_TEMPLATE_NAME, name.trim()).apply()
    }

    fun getClearedTemplateName(context: Context): String {
        return getPrefs(context).getString(KEY_CLEARED_TEMPLATE_NAME, "udhari_cleared") ?: "udhari_cleared"
    }

    fun setClearedTemplateName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_CLEARED_TEMPLATE_NAME, name.trim()).apply()
    }

    fun isUdhariReminderEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_UDHARI_REMINDER, true)
    }

    fun setUdhariReminderEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_UDHARI_REMINDER, enabled).apply()
    }

    fun isAutoRemindersFromThisDeviceEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_REMINDERS_FROM_THIS_DEVICE, false)
    }

    fun setAutoRemindersFromThisDeviceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_REMINDERS_FROM_THIS_DEVICE, enabled).apply()
    }

    suspend fun claimDailyReminder(
        cleanMobile: String,
        customerName: String,
        amount: Double,
        type: String,
        todayDate: String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).apply { timeZone = DateUtils.kolkataTimeZone }.format(java.util.Date())
    ): Boolean = withContext(Dispatchers.IO) {
        if (cleanMobile.length != 10) return@withContext false
        val claimId = "${cleanMobile}_${todayDate}"
        try {
            if (!com.matoshree.shopmanager.data.firebase.FirebaseManager.isNetworkAvailable()) {
                return@withContext true
            }
            com.matoshree.shopmanager.data.firebase.FirebaseManager.ensureAuthenticated()
            val docRef = com.matoshree.shopmanager.data.firebase.FirebaseManager.shopDoc().collection("reminder_claims").document(claimId)
            val claimed = com.matoshree.shopmanager.data.firebase.FirebaseManager.firestore.runTransaction { transaction ->
                val snapshot = transaction.get(docRef)
                if (snapshot.exists()) {
                    val status = snapshot.getString("status") ?: ""
                    val claimedAt = snapshot.getLong("claimedAt") ?: 0L
                    val isRecent = (System.currentTimeMillis() - claimedAt) < 30 * 60 * 1000L
                    if (status == "SENT" || isRecent) {
                        return@runTransaction false
                    }
                }
                transaction.set(
                    docRef,
                    mapOf(
                        "id" to claimId,
                        "customerMobile" to cleanMobile,
                        "customerName" to customerName,
                        "amount" to amount,
                        "type" to type,
                        "status" to "CLAIMED",
                        "claimedByDevice" to (android.os.Build.MODEL ?: "Android"),
                        "claimedAt" to System.currentTimeMillis(),
                        "date" to todayDate
                    )
                )
                true
            }.await()
            claimed
        } catch (e: Exception) {
            Log.w(TAG, "Distributed reminder claim note: ${e.message}")
            false
        }
    }

    suspend fun updateClaimStatus(
        cleanMobile: String,
        status: String,
        errorMessage: String? = null,
        todayDate: String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).apply { timeZone = DateUtils.kolkataTimeZone }.format(java.util.Date())
    ) = withContext(Dispatchers.IO) {
        try {
            val claimId = "${cleanMobile}_${todayDate}"
            val docRef = com.matoshree.shopmanager.data.firebase.FirebaseManager.shopDoc().collection("reminder_claims").document(claimId)
            val updateMap = mutableMapOf<String, Any>(
                "status" to status,
                "updatedAt" to System.currentTimeMillis()
            )
            if (errorMessage != null) updateMap["errorMessage"] = errorMessage
            docRef.set(updateMap, com.google.firebase.firestore.SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.w(TAG, "Update claim status note: ${e.message}")
        }
    }

    suspend fun recordReminderLog(
        context: Context,
        customerName: String,
        customerMobile: String,
        amount: Double,
        type: String,
        status: String,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        val clean10 = PhoneUtils.clean10DigitMobileInput(customerMobile)
        val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).apply { timeZone = DateUtils.kolkataTimeZone }.format(java.util.Date())
        val logId = "rem_${System.currentTimeMillis()}_${clean10}"
        val logEntity = com.matoshree.shopmanager.data.local.entity.ReminderLogEntity(
            id = logId,
            customerName = customerName,
            customerMobile = clean10,
            amount = amount,
            type = type,
            status = status,
            errorMessage = errorMessage,
            timestamp = System.currentTimeMillis(),
            date = todayDate
        )

        try {
            val db = com.matoshree.shopmanager.data.local.AppDatabase.getInstance(context)
            db.reminderLogDao().insertLog(logEntity)
        } catch (e: Exception) {
            Log.w(TAG, "Room reminder log insert note: ${e.message}")
        }

        try {
            if (com.matoshree.shopmanager.data.firebase.FirebaseManager.isNetworkAvailable()) {
                com.matoshree.shopmanager.data.firebase.FirebaseManager.ensureAuthenticated()
                com.matoshree.shopmanager.data.firebase.FirebaseManager.shopDoc().collection("reminder_logs").document(logId).set(logEntity).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore reminder log note: ${e.message}")
        }
    }

    /**
     * Automatically dispatches a message when a bill is completed.
     * In accordance with user policy, this app sends ONLY Udhari Reminders (no bill notification).
     * If the bill is fully paid (pendingAmount <= 0.01), no message is sent.
     * If the bill has pending balance (pendingAmount > 0.01), it sends the Udhari Reminder template.
     */
    suspend fun sendBillAutoWhatsApp(
        context: Context,
        bill: Bill,
        templateTypeOverride: String? = null,
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot()
    ): Result<String> = withContext(Dispatchers.IO) {
        // Automatic message dispatch immediately after billing is permanently turned OFF per user policy
        Log.d(TAG, "Automatic WhatsApp message dispatch after bill creation is disabled per user policy.")
        Result.success("Auto bill WhatsApp disabled")
    }

    /**
     * Sends an automated or one-tap Udhari Due Reminder directly to the customer's WhatsApp via Webhook.
     */
    suspend fun sendUdhariReminderWhatsApp(
        context: Context,
        customerName: String,
        customerMobile: String,
        outstandingDue: Double,
        dueDate: String? = null,
        reminderType: String = "MANUAL",
        silent: Boolean = false,
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot()
    ): Result<String> = withContext(Dispatchers.IO) {
        val clean10 = PhoneUtils.clean10DigitMobileInput(customerMobile)
        val custName = customerName.ifBlank { "ग्राहक" }

        try {
            val webhookUrl = getWebhookUrl(context)
            if (webhookUrl.isBlank()) {
                val err = "WhatsApp webhook URL is not configured."
                recordReminderLog(context, custName, clean10, outstandingDue, reminderType, "FAILED", err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            if (customerMobile.isBlank() || !PhoneUtils.isValidIndianMobile(customerMobile)) {
                val err = "Customer has no valid 10-digit mobile number."
                recordReminderLog(context, custName, clean10, outstandingDue, reminderType, "FAILED", err)
                return@withContext Result.failure(IllegalArgumentException(err))
            }

            val waDestination = "91$clean10"
            val shopName = settings.shopName.ifBlank { "मातोश्री कलेक्शन" }
            val shopAddress = settings.address.ifBlank { "कोंढारकी, पंढरपूर" }
            val shopMobile = settings.mobile.ifBlank { "8208610261" }

            val dueDateText = if (!dueDate.isNullOrBlank()) {
                "उधारी भरण्याची ठरलेली तारीख: $dueDate"
            } else {
                "दिनांक: ${DateUtils.todayDate()}"
            }

            val messageText = """
नमस्कार $custName,

$shopName कडून नम्र आठवण.
आपल्या खात्यावरील शिल्लक उधारी रक्कम: ${CurrencyFormatter.format(outstandingDue)}
$dueDateText

कृपया आपली बाकी उधारी वेळेवर जमा करून सहकार्य करावे आणि आपल्या दुकानाला भेट द्यावी ही नम्र विनंती.
उर्वरित उधारी पेमेंटसाठी आपण दुकानात रोख देऊ शकता किंवा फोनपे/गुगलपे (UPI) द्वारे ${settings.upiId.ifBlank { shopMobile }} वर पाठवू शकता.

दुकान: $shopName, $shopAddress
मोबाईल: $shopMobile${if (settings.upiId.isNotBlank()) "\nUPI ID: ${settings.upiId}" else ""}

धन्यवाद!
            """.trimIndent()

            val customTemplate = getReminderPayloadTemplate(context)
            val jsonPayloadString = if (customTemplate.isNotBlank()) {
                customTemplate
                    .replace("{phone}", waDestination)
                    .replace("{to}", waDestination)
                    .replace("{mobile}", clean10)
                    .replace("{customerName}", JSONObject.quote(custName).removeSurrounding("\""))
                    .replace("{billNumber}", "UDHARI-REMINDER")
                    .replace("{amount}", outstandingDue.toString())
                    .replace("{finalAmount}", outstandingDue.toString())
                    .replace("{outstandingDue}", outstandingDue.toString())
                    .replace("{dueDate}", dueDate ?: DateUtils.todayDate())
                    .replace("{shopName}", JSONObject.quote(shopName).removeSurrounding("\""))
                    .replace("{shopMobile}", shopMobile)
                    .replace("{message}", JSONObject.quote(messageText).removeSurrounding("\""))
            } else {
                val json = JSONObject()
                json.put("messaging_product", "whatsapp")
                json.put("to", waDestination)
                json.put("phone", waDestination)
                json.put("mobile", clean10)
                json.put("destination", waDestination)
                json.put("recipient", waDestination)
                json.put("action", "udhari_reminder")
                json.put("type", "template")
                json.put("template_name", "udhari_reminder")

                // Meta / WACRM Cloud API Template Object
                val templateObj = JSONObject()
                templateObj.put("name", "udhari_reminder")
                templateObj.put("language", JSONObject().put("code", "mr"))
                val componentsArr = org.json.JSONArray()
                val bodyComp = JSONObject()
                bodyComp.put("type", "body")
                val paramsArr = org.json.JSONArray()
                paramsArr.put(JSONObject().put("type", "text").put("text", custName))
                paramsArr.put(JSONObject().put("type", "text").put("text", CurrencyFormatter.format(outstandingDue)))
                paramsArr.put(JSONObject().put("type", "text").put("text", dueDate ?: DateUtils.todayDate()))
                paramsArr.put(JSONObject().put("type", "text").put("text", settings.upiId.ifBlank { shopMobile }))
                bodyComp.put("parameters", paramsArr)
                componentsArr.put(bodyComp)
                templateObj.put("components", componentsArr)
                json.put("template", templateObj)

                // Flat universal fields
                json.put("customerName", custName)
                json.put("billNumber", "UDHARI-REMINDER")
                json.put("amount", outstandingDue)
                json.put("outstandingDue", outstandingDue)
                json.put("dueDate", dueDate ?: DateUtils.todayDate())
                json.put("shopName", shopName)
                json.put("message", messageText)
                json.put("text", messageText)
                json.put("body", messageText)
                val rawParams = org.json.JSONArray()
                rawParams.put(custName)
                rawParams.put(CurrencyFormatter.format(outstandingDue))
                rawParams.put(dueDate ?: DateUtils.todayDate())
                rawParams.put(settings.upiId.ifBlank { shopMobile })
                json.put("parameters", rawParams)
                json.put("params", rawParams)
                json.toString()
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonPayloadString.toRequestBody(mediaType)

            val requestBuilder = Request.Builder()
                .url(webhookUrl)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")

            val apiKey = getApiKey(context)
            if (apiKey.isNotBlank()) {
                val headerName = getHeaderName(context).ifBlank { "Authorization" }
                val headerValue = if (headerName.equals("Authorization", ignoreCase = true) && !apiKey.startsWith("Bearer ", ignoreCase = true)) {
                    "Bearer $apiKey"
                } else {
                    apiKey
                }
                requestBuilder.addHeader(headerName, headerValue)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                Log.i(TAG, "WhatsApp Udhari reminder successfully dispatched to $waDestination: ${response.code}")
                updateClaimStatus(clean10, "SENT", null)
                recordReminderLog(context, custName, clean10, outstandingDue, reminderType, "SUCCESS", null)
                if (!silent) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "$custName यांना व्हॉट्सॲपवर उधारी आठवण मेसेज पाठवला", Toast.LENGTH_SHORT).show()
                    }
                }
                Result.success(responseBody)
            } else {
                val err = "HTTP ${response.code}: $responseBody"
                Log.e(TAG, "WhatsApp API responded with error ${response.code}: $responseBody")
                updateClaimStatus(clean10, "FAILED", err)
                recordReminderLog(context, custName, clean10, outstandingDue, reminderType, "FAILED", err)
                if (!silent) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "व्हॉट्सॲप API एरर (${response.code}): $responseBody", Toast.LENGTH_LONG).show()
                    }
                }
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            val err = e.message ?: "Unknown error"
            Log.e(TAG, "WhatsApp Udhari reminder exception: $err", e)
            updateClaimStatus(clean10, "FAILED", err)
            recordReminderLog(context, custName, clean10, outstandingDue, reminderType, "FAILED", err)
            if (!silent) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "व्हॉट्सॲप त्रुटी: $err", Toast.LENGTH_LONG).show()
                }
            }
            Result.failure(e)
        }
    }

    /**
     * Udhari Cleared confirmation messages are disabled per user policy.
     * Only the Udhari Reminder template is sent from this app.
     */
    suspend fun sendUdhariClearedWhatsApp(
        context: Context,
        customerName: String,
        customerMobile: String?,
        amountPaid: Double,
        remainingBalance: Double = 0.0,
        paymentMethod: String = "CASH",
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot()
    ): Result<String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Udhari cleared message disabled per policy. Only udhari reminders are configured.")
        Result.success("Udhari cleared message disabled per policy.")
    }

    /**
     * Centralized batch reminder dispatch: Scans all customers with pending balance across
     * Cloud Firestore and local Room database, and dispatches automated WhatsApp reminders.
     * Uses ShopMetricsCalculator.calculateCustomerLedgerBalances to guarantee the exact total balance.
     */
    suspend fun dispatchBulkUdhariReminders(
        context: Context,
        onProgress: ((current: Int, total: Int, name: String) -> Unit)? = null
    ): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        try {
            val webhookUrl = getWebhookUrl(context)
            if (webhookUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("कृपया आधी सेटिंग्जमध्ये व्हॉट्सॲप Webhook URL सेव्ह करा."))
            }

            // 1. Fetch from Local Room database
            val db = com.matoshree.shopmanager.data.local.AppDatabase.getInstance(context)
            val localBills = db.billDao().getAllBillsDirect(10000).map { it.toDomain() }
            val localCustomers = db.customerDao().getAllCustomersDirect().map { it.toDomain() }

            // 2. Fetch Udhari transactions (from Firestore or cache)
            val transactions = mutableListOf<com.matoshree.shopmanager.domain.model.UdhariTransaction>()
            try {
                if (com.matoshree.shopmanager.data.firebase.FirebaseManager.isNetworkAvailable()) {
                    com.matoshree.shopmanager.data.firebase.FirebaseManager.ensureAuthenticated()
                    val txSnap = com.matoshree.shopmanager.data.firebase.FirebaseManager.udhariTransactionsCollection().limit(2000).get().await()
                    txSnap.documents.mapNotNullTo(transactions) { doc ->
                        doc.toObject(com.matoshree.shopmanager.data.firebase.UdhariTransactionDto::class.java)?.apply { id = doc.id }?.toDomain()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bulk reminders tx fetch note: ${e.message}")
            }

            // 3. Merge Customers from Cloud if available
            val allCustomers = localCustomers.toMutableList()
            try {
                if (com.matoshree.shopmanager.data.firebase.FirebaseManager.isNetworkAvailable()) {
                    val custSnap = com.matoshree.shopmanager.data.firebase.FirebaseManager.customersCollection().get().await()
                    val cloudCusts = custSnap.documents.mapNotNull { doc ->
                        doc.toObject(com.matoshree.shopmanager.data.firebase.CustomerDto::class.java)?.apply { id = doc.id }?.toDomain()
                    }
                    allCustomers.addAll(cloudCusts)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bulk reminders cust fetch note: ${e.message}")
            }

            val uniqueCustomers = allCustomers.distinctBy { it.canonicalKey() }
            val ledgerBalances = com.matoshree.shopmanager.domain.usecase.ShopMetricsCalculator.calculateCustomerLedgerBalances(
                activeBills = localBills,
                transactions = transactions,
                customers = uniqueCustomers
            )
            val keyResolver = com.matoshree.shopmanager.domain.usecase.ShopMetricsCalculator.buildCustomerKeyResolver(
                customers = uniqueCustomers,
                bills = localBills,
                transactions = transactions
            )

            // Map: clean10 -> Pair(Name, DueAmount)
            val pendingMap = mutableMapOf<String, Pair<String, Double>>()

            for (cust in uniqueCustomers) {
                val clean = PhoneUtils.clean10DigitMobileInput(cust.mobile)
                if (clean.length == 10 && PhoneUtils.isValidIndianMobile(clean)) {
                    val custKey = keyResolver(cust.mobile, cust.name, cust.id) ?: "mob_$clean"
                    val due = ledgerBalances[custKey] ?: 0.0
                    if (due > 0.01) {
                        pendingMap[clean] = cust.name to due
                    }
                }
            }

            // Also check any customers who exist in bills with pending amount
            for (b in localBills.filter { !it.isVoided && it.pendingAmount > 0.01 }) {
                val mob = b.customerMobile ?: ""
                val clean = PhoneUtils.clean10DigitMobileInput(mob)
                if (clean.length == 10 && PhoneUtils.isValidIndianMobile(clean) && !pendingMap.containsKey(clean)) {
                    val custKey = keyResolver(b.customerMobile, b.customerName, b.customerId) ?: "mob_$clean"
                    val due = ledgerBalances[custKey] ?: b.pendingAmount
                    if (due > 0.01) {
                        pendingMap[clean] = (b.customerName ?: "ग्राहक") to due
                    }
                }
            }

            // Safety Cap: Maximum 200 messages per run
            val recipients = pendingMap.entries.take(200)
            val total = recipients.size
            if (total == 0) {
                return@withContext Result.success(0 to 0)
            }

            var sentCount = 0
            var failedCount = 0
            var currentIndex = 0

            for ((mobile, nameAndDue) in recipients) {
                currentIndex++
                val (custName, due) = nameAndDue
                onProgress?.invoke(currentIndex, total, custName)

                // Distributed deduplication: claim per customer per day
                val canClaim = claimDailyReminder(mobile, custName, due, "MONDAY_BULK")
                if (!canClaim) {
                    Log.d(TAG, "Bulk reminder for $custName ($mobile) skipped: already claimed or sent today.")
                    continue
                }

                try {
                    val res = sendUdhariReminderWhatsApp(
                        context = context,
                        customerName = custName,
                        customerMobile = mobile,
                        outstandingDue = due,
                        dueDate = null,
                        reminderType = "MONDAY_BULK",
                        silent = true
                    )
                    if (res.isSuccess) {
                        sentCount++
                    } else {
                        failedCount++
                    }
                } catch (e: Exception) {
                    failedCount++
                }

                // Minimum 800ms rate limiting delay between sends
                kotlinx.coroutines.delay(800L)
            }

            Result.success(sentCount to failedCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sends an immediate test message using the Udhari Reminder template to verify the webhook URL and API credentials.
     */
    suspend fun testWebhook(
        context: Context,
        testMobile: String
    ): Result<String> = withContext(Dispatchers.IO) {
        sendUdhariReminderWhatsApp(
            context = context,
            customerName = "चाचणी ग्राहक",
            customerMobile = testMobile,
            outstandingDue = 500.0,
            dueDate = DateUtils.todayDate()
        )
    }

    suspend fun testActionTemplate(
        context: Context,
        testMobile: String,
        actionType: String = "REMINDER"
    ): Result<String> = withContext(Dispatchers.IO) {
        sendUdhariReminderWhatsApp(
            context = context,
            customerName = "चाचणी ग्राहक",
            customerMobile = testMobile,
            outstandingDue = 500.0,
            dueDate = DateUtils.todayDate()
        )
    }
}

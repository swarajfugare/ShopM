package com.matoshree.shopmanager.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import com.matoshree.shopmanager.data.firebase.CustomerDto
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

    fun isConfigured(context: Context): Boolean {
        return getWebhookUrl(context).isNotBlank()
    }

    fun isAutoSendEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return if (prefs.contains(KEY_ENABLED)) {
            prefs.getBoolean(KEY_ENABLED, true)
        } else {
            getWebhookUrl(context).isNotBlank()
        }
    }

    fun setAutoSendEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getWebhookUrl(context: Context): String {
        return getPrefs(context).getString(KEY_WEBHOOK_URL, "") ?: ""
    }

    fun setWebhookUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_WEBHOOK_URL, url.trim()).apply()
    }

    fun getApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
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
        try {
            // User requirement: Only send udhari reminder message, do NOT send bill messages.
            if (bill.pendingAmount <= 0.01) {
                Log.d(TAG, "Paid bill with 0 balance. WhatsApp bill messages are disabled (only udhari reminders enabled).")
                return@withContext Result.success("Paid bill - no message sent")
            }

            if (!isAutoSendEnabled(context)) {
                Log.d(TAG, "WhatsApp auto-send is disabled.")
                return@withContext Result.failure(IllegalStateException("WhatsApp auto-send is disabled."))
            }

            var rawMobile = bill.customerMobile
            if (rawMobile.isNullOrBlank() && bill.customerId != null && bill.customerId > 0) {
                try {
                    rawMobile = com.matoshree.shopmanager.data.local.AppDatabase.getInstance(context)
                        .customerDao()
                        .getCustomerById(bill.customerId)?.mobile
                } catch (_: Exception) {}
            }

            if (rawMobile.isNullOrBlank() || !PhoneUtils.isValidIndianMobile(rawMobile)) {
                Log.d(TAG, "Customer has no valid 10-digit mobile number (${rawMobile}). Skipping WhatsApp reminder.")
                return@withContext Result.failure(IllegalArgumentException("Customer has no valid 10-digit mobile number."))
            }

            val customerName = (bill.customerName?.takeIf { it.isNotBlank() } ?: "ग्राहक")

            // Send exclusively via the Udhari Reminder template
            sendUdhariReminderWhatsApp(
                context = context,
                customerName = customerName,
                customerMobile = rawMobile,
                outstandingDue = bill.pendingAmount,
                dueDate = bill.dueDate,
                settings = settings
            )
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp auto-send reminder exception: ${e.message}", e)
            Result.failure(e)
        }
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
        settings: PrintShareHelper.ShopSettingsSnapshot = PrintShareHelper.ShopSettingsSnapshot()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val webhookUrl = getWebhookUrl(context)
            if (webhookUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("WhatsApp webhook URL is not configured."))
            }

            if (customerMobile.isBlank() || !PhoneUtils.isValidIndianMobile(customerMobile)) {
                return@withContext Result.failure(IllegalArgumentException("Customer has no valid 10-digit mobile number."))
            }

            val clean10 = PhoneUtils.clean10DigitMobileInput(customerMobile)
            val waDestination = "91$clean10"
            val custName = customerName.ifBlank { "ग्राहक" }
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
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "$custName यांना व्हॉट्सॲपवर उधारी आठवण मेसेज पाठवला", Toast.LENGTH_SHORT).show()
                }
                Result.success(responseBody)
            } else {
                Log.e(TAG, "WhatsApp API responded with error ${response.code}: $responseBody")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "व्हॉट्सॲप API एरर (${response.code}): $responseBody", Toast.LENGTH_LONG).show()
                }
                Result.failure(Exception("HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp Udhari reminder exception: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "व्हॉट्सॲप त्रुटी: ${e.message}", Toast.LENGTH_LONG).show()
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

            // Map: clean10 -> Pair(Name, DueAmount)
            val pendingMap = mutableMapOf<String, Pair<String, Double>>()

            // 1. From Cloud Firestore customers collection
            try {
                if (com.matoshree.shopmanager.data.firebase.FirebaseManager.isNetworkAvailable()) {
                    com.matoshree.shopmanager.data.firebase.FirebaseManager.ensureAuthenticated()
                    val custSnap = com.matoshree.shopmanager.data.firebase.FirebaseManager.customersCollection().get().await()
                    custSnap.documents.forEach { doc ->
                        val cust = doc.toObject(com.matoshree.shopmanager.data.firebase.CustomerDto::class.java)
                        if (cust != null && cust.status != "ARCHIVED" && cust.status != "TRASH" && cust.outstandingAmount > 0.01 && !cust.mobile.isNullOrBlank()) {
                            val clean = PhoneUtils.clean10DigitMobileInput(cust.mobile)
                            if (clean.length == 10) {
                                pendingMap[clean] = cust.name to cust.outstandingAmount
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Firestore customers fetch for bulk reminders: ${e.message}")
            }

            // 2. From Local Room database (active bills & customers)
            try {
                val db = com.matoshree.shopmanager.data.local.AppDatabase.getInstance(context)
                val allBills = db.billDao().getAllBillsDirect(500)
                val pendingBills = allBills.filter { !it.bill.isVoided && it.bill.paymentStatus != com.matoshree.shopmanager.domain.model.PaymentStatus.PAID }

                for (bWithItems in pendingBills) {
                    val b = bWithItems.bill
                    val mob = b.customerMobile
                    if (!mob.isNullOrBlank()) {
                        val clean = PhoneUtils.clean10DigitMobileInput(mob)
                        if (clean.length == 10 && !pendingMap.containsKey(clean)) {
                            val due = if (b.finalAmount > 0) b.finalAmount else 0.0
                            if (due > 0.01) {
                                pendingMap[clean] = (b.customerName ?: "ग्राहक") to due
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Local Room fetch for bulk reminders: ${e.message}")
            }

            val total = pendingMap.size
            if (total == 0) {
                return@withContext Result.success(0 to 0)
            }

            var sentCount = 0
            var failedCount = 0
            var currentIndex = 0

            for ((mobile, nameAndDue) in pendingMap) {
                currentIndex++
                val (custName, due) = nameAndDue
                onProgress?.invoke(currentIndex, total, custName)

                try {
                    val res = sendUdhariReminderWhatsApp(
                        context = context,
                        customerName = custName,
                        customerMobile = mobile,
                        outstandingDue = due,
                        dueDate = null
                    )
                    if (res.isSuccess) {
                        sentCount++
                    } else {
                        failedCount++
                    }
                } catch (e: Exception) {
                    failedCount++
                }
                kotlinx.coroutines.delay(600)
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

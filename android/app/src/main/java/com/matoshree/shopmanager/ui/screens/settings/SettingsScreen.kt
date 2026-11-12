package com.matoshree.shopmanager.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.matoshree.shopmanager.utils.PhoneUtils
import com.matoshree.shopmanager.printer.BluetoothPrinterDevice
import com.matoshree.shopmanager.printer.BluetoothPrinterManager
import com.matoshree.shopmanager.printer.ThermalPrinterManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.local.dao.SettingsDao
import com.matoshree.shopmanager.data.local.entity.SettingsEntity
import com.matoshree.shopmanager.data.remote.api.MatoshreeApiService
import com.matoshree.shopmanager.data.remote.dto.ChangePinRequest
import com.matoshree.shopmanager.data.remote.dto.RecoverPinRequest
import kotlinx.coroutines.tasks.await
import com.matoshree.shopmanager.data.remote.dto.UpdateSettingsRequest
import com.matoshree.shopmanager.data.repository.SyncRepository
import com.matoshree.shopmanager.security.PinManager
import com.matoshree.shopmanager.sync.SyncManager
import com.matoshree.shopmanager.sync.AppSyncState
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.PrintShareHelper
import com.matoshree.shopmanager.utils.WhatsAppWebhookManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class SettingsViewModel(
    private val syncRepository: SyncRepository,
    private val pinManager: PinManager,
    private val settingsDao: SettingsDao,
    private val apiService: MatoshreeApiService? = null
) : ViewModel() {

    val pendingSyncCount = MutableStateFlow(0)
    val isBiometricEnabled = MutableStateFlow(pinManager.isBiometricEnabled())
    val profitMargin = MutableStateFlow(25.0)

    // Shop Settings State
    val shopName = MutableStateFlow("Matoshree Collection")
    val shopMobile = MutableStateFlow("+91 98765 43210")
    val shopEmail = MutableStateFlow("contact@matoshree.in")
    val shopAddress = MutableStateFlow("Shop No. 4, Silk Heritage Complex, Kolhapur")
    val shopGstin = MutableStateFlow("27AAAAA0000A1Z5")
    val showGstinOnBill = MutableStateFlow(true)
    val logoPath = MutableStateFlow<String?>(null)

    // Payment Settings State
    val upiId = MutableStateFlow("matoshree@upi")
    val upiDisplayName = MutableStateFlow("Matoshree Collection")
    val upiMobile = MutableStateFlow("+919876543210")

    private val firestoreSettingsRepo = com.matoshree.shopmanager.data.firebase.FirestoreSettingsRepository()

    init {
        viewModelScope.launch {
            syncRepository.pendingSyncCount.collectLatest {
                pendingSyncCount.value = it
            }
        }
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            firestoreSettingsRepo.observeSettings().collectLatest { snap ->
                shopName.value = snap.shopName
                shopMobile.value = snap.mobile
                shopEmail.value = snap.email ?: ""
                shopAddress.value = snap.address
                shopGstin.value = snap.gstin ?: ""
                showGstinOnBill.value = snap.showGstin
                logoPath.value = snap.logoPath
                upiId.value = snap.upiId
                upiDisplayName.value = snap.upiDisplayName
            }
        }
    }

    fun saveLogo(context: Context, uri: Uri) {
        viewModelScope.launch {
            val localPath = com.matoshree.shopmanager.data.firebase.FirebaseStorageManager.uploadLogo(context, uri)
            if (localPath != null) {
                logoPath.value = localPath
                settingsDao.set(SettingsEntity("shop_logo_data", localPath))
                settingsDao.set(SettingsEntity("logo_path", localPath))
            }
        }
    }

    fun removeLogo(context: Context) {
        viewModelScope.launch {
            com.matoshree.shopmanager.data.firebase.FirebaseStorageManager.removeLogo(context)
            logoPath.value = null
            settingsDao.set(SettingsEntity("shop_logo_data", ""))
            settingsDao.set(SettingsEntity("logo_path", ""))
        }
    }

    fun saveShopSettings(name: String, mobile: String, email: String, address: String, gstin: String, showGstin: Boolean) {
        viewModelScope.launch {
            shopName.value = name.trim()
            shopMobile.value = mobile.trim()
            shopEmail.value = email.trim()
            shopAddress.value = address.trim()
            shopGstin.value = gstin.trim()
            showGstinOnBill.value = showGstin

            settingsDao.set(SettingsEntity("shop_name", name.trim()))
            settingsDao.set(SettingsEntity("shop_mobile", mobile.trim()))
            settingsDao.set(SettingsEntity("shop_email", email.trim()))
            settingsDao.set(SettingsEntity("shop_address", address.trim()))
            settingsDao.set(SettingsEntity("shop_gstin", gstin.trim()))
            settingsDao.set(SettingsEntity("show_gstin", showGstin.toString()))

            firestoreSettingsRepo.saveShopSettings(name, mobile, email, address, gstin, showGstin)
        }
    }

    fun savePaymentSettings(id: String, displayName: String, mobile: String) {
        viewModelScope.launch {
            upiId.value = id.trim()
            upiDisplayName.value = displayName.trim()
            upiMobile.value = mobile.trim()

            settingsDao.set(SettingsEntity("upi_id", id.trim()))
            settingsDao.set(SettingsEntity("upi_display_name", displayName.trim()))
            settingsDao.set(SettingsEntity("upi_mobile", mobile.trim()))

            firestoreSettingsRepo.savePaymentSettings(id, displayName, mobile)
        }
    }

    fun toggleBiometric(enabled: Boolean) {
        pinManager.setBiometricEnabled(enabled)
        isBiometricEnabled.value = enabled
    }

    fun changePin(currentPin: String, newPin: String, confirmPin: String, onResult: (String?) -> Unit) {
        if (currentPin.isBlank() || newPin.isBlank() || confirmPin.isBlank()) {
            onResult("All PIN fields are required")
            return
        }
        if (newPin.length != 4 || !newPin.all { it.isDigit() }) {
            onResult("New PIN must be exactly 4 digits")
            return
        }
        if (newPin != confirmPin) {
            onResult("New PIN and Confirm PIN do not match")
            return
        }

        viewModelScope.launch {
            val firestorePin = com.matoshree.shopmanager.data.firebase.FirestorePinManager(com.matoshree.shopmanager.data.firebase.FirebaseManager.firestore.app.applicationContext)
            val res = firestorePin.changePin(currentPin, newPin)
            if (res.isSuccess) {
                pinManager.savePin(newPin)
                onResult(null)
            } else {
                onResult(res.exceptionOrNull()?.message ?: "Failed to update PIN")
            }
        }
    }

    fun recoverPin(recoveryCode: String, newPin: String, confirmPin: String, onResult: (String?) -> Unit) {
        if (recoveryCode.isBlank() || newPin.isBlank() || confirmPin.isBlank()) {
            onResult("All fields are required")
            return
        }
        if (newPin.length != 4 || !newPin.all { it.isDigit() }) {
            onResult("New PIN must be exactly 4 digits")
            return
        }
        if (newPin != confirmPin) {
            onResult("New PIN and Confirm PIN do not match")
            return
        }

        viewModelScope.launch {
            val firestorePin = com.matoshree.shopmanager.data.firebase.FirestorePinManager(com.matoshree.shopmanager.data.firebase.FirebaseManager.firestore.app.applicationContext)
            val res = firestorePin.recoverPin(recoveryCode, newPin)
            if (res.isSuccess) {
                pinManager.savePin(newPin)
                onResult(null)
            } else {
                onResult(res.exceptionOrNull()?.message ?: "Failed to recover PIN")
            }
        }
    }

    data class RealtimeDiagnostics(
        val isOnline: Boolean = true,
        val firestoreStatus: String = "Cloud Firestore (Verified Online)",
        val customersCount: Int = 0,
        val productsCount: Int = 0,
        val billsCount: Int = 0,
        val udhariTxCount: Int = 0,
        val expensesCount: Int = 0,
        val deviceModel: String = android.os.Build.MODEL ?: "Android Device",
        val lastCheckTime: String = ""
    )

    val serverDiagnostics = MutableStateFlow<RealtimeDiagnostics?>(null)

    fun loadDiagnostics() {
        viewModelScope.launch {
            try {
                com.matoshree.shopmanager.data.firebase.FirebaseManager.ensureAuthenticated()
                val nowStr = java.text.SimpleDateFormat("hh:mm:ss a, dd MMM", java.util.Locale.getDefault()).format(java.util.Date())

                // Check both root and shop collections
                val rootCustSnap = try { com.matoshree.shopmanager.data.firebase.FirebaseManager.customersCollection().get().await() } catch (e: Exception) { null }
                val shopCustSnap = try { com.matoshree.shopmanager.data.firebase.FirebaseManager.shopCustomersCollection().get().await() } catch (e: Exception) { null }
                val totalCustDocs = (rootCustSnap?.documents ?: emptyList()) + (shopCustSnap?.documents ?: emptyList())
                val activeCust = totalCustDocs.distinctBy { it.id }.count { doc ->
                    val s = doc.getString("status")
                    s != "ARCHIVED" && s != "DELETED"
                }

                val rootProdSnap = try { com.matoshree.shopmanager.data.firebase.FirebaseManager.productsCollection().get().await() } catch (e: Exception) { null }
                val shopProdSnap = try { com.matoshree.shopmanager.data.firebase.FirebaseManager.shopProductsCollection().get().await() } catch (e: Exception) { null }
                val totalProdDocs = (rootProdSnap?.documents ?: emptyList()) + (shopProdSnap?.documents ?: emptyList())
                val activeProd = totalProdDocs.distinctBy { it.id }.count { doc ->
                    val s = doc.getString("status")
                    val act = doc.getBoolean("isActive") ?: true
                    s != "ARCHIVED" && s != "DELETED" && act
                }

                val rootBillsSnap = try { com.matoshree.shopmanager.data.firebase.FirebaseManager.billsCollection().get().await() } catch (e: Exception) { null }
                val shopBillsSnap = try { com.matoshree.shopmanager.data.firebase.FirebaseManager.shopBillsCollection().get().await() } catch (e: Exception) { null }
                val totalBillDocs = (rootBillsSnap?.documents ?: emptyList()) + (shopBillsSnap?.documents ?: emptyList())
                val validBills = totalBillDocs.distinctBy { it.id }.count { doc ->
                    val s = doc.getString("status")
                    s != "VOIDED"
                }

                val udhariSnap = try { com.matoshree.shopmanager.data.firebase.FirebaseManager.udhariTransactionsCollection().get().await() } catch (e: Exception) { null }
                val validUdhari = udhariSnap?.documents?.count { doc ->
                    val rev = doc.getBoolean("isReversed") ?: false
                    !rev
                } ?: 0

                val expSnap = try { com.matoshree.shopmanager.data.firebase.FirebaseManager.expensesCollection().get().await() } catch (e: Exception) { null }
                val totalExp = expSnap?.size() ?: 0

                val hasAnySuccess = rootCustSnap != null || shopCustSnap != null || rootProdSnap != null || shopProdSnap != null || rootBillsSnap != null

                if (hasAnySuccess) {
                    serverDiagnostics.value = RealtimeDiagnostics(
                        isOnline = true,
                        firestoreStatus = "Cloud Firestore (Online)",
                        customersCount = activeCust,
                        productsCount = activeProd,
                        billsCount = validBills,
                        udhariTxCount = validUdhari,
                        expensesCount = totalExp,
                        deviceModel = android.os.Build.MODEL ?: "Android Device",
                        lastCheckTime = nowStr
                    )
                } else {
                    // Probe for exact exception to display specific cause
                    com.matoshree.shopmanager.data.firebase.FirebaseManager.customersCollection().get().await()
                }
            } catch (e: Exception) {
                val errMsg = e.message ?: e.localizedMessage ?: "Connection error"
                val statusText = when {
                    errMsg.contains("PERMISSION_DENIED", ignoreCase = true) ->
                        "Firebase Rules Blocked (403)"
                    errMsg.contains("UNAVAILABLE", ignoreCase = true) || errMsg.contains("network", ignoreCase = true) ->
                        "Offline (Server Unavailable)"
                    errMsg.contains("NOT_FOUND", ignoreCase = true) ->
                        "Database Not Found"
                    else ->
                        "Offline: ${errMsg.take(45)}"
                }
                serverDiagnostics.value = RealtimeDiagnostics(
                    isOnline = false,
                    firestoreStatus = statusText,
                    lastCheckTime = java.text.SimpleDateFormat("hh:mm:ss a", java.util.Locale.getDefault()).format(java.util.Date())
                )
            }
        }
    }

    init {
        loadDiagnostics()
    }

    fun triggerSync(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = syncRepository.performSync()
            loadDiagnostics()
            onComplete(success)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    syncManager: SyncManager? = null,
    onNavigateToTrash: () -> Unit = {},
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val pendingSync by viewModel.pendingSyncCount.collectAsState()
    val biometricEnabled by viewModel.isBiometricEnabled.collectAsState()
    val margin by viewModel.profitMargin.collectAsState()

    val currentShopName by viewModel.shopName.collectAsState()
    val currentShopMobile by viewModel.shopMobile.collectAsState()
    val currentShopEmail by viewModel.shopEmail.collectAsState()
    val currentShopAddress by viewModel.shopAddress.collectAsState()
    val currentShopGstin by viewModel.shopGstin.collectAsState()
    val currentShowGstin by viewModel.showGstinOnBill.collectAsState()
    val currentLogoPath by viewModel.logoPath.collectAsState()

    val currentUpiId by viewModel.upiId.collectAsState()
    val currentUpiName by viewModel.upiDisplayName.collectAsState()

    val syncState = syncManager?.syncState?.collectAsState()?.value ?: AppSyncState.SYNCED
    val lastSyncTimestamp = syncManager?.lastSyncTimestamp?.collectAsState()?.value ?: 0L
    val diagnostics by viewModel.serverDiagnostics.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadDiagnostics()
    }

    var showEditShopDialog by remember { mutableStateOf(false) }
    var showEditUpiDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showForgotPinDialog by remember { mutableStateOf(false) }
    var showPrinterSelectionDialog by remember { mutableStateOf(false) }
    var isTestingPrint by remember { mutableStateOf(false) }

    var waAutoSendEnabled by remember { mutableStateOf(WhatsAppWebhookManager.isAutoSendEnabled(context)) }
    var waUdhariReminderEnabled by remember { mutableStateOf(WhatsAppWebhookManager.isUdhariReminderEnabled(context)) }
    var waWebhookUrl by remember { mutableStateOf(WhatsAppWebhookManager.getWebhookUrl(context)) }
    var waApiKey by remember { mutableStateOf(WhatsAppWebhookManager.getApiKey(context)) }
    var waHeaderName by remember { mutableStateOf(WhatsAppWebhookManager.getHeaderName(context)) }
    var waPayloadTemplate by remember { mutableStateOf(WhatsAppWebhookManager.getPayloadTemplate(context)) }
    var waBillTemplate by remember { mutableStateOf(WhatsAppWebhookManager.getBillPayloadTemplate(context)) }
    var waReminderTemplate by remember { mutableStateOf(WhatsAppWebhookManager.getReminderPayloadTemplate(context)) }
    var waClearedTemplate by remember { mutableStateOf(WhatsAppWebhookManager.getClearedPayloadTemplate(context)) }
    var waBillTemplateName by remember { mutableStateOf(WhatsAppWebhookManager.getBillTemplateName(context)) }
    var waReminderTemplateName by remember { mutableStateOf(WhatsAppWebhookManager.getReminderTemplateName(context)) }
    var waClearedTemplateName by remember { mutableStateOf(WhatsAppWebhookManager.getClearedTemplateName(context)) }
    var selectedBillTemplateOption by remember { mutableStateOf(WhatsAppWebhookManager.getSelectedBillTemplateType(context)) }
    var selectedTemplateTab by remember { mutableStateOf(0) } // 0: Bill, 1: Reminder, 2: Cleared
    var isSendingBulkReminders by remember { mutableStateOf(false) }
    var showBulkReminderConfirmDialog by remember { mutableStateOf(false) }
    var bulkReminderStatusText by remember { mutableStateOf("") }
    var showWaTestDialog by remember { mutableStateOf(false) }
    var showActionTestDialog by remember { mutableStateOf<String?>(null) }
    var isTestingWaWebhook by remember { mutableStateOf(false) }
    var waTestPhone by remember { mutableStateOf("") }

    var multiDeviceAlertsEnabled by remember { mutableStateOf(true) }
    var udhariRemindersEnabled by remember { mutableStateOf(true) }
    var dailyClosingReminderEnabled by remember { mutableStateOf(true) }
    var stockAlertsEnabled by remember { mutableStateOf(true) }

    val coroutineScope = rememberCoroutineScope()
    val bluetoothPrinterManager = remember { BluetoothPrinterManager(context) }
    val thermalPrinterManager = remember { ThermalPrinterManager(context) }
    var selectedPrinter by remember { mutableStateOf(bluetoothPrinterManager.getSelectedPrinter()) }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            showPrinterSelectionDialog = true
        } else {
            Toast.makeText(context, "Bluetooth permission required to connect to thermal printer", Toast.LENGTH_LONG).show()
        }
    }

    val logoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.saveLogo(context, it)
            Toast.makeText(context, "Logo updated & synced with MySQL", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "सेटिंग (Settings)",
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->

        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isTablet = configuration.screenWidthDp >= 600

        val renderShopLogoCard: @Composable () -> Unit = {
            MatoshreeCard(hasGoldTopBorder = true) {
                    Text("दुकानाचे लोगो आणि ब्रँडिंग", style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val logoBmp = remember(currentLogoPath) { PrintShareHelper.getShopLogoBitmap(context, currentLogoPath) }

                        Image(
                            bitmap = logoBmp.asImageBitmap(),
                            contentDescription = "Shop Logo Preview",
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .border(2.dp, ChampagneGold, CircleShape)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { logoPickerLauncher.launch("image/*") },
                                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = "Upload", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (currentLogoPath.isNullOrBlank()) "लोगो अपलोड करा" else "लोगो बदला", fontSize = 13.sp)
                                }

                                if (!currentLogoPath.isNullOrBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.removeLogo(context)
                                            Toast.makeText(context, "लोगो डीफॉल्टवर रीसेट केला", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("काढून टाका", fontSize = 13.sp, color = BoutiqueError)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "क्लाउड फायरस्टोअर आणि सर्व डिव्हाइसेसवर सुरक्षितपणे सेव्ह केले जाते.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                            )
                        }
                    }
                }
        }
        val renderShopInfoCard: @Composable () -> Unit = {
            MatoshreeCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("दुकानाची माहिती", style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold))
                        TextButton(onClick = { showEditShopDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = DeepEmerald, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("बदला", color = DeepEmerald, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(currentShopName, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    Text("मोबाईल नंबर: $currentShopMobile", style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                    Text("पत्ता: $currentShopAddress", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                    if (currentShopEmail.isNotBlank()) {
                        Text("ईमेल: $currentShopEmail", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = OutlineVariantGrey.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))

                    // GSTIN Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("बिलावर GSTIN दाखवा", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (currentShowGstin) "GSTIN: $currentShopGstin (दाखवला जाईल)" else "बिलावर GSTIN लपवला जाईल",
                                style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                            )
                        }
                        Switch(
                            checked = currentShowGstin,
                            onCheckedChange = {
                                viewModel.saveShopSettings(
                                    currentShopName,
                                    currentShopMobile,
                                    currentShopEmail,
                                    currentShopAddress,
                                    currentShopGstin,
                                    it
                                )
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = DeepEmerald, checkedTrackColor = DeepEmeraldContainer)
                        )
                    }
                }
        }
        val renderPaymentUpiCard: @Composable () -> Unit = {
            MatoshreeCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("यूपीआय पेमेंट सेटिंग", style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold))
                        TextButton(onClick = { showEditUpiDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit UPI", tint = DeepEmerald, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("बदला", color = DeepEmerald, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.QrCode, contentDescription = "QR", tint = ChampagneGold, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("यूपीआय आयडी: $currentUpiId", fontWeight = FontWeight.Bold)
                            Text("खातेदाराचे नाव: $currentUpiName", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "यूपीआय पेमेंट निवडल्यावर बिलावर डायनॅमिक क्यूआर कोड तयार केला जातो.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                    )
                }
        }
        val renderDataSyncCard: @Composable () -> Unit = {
            MatoshreeCard(hasGoldTopBorder = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("डेटा आणि सिंक (Sync)", style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = when {
                    pendingSync > 0 -> ChampagneGoldContainer
                    syncState == AppSyncState.OFFLINE -> BoutiqueError.copy(alpha = 0.15f)
                    syncState == AppSyncState.SYNCING -> ChampagneGoldContainer
                    else -> DeepEmeraldContainer
                }
            ) {
                Text(
                    text = when {
                        pendingSync > 0 -> "सिंक प्रलंबित ($pendingSync)"
                        syncState == AppSyncState.OFFLINE -> "ऑफलाईन मोड"
                        syncState == AppSyncState.SYNCING -> "सिंक होत आहे..."
                        else -> "कनेक्टेड • सिंक पूर्ण"
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        pendingSync > 0 -> DeepCharcoal
                        syncState == AppSyncState.OFFLINE -> BoutiqueError
                        syncState == AppSyncState.SYNCING -> DeepCharcoal
                        else -> DeepEmerald
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("डेटाबेस सर्व्हर:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                Text("Cloud Firestore (Two-Way Sync)", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("शेवटचे सिंक:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                val lastTimeStr = if (lastSyncTimestamp > 0) {
                    SimpleDateFormat("hh:mm:ss a, dd MMM", Locale.getDefault()).format(Date(lastSyncTimestamp))
                } else "आत्ताच (Auto Sync)"
                Text(lastTimeStr, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("प्रलंबित ऑफलाइन नोंदी:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                Text(
                    if (pendingSync > 0) "$pendingSync नोंदी प्रलंबित" else "सर्व डेटा सिंक आहे (0 प्रलंबित)",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (pendingSync == 0) BoutiqueSuccess else ChampagneGold
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (pendingSync > 0) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = ChampagneGoldContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = ChampagneGold, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "तुमच्याकडे $pendingSync ऑफलाइन नोंदी आहेत. इंटरनेट चालू झाल्यावर किंवा 'आता सिंक करा' दाबल्यावर त्या Cloud वर सुरक्षितपणे अपलोड होतील.",
                        style = MaterialTheme.typography.bodySmall.copy(color = DeepCharcoal, fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DeepEmeraldContainer.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = BoutiqueSuccess, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "सर्व डेटा क्लाउड व स्थानिक डेटाबेसमध्ये सुरक्षित आणि सिंक आहे.",
                        style = MaterialTheme.typography.bodySmall.copy(color = DeepEmerald, fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.triggerSync { success ->
                        syncManager?.triggerImmediateSync()
                        Toast.makeText(
                            context,
                            if (success) "सर्व डेटा क्लाउडसोबत यशस्वीरीत्या सिंक झाला!" else "सिंक अयशस्वी. इंटरनेट कनेक्शन तपासा.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Sync, contentDescription = "Sync", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("आता सिंक करा", fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = {
                    syncManager?.triggerImmediateSync()
                    viewModel.triggerSync { success ->
                        Toast.makeText(
                            context,
                            if (success) "सर्व्हरवरून संपूर्ण डेटा रीफ्रेश झाला!" else "रीफ्रेश अयशस्वी. इंटरनेट तपासा.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = "Full Sync", modifier = Modifier.size(16.dp), tint = DeepEmerald)
                Spacer(modifier = Modifier.width(6.dp))
                Text("क्लाउड रीफ्रेश", fontSize = 13.sp, color = DeepEmerald)
            }
        }
    }
        }
        val renderThermalPrinterCard: @Composable () -> Unit = {
            MatoshreeCard(hasGoldTopBorder = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "थर्मल प्रिंटर (५८ मिमी)",
                            style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold)
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedPrinter != null) DeepEmeraldContainer else SurfaceContainerLow
                        ) {
                            Text(
                                text = if (selectedPrinter != null) "● कनेक्टेड / तयार" else "○ जोडलेला नाही",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedPrinter != null) DeepEmerald else MutedCharcoal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedPrinter != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Print, contentDescription = "Printer", tint = DeepEmerald, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(selectedPrinter!!.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("MAC: ${selectedPrinter!!.address} • 58mm ESC/POS", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            }
                        }
                    } else {
                        Text(
                            "कोणताही ब्लूटूथ प्रिंटर निवडलेला नाही. ५८ मिमी प्रिंटर ब्लूटूथद्वारे कनेक्ट करून खाली निवडा.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    if (!bluetoothPrinterManager.hasBluetoothPermission()) {
                                        bluetoothPermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.BLUETOOTH_CONNECT,
                                                Manifest.permission.BLUETOOTH_SCAN
                                            )
                                        )
                                    } else {
                                        showPrinterSelectionDialog = true
                                    }
                                } else {
                                    showPrinterSelectionDialog = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (selectedPrinter == null) "प्रिंटर निवडा" else "बदला", fontSize = 13.sp)
                        }

                        if (selectedPrinter != null) {
                            OutlinedButton(
                                onClick = {
                                    if (isTestingPrint) return@OutlinedButton
                                    isTestingPrint = true
                                    coroutineScope.launch {
                                        try {
                                            val result = thermalPrinterManager.printTestReceipt()
                                            if (result.isSuccess) {
                                                Toast.makeText(context, "टेस्ट प्रिंट पाठवली गेली", Toast.LENGTH_SHORT).show()
                                            } else {
                                                val err = result.exceptionOrNull()?.message ?: "टेस्ट प्रिंट अयशस्वी"
                                                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                            }
                                        } finally {
                                            isTestingPrint = false
                                        }
                                    }
                                },
                                enabled = !isTestingPrint,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isTestingPrint) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = DeepEmerald)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("प्रिंट होत आहे...", fontSize = 13.sp)
                                } else {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = DeepEmerald)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("टेस्ट प्रिंट", fontSize = 13.sp, color = DeepEmerald)
                                }
                            }

                            TextButton(
                                onClick = {
                                    bluetoothPrinterManager.forgetPrinter()
                                    selectedPrinter = null
                                    Toast.makeText(context, "प्रिंटर काढून टाकला", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("काढून टाका", fontSize = 13.sp, color = BoutiqueError)
                            }
                        }
                    }
                }
        }
        val renderWhatsAppWebhookCard: @Composable () -> Unit = {
            MatoshreeCard(hasGoldTopBorder = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "स्वयंचलित व्हॉट्सॲप (WhatsApp Webhook API)",
                                style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold)
                            )
                        }
                        Surface(
                            color = if (waAutoSendEnabled) ChampagneGoldContainer.copy(alpha = 0.5f) else OutlineVariantGrey.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                if (waAutoSendEnabled) "सक्रिय" else "बंद",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (waAutoSendEnabled) DeepEmerald else MutedCharcoal,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "बिल तयार झाल्यावर ग्राहकांच्या मोबाईल नंबरवर स्वयंचलित मेसेज पाठवा (Zero Server / Direct API).",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Auto Send Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("बिल झाल्यावर आपोआप मेसेज पाठवा", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("१०-अंकी ग्राहक नंबरवर थेट API द्वारे मेसेज जाईल", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                        Switch(
                            checked = waAutoSendEnabled,
                            onCheckedChange = {
                                waAutoSendEnabled = it
                                WhatsAppWebhookManager.setAutoSendEnabled(context, it)
                                Toast.makeText(context, if (it) "व्हॉट्सॲप ऑटो-सेंड सक्रिय केले" else "व्हॉट्सॲप ऑटो-सेंड बंद केले", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = WarmWhite, checkedTrackColor = DeepEmerald)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Auto Udhari Due Reminder Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("उधारी मुदतीची आठवण (Auto Udhari Due Reminder)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("ठरलेल्या मुदत तारखेला ग्राहकाला आपोआप व्हॉट्सॲपवर आठवण जाईल", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                        Switch(
                            checked = waUdhariReminderEnabled,
                            onCheckedChange = {
                                waUdhariReminderEnabled = it
                                WhatsAppWebhookManager.setUdhariReminderEnabled(context, it)
                                Toast.makeText(context, if (it) "उधारी आठवण ऑटो-सेंड सक्रिय केले" else "उधारी आठवण ऑटो-सेंड बंद केले", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = WarmWhite, checkedTrackColor = DeepEmerald)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = OutlineVariantGrey.copy(alpha = 0.2f))

                    // Webhook URL Input
                    OutlinedTextField(
                        value = waWebhookUrl,
                        onValueChange = { waWebhookUrl = it },
                        label = { Text("Webhook URL / Gateway Endpoint") },
                        placeholder = { Text("https://your-api-gateway.com/send-message") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DeepEmerald,
                            unfocusedBorderColor = OutlineVariantGrey
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // API Key / Token Input
                    OutlinedTextField(
                        value = waApiKey,
                        onValueChange = { waApiKey = it },
                        label = { Text("API Key / Bearer Token (पर्यायी)") },
                        placeholder = { Text("API Authorization Token") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DeepEmerald,
                            unfocusedBorderColor = OutlineVariantGrey
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Header Name (Optional)
                    OutlinedTextField(
                        value = waHeaderName,
                        onValueChange = { waHeaderName = it },
                        label = { Text("Header नाव (डिफॉल्ट: Authorization)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = DeepEmerald,
                            unfocusedBorderColor = OutlineVariantGrey
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Udhari Reminder Template Config (Only template active in the app)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "उधारी आठवण व्हॉट्सॲप टेम्पलेट (WACRM / Webhook)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = DeepEmerald
                        )
                        TextButton(
                            onClick = {
                                waReminderTemplate = "{\n  \"to\": \"{phone}\",\n  \"action\": \"udhari_reminder\",\n  \"template_name\": \"$waReminderTemplateName\",\n  \"customerName\": \"{customerName}\",\n  \"amount\": \"{outstandingDue}\",\n  \"dueDate\": \"{dueDate}\",\n  \"shopName\": \"{shopName}\",\n  \"message\": \"{message}\"\n}"
                            }
                        ) {
                            Text("डिफॉल्ट WACRM आठवण पेलोड भरा", fontSize = 10.sp, color = DeepEmerald)
                        }
                    }
                    Text(
                        "या ॲपमधून ग्राहकांना केवळ उधारी आठवण (Udhari Reminder) मेसेज पाठवला जातो. नवीन बिलाचा मेसेज पाठवला जात नाही.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 11.sp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = waReminderTemplateName,
                        onValueChange = { waReminderTemplateName = it },
                        label = { Text("WACRM टेम्पलेट नाव (उदा. udhari_reminder)") },
                        placeholder = { Text("WACRM मधील नेमके नाव टाका...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = waReminderTemplate,
                        onValueChange = { waReminderTemplate = it },
                        label = { Text("उधारी आठवण JSON पेलोड (रिकामे असल्यास ऑटो WACRM)") },
                        placeholder = { Text("WACRM नुसार JSON पेस्ट करा...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        minLines = 3,
                        maxLines = 6
                    )
                    Text(
                        text = "उपलब्ध टॅग्ज: {phone}, {mobile}, {customerName}, {amount}, {outstandingDue}, {dueDate}, {shopName}, {message}",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 10.sp),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { showActionTestDialog = "REMINDER" },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(14.dp), tint = DeepEmerald)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("🧪 उधारी आठवण मेसेजची चाचणी घ्या (Test)", fontSize = 11.sp, color = DeepEmerald)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Monday Automated Reminder Action
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DeepEmeraldContainer.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("सोमवार उधारी रिमाइंडर (Weekly Monday Automation)", fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "दर सोमवारी सकाळी आपोआप थकबाकीदार ग्राहकांना व्हॉट्सॲपवर उधारी आठवण मेसेज जातो.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    showBulkReminderConfirmDialog = true
                                },
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BoutiqueSuccess),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("📢 सर्व उधारी ग्राहकांना आत्ताच आठवण मेसेज पाठवा", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                WhatsAppWebhookManager.setWebhookUrl(context, waWebhookUrl)
                                WhatsAppWebhookManager.setApiKey(context, waApiKey)
                                WhatsAppWebhookManager.setHeaderName(context, waHeaderName)
                                WhatsAppWebhookManager.setReminderPayloadTemplate(context, waReminderTemplate)
                                WhatsAppWebhookManager.setReminderTemplateName(context, waReminderTemplateName)
                                WhatsAppWebhookManager.setAutoSendEnabled(context, waAutoSendEnabled)
                                WhatsAppWebhookManager.setUdhariReminderEnabled(context, waUdhariReminderEnabled)
                                Toast.makeText(context, "व्हॉट्सॲप Webhook व उधारी आठवण टेम्पलेट सेव्ह झाले!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("सेटिंग्ज सेव्ह करा", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                showWaTestDialog = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp), tint = DeepEmerald)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("टेस्ट मेसेज", fontSize = 13.sp, color = DeepEmerald)
                        }
                    }
                }
        }
        val renderNotificationsCard: @Composable () -> Unit = {
            MatoshreeCard(hasGoldTopBorder = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("स्मार्ट नोटिफिकेशन्स", style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold))
                        }
                        Surface(
                            color = ChampagneGoldContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("सक्रिय", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DeepEmerald, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 1. Multi-Device Realtime Sale Alerts
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("इतर डिव्हाइसवरील विक्री अलर्ट", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("इतर काउंटरवर बिल तयार झाल्यावर त्वरित पुश अलर्ट", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                        Switch(
                            checked = multiDeviceAlertsEnabled,
                            onCheckedChange = { multiDeviceAlertsEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = WarmWhite, checkedTrackColor = DeepEmerald)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = OutlineVariantGrey.copy(alpha = 0.2f))

                    // 2. Udhari Due Date & Overdue Reminders
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("उधारी मुदत आणि थकीत अलर्ट", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("पेमेंट तारखेला सकाळी आणि थकीत रकमेसाठी अलर्ट", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                        Switch(
                            checked = udhariRemindersEnabled,
                            onCheckedChange = { udhariRemindersEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = WarmWhite, checkedTrackColor = DeepEmerald)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = OutlineVariantGrey.copy(alpha = 0.2f))

                    // 3. 9:30 PM Daily Register Closing Reminder
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("रात्री ९:३० वाजता दैनिक क्लोजिंग रिमाइंडर", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("कॅश गल्ला तपासण्यासाठी आणि क्लोजिंग करण्यासाठी रोज रात्री रिमाइंडर", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                        Switch(
                            checked = dailyClosingReminderEnabled,
                            onCheckedChange = { dailyClosingReminderEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = WarmWhite, checkedTrackColor = DeepEmerald)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            com.matoshree.shopmanager.notifications.ShopNotificationManager.sendDailyClosingReminderNotification(context)
                            Toast.makeText(context, "टेस्ट नोटिफिकेशन पाठवण्यात आले", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ChampagneGoldContainer),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("टेस्ट नोटिफिकेशन पाठवा", color = DeepEmerald, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
        }
        val renderDiagnosticsCard: @Composable () -> Unit = {
            MatoshreeCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("सर्व्हर आणि डेटाबेस तपासणी (Diagnostics)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        IconButton(onClick = { viewModel.loadDiagnostics() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "रिफ्रेश करा", tint = DeepEmerald, modifier = Modifier.size(20.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val diag = diagnostics
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("डेटाबेस स्थिती:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text(diag?.firestoreStatus ?: "Cloud Firestore (Online)", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = DeepEmerald))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("सक्रिय डिव्हाइस / काउंटर:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text(diag?.deviceModel ?: "Android Device", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("शेवटची तपासणी वेळ:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text(diag?.lastCheckTime ?: "आत्ताच", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = OutlineVariantGrey.copy(alpha = 0.2f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("सक्रिय कस्टमर्स (क्लाउड):", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text("${diag?.customersCount ?: 0} खाती", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = DeepEmerald))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("एकूण प्रॉडक्ट्स (क्लाउड):", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text("${diag?.productsCount ?: 0} वस्तू", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = DeepEmerald))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("तयार केलेली बिले (क्लाउड):", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text("${diag?.billsCount ?: 0} बिले", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = DeepEmerald))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("उधारी व्यवहार (क्लाउड):", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text("${diag?.udhariTxCount ?: 0} नोंदी", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = DeepEmerald))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("एकूण खर्च (क्लाउड):", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text("${diag?.expensesCount ?: 0} नोंदी", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
        }
        val renderSecurityCard: @Composable () -> Unit = {
            MatoshreeCard {
                    Text("सुरक्षा आणि पिन (Security & PIN)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("बायोमेट्रिक प्रमाणीकरण", fontWeight = FontWeight.SemiBold)
                            Text("फिंगरप्रिंट वापरा", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = { viewModel.toggleBiometric(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = DeepEmerald, checkedTrackColor = DeepEmeraldContainer)
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = OutlineVariantGrey.copy(alpha = 0.2f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("पिन बदला", fontWeight = FontWeight.SemiBold)
                            Text("४ अंकी सुरक्षा पिन बदला", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                        OutlinedButton(
                            onClick = { showChangePinDialog = true },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = DeepEmerald)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("पिन बदला", color = DeepEmerald)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("पिन रिकव्हरी", fontWeight = FontWeight.SemiBold)
                            Text("मास्टर कोडद्वारे पिन रीसेट करा", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                        TextButton(
                            onClick = { showForgotPinDialog = true }
                        ) {
                            Text("पिन रिकव्हर करा", color = DeepEmerald, fontWeight = FontWeight.Bold)
                        }
                    }
                }
        }
        val renderTrashCard: @Composable () -> Unit = {
            MatoshreeCard(hasGoldTopBorder = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Trash Bin", tint = DeepEmerald, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("कचरा पेटी (Trash Bin)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald))
                                Text("डिलीट केलेले प्रॉडक्ट्स, कस्टमर्स आणि बिले ७ दिवसांसाठी सुरक्षित", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onNavigateToTrash,
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("कचरा पेटी उघडा (Trash Bin)", fontWeight = FontWeight.Bold)
                    }
                }
        }
        val renderFinancialRulesCard: @Composable () -> Unit = {
            MatoshreeCard {
                    Text("नफा आणि हिशोब नियम", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("डीफॉल्ट अंदाजे नफा मार्जिन", fontWeight = FontWeight.SemiBold)
                            Text("खरेदी किंमत उपलब्ध नसताना लागू", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                        Text("$margin%", fontWeight = FontWeight.Bold, color = DeepEmerald)
                    }
                }
        }
        val renderVersionCard: @Composable () -> Unit = {
            Text(
                text = "मातोश्री कलेक्शन शॉप मॅनेजर • Version 2.5.4\nCloud Firestore Authoritative Single Source of Truth",
                style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
                .padding(horizontal = if (isTablet) 24.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)
        ) {
            if (isTablet) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            renderShopLogoCard()
                            renderShopInfoCard()
                            renderPaymentUpiCard()
                            renderThermalPrinterCard()
                            renderNotificationsCard()
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            renderDataSyncCard()
                            renderDiagnosticsCard()
                            renderWhatsAppWebhookCard()
                            renderSecurityCard()
                            renderTrashCard()
                            renderFinancialRulesCard()
                            renderVersionCard()
                        }
                    }
                }
            } else {
                item { renderShopLogoCard() }
                item { renderShopInfoCard() }
                item { renderPaymentUpiCard() }
                item { renderDataSyncCard() }
                item { renderDiagnosticsCard() }
                item { renderThermalPrinterCard() }
                item { renderWhatsAppWebhookCard() }
                item { renderNotificationsCard() }
                item { renderSecurityCard() }
                item { renderTrashCard() }
                item { renderFinancialRulesCard() }
                item { renderVersionCard() }
            }
        }
    }

    // Edit Shop Info Dialog
    if (showEditShopDialog) {
        var editName by remember { mutableStateOf(currentShopName) }
        var editMobile by remember { mutableStateOf(currentShopMobile) }
        var editEmail by remember { mutableStateOf(currentShopEmail) }
        var editAddress by remember { mutableStateOf(currentShopAddress) }
        var editGstin by remember { mutableStateOf(currentShopGstin) }
        var editShowGstin by remember { mutableStateOf(currentShowGstin) }

        AlertDialog(
            onDismissRequest = { showEditShopDialog = false },
            title = { Text("दुकानाची माहिती बदला", fontWeight = FontWeight.Bold, color = DeepEmerald) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("दुकानाचे नाव *") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = editMobile,
                        onValueChange = { editMobile = it },
                        label = { Text("संपर्क मोबाईल नंबर *") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = editEmail,
                        onValueChange = { editEmail = it },
                        label = { Text("ईमेल पत्ता") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = editAddress,
                        onValueChange = { editAddress = it },
                        label = { Text("दुकानाचा पत्ता") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = editGstin,
                        onValueChange = { editGstin = it },
                        label = { Text("GSTIN नंबर") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editName.isNotBlank() && editMobile.isNotBlank()) {
                            viewModel.saveShopSettings(
                                editName,
                                editMobile,
                                editEmail,
                                editAddress,
                                editGstin,
                                editShowGstin
                            )
                            showEditShopDialog = false
                            Toast.makeText(context, "दुकानाची माहिती सेव्ह झाली", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("सेव्ह करा")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditShopDialog = false }) {
                    Text("रद्द करा")
                }
            }
        )
    }

    // Edit UPI Settings Dialog
    if (showEditUpiDialog) {
        var editUpiId by remember { mutableStateOf(currentUpiId) }
        var editDisplayName by remember { mutableStateOf(currentUpiName) }

        AlertDialog(
            onDismissRequest = { showEditUpiDialog = false },
            title = { Text("यूपीआय पेमेंट सेटिंग", fontWeight = FontWeight.Bold, color = DeepEmerald) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editUpiId,
                        onValueChange = { editUpiId = it },
                        label = { Text("यूपीआय आयडी / VPA (उदा. matoshree@upi)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = editDisplayName,
                        onValueChange = { editDisplayName = it },
                        label = { Text("खातेदाराचे नाव") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editUpiId.isNotBlank()) {
                            viewModel.savePaymentSettings(
                                editUpiId,
                                editDisplayName,
                                ""
                            )
                            showEditUpiDialog = false
                            Toast.makeText(context, "यूपीआय सेटिंग सेव्ह झाली", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                ) {
                    Text("सेव्ह करा")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditUpiDialog = false }) {
                    Text("रद्द करा")
                }
            }
        )
    }

    // Change PIN Dialog
    if (showChangePinDialog) {
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
            title = {
                Text("सुरक्षा पिन बदला", fontWeight = FontWeight.Bold, color = DeepEmerald)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "पिन सर्व अधिकृत डिव्हाइसेसवर अपडेट केला जाईल.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                    )

                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { if (it.length <= 4 && it.all { ch -> ch.isDigit() }) currentPin = it },
                        label = { Text("सध्याचा पिन (४ अंक)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 4 && it.all { ch -> ch.isDigit() }) newPin = it },
                        label = { Text("नवीन पिन (४ अंक)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 4 && it.all { ch -> ch.isDigit() }) confirmPin = it },
                        label = { Text("नवीन पिन पुन्हा टाका") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage ?: "",
                            color = BoutiqueError,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.changePin(currentPin, newPin, confirmPin) { err ->
                            if (err != null) {
                                errorMessage = err
                            } else {
                                showChangePinDialog = false
                                Toast.makeText(context, "पिन यशस्वीरित्या बदलला", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("पिन बदला")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePinDialog = false }) {
                    Text("रद्द करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Forgot PIN Recovery Dialog
    if (showForgotPinDialog) {
        var recoveryCode by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showForgotPinDialog = false },
            title = {
                Text("सुरक्षा पिन रिकव्हर करा", fontWeight = FontWeight.Bold, color = DeepEmerald)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "पिन रीसेट करण्यासाठी मास्टर रिकव्हरी कोड टाका.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                    )

                    OutlinedTextField(
                        value = recoveryCode,
                        onValueChange = { recoveryCode = it },
                        label = { Text("मास्टर रिकव्हरी कोड") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 4 && it.all { ch -> ch.isDigit() }) newPin = it },
                        label = { Text("नवीन पिन (४ अंक)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 4 && it.all { ch -> ch.isDigit() }) confirmPin = it },
                        label = { Text("नवीन पिन पुन्हा टाका") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage ?: "",
                            color = BoutiqueError,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.recoverPin(recoveryCode, newPin, confirmPin) { err ->
                            if (err != null) {
                                errorMessage = err
                            } else {
                                showForgotPinDialog = false
                                Toast.makeText(context, "पिन यशस्वीरित्या रिकव्हर झाला", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("पिन रीसेट करा")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgotPinDialog = false }) {
                    Text("रद्द करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Select Bluetooth Printer Dialog
    if (showPrinterSelectionDialog) {
        val pairedPrinters = remember { bluetoothPrinterManager.getPairedPrinters() }
        var chosenDevice by remember { mutableStateOf<BluetoothPrinterDevice?>(selectedPrinter ?: pairedPrinters.firstOrNull()) }

        AlertDialog(
            onDismissRequest = { showPrinterSelectionDialog = false },
            title = {
                Text("५८ मिमी थर्मल प्रिंटर निवडा", fontWeight = FontWeight.Bold, color = DeepEmerald)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (!bluetoothPrinterManager.isBluetoothEnabled()) {
                        Text(
                            "ब्लूटूथ सध्या बंद आहे. कृपया फोनचे ब्लूटूथ सुरू करा.",
                            color = BoutiqueError,
                            fontSize = 13.sp
                        )
                    } else if (pairedPrinters.isEmpty()) {
                        Text(
                            "कोणतेही पेअर केलेले ब्लूटूथ डिव्हाइस सापडले नाही.\n\nकृपया फोनच्या सेटिंगमध्ये जाऊन प्रिंटर पेअर करा.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                        )
                    } else {
                        Text(
                            "पेअर केलेला ब्लूटूथ थर्मल प्रिंटर निवडा:",
                            style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        pairedPrinters.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { chosenDevice = device }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (chosenDevice?.address == device.address),
                                    onClick = { chosenDevice = device },
                                    colors = RadioButtonDefaults.colors(selectedColor = DeepEmerald)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(device.name, fontWeight = FontWeight.SemiBold)
                                    Text(device.address, style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (pairedPrinters.isNotEmpty()) {
                    Button(
                        onClick = {
                            chosenDevice?.let {
                                bluetoothPrinterManager.saveSelectedPrinter(it)
                                selectedPrinter = it
                                Toast.makeText(context, "प्रिंटर सेट केला: ${it.name}", Toast.LENGTH_SHORT).show()
                            }
                            showPrinterSelectionDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("प्रिंटर सेव्ह करा")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrinterSelectionDialog = false }) {
                    Text("रद्द करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // WhatsApp Webhook Test Dialog
    if (showWaTestDialog) {
        AlertDialog(
            onDismissRequest = { if (!isTestingWaWebhook) showWaTestDialog = false },
            icon = {
                Icon(Icons.Default.Send, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(32.dp))
            },
            title = {
                Text("व्हॉट्सॲप API चाचणी (Test Webhook)", fontWeight = FontWeight.Bold, color = DeepEmerald)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Webhook वर चाचणी मेसेज पाठवण्यासाठी १०-अंकी मोबाईल नंबर टाका:",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                    )
                    OutlinedTextField(
                        value = waTestPhone,
                        onValueChange = { if (it.length <= 10) waTestPhone = it.filter { char -> char.isDigit() } },
                        label = { Text("मोबाईल नंबर (१० अंक)") },
                        placeholder = { Text("९८७६५४३२१०") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )
                    Text(
                        "लक्ष द्या: चाचणी करण्यापूर्वी URL आणि API Key सेव्ह असल्याची खात्री करा.",
                        style = MaterialTheme.typography.labelSmall.copy(color = MutedCharcoal)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (waTestPhone.length != 10) {
                            Toast.makeText(context, "कृपया योग्य १०-अंकी मोबाईल नंबर टाका", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (waWebhookUrl.isBlank()) {
                            Toast.makeText(context, "कृपया आधी Webhook URL प्रविष्ट करा", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        // Save current values first
                        WhatsAppWebhookManager.setWebhookUrl(context, waWebhookUrl)
                        WhatsAppWebhookManager.setApiKey(context, waApiKey)
                        WhatsAppWebhookManager.setHeaderName(context, waHeaderName)

                        coroutineScope.launch {
                            isTestingWaWebhook = true
                            val result = WhatsAppWebhookManager.testWebhook(context, waTestPhone)
                            isTestingWaWebhook = false
                            if (result.isSuccess) {
                                Toast.makeText(context, result.getOrNull() ?: "चाचणी मेसेज यशस्वी!", Toast.LENGTH_LONG).show()
                                showWaTestDialog = false
                            } else {
                                val err = result.exceptionOrNull()?.message ?: "चाचणी अयशस्वी"
                                Toast.makeText(context, "त्रुटी: $err", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isTestingWaWebhook,
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isTestingWaWebhook) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = WarmWhite)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("पाठवत आहे...")
                    } else {
                        Text("मेसेज पाठवा")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showWaTestDialog = false },
                    enabled = !isTestingWaWebhook
                ) {
                    Text("रद्द करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showBulkReminderConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isSendingBulkReminders) {
                    showBulkReminderConfirmDialog = false
                    bulkReminderStatusText = ""
                }
            },
            icon = {
                Icon(Icons.Default.Send, contentDescription = null, tint = BoutiqueSuccess, modifier = Modifier.size(32.dp))
            },
            title = {
                Text(
                    text = "सर्व थकबाकीदारांना आठवण पाठवायची?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = DeepCharcoal
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "हे सुरू केल्यावर सर्व उधारी शिल्लक असलेल्या ग्राहकांना व्हॉट्सॲपवर उधारी आठवण मेसेज आपोआप पाठवला जाईल.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                    )
                    if (bulkReminderStatusText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = WarmIvory),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSendingBulkReminders) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = BoutiqueSuccess)
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                Text(bulkReminderStatusText, fontSize = 13.sp, color = DeepCharcoal, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!WhatsAppWebhookManager.isConfigured(context)) {
                            Toast.makeText(context, "कृपया प्रथम Webhook URL आणि API Key सेट करा", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        coroutineScope.launch {
                            isSendingBulkReminders = true
                            bulkReminderStatusText = "थकबाकीदार शोधून मेसेज पाठवत आहे..."
                            val result = WhatsAppWebhookManager.dispatchBulkUdhariReminders(context) { current, total, name ->
                                bulkReminderStatusText = "[$current/$total] $name ला पाठवत आहे..."
                            }
                            val (success, failed) = result.getOrDefault(0 to 0)
                            isSendingBulkReminders = false
                            bulkReminderStatusText = "पूर्ण झाले: $success यशस्वी, $failed अयशस्वी"
                            Toast.makeText(context, "उधारी आठवण पूर्ण: $success पाठवले, $failed अपयशी", Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !isSendingBulkReminders,
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueSuccess),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isSendingBulkReminders) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = WarmWhite)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("पाठवत आहे...")
                    } else {
                        Text("होय, पाठवा")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBulkReminderConfirmDialog = false
                        bulkReminderStatusText = ""
                    },
                    enabled = !isSendingBulkReminders
                ) {
                    Text("बंद करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showActionTestDialog != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isTestingWaWebhook) showActionTestDialog = null
            },
            title = {
                Text("उधारी आठवण टेम्पलेट चाचणी (Test)", fontWeight = FontWeight.Bold, color = DeepEmerald)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "हा मेसेज थेट तुमच्या व्हॉट्सॲप नंबरवर पाठवून पाहण्यासाठी खाली १०-अंकी मोबाईल नंबर टाका:",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                    )
                    OutlinedTextField(
                        value = waTestPhone,
                        onValueChange = { waTestPhone = it },
                        label = { Text("चाचणी मोबाईल नंबर") },
                        placeholder = { Text("उदा. 9876543210") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = PhoneUtils.clean10DigitMobileInput(waTestPhone)
                        if (clean.length != 10) {
                            Toast.makeText(context, "कृपया वैध १०-अंकी मोबाईल नंबर टाका", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (waWebhookUrl.isBlank()) {
                            Toast.makeText(context, "कृपया आधी Webhook URL प्रविष्ट करा", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        // Persist inputs first
                        WhatsAppWebhookManager.setWebhookUrl(context, waWebhookUrl)
                        WhatsAppWebhookManager.setApiKey(context, waApiKey)
                        WhatsAppWebhookManager.setHeaderName(context, waHeaderName)
                        WhatsAppWebhookManager.setReminderPayloadTemplate(context, waReminderTemplate)
                        WhatsAppWebhookManager.setReminderTemplateName(context, waReminderTemplateName)

                        coroutineScope.launch {
                            isTestingWaWebhook = true
                            val result = WhatsAppWebhookManager.testActionTemplate(context, waTestPhone, "REMINDER")
                            isTestingWaWebhook = false
                            if (result.isSuccess) {
                                Toast.makeText(context, result.getOrNull() ?: "चाचणी मेसेज यशस्वी!", Toast.LENGTH_LONG).show()
                                showActionTestDialog = null
                            } else {
                                val err = result.exceptionOrNull()?.message ?: "चाचणी अयशस्वी"
                                Toast.makeText(context, "त्रुटी: $err", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isTestingWaWebhook,
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isTestingWaWebhook) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = WarmWhite)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("पाठवत आहे...")
                    } else {
                        Text("चाचणी पाठवा")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showActionTestDialog = null },
                    enabled = !isTestingWaWebhook
                ) {
                    Text("रद्द करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }
}



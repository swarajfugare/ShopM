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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.tasks.await
import com.matoshree.shopmanager.data.repository.SyncRepository
import com.matoshree.shopmanager.security.PinManager
import com.matoshree.shopmanager.sync.SyncManager
import com.matoshree.shopmanager.sync.AppSyncState
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.PrintShareHelper
import com.matoshree.shopmanager.data.diagnostic.CustomerDiagnosticManager
import com.matoshree.shopmanager.data.diagnostic.CustomerDiagnosticReport
import com.matoshree.shopmanager.data.diagnostic.CustomerRepairResult
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.data.local.entity.ReminderLogEntity
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.DateUtils
import com.matoshree.shopmanager.utils.WhatsAppWebhookManager
import kotlinx.coroutines.withContext
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
    private val settingsDao: SettingsDao
) : ViewModel() {

    val pendingSyncCount = MutableStateFlow(0)
    val isBiometricEnabled = MutableStateFlow(pinManager.isBiometricEnabled())

    // Shop Settings State (Default Official Matoshree Collection Profile)
    val shopName = MutableStateFlow("मातोश्री कलेक्शन")
    val shopMobile = MutableStateFlow("8208610261")
    val shopEmail = MutableStateFlow("swarajfugare23@gmail.com")
    val shopAddress = MutableStateFlow("कोंढारकी")
    val shopGstin = MutableStateFlow("")
    val showGstinOnBill = MutableStateFlow(false)
    val logoPath = MutableStateFlow<String?>("android.resource://com.matoshree.collection/drawable/app_logo")

    // Payment Settings State (Default Official UPI Profile)
    val upiId = MutableStateFlow("matoshree@upi")
    val upiDisplayName = MutableStateFlow("मातोश्री कलेक्शन")
    val upiMobile = MutableStateFlow("8208610261")

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
    var showPrinterSelectionDialog by remember { mutableStateOf(false) }
    var isTestingPrint by remember { mutableStateOf(false) }

    var waUdhariReminderEnabled by remember { mutableStateOf(WhatsAppWebhookManager.isUdhariReminderEnabled(context)) }
    var waAutoFromThisDevice by remember { mutableStateOf(WhatsAppWebhookManager.isAutoRemindersFromThisDeviceEnabled(context)) }
    var showReminderLogsDialog by remember { mutableStateOf(false) }
    var showCustomerDiagnosticDialog by remember { mutableStateOf(false) }
    var isSendingBulkReminders by remember { mutableStateOf(false) }
    var showBulkReminderConfirmDialog by remember { mutableStateOf(false) }
    var bulkReminderStatusText by remember { mutableStateOf("") }
    var showWaConfigDialog by remember { mutableStateOf(false) }
    var showRulesHelpDialog by remember { mutableStateOf(false) }
    var showCrashReportDialog by remember { mutableStateOf(false) }
    var lastCrashContent by remember { mutableStateOf<String?>(null) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var waWebhookUrl by remember { mutableStateOf(WhatsAppWebhookManager.getWebhookUrl(context)) }
    var waApiKey by remember { mutableStateOf(WhatsAppWebhookManager.getApiKey(context)) }
    var screenProtectionEnabled by remember {
        mutableStateOf(com.matoshree.shopmanager.security.AppLockManager.isScreenProtectionEnabled(context))
    }
    var lockTimeoutSeconds by remember {
        mutableLongStateOf(com.matoshree.shopmanager.security.AppLockManager.getTimeoutSeconds(context))
    }

    // Advanced App Controls Preferences
    val appControlPrefs = remember { context.getSharedPreferences("matoshree_app_controls", Context.MODE_PRIVATE) }
    var defaultQuickSalePaymentMethod by remember { mutableStateOf(appControlPrefs.getString("default_payment_method", "CASH") ?: "CASH") }
    var receiptCopiesCount by remember { mutableIntStateOf(appControlPrefs.getInt("receipt_copies_count", 1)) }
    var audioHapticFeedbackEnabled by remember { mutableStateOf(appControlPrefs.getBoolean("audio_haptic_feedback", true)) }
    var lowStockThreshold by remember { mutableIntStateOf(appControlPrefs.getInt("low_stock_threshold", 5)) }
    var allowZeroStockSale by remember { mutableStateOf(appControlPrefs.getBoolean("allow_zero_stock_sale", true)) }

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
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Send, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "व्हॉट्सॲप उधारी आठवण",
                            style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = ChampagneGoldContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "CRM जोडलेले 🔒",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeepEmerald,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "wa-crm.refixer.in द्वारे ग्राहकांना उधारी वसुलीची आठवण पाठवा.",
                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 11.sp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Auto Udhari Due Reminder Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("सोमवार साप्ताहिक उधारी आठवण", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("दर सोमवारी थकबाकीदारांना आपोआप आठवण पाठवा", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 11.sp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = waUdhariReminderEnabled,
                        onCheckedChange = {
                            waUdhariReminderEnabled = it
                            WhatsAppWebhookManager.setUdhariReminderEnabled(context, it)
                            Toast.makeText(context, if (it) "उधारी आठवण ऑटो-सेंड चालू केले" else "उधारी आठवण ऑटो-सेंड बंद केले", Toast.LENGTH_SHORT).show()
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = WarmWhite, checkedTrackColor = DeepEmerald)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Single Device Automation Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("या फोनवरून स्वयंचलित मेसेज पाठवा", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "दोन फोन असल्यास केवळ एकाच फोनवर हे चालू ठेवा (दुसऱ्या फोनवर बंद ठेवा, जेणेकरून ग्राहकांना डबल मेसेज जाणार नाहीत)",
                            style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, fontSize = 11.sp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = waAutoFromThisDevice,
                        onCheckedChange = {
                            waAutoFromThisDevice = it
                            WhatsAppWebhookManager.setAutoRemindersFromThisDeviceEnabled(context, it)
                            Toast.makeText(context, if (it) "या फोनवरून ऑटो मेसेज सक्रिय केले" else "या फोनवरून ऑटो मेसेज बंद केले", Toast.LENGTH_SHORT).show()
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = WarmWhite, checkedTrackColor = DeepEmerald)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // View Reminder Logs Button
                OutlinedButton(
                    onClick = { showReminderLogsDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("गेल्या ३० दिवसांचा मेसेज अहवाल पहा", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Credentials status and secure edit button
                val isWaConfigured = WhatsAppWebhookManager.isConfigured(context)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isWaConfigured) ChampagneGoldContainer.copy(alpha = 0.35f) else OutlineVariantGrey.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (isWaConfigured) "CRM क्रेडेंशियल्स: एनक्रिप्टेड सेव्ह 🔒" else "CRM क्रेडेंशियल्स: सेट केलेले नाही ⚠️",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isWaConfigured) DeepEmerald else MutedCharcoal
                            )
                            Text(
                                if (isWaConfigured) "URL व Secret Key Keystore स्टोरेजमध्ये सुरक्षित आहेत" else "मेसेज पाठवण्यासाठी URL आणि Secret Key सेट करा",
                                fontSize = 11.sp,
                                color = MutedCharcoal
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                waWebhookUrl = WhatsAppWebhookManager.getWebhookUrl(context)
                                waApiKey = WhatsAppWebhookManager.getApiKey(context)
                                showWaConfigDialog = true
                            },
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("बदला", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        showBulkReminderConfirmDialog = true
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BoutiqueSuccess),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("📢 सर्व उधारी ग्राहकांना आत्ताच आठवण मेसेज पाठवा", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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

                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ChampagneGoldContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "भारतीय प्रमाणवेळ (IST) अचूक ०९:३० PM अलार्म सक्रिय ⏰",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepEmerald
                            )
                        }
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
                    val isDiagOk = diag?.isOnline == true && !(diag.firestoreStatus.contains("403") || diag.firestoreStatus.contains("Blocked"))
                    val isDiagBlocked = diag?.isOnline == false || diag?.firestoreStatus?.contains("403") == true || diag?.firestoreStatus?.contains("Blocked") == true
                    val isFirebaseAuthed = com.matoshree.shopmanager.data.firebase.FirebaseManager.isAuthorizedUser()

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("डेटाबेस स्थिती:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text(
                                diag?.firestoreStatus ?: "Cloud Firestore (Online)",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDiagOk) DeepEmerald else BoutiqueError
                                )
                            )
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

                    if (isDiagBlocked || !isFirebaseAuthed) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = BoutiqueError.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, BoutiqueError.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Security,
                                        contentDescription = null,
                                        tint = BoutiqueError,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "क्लाउड ऑथेंटिकेशन आवश्यक (Firebase 403)",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = BoutiqueError
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Firestore डेटाबेस सुरक्षित असल्यामुळे अधिकृत Google खात्याने लॉगिन आवश्यक आहे. खालील बटणावर क्लिक करून Google लॉगिन री-ऑथेंटिकेट करा:",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MutedCharcoal,
                                        lineHeight = 18.sp
                                    )
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                var isAuthenticating by remember { mutableStateOf(false) }

                                Button(
                                    onClick = {
                                        val act = context as? android.app.Activity
                                        if (act != null) {
                                            isAuthenticating = true
                                            com.matoshree.shopmanager.data.firebase.FirebaseManager.signInWithGoogle(act) { success, err ->
                                                isAuthenticating = false
                                                if (success) {
                                                    val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                                                    val email = user?.email?.lowercase()?.trim() ?: ""
                                                    val name = user?.displayName ?: "Owner"
                                                    val gAuth = com.matoshree.shopmanager.security.GoogleAuthManager(context)
                                                    gAuth.saveSession(email, name)
                                                    Toast.makeText(context, "Google प्रमाणीकरण यशस्वी! क्लाउड डेटाबेस जोडला गेला.", Toast.LENGTH_SHORT).show()
                                                    viewModel.loadDiagnostics()
                                                } else {
                                                    Toast.makeText(context, err ?: "प्रमाणीकरण अयशस्वी", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isAuthenticating,
                                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().height(42.dp)
                                ) {
                                    if (isAuthenticating) {
                                        CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = ChampagneGold, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Google ने पुन्हा लॉगिन करा (Re-Authenticate)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = WarmWhite
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        showRulesHelpDialog = true
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, ChampagneGold),
                                    modifier = Modifier.fillMaxWidth().height(36.dp)
                                ) {
                                    Text(
                                        "📋 Firebase Console नियम (Rules) तपासा / कॉपी करा",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = DeepEmerald
                                    )
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    color = OutlineVariantGrey.copy(alpha = 0.2f)
                                )

                                // Crash Diagnostics Section
                                val existingCrash = remember(showCrashReportDialog) {
                                    com.matoshree.shopmanager.security.CrashReporter.getLastCrash(context)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "शेवटचा क्रॅश अहवाल (Crash Diagnostic)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (existingCrash != null) BoutiqueError else DeepEmerald
                                        )
                                        Text(
                                            if (existingCrash != null) "⚠️ सिस्टीममध्ये शेवटचा अनपेक्षित क्रॅश नोंदवला गेला आहे" else "✅ कोणताही क्रॅश झालेला नाही (सिस्टीम स्थिर)",
                                            fontSize = 11.sp,
                                            color = MutedCharcoal
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    OutlinedButton(
                                        onClick = {
                                            lastCrashContent = com.matoshree.shopmanager.security.CrashReporter.getLastCrash(context)
                                            showCrashReportDialog = true
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, if (existingCrash != null) BoutiqueError else DeepEmerald),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text(
                                            "तपासा",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (existingCrash != null) BoutiqueError else DeepEmerald
                                        )
                                    }
                                }
                            }
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

                    // Auto-Lock Inactivity Timeout
                    Text("ऑटो-लॉक वेळ (Inactivity Auto-Lock)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("ॲप बॅकग्राउंडमध्ये गेल्यावर किती वेळाने आपोआप लॉक व्हावे", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            0L to "लगेच",
                            30L to "३० से.",
                            60L to "१ मि.",
                            300L to "५ मि."
                        ).forEach { (seconds, label) ->
                            val isSelected = lockTimeoutSeconds == seconds
                            OutlinedButton(
                                onClick = {
                                    lockTimeoutSeconds = seconds
                                    com.matoshree.shopmanager.security.AppLockManager.setTimeoutSeconds(context, seconds)
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (isSelected) DeepEmerald else OutlineVariantGrey),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isSelected) ChampagneGoldContainer.copy(alpha = 0.4f) else Color.Transparent
                                ),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.weight(1f).height(32.dp)
                            ) {
                                Text(label, fontSize = 10.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) DeepEmerald else MutedCharcoal)
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = OutlineVariantGrey.copy(alpha = 0.2f)
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ChampagneGoldContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "४-अंकी सिक्युरिटी पिन आणि गुगल लॉगिन सक्रिय 🔒",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepEmerald
                            )
                        }
                    }
                }
        }
        val renderAppControlsCard: @Composable () -> Unit = {}
        val renderCustomerDiagnosticCard: @Composable () -> Unit = {
            MatoshreeCard(hasGoldTopBorder = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ManageAccounts, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("ग्राहक डेटा तपासणी व दुरुस्ती", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald))
                            Text("फोन आणि क्लाउड यादी तपासा, हरवलेले ग्राहक सुरक्षितपणे पूर्ववत करा", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        showCustomerDiagnosticDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ग्राहक डेटा तपासणी व दुरुस्ती सुरू करा", fontWeight = FontWeight.Bold)
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
        val renderVersionCard: @Composable () -> Unit = {
            Text(
                text = "मातोश्री कलेक्शन शॉप मॅनेजर • Version 3.3.0 (Production Fortified)\nCloud Firestore Authoritative Single Source of Truth • Zero Trust Security",
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
                            renderAppControlsCard()
                            renderThermalPrinterCard()
                            renderNotificationsCard()
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            renderWhatsAppWebhookCard()
                            renderSecurityCard()
                            renderDataSyncCard()
                            renderDiagnosticsCard()
                            renderCustomerDiagnosticCard()
                            renderTrashCard()
                            renderVersionCard()
                        }
                    }
                }
            } else {
                item { renderShopLogoCard() }
                item { renderShopInfoCard() }
                item { renderPaymentUpiCard() }
                item { renderAppControlsCard() }
                item { renderThermalPrinterCard() }
                item { renderWhatsAppWebhookCard() }
                item { renderNotificationsCard() }
                item { renderSecurityCard() }
                item { renderDataSyncCard() }
                item { renderDiagnosticsCard() }
                item { renderCustomerDiagnosticCard() }
                item { renderTrashCard() }
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

    // Secure WhatsApp Webhook Credentials Dialog
    if (showWaConfigDialog) {
        var editUrl by remember { mutableStateOf(waWebhookUrl) }
        var editKey by remember { mutableStateOf(waApiKey) }
        var showPassword by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showWaConfigDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("WhatsApp Webhook API सेटिंग्ज", fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "हे क्रेडेंशियल्स डिव्हाइसच्या हार्डवेअर Keystore (EncryptedSharedPreferences) मध्ये सुरक्षितपणे सेव्ह होतात.",
                        fontSize = 11.sp,
                        color = MutedCharcoal
                    )

                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("Webhook URL") },
                        placeholder = { Text("https://wa-crm.refixer.in/api/webhooks/shop-bills") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = editKey,
                        onValueChange = { editKey = it },
                        label = { Text("API Secret Key") },
                        placeholder = { Text("wacrm_live_...") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "Hide API Key" else "Show API Key"
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        WhatsAppWebhookManager.setWebhookUrl(context, editUrl)
                        WhatsAppWebhookManager.setApiKey(context, editKey)
                        waWebhookUrl = editUrl
                        waApiKey = editKey
                        showWaConfigDialog = false
                        Toast.makeText(context, "API क्रेडेंशियल्स सुरक्षितपणे सेव्ह केले", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("सेव्ह करा")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWaConfigDialog = false }) {
                    Text("रद्द करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showRulesHelpDialog) {
        val rulesText = """rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function isAuthenticated() {
      return request.auth != null;
    }
    function isAuthorizedOwner() {
      return isAuthenticated() &&
        request.auth.token.email in ['swarajfugare23@gmail.com', 'sadhanafugare86@gmail.com'];
    }
    match /{document=**} {
      allow read, write: if isAuthorizedOwner();
    }
  }
}"""

        AlertDialog(
            onDismissRequest = { showRulesHelpDialog = false },
            title = {
                Text(
                    "Firebase Console Rules मदत",
                    fontWeight = FontWeight.Bold,
                    color = DeepEmerald
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "जर Firebase Console मधील नियम जुने किंवा ब्लॉक झालेले असतील, तर पुढील ३ सोप्या पायऱ्या करा:\n\n" +
                        "१. console.firebase.google.com उघडा.\n" +
                        "२. Firestore Database -> 'Rules' टॅबवर जा.\n" +
                        "३. खालील नियम पेस्ट करून वर दिलेले 'Publish' बटण दाबा.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal, lineHeight = 18.sp)
                    )

                    Surface(
                        color = Color(0xFFF4F4F4),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = rulesText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color.DarkGray
                            ),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(rulesText))
                        Toast.makeText(context, "Firestore Rules क्लिपबोर्डवर कॉपी केले!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("नियम कॉपी करा")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRulesHelpDialog = false }) {
                    Text("बंद करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showCrashReportDialog) {
        val report = lastCrashContent ?: com.matoshree.shopmanager.security.CrashReporter.getLastCrash(context)
        AlertDialog(
            onDismissRequest = { showCrashReportDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (report != null) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (report != null) BoutiqueError else BoutiqueSuccess
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "शेवटचा क्रॅश अहवाल",
                        fontWeight = FontWeight.Bold,
                        color = DeepEmerald
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (report != null) {
                        Text(
                            "खालील तांत्रिक माहिती डेव्हलपरला पाठवून समस्येचे निराकरण करता येईल:",
                            style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                        )
                        Surface(
                            color = Color(0xFFF4F4F4),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = report,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color.DarkGray
                                ),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = BoutiqueSuccess,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "कोणताही क्रॅश अहवाल उपलब्ध नाही.",
                                fontWeight = FontWeight.Bold,
                                color = DeepEmerald
                            )
                            Text(
                                "ॲप्लिकेशन सुरक्षित आणि स्थिर रीतीने चालू आहे.",
                                fontSize = 12.sp,
                                color = MutedCharcoal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (report != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                com.matoshree.shopmanager.security.CrashReporter.clearCrash(context)
                                lastCrashContent = null
                                Toast.makeText(context, "क्रॅश अहवाल मिटवला गेला.", Toast.LENGTH_SHORT).show()
                                showCrashReportDialog = false
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("मिटवा", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                com.matoshree.shopmanager.security.CrashReporter.shareCrashReport(context)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("शेअर करा 📤", fontSize = 12.sp)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showCrashReportDialog = false }) {
                    Text("बंद करा")
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Last 30 Days WhatsApp Reminder Logs Dialog
    if (showReminderLogsDialog) {
        var logs by remember { mutableStateOf<List<ReminderLogEntity>>(emptyList()) }
        var isLoadingLogs by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val db = AppDatabase.getInstance(context)
                    logs = db.reminderLogDao().getRecentLogsDirect(100)
                } catch (e: Exception) {
                    android.util.Log.e("SettingsScreen", "Error loading reminder logs: ${e.message}")
                } finally {
                    isLoadingLogs = false
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showReminderLogsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("गेल्या ३० दिवसांचा मेसेज अहवाल", fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 16.sp)
                }
            },
            text = {
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 400.dp)) {
                    if (isLoadingLogs) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = DeepEmerald)
                    } else if (logs.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = BoutiqueSuccess, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("गेल्या ३० दिवसांत कोणताही मेसेज पाठवलेला नाही.", fontSize = 13.sp, color = MutedCharcoal)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(logs.size) { index ->
                                val log = logs[index]
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = WarmIvory,
                                    border = BorderStroke(1.dp, if (log.status == "SUCCESS") BoutiqueSuccess.copy(alpha = 0.3f) else BoutiqueError.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(log.customerName.ifBlank { "ग्राहक" }, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = DeepCharcoal)
                                            Text(
                                                CurrencyFormatter.format(log.amount),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = DeepEmerald
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("📱 ${log.customerMobile}", fontSize = 11.sp, color = MutedCharcoal)
                                            val typeLabel = when (log.type) {
                                                "MONDAY_BULK" -> "सोमवार आठवण"
                                                "DUE_DATE" -> "ठरलेली तारीख"
                                                else -> "मॅन्युअल"
                                            }
                                            Surface(
                                                color = if (log.status == "SUCCESS") BoutiqueSuccess.copy(alpha = 0.15f) else BoutiqueError.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    "$typeLabel • ${if (log.status == "SUCCESS") "यशस्वी" else "त्रुटी"}",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (log.status == "SUCCESS") BoutiqueSuccess else BoutiqueError,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        if (!log.errorMessage.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("त्रुटी: ${log.errorMessage}", fontSize = 10.sp, color = BoutiqueError)
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(log.date, fontSize = 9.sp, color = MutedCharcoal)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showReminderLogsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("ठीक आहे", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Customer Diagnostic & Safe Repair Dialog
    if (showCustomerDiagnosticDialog) {
        var report by remember { mutableStateOf<CustomerDiagnosticReport?>(null) }
        var isScanning by remember { mutableStateOf(true) }
        var isRepairing by remember { mutableStateOf(false) }
        var repairResult by remember { mutableStateOf<CustomerRepairResult?>(null) }

        LaunchedEffect(Unit) {
            report = CustomerDiagnosticManager.runDiagnosticScan(context)
            isScanning = false
        }

        AlertDialog(
            onDismissRequest = {
                if (!isRepairing) {
                    showCustomerDiagnosticDialog = false
                    report = null
                    repairResult = null
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ManageAccounts, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ग्राहक डेटा तपासणी व दुरुस्ती", fontWeight = FontWeight.Bold, color = DeepEmerald, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isScanning) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = DeepEmerald)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("ग्राहक डेटा तपासणी सुरू आहे...", fontSize = 13.sp, color = MutedCharcoal)
                        }
                    } else if (repairResult != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BoutiqueSuccess.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, BoutiqueSuccess.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = BoutiqueSuccess, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("दुरुस्ती यशस्वी!", fontWeight = FontWeight.Bold, color = BoutiqueSuccess, fontSize = 14.sp)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(repairResult!!.message, fontSize = 12.sp, color = DeepEmerald)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("सुरक्षित बॅकअप: ${repairResult!!.backupPath}", fontSize = 10.sp, color = MutedCharcoal)
                            }
                        }
                    } else if (report != null) {
                        val r = report!!
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = WarmIvory,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("तपासणी अहवाल:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = DeepEmerald)
                                Text("• ग्राहकांची यादी (Directory) / फोन सक्रिय ग्राहक: ${r.totalRoomActive}", fontSize = 12.sp)
                                Text("• फोनमधील अर्काईव्ह ग्राहक: ${r.totalRoomArchived}", fontSize = 12.sp)
                                Text("• क्लाउडवरील सक्रिय ग्राहक: ${r.totalFirestoreActive}", fontSize = 12.sp)

                                if (r.erroneouslyArchivedCount > 0) {
                                    Text("⚠️ चुकून अर्काईव्ह झालेले ग्राहक: ${r.erroneouslyArchivedCount}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BoutiqueError)
                                }
                                if (r.missingFromRoomCount > 0) {
                                    Text("⚠️ फोनमध्ये नसलेले क्लाउड ग्राहक: ${r.missingFromRoomCount}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BoutiqueError)
                                }
                                if (r.duplicateMobileCount > 0) {
                                    Text("⚠️ डुप्लिकेट मोबाईल नोंदी: ${r.duplicateMobileCount}", fontSize = 12.sp, color = ChampagneGold)
                                }
                                if (r.duplicateEventsCount > 0) {
                                    Text("⚠️ संभाव्य डुप्लिकेट उधारी/पेमेंट नोंदी: ${r.duplicateEventsCount}", fontSize = 12.sp, color = BoutiqueError, fontWeight = FontWeight.Bold)
                                }
                                if (r.needsReviewCount > 0) {
                                    Text("⚠️ पुनरावलोकन आवश्यक नोंदी: ${r.needsReviewCount}", fontSize = 12.sp, color = BoutiqueError, fontWeight = FontWeight.Bold)
                                }
                                if (r.overpaidCustomersCount > 0) {
                                    Text("⚠️ जास्त भरणा (data issue): ${r.overpaidCustomersCount}", fontSize = 12.sp, color = BoutiqueError, fontWeight = FontWeight.Bold)
                                }
                                if (r.erroneouslyArchivedCount == 0 && r.missingFromRoomCount == 0 && r.duplicateMobileCount == 0 && r.duplicateEventsCount == 0 && r.needsReviewCount == 0 && r.overpaidCustomersCount == 0) {
                                    Text("✅ सर्व ग्राहक डेटा परिपूर्ण आणि सुसंगत आहे!", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BoutiqueSuccess)
                                }
                            }
                        }

                        if (r.details.isNotEmpty()) {
                            Text("तपशील:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = DeepEmerald)
                            r.details.forEach { d ->
                                Text("• $d", fontSize = 11.sp, color = MutedCharcoal)
                            }
                        }

                        Text(
                            "टीप: दुरुस्ती सुरू करण्यापूर्वी सर्व डेटाची संपूर्ण सुरक्षित JSON बॅकअप फाईल सेव्ह केली जाते.",
                            fontSize = 11.sp,
                            color = MutedCharcoal
                        )
                    }
                }
            },
            confirmButton = {
                if (repairResult == null && !isScanning) {
                    Button(
                        onClick = {
                            isRepairing = true
                            coroutineScope.launch {
                                val res = CustomerDiagnosticManager.performSafeRepair(context)
                                repairResult = res
                                isRepairing = false
                            }
                        },
                        enabled = !isRepairing,
                        colors = ButtonDefaults.buttonColors(containerColor = BoutiqueSuccess),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isRepairing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = WarmWhite)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("सुरक्षित दुरुस्ती करा (बॅकअपसह)", fontWeight = FontWeight.Bold)
                    }
                } else if (repairResult != null) {
                    Button(
                        onClick = {
                            showCustomerDiagnosticDialog = false
                            report = null
                            repairResult = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("पूर्ण झाले", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                if (!isRepairing) {
                    TextButton(onClick = {
                        showCustomerDiagnosticDialog = false
                        report = null
                        repairResult = null
                    }) {
                        Text("बंद करा")
                    }
                }
            },
            containerColor = WarmWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }
}




package com.matoshree.shopmanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.rememberNavController
import com.matoshree.shopmanager.data.firebase.FirebaseManager
import com.matoshree.shopmanager.data.local.AppDatabase
import com.matoshree.shopmanager.notifications.ShopNotificationManager
import com.matoshree.shopmanager.security.AppLockManager
import com.matoshree.shopmanager.security.PinManager
import com.matoshree.shopmanager.sync.SyncManager
import com.matoshree.shopmanager.ui.navigation.AppNavGraph
import com.matoshree.shopmanager.ui.navigation.Screen
import com.matoshree.shopmanager.ui.theme.MatoshreeTheme

class MainActivity : FragmentActivity() {

    private lateinit var syncManager: SyncManager
    private val deepLinkRoute = mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            android.util.Log.d("MainActivity", "POST_NOTIFICATIONS permission granted")
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.matoshree.shopmanager.utils.LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleNotificationIntent(intent)



        val database = AppDatabase.getInstance(this)
        val pinManager = PinManager(this)
        syncManager = SyncManager.getInstance(this, database)

        val deviceTrustManager = com.matoshree.shopmanager.security.DeviceTrustManager(this)
        val googleAuthManager = com.matoshree.shopmanager.security.GoogleAuthManager(this)
        val securityStatus = com.matoshree.shopmanager.security.AppSecurityShield.performFullSecurityScan(this)

        // Initialize process-wide lock manager and apply FLAG_SECURE screen protection
        AppLockManager.init(this)
        AppLockManager.applyScreenProtection(this)

        // Schedule periodic background sync
        SyncManager.schedulePeriodicSync(this)

        setContent {
            MatoshreeTheme {
                var isGoogleLoggedIn: Boolean by remember { mutableStateOf(googleAuthManager.isUserLoggedIn()) }
                val isAppLocked by AppLockManager.isLocked.collectAsState()

                DisposableEffect(isGoogleLoggedIn) {
                    val listener = if (isGoogleLoggedIn) {
                        syncManager.startRealtimeSync()
                        deviceTrustManager.listenForRemoteRevocation {
                            googleAuthManager.signOut()
                            deviceTrustManager.revokeDeviceAuthorization()
                            isGoogleLoggedIn = false
                        }
                    } else null
                    onDispose {
                        listener?.remove()
                        syncManager.stopRealtimeSync()
                    }
                }

                LaunchedEffect(isGoogleLoggedIn) {
                    if (isGoogleLoggedIn) {
                        syncManager.triggerImmediateSync()
                    }
                }

                if (!securityStatus.isSecure) {
                    com.matoshree.shopmanager.ui.screens.lock.SecurityViolationScreen(
                        securityStatus = securityStatus,
                        onExitApp = {
                            finishAffinity()
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    )
                } else if (!isGoogleLoggedIn) {
                    com.matoshree.shopmanager.ui.screens.lock.GoogleLoginScreen(
                        googleAuthManager = googleAuthManager,
                        onLoginSuccess = {
                            isGoogleLoggedIn = true
                            val email = googleAuthManager.getLoggedInEmail() ?: "authorized_owner"
                            deviceTrustManager.authorizeDeviceFromGoogleLogin(email)
                        }
                    )
                } else if (isAppLocked) {
                    com.matoshree.shopmanager.ui.screens.lock.AppLockScreen(
                        onUnlockSuccess = {
                            AppLockManager.unlock()
                        }
                    )
                } else {
                    val navController = rememberNavController()
                    val pendingRoute by deepLinkRoute

                    LaunchedEffect(pendingRoute) {
                        pendingRoute?.let { route ->
                            try {
                                navController.navigate(route)
                            } catch (e: Exception) {
                                android.util.Log.w("MainActivity", "Navigation error: ${e.message}")
                            }
                            deepLinkRoute.value = null
                        }
                    }

                    AppNavGraph(
                        navController = navController,
                        database = database,
                        pinManager = pinManager,
                        syncManager = syncManager
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return
        }
        val navRoute = intent.getStringExtra(ShopNotificationManager.EXTRA_NAV_ROUTE) ?: return
        intent.removeExtra(ShopNotificationManager.EXTRA_NAV_ROUTE)
        when (navRoute) {
            "bill_preview" -> {
                val billId = intent.getLongExtra(ShopNotificationManager.EXTRA_BILL_ID, -1L)
                if (billId > 0) {
                    deepLinkRoute.value = Screen.BillPreview.createRoute(billId)
                }
            }
            "customer_profile" -> {
                val customerGid = intent.getStringExtra("extra_customer_gid") ?: ""
                val customerId = intent.getLongExtra(ShopNotificationManager.EXTRA_CUSTOMER_ID, -1L)
                if (customerGid.isNotBlank()) {
                    deepLinkRoute.value = Screen.CustomerProfile.createRoute(customerGid)
                } else if (customerId > 0) {
                    deepLinkRoute.value = Screen.CustomerProfile.createRoute(customerId)
                }
            }
            "daily_closing" -> {
                deepLinkRoute.value = Screen.DailyClosing.route
            }
            "customers" -> {
                deepLinkRoute.value = Screen.Customers.route
            }
            "products" -> {
                deepLinkRoute.value = Screen.Products.route
            }
            "inventory" -> {
                deepLinkRoute.value = Screen.Inventory.route
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::syncManager.isInitialized) {
            syncManager.startForegroundPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::syncManager.isInitialized) {
            syncManager.stopForegroundPolling()
        }
    }
}

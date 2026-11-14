package com.matoshree.shopmanager.ui.screens.lock

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.security.BiometricAuthHelper
import com.matoshree.shopmanager.security.GoogleAuthManager
import com.matoshree.shopmanager.security.PinAuthEngine
import com.matoshree.shopmanager.security.PinManager
import com.matoshree.shopmanager.ui.components.CustomKeypad
import com.matoshree.shopmanager.ui.components.PinIndicator
import com.matoshree.shopmanager.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppLockViewModel(private val pinManager: PinManager) : ViewModel() {
    private val _pinState = MutableStateFlow("")
    val pinState: StateFlow<String> = _pinState

    private val _isError = MutableStateFlow(false)
    val isError: StateFlow<Boolean> = _isError

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked

    // Creation & Reset State
    val isPinSetupMode = MutableStateFlow(!pinManager.isPinSet())
    val setupStep = MutableStateFlow(1) // 1 = enter new, 2 = confirm new
    private var firstEnteredPin = ""

    val showGoogleResetDialog = MutableStateFlow(false)
    val pinLockoutRemaining = MutableStateFlow(0L)

    init {
        checkPinLockout()
    }

    fun checkPinLockout() {
        pinLockoutRemaining.value = pinManager.getPinLockoutRemainingSeconds()
    }

    fun onDigit(digit: String) {
        if (!isPinSetupMode.value && pinManager.isPinLockedOut()) {
            checkPinLockout()
            return
        }
        if (_pinState.value.length < 4) {
            _pinState.value += digit
            if (_pinState.value.length == 4) {
                if (isPinSetupMode.value) {
                    handleSetupStep()
                } else {
                    verify()
                }
            }
        }
    }

    fun onDelete() {
        if (_pinState.value.isNotEmpty()) {
            _pinState.value = _pinState.value.dropLast(1)
            _isError.value = false
        }
    }

    val setupErrorMessage = MutableStateFlow<String?>(null)

    private fun handleSetupStep() {
        viewModelScope.launch {
            if (setupStep.value == 1) {
                val entered = _pinState.value
                if (entered in PinAuthEngine.DISALLOWED_PINS) {
                    _isError.value = true
                    setupErrorMessage.value = "सुरक्षिततेसाठी १२३४ किंवा २५८० हा पिन वापरू नका. कृपया नवीन सुरक्षित पिन निवडा."
                    delay(1200)
                    _pinState.value = ""
                    _isError.value = false
                    setupErrorMessage.value = null
                    return@launch
                }
                firstEnteredPin = entered
                delay(200)
                _pinState.value = ""
                setupStep.value = 2
            } else {
                if (_pinState.value == firstEnteredPin) {
                    try {
                        pinManager.setPin(_pinState.value)
                        isPinSetupMode.value = false
                        _isUnlocked.value = true
                    } catch (e: Exception) {
                        _isError.value = true
                        setupErrorMessage.value = e.message
                        delay(1000)
                        _pinState.value = ""
                        _isError.value = false
                        setupStep.value = 1
                        firstEnteredPin = ""
                        setupErrorMessage.value = null
                    }
                } else {
                    _isError.value = true
                    setupErrorMessage.value = "पिन जुळला नाही. कृपया पुन्हा प्रयत्न करा."
                    delay(600)
                    _pinState.value = ""
                    _isError.value = false
                    setupStep.value = 1
                    firstEnteredPin = ""
                    setupErrorMessage.value = null
                }
            }
        }
    }

    private fun verify() {
        viewModelScope.launch {
            if (pinManager.isPinLockedOut()) {
                checkPinLockout()
                _isError.value = true
                delay(600)
                _pinState.value = ""
                _isError.value = false
                return@launch
            }

            if (pinManager.verifyPin(_pinState.value)) {
                _isUnlocked.value = true
            } else {
                _isError.value = true
                checkPinLockout()
                delay(600)
                _pinState.value = ""
                _isError.value = false
            }
        }
    }

    fun onBiometricSuccess() {
        _isUnlocked.value = true
    }

    fun openGoogleResetDialog() {
        showGoogleResetDialog.value = true
    }

    fun closeGoogleResetDialog() {
        showGoogleResetDialog.value = false
    }

    fun onGoogleAuthVerifiedForReset() {
        showGoogleResetDialog.value = false
        isPinSetupMode.value = true
        setupStep.value = 1
        firstEnteredPin = ""
        _pinState.value = ""
    }
}

@Composable
fun AppLockScreen(
    onUnlockSuccess: () -> Unit
) {
    val context = LocalContext.current
    val pinManager = remember { PinManager(context) }
    val googleAuthManager = remember { GoogleAuthManager(context) }
    val viewModel = remember { AppLockViewModel(pinManager) }

    val pin by viewModel.pinState.collectAsState()
    val isError by viewModel.isError.collectAsState()
    val isUnlocked by viewModel.isUnlocked.collectAsState()
    val pinLockoutRemaining by viewModel.pinLockoutRemaining.collectAsState()
    val isPinSetupMode by viewModel.isPinSetupMode.collectAsState()
    val setupStep by viewModel.setupStep.collectAsState()
    val showGoogleResetDialog by viewModel.showGoogleResetDialog.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val currentActivity = context as? Activity

    DisposableEffect(Unit) {
        // ALWAYS keep FLAG_SECURE on the Lock/PIN screen
        currentActivity?.window?.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            // Restore normal screen protection setting upon exiting lock screen
            currentActivity?.let { com.matoshree.shopmanager.security.AppLockManager.applyScreenProtection(it) }
        }
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            coroutineScope.launch {
                val res = googleAuthManager.handleSignInResult(result.data)
                if (res.isSuccess) {
                    Toast.makeText(context, "Google प्रमाणीकरण यशस्वी! आता नवीन पिन सेट करा.", Toast.LENGTH_LONG).show()
                    viewModel.onGoogleAuthVerifiedForReset()
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Google प्रमाणीकरण अयशस्वी."
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Biometric auto-trigger on screen open if PIN is set and not locked out
    LaunchedEffect(Unit) {
        if (!isPinSetupMode && pinManager.isBiometricEnabled() && !pinManager.isPinLockedOut()) {
            val activity = context as? FragmentActivity
            if (activity != null && BiometricAuthHelper.canAuthenticate(activity)) {
                BiometricAuthHelper.showBiometricPrompt(
                    activity = activity,
                    onSuccess = { viewModel.onBiometricSuccess() },
                    onError = { /* fallback to PIN */ }
                )
            }
        }
    }

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            onUnlockSuccess()
        }
    }

    // Periodic lockout check
    LaunchedEffect(pinLockoutRemaining) {
        if (pinLockoutRemaining > 0) {
            delay(1000)
            viewModel.checkPinLockout()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = WarmIvory
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                // Shop Emblem
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(WarmWhite),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = com.matoshree.shopmanager.R.drawable.app_logo),
                        contentDescription = "Logo",
                        modifier = Modifier.size(52.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "मातोश्री कलेक्शन",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 24.sp,
                        color = DeepEmerald,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                val setupErr by viewModel.setupErrorMessage.collectAsState()

                Text(
                    text = when {
                        !setupErr.isNullOrBlank() -> setupErr!!
                        isPinSetupMode && setupStep == 1 -> "नवीन ४-अंकी सुरक्षा पिन तयार करा\n(सुरक्षिततेसाठी १२३४ किंवा २५८० हा पिन वापरू नका)"
                        isPinSetupMode && setupStep == 2 -> "पिन निश्चित करा (पिन पुन्हा प्रविष्ट करा)"
                        else -> "दुकान अनलॉक करण्यासाठी ४ अंकी पिन टाका"
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = if (!setupErr.isNullOrBlank()) BoutiqueError else if (isPinSetupMode) DeepEmerald else MutedCharcoal,
                        fontWeight = if (isPinSetupMode || !setupErr.isNullOrBlank()) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp
                    ),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Lockout Banner
                AnimatedVisibility(visible = pinLockoutRemaining > 0 && !isPinSetupMode) {
                    Surface(
                        color = BoutiqueError.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(bottom = 12.dp, start = 24.dp, end = 24.dp)
                    ) {
                        Text(
                            text = "खूप जास्त चुकीचे प्रयत्न. कृपया ${pinLockoutRemaining} सेकंद थांबा.",
                            color = BoutiqueError,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // 4-Digit PIN Indicator Dots
                PinIndicator(
                    length = 4,
                    enteredCount = pin.length,
                    isError = isError
                )

                Spacer(modifier = Modifier.height(26.dp))

                // Keypad
                CustomKeypad(
                    onDigitClick = { viewModel.onDigit(it) },
                    onDeleteClick = { viewModel.onDelete() },
                    showDoubleZero = false
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Forgot PIN / Biometric Option
                if (!isPinSetupMode) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.openGoogleResetDialog() }
                        ) {
                            Text(
                                text = "पिन विसरलात का?",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = ChampagneGold,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        if (pinManager.isBiometricEnabled() && pinLockoutRemaining == 0L) {
                            Spacer(modifier = Modifier.width(12.dp))
                            IconButton(
                                onClick = {
                                    val activity = context as? FragmentActivity
                                    if (activity != null && BiometricAuthHelper.canAuthenticate(activity)) {
                                        BiometricAuthHelper.showBiometricPrompt(
                                            activity = activity,
                                            onSuccess = { viewModel.onBiometricSuccess() },
                                            onError = { /* fallback to PIN */ }
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Fingerprint, contentDescription = "Biometric", tint = DeepEmerald)
                            }
                        }
                    }
                }
            }
        }

        // Google Authentication PIN Reset Dialog (Zero backdoor password)
        if (showGoogleResetDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.closeGoogleResetDialog() },
                containerColor = WarmWhite,
                shape = RoundedCornerShape(16.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = DeepEmerald,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Google द्वारे पिन रीसेट",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = DeepEmerald
                            )
                        )
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "सुरक्षेसाठी पिन रीसेट करण्याकरिता अधिकृत मालक Google खात्याने पुन्हा प्रमाणीकरण करणे आवश्यक आहे.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Google लॉगिन यशस्वी झाल्यावर तुम्ही लगेच नवीन ४-अंकी पिन सेट करू शकाल.",
                            style = MaterialTheme.typography.bodySmall.copy(color = DeepCharcoal, fontWeight = FontWeight.Medium)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            googleSignInLauncher.launch(googleAuthManager.getSignInIntent())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Google ने पडताळणी करा")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.closeGoogleResetDialog() }) {
                        Text("रद्द करा", color = MutedCharcoal)
                    }
                }
            )
        }
    }
}

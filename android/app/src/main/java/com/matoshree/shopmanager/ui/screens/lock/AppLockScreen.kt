package com.matoshree.shopmanager.ui.screens.lock

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

    val showRecoveryDialog = MutableStateFlow(false)
    val recoveryStep = MutableStateFlow(1) // 1: password, 2: set pin
    val recoveryError = MutableStateFlow<String?>(null)
    val lockoutRemaining = MutableStateFlow(0L)
    val pinLockoutRemaining = MutableStateFlow(0L)

    init {
        checkPinLockout()
    }

    fun checkPinLockout() {
        pinLockoutRemaining.value = pinManager.getPinLockoutRemainingSeconds()
    }

    fun onDigit(digit: String) {
        if (pinManager.isPinLockedOut()) {
            checkPinLockout()
            return
        }
        if (_pinState.value.length < 4) {
            _pinState.value += digit
            if (_pinState.value.length == 4) {
                verify()
            }
        }
    }

    fun onDelete() {
        if (_pinState.value.isNotEmpty()) {
            _pinState.value = _pinState.value.dropLast(1)
            _isError.value = false
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

    fun openForgotPinDialog() {
        checkLockout()
        recoveryError.value = null
        recoveryStep.value = 1
        showRecoveryDialog.value = true
    }

    fun closeForgotPinDialog() {
        showRecoveryDialog.value = false
        recoveryError.value = null
        recoveryStep.value = 1
    }

    private fun checkLockout() {
        lockoutRemaining.value = pinManager.getRemainingLockoutSeconds()
    }

    fun verifyRecovery(password: String) {
        if (password.isBlank()) {
            recoveryError.value = "Please enter the recovery password"
            return
        }
        if (pinManager.isRecoveryLockedOut()) {
            lockoutRemaining.value = pinManager.getRemainingLockoutSeconds()
            recoveryError.value = "Locked out. Try again later."
            return
        }

        if (pinManager.verifyRecoveryPassword(password)) {
            recoveryError.value = null
            recoveryStep.value = 2
        } else {
            checkLockout()
            if (lockoutRemaining.value > 0) {
                recoveryError.value = "Too many failed attempts. Locked out for ${lockoutRemaining.value}s."
            } else {
                recoveryError.value = "Invalid recovery password. Please try again."
            }
        }
    }

    fun applyNewPin(password: String, newPin: String, confirmPin: String) {
        if (newPin.length != 4 || !newPin.all { it.isDigit() }) {
            recoveryError.value = "New PIN must be exactly 4 digits"
            return
        }
        if (newPin != confirmPin) {
            recoveryError.value = "PINs do not match"
            return
        }

        if (pinManager.recoverAndSetPin(password, newPin)) {
            recoveryError.value = null
            showRecoveryDialog.value = false
            _isUnlocked.value = true
        } else {
            recoveryError.value = "Failed to update PIN"
        }
    }
}

@Composable
fun AppLockScreen(
    onUnlockSuccess: () -> Unit
) {
    val context = LocalContext.current
    val pinManager = remember { PinManager(context) }
    val viewModel = remember { AppLockViewModel(pinManager) }

    val pin by viewModel.pinState.collectAsState()
    val isError by viewModel.isError.collectAsState()
    val isUnlocked by viewModel.isUnlocked.collectAsState()
    val pinLockoutRemaining by viewModel.pinLockoutRemaining.collectAsState()

    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(1200)
        showSplash = false
    }

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            onUnlockSuccess()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Trigger biometric automatically if enabled and available
    LaunchedEffect(showSplash) {
        if (!showSplash && pinManager.isBiometricEnabled() && BiometricAuthHelper.canAuthenticate(context)) {
            if (context is FragmentActivity) {
                BiometricAuthHelper.showBiometricPrompt(
                    activity = context,
                    title = "मातोश्री कलेक्शन अनलॉक करा",
                    subtitle = "अनलॉक करण्यासाठी फिंगरप्रिंट वापरा",
                    onSuccess = { viewModel.onBiometricSuccess() },
                    onError = { /* Fallback to keypad smoothly */ }
                )
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = WarmIvory
    ) {
        if (showSplash) {
            // Splash Screen
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.scale(pulseScale)
                ) {
                    Surface(
                        modifier = Modifier.size(130.dp),
                        shape = CircleShape,
                        color = DeepEmerald,
                        shadowElevation = 8.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = com.matoshree.shopmanager.R.drawable.app_logo),
                                contentDescription = "Matoshree Logo",
                                modifier = Modifier.size(90.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = "मातोश्री कलेक्शन",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 28.sp,
                        color = DeepEmerald,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "MATOSHREE COLLECTION",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = ChampagneGold,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Smart Boutique & POS Manager",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MutedCharcoal,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                )

                Spacer(modifier = Modifier.height(36.dp))

                LinearProgressIndicator(
                    modifier = Modifier
                        .width(160.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = DeepEmerald,
                    trackColor = OutlineVariantGrey.copy(alpha = 0.3f)
                )
            }
        } else {
            // Main 4-Digit Unlock Keypad UI
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Header Logo
                Surface(
                    modifier = Modifier.size(68.dp),
                    shape = CircleShape,
                    color = WarmWhite,
                    border = androidx.compose.foundation.BorderStroke(2.dp, ChampagneGoldContainer),
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = com.matoshree.shopmanager.R.drawable.app_logo),
                            contentDescription = "Logo",
                            modifier = Modifier.size(50.dp)
                        )
                    }
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

                Text(
                    text = "दुकान अनलॉक करण्यासाठी ४ अंकी पिन टाका",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MutedCharcoal,
                        fontSize = 13.sp
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Lockout Banner
                AnimatedVisibility(visible = pinLockoutRemaining > 0) {
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

                Spacer(modifier = Modifier.height(30.dp))

                // Keypad
                CustomKeypad(
                    onDigitClick = { viewModel.onDigit(it) },
                    onDeleteClick = { viewModel.onDelete() },
                    showDoubleZero = false
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Forgot PIN
                TextButton(
                    onClick = { viewModel.openForgotPinDialog() }
                ) {
                    Text(
                        text = "पिन विसरलात का?",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = ChampagneGold,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }

        // Forgot PIN Recovery Modal Dialog
        val showRecoveryDialog by viewModel.showRecoveryDialog.collectAsState()
        val recoveryStep by viewModel.recoveryStep.collectAsState()
        val recoveryError by viewModel.recoveryError.collectAsState()
        val lockoutRemaining by viewModel.lockoutRemaining.collectAsState()

        if (showRecoveryDialog) {
            var recoveryPassword by remember { mutableStateOf("") }
            var newPin by remember { mutableStateOf("") }
            var confirmPin by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { viewModel.closeForgotPinDialog() },
                containerColor = WarmWhite,
                shape = RoundedCornerShape(16.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LockReset,
                            contentDescription = null,
                            tint = DeepEmerald,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (recoveryStep == 1) "ओनर पिन रिकव्हरी" else "नवीन ४ अंकी पिन सेट करा",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = DeepEmerald
                            )
                        )
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (recoveryStep == 1) {
                            Text(
                                text = "ओळख निश्चितीसाठी मास्टर ओनर रिकव्हरी कोड टाका.",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = recoveryPassword,
                                onValueChange = { recoveryPassword = it },
                                label = { Text("रिकव्हरी कोड") },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = "दुकान अनलॉक करण्यासाठी नवीन ४ अंकी पिन निवडा.",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = newPin,
                                onValueChange = { if (it.length <= 4 && it.all { ch -> ch.isDigit() }) newPin = it },
                                label = { Text("नवीन ४ अंकी पिन") },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = confirmPin,
                                onValueChange = { if (it.length <= 4 && it.all { ch -> ch.isDigit() }) confirmPin = it },
                                label = { Text("पिन पुन्हा टाका (Confirm)") },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (recoveryError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = recoveryError!!,
                                style = MaterialTheme.typography.bodySmall.copy(color = BoutiqueError)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (recoveryStep == 1) {
                                viewModel.verifyRecovery(recoveryPassword)
                            } else {
                                viewModel.applyNewPin(recoveryPassword, newPin, confirmPin)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (recoveryStep == 1) "पडताळणी करा" else "पिन बदला")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.closeForgotPinDialog() }) {
                        Text("रद्द करा", color = MutedCharcoal)
                    }
                }
            )
        }
    }
}

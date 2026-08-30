package com.matoshree.shopmanager.ui.screens.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    fun onDigit(digit: String) {
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
            if (pinManager.verifyPin(_pinState.value)) {
                _isUnlocked.value = true
            } else {
                _isError.value = true
                delay(600)
                _pinState.value = ""
                _isError.value = false
            }
        }
    }

    fun onBiometricSuccess() {
        _isUnlocked.value = true
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

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            onUnlockSuccess()
        }
    }

    // Auto-prompt biometric if available and enabled
    LaunchedEffect(Unit) {
        if (pinManager.isBiometricEnabled() && BiometricAuthHelper.canAuthenticate(context)) {
            (context as? FragmentActivity)?.let { activity ->
                BiometricAuthHelper.showBiometricPrompt(
                    activity = activity,
                    onSuccess = { viewModel.onBiometricSuccess() },
                    onError = { /* Allow PIN entry fallback */ }
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmIvory)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header Diamond Icon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(WarmWhite),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Diamond,
                    contentDescription = "Boutique Logo",
                    tint = DeepEmerald,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Matoshree",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 36.sp,
                    color = DeepEmerald,
                    fontWeight = FontWeight.Bold
                )
            )

            Text(
                text = "Enter your PIN to access the boutique",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MutedCharcoal
                )
            )

            Spacer(modifier = Modifier.height(32.dp))

            // PIN Indicator Dots
            PinIndicator(
                enteredCount = pin.length,
                isError = isError
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Keypad
            CustomKeypad(
                onDigitClick = { viewModel.onDigit(it) },
                onDeleteClick = { viewModel.onDelete() },
                showDoubleZero = false
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Biometric Option Button
            if (BiometricAuthHelper.canAuthenticate(context)) {
                TextButton(
                    onClick = {
                        (context as? FragmentActivity)?.let { activity ->
                            BiometricAuthHelper.showBiometricPrompt(
                                activity = activity,
                                onSuccess = { viewModel.onBiometricSuccess() },
                                onError = {}
                            )
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = "Biometric",
                        tint = DeepEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Unlock with Biometrics",
                        color = DeepEmerald,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp)
                    )
                }
            }
        }
    }
}

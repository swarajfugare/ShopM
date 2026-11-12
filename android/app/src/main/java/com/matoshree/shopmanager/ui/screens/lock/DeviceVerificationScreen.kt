package com.matoshree.shopmanager.ui.screens.lock

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matoshree.shopmanager.security.AppSecurityShield
import com.matoshree.shopmanager.security.DeviceTrustManager
import com.matoshree.shopmanager.security.TotpAuthenticator
import com.matoshree.shopmanager.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DeviceVerificationScreen(
    securityStatus: AppSecurityShield.SecurityStatus,
    onVerificationSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val deviceTrustManager = remember { DeviceTrustManager(context) }

    var otpInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }

    val accountEmail = remember { TotpAuthenticator.ACCOUNT_EMAIL }
    val hardwareId = remember { deviceTrustManager.getHardwareDeviceId() }
    val deviceName = remember { deviceTrustManager.getDeviceName() }

    LaunchedEffect(Unit) {
        deviceTrustManager.registerPendingDevice()
    }

    DisposableEffect(Unit) {
        val listener = deviceTrustManager.listenForApproval {
            Toast.makeText(context, "डिव्हाइसला क्लाउडवरून अधिकृत करण्यात आले!", Toast.LENGTH_SHORT).show()
            onVerificationSuccess()
        }
        onDispose {
            listener?.remove()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = WarmIvory
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Emblem
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(DeepEmerald.copy(alpha = 0.12f))
                    .border(2.dp, ChampagneGold, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Security Shield",
                    tint = DeepEmerald,
                    modifier = Modifier.size(42.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "डिव्हाइस ऑथोरायझेशन",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = DeepEmerald,
                    textAlign = TextAlign.Center,
                    fontSize = 22.sp
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "हे डिव्हाइस अधिकृत करण्यासाठी $accountEmail च्या Google Authenticator ॲपमधील ६ अंकी कोड टाका, किंवा दुकान मालकाकडून या डिव्हाइसला क्लाउड मंजुरी घ्या.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MutedCharcoal,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                ),
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                color = DeepEmeraldContainer.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, ChampagneGold.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = DeepEmerald, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$deviceName • ID: $hardwareId",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = DeepEmerald)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 6-digit Code Input
            OutlinedTextField(
                value = otpInput,
                onValueChange = {
                    if (it.length <= 6 && it.all { ch -> ch.isDigit() }) {
                        otpInput = it
                        errorMessage = null
                        if (it.length == 6) {
                            isVerifying = true
                            coroutineScope.launch {
                                val authorized = deviceTrustManager.verifyAndAuthorizeDevice(it)
                                isVerifying = false
                                if (authorized) {
                                    Toast.makeText(context, "डिव्हाइस यशस्वीरित्या अधिकृत झाले", Toast.LENGTH_SHORT).show()
                                    onVerificationSuccess()
                                } else {
                                    errorMessage = "अवैध ६ अंकी कोड. कृपया Google Authenticator तपासा."
                                }
                            }
                        }
                    }
                },
                placeholder = { Text("६ अंकी कोड टाका", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), fontSize = 16.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Pin, contentDescription = null, tint = DeepEmerald)
                },
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    color = DeepEmerald,
                    fontSize = 22.sp
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            AnimatedVisibility(visible = errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = BoutiqueError,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (otpInput.length < 6) {
                        errorMessage = "कृपया Google Authenticator मधील पूर्ण ६ अंक टाका"
                        return@Button
                    }
                    isVerifying = true
                    coroutineScope.launch {
                        val authorized = deviceTrustManager.verifyAndAuthorizeDevice(otpInput)
                        isVerifying = false
                        if (authorized) {
                            Toast.makeText(context, "डिव्हाइस यशस्वीरित्या अधिकृत झाले", Toast.LENGTH_SHORT).show()
                            onVerificationSuccess()
                        } else {
                            errorMessage = "अवैध ६ अंकी कोड. कृपया Google Authenticator तपासा."
                        }
                    }
                },
                enabled = !isVerifying,
                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp), tint = ChampagneGold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("पडताळणी करा आणि अनलॉक करा", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = WarmWhite)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    isVerifying = true
                    coroutineScope.launch {
                        val authorized = deviceTrustManager.checkCloudApproval()
                        isVerifying = false
                        if (authorized) {
                            Toast.makeText(context, "डिव्हाइसला क्लाउडवरून अधिकृत करण्यात आले!", Toast.LENGTH_SHORT).show()
                            onVerificationSuccess()
                        } else {
                            errorMessage = "अद्याप क्लाउड मंजुरी मिळालेली नाही. दुकान मालकाला सांगा."
                        }
                    }
                },
                enabled = !isVerifying,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DeepEmerald),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp), tint = DeepEmerald)
                Spacer(modifier = Modifier.width(6.dp))
                Text("क्लाउड मंजुरी तपासा (Check Cloud Approval)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = DeepEmerald)
            }
        }
    }
}

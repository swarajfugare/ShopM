package com.matoshree.shopmanager.ui.screens.lock

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matoshree.shopmanager.security.GoogleAuthManager
import com.matoshree.shopmanager.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun GoogleLoginScreen(
    googleAuthManager: GoogleAuthManager,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            coroutineScope.launch {
                val res = googleAuthManager.handleSignInResult(result.data)
                isLoading = false
                if (res.isSuccess) {
                    Toast.makeText(context, "Google लॉगिन यशस्वी!", Toast.LENGTH_SHORT).show()
                    onLoginSuccess()
                } else {
                    errorMessage = res.exceptionOrNull()?.message ?: "लॉगिन अयशस्वी."
                }
            }
        } else {
            isLoading = false
            errorMessage = "लॉगिन रद्द केले किंवा अयशस्वी झाले."
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
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Emblem
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(DeepEmerald.copy(alpha = 0.12f))
                    .border(2.dp, ChampagneGold, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Security Shield",
                    tint = DeepEmerald,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "मातोश्री कलेक्शन",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = DeepEmerald,
                    letterSpacing = 1.sp
                )
            )

            Text(
                text = "अधिकृत मालक प्रमाणीकरण",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = ChampagneGold,
                    letterSpacing = 0.5.sp
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = WarmWhite),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, ChampagneGold.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = DeepEmerald,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "सुरक्षित Google लॉगिन",
                        fontWeight = FontWeight.Bold,
                        color = DeepEmerald,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "ॲपमधील दुकानाचा डेटा सुरक्षित ठेवण्यासाठी आपल्या अधिकृत Google खात्याने लॉगिन करा.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MutedCharcoal,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    )
                }
            }

            AnimatedVisibility(visible = errorMessage != null) {
                Surface(
                    color = BoutiqueError.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BoutiqueError.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = BoutiqueError,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    isLoading = true
                    errorMessage = null
                    try {
                        signInLauncher.launch(googleAuthManager.getSignInIntent())
                    } catch (e: Exception) {
                        isLoading = false
                        errorMessage = "लॉगिन सुरू करता आले नाही: ${e.message}"
                    }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(24.dp), tint = ChampagneGold)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Google ने लॉगिन करा",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = WarmWhite
                    )
                }
            }
        }
    }
}

package com.matoshree.shopmanager.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matoshree.shopmanager.data.repository.SyncRepository
import com.matoshree.shopmanager.security.PinManager
import com.matoshree.shopmanager.sync.SyncManager
import com.matoshree.shopmanager.ui.components.MatoshreeCard
import com.matoshree.shopmanager.ui.components.MatoshreeTopAppBar
import com.matoshree.shopmanager.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val syncRepository: SyncRepository,
    private val pinManager: PinManager
) : ViewModel() {

    val pendingSyncCount = MutableStateFlow(0)
    val isBiometricEnabled = MutableStateFlow(pinManager.isBiometricEnabled())
    val profitMargin = MutableStateFlow(25.0)

    init {
        viewModelScope.launch {
            syncRepository.pendingSyncCount.collectLatest {
                pendingSyncCount.value = it
            }
        }
    }

    fun toggleBiometric(enabled: Boolean) {
        pinManager.setBiometricEnabled(enabled)
        isBiometricEnabled.value = enabled
    }

    fun triggerSync(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = syncRepository.performSync()
            onComplete(success)
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val pendingSync by viewModel.pendingSyncCount.collectAsState()
    val biometricEnabled by viewModel.isBiometricEnabled.collectAsState()
    val margin by viewModel.profitMargin.collectAsState()

    Scaffold(
        topBar = {
            MatoshreeTopAppBar(
                title = "Settings",
                showBackButton = true,
                onBackClick = onBackClick
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(WarmIvory)
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)
        ) {
            // Boutique Information
            item {
                MatoshreeCard(hasGoldTopBorder = true) {
                    Text("Boutique Profile", style = MaterialTheme.typography.titleMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Matoshree Collection", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                    Text("GSTIN: 27AAAAA0000A1Z5", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                    Text("123 Silk Route, Kolhapur, Maharashtra", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                }
            }

            // Sync Status & Offline Engine
            item {
                MatoshreeCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Offline Sync Queue", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text(
                                text = if (pendingSync == 0) "All data synchronized with Hostinger MySQL" else "$pendingSync transactions pending sync",
                                style = MaterialTheme.typography.bodySmall.copy(color = if (pendingSync == 0) BoutiqueSuccess else ChampagneGold)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.triggerSync { success ->
                                Toast.makeText(
                                    context,
                                    if (success) "Synchronization completed successfully" else "Sync failed. Will retry automatically.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sync Now with Server", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Security Settings
            item {
                MatoshreeCard {
                    Text("Security & PIN", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Biometric Authentication", fontWeight = FontWeight.SemiBold)
                            Text("Fingerprint / Face unlock", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = { viewModel.toggleBiometric(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = DeepEmerald, checkedTrackColor = DeepEmeraldContainer)
                        )
                    }
                }
            }

            // Business Rules
            item {
                MatoshreeCard {
                    Text("Financial Rules", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Default Estimated Profit Margin", fontWeight = FontWeight.SemiBold)
                            Text("Applied when cost price is unlisted", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                        }
                        Text("$margin%", fontWeight = FontWeight.Bold, color = DeepEmerald)
                    }
                }
            }

            // Version info
            item {
                Text(
                    text = "Matoshree Collection Shop Manager • v1.0.0\nHostinger MySQL + Room Offline Sync",
                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

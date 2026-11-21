package com.matoshree.shopmanager.ui.screens.invoicescan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matoshree.shopmanager.R
import com.matoshree.shopmanager.ui.theme.*

@Composable
fun InvoiceScanScreen(
    viewModel: InvoiceScanViewModel,
    onBackClick: () -> Unit,
    onNavigateToLabels: (List<Long>) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    when (val step = uiState.step) {
        is ScanStep.Capture -> {
            InvoiceCaptureScreen(
                viewModel = viewModel,
                onBackClick = onBackClick
            )
        }
        is ScanStep.ProcessingAi -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WarmIvory)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    color = DeepEmerald,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = step.progressMessage,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "कृपया थोडा वेळ थांबा...",
                    fontSize = 13.sp,
                    color = MutedCharcoal,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { viewModel.backToCapture() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DeepCharcoal)
                ) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        }
        is ScanStep.Review, is ScanStep.Saving, is ScanStep.Success -> {
            InvoiceReviewScreen(
                viewModel = viewModel,
                onNavigateBack = onBackClick,
                onNavigateToLabels = onNavigateToLabels
            )
        }
        is ScanStep.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WarmIvory)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = step.message,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.backToCapture() }
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("मागे जा")
                    }
                    if (step.canRetry) {
                        Button(
                            onClick = { viewModel.startAiExtraction() },
                            colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_try_again), color = WarmWhite)
                        }
                    }
                }
            }
        }
    }
}

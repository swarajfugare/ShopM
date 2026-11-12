package com.matoshree.shopmanager.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matoshree.shopmanager.domain.model.UdhariTransaction
import com.matoshree.shopmanager.domain.model.UdhariTransactionType
import com.matoshree.shopmanager.printer.ThermalPrinterManager
import com.matoshree.shopmanager.ui.theme.*
import com.matoshree.shopmanager.utils.CurrencyFormatter
import com.matoshree.shopmanager.utils.PrintShareHelper
import kotlinx.coroutines.launch

@Composable
fun PaymentReceiptDialog(
    transaction: UdhariTransaction,
    customerName: String,
    customerMobile: String,
    settings: PrintShareHelper.ShopSettingsSnapshot,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val thermalPrinterManager = remember { ThermalPrinterManager(context) }
    var isPrinting by remember { mutableStateOf(false) }

    val isDebit = transaction.type == UdhariTransactionType.PURCHASE_CREDIT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = if (isDebit) Icons.Default.PostAdd else Icons.Default.CheckCircle,
                    contentDescription = if (isDebit) "Debit Added" else "Payment Received",
                    tint = if (isDebit) DeepEmerald else BoutiqueSuccess,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isDebit) "उधारी नोंद पावती" else "जमा पावती",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = DeepEmerald,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (isDebit) "जुनी बाकी नोंद" else "पेमेंट जमा पावती",
                    style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = WarmIvory,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ChampagneGoldContainer)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("ग्राहक:", style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                            Text(customerName, fontWeight = FontWeight.Bold)
                        }
                        if (customerMobile.isNotBlank()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("मोबाईल नंबर:", style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                                Text(customerMobile, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (transaction.billNumber.isNotBlank()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("पावती क्र:", style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                                Text(transaction.billNumber, fontWeight = FontWeight.SemiBold, color = DeepEmerald)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("तारीख:", style = MaterialTheme.typography.bodyMedium.copy(color = MutedCharcoal))
                            Text(transaction.date, fontWeight = FontWeight.SemiBold)
                        }
                        if (!transaction.dueDate.isNullOrBlank()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("पेमेंट मुदत तारीख:", style = MaterialTheme.typography.bodyMedium.copy(color = BoutiqueError))
                                Text(transaction.dueDate!!, fontWeight = FontWeight.Bold, color = BoutiqueError)
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (isDebit) ChampagneGoldContainer.copy(alpha = 0.25f) else DeepEmeraldContainer.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isDebit) "नोंद केलेली उधारी" else "जमा रक्कम",
                                style = MaterialTheme.typography.labelMedium.copy(color = DeepEmerald, fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = CurrencyFormatter.format(transaction.amount),
                                style = MaterialTheme.typography.titleLarge.copy(color = if (isDebit) BoutiqueError else DeepEmerald, fontWeight = FontWeight.Bold)
                            )
                        }
                        if (!isDebit) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("पेमेंट पद्धत:", style = MaterialTheme.typography.bodyMedium)
                                Text(if (transaction.paymentMethod.name == "CASH") "कॅश" else "यूपीआय", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = OutlineVariantGrey.copy(alpha = 0.3f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("मागील बाकी:", style = MaterialTheme.typography.bodySmall.copy(color = MutedCharcoal))
                            Text(CurrencyFormatter.format(transaction.previousOutstanding), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = if (isDebit) "एकूण बाकी रक्कम:" else "शिल्लक बाकी:",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = BoutiqueError)
                            )
                            Text(
                                text = CurrencyFormatter.format(transaction.remainingOutstanding),
                                fontWeight = FontWeight.Bold,
                                color = BoutiqueError
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        PrintShareHelper.sharePaymentReceiptJpg(
                            context = context,
                            transaction = transaction,
                            customerName = customerName,
                            customerMobile = customerMobile,
                            settings = settings
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp), tint = DeepEmerald)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("व्हॉट्सॲप / शेअर", fontSize = 12.sp, color = DeepEmerald, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        if (isPrinting) return@Button
                        isPrinting = true
                        coroutineScope.launch {
                            try {
                                val res = thermalPrinterManager.printUdhariPaymentReceipt(
                                    transaction = transaction,
                                    customerName = customerName,
                                    customerMobile = customerMobile,
                                    settings = settings
                                )
                                if (res.isSuccess) {
                                    Toast.makeText(context, "पावती यशस्वीरित्या प्रिंट झाली", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, res.exceptionOrNull()?.message ?: "प्रिंट अयशस्वी", Toast.LENGTH_LONG).show()
                                }
                            } finally {
                                isPrinting = false
                            }
                        }
                    },
                    enabled = !isPrinting,
                    colors = ButtonDefaults.buttonColors(containerColor = DeepEmerald),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    if (isPrinting) {
                        CircularProgressIndicator(color = WarmWhite, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Print, contentDescription = "Print", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("प्रिंट", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("बंद करा", color = MutedCharcoal, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = WarmWhite,
        shape = RoundedCornerShape(16.dp)
    )
}

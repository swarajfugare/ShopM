package com.matoshree.shopmanager.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matoshree.shopmanager.domain.model.PaymentMethod
import com.matoshree.shopmanager.domain.model.PaymentStatus
import com.matoshree.shopmanager.domain.model.SyncStatus
import com.matoshree.shopmanager.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatoshreeTopAppBar(
    title: String,
    showBackButton: Boolean = false,
    onBackClick: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = PlayfairFontFamily,
                    color = DeepEmerald,
                    fontWeight = FontWeight.Bold
                )
            )
        },
        navigationIcon = {
            if (showBackButton) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = DeepEmerald
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = WarmWhite,
            scrolledContainerColor = WarmWhite
        )
    )
}

@Composable
fun MatoshreeCard(
    modifier: Modifier = Modifier,
    hasGoldTopBorder: Boolean = false,
    hasGoldLeftAccent: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        color = WarmWhite,
        shadowElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, DeepEmerald.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (hasGoldTopBorder) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(GoldAccent)
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                if (hasGoldLeftAccent) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(ChampagneGoldContainer)
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
fun PinIndicator(
    length: Int = 4,
    enteredCount: Int,
    isError: Boolean = false
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until length) {
            val isFilled = i < enteredCount
            val dotColor by animateColorAsState(
                targetValue = if (isError) BoutiqueError else if (isFilled) DeepEmerald else Color.Transparent,
                label = "pinColor"
            )
            val scale by animateFloatAsState(
                targetValue = if (isFilled) 1.2f else 1.0f,
                label = "pinScale"
            )

            Box(
                modifier = Modifier
                    .size(16.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(
                        width = 2.dp,
                        color = if (isError) BoutiqueError else if (isFilled) DeepEmerald else OutlineVariantGrey,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
fun CustomKeypad(
    onDigitClick: (String) -> Unit,
    onDeleteClick: () -> Unit,
    showDoubleZero: Boolean = true
) {
    val keypadGrid = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(if (showDoubleZero) "00" else "", "0", "DEL")
    )

    Column(
        modifier = Modifier.fillMaxWidth(0.85f),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        for (row in keypadGrid) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (key in row) {
                    if (key.isEmpty()) {
                        Spacer(modifier = Modifier.size(64.dp))
                    } else if (key == "DEL") {
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(SurfaceContainerLow)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Backspace,
                                contentDescription = "Delete",
                                tint = DeepCharcoal
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(WarmWhite)
                                .border(1.dp, DeepEmerald.copy(alpha = 0.08f), CircleShape)
                                .clickable { onDigitClick(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = DeepCharcoal
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(
    status: String,
    isSuccess: Boolean = false,
    isWarning: Boolean = false,
    isError: Boolean = false
) {
    val bgColor = when {
        isSuccess -> BoutiqueSuccessBg
        isError -> BoutiqueErrorBg
        isWarning -> ChampagneGoldContainer.copy(alpha = 0.2f)
        else -> SurfaceContainer
    }
    val textColor = when {
        isSuccess -> BoutiqueSuccess
        isError -> BoutiqueError
        isWarning -> ChampagneGold
        else -> MutedCharcoal
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall.copy(
                color = textColor,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
fun PaymentMethodPill(
    method: PaymentMethod,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val (icon, label) = when (method) {
        PaymentMethod.CASH -> Icons.Outlined.AccountBalanceWallet to "Cash"
        PaymentMethod.UPI -> Icons.Outlined.QrCodeScanner to "UPI"
        PaymentMethod.CARD -> Icons.Outlined.CreditCard to "Card"
        else -> Icons.Outlined.Payments to "Other"
    }

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = if (isSelected) DeepEmeraldContainer else WarmWhite,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) DeepEmerald else OutlineVariantGrey.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) WarmWhite else DeepCharcoal,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 14.sp,
                    color = if (isSelected) WarmWhite else DeepCharcoal
                )
            )
        }
    }
}

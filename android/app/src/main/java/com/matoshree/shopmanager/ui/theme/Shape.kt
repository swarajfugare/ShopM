package com.matoshree.shopmanager.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val MatoshreeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), // Status chips & tags
    small = RoundedCornerShape(8.dp),      // Input fields
    medium = RoundedCornerShape(12.dp),    // Cards & Primary Buttons
    large = RoundedCornerShape(16.dp),     // Modal Sheets
    extraLarge = RoundedCornerShape(24.dp) // Large banners
)

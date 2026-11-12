package com.matoshree.shopmanager.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = DeepEmerald,
    onPrimary = WarmWhite,
    primaryContainer = DeepEmeraldContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = ChampagneGold,
    onSecondary = WarmWhite,
    secondaryContainer = ChampagneGoldContainer,
    onSecondaryContainer = OnGoldContainer,
    background = WarmIvory,
    onBackground = DeepCharcoal,
    surface = WarmWhite,
    onSurface = DeepCharcoal,
    surfaceVariant = SurfaceContainer,
    onSurfaceVariant = MutedCharcoal,
    outline = OutlineGrey,
    outlineVariant = OutlineVariantGrey,
    error = BoutiqueError,
    onError = WarmWhite
)

@Composable
fun MatoshreeTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MatoshreeTypography,
        shapes = MatoshreeShapes,
        content = content
    )
}

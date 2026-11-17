package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DeskDeckColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF04131E),
    primaryContainer = ElectricCyanDim,
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = SoftViolet,
    onSecondary = Color(0xFF160B30),
    secondaryContainer = SoftVioletDim,
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = EmeraldAccent,
    onTertiary = Color(0xFF052E16),
    tertiaryContainer = EmeraldDim,
    onTertiaryContainer = Color(0xFFDCFCE7),
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    outlineVariant = SlateBorderBright,
    error = CoralRed,
    onError = Color.White,
    errorContainer = CoralRedDim,
    onErrorContainer = Color(0xFFFEE2E2),
)

private val DeskDeckShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun DeskDeckTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DeskDeckColorScheme,
        typography = DeskDeckTypography,
        shapes = DeskDeckShapes,
        content = content,
    )
}

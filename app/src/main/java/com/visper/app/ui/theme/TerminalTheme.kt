package com.visper.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Modern Minimal Dark Theme
val AppBackground = Color(0xFF09090B)
val AppSurface = Color(0xFF141417)
val AppSurfaceElevated = Color(0xFF1C1C21)
val AppBorder = Color(0xFF27272A)
val AppBorderLight = Color(0xFF3F3F46)

val AppPrimary = Color(0xFF3B82F6) // Modern Blue
val AppAccent = Color(0xFF10B981)  // Emerald
val AppAmber = Color(0xFFF59E0B)
val AppRed = Color(0xFFEF4444)

val TextPrimary = Color(0xFFFAFAFA)
val TextSecondary = Color(0xFFA1A1AA)
val TextMuted = Color(0xFF71717A)

// Backward compatibility aliases
val TerminalBlack = AppBackground
val TerminalSurface = AppSurface
val TerminalBorder = AppBorder
val TerminalGreen = AppAccent
val TerminalAmber = AppAmber
val TerminalCyan = AppPrimary
val TerminalDim = TextMuted
val TerminalRed = AppRed
val TerminalWhite = TextPrimary

private val AppColorScheme = darkColorScheme(
    primary = AppPrimary,
    onPrimary = Color.White,
    secondary = AppAccent,
    onSecondary = Color.White,
    tertiary = AppAmber,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = AppSurface,
    onSurface = TextPrimary
)

val AppBodyStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
    color = TextPrimary
)

val AppCodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    color = Color(0xFF93C5FD)
)

val AppHeaderStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    color = TextPrimary
)

val AppCaptionStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    color = TextMuted
)

// Backward compatibility styles
val TerminalMonoStyle = AppBodyStyle
val TerminalHeaderStyle = AppCaptionStyle

@Composable
fun VisperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        content = content
    )
}

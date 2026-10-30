package com.visper.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val TerminalBlack = Color(0xFF050505)
val TerminalSurface = Color(0xFF0F0F0F)
val TerminalBorder = Color(0xFF1F1F1F)

val TerminalGreen = Color(0xFF00FF66)
val TerminalAmber = Color(0xFFFFB000)
val TerminalCyan = Color(0xFF00E5FF)
val TerminalDim = Color(0xFF6E6E6E)
val TerminalRed = Color(0xFFFF4444)
val TerminalWhite = Color(0xFFEEEEEE)

private val TerminalColorScheme = darkColorScheme(
    primary = TerminalGreen,
    onPrimary = TerminalBlack,
    secondary = TerminalCyan,
    onSecondary = TerminalBlack,
    tertiary = TerminalAmber,
    background = TerminalBlack,
    onBackground = TerminalGreen,
    surface = TerminalSurface,
    onSurface = TerminalWhite
)

val TerminalMonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    color = TerminalGreen
)

val TerminalHeaderStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    color = TerminalDim
)

@Composable
fun VisperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TerminalColorScheme,
        content = content
    )
}

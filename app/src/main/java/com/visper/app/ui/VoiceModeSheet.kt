package com.visper.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visper.app.ui.theme.AppAccent
import com.visper.app.ui.theme.AppBackground
import com.visper.app.ui.theme.AppBorder
import com.visper.app.ui.theme.AppPrimary
import com.visper.app.ui.theme.AppSurface
import com.visper.app.ui.theme.TextPrimary
import com.visper.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceModeSheet(
    status: VoiceModeStatus,
    rmsDb: Float,
    userTranscript: String,
    aiResponse: String,
    onDismiss: () -> Unit,
    onTapOrb: () -> Unit,
    onInterrupt: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppBackground,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (status) {
                                    VoiceModeStatus.LISTENING -> Color(0xFF38BDF8)
                                    VoiceModeStatus.THINKING -> Color(0xFFFBBF24)
                                    VoiceModeStatus.SPEAKING -> AppAccent
                                    VoiceModeStatus.IDLE -> TextSecondary
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Conversational Voice",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = AppSurface,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("✕", fontSize = 13.sp, color = TextSecondary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            // Central Animated Neural Orb
            VoicePulsingOrb(
                status = status,
                rmsDb = rmsDb,
                onClick = onTapOrb
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Status description
            val statusTitle = when (status) {
                VoiceModeStatus.LISTENING -> "Listening to you..."
                VoiceModeStatus.THINKING -> "Thinking locally..."
                VoiceModeStatus.SPEAKING -> "Visper is speaking..."
                VoiceModeStatus.IDLE -> "Tap orb to speak"
            }
            Text(
                text = statusTitle,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = when (status) {
                    VoiceModeStatus.LISTENING -> Color(0xFF38BDF8)
                    VoiceModeStatus.THINKING -> Color(0xFFFBBF24)
                    VoiceModeStatus.SPEAKING -> AppAccent
                    VoiceModeStatus.IDLE -> TextPrimary
                }
            )

            Text(
                text = "100% Offline • On-Device Speech & LLM",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Real-time transcript cards
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppSurface)
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (userTranscript.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("👤", fontSize = 13.sp)
                        Text(
                            text = userTranscript,
                            fontSize = 13.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }

                if (aiResponse.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("✦", fontSize = 13.sp, color = AppPrimary)
                        Text(
                            text = aiResponse,
                            fontSize = 13.sp,
                            color = TextPrimary.copy(alpha = 0.9f),
                            fontWeight = FontWeight.Light,
                            lineHeight = 18.sp
                        )
                    }
                }

                if (userTranscript.isBlank() && aiResponse.isBlank()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Say \"What is quantum computing?\" or \"Turn on flashlight\"",
                            fontSize = 12.sp,
                            color = TextSecondary.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Control Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                when (status) {
                    VoiceModeStatus.SPEAKING -> {
                        Surface(
                            onClick = onInterrupt,
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⏹", fontSize = 14.sp, color = Color(0xFFEF4444))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Interrupt Speech", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFFEF4444))
                            }
                        }
                    }
                    VoiceModeStatus.LISTENING -> {
                        Surface(
                            onClick = onTapOrb,
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF38BDF8).copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⏹", fontSize = 14.sp, color = Color(0xFF38BDF8))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Done Speaking", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF38BDF8))
                            }
                        }
                    }
                    VoiceModeStatus.THINKING -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AppSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⚡", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Synthesizing answer...", fontSize = 14.sp, color = TextSecondary)
                            }
                        }
                    }
                    VoiceModeStatus.IDLE -> {
                        Surface(
                            onClick = onTapOrb,
                            shape = RoundedCornerShape(12.dp),
                            color = AppPrimary,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🎙️", fontSize = 15.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Tap to Speak", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun VoicePulsingOrb(
    status: VoiceModeStatus,
    rmsDb: Float,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbTransition")

    // Idle / Thinking pulse
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idlePulse"
    )

    // Thinking rotation / ripple
    val thinkingWave by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thinkingWave"
    )

    // Animated dynamic mic level
    val micScale by animateFloatAsState(
        targetValue = if (status == VoiceModeStatus.LISTENING) {
            1.0f + (rmsDb.coerceIn(0f, 12f) / 14f)
        } else 1.0f,
        animationSpec = tween(100),
        label = "micScale"
    )

    val coreScale = when (status) {
        VoiceModeStatus.LISTENING -> micScale
        VoiceModeStatus.THINKING -> idlePulse
        VoiceModeStatus.SPEAKING -> idlePulse * 1.06f
        VoiceModeStatus.IDLE -> idlePulse
    }

    val primaryColor = when (status) {
        VoiceModeStatus.LISTENING -> Color(0xFF38BDF8)
        VoiceModeStatus.THINKING -> Color(0xFFFBBF24)
        VoiceModeStatus.SPEAKING -> AppAccent
        VoiceModeStatus.IDLE -> AppPrimary
    }

    val secondaryColor = when (status) {
        VoiceModeStatus.LISTENING -> Color(0xFF2563EB)
        VoiceModeStatus.THINKING -> Color(0xFFD97706)
        VoiceModeStatus.SPEAKING -> Color(0xFF059669)
        VoiceModeStatus.IDLE -> Color(0xFF4F46E5)
    }

    Box(
        modifier = Modifier
            .size(180.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(180.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = 55.dp.toPx()

            // Outer ripple ring
            if (status == VoiceModeStatus.LISTENING || status == VoiceModeStatus.SPEAKING) {
                drawCircle(
                    color = primaryColor.copy(alpha = 0.15f),
                    radius = baseRadius * coreScale * 1.3f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = primaryColor.copy(alpha = 0.08f),
                    radius = baseRadius * coreScale * 1.55f,
                    center = center
                )
            } else if (status == VoiceModeStatus.THINKING) {
                drawCircle(
                    color = primaryColor.copy(alpha = (1.4f - thinkingWave).coerceIn(0f, 0.3f)),
                    radius = baseRadius * thinkingWave,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Glow radial gradient behind orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.35f),
                        secondaryColor.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.4f
                ),
                radius = baseRadius * 1.4f,
                center = center
            )

            // Inner solid glowing sphere
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(primaryColor, secondaryColor),
                    start = Offset(center.x - baseRadius, center.y - baseRadius),
                    end = Offset(center.x + baseRadius, center.y + baseRadius)
                ),
                radius = baseRadius * coreScale,
                center = center
            )

            // Highlight glint
            drawCircle(
                color = Color.White.copy(alpha = 0.45f),
                radius = baseRadius * coreScale * 0.28f,
                center = Offset(center.x - baseRadius * 0.35f, center.y - baseRadius * 0.35f)
            )
        }
    }
}

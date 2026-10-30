package com.visper.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visper.app.model.Attachment
import com.visper.app.model.ChatMessage
import com.visper.app.model.EngineState
import com.visper.app.model.MessageSender
import com.visper.app.ui.theme.AppAccent
import com.visper.app.ui.theme.AppAmber
import com.visper.app.ui.theme.AppBackground
import com.visper.app.ui.theme.AppBodyStyle
import com.visper.app.ui.theme.AppBorder
import com.visper.app.ui.theme.AppBorderLight
import com.visper.app.ui.theme.AppCaptionStyle
import com.visper.app.ui.theme.AppHeaderStyle
import com.visper.app.ui.theme.AppPrimary
import com.visper.app.ui.theme.AppRed
import com.visper.app.ui.theme.AppSurface
import com.visper.app.ui.theme.AppSurfaceElevated
import com.visper.app.ui.theme.TextMuted
import com.visper.app.ui.theme.TextPrimary
import com.visper.app.ui.theme.TextSecondary

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImageSelected(it) }
    }

    val docPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onDocumentSelected(it) }
    }

    // Auto-scroll on new message or streaming tokens
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text?.length) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
    ) {
        // --- 1. CLEAN APP HEADER ---
        AppHeader(
            engineState = uiState.engineState,
            tokensPerSecond = uiState.metrics.tokensPerSecond,
            onClear = { viewModel.clearConversation() }
        )

        // --- 2. CONVERSATION STREAM ---
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(modifier = Modifier.height(6.dp)) }

            items(
                items = uiState.messages,
                key = { it.id }
            ) { msg ->
                MessageBubble(message = msg)
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        // --- 3. ACTIVE ATTACHMENTS TRAY ---
        ActiveAttachmentsTray(
            uiState = uiState,
            onClear = { viewModel.clearAttachments() }
        )

        if (uiState.isRecordingAudio) {
            AudioRecordingBanner(waveformVisual = uiState.audioWaveformVisual)
        }

        // --- 4. MODERN INPUT BAR ---
        ModernInputBar(
            inputText = uiState.inputText,
            onTextChanged = { viewModel.onInputTextChanged(it) },
            onSubmit = { viewModel.submitPrompt() },
            onPickDoc = { docPicker.launch("*/*") },
            onPickImg = { imagePicker.launch("image/*") },
            onToggleMic = { viewModel.toggleVoiceRecording() },
            isRecording = uiState.isRecordingAudio,
            engineState = uiState.engineState
        )
    }
}

@Composable
private fun AppHeader(
    engineState: EngineState,
    tokensPerSecond: Float,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface)
            .border(width = 0.5.dp, color = AppBorder)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Pulsing status dot
            val dotColor = when (engineState) {
                EngineState.READY -> AppAccent
                EngineState.GENERATING -> AppAmber
                EngineState.ERROR -> AppRed
                else -> TextMuted
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = "Visper",
                    style = AppHeaderStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
                )
                val statusText = when (engineState) {
                    EngineState.GENERATING -> if (tokensPerSecond > 0f) "Generating (%.1f tok/s)".format(tokensPerSecond) else "Thinking..."
                    EngineState.READY -> "On-Device Neural Core"
                    EngineState.LOADING -> "Initializing Engine..."
                    EngineState.ERROR -> "Engine Offline"
                    EngineState.UNINITIALIZED -> "Standby"
                }
                Text(
                    text = statusText,
                    style = AppCaptionStyle.copy(fontSize = 11.sp, color = TextMuted)
                )
            }
        }

        // Clear chat button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(AppSurfaceElevated)
                .clickable { onClear() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Clear",
                style = AppCaptionStyle.copy(color = TextSecondary, fontWeight = FontWeight.Medium)
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.sender == MessageSender.USER
    val isAi = message.sender == MessageSender.AI
    val isError = message.sender == MessageSender.ERROR

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            // USER BUBBLE
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(AppSurfaceElevated)
                    .border(0.5.dp, AppBorder, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .padding(14.dp)
            ) {
                // Attached Image Thumbnail
                if (message.attachment is Attachment.Image) {
                    val context = LocalContext.current
                    val bitmap = remember(message.attachment.uri) {
                        try {
                            if (message.attachment.uri.scheme == "file" && message.attachment.uri.path != null) {
                                BitmapFactory.decodeFile(message.attachment.uri.path)
                            } else {
                                context.contentResolver.openInputStream(message.attachment.uri)?.use { stream ->
                                    BitmapFactory.decodeStream(stream)
                                }
                            }
                        } catch (_: Exception) { null }
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Attached Document Card
                if (message.attachment is Attachment.Document) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "📄", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = message.attachment.fileName,
                            style = AppBodyStyle.copy(fontSize = 12.sp, color = AppPrimary, fontWeight = FontWeight.Medium),
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Prompt Text
                Text(
                    text = message.text,
                    style = AppBodyStyle.copy(color = TextPrimary)
                )
            }
        } else if (isAi) {
            // AI ASSISTANT BUBBLE
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "✦ Visper",
                        style = AppHeaderStyle.copy(
                            fontSize = 13.sp,
                            color = AppPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                        .background(AppSurface)
                        .border(0.5.dp, AppBorder, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        val textWithCursor = if (message.isStreaming) message.text + " ▮" else message.text
                        Text(
                            text = textWithCursor,
                            style = AppBodyStyle.copy(
                                color = Color(0xFFF3F4F6),
                                lineHeight = 22.sp
                            )
                        )

                        if (!message.isStreaming && message.timeToFirstTokenMs != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "On-device • %.1fs response time".format((message.timeToFirstTokenMs ?: 0L) / 1000f),
                                style = AppCaptionStyle.copy(fontSize = 10.sp, color = TextMuted)
                            )
                        }
                    }
                }
            }
        } else if (isError) {
            // ERROR BUBBLE
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF261214))
                    .border(0.5.dp, AppRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = message.text,
                    style = AppBodyStyle.copy(color = AppRed, fontSize = 13.sp)
                )
            }
        }
    }
}

@Composable
private fun ActiveAttachmentsTray(
    uiState: TerminalUiState,
    onClear: () -> Unit
) {
    val hasAttachments = uiState.attachedImageBitmap != null || uiState.attachedDoc != null
    AnimatedVisibility(
        visible = hasAttachments,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            uiState.attachedImageBitmap?.let { bmp ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppSurfaceElevated)
                        .border(0.5.dp, AppBorderLight, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Attachment preview",
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Image attached",
                        style = AppCaptionStyle.copy(color = TextPrimary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "✕",
                        style = AppCaptionStyle.copy(color = AppRed, fontWeight = FontWeight.Bold),
                        modifier = Modifier.clickable { onClear() }
                    )
                }
            }

            uiState.attachedDoc?.let { doc ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppSurfaceElevated)
                        .border(0.5.dp, AppBorderLight, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "📄", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = doc.fileName,
                        style = AppCaptionStyle.copy(color = TextPrimary),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "✕",
                        style = AppCaptionStyle.copy(color = AppRed, fontWeight = FontWeight.Bold),
                        modifier = Modifier.clickable { onClear() }
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioRecordingBanner(waveformVisual: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF261214))
            .border(0.5.dp, AppRed)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
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
                        .background(AppRed)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Listening... Speak now",
                    style = AppBodyStyle.copy(color = AppRed, fontSize = 13.sp)
                )
            }
            if (waveformVisual.isNotBlank()) {
                Text(
                    text = waveformVisual,
                    style = AppBodyStyle.copy(color = AppRed, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}

@Composable
private fun ModernInputBar(
    inputText: String,
    onTextChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onPickDoc: () -> Unit,
    onPickImg: () -> Unit,
    onToggleMic: () -> Unit,
    isRecording: Boolean,
    engineState: EngineState
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface)
            .border(0.5.dp, AppBorder)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Quick Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionButton(label = "Photo", icon = "📷", onClick = onPickImg)
            ActionButton(label = "Doc", icon = "📄", onClick = onPickDoc)
            ActionButton(
                label = if (isRecording) "Stop" else "Voice",
                icon = if (isRecording) "⏹" else "🎙",
                activeColor = if (isRecording) AppRed else null,
                onClick = onToggleMic
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Text Input & Send Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(AppBackground)
                .border(0.5.dp, AppBorder, RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (inputText.isEmpty()) {
                    Text(
                        text = "Message Visper...",
                        style = AppBodyStyle.copy(color = TextMuted)
                    )
                }

                BasicTextField(
                    value = inputText,
                    onValueChange = onTextChanged,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = AppBodyStyle.copy(color = TextPrimary),
                    cursorBrush = SolidColor(AppPrimary),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { onSubmit() }
                    )
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Send / Halt Circular Button
            val isGenerating = engineState == EngineState.GENERATING
            val sendButtonColor = if (isGenerating) AppRed else AppPrimary

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(sendButtonColor)
                    .clickable { onSubmit() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isGenerating) "■" else "↑",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: String,
    activeColor: Color? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AppSurfaceElevated)
            .border(0.5.dp, activeColor ?: AppBorder, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = AppCaptionStyle.copy(
                    color = activeColor ?: TextSecondary,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

package com.visper.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visper.app.model.Attachment
import com.visper.app.model.ChatMessage
import com.visper.app.model.EngineState
import com.visper.app.model.MessageSender
import com.visper.app.ui.theme.TerminalAmber
import com.visper.app.ui.theme.TerminalBlack
import com.visper.app.ui.theme.TerminalBorder
import com.visper.app.ui.theme.TerminalCyan
import com.visper.app.ui.theme.TerminalDim
import com.visper.app.ui.theme.TerminalGreen
import com.visper.app.ui.theme.TerminalHeaderStyle
import com.visper.app.ui.theme.TerminalMonoStyle
import com.visper.app.ui.theme.TerminalRed
import com.visper.app.ui.theme.TerminalSurface
import com.visper.app.ui.theme.TerminalWhite

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // File pickers
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

    // Auto scroll on new tokens or messages
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text?.length) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TerminalBlack)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
    ) {
        // --- 1. TELEMETRY STATUS BAR ---
        TelemetryHeader(uiState = uiState)

        // --- 2. TERMINAL LOG / CHAT STREAM ---
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(6.dp)) }

            items(
                items = uiState.messages,
                key = { it.id }
            ) { msg ->
                TerminalMessageItem(message = msg)
            }

            item { Spacer(modifier = Modifier.height(6.dp)) }
        }

        // --- 3. LIVE ATTACHMENTS / AUDIO RECORDING BANNER ---
        if (uiState.isRecordingAudio) {
            AudioRecordingBanner(waveformVisual = uiState.audioWaveformVisual)
        }

        ActiveAttachmentsPill(
            uiState = uiState,
            onClear = { viewModel.clearAttachments() }
        )

        // --- 4. ACTION CONTROLS & COMMAND PROMPT ---
        TerminalCommandBar(
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
private fun TelemetryHeader(uiState: TerminalUiState) {
    val metrics = uiState.metrics
    val stateText = when (uiState.engineState) {
        EngineState.UNINITIALIZED -> "IDLE"
        EngineState.LOADING -> "INIT"
        EngineState.READY -> "READY"
        EngineState.GENERATING -> "BUSY"
        EngineState.ERROR -> "FAULT"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface)
            .border(width = 0.5.dp, color = TerminalBorder)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VISPER // CORE [${metrics.activeEngineName}]",
                style = TerminalHeaderStyle.copy(color = TerminalGreen)
            )
            Text(
                text = "STATE: $stateText",
                style = TerminalHeaderStyle.copy(
                    color = if (uiState.engineState == EngineState.GENERATING) TerminalAmber else TerminalCyan
                )
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "RAM: ${metrics.memoryUsageMb}MB / ${metrics.maxMemoryMb}MB",
                style = TerminalHeaderStyle
            )
            Text(
                text = "SPEED: %.1f tok/s".format(metrics.tokensPerSecond),
                style = TerminalHeaderStyle.copy(color = TerminalAmber)
            )
            Text(
                text = "CTX: ${metrics.contextTokensUsed}/${metrics.contextTokensMax}",
                style = TerminalHeaderStyle
            )
        }
    }
}

@Composable
private fun TerminalMessageItem(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Message prefix tag
        val (prefix, color) = when (message.sender) {
            MessageSender.SYSTEM -> "sys #" to TerminalAmber
            MessageSender.USER -> "user@visper:~$ " to TerminalGreen
            MessageSender.AI -> "visper> " to TerminalCyan
            MessageSender.ERROR -> "error! " to TerminalRed
        }

        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = prefix,
                style = TerminalMonoStyle.copy(
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            )

            Text(
                text = message.text + if (message.isStreaming) " █" else "",
                style = TerminalMonoStyle.copy(
                    color = if (message.sender == MessageSender.USER) TerminalWhite else TerminalGreen
                ),
                modifier = Modifier.weight(1f)
            )
        }

        // Render visual ASCII preview for images
        if (message.attachment is Attachment.Image && message.attachment.asciiPreview != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A))
                    .border(0.5.dp, TerminalBorder)
                    .padding(6.dp)
            ) {
                Text(
                    text = message.attachment.asciiPreview,
                    style = TerminalMonoStyle.copy(
                        color = TerminalGreen.copy(alpha = 0.85f),
                        fontSize = 8.sp,
                        lineHeight = 9.sp
                    )
                )
            }
        }

        // Render document snippet preview
        if (message.attachment is Attachment.Document) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "[ATTACHED DOC: ${message.attachment.fileName} | ~${message.attachment.tokenEstimate} tokens]",
                style = TerminalMonoStyle.copy(color = TerminalCyan, fontSize = 11.sp)
            )
        }

        // Show generation metrics if finished
        if (message.sender == MessageSender.AI && message.timeToFirstTokenMs != null && !message.isStreaming) {
            Text(
                text = "── (TTFT: ${message.timeToFirstTokenMs}ms)",
                style = TerminalHeaderStyle.copy(fontSize = 10.sp, color = TerminalDim)
            )
        }
    }
}

@Composable
private fun AudioRecordingBanner(waveformVisual: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A0A0A))
            .border(0.5.dp, TerminalRed)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = "AUDIO STREAM: $waveformVisual",
            style = TerminalMonoStyle.copy(color = TerminalRed)
        )
    }
}

@Composable
private fun ActiveAttachmentsPill(
    uiState: TerminalUiState,
    onClear: () -> Unit
) {
    val hasAttachments = uiState.attachedImageUri != null || uiState.attachedDoc != null
    AnimatedVisibility(visible = hasAttachments) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F1A12))
                .border(0.5.dp, TerminalGreen.copy(alpha = 0.3f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildString {
                    append("BUFFERS: ")
                    uiState.attachedImageBitmap?.let { append("[IMG ${it.width}x${it.height}] ") }
                    uiState.attachedDoc?.let { append("[DOC: ${it.fileName} ~${it.tokenEstimate}tok] ") }
                },
                style = TerminalHeaderStyle.copy(color = TerminalGreen)
            )

            Text(
                text = "[FLUSH]",
                style = TerminalHeaderStyle.copy(color = TerminalRed),
                modifier = Modifier.clickable { onClear() }
            )
        }
    }
}

@Composable
private fun TerminalCommandBar(
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
            .background(TerminalSurface)
            .border(0.5.dp, TerminalBorder)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // Quick Action Chips Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TerminalButton(label = "[DOC]", color = TerminalCyan, onClick = onPickDoc)
            TerminalButton(label = "[IMG]", color = TerminalCyan, onClick = onPickImg)
            TerminalButton(
                label = if (isRecording) "[STOP MIC]" else "[MIC]",
                color = if (isRecording) TerminalRed else TerminalAmber,
                onClick = onToggleMic
            )
            Spacer(modifier = Modifier.weight(1f))
            TerminalButton(
                label = if (engineState == EngineState.GENERATING) "[HALT]" else "[RUN]",
                color = if (engineState == EngineState.GENERATING) TerminalRed else TerminalGreen,
                onClick = onSubmit
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Input Line
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalBlack)
                .border(0.5.dp, TerminalBorder)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "> ",
                style = TerminalMonoStyle.copy(color = TerminalGreen, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            )

            BasicTextField(
                value = inputText,
                onValueChange = onTextChanged,
                modifier = Modifier.weight(1f),
                textStyle = TerminalMonoStyle.copy(color = TerminalWhite),
                cursorBrush = SolidColor(TerminalGreen),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onSubmit() },
                    onDone = { onSubmit() }
                )
            )
        }
    }
}

@Composable
private fun TerminalButton(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .border(0.5.dp, color, RoundedCornerShape(2.dp))
            .background(TerminalBlack)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TerminalHeaderStyle.copy(color = color)
        )
    }
}

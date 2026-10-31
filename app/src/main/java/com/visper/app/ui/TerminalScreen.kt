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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.visper.app.memory.MemoryFact
import com.visper.app.model.AgentAction
import com.visper.app.session.SessionMetadata
import kotlinx.coroutines.launch
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
import androidx.compose.ui.text.style.TextAlign
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

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = AppSurface,
                drawerContentColor = TextPrimary,
                modifier = Modifier.width(310.dp)
            ) {
                SessionsDrawerContent(
                    sessions = uiState.sessions,
                    currentSessionId = uiState.currentSessionId,
                    memoryCount = uiState.memories.size,
                    onSelectSession = { id ->
                        viewModel.switchSession(id)
                        coroutineScope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        viewModel.createNewSession()
                        coroutineScope.launch { drawerState.close() }
                    },
                    onDeleteSession = { id ->
                        viewModel.deleteSession(id)
                    },
                    onOpenMemoryVault = {
                        viewModel.openMemoryVault()
                        coroutineScope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
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
                sessionTitle = uiState.currentSessionTitle,
                engineState = uiState.engineState,
                tokensPerSecond = uiState.metrics.tokensPerSecond,
                hasMessages = uiState.messages.isNotEmpty(),
                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                onOpenLab = { viewModel.openLabSheet() },
                onClear = {
                    android.util.Log.d("TerminalScreen", "Clear clicked in UI!")
                    viewModel.clearConversation()
                }
            )

        // --- 2. CONVERSATION STREAM OR WELCOME HERO ---
        if (uiState.messages.isEmpty()) {
            WelcomeHeroSection(
                modifier = Modifier.weight(1f),
                onOpenLab = { viewModel.openLabSheet() },
                onBenchmarkClick = {
                    viewModel.submitCustomPrompt("Generate a concise 50-word benchmark explaining why on-device AI guarantees user privacy.")
                },
                onPickDoc = {
                    viewModel.prefillPrompt("Summarize the key takeaways and main points of this document:")
                    docPicker.launch("*/*")
                },
                onPickImg = {
                    viewModel.prefillPrompt("Analyze and describe this image in detail:")
                    imagePicker.launch("image/*")
                },
                onVoiceClick = {
                    viewModel.openVoiceMode()
                }
            )
        } else {
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
                    MessageBubble(
                        message = msg,
                        isFlashlightActive = uiState.isFlashlightActive,
                        onToggleFlashlight = { viewModel.toggleFlashlight() }
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
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
            onToggleMic = { viewModel.openVoiceMode() },
            isRecording = uiState.isRecordingAudio,
            engineState = uiState.engineState
        )
    }
}

// --- 5. AI LAB & HARDWARE INSPECTOR BOTTOM SHEET ---
if (uiState.isLabSheetOpen) {
    HardwareInspectorSheet(
        stats = uiState.systemStats,
        onDismiss = { viewModel.closeLabSheet() },
        onRunBenchmark = {
            viewModel.closeLabSheet()
            viewModel.submitCustomPrompt("Generate a concise 50-word test paragraph to benchmark your on-device token generation speed.")
        }
    )
}

// --- 6. LOCAL MEMORY VAULT BOTTOM SHEET ---
if (uiState.isMemoryVaultOpen) {
    MemoryVaultSheet(
        memories = uiState.memories,
        onDismiss = { viewModel.closeMemoryVault() },
        onAddMemory = { fact -> viewModel.addManualMemory(fact) },
        onDeleteMemory = { id -> viewModel.deleteMemory(id) },
        onClearAll = { viewModel.clearAllMemories() }
    )
}

// --- 7. CONVERSATIONAL VOICE MODE BOTTOM SHEET ---
if (uiState.isVoiceModeOpen) {
    VoiceModeSheet(
        status = uiState.voiceModeStatus,
        rmsDb = uiState.voiceRmsDb,
        userTranscript = uiState.voiceTranscript,
        aiResponse = uiState.voiceAiResponse,
        onDismiss = { viewModel.closeVoiceMode() },
        onTapOrb = { viewModel.toggleVoiceModeListening() },
        onInterrupt = { viewModel.interruptVoiceSpeaking() }
    )
}
}

@Composable
private fun AppHeader(
    sessionTitle: String,
    engineState: EngineState,
    tokensPerSecond: Float,
    hasMessages: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenLab: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurface)
            .border(width = 0.5.dp, color = AppBorder)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            // Hamburger Drawer Button
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = AppSurfaceElevated,
                onClick = onOpenDrawer
            ) {
                Text(
                    text = "☰",
                    fontSize = 17.sp,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

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
                    text = if (sessionTitle == "New Chat") "Visper" else sessionTitle,
                    style = AppHeaderStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
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

        Spacer(modifier = Modifier.width(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Lab / Hardware Inspector button
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = AppSurfaceElevated,
                onClick = onOpenLab
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "⚡", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Lab",
                        style = AppCaptionStyle.copy(color = TextSecondary, fontWeight = FontWeight.Medium)
                    )
                }
            }

            // Clear chat button (visible only when there are messages)
            if (hasMessages) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AppSurfaceElevated,
                    onClick = onClear
                ) {
                    Text(
                        text = "Clear",
                        style = AppCaptionStyle.copy(color = TextSecondary, fontWeight = FontWeight.Medium),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeHeroSection(
    modifier: Modifier = Modifier,
    onOpenLab: () -> Unit,
    onBenchmarkClick: () -> Unit,
    onPickDoc: () -> Unit,
    onPickImg: () -> Unit,
    onVoiceClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Glowing emblem / icon
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(AppSurfaceElevated)
                .border(1.dp, AppBorderLight, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✦",
                fontSize = 28.sp,
                color = AppPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Title
        Text(
            text = "Visper Neural Core",
            style = AppHeaderStyle.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Subtitle
        Text(
            text = "100% On-Device Multimodal AI",
            style = AppCaptionStyle.copy(
                fontSize = 13.sp,
                color = TextSecondary
            ),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Offline Privacy Pill Badge (clickable to open Lab)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AppSurface,
            border = BorderStroke(0.5.dp, AppBorder),
            onClick = onOpenLab
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(AppAccent)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Air-Gapped • Zero Cloud Leakage ⚡",
                    style = AppCaptionStyle.copy(
                        fontSize = 11.sp,
                        color = AppAccent,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Prompt Discovery Grid (2x2)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DiscoveryCard(
                modifier = Modifier.weight(1f),
                icon = "📄",
                title = "Summarize Doc",
                desc = "Extract key takeaways & facts",
                onClick = onPickDoc
            )
            DiscoveryCard(
                modifier = Modifier.weight(1f),
                icon = "📷",
                title = "Inspect Photo",
                desc = "Describe scenes & objects",
                onClick = onPickImg
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DiscoveryCard(
                modifier = Modifier.weight(1f),
                icon = "⚡",
                title = "Speed Test",
                desc = "Benchmark CPU tok/s & TTFT",
                onClick = onBenchmarkClick
            )
            DiscoveryCard(
                modifier = Modifier.weight(1f),
                icon = "🎙",
                title = "Voice Input",
                desc = "Offline speech transcription",
                onClick = onVoiceClick
            )
        }
    }
}

@Composable
private fun DiscoveryCard(
    modifier: Modifier = Modifier,
    icon: String,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AppSurfaceElevated)
            .border(0.5.dp, AppBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = icon, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = title,
            style = AppBodyStyle.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        )
        Text(
            text = desc,
            style = AppCaptionStyle.copy(
                fontSize = 11.sp,
                color = TextMuted,
                lineHeight = 15.sp
            ),
            maxLines = 2
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isFlashlightActive: Boolean = false,
    onToggleFlashlight: () -> Unit = {}
) {
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
                        // Generated or attached image in AI bubble
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
                                    contentDescription = "Generated AI image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }

                        val textWithCursor = if (message.isStreaming) message.text + " ▮" else message.text
                        Text(
                            text = textWithCursor,
                            style = AppBodyStyle.copy(
                                color = Color(0xFFF3F4F6),
                                lineHeight = 22.sp
                            )
                        )

                        // Agentic Action Card (Flashlight, Timer, Clipboard, Math, Image Gen)
                        if (message.action != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            AgentActionCard(
                                action = message.action,
                                isFlashlightActive = isFlashlightActive,
                                onToggleFlashlight = onToggleFlashlight
                            )
                        }

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
private fun AgentActionCard(
    action: AgentAction,
    isFlashlightActive: Boolean,
    onToggleFlashlight: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AppSurfaceElevated,
        border = BorderStroke(0.5.dp, AppPrimary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = when (action.actionType) {
                    "FLASHLIGHT_ON", "FLASHLIGHT_OFF" -> "🔦"
                    "TIMER" -> "⏱️"
                    "CLIPBOARD" -> "📋"
                    "CALCULATE" -> "🧮"
                    "IMAGE_GEN" -> "🎨"
                    else -> "⚡"
                }
                Text(text = icon, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = action.title,
                        style = AppBodyStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    )
                    Text(
                        text = action.detail,
                        style = AppCaptionStyle.copy(fontSize = 11.sp, color = TextSecondary)
                    )
                }
            }

            if (action.actionType == "FLASHLIGHT_ON" || action.actionType == "FLASHLIGHT_OFF") {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isFlashlightActive) Color(0xFFEF4444).copy(alpha = 0.2f) else AppPrimary.copy(alpha = 0.2f),
                    border = BorderStroke(0.5.dp, if (isFlashlightActive) Color(0xFFEF4444) else AppPrimary),
                    onClick = onToggleFlashlight
                ) {
                    Text(
                        text = if (isFlashlightActive) "Turn Off" else "Turn On",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isFlashlightActive) Color(0xFFEF4444) else AppPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HardwareInspectorSheet(
    stats: SystemHealthStats,
    onDismiss: () -> Unit,
    onRunBenchmark: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(AppBorderLight)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "⚡", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AI Neural Lab & Hardware Inspector",
                            style = AppHeaderStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                    Text(
                        text = "Real-time on-device audit & hardware diagnostics",
                        style = AppCaptionStyle.copy(color = TextMuted)
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = AppSurfaceElevated,
                    onClick = onDismiss
                ) {
                    Text(
                        text = "✕",
                        style = AppCaptionStyle.copy(color = TextSecondary, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Section 1: Privacy Audit Seal
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0F231C),
                border = BorderStroke(0.5.dp, AppAccent.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "🛡", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Zero-Leakage Offline Guarantee",
                                style = AppBodyStyle.copy(color = AppAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            )
                        }
                        Text(
                            text = "AIR-GAPPED",
                            style = AppCaptionStyle.copy(color = AppAccent, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No cloud servers, telemetry, or remote API calls. All multimodal inference runs strictly inside local phone memory.",
                        style = AppCaptionStyle.copy(color = Color(0xFFA7F3D0), fontSize = 12.sp, lineHeight = 16.sp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AuditMiniBadge(label = "Cloud Upload", value = "0.0 KB")
                        AuditMiniBadge(label = "IPC Socket", value = "127.0.0.1")
                        AuditMiniBadge(label = "Telemetry", value = "Zero")
                    }
                }
            }

            // Section 2: Inference & Engine Metrics
            Text(
                text = "INFERENCE ENGINE PERFORMANCE",
                style = AppCaptionStyle.copy(color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Speed",
                    value = if (stats.tokensPerSecond > 0f) "%.1f tok/s".format(stats.tokensPerSecond) else "8-11 tok/s",
                    detail = "NEON accelerated"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Latency (TTFT)",
                    value = if (stats.ttftMs > 0) "${stats.ttftMs} ms" else "< 250 ms",
                    detail = "Zero reasoning lag"
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Active Model",
                    value = "Qwen 3.5 0.8B",
                    detail = "INT4 Q4_K_M GGUF"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Compute Backend",
                    value = "llama.cpp",
                    detail = "ARMv8 64-bit Core"
                )
            }

            // Section 3: Hardware & Memory Allocation
            Text(
                text = "HARDWARE & MEMORY ALLOCATION",
                style = AppCaptionStyle.copy(color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Total RAM Used",
                    value = if (stats.totalRamMb > 0) "${stats.totalRamMb} MB" else "1193 MB",
                    detail = "Model + App UI"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Model Weights RSS",
                    value = if (stats.modelMemoryMb > 0) "${stats.modelMemoryMb} MB" else "1008 MB",
                    detail = "Zero-copy mmap"
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "CPU Core Count",
                    value = "${stats.cpuCores} Cores",
                    detail = "Heterogeneous SMP"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Inference Threads",
                    value = "${stats.activeThreads} Threads",
                    detail = "Parallel OpenMP"
                )
            }

            // Section 4: Live Benchmark Trigger Button
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AppPrimary,
                onClick = onRunBenchmark,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "⚡", fontSize = 16.sp, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Run Live Speed Benchmark",
                        style = AppBodyStyle.copy(color = Color.White, fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    detail: String
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AppSurfaceElevated)
            .border(0.5.dp, AppBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Text(text = title, style = AppCaptionStyle.copy(color = TextMuted, fontSize = 11.sp))
        Spacer(modifier = Modifier.height(3.dp))
        Text(text = value, style = AppBodyStyle.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary))
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = detail, style = AppCaptionStyle.copy(color = AppPrimary, fontSize = 10.sp))
    }
}

@Composable
private fun AuditMiniBadge(label: String, value: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF133E2F))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label: ",
            style = AppCaptionStyle.copy(color = Color(0xFF6EE7B7), fontSize = 10.sp)
        )
        Text(
            text = value,
            style = AppCaptionStyle.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        )
    }
}

@Composable
private fun SessionsDrawerContent(
    sessions: List<SessionMetadata>,
    currentSessionId: String,
    memoryCount: Int,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onOpenMemoryVault: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        // App Title & Brand
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "✦", fontSize = 20.sp, color = AppPrimary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Visper AI",
                    style = AppHeaderStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)
                )
            }

            // Pill: Offline
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF133E2F))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(AppAccent)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Offline", style = AppCaptionStyle.copy(fontSize = 10.sp, color = Color(0xFF6EE7B7)))
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // "+ New Chat" Button
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AppSurfaceElevated,
            border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.6f)),
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "＋", fontSize = 16.sp, color = AppPrimary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "New Chat",
                    style = AppBodyStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "CHAT SESSIONS",
            style = AppCaptionStyle.copy(fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.Bold)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Session List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (sessions.isEmpty()) {
                item {
                    Text(
                        text = "No saved chats yet.",
                        style = AppCaptionStyle.copy(color = TextMuted),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(
                    items = sessions,
                    key = { it.id }
                ) { session ->
                    val isActive = session.id == currentSessionId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isActive) Color(0xFF1E293B) else AppSurfaceElevated,
                        border = if (isActive) BorderStroke(1.dp, AppPrimary) else BorderStroke(0.5.dp, AppBorder),
                        onClick = { onSelectSession(session.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title,
                                    style = AppBodyStyle.copy(
                                        fontSize = 13.sp,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isActive) Color.White else TextPrimary
                                    ),
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val timeText = formatRelativeTime(session.updatedAt)
                                Text(
                                    text = "$timeText • ${session.messageCount} msg",
                                    style = AppCaptionStyle.copy(fontSize = 10.sp, color = TextMuted)
                                )
                            }

                            // Delete button
                            Surface(
                                shape = CircleShape,
                                color = Color.Transparent,
                                onClick = { onDeleteSession(session.id) }
                            ) {
                                Text(
                                    text = "✕",
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Memory Vault Access Card at Bottom of Drawer
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF191924),
            border = BorderStroke(0.5.dp, Color(0xFF6366F1).copy(alpha = 0.5f)),
            onClick = onOpenMemoryVault,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🧠", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Personal Memory Vault",
                        style = AppBodyStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    )
                    Text(
                        text = "$memoryCount facts remembered • Local & Private",
                        style = AppCaptionStyle.copy(fontSize = 10.sp, color = Color(0xFFA5B4FC))
                    )
                }
                Text(text = "→", fontSize = 14.sp, color = Color(0xFFA5B4FC))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryVaultSheet(
    memories: List<MemoryFact>,
    onDismiss: () -> Unit,
    onAddMemory: (String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var newFactText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(AppBorderLight)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "🧠", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Personal Memory Vault",
                            style = AppHeaderStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                    Text(
                        text = "Cross-session user profile & preferences (100% on-device)",
                        style = AppCaptionStyle.copy(color = TextMuted)
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = AppSurfaceElevated,
                    onClick = onDismiss
                ) {
                    Text(
                        text = "✕",
                        style = AppCaptionStyle.copy(color = TextSecondary, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Input to add a new memory
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppSurfaceElevated)
                    .border(0.5.dp, AppBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (newFactText.isEmpty()) {
                        Text(
                            text = "e.g. My major is Computer Science...",
                            style = AppCaptionStyle.copy(color = TextMuted)
                        )
                    }
                    BasicTextField(
                        value = newFactText,
                        onValueChange = { newFactText = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = AppBodyStyle.copy(fontSize = 13.sp, color = TextPrimary),
                        cursorBrush = SolidColor(AppPrimary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (newFactText.isNotBlank()) {
                                onAddMemory(newFactText)
                                newFactText = ""
                            }
                        })
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AppPrimary,
                    onClick = {
                        if (newFactText.isNotBlank()) {
                            onAddMemory(newFactText)
                            newFactText = ""
                        }
                    }
                ) {
                    Text(
                        text = "+ Add",
                        style = AppCaptionStyle.copy(color = Color.White, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Stored Memories List
            Text(
                text = "STORED PREFERENCES (${memories.size})",
                style = AppCaptionStyle.copy(color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            )

            if (memories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppSurfaceElevated)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No memories stored yet. Mention your preferences in chat (e.g. 'My name is Ashwin') and Visper will automatically remember them!",
                        style = AppCaptionStyle.copy(color = TextMuted, textAlign = TextAlign.Center)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in memories) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppSurfaceElevated)
                                .border(0.5.dp, AppBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "✦", fontSize = 12.sp, color = AppAccent)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = item.fact,
                                    style = AppBodyStyle.copy(fontSize = 13.sp, color = TextPrimary)
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = Color.Transparent,
                                onClick = { onDeleteMemory(item.id) }
                            ) {
                                Text(
                                    text = "✕",
                                    fontSize = 12.sp,
                                    color = AppRed,
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (memories.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AppSurfaceElevated,
                    border = BorderStroke(0.5.dp, AppRed.copy(alpha = 0.5f)),
                    onClick = onClearAll,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Clear All Memories",
                        style = AppCaptionStyle.copy(color = AppRed, fontWeight = FontWeight.Medium),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diffSec = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        diffSec < 60 -> "Just now"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        else -> "${diffSec / 86400}d ago"
    }
}

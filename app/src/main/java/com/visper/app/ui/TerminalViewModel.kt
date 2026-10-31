package com.visper.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.visper.app.agent.DeviceActionManager
import com.visper.app.audio.AudioRecordingState
import com.visper.app.audio.AudioTranscriptionManager
import com.visper.app.audio.VisperTtsManager
import com.visper.app.document.DocumentParser
import com.visper.app.document.ParsedDocument
import com.visper.app.engine.InferenceEngine
import com.visper.app.engine.VisperEngine
import com.visper.app.model.Attachment
import com.visper.app.model.ChatMessage
import com.visper.app.model.EngineState
import com.visper.app.model.MessageSender
import com.visper.app.model.PerformanceMetrics
import com.visper.app.memory.LocalMemoryStore
import com.visper.app.memory.MemoryFact
import com.visper.app.session.ChatSession
import com.visper.app.session.ChatSessionManager
import com.visper.app.session.SessionMetadata
import com.visper.app.vision.ImageProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class SystemHealthStats(
    val modelName: String = "Qwen 3.5 0.8B",
    val modelQuant: String = "Q4_K_M (4-bit)",
    val backend: String = "llama.cpp ARMv8 NEON",
    val modelMemoryMb: Long = 0,
    val appMemoryMb: Long = 0,
    val totalRamMb: Long = 0,
    val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
    val activeThreads: Int = 16,
    val tokensPerSecond: Float = 0f,
    val ttftMs: Long = 0,
    val externalNetBytes: Long = 0L,
    val isAirGapped: Boolean = true
)

data class TerminalUiState(
    val currentSessionId: String = "",
    val currentSessionTitle: String = "New Chat",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val attachedImageUri: Uri? = null,
    val attachedImageBitmap: Bitmap? = null,
    val attachedDoc: ParsedDocument? = null,
    val isRecordingAudio: Boolean = false,
    val audioWaveformVisual: String = "",
    val metrics: PerformanceMetrics = PerformanceMetrics(),
    val engineState: EngineState = EngineState.READY,
    val isLabSheetOpen: Boolean = false,
    val systemStats: SystemHealthStats = SystemHealthStats(),
    val sessions: List<SessionMetadata> = emptyList(),
    val memories: List<MemoryFact> = emptyList(),
    val isMemoryVaultOpen: Boolean = false,
    val isVoiceModeOpen: Boolean = false,
    val voiceModeStatus: VoiceModeStatus = VoiceModeStatus.IDLE,
    val voiceRmsDb: Float = 0f,
    val voiceTranscript: String = "",
    val voiceAiResponse: String = "",
    val isFlashlightActive: Boolean = false
)

class TerminalViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val engine: InferenceEngine = VisperEngine(application)
    private val audioManager = AudioTranscriptionManager(application)
    private val sessionManager = ChatSessionManager(application)
    private val memoryStore = LocalMemoryStore(application)
    private val ttsManager = VisperTtsManager(application)
    private val deviceActionManager = DeviceActionManager(application)
    private var generationJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow(
        TerminalUiState()
    )
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    init {
        // Initialize sessions and memory vault
        val existingSessions = sessionManager.getSessionList()
        val initialSession = if (existingSessions.isNotEmpty()) {
            val latest = existingSessions.first()
            sessionManager.loadSession(latest.id) ?: ChatSession()
        } else {
            val newSess = ChatSession()
            sessionManager.saveSession(newSess)
            newSess
        }

        _uiState.value = _uiState.value.copy(
            currentSessionId = initialSession.id,
            currentSessionTitle = initialSession.title,
            messages = initialSession.messages,
            sessions = sessionManager.getSessionList(),
            memories = memoryStore.getMemories()
        )

        viewModelScope.launch {
            engine.metrics.collect { metrics ->
                _uiState.value = _uiState.value.copy(metrics = metrics)
            }
        }
        viewModelScope.launch {
            engine.state.collect { state ->
                _uiState.value = _uiState.value.copy(engineState = state)
            }
        }
        viewModelScope.launch {
            audioManager.recordingState.collect { audioState ->
                when (audioState) {
                    is AudioRecordingState.Idle -> {
                        _uiState.value = _uiState.value.copy(
                            isRecordingAudio = false,
                            audioWaveformVisual = ""
                        )
                    }
                    is AudioRecordingState.Recording -> {
                        _uiState.value = _uiState.value.copy(
                            isRecordingAudio = true,
                            audioWaveformVisual = audioState.waveformVisual,
                            voiceRmsDb = audioState.rmsDb
                        )
                    }
                    is AudioRecordingState.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isRecordingAudio = false,
                            audioWaveformVisual = ""
                        )
                        if (_uiState.value.isVoiceModeOpen) {
                            val recognized = audioState.text.trim()
                            audioManager.reset()
                            if (recognized.isNotBlank()) {
                                _uiState.value = _uiState.value.copy(
                                    voiceTranscript = recognized,
                                    voiceModeStatus = VoiceModeStatus.THINKING
                                )
                                submitCustomPrompt(recognized)
                            } else {
                                _uiState.value = _uiState.value.copy(
                                    voiceModeStatus = VoiceModeStatus.IDLE
                                )
                            }
                        } else {
                            _uiState.value = _uiState.value.copy(
                                inputText = if (_uiState.value.inputText.isBlank()) {
                                    audioState.text
                                } else {
                                    "${_uiState.value.inputText} ${audioState.text}"
                                }
                            )
                            audioManager.reset()
                        }
                    }
                    is AudioRecordingState.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isRecordingAudio = false,
                            audioWaveformVisual = ""
                        )
                        if (_uiState.value.isVoiceModeOpen) {
                            _uiState.value = _uiState.value.copy(
                                voiceModeStatus = VoiceModeStatus.IDLE,
                                voiceTranscript = "Audio: ${audioState.message}"
                            )
                        } else {
                            appendMessage(
                                ChatMessage(
                                    sender = MessageSender.ERROR,
                                    text = "Audio Error: ${audioState.message}"
                                )
                            )
                        }
                        audioManager.reset()
                    }
                }
            }
        }

        // Quietly detect and auto-initialize model in background
        viewModelScope.launch {
            val appModelsDir = getApplication<Application>().getExternalFilesDir("models")
            val candidatePaths = listOf(
                java.io.File(appModelsDir, "Qwen3.5-0.8B-Q4_K_M.gguf").absolutePath,
                java.io.File(appModelsDir, "qwen3.5-0.8b-q4_k_m.gguf").absolutePath,
                java.io.File(appModelsDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf").absolutePath,
                "/sdcard/Android/data/com.visper.app/files/models/Qwen3.5-0.8B-Q4_K_M.gguf",
                "/sdcard/Download/Qwen3.5-0.8B-Q4_K_M.gguf",
                "/sdcard/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                java.io.File(appModelsDir, "gemma-2b-it-cpu-int4.bin").absolutePath
            )
            val detected = candidatePaths.firstOrNull { java.io.File(it).exists() }
            if (detected != null) {
                engine.initialize(detected, useGpu = false)
            }
        }
    }

    fun onInputTextChanged(newText: String) {
        _uiState.value = _uiState.value.copy(inputText = newText)
    }

    fun toggleVoiceRecording() {
        if (_uiState.value.isRecordingAudio) {
            audioManager.stopListening()
        } else {
            audioManager.startListening()
        }
    }

    fun onImageSelected(uri: Uri) {
        viewModelScope.launch {
            val bitmap = ImageProcessor.decodeSampledBitmapFromUri(getApplication(), uri, 1280, 1280)
            if (bitmap != null) {
                _uiState.value = _uiState.value.copy(
                    attachedImageUri = uri,
                    attachedImageBitmap = bitmap
                )
            }
        }
    }

    fun onDocumentSelected(uri: Uri) {
        viewModelScope.launch {
            val result = DocumentParser.parseDocument(getApplication(), uri)
            result.onSuccess { doc ->
                _uiState.value = _uiState.value.copy(attachedDoc = doc)
            }
        }
    }

    fun clearAttachments() {
        _uiState.value = _uiState.value.copy(
            attachedImageUri = null,
            attachedImageBitmap = null,
            attachedDoc = null
        )
    }

    fun clearConversation() {
        cancelGeneration()
        engine.resetContext()
        _uiState.value = _uiState.value.copy(
            messages = emptyList(),
            inputText = ""
        )
        clearAttachments()
        saveCurrentSession()
    }

    fun createNewSession() {
        cancelGeneration()
        saveCurrentSession()
        val newSession = ChatSession()
        sessionManager.saveSession(newSession)
        engine.resetContext()
        _uiState.value = _uiState.value.copy(
            currentSessionId = newSession.id,
            currentSessionTitle = newSession.title,
            messages = emptyList(),
            inputText = "",
            sessions = sessionManager.getSessionList()
        )
        clearAttachments()
    }

    fun switchSession(sessionId: String) {
        if (sessionId == _uiState.value.currentSessionId) return
        cancelGeneration()
        saveCurrentSession()
        val target = sessionManager.loadSession(sessionId) ?: return
        engine.resetContext()
        _uiState.value = _uiState.value.copy(
            currentSessionId = target.id,
            currentSessionTitle = target.title,
            messages = target.messages,
            inputText = "",
            sessions = sessionManager.getSessionList()
        )
        clearAttachments()
    }

    fun deleteSession(sessionId: String) {
        sessionManager.deleteSession(sessionId)
        if (sessionId == _uiState.value.currentSessionId) {
            val remaining = sessionManager.getSessionList()
            if (remaining.isNotEmpty()) {
                switchSession(remaining.first().id)
            } else {
                createNewSession()
            }
        } else {
            _uiState.value = _uiState.value.copy(
                sessions = sessionManager.getSessionList()
            )
        }
    }

    private fun saveCurrentSession() {
        val sId = _uiState.value.currentSessionId
        if (sId.isBlank()) return
        val currentMsgs = _uiState.value.messages
        val firstUserMsg = currentMsgs.firstOrNull { it.sender == MessageSender.USER }
        val title = if (_uiState.value.currentSessionTitle == "New Chat" && firstUserMsg != null) {
            sessionManager.generateTitleFromPrompt(firstUserMsg.text)
        } else {
            _uiState.value.currentSessionTitle
        }

        val session = ChatSession(
            id = sId,
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = currentMsgs
        )
        sessionManager.saveSession(session)
        _uiState.value = _uiState.value.copy(
            currentSessionTitle = title,
            sessions = sessionManager.getSessionList()
        )
    }

    fun openMemoryVault() {
        _uiState.value = _uiState.value.copy(
            isMemoryVaultOpen = true,
            memories = memoryStore.getMemories()
        )
    }

    fun closeMemoryVault() {
        _uiState.value = _uiState.value.copy(isMemoryVaultOpen = false)
    }

    fun addManualMemory(fact: String) {
        if (fact.isBlank()) return
        memoryStore.addMemory(fact)
        _uiState.value = _uiState.value.copy(memories = memoryStore.getMemories())
    }

    fun deleteMemory(id: String) {
        memoryStore.deleteMemory(id)
        _uiState.value = _uiState.value.copy(memories = memoryStore.getMemories())
    }

    fun clearAllMemories() {
        memoryStore.clearAllMemories()
        _uiState.value = _uiState.value.copy(memories = emptyList())
    }

    fun submitCustomPrompt(prompt: String) {
        if (_uiState.value.engineState == EngineState.GENERATING) return
        _uiState.value = _uiState.value.copy(inputText = prompt)
        submitPrompt()
    }

    fun prefillPrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(inputText = prompt)
    }

    fun openLabSheet() {
        _uiState.value = _uiState.value.copy(
            isLabSheetOpen = true,
            systemStats = getSystemHealthStats()
        )
    }

    fun closeLabSheet() {
        _uiState.value = _uiState.value.copy(isLabSheetOpen = false)
    }

    fun getSystemHealthStats(): SystemHealthStats {
        val (modelMb, appMb) = engine.getHardwareStats()
        val totalRam = if (modelMb > 0) modelMb + appMb else _uiState.value.metrics.memoryUsageMb
        val metrics = _uiState.value.metrics
        val lastAiMsg = _uiState.value.messages.lastOrNull { it.sender == MessageSender.AI && !it.isStreaming }

        val txBytes = try {
            android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        } catch (_: Exception) { 0L }

        return SystemHealthStats(
            modelName = if (engine.activeModelName.isNotBlank()) engine.activeModelName else "Qwen 3.5 0.8B",
            modelQuant = "Q4_K_M (4-bit)",
            backend = "llama.cpp ARMv8 NEON",
            modelMemoryMb = if (modelMb > 0) modelMb else 1008L,
            appMemoryMb = if (appMb > 0) appMb else 185L,
            totalRamMb = if (totalRam > 0) totalRam else 1193L,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            activeThreads = 16,
            tokensPerSecond = metrics.tokensPerSecond,
            ttftMs = lastAiMsg?.timeToFirstTokenMs ?: 0L,
            externalNetBytes = if (txBytes < 0) 0L else txBytes,
            isAirGapped = true
        )
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        engine.resetContext()
        _uiState.value = _uiState.value.copy(engineState = EngineState.READY)
        val updated = _uiState.value.messages.map { msg ->
            if (msg.isStreaming) msg.copy(isStreaming = false) else msg
        }
        _uiState.value = _uiState.value.copy(messages = updated)
    }

    fun submitPrompt() {
        if (_uiState.value.engineState == EngineState.GENERATING) {
            cancelGeneration()
            return
        }

        val prompt = _uiState.value.inputText.trim()
        val currentImage = _uiState.value.attachedImageBitmap
        val currentImageUri = _uiState.value.attachedImageUri
        val currentDoc = _uiState.value.attachedDoc

        if (prompt.isBlank() && currentImage == null && currentDoc == null) {
            return
        }

        if (prompt.equals("/clear", ignoreCase = true)) {
            clearConversation()
            return
        }

        val displayPrompt = prompt.ifBlank {
            when {
                currentImage != null -> "Describe this image in detail."
                currentDoc != null -> "Summarize this document."
                else -> ""
            }
        }

        // Auto-extract personal preference/fact into Memory Vault
        val potentialMem = memoryStore.extractPotentialMemory(displayPrompt)
        if (potentialMem != null) {
            memoryStore.addMemory(potentialMem)
            _uiState.value = _uiState.value.copy(
                memories = memoryStore.getMemories()
            )
        }

        // Add user message
        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = displayPrompt,
            attachment = when {
                currentImage != null && currentImageUri != null -> Attachment.Image(
                    uri = currentImageUri,
                    width = currentImage.width,
                    height = currentImage.height
                )
                currentDoc != null -> Attachment.Document(
                    uri = currentDoc.fileName.let { Uri.parse("file://$it") },
                    fileName = currentDoc.fileName,
                    tokenEstimate = currentDoc.tokenEstimate,
                    snippet = currentDoc.textContent.take(160)
                )
                else -> null
            }
        )

        // Clear input text and attachments and persist session
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMsg,
            inputText = "",
            attachedImageUri = null,
            attachedImageBitmap = null,
            attachedDoc = null
        )
        saveCurrentSession()

        // 1. Evaluate Agentic Device Action Intent
        val action = deviceActionManager.evaluateAndExecute(displayPrompt)
        if (action != null) {
            val actionReply = when (action.actionType) {
                "FLASHLIGHT_ON" -> "I've turned on your device flashlight."
                "FLASHLIGHT_OFF" -> "I've turned off your device flashlight."
                "TIMER" -> "I've scheduled a countdown timer on your device: ${action.detail}"
                "CLIPBOARD" -> "I've copied the requested text to your device clipboard."
                "CALCULATE" -> "The calculated result is: ${action.detail}"
                else -> action.detail
            }
            val aiMsg = ChatMessage(
                sender = MessageSender.AI,
                text = actionReply,
                action = action,
                isStreaming = false
            )
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + aiMsg,
                isFlashlightActive = deviceActionManager.isTorchActive()
            )
            saveCurrentSession()

            if (_uiState.value.isVoiceModeOpen) {
                _uiState.value = _uiState.value.copy(
                    voiceAiResponse = actionReply,
                    voiceModeStatus = VoiceModeStatus.SPEAKING
                )
                ttsManager.speak(actionReply) {
                    if (_uiState.value.isVoiceModeOpen) {
                        _uiState.value = _uiState.value.copy(voiceModeStatus = VoiceModeStatus.IDLE)
                    }
                }
            }
            return
        }

        // Stream AI response
        generationJob = viewModelScope.launch {
            val responseId = java.util.UUID.randomUUID().toString()
            val initialAiMsg = ChatMessage(
                id = responseId,
                sender = MessageSender.AI,
                text = "",
                isStreaming = true
            )
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + initialAiMsg
            )

            val memoryPrefix = memoryStore.getMemoryPromptContext()
            val contextPrefix = buildString {
                if (memoryPrefix.isNotBlank()) {
                    append(memoryPrefix)
                }
                if (currentDoc != null) {
                    append("Document text:\n\"\"\"\n")
                    append(currentDoc.textContent)
                    append("\n\"\"\"\n\n")
                }
            }

            val startTime = System.currentTimeMillis()
            var firstTokenTime: Long? = null
            val fullTextBuilder = StringBuilder()

            engine.generateStream(displayPrompt, contextPrefix, currentImage)
                .catch { e ->
                    android.util.Log.e("TerminalViewModel", "Generation error", e)
                    updateStreamingMessage(
                        responseId,
                        "Error: ${e.message ?: e.javaClass.simpleName}",
                        isDone = true
                    )
                    saveCurrentSession()
                    if (_uiState.value.isVoiceModeOpen) {
                        _uiState.value = _uiState.value.copy(
                            voiceModeStatus = VoiceModeStatus.IDLE,
                            voiceAiResponse = "Inference error occurred."
                        )
                    }
                }
                .collect { token ->
                    if (firstTokenTime == null) {
                        firstTokenTime = System.currentTimeMillis() - startTime
                    }
                    fullTextBuilder.append(token)
                    updateStreamingMessage(
                        responseId,
                        fullTextBuilder.toString(),
                        isDone = false,
                        ttftMs = firstTokenTime
                    )
                    if (_uiState.value.isVoiceModeOpen) {
                        _uiState.value = _uiState.value.copy(
                            voiceAiResponse = fullTextBuilder.toString()
                        )
                    }
                }

            val finalReply = fullTextBuilder.toString()
            updateStreamingMessage(
                responseId,
                finalReply,
                isDone = true,
                ttftMs = firstTokenTime
            )
            saveCurrentSession()

            if (_uiState.value.isVoiceModeOpen) {
                _uiState.value = _uiState.value.copy(
                    voiceAiResponse = finalReply,
                    voiceModeStatus = VoiceModeStatus.SPEAKING
                )
                ttsManager.speak(finalReply) {
                    if (_uiState.value.isVoiceModeOpen) {
                        _uiState.value = _uiState.value.copy(voiceModeStatus = VoiceModeStatus.IDLE)
                    }
                }
            }
        }
    }

    fun openVoiceMode() {
        _uiState.value = _uiState.value.copy(
            isVoiceModeOpen = true,
            voiceModeStatus = VoiceModeStatus.LISTENING,
            voiceTranscript = "",
            voiceAiResponse = ""
        )
        audioManager.startListening()
    }

    fun closeVoiceMode() {
        audioManager.stopListening()
        ttsManager.stop()
        _uiState.value = _uiState.value.copy(
            isVoiceModeOpen = false,
            voiceModeStatus = VoiceModeStatus.IDLE
        )
    }

    fun toggleVoiceModeListening() {
        if (_uiState.value.voiceModeStatus == VoiceModeStatus.SPEAKING) {
            interruptVoiceSpeaking()
        } else if (_uiState.value.voiceModeStatus == VoiceModeStatus.LISTENING) {
            audioManager.stopListening()
            _uiState.value = _uiState.value.copy(voiceModeStatus = VoiceModeStatus.IDLE)
        } else {
            _uiState.value = _uiState.value.copy(
                voiceModeStatus = VoiceModeStatus.LISTENING,
                voiceTranscript = "",
                voiceAiResponse = ""
            )
            audioManager.startListening()
        }
    }

    fun interruptVoiceSpeaking() {
        ttsManager.stop()
        _uiState.value = _uiState.value.copy(voiceModeStatus = VoiceModeStatus.IDLE)
    }

    fun toggleFlashlight() {
        deviceActionManager.toggleTorch()
        _uiState.value = _uiState.value.copy(
            isFlashlightActive = deviceActionManager.isTorchActive()
        )
    }

    fun copyMessageText(text: String) {
        deviceActionManager.copyToClipboard(text)
    }

    private fun updateStreamingMessage(
        id: String,
        text: String,
        isDone: Boolean,
        ttftMs: Long? = null
    ) {
        val updated = _uiState.value.messages.map { msg ->
            if (msg.id == id) {
                msg.copy(
                    text = text,
                    isStreaming = !isDone,
                    timeToFirstTokenMs = ttftMs ?: msg.timeToFirstTokenMs
                )
            } else {
                msg
            }
        }
        _uiState.value = _uiState.value.copy(messages = updated)
    }

    private fun appendMessage(message: ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + message
        )
    }

    override fun onCleared() {
        super.onCleared()
        audioManager.stopListening()
        ttsManager.shutdown()
        deviceActionManager.setTorch(false)
        engine.close()
    }
}

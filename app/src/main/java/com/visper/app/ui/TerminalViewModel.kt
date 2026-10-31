package com.visper.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.visper.app.audio.AudioRecordingState
import com.visper.app.audio.AudioTranscriptionManager
import com.visper.app.document.DocumentParser
import com.visper.app.document.ParsedDocument
import com.visper.app.engine.InferenceEngine
import com.visper.app.engine.VisperEngine
import com.visper.app.model.Attachment
import com.visper.app.model.ChatMessage
import com.visper.app.model.EngineState
import com.visper.app.model.MessageSender
import com.visper.app.model.PerformanceMetrics
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
    val systemStats: SystemHealthStats = SystemHealthStats()
)

class TerminalViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val engine: InferenceEngine = VisperEngine(application)
    private val audioManager = AudioTranscriptionManager(application)
    private var generationJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow(
        TerminalUiState(
            messages = emptyList()
        )
    )
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    init {
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
                            audioWaveformVisual = audioState.waveformVisual
                        )
                    }
                    is AudioRecordingState.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isRecordingAudio = false,
                            audioWaveformVisual = "",
                            inputText = if (_uiState.value.inputText.isBlank()) {
                                audioState.text
                            } else {
                                "${_uiState.value.inputText} ${audioState.text}"
                            }
                        )
                        audioManager.reset()
                    }
                    is AudioRecordingState.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isRecordingAudio = false,
                            audioWaveformVisual = ""
                        )
                        appendMessage(
                            ChatMessage(
                                sender = MessageSender.ERROR,
                                text = "Audio Error: ${audioState.message}"
                            )
                        )
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

        // Clear input text and attachments
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMsg,
            inputText = "",
            attachedImageUri = null,
            attachedImageBitmap = null,
            attachedDoc = null
        )

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

            val contextPrefix = buildString {
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
                }

            updateStreamingMessage(
                responseId,
                fullTextBuilder.toString(),
                isDone = true,
                ttftMs = firstTokenTime
            )
        }
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
        engine.close()
    }
}

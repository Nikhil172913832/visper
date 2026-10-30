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

data class TerminalUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val attachedImageUri: Uri? = null,
    val attachedImageBitmap: Bitmap? = null,
    val attachedImageAscii: String? = null,
    val attachedDoc: ParsedDocument? = null,
    val isRecordingAudio: Boolean = false,
    val audioWaveformVisual: String = "",
    val metrics: PerformanceMetrics = PerformanceMetrics(),
    val engineState: EngineState = EngineState.READY
)

class TerminalViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val engine: InferenceEngine = VisperEngine(application)
    private val audioManager = AudioTranscriptionManager(application)
    private var generationJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow(
        TerminalUiState(
            messages = listOf(
                ChatMessage(
                    sender = MessageSender.SYSTEM,
                    text = """
                    VISPER ON-DEVICE AI TERMINAL [v1.0.0]
                    Kernel: Linux aarch64 | Hardware Accel: Vulkan / NPU
                    Modality: Text | Vision | Audio | Document
                    Type '/help' for commands. Tap [DOC], [IMG], or [MIC] below.
                    """.trimIndent()
                )
            )
        )
    )
    val uiState: StateFlow<TerminalUiState> = _uiState.asStateFlow()

    init {
        // Collect engine metrics & state
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
        // Collect audio recording state
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

        // Check for model weights on device storage and auto-bind on launch
        viewModelScope.launch {
            val appModelsDir = getApplication<Application>().getExternalFilesDir("models")
            val candidatePaths = listOf(
                java.io.File(appModelsDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf").absolutePath,
                java.io.File(appModelsDir, "gemma-2b-it-cpu-int4.bin").absolutePath,
                "/sdcard/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                java.io.File(appModelsDir, "gemma-2b-it-gpu-int4.bin").absolutePath
            )
            val detected = candidatePaths.firstOrNull { java.io.File(it).exists() }
            if (detected != null) {
                val f = java.io.File(detected)
                val isGguf = f.name.endsWith(".gguf", ignoreCase = true)
                appendMessage(
                    ChatMessage(
                        sender = MessageSender.SYSTEM,
                        text = "[STORAGE] Model detected: '${f.name}' (${f.length() / (1024 * 1024)} MB).\nInitializing on-device neural engine..."
                    )
                )
                val res = engine.initialize(detected, useGpu = false)
                if (res.isSuccess) {
                    appendMessage(
                        ChatMessage(
                            sender = MessageSender.SYSTEM,
                            text = "[READY] Neural model active (${if (isGguf) "llama.cpp ARM NEON" else "CPU"}). Real neural inference ready!"
                        )
                    )
                } else {
                    appendMessage(
                        ChatMessage(
                            sender = MessageSender.ERROR,
                            text = "[INIT FAILED] ${res.exceptionOrNull()?.message}"
                        )
                    )
                }
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
            val bitmap = ImageProcessor.decodeSampledBitmapFromUri(getApplication(), uri)
            val ascii = bitmap?.let { ImageProcessor.toAsciiArt(it, targetCols = 38) }
            _uiState.value = _uiState.value.copy(
                attachedImageUri = uri,
                attachedImageBitmap = bitmap,
                attachedImageAscii = ascii
            )
            appendMessage(
                ChatMessage(
                    sender = MessageSender.SYSTEM,
                    text = "[IMG ATTACHED] ${bitmap?.width}x${bitmap?.height} px -> ASCII vision tensor generated.",
                    attachment = bitmap?.let {
                        Attachment.Image(uri, it.width, it.height, ascii)
                    }
                )
            )
        }
    }

    fun onDocumentSelected(uri: Uri) {
        viewModelScope.launch {
            val result = DocumentParser.parseDocument(getApplication(), uri)
            result.onSuccess { doc ->
                _uiState.value = _uiState.value.copy(attachedDoc = doc)
                appendMessage(
                    ChatMessage(
                        sender = MessageSender.SYSTEM,
                        text = "[DOC ATTACHED] '${doc.fileName}' (~${doc.tokenEstimate} tokens)${if (doc.isTruncated) " [Budget Truncated]" else ""}",
                        attachment = Attachment.Document(
                            uri = uri,
                            fileName = doc.fileName,
                            tokenEstimate = doc.tokenEstimate,
                            snippet = doc.textContent.take(160)
                        )
                    )
                )
            }.onFailure { err ->
                appendMessage(
                    ChatMessage(
                        sender = MessageSender.ERROR,
                        text = "Failed to load document: ${err.message}"
                    )
                )
            }
        }
    }

    fun clearAttachments() {
        _uiState.value = _uiState.value.copy(
            attachedImageUri = null,
            attachedImageBitmap = null,
            attachedImageAscii = null,
            attachedDoc = null
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
        val currentImageAscii = _uiState.value.attachedImageAscii
        val currentDoc = _uiState.value.attachedDoc

        if (prompt.isBlank() && currentImage == null && currentDoc == null) {
            return
        }

        // Handle terminal commands
        if (prompt.equals("/clear", ignoreCase = true)) {
            engine.resetContext()
            _uiState.value = _uiState.value.copy(
                messages = emptyList(),
                inputText = ""
            )
            clearAttachments()
            return
        }

        if (prompt.startsWith("/model", ignoreCase = true)) {
            val appModelsDir = getApplication<Application>().getExternalFilesDir("models")
            val targetPath = prompt.removePrefix("/model").trim().ifEmpty {
                val candidatePaths = listOf(
                    java.io.File(appModelsDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf").absolutePath,
                    java.io.File(appModelsDir, "gemma-2b-it-cpu-int4.bin").absolutePath,
                    "/sdcard/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                    java.io.File(appModelsDir, "gemma-2b-it-gpu-int4.bin").absolutePath
                )
                candidatePaths.firstOrNull { java.io.File(it).exists() } ?: ""
            }
            if (targetPath.isEmpty()) {
                appendMessage(
                    ChatMessage(
                        sender = MessageSender.ERROR,
                        text = "Usage: /model <path_to_gguf_or_bin>"
                    )
                )
            } else {
                viewModelScope.launch {
                    val f = java.io.File(targetPath)
                    val isGguf = f.name.endsWith(".gguf", ignoreCase = true)
                    appendMessage(
                        ChatMessage(
                            sender = MessageSender.SYSTEM,
                            text = "Binding '${f.name}' via ${if (isGguf) "llama.cpp NEON engine" else "MediaPipe"}..."
                        )
                    )
                    try {
                        val res = engine.initialize(targetPath, useGpu = false)
                        if (res.isSuccess) {
                            appendMessage(
                                ChatMessage(
                                    sender = MessageSender.SYSTEM,
                                    text = "[SUCCESS] Model '${f.name}' bound into memory! Real token generation ready."
                                )
                            )
                        } else {
                            appendMessage(
                                ChatMessage(
                                    sender = MessageSender.ERROR,
                                    text = "[ERROR] Engine failed to load: ${res.exceptionOrNull()?.message}"
                                )
                            )
                        }
                    } catch (t: Throwable) {
                        appendMessage(
                            ChatMessage(
                                sender = MessageSender.ERROR,
                                text = "Engine load error: ${t.message}"
                            )
                        )
                    }
                }
            }
            _uiState.value = _uiState.value.copy(inputText = "")
            return
        }

        val displayPrompt = prompt.ifBlank {
            if (currentImage != null) "[Analyze Image]" else "[Analyze Document]"
        }

        // Add user message
        val userMsg = ChatMessage(
            sender = MessageSender.USER,
            text = displayPrompt,
            attachment = when {
                currentImage != null -> Attachment.Image(
                    uri = _uiState.value.attachedImageUri!!,
                    width = currentImage.width,
                    height = currentImage.height,
                    asciiPreview = currentImageAscii
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
            attachedImageAscii = null,
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

            val contextPrefix = currentDoc?.textContent?.let {
                "--- BEGIN ATTACHED DOCUMENT: ${currentDoc.fileName} ---\n$it\n--- END ATTACHED DOCUMENT ---"
            } ?: ""

            val startTime = System.currentTimeMillis()
            var firstTokenTime: Long? = null
            val fullTextBuilder = StringBuilder()

            engine.generateStream(displayPrompt, contextPrefix, currentImage)
                .catch { e ->
                    updateStreamingMessage(
                        responseId,
                        "visper: error: ${e.message}",
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

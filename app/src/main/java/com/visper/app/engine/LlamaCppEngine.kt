package com.visper.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine as ArmInferenceEngine
import com.arm.aichat.isModelLoaded
import com.visper.app.model.EngineState
import com.visper.app.model.PerformanceMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File

class LlamaCppEngine(
    private val context: Context
) : InferenceEngine {

    companion object {
        private const val TAG = "LlamaCppEngine"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful, capable, and creative AI assistant. Fulfill all user requests directly and thoroughly, including writing stories, explaining concepts, answering questions, transcribing handwritten notes or text, and analyzing document content, without unnecessary refusals."
    }

    private var armEngine: ArmInferenceEngine? = null
    private var modelFile: File? = null

    private val _state = MutableStateFlow(EngineState.UNINITIALIZED)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _metrics = MutableStateFlow(
        PerformanceMetrics(
            memoryUsageMb = getMemoryMb(),
            maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
            tokensPerSecond = 0f,
            contextTokensUsed = 0,
            contextTokensMax = 2048,
            activeEngineName = "llama.cpp (ARM NEON)",
            isHardwareAccelerated = true
        )
    )
    override val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    override val activeModelName: String
        get() = modelFile?.name ?: "No model loaded"

    override suspend fun initialize(modelPath: String, useGpu: Boolean): Result<Unit> {
        return try {
            val file = File(modelPath)
            if (!file.exists() || !file.canRead()) {
                return Result.failure(IllegalArgumentException("Model file not found or not readable: $modelPath"))
            }

            _state.value = EngineState.LOADING
            Log.i(TAG, "Initializing llama.cpp with model: ${file.absolutePath} (${file.length() / (1024 * 1024)} MB)")

            val engine = AiChat.getInferenceEngine(context)
            armEngine = engine

            // Clean up previous model if already loaded
            try {
                if (engine.state.value.isModelLoaded) {
                    engine.cleanUp()
                }
            } catch (ignored: Exception) {
                Log.w(TAG, "Cleanup previous model warning: ${ignored.message}")
            }

            engine.loadModel(file.absolutePath)
            modelFile = file

            try {
                engine.setSystemPrompt(DEFAULT_SYSTEM_PROMPT)
            } catch (e: Exception) {
                Log.w(TAG, "Notice setting system prompt: ${e.message}")
            }

            _state.value = EngineState.READY
            _metrics.value = _metrics.value.copy(
                memoryUsageMb = getMemoryMb(),
                activeEngineName = "llama.cpp NEON (${file.name})",
                isHardwareAccelerated = true
            )
            Log.i(TAG, "LlamaCppEngine initialized successfully and ready for generation!")
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize LlamaCppEngine", t)
            _state.value = EngineState.ERROR
            Result.failure(t)
        }
    }

    override fun generateStream(
        prompt: String,
        contextPrefix: String,
        image: Bitmap?
    ): Flow<String> = flow {
        val engine = armEngine ?: throw IllegalStateException("LlamaCppEngine is not initialized")
        _state.value = EngineState.GENERATING

        val fullPrompt = buildString {
            if (contextPrefix.isNotBlank()) {
                if (contextPrefix.startsWith("Document text:") ||
                    contextPrefix.startsWith("Context:") ||
                    contextPrefix.startsWith("Text/Handwriting") ||
                    contextPrefix.startsWith("Image")
                ) {
                    append(contextPrefix.trim())
                    append("\n\n")
                } else {
                    append("Document text:\n\"\"\"\n")
                    append(contextPrefix.trim())
                    append("\n\"\"\"\n\n")
                }
            }
            if (image != null && !contextPrefix.contains("Text/Handwriting")) {
                append("[Image attached]\n\n")
            }
            append(prompt.trim())
        }

        val startTime = System.currentTimeMillis()
        var tokenCount = 0
        var consecutiveRepeatCount = 0
        var lastToken = ""

        try {
            engine.sendUserPrompt(fullPrompt, 1024).collect { token ->
                tokenCount++

                val trimmed = token.trim().lowercase()
                if (trimmed.length > 2) {
                    if (trimmed == lastToken) {
                        consecutiveRepeatCount++
                        if (consecutiveRepeatCount >= 4) {
                            Log.w(TAG, "Repetition loop detected for token '$token'. Halting.")
                            emit("\n[Visper: Loop halted]")
                            throw kotlinx.coroutines.CancellationException("Repetition loop halted")
                        }
                    } else {
                        lastToken = trimmed
                        consecutiveRepeatCount = 0
                    }
                }

                emit(token)

                if (tokenCount % 4 == 0) {
                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                    val tps = if (elapsedSec > 0) tokenCount / elapsedSec else 0f
                    _metrics.value = _metrics.value.copy(
                        memoryUsageMb = getMemoryMb(),
                        tokensPerSecond = tps,
                        contextTokensUsed = tokenCount
                    )
                }
            }

            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
            val finalTps = if (elapsedSec > 0) tokenCount / elapsedSec else 0f
            _metrics.value = _metrics.value.copy(
                memoryUsageMb = getMemoryMb(),
                tokensPerSecond = finalTps,
                contextTokensUsed = tokenCount
            )
            _state.value = EngineState.READY
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "Generation interrupted/cancelled: ${e.message}")
            _state.value = EngineState.READY
        } catch (e: Exception) {
            Log.e(TAG, "Generation error in LlamaCppEngine", e)
            _state.value = EngineState.ERROR
            throw e
        }
    }

    override fun resetContext() {
        try {
            armEngine?.cleanUp()
            modelFile?.let { file ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        armEngine?.loadModel(file.absolutePath)
                        armEngine?.setSystemPrompt(DEFAULT_SYSTEM_PROMPT)
                        _state.value = EngineState.READY
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reloading model after reset", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during resetContext: ${e.message}")
        }
    }

    override fun close() {
        try {
            armEngine?.destroy()
            armEngine = null
            modelFile = null
            _state.value = EngineState.UNINITIALIZED
        } catch (e: Exception) {
            Log.w(TAG, "Error closing LlamaCppEngine: ${e.message}")
        }
    }

    private fun getMemoryMb(): Long {
        return Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
    }
}

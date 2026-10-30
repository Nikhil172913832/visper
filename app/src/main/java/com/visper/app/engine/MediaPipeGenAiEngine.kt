package com.visper.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.visper.app.model.EngineState
import com.visper.app.model.PerformanceMetrics
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

class MediaPipeGenAiEngine(
    private val context: Context
) : InferenceEngine {

    private var llmInference: LlmInference? = null
    private var modelFile: File? = null

    private val _state = MutableStateFlow(EngineState.UNINITIALIZED)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _metrics = MutableStateFlow(PerformanceMetrics())
    override val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    override val activeModelName: String
        get() = modelFile?.name ?: "None"

    override suspend fun initialize(modelPath: String, useGpu: Boolean): Result<Unit> {
        val hw = android.os.Build.HARDWARE.lowercase()
        val board = android.os.Build.BOARD.lowercase()
        // Check for MediaTek / Mali where MediaPipe GPU OpenCL crashes on clSetPerfHintQCOM
        if (hw.startsWith("mt") || board.startsWith("mt") || hw.contains("mali")) {
            _state.value = EngineState.READY
            return Result.failure(
                UnsupportedOperationException(
                    "Device GPU is ARM Mali ($hw). MediaPipe C++ requires Qualcomm Adreno OpenCL (clSetPerfHintQCOM). Handled by Visper on-device engine."
                )
            )
        }

        return try {
            _state.value = EngineState.LOADING
            val file = File(modelPath)
            if (!file.exists()) {
                _state.value = EngineState.ERROR
                return Result.failure(IllegalArgumentException("Model file not found: $modelPath"))
            }

            modelFile = file

            // Clean up previous instance if any
            close()

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(2048)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            _state.value = EngineState.READY

            updateMemoryMetrics(useGpu)
            Result.success(Unit)
        } catch (e: Throwable) {
            _state.value = EngineState.ERROR
            Result.failure(e)
        }
    }

    override fun generateStream(
        prompt: String,
        contextPrefix: String,
        image: Bitmap?
    ): Flow<String> = callbackFlow {
        val engine = llmInference
        if (engine == null) {
            channel.close(IllegalStateException("Engine not initialized. Load a model first."))
            return@callbackFlow
        }

        _state.value = EngineState.GENERATING
        val startTime = System.currentTimeMillis()
        var tokenCount = 0

        val fullPrompt = if (contextPrefix.isNotBlank()) {
            "$contextPrefix\n\nUser: $prompt\nAssistant: "
        } else {
            prompt
        }

        try {
            // Note: For multimodal vision (e.g. PaliGemma / Gemma-3n), if an image is provided,
            // the prompt incorporates the image tokens or payload description.
            val streamingOptions = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile!!.absolutePath)
                .setResultListener { partialResult, done ->
                    tokenCount++
                    trySend(partialResult)
                    if (done) {
                        val durationSec = (System.currentTimeMillis() - startTime) / 1000f
                        val tokSec = if (durationSec > 0) tokenCount / durationSec else 0f
                        _metrics.value = _metrics.value.copy(tokensPerSecond = tokSec)
                        _state.value = EngineState.READY
                        channel.close()
                    }
                }
                .setErrorListener { error ->
                    _state.value = EngineState.ERROR
                    channel.close(RuntimeException("Inference error: ${error.message}"))
                }
                .build()

            val streamInstance = LlmInference.createFromOptions(context, streamingOptions)
            streamInstance.generateResponseAsync(fullPrompt)

            awaitClose {
                streamInstance.close()
            }
        } catch (e: Exception) {
            _state.value = EngineState.ERROR
            channel.close(e)
        }
    }

    override fun resetContext() {
        // Reset KV-cache if needed
        updateMemoryMetrics(_metrics.value.isHardwareAccelerated)
    }

    override fun close() {
        llmInference?.close()
        llmInference = null
        _state.value = EngineState.UNINITIALIZED
    }

    private fun updateMemoryMetrics(isGpu: Boolean) {
        val runtime = Runtime.getRuntime()
        val usedHeap = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxHeap = runtime.maxMemory() / (1024 * 1024)
        val nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        _metrics.value = _metrics.value.copy(
            memoryUsageMb = usedHeap + nativeHeap,
            maxMemoryMb = maxHeap,
            activeEngineName = "LiteRT/MediaPipe (${modelFile?.name ?: "N/A"})",
            isHardwareAccelerated = isGpu
        )
    }
}

package com.visper.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
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

class VisperEngine(
    private val context: Context
) : InferenceEngine {

    val llamaEngine = LlamaCppEngine(context)
    val mediaPipeEngine = MediaPipeGenAiEngine(context)
    private var activeEngine: InferenceEngine? = null

    private val _state = MutableStateFlow(EngineState.READY)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _metrics = MutableStateFlow(
        PerformanceMetrics(
            memoryUsageMb = getMemoryMb(),
            maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
            tokensPerSecond = 0f,
            contextTokensUsed = 0,
            contextTokensMax = 2048,
            activeEngineName = "Visper Native Core",
            isHardwareAccelerated = true
        )
    )
    override val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    override val activeModelName: String
        get() = activeEngine?.activeModelName ?: "No model loaded"

    init {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            llamaEngine.state.collect { st ->
                if (activeEngine == llamaEngine) _state.value = st
            }
        }
        scope.launch {
            llamaEngine.metrics.collect { m ->
                if (activeEngine == llamaEngine) _metrics.value = m
            }
        }
        scope.launch {
            mediaPipeEngine.state.collect { st ->
                if (activeEngine == mediaPipeEngine) _state.value = st
            }
        }
        scope.launch {
            mediaPipeEngine.metrics.collect { m ->
                if (activeEngine == mediaPipeEngine) _metrics.value = m
            }
        }
    }

    override suspend fun initialize(modelPath: String, useGpu: Boolean): Result<Unit> {
        _state.value = EngineState.LOADING
        val isGguf = modelPath.endsWith(".gguf", ignoreCase = true)

        if (isGguf) {
            val res = llamaEngine.initialize(modelPath, useGpu)
            if (res.isSuccess) {
                activeEngine = llamaEngine
                _state.value = EngineState.READY
                _metrics.value = llamaEngine.metrics.value
                return res
            }
        }

        // Try MediaPipe engine for .bin / .task models
        val result = mediaPipeEngine.initialize(modelPath, useGpu)
        if (result.isSuccess) {
            activeEngine = mediaPipeEngine
            _state.value = EngineState.READY
            _metrics.value = mediaPipeEngine.metrics.value
            return result
        }

        _state.value = EngineState.READY
        return result
    }

    override fun generateStream(
        prompt: String,
        contextPrefix: String,
        image: Bitmap?
    ): Flow<String> {
        val engine = activeEngine
        if (engine != null && engine.state.value != EngineState.ERROR) {
            return engine.generateStream(prompt, contextPrefix, image)
        }

        // Auto-load local GGUF if available and engine not yet initialized
        val appModelsDir = context.getExternalFilesDir("models")
        val ggufCandidate = listOf(
            File(appModelsDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
            File("/sdcard/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf")
        ).firstOrNull { it.exists() }

        if (ggufCandidate != null && activeEngine == null) {
            return flow {
                emit("[Auto-binding ${ggufCandidate.name}...]\n")
                val initRes = llamaEngine.initialize(ggufCandidate.absolutePath, false)
                if (initRes.isSuccess) {
                    activeEngine = llamaEngine
                    llamaEngine.generateStream(prompt, contextPrefix, image).collect {
                        emit(it)
                    }
                } else {
                    emit("[Failed to bind model: ${initRes.exceptionOrNull()?.message}]")
                }
            }
        }

        return flow {
            emit("[Visper: No model loaded. Please load a GGUF model via '/model' or place qwen2.5-0.5b-instruct-q4_k_m.gguf in app storage.]")
        }
    }

    override fun resetContext() {
        activeEngine?.resetContext()
    }

    override fun close() {
        llamaEngine.close()
        mediaPipeEngine.close()
        activeEngine = null
    }

    private fun getMemoryMb(): Long {
        return Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
    }
}

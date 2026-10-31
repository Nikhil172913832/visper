package com.visper.app.engine

import android.graphics.Bitmap
import com.visper.app.model.EngineState
import com.visper.app.model.PerformanceMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface InferenceEngine {
    val state: StateFlow<EngineState>
    val metrics: StateFlow<PerformanceMetrics>
    val activeModelName: String

    suspend fun initialize(modelPath: String, useGpu: Boolean = true): Result<Unit>
    fun generateStream(
        prompt: String,
        contextPrefix: String = "",
        image: Bitmap? = null
    ): Flow<String>
    fun resetContext()
    fun close()
    fun getHardwareStats(): Pair<Long, Long> = Pair(0L, 0L)
}

package com.visper.app.model

data class PerformanceMetrics(
    val memoryUsageMb: Long = 0,
    val maxMemoryMb: Long = 0,
    val tokensPerSecond: Float = 0f,
    val contextTokensUsed: Int = 0,
    val contextTokensMax: Int = 2048,
    val activeEngineName: String = "Visper Core",
    val isHardwareAccelerated: Boolean = true
)

enum class EngineState {
    UNINITIALIZED,
    LOADING,
    READY,
    GENERATING,
    ERROR
}

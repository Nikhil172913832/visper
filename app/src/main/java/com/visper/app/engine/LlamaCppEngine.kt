package com.visper.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import com.visper.app.model.EngineState
import com.visper.app.model.PerformanceMetrics
import com.visper.app.vision.ImageProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class LlamaCppEngine(
    private val context: Context
) : InferenceEngine {

    companion object {
        private const val TAG = "LlamaCppEngine"
        private const val SERVER_PORT = 8080
        private const val SERVER_HOST = "127.0.0.1"
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are Visper, an intelligent on-device AI assistant. Answer questions clearly, accurately, and concisely."
        private const val VISION_SYSTEM_PROMPT =
            "You are Visper, an intelligent on-device multimodal AI assistant. Describe images, transcribe visible text accurately, and answer the user's questions clearly and concisely."
    }

    private var serverProcess: Process? = null
    private var modelFile: File? = null
    private var mmprojFile: File? = null
    private var activeHttpConnection: HttpURLConnection? = null

    private val _state = MutableStateFlow(EngineState.UNINITIALIZED)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _metrics = MutableStateFlow(
        PerformanceMetrics(
            memoryUsageMb = getMemoryMb(),
            maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
            tokensPerSecond = 0f,
            contextTokensUsed = 0,
            contextTokensMax = 2048,
            activeEngineName = "Qwen Multimodal NEON",
            isHardwareAccelerated = true
        )
    )
    override val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    override val activeModelName: String
        get() = modelFile?.name ?: "No model loaded"

    override suspend fun initialize(modelPath: String, useGpu: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(modelPath)
            if (!file.exists() || !file.canRead()) {
                return@withContext Result.failure(IllegalArgumentException("Model file not found: $modelPath"))
            }

            _state.value = EngineState.LOADING
            Log.i(TAG, "Initializing Native Engine with model: ${file.absolutePath} (${file.length() / (1024 * 1024)} MB)")

            // Locate companion mmproj file if available in model directory
            val parentDir = file.parentFile
            val detectedMmproj = parentDir?.listFiles()?.firstOrNull { f ->
                val name = f.name.lowercase()
                name.contains("mmproj") && name.endsWith(".gguf")
            }
            mmprojFile = detectedMmproj
            if (detectedMmproj != null) {
                Log.i(TAG, "Companion multimodal projector detected: ${detectedMmproj.name} (${detectedMmproj.length() / (1024 * 1024)} MB)")
            }

            // Locate executable binary
            val binary = getExecutableServerBinary()
            if (binary == null || !binary.exists()) {
                return@withContext Result.failure(IllegalStateException("Native llama-server binary not found on device."))
            }

            // Check if server is already running with this model
            if (isServerHealthy() && modelFile?.absolutePath == file.absolutePath) {
                Log.i(TAG, "llama-server already running healthy with ${file.name}")
                _state.value = EngineState.READY
                return@withContext Result.success(Unit)
            }

            // Terminate previous server process if running
            stopServer()

            // Prepare command
            val cmd = mutableListOf(
                binary.absolutePath,
                "-m", file.absolutePath,
                "--host", SERVER_HOST,
                "--port", SERVER_PORT.toString(),
                "-c", "2048",
                "-t", "4",
                "--image-max-tokens", "512",
                "--reasoning", "off"
            )

            if (detectedMmproj != null && detectedMmproj.exists()) {
                cmd.add("--mmproj")
                cmd.add(detectedMmproj.absolutePath)
            }

            Log.i(TAG, "Starting native daemon: ${cmd.joinToString(" ")}")
            val pb = ProcessBuilder(cmd)
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
            pb.redirectErrorStream(true)

            val process = pb.start()
            serverProcess = process

            // Asynchronously log server stdout/stderr
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, "[llama-server] $line")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Server stream ended: ${e.message}")
                }
            }

            // Poll /health endpoint until server is ready (max 15 seconds)
            val startTime = System.currentTimeMillis()
            var healthy = false
            while (System.currentTimeMillis() - startTime < 15000) {
                if (isServerHealthy()) {
                    healthy = true
                    break
                }
                kotlinx.coroutines.delay(250)
            }

            if (!healthy) {
                stopServer()
                return@withContext Result.failure(IllegalStateException("Native engine daemon failed to become ready within timeout."))
            }

            modelFile = file
            _state.value = EngineState.READY
            _metrics.value = _metrics.value.copy(
                memoryUsageMb = getMemoryMb(),
                activeEngineName = "Qwen Multimodal (${file.name})",
                isHardwareAccelerated = true
            )

            Log.i(TAG, "Native Multimodal Engine successfully initialized and ready!")
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize Native Multimodal Engine", t)
            _state.value = EngineState.ERROR
            Result.failure(t)
        }
    }

    override fun generateStream(
        prompt: String,
        contextPrefix: String,
        image: Bitmap?
    ): Flow<String> = flow {
        Log.i(TAG, "generateStream requested: prompt='${prompt.take(60)}...', hasImage=${image != null}")
        if (!isServerHealthy()) {
            throw IllegalStateException("Multimodal neural engine is not ready")
        }

        _state.value = EngineState.GENERATING

        // Build OpenAI Chat Completions JSON schema
        val root = JSONObject()
        root.put("model", modelFile?.nameWithoutExtension ?: "qwen3.5-0.8b")
        root.put("stream", true)
        root.put("max_tokens", 512)
        root.put("temperature", 0.3)
        root.put("frequency_penalty", 0.3)
        root.put("presence_penalty", 0.3)

        val messages = JSONArray()
        val sysMsg = JSONObject().apply {
            put("role", "system")
            val basePrompt = if (image != null) VISION_SYSTEM_PROMPT else DEFAULT_SYSTEM_PROMPT
            val sysContent = if (contextPrefix.isNotBlank()) {
                "$basePrompt\n\n$contextPrefix"
            } else {
                basePrompt
            }
            put("content", sysContent)
        }
        messages.put(sysMsg)

        val userPrompt = prompt.trim()
        val userMsg = JSONObject()
        userMsg.put("role", "user")

        if (image != null) {
            val contentArr = JSONArray()
            val textObj = JSONObject().apply {
                put("type", "text")
                put("text", userPrompt.ifBlank { "Describe this image in detail and transcribe any visible handwriting or text." })
            }
            contentArr.put(textObj)

            val base64Img = ImageProcessor.bitmapToBase64Jpeg(image, maxDim = 640)
            val imgObj = JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Img")
                })
            }
            contentArr.put(imgObj)
            userMsg.put("content", contentArr)
        } else {
            userMsg.put("content", userPrompt)
        }

        messages.put(userMsg)
        root.put("messages", messages)

        val startTime = System.currentTimeMillis()
        var tokenCount = 0
        var consecutiveRepeatCount = 0
        var lastToken = ""

        val url = URL("http://$SERVER_HOST:$SERVER_PORT/v1/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            doOutput = true
            connectTimeout = 10000
            readTimeout = 120000
        }
        activeHttpConnection = conn

        try {
            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(root.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorStream = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                throw IllegalStateException("Engine inference error ($responseCode): $errorStream")
            }

            val thinkingFilter = ThinkingFilter { cleanToken ->
                tokenCount++

                val trimmed = cleanToken.trim().lowercase()
                if (trimmed.length > 2) {
                    if (trimmed == lastToken) {
                        consecutiveRepeatCount++
                        if (consecutiveRepeatCount >= 4) {
                            Log.w(TAG, "Repetition loop detected for token '$cleanToken'. Halting.")
                            emit("\n[Visper: Loop halted]")
                            return@ThinkingFilter
                        }
                    } else {
                        lastToken = trimmed
                        consecutiveRepeatCount = 0
                    }
                }

                emit(cleanToken)

                if (tokenCount % 3 == 0) {
                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                    val tps = if (elapsedSec > 0) tokenCount / elapsedSec else 0f
                    _metrics.value = _metrics.value.copy(
                        memoryUsageMb = getMemoryMb(),
                        tokensPerSecond = tps,
                        contextTokensUsed = tokenCount
                    )
                }
            }

            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isEmpty()) continue
                    if (!trimmedLine.startsWith("data:")) continue

                    val data = trimmedLine.removePrefix("data:").trim()
                    if (data == "[DONE]") break

                    try {
                        val chunkObj = JSONObject(data)
                        val choices = chunkObj.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val choice = choices.getJSONObject(0)
                            val delta = choice.optJSONObject("delta")
                            if (delta != null) {
                                val content = if (delta.isNull("content")) "" else delta.optString("content", "")
                                val reasoning = if (delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content", "")
                                val token = if (content.isNotEmpty()) content else reasoning
                                if (token.isNotEmpty()) {
                                    thinkingFilter.feed(token)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Skipping SSE parse chunk: ${e.message}")
                    }
                }
            }

            thinkingFilter.flush()

            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
            val finalTps = if (elapsedSec > 0) tokenCount / elapsedSec else 0f
            _metrics.value = _metrics.value.copy(
                memoryUsageMb = getMemoryMb(),
                tokensPerSecond = finalTps,
                contextTokensUsed = tokenCount
            )
            _state.value = EngineState.READY
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "Generation cancelled: ${e.message}")
            conn.disconnect()
            _state.value = EngineState.READY
        } catch (e: java.net.SocketException) {
            Log.i(TAG, "Socket closed / generation halted: ${e.message}")
            conn.disconnect()
            _state.value = EngineState.READY
        } catch (e: Exception) {
            Log.e(TAG, "Generation error in LlamaCppEngine", e)
            _state.value = if (isServerHealthy()) EngineState.READY else EngineState.ERROR
            throw e
        } finally {
            activeHttpConnection = null
        }
    }.flowOn(Dispatchers.IO)

    override fun resetContext() {
        try {
            activeHttpConnection?.disconnect()
            activeHttpConnection = null
            // Optional: call slots release
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val url = URL("http://$SERVER_HOST:$SERVER_PORT/slots/0?action=release")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 1000
                    conn.readTimeout = 1000
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Exception) {}
            }
            _state.value = EngineState.READY
        } catch (e: Exception) {
            Log.w(TAG, "Error during resetContext: ${e.message}")
        }
    }

    override fun close() {
        try {
            activeHttpConnection?.disconnect()
            activeHttpConnection = null
            stopServer()
            modelFile = null
            mmprojFile = null
            _state.value = EngineState.UNINITIALIZED
        } catch (e: Exception) {
            Log.w(TAG, "Error closing LlamaCppEngine: ${e.message}")
        }
    }

    private fun isServerHealthy(): Boolean {
        return try {
            val url = URL("http://$SERVER_HOST:$SERVER_PORT/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 800
                readTimeout = 800
            }
            val code = conn.responseCode
            conn.disconnect()
            code == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.d(TAG, "Health check error: ${e.message}")
            false
        }
    }

    private fun stopServer() {
        try {
            serverProcess?.destroy()
            serverProcess?.waitFor()
        } catch (_: Exception) {}
        serverProcess = null
    }

    private fun getExecutableServerBinary(): File? {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val serverLib = File(nativeLibDir, "libllama-server.so")
        if (serverLib.exists() && serverLib.canExecute()) {
            return serverLib
        }

        // Fallback: Copy to filesDir/bin/llama-server and set executable
        val binDir = File(context.filesDir, "bin").apply { mkdirs() }
        val targetBin = File(binDir, "llama-server")
        if (!targetBin.exists() || targetBin.length() != serverLib.length()) {
            if (serverLib.exists()) {
                serverLib.copyTo(targetBin, overwrite = true)
                targetBin.setExecutable(true, false)
            }
        }
        if (targetBin.exists() && targetBin.canExecute()) {
            return targetBin
        }

        return if (serverLib.exists()) serverLib else null
    }

    private fun getMemoryMb(): Long {
        val serverMb = getServerMemoryMb()
        val appMb = getAppMemoryMb()
        return if (serverMb > 0) serverMb + appMb else Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
    }

    override fun getHardwareStats(): Pair<Long, Long> {
        val serverMb = getServerMemoryMb()
        val appMb = getAppMemoryMb()
        return Pair(serverMb, appMb)
    }

    fun getServerPid(): Int? {
        serverProcess?.let { proc ->
            try {
                val pidField = proc.javaClass.getDeclaredField("pid")
                pidField.isAccessible = true
                return pidField.getInt(proc)
            } catch (_: Exception) {}
        }
        try {
            val procDir = java.io.File("/proc")
            val pids = procDir.listFiles { file -> file.isDirectory && file.name.all { it.isDigit() } }
            for (p in pids ?: emptyArray()) {
                val cmdlineFile = java.io.File(p, "cmdline")
                if (cmdlineFile.exists()) {
                    val cmdline = cmdlineFile.readText()
                    if (cmdline.contains("libllama-server.so")) {
                        return p.name.toIntOrNull()
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun getServerMemoryMb(): Long {
        val pid = getServerPid() ?: return 0L
        return try {
            val statusFile = java.io.File("/proc/$pid/status")
            if (!statusFile.exists()) return 0L
            statusFile.useLines { lines ->
                val line = lines.firstOrNull { it.startsWith("VmRSS:") }
                val kb = line?.replace(Regex("[^0-9]"), "")?.toLongOrNull() ?: 0L
                kb / 1024
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun getAppMemoryMb(): Long {
        return try {
            val statusFile = java.io.File("/proc/self/status")
            if (!statusFile.exists()) {
                val rt = Runtime.getRuntime()
                return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            }
            statusFile.useLines { lines ->
                val line = lines.firstOrNull { it.startsWith("VmRSS:") }
                val kb = line?.replace(Regex("[^0-9]"), "")?.toLongOrNull() ?: 0L
                kb / 1024
            }
        } catch (_: Exception) {
            val rt = Runtime.getRuntime()
            (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        }
    }
}

private class ThinkingFilter(private val onEmit: suspend (String) -> Unit) {
    private var isInsideThinking = false
    private val buffer = StringBuilder()

    suspend fun feed(chunk: String) {
        buffer.append(chunk)
        processBuffer()
    }

    private suspend fun processBuffer() {
        while (buffer.isNotEmpty()) {
            if (isInsideThinking) {
                val endIdx = buffer.indexOf("</think>")
                if (endIdx != -1) {
                    isInsideThinking = false
                    buffer.delete(0, endIdx + "</think>".length)
                } else {
                    if (buffer.length > "</think>".length) {
                        buffer.delete(0, buffer.length - "</think>".length)
                    }
                    break
                }
            } else {
                val startIdx = buffer.indexOf("<think>")
                if (startIdx != -1) {
                    if (startIdx > 0) {
                        val before = buffer.substring(0, startIdx)
                        onEmit(before)
                    }
                    isInsideThinking = true
                    buffer.delete(0, startIdx + "<think>".length)
                } else {
                    val possiblePrefixes = listOf("<", "<t", "<th", "<thi", "<thin", "<think")
                    val matchingPrefix = possiblePrefixes.lastOrNull { buffer.endsWith(it) }
                    if (matchingPrefix != null) {
                        val safeLen = buffer.length - matchingPrefix.length
                        if (safeLen > 0) {
                            val safe = buffer.substring(0, safeLen)
                            onEmit(safe)
                            buffer.delete(0, safeLen)
                        }
                        break
                    } else {
                        val all = buffer.toString()
                        buffer.clear()
                        onEmit(all)
                    }
                }
            }
        }
    }

    suspend fun flush() {
        if (!isInsideThinking && buffer.isNotEmpty()) {
            val remaining = buffer.toString()
            buffer.clear()
            onEmit(remaining)
        }
    }
}

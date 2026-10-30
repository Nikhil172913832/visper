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
            "You are Visper, an intelligent on-device multimodal assistant. Answer all user requests, transcribe handwritten notes and text, analyze images, and answer questions directly, accurately, and concisely."
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
                "--image-max-tokens", "512"
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

        val fullPrompt = buildString {
            if (contextPrefix.isNotBlank()) {
                append(contextPrefix.trim())
                append("\n\n")
            }
            append(prompt.trim())
        }

        // Build OpenAI Chat Completions JSON schema
        val root = JSONObject()
        root.put("model", modelFile?.nameWithoutExtension ?: "qwen3.5-0.8b")
        root.put("stream", true)
        root.put("max_tokens", 1024)
        root.put("temperature", 0.7)
        root.put("frequency_penalty", 0.5)
        root.put("presence_penalty", 0.5)

        val messages = JSONArray()
        val sysMsg = JSONObject().apply {
            put("role", "system")
            put("content", DEFAULT_SYSTEM_PROMPT)
        }
        messages.put(sysMsg)

        val userMsg = JSONObject()
        userMsg.put("role", "user")

        if (image != null) {
            val contentArr = JSONArray()
            val textObj = JSONObject().apply {
                put("type", "text")
                put("text", fullPrompt.ifBlank { "Describe this image in detail and transcribe any visible handwriting or text." })
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
            userMsg.put("content", fullPrompt)
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
                                    tokenCount++

                                    val trimmed = token.trim().lowercase()
                                    if (trimmed.length > 2) {
                                        if (trimmed == lastToken) {
                                            consecutiveRepeatCount++
                                            if (consecutiveRepeatCount >= 4) {
                                                Log.w(TAG, "Repetition loop detected for token '$token'. Halting.")
                                                emit("\n[Visper: Loop halted]")
                                                break
                                            }
                                        } else {
                                            lastToken = trimmed
                                            consecutiveRepeatCount = 0
                                        }
                                    }

                                    emit(token)

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
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Skipping SSE parse chunk: ${e.message}")
                    }
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
            Log.i(TAG, "Generation cancelled: ${e.message}")
            conn.disconnect()
            _state.value = EngineState.READY
        } catch (e: Exception) {
            Log.e(TAG, "Generation error in LlamaCppEngine", e)
            _state.value = EngineState.ERROR
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
        return Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
    }
}

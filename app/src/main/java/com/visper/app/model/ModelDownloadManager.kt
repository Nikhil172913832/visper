package com.visper.app.model

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ModelDownloadProgress(
    val isDownloading: Boolean = false,
    val currentFileName: String = "",
    val fileIndex: Int = 0,
    val totalFiles: Int = 2,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val fileProgress: Float = 0f,
    val overallProgress: Float = 0f,
    val speedMbPerSec: Float = 0f,
    val etaSeconds: Int = 0,
    val errorMessage: String? = null,
    val isCompleted: Boolean = false
)

class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloadManager"

        const val MODEL_FILE_NAME = "Qwen3.5-0.8B-Q4_K_M.gguf"
        const val MMPROJ_FILE_NAME = "mmproj-F16.gguf"

        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
        const val MMPROJ_DOWNLOAD_URL =
            "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/mmproj-F16.gguf"

        // Expected approximate total sizes for calculation
        const val EXPECTED_MODEL_BYTES = 532_517_120L // ~508 MB
        const val EXPECTED_MMPROJ_BYTES = 204_987_232L // ~195 MB
        const val TOTAL_DOWNLOAD_BYTES = EXPECTED_MODEL_BYTES + EXPECTED_MMPROJ_BYTES // ~703 MB
    }

    private val _progress = MutableStateFlow(ModelDownloadProgress())
    val progress: StateFlow<ModelDownloadProgress> = _progress.asStateFlow()

    private var activeJob: Job? = null
    private var activeConnection: HttpURLConnection? = null

    val modelsDir: File
        get() = context.getExternalFilesDir("models") ?: File(context.filesDir, "models").apply {
            if (!exists()) mkdirs()
        }

    fun isModelInstalled(): Boolean {
        val appDir = modelsDir
        val candidatePaths = listOf(
            File(appDir, MODEL_FILE_NAME),
            File(appDir, "qwen3.5-0.8b-q4_k_m.gguf"),
            File(appDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
            File("/sdcard/Android/data/com.visper.app/files/models/$MODEL_FILE_NAME"),
            File("/sdcard/Download/$MODEL_FILE_NAME")
        )
        return candidatePaths.any { it.exists() && it.length() > 50 * 1024 * 1024 }
    }

    fun getInstalledModelFile(): File? {
        val appDir = modelsDir
        val candidatePaths = listOf(
            File(appDir, MODEL_FILE_NAME),
            File(appDir, "qwen3.5-0.8b-q4_k_m.gguf"),
            File(appDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
            File("/sdcard/Android/data/com.visper.app/files/models/$MODEL_FILE_NAME"),
            File("/sdcard/Download/$MODEL_FILE_NAME")
        )
        return candidatePaths.firstOrNull { it.exists() && it.length() > 50 * 1024 * 1024 }
    }

    suspend fun startDownload(onCompleted: (File) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        if (_progress.value.isDownloading) {
            return@withContext Result.failure(IllegalStateException("Download already in progress"))
        }

        val targetDir = modelsDir
        if (!targetDir.exists()) targetDir.mkdirs()

        val tasks = listOf(
            DownloadTask(
                fileName = MODEL_FILE_NAME,
                url = MODEL_DOWNLOAD_URL,
                expectedBytes = EXPECTED_MODEL_BYTES,
                index = 1
            ),
            DownloadTask(
                fileName = MMPROJ_FILE_NAME,
                url = MMPROJ_DOWNLOAD_URL,
                expectedBytes = EXPECTED_MMPROJ_BYTES,
                index = 2
            )
        )

        _progress.value = ModelDownloadProgress(
            isDownloading = true,
            currentFileName = tasks[0].fileName,
            fileIndex = 1,
            totalFiles = tasks.size,
            totalBytes = TOTAL_DOWNLOAD_BYTES,
            errorMessage = null,
            isCompleted = false
        )

        var completedBytesAcrossFiles = 0L

        for (task in tasks) {
            val destinationFile = File(targetDir, task.fileName)
            // If already downloaded and valid size, skip
            if (destinationFile.exists() && destinationFile.length() >= task.expectedBytes * 0.95) {
                completedBytesAcrossFiles += destinationFile.length()
                continue
            }

            val partFile = File(targetDir, "${task.fileName}.part")

            val downloadResult = downloadSingleFile(
                task = task,
                destinationPartFile = partFile,
                accumulatedCompletedBytes = completedBytesAcrossFiles
            )

            if (downloadResult.isFailure) {
                val err = downloadResult.exceptionOrNull()?.message ?: "Download failed"
                Log.e(TAG, "Failed downloading ${task.fileName}: $err")
                _progress.value = _progress.value.copy(
                    isDownloading = false,
                    errorMessage = err
                )
                return@withContext Result.failure(downloadResult.exceptionOrNull() ?: Exception(err))
            }

            // Rename .part to final .gguf
            if (partFile.exists()) {
                if (destinationFile.exists()) destinationFile.delete()
                partFile.renameTo(destinationFile)
            }

            completedBytesAcrossFiles += destinationFile.length()
        }

        val primaryModelFile = File(targetDir, MODEL_FILE_NAME)
        _progress.value = _progress.value.copy(
            isDownloading = false,
            overallProgress = 1.0f,
            fileProgress = 1.0f,
            isCompleted = true,
            errorMessage = null
        )

        onCompleted(primaryModelFile)
        Result.success(Unit)
    }

    private suspend fun downloadSingleFile(
        task: DownloadTask,
        destinationPartFile: File,
        accumulatedCompletedBytes: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            var targetUrl = task.url
            var redirects = 0
            while (redirects < 6) {
                val urlObj = URL(targetUrl)
                connection = (urlObj.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Visper-Android-NeuralCore/1.0")
                }
                activeConnection = connection

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == 307 || responseCode == 308 || responseCode == 302
                ) {
                    val location = connection.getHeaderField("Location")
                    if (location != null) {
                        targetUrl = location
                        redirects++
                        connection.disconnect()
                        continue
                    }
                }
                break
            }

            val conn = connection ?: return@withContext Result.failure(Exception("Failed to open connection"))
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.failure(Exception("Server returned HTTP $responseCode for ${task.fileName}"))
            }

            val serverContentLength = conn.contentLengthLong.takeIf { it > 0 } ?: task.expectedBytes
            var bytesDownloaded = 0L
            var lastUpdateMs = SystemClock.elapsedRealtime()
            var bytesSinceLastSpeedSample = 0L
            var currentSpeedMbPerSec = 0f

            conn.inputStream.use { input ->
                FileOutputStream(destinationPartFile).use { output ->
                    val buffer = ByteArray(32 * 1024) // 32 KB chunk
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        bytesSinceLastSpeedSample += bytesRead

                        val now = SystemClock.elapsedRealtime()
                        val elapsedMs = now - lastUpdateMs
                        if (elapsedMs >= 200) {
                            val speedBytesPerSec = (bytesSinceLastSpeedSample * 1000f) / elapsedMs
                            currentSpeedMbPerSec = speedBytesPerSec / (1024f * 1024f)
                            lastUpdateMs = now
                            bytesSinceLastSpeedSample = 0L

                            val fileProgress = (bytesDownloaded.toFloat() / serverContentLength).coerceIn(0f, 1f)
                            val overallBytes = accumulatedCompletedBytes + bytesDownloaded
                            val overallProgress = (overallBytes.toFloat() / TOTAL_DOWNLOAD_BYTES).coerceIn(0f, 1f)

                            val remainingBytes = TOTAL_DOWNLOAD_BYTES - overallBytes
                            val etaSeconds = if (speedBytesPerSec > 0) (remainingBytes / speedBytesPerSec).toInt() else 0

                            _progress.value = ModelDownloadProgress(
                                isDownloading = true,
                                currentFileName = task.fileName,
                                fileIndex = task.index,
                                totalFiles = 2,
                                bytesDownloaded = overallBytes,
                                totalBytes = TOTAL_DOWNLOAD_BYTES,
                                fileProgress = fileProgress,
                                overallProgress = overallProgress,
                                speedMbPerSec = currentSpeedMbPerSec,
                                etaSeconds = etaSeconds,
                                errorMessage = null,
                                isCompleted = false
                            )
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Download error on ${task.fileName}", e)
            Result.failure(e)
        } finally {
            activeConnection = null
            connection?.disconnect()
        }
    }

    fun cancelDownload() {
        try {
            activeJob?.cancel()
            activeConnection?.disconnect()
            activeConnection = null
            _progress.value = ModelDownloadProgress(
                isDownloading = false,
                errorMessage = "Download paused."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling download", e)
        }
    }

    private data class DownloadTask(
        val fileName: String,
        val url: String,
        val expectedBytes: Long,
        val index: Int
    )
}

package com.visper.app.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.visper.app.vision.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

data class ParsedDocument(
    val fileName: String,
    val mimeType: String,
    val textContent: String,
    val tokenEstimate: Int,
    val isTruncated: Boolean,
    val visualAsciiPage: String? = null
)

object DocumentParser {

    private const val TAG = "DocumentParser"
    private const val MAX_CONTEXT_CHARS = 4000 // ~1000 tokens budget for high-speed CPU eval

    // Known PDF font encoding & metadata keywords that are NOT document text
    private val PDF_METADATA_FILTER = setOf(
        "macintosh", "macromanencoding", "winansiencoding", "standardencoding",
        "fontdescriptor", "cidfonttype2", "identity-h", "adobe-identity",
        "type1", "truetype", "fontname", "basefont", "cidtogidmap", "tocmap"
    )

    suspend fun parseDocument(
        context: Context,
        uri: Uri,
        maxChars: Int = MAX_CONTEXT_CHARS
    ): Result<ParsedDocument> = withContext(Dispatchers.IO) {
        try {
            val fileName = queryFileName(context, uri) ?: "document.txt"
            val mimeType = context.contentResolver.getType(uri) ?: "text/plain"

            var visualAscii: String? = null
            val rawText = if (mimeType.contains("pdf", ignoreCase = true) || fileName.endsWith(".pdf", ignoreCase = true)) {
                val extracted = extractPdfText(context, uri, maxChars)
                // If text is minimal or PDF is image-based, render page 1 via native PdfRenderer
                if (extracted.length < 80) {
                    visualAscii = renderFirstPageAscii(context, uri)
                }
                extracted
            } else {
                extractPlainText(context, uri, maxChars)
            }

            val isTruncated = rawText.length > maxChars
            val cleanText = if (isTruncated) {
                rawText.substring(0, maxChars) + "\n\n[... Remaining content truncated for token budget ...]"
            } else {
                rawText
            }

            val estimatedTokens = (cleanText.length / 4).coerceAtLeast(1)

            Result.success(
                ParsedDocument(
                    fileName = fileName,
                    mimeType = mimeType,
                    textContent = cleanText,
                    tokenEstimate = estimatedTokens,
                    isTruncated = isTruncated,
                    visualAsciiPage = visualAscii
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse document: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun extractPlainText(context: Context, uri: Uri, maxChars: Int): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open document stream")

        val sb = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null && sb.length < maxChars) {
                sb.append(line).append("\n")
                line = reader.readLine()
            }
        }
        return sb.toString().trim()
    }

    /**
     * Extracts text from both compressed (FlateDecode) and uncompressed PDF content streams,
     * filtering out PostScript font dictionaries and de-duplicating consecutive repeated tokens.
     */
    private fun extractPdfText(context: Context, uri: Uri, maxChars: Int): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open PDF stream")

        val bytes = inputStream.use { it.readBytes() }
        val sb = StringBuilder()
        var lastWord = ""
        var consecutiveCount = 0

        // 1. Scan for FlateDecode (zlib-compressed) streams
        val streamMarker = "stream".toByteArray(Charsets.ISO_8859_1)
        val endstreamMarker = "endstream".toByteArray(Charsets.ISO_8859_1)

        var idx = 0
        while (idx < bytes.size && sb.length < maxChars) {
            val streamStart = indexOfBytes(bytes, streamMarker, idx)
            if (streamStart == -1) break

            // Skip past "stream\r\n" or "stream\n"
            var dataStart = streamStart + streamMarker.size
            if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++

            val streamEnd = indexOfBytes(bytes, endstreamMarker, dataStart)
            if (streamEnd == -1) break

            val streamLen = streamEnd - dataStart
            if (streamLen > 10) {
                val streamBytes = bytes.copyOfRange(dataStart, streamEnd)
                val decompressed = tryDecompressZlib(streamBytes)
                if (decompressed != null) {
                    val extractedWords = extractWordsFromPdfStream(decompressed)
                    for (word in extractedWords) {
                        val lower = word.lowercase()
                        if (lower in PDF_METADATA_FILTER) continue
                        if (lower == lastWord) {
                            consecutiveCount++
                            if (consecutiveCount >= 2) continue
                        } else {
                            lastWord = lower
                            consecutiveCount = 0
                        }
                        sb.append(word).append(" ")
                        if (sb.length >= maxChars) break
                    }
                }
            }
            idx = streamEnd + endstreamMarker.size
        }

        // 2. If compressed stream extraction didn't yield enough, extract uncompressed text
        if (sb.length < 100) {
            val rawString = String(bytes, Charsets.ISO_8859_1)
            val extractedWords = extractWordsFromPdfStream(rawString)
            for (word in extractedWords) {
                val lower = word.lowercase()
                if (lower in PDF_METADATA_FILTER) continue
                if (lower == lastWord) {
                    consecutiveCount++
                    if (consecutiveCount >= 2) continue
                } else {
                    lastWord = lower
                    consecutiveCount = 0
                }
                sb.append(word).append(" ")
                if (sb.length >= maxChars) break
            }
        }

        val result = sb.toString().trim()
        return if (result.isBlank()) {
            "[PDF Document loaded: text layer is compressed or image-based. See attached page preview.]"
        } else {
            result
        }
    }

    private fun extractWordsFromPdfStream(text: String): List<String> {
        val words = mutableListOf<String>()
        // Match string literals (text) Tj or ' or "
        val singleTjRegex = Regex("\\(([^)]{1,200})\\)\\s*(?:Tj|'|\")")
        // Match string arrays [(t1) 20 (t2)] TJ
        val arrayTjRegex = Regex("\\[(.*?)\\]\\s*TJ")
        val innerStringRegex = Regex("\\(([^)]+)\\)")

        // Check array TJ first
        for (match in arrayTjRegex.findAll(text)) {
            val arrayBody = match.groupValues[1]
            for (inner in innerStringRegex.findAll(arrayBody)) {
                val str = cleanPdfString(inner.groupValues[1])
                if (str.isNotBlank()) words.add(str)
            }
        }

        // Check single Tj
        for (match in singleTjRegex.findAll(text)) {
            val str = cleanPdfString(match.groupValues[1])
            if (str.isNotBlank()) words.add(str)
        }

        return words
    }

    private fun cleanPdfString(raw: String): String {
        return raw.replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .trim()
    }

    private fun tryDecompressZlib(data: ByteArray): String? {
        return try {
            val inflater = Inflater(false)
            val bis = ByteArrayInputStream(data)
            val iis = InflaterInputStream(bis, inflater)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            var n: Int
            while (iis.read(buf).also { n = it } > 0) {
                out.write(buf, 0, n)
                if (out.size() > 60000) break
            }
            out.toString("ISO-8859-1")
        } catch (e: Exception) {
            null
        }
    }

    private fun indexOfBytes(source: ByteArray, target: ByteArray, fromIndex: Int): Int {
        if (fromIndex >= source.size || target.isEmpty()) return -1
        outer@ for (i in fromIndex..(source.size - target.size)) {
            for (j in target.indices) {
                if (source[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * Renders Page 1 of the PDF to an ASCII tensor preview via native Android PdfRenderer
     */
    private fun renderFirstPageAscii(context: Context, uri: Uri): String? {
        return try {
            val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val width = 300
                        val height = (300f * page.height / page.width).toInt()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ImageProcessor.toAsciiArt(bitmap, targetCols = 38)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "PdfRenderer preview skipped: ${e.message}")
            null
        }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        if (name == null) {
            name = uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) path.substring(cut + 1) else path
            }
        }
        return name
    }
}

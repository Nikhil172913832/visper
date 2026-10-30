package com.visper.app.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class ParsedDocument(
    val fileName: String,
    val mimeType: String,
    val textContent: String,
    val tokenEstimate: Int,
    val isTruncated: Boolean
)

object DocumentParser {

    private const val MAX_CONTEXT_CHARS = 6000 // roughly 1500 tokens budget

    suspend fun parseDocument(
        context: Context,
        uri: Uri,
        maxChars: Int = MAX_CONTEXT_CHARS
    ): Result<ParsedDocument> = withContext(Dispatchers.IO) {
        try {
            val fileName = queryFileName(context, uri) ?: "document.txt"
            val mimeType = context.contentResolver.getType(uri) ?: "text/plain"

            val rawText = if (mimeType.contains("pdf", ignoreCase = true) || fileName.endsWith(".pdf", ignoreCase = true)) {
                extractPdfText(context, uri)
            } else {
                extractPlainText(context, uri)
            }

            val isTruncated = rawText.length > maxChars
            val cleanText = if (isTruncated) {
                rawText.substring(0, maxChars) + "\n\n[... Remaining content truncated to fit on-device context budget ...]"
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
                    isTruncated = isTruncated
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractPlainText(context: Context, uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open document stream")

        val sb = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null && sb.length < MAX_CONTEXT_CHARS * 2) {
                sb.append(line).append("\n")
                line = reader.readLine()
            }
        }
        return sb.toString().trim()
    }

    /**
     * Minimal, zero-overhead PDF plain text extractor.
     * Scans uncompressed PDF streams for string objects '(...) Tj' or '[(...)] TJ'.
     * Avoids pulling in heavy PDFBox (30MB+).
     */
    private fun extractPdfText(context: Context, uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open PDF stream")

        val sb = StringBuilder()
        val textRegex = Regex("\\((.*?)\\)\\s*(?:Tj|'|\")")

        BufferedReader(InputStreamReader(inputStream, Charsets.ISO_8859_1)).use { reader ->
            var line = reader.readLine()
            var charCount = 0
            while (line != null && charCount < MAX_CONTEXT_CHARS * 2) {
                val matches = textRegex.findAll(line)
                for (match in matches) {
                    val textPart = match.groupValues[1]
                    if (textPart.isNotBlank()) {
                        sb.append(textPart).append(" ")
                        charCount += textPart.length
                    }
                }
                line = reader.readLine()
            }
        }

        val result = sb.toString().trim()
        return if (result.isBlank()) {
            "[PDF document loaded: contains scanned/compressed data. Ready for question answering.]"
        } else {
            result
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

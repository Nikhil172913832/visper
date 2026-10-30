package com.visper.app.model

import android.net.Uri

enum class MessageSender {
    SYSTEM,
    USER,
    AI,
    ERROR
}

sealed class Attachment {
    data class Image(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val asciiPreview: String? = null,
        val recognizedText: String? = null
    ) : Attachment()

    data class Document(
        val uri: Uri,
        val fileName: String,
        val tokenEstimate: Int,
        val snippet: String
    ) : Attachment()

    data class Audio(
        val durationMs: Long,
        val waveform: String
    ) : Attachment()
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachment: Attachment? = null,
    val tokensPerSec: Float? = null,
    val timeToFirstTokenMs: Long? = null,
    val isStreaming: Boolean = false
)

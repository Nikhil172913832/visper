package com.visper.app.session

import android.content.Context
import android.net.Uri
import com.visper.app.model.Attachment
import com.visper.app.model.ChatMessage
import com.visper.app.model.MessageSender
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SessionMetadata(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList()
)

class ChatSessionManager(private val context: Context) {

    private val sessionsDir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val indexFile = File(sessionsDir, "sessions_index.json")

    @Synchronized
    fun getSessionList(): List<SessionMetadata> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val jsonArray = JSONArray(indexFile.readText())
            val list = mutableListOf<SessionMetadata>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SessionMetadata(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                        messageCount = obj.optInt("messageCount", 0)
                    )
                )
            }
            list.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            android.util.Log.e("ChatSessionManager", "Error reading session index", e)
            emptyList()
        }
    }

    @Synchronized
    fun loadSession(sessionId: String): ChatSession? {
        val file = File(sessionsDir, "$sessionId.json")
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            val id = obj.getString("id")
            val title = obj.optString("title", "New Chat")
            val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            val updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
            val msgsArray = obj.getJSONArray("messages")

            val messages = mutableListOf<ChatMessage>()
            for (i in 0 until msgsArray.length()) {
                val mObj = msgsArray.getJSONObject(i)
                val senderStr = mObj.getString("sender")
                val sender = try {
                    MessageSender.valueOf(senderStr)
                } catch (_: Exception) {
                    MessageSender.AI
                }

                var attachment: Attachment? = null
                if (mObj.has("attachmentType")) {
                    val type = mObj.getString("attachmentType")
                    val uri = Uri.parse(mObj.optString("attachmentUri", ""))
                    if (type == "IMAGE") {
                        attachment = Attachment.Image(
                            uri = uri,
                            width = mObj.optInt("imgWidth", 0),
                            height = mObj.optInt("imgHeight", 0)
                        )
                    } else if (type == "DOC") {
                        attachment = Attachment.Document(
                            uri = uri,
                            fileName = mObj.optString("docFileName", "Document"),
                            tokenEstimate = mObj.optInt("docTokenEstimate", 0),
                            snippet = mObj.optString("docSnippet", "")
                        )
                    } else if (type == "AUDIO") {
                        attachment = Attachment.Audio(
                            durationMs = mObj.optLong("audioDuration", 0L),
                            waveform = mObj.optString("audioWaveform", "")
                        )
                    }
                }

                val action = if (mObj.has("actionType")) {
                    com.visper.app.model.AgentAction(
                        actionType = mObj.getString("actionType"),
                        title = mObj.optString("actionTitle", ""),
                        detail = mObj.optString("actionDetail", ""),
                        isSuccess = mObj.optBoolean("actionSuccess", true)
                    )
                } else null

                messages.add(
                    ChatMessage(
                        id = mObj.optString("id", UUID.randomUUID().toString()),
                        sender = sender,
                        text = mObj.optString("text", ""),
                        timestamp = mObj.optLong("timestamp", System.currentTimeMillis()),
                        timeToFirstTokenMs = if (mObj.has("ttftMs")) mObj.getLong("ttftMs") else null,
                        attachment = attachment,
                        action = action
                    )
                )
            }

            ChatSession(
                id = id,
                title = title,
                createdAt = createdAt,
                updatedAt = updatedAt,
                messages = messages
            )
        } catch (e: Exception) {
            android.util.Log.e("ChatSessionManager", "Error loading session $sessionId", e)
            null
        }
    }

    @Synchronized
    fun saveSession(session: ChatSession) {
        try {
            val file = File(sessionsDir, "${session.id}.json")
            val obj = JSONObject().apply {
                put("id", session.id)
                put("title", session.title)
                put("createdAt", session.createdAt)
                put("updatedAt", session.updatedAt)

                val msgsArray = JSONArray()
                for (msg in session.messages) {
                    val mObj = JSONObject().apply {
                        put("id", msg.id)
                        put("sender", msg.sender.name)
                        put("text", msg.text)
                        put("timestamp", msg.timestamp)
                        msg.timeToFirstTokenMs?.let { put("ttftMs", it) }

                        when (val att = msg.attachment) {
                            is Attachment.Image -> {
                                put("attachmentType", "IMAGE")
                                put("attachmentUri", att.uri.toString())
                                put("imgWidth", att.width)
                                put("imgHeight", att.height)
                            }
                            is Attachment.Document -> {
                                put("attachmentType", "DOC")
                                put("attachmentUri", att.uri.toString())
                                put("docFileName", att.fileName)
                                put("docTokenEstimate", att.tokenEstimate)
                                put("docSnippet", att.snippet)
                            }
                            is Attachment.Audio -> {
                                put("attachmentType", "AUDIO")
                                put("audioDuration", att.durationMs)
                                put("audioWaveform", att.waveform)
                            }
                            null -> {}
                        }

                        msg.action?.let { act ->
                            put("actionType", act.actionType)
                            put("actionTitle", act.title)
                            put("actionDetail", act.detail)
                            put("actionSuccess", act.isSuccess)
                        }
                    }
                    msgsArray.put(mObj)
                }
                put("messages", msgsArray)
            }

            file.writeText(obj.toString())

            // Update index
            val currentList = getSessionList().toMutableList()
            val existingIndex = currentList.indexOfFirst { it.id == session.id }
            val meta = SessionMetadata(
                id = session.id,
                title = session.title,
                updatedAt = session.updatedAt,
                messageCount = session.messages.size
            )

            if (existingIndex >= 0) {
                currentList[existingIndex] = meta
            } else {
                currentList.add(0, meta)
            }

            val indexArray = JSONArray()
            for (item in currentList.sortedByDescending { it.updatedAt }) {
                indexArray.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("updatedAt", item.updatedAt)
                        put("messageCount", item.messageCount)
                    }
                )
            }
            indexFile.writeText(indexArray.toString())
        } catch (e: Exception) {
            android.util.Log.e("ChatSessionManager", "Error saving session ${session.id}", e)
        }
    }

    @Synchronized
    fun deleteSession(sessionId: String) {
        try {
            val file = File(sessionsDir, "$sessionId.json")
            if (file.exists()) file.delete()

            val currentList = getSessionList().filter { it.id != sessionId }
            val indexArray = JSONArray()
            for (item in currentList) {
                indexArray.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("updatedAt", item.updatedAt)
                        put("messageCount", item.messageCount)
                    }
                )
            }
            indexFile.writeText(indexArray.toString())
        } catch (e: Exception) {
            android.util.Log.e("ChatSessionManager", "Error deleting session $sessionId", e)
        }
    }

    fun generateTitleFromPrompt(prompt: String): String {
        val clean = prompt.trim().replace("\n", " ")
        return if (clean.length > 30) {
            clean.take(28).trim() + "…"
        } else if (clean.isNotBlank()) {
            clean
        } else {
            "New Chat"
        }
    }
}

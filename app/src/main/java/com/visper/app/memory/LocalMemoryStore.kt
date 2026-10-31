package com.visper.app.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class MemoryFact(
    val id: String = UUID.randomUUID().toString(),
    val fact: String,
    val createdAt: Long = System.currentTimeMillis()
)

class LocalMemoryStore(private val context: Context) {

    private val memoryFile = File(context.filesDir, "memory_vault.json")

    @Synchronized
    fun getMemories(): List<MemoryFact> {
        if (!memoryFile.exists()) return emptyList()
        return try {
            val array = JSONArray(memoryFile.readText())
            val list = mutableListOf<MemoryFact>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    MemoryFact(
                        id = obj.getString("id"),
                        fact = obj.getString("fact"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            list.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            android.util.Log.e("LocalMemoryStore", "Failed to read memories", e)
            emptyList()
        }
    }

    @Synchronized
    fun addMemory(fact: String): MemoryFact {
        val cleanFact = fact.trim()
        val current = getMemories().toMutableList()
        // Deduplicate
        val existing = current.find { it.fact.equals(cleanFact, ignoreCase = true) }
        if (existing != null) return existing

        val newFact = MemoryFact(fact = cleanFact)
        current.add(0, newFact)
        saveMemories(current)
        return newFact
    }

    @Synchronized
    fun deleteMemory(id: String) {
        val updated = getMemories().filter { it.id != id }
        saveMemories(updated)
    }

    @Synchronized
    fun clearAllMemories() {
        if (memoryFile.exists()) memoryFile.delete()
    }

    private fun saveMemories(list: List<MemoryFact>) {
        try {
            val array = JSONArray()
            for (item in list) {
                array.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("fact", item.fact)
                        put("createdAt", item.createdAt)
                    }
                )
            }
            memoryFile.writeText(array.toString())
        } catch (e: Exception) {
            android.util.Log.e("LocalMemoryStore", "Failed to save memories", e)
        }
    }

    fun getMemoryPromptContext(): String {
        val memories = getMemories()
        if (memories.isEmpty()) return ""
        return buildString {
            append("\n[User Personal Profile & Stored Facts]:\n")
            for (m in memories.take(8)) {
                append("- ").append(m.fact).append("\n")
            }
            append("When the user asks about their identity, name, or preferences, use these stored facts directly to address the user (e.g., 'Your name is ...').\n")
        }
    }

    fun extractPotentialMemory(userPrompt: String): String? {
        val p = userPrompt.trim()
        val lower = p.lowercase()

        val triggers = listOf(
            "remember that ",
            "remember: ",
            "remember ",
            "my name is ",
            "i am a ",
            "i study ",
            "i am studying ",
            "i prefer ",
            "always reply in ",
            "always respond in "
        )

        for (trigger in triggers) {
            val idx = lower.indexOf(trigger)
            if (idx != -1) {
                val candidate = p.substring(idx + trigger.length).trim()
                if (candidate.length in 2..120) {
                    return when (trigger.trim()) {
                        "remember that", "remember:", "remember" -> candidate
                        "my name is" -> "The user's name is ${candidate.takeWhile { it != '.' && it != ',' }}"
                        "i am a" -> "The user is a $candidate"
                        "i study", "i am studying" -> "The user studies $candidate"
                        "i prefer" -> "The user prefers $candidate"
                        "always reply in", "always respond in" -> "Prefer responses in $candidate"
                        else -> candidate
                    }
                }
            }
        }
        return null
    }
}

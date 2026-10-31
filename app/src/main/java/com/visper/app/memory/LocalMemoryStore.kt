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

    fun getStoredUserName(): String? {
        for (m in getMemories()) {
            val f = m.fact.trim()
            if (f.startsWith("Name:", ignoreCase = true)) {
                val candidate = f.substring(5).trim()
                if (candidate.isNotBlank()) return candidate
            }
            if (f.startsWith("The user's name is", ignoreCase = true)) {
                val candidate = f.substring(18).trim()
                if (candidate.isNotBlank()) return candidate
            }
        }
        return null
    }

    fun getMemoryPromptContext(): String {
        val memories = getMemories()
        if (memories.isEmpty()) return ""
        val storedName = getStoredUserName()
        return buildString {
            append("\n[Profile of the Human User]:\n")
            for (m in memories.take(8)) {
                val cleanFact = when {
                    m.fact.startsWith("The user's name is ", ignoreCase = true) -> "Name: " + m.fact.substring(19)
                    m.fact.startsWith("The user is a ", ignoreCase = true) -> "Role: " + m.fact.substring(14)
                    m.fact.startsWith("The user is ", ignoreCase = true) -> "Role: " + m.fact.substring(12)
                    m.fact.startsWith("The user studies ", ignoreCase = true) -> "Studies: " + m.fact.substring(17)
                    m.fact.startsWith("The user prefers ", ignoreCase = true) -> "Preference: " + m.fact.substring(17)
                    else -> m.fact
                }
                append("- ").append(cleanFact).append("\n")
            }
            append("\nRULES:\n")
            append("1. Your name is Visper. You are the AI assistant.\n")
            if (storedName != null) {
                append("2. The human user's name is $storedName. Never call yourself $storedName.\n")
                append("3. When asked \"What is my name?\" or \"Who am I?\", tell the user: \"Your name is $storedName.\"\n")
            } else {
                append("2. Never adopt the user's name or identity. When answering questions about the user, address them using \"You\" based on the profile above.\n")
            }
            append("4. For greetings, introduce yourself only as Visper.\n")
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
                        "my name is" -> "Name: ${candidate.takeWhile { it != '.' && it != ',' }}"
                        "i am a" -> "Role: $candidate"
                        "i study", "i am studying" -> "Studies: $candidate"
                        "i prefer" -> "Preference: $candidate"
                        "always reply in", "always respond in" -> "Prefer responses in $candidate"
                        else -> candidate
                    }
                }
            }
        }
        return null
    }
}

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
            val seenCategories = mutableSetOf<String>()
            val seenFacts = mutableSetOf<String>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val rawFact = obj.getString("fact")
                val cleanFact = normalizeFact(rawFact)
                val category = getMemoryCategory(cleanFact)

                // Category-level deduplication (e.g., keep newest Name/Role)
                if (category != null) {
                    if (seenCategories.contains(category)) continue
                    seenCategories.add(category)
                }

                // Exact fact deduplication
                val lowerFact = cleanFact.lowercase()
                if (seenFacts.contains(lowerFact)) continue
                seenFacts.add(lowerFact)

                list.add(
                    MemoryFact(
                        id = obj.getString("id"),
                        fact = cleanFact,
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            val sorted = list.sortedByDescending { it.createdAt }
            // If cleaned/normalized differs from raw count, persist back
            if (sorted.size != array.length()) {
                saveMemories(sorted)
            }
            sorted
        } catch (e: Exception) {
            android.util.Log.e("LocalMemoryStore", "Failed to read memories", e)
            emptyList()
        }
    }

    @Synchronized
    fun addMemory(fact: String): MemoryFact {
        val cleanFact = normalizeFact(fact)
        val current = getMemories().toMutableList()

        val category = getMemoryCategory(cleanFact)
        if (category != null) {
            // Replace any existing fact of this same category (e.g., updating Name or Role)
            current.removeAll { getMemoryCategory(it.fact) == category }
        } else {
            // Exact deduplicate
            val existing = current.find { it.fact.equals(cleanFact, ignoreCase = true) }
            if (existing != null) return existing
        }

        val newFact = MemoryFact(fact = cleanFact)
        current.add(0, newFact)
        saveMemories(current)
        return newFact
    }

    fun normalizeFact(fact: String): String {
        val trim = fact.trim()
        return when {
            trim.startsWith("The user's name is ", ignoreCase = true) -> "Name: " + trim.substring(19).trim()
            trim.startsWith("User name is ", ignoreCase = true) -> "Name: " + trim.substring(13).trim()
            trim.startsWith("The user is a ", ignoreCase = true) -> "Role: " + trim.substring(14).trim()
            trim.startsWith("The user is ", ignoreCase = true) -> "Role: " + trim.substring(12).trim()
            trim.startsWith("The user studies ", ignoreCase = true) -> "Studies: " + trim.substring(17).trim()
            trim.startsWith("The user prefers ", ignoreCase = true) -> "Preference: " + trim.substring(17).trim()
            else -> trim
        }
    }

    private fun getMemoryCategory(fact: String): String? {
        val lower = normalizeFact(fact).lowercase()
        return when {
            lower.startsWith("name:") -> "name"
            lower.startsWith("role:") || lower.startsWith("profession:") -> "role"
            lower.startsWith("studies:") -> "studies"
            else -> null
        }
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
        }
        return null
    }

    fun getMemoryPromptContext(): String {
        val memories = getMemories()
        if (memories.isEmpty()) return ""
        val storedName = getStoredUserName()
        return buildString {
            append("\n[User Personal Information]:\n")
            if (storedName != null) {
                append("- The user's name is ").append(storedName).append(".\n")
            }
            for (m in memories.take(8)) {
                if (storedName != null && m.fact.startsWith("Name:", ignoreCase = true)) continue
                append("- ").append(m.fact).append("\n")
            }
            append("\nIdentity Rules:\n")
            append("- Your name is Visper. You are the AI assistant.\n")
            if (storedName != null) {
                append("- The human user you are speaking to is $storedName. Never claim to be $storedName.\n")
                append("- When asked who they are or what their name is, tell the user: \"Your name is $storedName.\"\n")
            } else {
                append("- When asked about the user, address them using \"You\" based on the user information above.\n")
            }
            append("- For greetings, introduce yourself as Visper.\n")
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

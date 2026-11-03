package com.visper.app.model

enum class SupportedLanguage(val code: String, val label: String) {
    ENGLISH("en", "English"),
    HINDI("hi", "Hindi (हिंदी)"),
    PUNJABI("pa", "Punjabi (ਪੰਜਾਬੀ)")
}

/**
 * Intelligent on-device language and script detector.
 * Accurately routes user prompts to English, Hindi, or Punjabi (including Hinglish and Romanized dialects)
 * so the on-device model always responds in the exact language the user used.
 */
object LanguageDetector {

    // Common Hinglish / Romanized Hindi conversational keywords
    private val HINGLISH_KEYWORDS = setOf(
        "kya", "kaise", "batao", "bataiye", "bataao", "hai", "hain", "haan", "nahi", "nahin",
        "mujhe", "mera", "meri", "mere", "tum", "tumhara", "tumhari", "aap", "aapka", "aapki",
        "karo", "kariye", "karna", "chahiye", "suno", "samjhao", "shukriya", "dhanyawad",
        "namaste", "namaskar", "theek", "thik", "accha", "achha", "bahut", "kaun", "kaha",
        "kahan", "kab", "kyun", "kyu", "madad", "sahayata", "bhai", "yaar", "bolo"
    )

    // Common Romanized Punjabi conversational keywords
    private val ROMAN_PUNJABI_KEYWORDS = setOf(
        "kiddan", "kidda", "tussi", "tuhada", "tuhadi", "mainu", "tenu", "assi", "changa",
        "haanji", "daso", "dasso", "kive", "kiven", "hunda", "hundi", "veere", "paaji",
        "rab", "sat", "sri", "akaal", "shonk", "vadhia", "ki", "gal"
    )

    fun detect(text: String, voiceLanguageSetting: String = "auto"): SupportedLanguage {
        // If the user has locked their voice language to a specific choice (e.g. from the Voice Mode sheet)
        if (voiceLanguageSetting.equals("hi", ignoreCase = true)) return SupportedLanguage.HINDI
        if (voiceLanguageSetting.equals("pa", ignoreCase = true)) return SupportedLanguage.PUNJABI
        if (voiceLanguageSetting.equals("en", ignoreCase = true)) return SupportedLanguage.ENGLISH

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SupportedLanguage.ENGLISH

        var gurmukhiCount = 0
        var devanagariCount = 0
        var latinCount = 0

        for (ch in trimmed) {
            val code = ch.code
            when {
                code in 0x0A00..0x0A7F -> gurmukhiCount++
                code in 0x0900..0x097F -> devanagariCount++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latinCount++
            }
        }

        // 1. Script checks (Devanagari vs Gurmukhi)
        if (gurmukhiCount > 0 && gurmukhiCount >= devanagariCount) {
            return SupportedLanguage.PUNJABI
        }
        if (devanagariCount > 0) {
            return SupportedLanguage.HINDI
        }

        // 2. Latin script: check for Hinglish or Romanized Punjabi
        if (latinCount > 0) {
            val words = trimmed.lowercase()
                .split(Regex("[^a-zA-Z]+"))
                .filter { it.length > 1 }

            if (words.isNotEmpty()) {
                val punjabiMatches = words.count { it in ROMAN_PUNJABI_KEYWORDS }
                if (punjabiMatches >= 2 || (words.size <= 3 && punjabiMatches >= 1)) {
                    return SupportedLanguage.PUNJABI
                }

                val hinglishMatches = words.count { it in HINGLISH_KEYWORDS }
                if (hinglishMatches >= 2 || (words.size <= 4 && hinglishMatches >= 1)) {
                    return SupportedLanguage.HINDI
                }
            }
        }

        // 3. Pure English / default
        return SupportedLanguage.ENGLISH
    }
}

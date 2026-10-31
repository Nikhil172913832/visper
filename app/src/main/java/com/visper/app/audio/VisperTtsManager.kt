package com.visper.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class VisperTtsManager(private val context: Context) {

    companion object {
        private const val TAG = "VisperTtsManager"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false

    var preferredLanguage: String = "auto"
    private var currentAppliedLocale: Locale? = null

    private val queuedUtterances = AtomicInteger(0)
    private var isSessionFinalized = false
    private var sessionCompletionCallback: (() -> Unit)? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val defaultLoc = Locale.getDefault()
                    val langResult = tts?.setLanguage(defaultLoc)
                    if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Default locale not supported, falling back to US")
                        tts?.setLanguage(Locale.US)
                        currentAppliedLocale = Locale.US
                    } else {
                        currentAppliedLocale = defaultLoc
                    }
                    tts?.setSpeechRate(1.05f)
                    tts?.setPitch(1.0f)
                    isReady = true
                    Log.i(TAG, "TextToSpeech initialized successfully with initial locale: $currentAppliedLocale")

                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            val remaining = queuedUtterances.decrementAndGet()
                            Log.d(TAG, "Utterance completed: $utteranceId, remaining: $remaining, finalized: $isSessionFinalized")
                            if (remaining <= 0 && isSessionFinalized) {
                                _isSpeaking.value = false
                                val cb = sessionCompletionCallback
                                sessionCompletionCallback = null
                                cb?.invoke()
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            val remaining = queuedUtterances.decrementAndGet()
                            Log.w(TAG, "Utterance error: $utteranceId, remaining: $remaining")
                            if (remaining <= 0 && isSessionFinalized) {
                                _isSpeaking.value = false
                                val cb = sessionCompletionCallback
                                sessionCompletionCallback = null
                                cb?.invoke()
                            }
                        }
                    })
                } else {
                    Log.e(TAG, "TextToSpeech init failed with code $status")
                    isReady = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate TextToSpeech", e)
            isReady = false
        }
    }

    /**
     * Starts a streaming audio session, clearing previous queues and setting up completion listener.
     */
    fun startStreamingSession(onDone: (() -> Unit)? = null) {
        stop()
        queuedUtterances.set(0)
        isSessionFinalized = false
        sessionCompletionCallback = onDone
    }

    /**
     * Determines the optimal locale based on user setting or script detection:
     * - Gurmukhi (U+0A00..U+0A7F) -> Punjabi (pa-IN)
     * - Devanagari (U+0900..U+097F) -> Hindi (hi-IN)
     * - Latin/English -> Indian English (en-IN) or system default
     */
    fun detectOptimalLocale(text: String): Locale {
        when (preferredLanguage.lowercase()) {
            "hi" -> return Locale("hi", "IN")
            "pa" -> return Locale("pa", "IN")
            "en" -> return Locale("en", "IN")
        }

        var hasGurmukhi = false
        var hasDevanagari = false

        for (char in text) {
            val code = char.code
            if (code in 0x0A00..0x0A7F) {
                hasGurmukhi = true
                break
            } else if (code in 0x0900..0x097F) {
                hasDevanagari = true
            }
        }

        return when {
            hasGurmukhi -> Locale("pa", "IN")
            hasDevanagari -> Locale("hi", "IN")
            else -> Locale("en", "IN")
        }
    }

    private fun applyLocaleForText(text: String) {
        val ttsEngine = tts ?: return
        val targetLocale = detectOptimalLocale(text)

        if (currentAppliedLocale?.language == targetLocale.language &&
            currentAppliedLocale?.country == targetLocale.country) {
            return
        }

        val candidates = when (targetLocale.language) {
            "pa" -> listOf(Locale("pa", "IN"), Locale("pa"))
            "hi" -> listOf(Locale("hi", "IN"), Locale("hi"))
            "en" -> listOf(Locale("en", "IN"), Locale.US, Locale.getDefault())
            else -> listOf(targetLocale, Locale.getDefault(), Locale.US)
        }

        for (candidate in candidates) {
            val avail = ttsEngine.isLanguageAvailable(candidate)
            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                val res = ttsEngine.setLanguage(candidate)
                if (res >= TextToSpeech.LANG_AVAILABLE) {
                    currentAppliedLocale = candidate
                    Log.i(TAG, "Switched TTS voice locale to: ${candidate.toLanguageTag()}")
                    return
                }
            }
        }
        Log.w(TAG, "Locale ${targetLocale.toLanguageTag()} not fully available on device TTS engine")
    }

    /**
     * Enqueues a single sentence or clause while the LLM is actively streaming tokens.
     * Uses QUEUE_FLUSH for the very first sentence to start speaking immediately,
     * and QUEUE_ADD for subsequent sentences to chain seamlessly without gaps.
     */
    fun enqueueStreamingChunk(text: String) {
        if (!isReady || text.isBlank()) return

        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return

        applyLocaleForText(clean)

        val currentCount = queuedUtterances.getAndIncrement()
        val queueMode = if (currentCount == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "stream_chunk_${System.currentTimeMillis()}_$currentCount"

        _isSpeaking.value = true
        val res = tts?.speak(clean, queueMode, null, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            Log.w(TAG, "tts.speak returned non-success code $res for chunk: $clean")
            queuedUtterances.decrementAndGet()
        }
    }

    /**
     * Finalizes the streaming session, optionally pushing any remaining trailing text.
     */
    fun finishStreamingSession(remainingText: String = "") {
        if (remainingText.isNotBlank()) {
            enqueueStreamingChunk(remainingText)
        }
        isSessionFinalized = true
        if (queuedUtterances.get() <= 0) {
            _isSpeaking.value = false
            val cb = sessionCompletionCallback
            sessionCompletionCallback = null
            cb?.invoke()
        }
    }

    /**
     * One-shot speech helper for instant replies or agentic action confirmations.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        startStreamingSession(onDone)
        finishStreamingSession(text)
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS", e)
        }
        queuedUtterances.set(0)
        isSessionFinalized = true
        _isSpeaking.value = false
        sessionCompletionCallback = null
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS", e)
        }
        tts = null
        isReady = false
    }

    private fun cleanForSpeech(text: String): String {
        return text
            .replace(Regex("[*#_~`>]"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\(https?://[^)]+\\)"), "")
            .replace("।", ". ")
            .replace("॥", ". ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

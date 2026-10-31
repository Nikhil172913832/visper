package com.visper.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VisperTtsManager(private val context: Context) {

    companion object {
        private const val TAG = "VisperTtsManager"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingUtteranceCallback: (() -> Unit)? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val langResult = tts?.setLanguage(Locale.US)
                    if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Locale.US not supported, falling back to default locale")
                        tts?.setLanguage(Locale.getDefault())
                    }
                    tts?.setSpeechRate(1.05f)
                    tts?.setPitch(1.0f)
                    isReady = true
                    Log.i(TAG, "TextToSpeech initialized successfully")

                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                            val cb = pendingUtteranceCallback
                            pendingUtteranceCallback = null
                            cb?.invoke()
                        }

                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            val cb = pendingUtteranceCallback
                            pendingUtteranceCallback = null
                            cb?.invoke()
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

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isReady || text.isBlank()) {
            onDone?.invoke()
            return
        }

        stop()
        pendingUtteranceCallback = onDone

        // Clean text for speech: remove markdown symbols, bullets, asterisks, brackets
        val cleanText = text
            .replace(Regex("[*#_~`>]"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleanText.isBlank()) {
            onDone?.invoke()
            return
        }

        val utteranceId = "visper_${System.currentTimeMillis()}"
        val result = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "tts.speak returned non-success code $result")
            _isSpeaking.value = false
            pendingUtteranceCallback = null
            onDone?.invoke()
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS", e)
        }
        _isSpeaking.value = false
        pendingUtteranceCallback = null
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
}

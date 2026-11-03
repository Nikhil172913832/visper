package com.visper.app.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed class AudioRecordingState {
    data object Idle : AudioRecordingState()
    data class Recording(val rmsDb: Float, val waveformVisual: String) : AudioRecordingState()
    data class Success(val text: String) : AudioRecordingState()
    data class Error(val message: String, val isPackMissing: Boolean = false) : AudioRecordingState()
}

class AudioTranscriptionManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioTranscriptionMgr"
    }

    private var speechRecognizer: SpeechRecognizer? = null

    var preferredLanguage: String = "auto"
    private var fallbackAttempts = 0
    private var isExplicitlyStopped = false

    private val _recordingState = MutableStateFlow<AudioRecordingState>(AudioRecordingState.Idle)
    val recordingState: StateFlow<AudioRecordingState> = _recordingState.asStateFlow()

    private val asciiBars = listOf(" ", " ", "▂", "▃", "▄", "▅", "▆", "▇", "█")

    fun startListening() {
        fallbackAttempts = 0
        isExplicitlyStopped = false
        startInternal(resolveTargetLanguageTag())
    }

    private fun resolveTargetLanguageTag(): String {
        return when (preferredLanguage.lowercase()) {
            "hi" -> "hi-IN"
            "pa" -> "pa-IN"
            "en" -> "en-US" // Use en-US which is pre-installed on Android devices
            else -> {
                // Auto mode: check device locale
                val defaultLoc = Locale.getDefault()
                when {
                    defaultLoc.language.equals("hi", ignoreCase = true) -> "hi-IN"
                    defaultLoc.language.equals("pa", ignoreCase = true) -> "pa-IN"
                    defaultLoc.language.equals("en", ignoreCase = true) -> "en-US"
                    else -> defaultLoc.toLanguageTag()
                }
            }
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        return try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun startInternal(languageTag: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _recordingState.value = AudioRecordingState.Error(
                message = "Speech recognition is not available on this device",
                isPackMissing = false
            )
            return
        }

        stopListeningInternal()
        isExplicitlyStopped = false

        try {
            speechRecognizer = createRecognizer().apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        val visual = if (fallbackAttempts > 0) "  ▂▃ ($languageTag) " else "  ▂▃ "
                        _recordingState.value = AudioRecordingState.Recording(0f, visual)
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {
                        if (isExplicitlyStopped) return
                        val normalized = (rmsdB / 10f).coerceIn(0f, 1f)
                        val barIndex = (normalized * (asciiBars.size - 1)).toInt()
                        val bar = asciiBars[barIndex]
                        val visual = " [REC] $bar$bar$bar$bar "
                        _recordingState.value = AudioRecordingState.Recording(rmsdB, visual)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        if (isExplicitlyStopped) return
                        _recordingState.value = AudioRecordingState.Recording(0f, " [TRANSCRIBING...] ")
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "SpeechRecognizer error: $error for language: $languageTag (fallbackAttempts=$fallbackAttempts, isExplicitlyStopped=$isExplicitlyStopped)")

                        // If user intentionally stopped / cancelled, reset to Idle cleanly
                        if (isExplicitlyStopped || error == SpeechRecognizer.ERROR_CLIENT) {
                            Log.d(TAG, "Speech recognition stopped or cancelled by user, resetting to Idle")
                            _recordingState.value = AudioRecordingState.Idle
                            return
                        }

                        // If no speech detected / timeout, reset to Idle cleanly without noisy errors
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            Log.d(TAG, "No speech detected ($error), resetting to Idle")
                            _recordingState.value = AudioRecordingState.Idle
                            return
                        }

                        // Error 13 = Language pack not downloaded
                        // Error 2 = Network error / offline pack unavailable
                        // Error 12 = Language not supported
                        val isOfflinePackError = (error == 13 || error == SpeechRecognizer.ERROR_NETWORK || error == 12)

                        // Attempt automatic fallback to en-US if requested locale fails offline
                        if (isOfflinePackError && fallbackAttempts < 2) {
                            fallbackAttempts++
                            val fallbackTag = when (fallbackAttempts) {
                                1 -> if (languageTag != "en-US") "en-US" else "en-GB"
                                else -> Locale.getDefault().toLanguageTag()
                            }
                            if (fallbackTag != languageTag) {
                                Log.i(TAG, "Retrying speech recognition with fallback locale: $fallbackTag")
                                _recordingState.value = AudioRecordingState.Recording(0f, " [Trying $fallbackTag...] ")
                                startInternal(fallbackTag)
                                return
                            }
                        }

                        val (errorMsg, isPack) = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error" to false
                            SpeechRecognizer.ERROR_CLIENT -> "Speech recognition cancelled" to false
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Record audio permission denied" to false
                            SpeechRecognizer.ERROR_NETWORK -> "No internet connection and offline speech pack is not downloaded for $languageTag" to true
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network timeout" to false
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized" to false
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy" to false
                            SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error" to false
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected" to false
                            12 -> "Language $languageTag not supported offline by speech engine" to true
                            13 -> "Offline speech pack not downloaded for $languageTag" to true
                            else -> "Recognition error ($error)" to false
                        }
                        _recordingState.value = AudioRecordingState.Error(errorMsg, isPack)
                    }

                    override fun onResults(results: Bundle?) {
                        if (isExplicitlyStopped) return
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val recognizedText = matches?.firstOrNull() ?: ""
                        _recordingState.value = AudioRecordingState.Success(recognizedText)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        if (isExplicitlyStopped) return
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partialText = matches?.firstOrNull() ?: ""
                        if (partialText.isNotBlank()) {
                            _recordingState.value = AudioRecordingState.Recording(5f, " > $partialText")
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "en-IN", "en-GB", "hi-IN", "pa-IN"))
            }

            speechRecognizer?.startListening(intent)
            _recordingState.value = AudioRecordingState.Recording(0f, " [LISTENING...] ")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition", e)
            _recordingState.value = AudioRecordingState.Error("Failed to start speech: ${e.message}", false)
        }
    }

    private fun stopListeningInternal() {
        isExplicitlyStopped = true
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            speechRecognizer = null
        }
    }

    fun stopListening() {
        stopListeningInternal()
        _recordingState.value = AudioRecordingState.Idle
    }

    fun reset() {
        stopListening()
        fallbackAttempts = 0
        _recordingState.value = AudioRecordingState.Idle
    }

    /**
     * Opens Android System Settings directly to the offline voice recognition packs page.
     */
    fun openOfflineVoiceSettings() {
        val intents = listOf(
            // 1. Google App Greco3 Offline Speech Settings (direct + Add a language page)
            Intent().apply {
                component = ComponentName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.voicesearch.greco3.languagepack.InstallActivity"
                )
            },
            // 2. Standard Google Recognition download language intent
            Intent("com.google.recognition.action.DOWNLOAD_LANGUAGE"),
            // 3. Android Voice Input Settings
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            // 4. Android Locale Settings fallback
            Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS)
        )

        for (intent in intents) {
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Could not launch intent $intent: ${e.message}")
            }
        }
    }
}

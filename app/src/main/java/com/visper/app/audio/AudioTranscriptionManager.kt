package com.visper.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed class AudioRecordingState {
    data object Idle : AudioRecordingState()
    data class Recording(val rmsDb: Float, val waveformVisual: String) : AudioRecordingState()
    data class Success(val text: String) : AudioRecordingState()
    data class Error(val message: String) : AudioRecordingState()
}

class AudioTranscriptionManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null

    var preferredLanguage: String = "auto"

    private val _recordingState = MutableStateFlow<AudioRecordingState>(AudioRecordingState.Idle)
    val recordingState: StateFlow<AudioRecordingState> = _recordingState.asStateFlow()

    private val asciiBars = listOf(" ", " ", "▂", "▃", "▄", "▅", "▆", "▇", "█")

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _recordingState.value = AudioRecordingState.Error("Speech recognition not available on this device")
            return
        }

        stopListening()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _recordingState.value = AudioRecordingState.Recording(0f, "  ▂▃ ")
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = (rmsdB / 10f).coerceIn(0f, 1f)
                    val barIndex = (normalized * (asciiBars.size - 1)).toInt()
                    val bar = asciiBars[barIndex]
                    val visual = " [REC] $bar$bar$bar$bar "
                    _recordingState.value = AudioRecordingState.Recording(rmsdB, visual)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _recordingState.value = AudioRecordingState.Recording(0f, " [TRANSCRIBING...] ")
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Record audio permission denied"
                        SpeechRecognizer.ERROR_NETWORK -> "Network required or speech pack unavailable"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                        12 -> "Language not supported by device speech engine"
                        13 -> "Language pack not downloaded for offline speech"
                        else -> "Recognition error ($error)"
                    }
                    _recordingState.value = AudioRecordingState.Error(errorMsg)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val recognizedText = matches?.firstOrNull() ?: ""
                    _recordingState.value = AudioRecordingState.Success(recognizedText)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialText = matches?.firstOrNull() ?: ""
                    if (partialText.isNotBlank()) {
                        _recordingState.value = AudioRecordingState.Recording(5f, " > $partialText")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val targetLangTag = when (preferredLanguage.lowercase()) {
            "hi" -> "hi-IN"
            "pa" -> "pa-IN"
            "en" -> "en-IN"
            else -> {
                // For Auto mode: check if Indian region or Hindi/Punjabi locale
                val defaultLoc = Locale.getDefault()
                val tz = java.util.TimeZone.getDefault().id
                if (defaultLoc.country.equals("IN", ignoreCase = true) ||
                    tz.contains("Calcutta", ignoreCase = true) ||
                    tz.contains("Kolkata", ignoreCase = true) ||
                    defaultLoc.language.equals("hi", ignoreCase = true) ||
                    defaultLoc.language.equals("pa", ignoreCase = true)) {
                    "hi-IN"
                } else {
                    defaultLoc.toLanguageTag()
                }
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLangTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLangTag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Extra languages hint for modern multilingual recognition engines
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-IN", "pa-IN", "en-GB", "en-US"))
        }

        try {
            speechRecognizer?.startListening(intent)
            _recordingState.value = AudioRecordingState.Recording(0f, " [LISTENING...] ")
        } catch (e: Exception) {
            _recordingState.value = AudioRecordingState.Error("Failed to start speech: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // Ignore teardown errors
        } finally {
            speechRecognizer = null
            _recordingState.value = AudioRecordingState.Idle
        }
    }

    fun reset() {
        stopListening()
        _recordingState.value = AudioRecordingState.Idle
    }
}

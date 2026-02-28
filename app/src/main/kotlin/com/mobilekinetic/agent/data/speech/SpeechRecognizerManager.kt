package com.mobilekinetic.agent.data.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps Android SpeechRecognizer for voice-to-text input in ChatScreen.
 * Exposes reactive StateFlows for UI observation.
 *
 * Must be created on the main thread (Compose composition is fine).
 * Call [destroy] when the composable leaves composition.
 */
class SpeechRecognizerManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRecognizerMgr"
        private const val SILENCE_TIMEOUT_MS = 5000L
    }

    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var onPartialResult: ((String) -> Unit)? = null
    private var onFinalResult: ((String) -> Unit)? = null
    private var onListeningStopped: (() -> Unit)? = null

    /** Text accumulated across partial/final results within one listening session. */
    private var accumulatedText = ""

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            _isListening.value = true
            _error.value = null
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) { /* unused */ }
        override fun onBufferReceived(buffer: ByteArray?) { /* unused */ }

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech detected")
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                else -> "Unknown error ($error)"
            }
            Log.w(TAG, "Recognition error: $msg")

            // Silence-related errors → auto-stop without restart
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                Log.d(TAG, "Silence timeout – auto-stopping")
                _isListening.value = false
                onListeningStopped?.invoke()
            } else {
                _error.value = msg
                _isListening.value = false
                onListeningStopped?.invoke()
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val finalText = matches?.firstOrNull() ?: ""
            Log.d(TAG, "Final result: $finalText")

            if (finalText.isNotBlank()) {
                accumulatedText = if (accumulatedText.isBlank()) {
                    finalText
                } else {
                    "$accumulatedText $finalText"
                }
                _recognizedText.value = accumulatedText
                onFinalResult?.invoke(accumulatedText)
            }

            _isListening.value = false
            onListeningStopped?.invoke()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull() ?: ""
            if (partial.isNotBlank()) {
                val fullText = if (accumulatedText.isBlank()) partial else "$accumulatedText $partial"
                _recognizedText.value = fullText
                onPartialResult?.invoke(fullText)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) { /* unused */ }
    }

    /**
     * Begin speech recognition.
     *
     * @param existingText text already in the input field (prepended to results)
     * @param onPartial called with accumulated text on each partial result
     * @param onFinal called with final accumulated text when recognition completes
     * @param onStopped called when listening stops for any reason
     */
    fun startListening(
        existingText: String = "",
        onPartial: (String) -> Unit = {},
        onFinal: (String) -> Unit = {},
        onStopped: () -> Unit = {}
    ) {
        if (_isListening.value) {
            Log.d(TAG, "Already listening – ignoring")
            return
        }

        accumulatedText = existingText
        onPartialResult = onPartial
        onFinalResult = onFinal
        onListeningStopped = onStopped

        if (speechRecognizer == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _error.value = "Speech recognition not available"
                return
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SILENCE_TIMEOUT_MS)
        }

        Log.d(TAG, "Starting recognition (silence=${SILENCE_TIMEOUT_MS}ms)")
        speechRecognizer?.startListening(intent)
    }

    /** Stop listening gracefully; final results will still be delivered. */
    fun stopListening() {
        Log.d(TAG, "Manual stop")
        speechRecognizer?.stopListening()
    }

    /** Cancel listening immediately; no results delivered. */
    fun cancel() {
        Log.d(TAG, "Cancel")
        speechRecognizer?.cancel()
        _isListening.value = false
        onListeningStopped?.invoke()
    }

    /** Release native resources. Call from DisposableEffect onDispose. */
    fun destroy() {
        Log.d(TAG, "Destroy")
        speechRecognizer?.destroy()
        speechRecognizer = null
        _isListening.value = false
    }
}

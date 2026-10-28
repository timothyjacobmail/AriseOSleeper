package com.example.ariseosleeper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Uses the installed speech service while all controls and results stay inside this app. */
internal class AndroidDictationEngine(context: Context) : DictationEngine {
    private val recognizer: SpeechRecognizer
    private val handler = Handler(Looper.getMainLooper())
    private var listener: DictationEngine.Listener? = null
    private val timeout = Runnable { listener?.error("Dictation timed out. Tap Dictate to try again.") }

    init {
        check(SpeechRecognizer.isRecognitionAvailable(context)) {
            "Speech recognition is unavailable. Enable a speech service in Android Settings, or type the passage."
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    }

    override fun start(listener: DictationEngine.Listener) {
        this.listener = listener
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listener.ready() }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { finishing(); listener.processing() }
            override fun onError(error: Int) { listener.error(errorMessage(error)) }
            override fun onResults(results: Bundle?) {
                listener.result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onPartialResults(partialResults: Bundle?) {
                listener.partial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        handler.postDelayed(timeout, 5 * 60_000L)
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
    }

    private fun finishing() {
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 15_000)
    }
    override fun stop() { finishing(); recognizer.stopListening() }
    override fun cancel() { handler.removeCallbacks(timeout); recognizer.cancel() }
    override fun destroy() { handler.removeCallbacksAndMessages(null); listener = null; recognizer.destroy() }

    private fun errorMessage(error: Int) = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is unavailable. Allow Microphone in Android app permissions."
        SpeechRecognizer.ERROR_AUDIO -> "Could not access the microphone. Check microphone access in Android Settings."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was recognized. Tap Dictate to try again."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service could not connect. Check your internet connection and try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "The speech service is busy. Wait a moment and tap Dictate again."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "English speech recognition is unavailable. Check your speech service's language settings."
        else -> "Dictation stopped. Tap Dictate to try again, or type the passage."
    }
}

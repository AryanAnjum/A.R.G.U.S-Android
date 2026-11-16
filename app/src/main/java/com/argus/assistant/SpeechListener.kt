package com.argus.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous listening with Android speech recognition. A phrase ends after a short pause,
 * is handed to [onPhrase], and listening carries on. Use from the main thread.
 */
class SpeechListener(private val context: Context) {
    var onPhrase: ((String) -> Unit)? = null
    var onPartial: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var isListening = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    /** Results from an older listening round are ignored. */
    private var round = 0
    private var lastErrorNote = 0L

    fun start() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("Speech recognition isn't available on this phone. Install or enable the Google app.")
            return
        }
        isListening = true
        begin()
    }

    fun stop() {
        isListening = false
        round++
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        onPartial?.invoke("")
    }

    /** Throws away whatever has been heard so far (ARGUS's own voice) and starts listening afresh. */
    fun discardCurrentPhrase() {
        if (!isListening) return
        onPartial?.invoke("")
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            destroyRecognizer()
        }
        restart(60)
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (e: Exception) {
                // already gone
            }
        }
        recognizer = null
    }

    private fun restart(delayMs: Long) {
        round++
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ begin() }, delayMs)
    }

    private fun begin() {
        if (!isListening) return
        val mine = ++round
        val r = recognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        } catch (e: Exception) {
            onError?.invoke("Couldn't start speech recognition: ${e.message ?: "unknown error"}")
            isListening = false
            return
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (mine != round) return
                best(partialResults)?.let { onPartial?.invoke(it) }
            }

            override fun onResults(results: Bundle?) {
                if (mine != round) return
                onPartial?.invoke("")
                val text = best(results)
                restart(50)
                if (!text.isNullOrBlank()) onPhrase?.invoke(text.trim())
            }

            override fun onError(error: Int) {
                if (mine != round) return
                onPartial?.invoke("")
                when (error) {
                    // Silence: the recognizer gives up every few seconds, so just listen again.
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(100)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                        destroyRecognizer()
                        restart(400)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        onError?.invoke("Allow Microphone for ARGUS in Settings.")
                        stop()
                    }
                    else -> {
                        note(
                            when (error) {
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                                SpeechRecognizer.ERROR_SERVER ->
                                    "Speech recognition needs the internet, or the offline English pack " +
                                        "(Settings > Google > Voice > Offline speech recognition)."
                                SpeechRecognizer.ERROR_AUDIO -> "The microphone is busy in another app."
                                else -> "Speech recognition error $error. Trying again."
                            }
                        )
                        destroyRecognizer()
                        restart(1500)
                    }
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            note("Couldn't start the microphone: ${e.message ?: "unknown error"}")
            destroyRecognizer()
            restart(1500)
        }
    }

    private fun best(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    /** The same problem is reported at most every 20 seconds. */
    private fun note(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrorNote < 20_000) return
        lastErrorNote = now
        onError?.invoke(message)
    }
}

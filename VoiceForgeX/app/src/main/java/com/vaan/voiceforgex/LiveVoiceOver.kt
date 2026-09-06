package com.vaan.voiceforgex

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper

/**
 * Low-latency speech-to-clone controller. It prefers Android's on-device recognizer when present.
 * The recognizer is deliberately paused while synthesized speech plays to prevent feedback loops.
 */
class LiveVoiceOver(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onPhrase: (String) -> Unit,
) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        if (running) return
        require(SpeechRecognizer.isRecognitionAvailable(context)) { "Speech recognition service is unavailable on this phone" }
        recognizer = if (android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer!!.setRecognitionListener(this)
        running = true; paused = false
        listenSoon(0)
    }

    fun pause() {
        paused = true
        runCatching { recognizer?.cancel() }
    }

    fun resume(delayMs: Long = 300) {
        if (!running) return
        paused = false
        listenSoon(delayMs)
    }

    fun stop() {
        running = false; paused = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        recognizer?.destroy(); recognizer = null
        onStatus("Live voiceover stopped")
    }

    private fun listenSoon(delay: Long = 250) {
        handler.postDelayed({ if (running && !paused) beginListening() }, delay)
    }

    private fun beginListening() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        onStatus("Ω LIVE • listening…")
        runCatching { recognizer?.startListening(i) }.onFailure { onStatus("Live recognizer error: ${it.message}"); listenSoon(800) }
    }

    override fun onReadyForSpeech(params: Bundle?) { onStatus("Ω LIVE • speak") }
    override fun onBeginningOfSpeech() { onStatus("Ω LIVE • capturing performance") }
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { onStatus("Ω LIVE • transforming phrase…") }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onResults(results: Bundle?) {
        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (phrase.isNotEmpty()) onPhrase(phrase) else listenSoon()
    }

    override fun onError(error: Int) {
        if (!running || paused) return
        // NO_MATCH / SPEECH_TIMEOUT are normal boundaries in continuous mode.
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) listenSoon(180)
        else { onStatus("Ω LIVE recognizer code $error • retrying"); listenSoon(700) }
    }
}

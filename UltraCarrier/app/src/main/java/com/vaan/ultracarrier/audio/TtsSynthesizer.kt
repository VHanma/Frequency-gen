package com.vaan.ultracarrier.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.HashMap
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TtsSynthesizer(private val context: Context) : AutoCloseable {
    private val ready = CompletableDeferred<Unit>()
    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready.complete(Unit)
        } else if (!ready.isCompleted) {
            ready.completeExceptionally(IllegalStateException("Android Text-to-Speech initialization failed with code $status."))
        }
    }

    suspend fun synthesize(text: String): File {
        require(text.isNotBlank()) { "Enter text before synthesizing." }
        withTimeout(12_000) { ready.await() }
        configureVoice()

        val modernError = runCatching { synthesizeOnce(text, legacy = false) }.exceptionOrNull()
        if (modernError == null) {
            val newest = lastFile
            if (newest != null && validAudioFile(newest)) return newest
        }

        val legacy = runCatching { synthesizeOnce(text, legacy = true) }
        val file = legacy.getOrElse { legacyError ->
            throw IllegalStateException(
                "TTS could not create audio. Modern path: ${modernError?.message ?: "empty output"}; fallback: ${legacyError.message}",
                legacyError
            )
        }
        if (!validAudioFile(file)) {
            file.delete()
            throw IllegalStateException("TTS engine reported success but produced an empty audio file. Try another Android TTS voice/engine.")
        }
        return file
    }

    @Volatile private var lastFile: File? = null

    private fun configureVoice() {
        val preferred = Locale.US
        val available = tts.isLanguageAvailable(preferred)
        val languageResult = if (available >= TextToSpeech.LANG_AVAILABLE) {
            tts.setLanguage(preferred)
        } else {
            tts.setLanguage(Locale.getDefault())
        }
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw IllegalStateException("The active Android TTS engine has no usable installed language data.")
        }
        tts.setSpeechRate(1.0f)
        tts.setPitch(1.0f)
    }

    private suspend fun synthesizeOnce(text: String, legacy: Boolean): File {
        val id = UUID.randomUUID().toString()
        val file = File.createTempFile(if (legacy) "tts_legacy_" else "tts_", ".wav", context.cacheDir)
        file.delete()
        lastFile = file

        return withTimeout(30_000) {
            suspendCancellableCoroutine { continuation ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != id || !continuation.isActive) return
                        repeat(8) {
                            if (validAudioFile(file)) {
                                continuation.resume(file)
                                return
                            }
                            try { Thread.sleep(35) } catch (_: InterruptedException) { }
                        }
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("TTS finished but the output file was empty."))
                        }
                    }

                    @Deprecated("Deprecated in Android")
                    override fun onError(utteranceId: String?) {
                        onError(utteranceId, TextToSpeech.ERROR)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("TTS synthesis failed with code $errorCode."))
                        }
                    }
                })

                val result = if (!legacy) {
                    tts.synthesizeToFile(text, Bundle(), file, id)
                } else {
                    @Suppress("DEPRECATION")
                    val params = HashMap<String, String>().apply {
                        put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
                    }
                    @Suppress("DEPRECATION")
                    tts.synthesizeToFile(text, params, file.absolutePath)
                }

                if (result != TextToSpeech.SUCCESS && continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException(if (legacy) "Legacy TTS file path was rejected." else "Modern TTS file path was rejected.")
                    )
                }
                continuation.invokeOnCancellation { file.delete() }
            }
        }
    }

    private fun validAudioFile(file: File): Boolean = file.exists() && file.isFile && file.length() > 44L

    override fun close() {
        tts.stop()
        tts.shutdown()
    }
}

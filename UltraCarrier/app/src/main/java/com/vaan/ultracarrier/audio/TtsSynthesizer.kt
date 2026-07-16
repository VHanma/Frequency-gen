package com.vaan.ultracarrier.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TtsSynthesizer(private val context: Context) : AutoCloseable {
    private val ready = CompletableDeferred<Unit>()
    private val tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready.complete(Unit)
        } else {
            ready.completeExceptionally(IllegalStateException("Android Text-to-Speech initialization failed."))
        }
    }

    suspend fun synthesize(text: String): File {
        require(text.isNotBlank()) { "Enter text before synthesizing." }
        ready.await()
        tts.language = Locale.US

        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "tts_$id.wav")
        return suspendCancellableCoroutine { continuation ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && continuation.isActive) continuation.resume(file)
                }

                @Deprecated("Deprecated in Android")
                override fun onError(utteranceId: String?) {
                    onError(utteranceId, TextToSpeech.ERROR)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException("Text-to-Speech synthesis failed with code $errorCode.")
                        )
                    }
                }
            })

            val result = tts.synthesizeToFile(text, Bundle(), file, id)
            if (result != TextToSpeech.SUCCESS && continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("Text-to-Speech rejected the request."))
            }
            continuation.invokeOnCancellation { file.delete() }
        }
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
    }
}

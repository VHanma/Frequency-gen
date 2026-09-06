package com.vaan.voiceforgex

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

object CloneEngine {
    private val mutex = Mutex()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var modelPath: String? = null

    fun invalidate() {
        synchronized(this) {
            tts = null
            modelPath = null
        }
    }

    private fun build(context: Context): OfflineTts {
        val d = ModelManager.dir(context)
        check(ModelManager.isReady(context)) { "Voice model is not downloaded yet" }
        val p = OfflineTtsPocketModelConfig(
            lmFlow = File(d, "lm_flow.int8.onnx").absolutePath,
            lmMain = File(d, "lm_main.int8.onnx").absolutePath,
            encoder = File(d, "encoder.onnx").absolutePath,
            decoder = File(d, "decoder.int8.onnx").absolutePath,
            textConditioner = File(d, "text_conditioner.onnx").absolutePath,
            vocabJson = File(d, "vocab.json").absolutePath,
            tokenScoresJson = File(d, "token_scores.json").absolutePath,
            voiceEmbeddingCacheCapacity = 100,
        )
        val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(pocket = p, numThreads = maxOf(2, Runtime.getRuntime().availableProcessors().coerceAtMost(6)), debug = false, provider = "cpu"))
        return OfflineTts(config = cfg).also { modelPath = d.absolutePath }
    }

    private fun instance(context: Context): OfflineTts {
        val d = ModelManager.dir(context).absolutePath
        val old = tts
        if (old != null && modelPath == d) return old
        synchronized(this) {
            return tts ?: build(context).also { tts = it }
        }
    }

    suspend fun generate(context: Context, profile: CloneProfile, text: String, steps: Int = 5): GeneratedAudio = mutex.withLock {
        val ref = WavUtils.readPcm16Mono(File(profile.wavPath))
        instance(context).generateWithConfig(
            text = text,
            config = GenerationConfig(
                referenceAudio = ref.samples,
                referenceSampleRate = ref.sampleRate,
                numSteps = steps.coerceIn(2, 12),
                extra = mapOf("max_reference_audio_len" to "15", "seed" to "42")
            )
        )
    }

    suspend fun play(context: Context, profile: CloneProfile, text: String, steps: Int = 5) {
        val a = generate(context, profile, text, steps)
        val min = AudioTrack.getMinBufferSize(a.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(a.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, 32768)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track.play(); val pcm = WavUtils.floatToPcm16(a.samples); track.write(pcm, 0, pcm.size); track.stop(); track.release()
    }
}

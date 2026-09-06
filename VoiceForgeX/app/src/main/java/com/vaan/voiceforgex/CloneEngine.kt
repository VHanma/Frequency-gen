package com.vaan.voiceforgex

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

enum class SynthesisMode(val steps: Int, val candidates: Int) {
    FAST(3, 1), BALANCED(5, 1), OMEGA(8, 3)
}

object CloneEngine {
    private val mutex = Mutex()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var modelPath: String? = null

    fun invalidate() { synchronized(this) { tts = null; modelPath = null } }

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
            voiceEmbeddingCacheCapacity = 256,
        )
        val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(
            pocket = p,
            numThreads = maxOf(2, Runtime.getRuntime().availableProcessors().coerceAtMost(6)),
            debug = false,
            provider = "cpu"
        ))
        return OfflineTts(config = cfg).also { modelPath = d.absolutePath }
    }

    private fun instance(context: Context): OfflineTts {
        val d = ModelManager.dir(context).absolutePath
        val old = tts
        if (old != null && modelPath == d) return old
        synchronized(this) { return tts ?: build(context).also { tts = it } }
    }

    suspend fun generate(
        context: Context,
        profile: CloneProfile,
        text: String,
        mode: SynthesisMode = SynthesisMode.BALANCED,
    ): GeneratedAudio = mutex.withLock {
        val referenceFile = VoiceGenome.bestReference(profile)
        val ref = WavUtils.readPcm16Mono(referenceFile)
        val engine = instance(context)
        var best: GeneratedAudio? = null
        var bestScore = Double.NEGATIVE_INFINITY
        repeat(mode.candidates) { index ->
            val seed = 42 + index * 7919
            val candidate = engine.generateWithConfig(
                text = text,
                config = GenerationConfig(
                    referenceAudio = ref.samples,
                    referenceSampleRate = ref.sampleRate,
                    numSteps = mode.steps,
                    extra = mapOf("max_reference_audio_len" to "15", "seed" to seed.toString())
                )
            )
            val score = acousticFit(ref.samples, candidate.samples)
            if (score > bestScore) { bestScore = score; best = candidate }
        }
        best ?: error("Voice generation returned no audio")
    }

    /** Backward-compatible steps API used by Android TTS/overlay. */
    suspend fun generate(context: Context, profile: CloneProfile, text: String, steps: Int): GeneratedAudio {
        val mode = when { steps <= 3 -> SynthesisMode.FAST; steps >= 8 -> SynthesisMode.OMEGA; else -> SynthesisMode.BALANCED }
        return generate(context, profile, text, mode)
    }

    suspend fun play(context: Context, profile: CloneProfile, text: String, mode: SynthesisMode = SynthesisMode.BALANCED) {
        playAudio(generate(context, profile, text, mode))
    }

    suspend fun play(context: Context, profile: CloneProfile, text: String, steps: Int) {
        playAudio(generate(context, profile, text, steps))
    }

    private fun playAudio(a: GeneratedAudio) {
        val min = AudioTrack.getMinBufferSize(a.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(a.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, 32768)).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            track.play()
            val pcm = WavUtils.floatToPcm16(a.samples)
            var off = 0
            while (off < pcm.size) {
                val n = track.write(pcm, off, pcm.size - off)
                if (n <= 0) break
                off += n
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Cheap deterministic candidate judge. It compares energy, zero-crossing texture and crest behavior. */
    private fun acousticFit(reference: FloatArray, candidate: FloatArray): Double {
        fun features(s: FloatArray): DoubleArray {
            if (s.isEmpty()) return doubleArrayOf(0.0, 0.0, 0.0)
            var sq = 0.0; var z = 0; var peak = 0.0
            var prev = s[0]
            val step = (s.size / 12000).coerceAtLeast(1)
            var count = 0
            var i = 0
            while (i < s.size) {
                val v = s[i].toDouble(); sq += v * v; peak = maxOf(peak, abs(v))
                if ((v >= 0) != (prev >= 0)) z++
                prev = s[i]; count++; i += step
            }
            val rms = sqrt(sq / count.coerceAtLeast(1))
            return doubleArrayOf(rms, z.toDouble() / count.coerceAtLeast(1), peak)
        }
        val a = features(reference); val b = features(candidate)
        val distance = abs(a[0]-b[0]) * 3.0 + abs(a[1]-b[1]) * 1.2 + abs(a[2]-b[2]) * 0.5
        return -distance
    }
}

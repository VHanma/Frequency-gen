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
    FAST(3, 1), BALANCED(6, 2), OMEGA(10, 5)
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
            voiceEmbeddingCacheCapacity = 384,
        )
        return OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            pocket = p,
            numThreads = maxOf(2, Runtime.getRuntime().availableProcessors().coerceAtMost(6)),
            debug = false,
            provider = "cpu"
        ))).also { modelPath = d.absolutePath }
    }

    private fun instance(context: Context): OfflineTts {
        val d = ModelManager.dir(context).absolutePath
        val old = tts
        if (old != null && modelPath == d) return old
        synchronized(this) { return tts ?: build(context).also { tts = it } }
    }

    suspend fun generate(context: Context, profile: CloneProfile, text: String, mode: SynthesisMode = SynthesisMode.BALANCED): GeneratedAudio = mutex.withLock {
        VoiceGenome.ensureLegacy(profile)
        val refs = VoiceGenome.samples(profile.id).take(if (mode == SynthesisMode.OMEGA) 4 else 2)
            .mapNotNull { runCatching { WavUtils.readPcm16Mono(File(it.path)) }.getOrNull() }
            .ifEmpty { listOf(WavUtils.readPcm16Mono(VoiceGenome.bestReference(profile))) }
        val primary = refs.first()
        val engine = instance(context)
        val chunks = chunkText(text)
        val rendered = ArrayList<GeneratedAudio>()
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            var best: GeneratedAudio? = null
            var bestScore = Double.NEGATIVE_INFINITY
            repeat(mode.candidates) { index ->
                val reference = refs[index % refs.size]
                val seed = 42 + index * 7919 + chunkIndex * 104729
                val candidate = engine.generateWithConfig(
                    text = chunk,
                    config = GenerationConfig(
                        referenceAudio = reference.samples,
                        referenceSampleRate = reference.sampleRate,
                        numSteps = mode.steps,
                        extra = mapOf("max_reference_audio_len" to "15", "seed" to seed.toString())
                    )
                )
                val score = refs.map { acousticFit(it.samples, candidate.samples) }.average()
                if (score > bestScore) { bestScore = score; best = candidate }
            }
            rendered += best ?: error("Voice generation returned no audio")
        }
        stitch(rendered, primary.sampleRate)
    }

    suspend fun generate(context: Context, profile: CloneProfile, text: String, steps: Int): GeneratedAudio {
        val mode = when { steps <= 3 -> SynthesisMode.FAST; steps >= 8 -> SynthesisMode.OMEGA; else -> SynthesisMode.BALANCED }
        return generate(context, profile, text, mode)
    }

    suspend fun play(context: Context, profile: CloneProfile, text: String, mode: SynthesisMode = SynthesisMode.BALANCED) = playAudio(generate(context, profile, text, mode))
    suspend fun play(context: Context, profile: CloneProfile, text: String, steps: Int) = playAudio(generate(context, profile, text, steps))

    private fun chunkText(text: String): List<String> {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= 220) return listOf(clean)
        val sentences = clean.split(Regex("(?<=[.!?])\\s+"))
        val out = mutableListOf<String>(); var current = ""
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length + 1 > 220) { out += current; current = sentence }
            else current = if (current.isEmpty()) sentence else "$current $sentence"
        }
        if (current.isNotBlank()) out += current
        return out.flatMap { c -> if (c.length <= 300) listOf(c) else c.chunked(260) }
    }

    private fun stitch(parts: List<GeneratedAudio>, fallbackRate: Int): GeneratedAudio {
        if (parts.size == 1) return parts[0]
        val rate = parts.firstOrNull()?.sampleRate ?: fallbackRate
        val gap = FloatArray((rate * 0.055).toInt())
        val total = parts.sumOf { it.samples.size } + gap.size * (parts.size - 1)
        val all = FloatArray(total); var p = 0
        parts.forEachIndexed { i, a ->
            a.samples.copyInto(all, p); p += a.samples.size
            if (i != parts.lastIndex) { gap.copyInto(all, p); p += gap.size }
        }
        return GeneratedAudio(samples = all, sampleRate = rate)
    }

    private fun playAudio(a: GeneratedAudio) {
        val min = AudioTrack.getMinBufferSize(a.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(a.sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, 32768)).setTransferMode(AudioTrack.MODE_STREAM).build()
        try { track.play(); val pcm = WavUtils.floatToPcm16(a.samples); var off = 0; while (off < pcm.size) { val n = track.write(pcm, off, pcm.size-off); if(n<=0) break; off+=n } }
        finally { runCatching { track.stop() }; track.release() }
    }

    private fun acousticFit(reference: FloatArray, candidate: FloatArray): Double {
        fun features(s: FloatArray): DoubleArray {
            if (s.isEmpty()) return DoubleArray(8)
            var sq=0.0; var z=0; var peak=0.0; var absSum=0.0; var diff=0.0; var diff2=0.0; var prev=s[0].toDouble(); var prevDiff=0.0
            val step=(s.size/16000).coerceAtLeast(1); var count=0; var i=0
            while(i<s.size){ val v=s[i].toDouble(); val d=v-prev; sq+=v*v; absSum+=abs(v); peak=maxOf(peak,abs(v)); diff+=abs(d); diff2+=abs(d-prevDiff); if((v>=0)!=(prev>=0))z++; prevDiff=d; prev=v; count++; i+=step }
            val n=count.coerceAtLeast(1); val rms=sqrt(sq/n); val mean=absSum/n; val crest=if(rms>.0001) peak/rms else 0.0
            return doubleArrayOf(rms,mean,z.toDouble()/n,peak,diff/n,diff2/n,crest.coerceAtMost(10.0)/10.0,(peak-mean).coerceAtLeast(0.0))
        }
        val a=features(reference); val b=features(candidate)
        val weights=doubleArrayOf(3.0,2.0,1.4,.7,1.6,1.2,.8,.8)
        var d=0.0; for(i in a.indices)d+=abs(a[i]-b[i])*weights[i]
        return -d
    }
}

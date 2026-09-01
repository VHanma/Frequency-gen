package com.vaan.frequencyremapper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.io.Closeable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Live preview that decodes from the original URI and automatically rebuilds transient audio sessions. */
class StreamingResilientLivePreviewEngine(private val context: Context) : Closeable {
    private data class Config(val mappings: List<PhaseFrequencyMapping>, val options: PhaseRemapOptions, val version: Long)
    private data class BinPlan(val version: Long, val ratio: DoubleArray, val weight: DoubleArray, val phaseRadians: DoubleArray, val activeCount: Int)

    @Volatile private var running = false
    @Volatile private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var config = Config(emptyList(), PhaseRemapOptions(), 0L)
    @Volatile private var onError: ((String) -> Unit)? = null
    @Volatile private var onEnded: (() -> Unit)? = null
    private var versionCounter = 0L

    @Synchronized
    fun start(source: StreamAudioSource, mappings: List<PhaseFrequencyMapping>, options: PhaseRemapOptions, onError: (String) -> Unit = {}, onEnded: () -> Unit = {}) {
        stop()
        versionCounter++
        config = Config(mappings.toList(), options, versionCounter)
        this.onError = onError; this.onEnded = onEnded; running = true
        worker = Thread({
            var failures = 0
            try {
                while (running) {
                    try {
                        runSession(source)
                        failures = 0
                    } catch (t: Throwable) {
                        if (!running) break
                        failures++
                        releaseOwnedTrack()
                        if (failures >= 3) throw IllegalStateException("Live audio failed after 3 recovery attempts: ${t.message ?: t.javaClass.simpleName}", t)
                        Thread.sleep((150L * failures).coerceAtMost(450L))
                    }
                }
            } catch (t: Throwable) {
                val report = running
                running = false
                if (report) runCatching { this.onError?.invoke(t.message ?: t.javaClass.simpleName) }
            } finally {
                running = false
                releaseOwnedTrack()
                worker = null
                runCatching { this.onEnded?.invoke() }
            }
        }, "FrequencyRemapper-StreamingLive").apply { start() }
    }

    @Synchronized
    fun update(mappings: List<PhaseFrequencyMapping>, options: PhaseRemapOptions) {
        versionCounter++
        config = Config(mappings.toList(), options, versionCounter)
    }

    @Synchronized
    fun stop() {
        running = false
        val t = track
        runCatching { t?.pause() }; runCatching { t?.flush() }
        val w = worker
        if (w != null && w !== Thread.currentThread()) {
            runCatching { w.join(1200) }
            if (w.isAlive) runCatching { w.interrupt() }
        }
    }

    override fun close() = stop()

    private fun releaseOwnedTrack() {
        val owned = track
        runCatching { owned?.stop() }; runCatching { owned?.flush() }; runCatching { owned?.release() }
        if (track === owned) track = null
    }

    private fun runSession(source: StreamAudioSource) {
        require(source.sampleRate in 4000..192000) { "Unsupported sample rate ${source.sampleRate} Hz." }
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val fftSize = if (source.sampleRate <= 50000) 1024 else 2048
        val hop = fftSize / 4
        val inChannels = source.channels
        val outChannels = if (inChannels == 1) 1 else 2
        val channelMask = if (outChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(source.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "Android rejected live output at ${source.sampleRate} Hz." }
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(source.sampleRate).setChannelMask(channelMask).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuffer * 4, hop * outChannels * 2 * 12))
            .build()
        require(audioTrack.state == AudioTrack.STATE_INITIALIZED) { "Android audio output failed to initialize." }
        track = audioTrack

        val input = Array(inChannels) { FloatArray(fftSize) }
        val ola = Array(outChannels) { FloatArray(fftSize) }
        val norm = FloatArray(fftSize)
        val window = hammingWindow(fftSize)
        val windowSq = FloatArray(fftSize) { window[it] * window[it] }
        val prevPhase = Array(outChannels) { FloatArray(fftSize / 2 + 1) }
        val mappedPhase = Array(outChannels) { FloatArray(fftSize / 2 + 1) }
        val phaseSeen = Array(outChannels) { BooleanArray(fftSize / 2 + 1) }
        val real = FloatArray(fftSize); val imag = FloatArray(fftSize); val outReal = FloatArray(fftSize); val outImag = FloatArray(fftSize)
        val interleaved = ShortArray(hop * outChannels)
        var plan = compilePlan(source.sampleRate, fftSize, config)
        audioTrack.play()

        while (running) {
            for (a in input) a.fill(0f); for (a in ola) a.fill(0f); norm.fill(0f)
            for (a in prevPhase) a.fill(0f); for (a in mappedPhase) a.fill(0f); for (a in phaseSeen) a.fill(false)
            var any = false
            StreamingPcmFrameReader(context, source).use { reader ->
                var valid = reader.readInto(input, 0, fftSize)
                while (running && valid > 0) {
                    any = true
                    val latest = config
                    if (latest.version != plan.version) { plan = compilePlan(source.sampleRate, fftSize, latest); for (a in phaseSeen) a.fill(false) }

                    if (plan.activeCount == 0) {
                        for (i in 0 until min(hop, valid)) {
                            for (ch in 0 until outChannels) {
                                val srcCh = if (inChannels == 1) 0 else ch.coerceAtMost(inChannels - 1)
                                interleaved[i * outChannels + ch] = (input[srcCh][i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                            }
                        }
                    } else {
                        for (outCh in 0 until outChannels) {
                            val srcCh = if (inChannels == 1) 0 else outCh.coerceAtMost(inChannels - 1)
                            for (i in 0 until fftSize) { real[i] = input[srcCh][i] * window[i]; imag[i] = 0f }
                            FastFft.transform(real, imag, false)
                            warp(real, imag, outReal, outImag, prevPhase[outCh], mappedPhase[outCh], phaseSeen[outCh], fftSize, hop, plan)
                            FastFft.transform(outReal, outImag, true)
                            for (i in 0 until fftSize) ola[outCh][i] += outReal[i] * window[i]
                        }
                        for (i in 0 until fftSize) norm[i] += windowSq[i]
                        for (i in 0 until hop) {
                            val scale = 1f / max(norm[i], 1e-7f)
                            for (ch in 0 until outChannels) interleaved[i * outChannels + ch] = ((ola[ch][i] * scale).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                        }
                    }

                    var offset = 0
                    while (running && offset < interleaved.size) {
                        val wrote = audioTrack.write(interleaved, offset, interleaved.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (wrote == AudioTrack.ERROR_DEAD_OBJECT) error("AudioTrack disconnected.")
                        if (wrote < 0) error("AudioTrack write error $wrote")
                        if (wrote == 0) break
                        offset += wrote
                    }

                    for (ch in 0 until inChannels) { input[ch].copyInto(input[ch], 0, hop, fftSize); input[ch].fill(0f, fftSize - hop, fftSize) }
                    for (ch in 0 until outChannels) { ola[ch].copyInto(ola[ch], 0, hop, fftSize); ola[ch].fill(0f, fftSize - hop, fftSize) }
                    norm.copyInto(norm, 0, hop, fftSize); norm.fill(0f, fftSize - hop, fftSize)
                    valid = reader.readInto(input, fftSize - hop, hop)
                }
            }
            if (!running) break
            if (!any) error("Decoder returned no playable audio.")
        }
    }

    private fun compilePlan(sampleRate: Int, fftSize: Int, cfg: Config): BinPlan {
        val half = fftSize / 2; val ratio = DoubleArray(half + 1) { 1.0 }; val weight = DoubleArray(half + 1); val phase = DoubleArray(half + 1)
        val binHz = sampleRate.toDouble() / fftSize; val nyquist = sampleRate / 2.0; var active = 0
        for (k in 1 until half) {
            val frequency = k * binHz; var bw = 0.0; var br = 1.0; var bp = 0.0
            for (m in cfg.mappings) {
                if (!m.enabled || m.sourceHz <= 0.0 || m.targetHz <= 0.0 || m.sourceHz >= nyquist || m.targetHz >= nyquist) continue
                val h = if (cfg.options.shiftHarmonicFamily) (frequency / m.sourceHz).roundToInt().coerceAtLeast(1) else 1
                if (h > cfg.options.maxHarmonics) continue
                val center = m.sourceHz * h; if (center <= 0.0 || center >= nyquist) continue
                val minCents = if (center > binHz) 1200.0 * log2((center + binHz) / center) else cfg.options.bandCents
                val band = max(cfg.options.bandCents, minCents)
                val dist = abs(1200.0 * log2(frequency / center)); if (dist > band) continue
                val edge = (dist / band).coerceIn(0.0, 1.0); val w = if (edge <= 0.62) 1.0 else 0.5 * (1.0 + cos(PI * ((edge - 0.62) / 0.38)))
                val r = m.targetHz / m.sourceHz; if (frequency * r <= 0.0 || frequency * r >= nyquist) continue
                if (w > bw) { bw = w; br = r; val mult = if (cfg.options.shiftHarmonicFamily) h.toDouble() else 1.0; bp = normalizedDegrees(m.phaseDegrees * mult) * PI / 180.0 }
            }
            if (bw > 0.0001 && (abs(br - 1.0) > 1e-8 || abs(bp) > 1e-8)) { ratio[k] = br; weight[k] = bw; phase[k] = bp; active++ }
        }
        return BinPlan(cfg.version, ratio, weight, phase, active)
    }

    private fun warp(real: FloatArray, imag: FloatArray, outReal: FloatArray, outImag: FloatArray, prevPhase: FloatArray, mappedPhase: FloatArray, phaseSeen: BooleanArray, fftSize: Int, hop: Int, plan: BinPlan) {
        outReal.fill(0f); outImag.fill(0f); val half = fftSize / 2; outReal[0] = real[0]; outReal[half] = real[half]
        for (k in 1 until half) {
            val w = plan.weight[k]
            if (w <= 0.0001) { outReal[k] += real[k]; outImag[k] += imag[k]; prevPhase[k] = kotlin.math.atan2(imag[k].toDouble(), real[k].toDouble()).toFloat(); continue }
            val re = real[k].toDouble(); val im = imag[k].toDouble(); val mag = hypot(re, im); val srcPhase = kotlin.math.atan2(im, re); val keep = (1.0 - w).coerceIn(0.0, 1.0)
            outReal[k] += (re * keep).toFloat(); outImag[k] += (im * keep).toFloat(); val baseOmega = 2.0 * PI * k / fftSize
            val targetOmega: Double
            if (!phaseSeen[k]) { mappedPhase[k] = srcPhase.toFloat(); phaseSeen[k] = true; targetOmega = baseOmega * plan.ratio[k] }
            else { val expected = 2.0 * PI * k * hop / fftSize; val delta = principalPhase(srcPhase - prevPhase[k] - expected); val trueOmega = baseOmega + delta / hop; targetOmega = trueOmega * plan.ratio[k]; mappedPhase[k] = principalPhase(mappedPhase[k] + targetOmega * hop).toFloat() }
            prevPhase[k] = srcPhase.toFloat(); val dest = targetOmega * fftSize / (2.0 * PI)
            if (dest <= 0.0 || dest >= half - 1.0) { outReal[k] += (re * w).toFloat(); outImag[k] += (im * w).toFloat(); continue }
            val lower = floor(dest).toInt().coerceIn(1, half - 1); val upper = min(half - 1, lower + 1); val uw = dest - lower; val sm = mag * w
            val ph = principalPhase(mappedPhase[k].toDouble() + plan.phaseRadians[k]); val sr = cos(ph) * sm; val si = sin(ph) * sm
            outReal[lower] += (sr * (1.0 - uw)).toFloat(); outImag[lower] += (si * (1.0 - uw)).toFloat(); if (upper != lower) { outReal[upper] += (sr * uw).toFloat(); outImag[upper] += (si * uw).toFloat() }
        }
        for (k in 1 until half) { outReal[fftSize - k] = outReal[k]; outImag[fftSize - k] = -outImag[k] }
    }

    private fun normalizedDegrees(v: Double): Double { var x = v % 360.0; if (x > 180.0) x -= 360.0; if (x <= -180.0) x += 360.0; return x }
    private fun principalPhase(v: Double): Double { var x = v; while (x > PI) x -= 2.0 * PI; while (x < -PI) x += 2.0 * PI; return x }
}

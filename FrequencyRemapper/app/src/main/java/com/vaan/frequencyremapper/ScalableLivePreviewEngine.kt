package com.vaan.frequencyremapper

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

/**
 * Low-overhead live preview for large maps/files.
 *
 * The expensive mapping search is compiled once whenever the user changes a
 * field. Playback frames then use O(FFT bins) array lookups instead of
 * O(FFT bins × mappings) searches. Preview loops at EOF so editing never dies
 * just because the source reached the end.
 */
class ScalableLivePreviewEngine : Closeable {
    private data class Config(
        val mappings: List<PhaseFrequencyMapping>,
        val options: PhaseRemapOptions,
        val version: Long
    )

    private data class BinPlan(
        val version: Long,
        val ratio: DoubleArray,
        val weight: DoubleArray,
        val phaseRadians: DoubleArray,
        val activeCount: Int
    )

    @Volatile private var running = false
    @Volatile private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var config = Config(emptyList(), PhaseRemapOptions(), 0L)
    @Volatile private var onError: ((String) -> Unit)? = null
    @Volatile private var onEnded: (() -> Unit)? = null
    private var versionCounter = 0L

    val isRunning: Boolean get() = running

    @Synchronized
    fun start(
        source: PcmSource,
        mappings: List<PhaseFrequencyMapping>,
        options: PhaseRemapOptions,
        onError: (String) -> Unit = {},
        onEnded: () -> Unit = {}
    ) {
        stop()
        versionCounter++
        config = Config(mappings.toList(), options, versionCounter)
        this.onError = onError
        this.onEnded = onEnded
        running = true
        worker = Thread({
            try {
                runStream(source)
            } catch (t: Throwable) {
                val report = running
                running = false
                if (report) runCatching { this.onError?.invoke(t.message ?: t.javaClass.simpleName) }
            } finally {
                running = false
                val owned = track
                runCatching { owned?.stop() }
                runCatching { owned?.flush() }
                runCatching { owned?.release() }
                if (track === owned) track = null
                worker = null
                runCatching { this.onEnded?.invoke() }
            }
        }, "FrequencyRemapper-ScalableLive").apply { start() }
    }

    @Synchronized
    fun update(mappings: List<PhaseFrequencyMapping>, options: PhaseRemapOptions) {
        versionCounter++
        config = Config(mappings.toList(), options, versionCounter)
    }

    @Synchronized
    fun stop() {
        running = false
        val localTrack = track
        runCatching { localTrack?.pause() }
        runCatching { localTrack?.flush() }
        val thread = worker
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(1400) }
            if (thread.isAlive) runCatching { thread.interrupt() }
        }
    }

    override fun close() = stop()

    private fun runStream(source: PcmSource) {
        require(source.sampleRate in 4000..192000) { "Unsupported preview sample rate: ${source.sampleRate} Hz" }
        require(source.channels > 0) { "Audio has no channels." }
        require(source.file.exists() && source.file.length() > 0L) { "Decoded audio is unavailable." }
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

        // Preview favors stability/latency. Full render keeps the larger FFT.
        val fftSize = if (source.sampleRate <= 50000) 1024 else 2048
        val hop = fftSize / 4
        val inChannels = source.channels
        val outChannels = if (inChannels == 1) 1 else 2
        val channelMask = if (outChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(source.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "This device rejected ${source.sampleRate} Hz live playback." }
        val bufferBytes = max(minBuffer * 3, hop * outChannels * 2 * 10)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(source.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        require(audioTrack.state == AudioTrack.STATE_INITIALIZED) { "Android audio output failed to initialize." }
        track = audioTrack

        val inputFrames = Array(inChannels) { FloatArray(fftSize) }
        val ola = Array(outChannels) { FloatArray(fftSize) }
        val norm = FloatArray(fftSize)
        val window = hammingWindow(fftSize)
        val windowSquared = FloatArray(fftSize) { window[it] * window[it] }
        val prevPhase = Array(outChannels) { FloatArray(fftSize / 2 + 1) }
        val mappedPhase = Array(outChannels) { FloatArray(fftSize / 2 + 1) }
        val phaseSeen = Array(outChannels) { BooleanArray(fftSize / 2 + 1) }
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val outReal = FloatArray(fftSize)
        val outImag = FloatArray(fftSize)
        val interleaved = ShortArray(hop * outChannels)
        var plan = compilePlan(source.sampleRate, fftSize, config)

        audioTrack.play()

        while (running) {
            // Reset overlap/phase state each time preview loops to the beginning.
            for (a in inputFrames) a.fill(0f)
            for (a in ola) a.fill(0f)
            norm.fill(0f)
            for (a in prevPhase) a.fill(0f)
            for (a in mappedPhase) a.fill(0f)
            for (a in phaseSeen) a.fill(false)

            var reachedAnyAudio = false
            PcmFrameReader(source, fftSize).use { reader ->
                var valid = reader.readInto(inputFrames, 0, fftSize)
                var consumed = 0L
                while (running && valid > 0 && consumed < source.totalFrames) {
                    reachedAnyAudio = true
                    val latest = config
                    if (latest.version != plan.version) {
                        plan = compilePlan(source.sampleRate, fftSize, latest)
                        for (a in phaseSeen) a.fill(false)
                    }

                    if (plan.activeCount == 0) {
                        for (i in 0 until hop) {
                            for (ch in 0 until outChannels) {
                                val srcCh = if (inChannels == 1) 0 else ch.coerceAtMost(inChannels - 1)
                                interleaved[i * outChannels + ch] =
                                    (inputFrames[srcCh][i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                            }
                        }
                    } else {
                        for (outCh in 0 until outChannels) {
                            val srcCh = if (inChannels == 1) 0 else outCh.coerceAtMost(inChannels - 1)
                            for (i in 0 until fftSize) {
                                real[i] = inputFrames[srcCh][i] * window[i]
                                imag[i] = 0f
                            }
                            FastFft.transform(real, imag, inverse = false)
                            warpWithPlan(
                                real, imag, outReal, outImag,
                                prevPhase[outCh], mappedPhase[outCh], phaseSeen[outCh],
                                fftSize, hop, plan
                            )
                            FastFft.transform(outReal, outImag, inverse = true)
                            for (i in 0 until fftSize) ola[outCh][i] += outReal[i] * window[i]
                        }
                        for (i in 0 until fftSize) norm[i] += windowSquared[i]
                        for (i in 0 until hop) {
                            val scale = 1f / max(norm[i], 1e-7f)
                            for (ch in 0 until outChannels) {
                                val sample = (ola[ch][i] * scale).coerceIn(-1f, 1f)
                                interleaved[i * outChannels + ch] = (sample * 32767f).roundToInt().toShort()
                            }
                        }
                    }

                    var offset = 0
                    while (running && offset < interleaved.size) {
                        val wrote = audioTrack.write(interleaved, offset, interleaved.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (wrote == AudioTrack.ERROR_DEAD_OBJECT) error("Android audio output disconnected.")
                        if (wrote < 0) error("Android audio write failed: $wrote")
                        if (wrote == 0) break
                        offset += wrote
                    }

                    consumed += hop
                    for (ch in 0 until inChannels) {
                        inputFrames[ch].copyInto(inputFrames[ch], 0, hop, fftSize)
                        inputFrames[ch].fill(0f, fftSize - hop, fftSize)
                    }
                    for (ch in 0 until outChannels) {
                        ola[ch].copyInto(ola[ch], 0, hop, fftSize)
                        ola[ch].fill(0f, fftSize - hop, fftSize)
                    }
                    norm.copyInto(norm, 0, hop, fftSize)
                    norm.fill(0f, fftSize - hop, fftSize)
                    valid = reader.readInto(inputFrames, fftSize - hop, hop)
                }
            }

            if (!running) break
            if (!reachedAnyAudio) error("Decoded audio contains no playable frames.")
            // Deliberately loop. Live editing remains active after EOF.
        }
    }

    private fun compilePlan(sampleRate: Int, fftSize: Int, cfg: Config): BinPlan {
        val half = fftSize / 2
        val ratio = DoubleArray(half + 1) { 1.0 }
        val weight = DoubleArray(half + 1)
        val phase = DoubleArray(half + 1)
        val binHz = sampleRate.toDouble() / fftSize
        val nyquist = sampleRate / 2.0
        var active = 0

        for (k in 1 until half) {
            val frequency = k * binHz
            var bestWeight = 0.0
            var bestRatio = 1.0
            var bestPhase = 0.0

            for (mapping in cfg.mappings) {
                if (!mapping.enabled) continue
                val source = mapping.sourceHz
                val target = mapping.targetHz
                if (!source.isFinite() || !target.isFinite() || !mapping.phaseDegrees.isFinite()) continue
                if (source <= 0.0 || target <= 0.0 || source >= nyquist || target >= nyquist) continue

                val harmonic = if (cfg.options.shiftHarmonicFamily) {
                    (frequency / source).roundToInt().coerceAtLeast(1)
                } else 1
                if (harmonic > cfg.options.maxHarmonics) continue
                val center = source * harmonic
                if (center <= 0.0 || center >= nyquist) continue

                val minimumCents = if (center > binHz) {
                    1200.0 * log2((center + binHz) / center)
                } else cfg.options.bandCents
                val liveBandCents = max(cfg.options.bandCents, minimumCents)
                val distance = abs(1200.0 * log2(frequency / center))
                if (distance > liveBandCents) continue

                val edge = (distance / liveBandCents).coerceIn(0.0, 1.0)
                val w = if (edge <= 0.62) 1.0 else {
                    val x = (edge - 0.62) / 0.38
                    0.5 * (1.0 + cos(PI * x))
                }
                val r = target / source
                if (frequency * r <= 0.0 || frequency * r >= nyquist) continue
                if (w > bestWeight) {
                    bestWeight = w
                    bestRatio = r
                    val mult = if (cfg.options.shiftHarmonicFamily) harmonic.toDouble() else 1.0
                    bestPhase = normalizedDegrees(mapping.phaseDegrees * mult) * PI / 180.0
                }
            }

            if (bestWeight > 0.0001 && (abs(bestRatio - 1.0) > 1e-8 || abs(bestPhase) > 1e-8)) {
                ratio[k] = bestRatio
                weight[k] = bestWeight
                phase[k] = bestPhase
                active++
            }
        }
        return BinPlan(cfg.version, ratio, weight, phase, active)
    }

    private fun warpWithPlan(
        real: FloatArray,
        imag: FloatArray,
        outReal: FloatArray,
        outImag: FloatArray,
        prevPhase: FloatArray,
        mappedPhase: FloatArray,
        phaseSeen: BooleanArray,
        fftSize: Int,
        hop: Int,
        plan: BinPlan
    ) {
        outReal.fill(0f)
        outImag.fill(0f)
        val half = fftSize / 2
        outReal[0] = real[0]
        outReal[half] = real[half]

        for (k in 1 until half) {
            val w = plan.weight[k]
            if (w <= 0.0001) {
                outReal[k] += real[k]
                outImag[k] += imag[k]
                prevPhase[k] = kotlin.math.atan2(imag[k].toDouble(), real[k].toDouble()).toFloat()
                continue
            }

            val re = real[k].toDouble()
            val im = imag[k].toDouble()
            val magnitude = hypot(re, im)
            val sourcePhase = kotlin.math.atan2(im, re)
            val keep = (1.0 - w).coerceIn(0.0, 1.0)
            outReal[k] += (re * keep).toFloat()
            outImag[k] += (im * keep).toFloat()

            val baseOmega = 2.0 * PI * k / fftSize
            val targetOmega: Double
            if (!phaseSeen[k]) {
                mappedPhase[k] = sourcePhase.toFloat()
                phaseSeen[k] = true
                targetOmega = baseOmega * plan.ratio[k]
            } else {
                val expectedAdvance = 2.0 * PI * k * hop / fftSize
                val delta = principalPhase(sourcePhase - prevPhase[k] - expectedAdvance)
                val trueOmega = baseOmega + delta / hop
                targetOmega = trueOmega * plan.ratio[k]
                mappedPhase[k] = principalPhase(mappedPhase[k] + targetOmega * hop).toFloat()
            }
            prevPhase[k] = sourcePhase.toFloat()

            val destinationBin = targetOmega * fftSize / (2.0 * PI)
            if (destinationBin <= 0.0 || destinationBin >= half - 1.0) {
                outReal[k] += (re * w).toFloat()
                outImag[k] += (im * w).toFloat()
                continue
            }

            val lower = floor(destinationBin).toInt().coerceIn(1, half - 1)
            val upper = min(half - 1, lower + 1)
            val upperWeight = destinationBin - lower
            val shiftedMagnitude = magnitude * w
            val shiftedPhase = principalPhase(mappedPhase[k].toDouble() + plan.phaseRadians[k])
            val sr = cos(shiftedPhase) * shiftedMagnitude
            val si = sin(shiftedPhase) * shiftedMagnitude
            outReal[lower] += (sr * (1.0 - upperWeight)).toFloat()
            outImag[lower] += (si * (1.0 - upperWeight)).toFloat()
            if (upper != lower) {
                outReal[upper] += (sr * upperWeight).toFloat()
                outImag[upper] += (si * upperWeight).toFloat()
            }
        }

        for (k in 1 until half) {
            outReal[fftSize - k] = outReal[k]
            outImag[fftSize - k] = -outImag[k]
        }
    }

    private fun normalizedDegrees(value: Double): Double {
        var x = value % 360.0
        if (x > 180.0) x -= 360.0
        if (x <= -180.0) x += 360.0
        return x
    }

    private fun principalPhase(value: Double): Double {
        var x = value
        while (x > PI) x -= 2.0 * PI
        while (x < -PI) x += 2.0 * PI
        return x
    }
}

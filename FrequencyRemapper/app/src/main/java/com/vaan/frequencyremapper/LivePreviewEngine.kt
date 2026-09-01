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
 * Crash-safe streaming preview. The worker owns AudioTrack for its entire
 * lifetime, so stop() never releases the track underneath a blocking write.
 * PCM16 output is used for broad Android device compatibility.
 */
class LivePreviewEngine : Closeable {
    @Volatile private var running = false
    @Volatile private var mappings: List<FrequencyMapping> = emptyList()
    @Volatile private var options: RemapOptions = RemapOptions()
    @Volatile private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var onError: ((String) -> Unit)? = null
    @Volatile private var onEnded: (() -> Unit)? = null

    val isRunning: Boolean get() = running

    @Synchronized
    fun start(
        source: PcmSource,
        mappings: List<FrequencyMapping>,
        options: RemapOptions,
        onError: (String) -> Unit = {},
        onEnded: () -> Unit = {}
    ) {
        stop()
        this.mappings = mappings.toList()
        this.options = options
        this.onError = onError
        this.onEnded = onEnded
        running = true
        worker = Thread({
            try {
                runStream(source)
            } catch (t: Throwable) {
                val shouldReport = running
                running = false
                if (shouldReport) {
                    runCatching { this.onError?.invoke(t.message ?: t.javaClass.simpleName) }
                }
            } finally {
                running = false
                val ownedTrack = track
                runCatching { ownedTrack?.stop() }
                runCatching { ownedTrack?.flush() }
                runCatching { ownedTrack?.release() }
                if (track === ownedTrack) track = null
                worker = null
                runCatching { this.onEnded?.invoke() }
            }
        }, "FrequencyRemapper-LivePreview").apply { start() }
    }

    fun update(mappings: List<FrequencyMapping>, options: RemapOptions) {
        this.mappings = mappings.toList()
        this.options = options
    }

    @Synchronized
    fun stop() {
        running = false
        val localTrack = track
        // Pause + flush asks a blocking write to return. The worker, not this
        // method, owns release() to avoid the old use-after-release race.
        runCatching { localTrack?.pause() }
        runCatching { localTrack?.flush() }
        val thread = worker
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(1200) }
            if (thread.isAlive) runCatching { thread.interrupt() }
        }
    }

    override fun close() = stop()

    private fun runStream(source: PcmSource) {
        require(source.sampleRate in 4000..192000) { "Unsupported preview sample rate: ${source.sampleRate} Hz" }
        require(source.channels > 0) { "Audio has no channels." }
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

        val fftSize = if (source.sampleRate > 50000) 2048 else 2048
        val hop = fftSize / 4
        val inChannels = source.channels
        val outChannels = if (inChannels == 1) 1 else 2
        val channelMask = if (outChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

        val minBuffer = AudioTrack.getMinBufferSize(
            source.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "This device rejected ${source.sampleRate} Hz live playback." }
        val bufferBytes = max(minBuffer * 2, hop * outChannels * 2 * 8)

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

        audioTrack.play()
        PcmFrameReader(source, fftSize).use { reader ->
            var valid = reader.readInto(inputFrames, 0, fftSize)
            var consumed = 0L

            while (running && valid > 0 && consumed < source.totalFrames) {
                val frameMappings = mappings.filter {
                    it.enabled && it.sourceHz > 0.0 && it.targetHz > 0.0 && abs(it.sourceHz - it.targetHz) > 0.0001
                }
                val frameOptions = options

                if (frameMappings.isEmpty()) {
                    // Zero-cost pass-through until the user actually changes a
                    // frequency. This keeps live preview light and responsive.
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
                        warpSpectrum(
                            real, imag, outReal, outImag,
                            prevPhase[outCh], mappedPhase[outCh], phaseSeen[outCh],
                            source.sampleRate, fftSize, hop, frameMappings, frameOptions
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
                    val wrote = audioTrack.write(
                        interleaved,
                        offset,
                        interleaved.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
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
    }

    private data class Transform(val ratio: Double, val weight: Double)

    private fun warpSpectrum(
        real: FloatArray,
        imag: FloatArray,
        outReal: FloatArray,
        outImag: FloatArray,
        prevPhase: FloatArray,
        mappedPhase: FloatArray,
        phaseSeen: BooleanArray,
        sampleRate: Int,
        fftSize: Int,
        hop: Int,
        mappings: List<FrequencyMapping>,
        options: RemapOptions
    ) {
        outReal.fill(0f)
        outImag.fill(0f)
        val half = fftSize / 2
        val nyquist = sampleRate / 2.0
        val binHz = sampleRate.toDouble() / fftSize
        outReal[0] = real[0]
        outImag[0] = 0f
        outReal[half] = real[half]
        outImag[half] = 0f

        for (k in 1 until half) {
            val re = real[k].toDouble()
            val im = imag[k].toDouble()
            val magnitude = hypot(re, im)
            val phase = kotlin.math.atan2(im, re)
            val frequency = k * binHz
            val transform = findTransform(frequency, nyquist, mappings, options, binHz)

            if (transform == null || transform.weight <= 0.0001 || abs(transform.ratio - 1.0) < 1e-8) {
                outReal[k] += real[k]
                outImag[k] += imag[k]
                prevPhase[k] = phase.toFloat()
                continue
            }

            val keep = (1.0 - transform.weight).coerceIn(0.0, 1.0)
            outReal[k] += (re * keep).toFloat()
            outImag[k] += (im * keep).toFloat()

            val baseOmega = 2.0 * PI * k / fftSize
            val targetOmega: Double
            if (!phaseSeen[k]) {
                mappedPhase[k] = phase.toFloat()
                phaseSeen[k] = true
                targetOmega = baseOmega * transform.ratio
            } else {
                val expectedAdvance = 2.0 * PI * k * hop / fftSize
                val delta = principalPhase(phase - prevPhase[k] - expectedAdvance)
                val trueOmega = baseOmega + delta / hop
                targetOmega = trueOmega * transform.ratio
                mappedPhase[k] = principalPhase(mappedPhase[k] + targetOmega * hop).toFloat()
            }
            prevPhase[k] = phase.toFloat()

            val destinationBin = targetOmega * fftSize / (2.0 * PI)
            if (destinationBin <= 0.0 || destinationBin >= half - 1.0) continue
            val lower = floor(destinationBin).toInt().coerceIn(1, half - 1)
            val upper = min(half - 1, lower + 1)
            val upperWeight = destinationBin - lower
            val shiftedMagnitude = magnitude * transform.weight
            val shiftedPhase = mappedPhase[k].toDouble()
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

    private fun findTransform(
        frequency: Double,
        nyquist: Double,
        mappings: List<FrequencyMapping>,
        options: RemapOptions,
        binHz: Double
    ): Transform? {
        var best: Transform? = null
        var bestWeight = 0.0
        for (mapping in mappings) {
            val source = mapping.sourceHz
            val target = mapping.targetHz
            if (source <= 0.0 || target <= 0.0) continue
            val ratio = target / source
            val harmonic = if (options.shiftHarmonicFamily) {
                (frequency / source).roundToInt().coerceAtLeast(1)
            } else 1
            if (harmonic > options.maxHarmonics) continue
            val center = source * harmonic
            if (center <= 0.0 || center >= nyquist) continue

            // Live preview uses a smaller FFT for stability. Never make the
            // selection band narrower than roughly one FFT bin.
            val requestedCents = options.bandCents
            val minimumCents = if (center > binHz) 1200.0 * log2((center + binHz) / center) else requestedCents
            val liveBandCents = max(requestedCents, minimumCents)
            val cents = 1200.0 * log2(frequency / center)
            val distance = abs(cents)
            if (distance > liveBandCents) continue
            val edge = (distance / liveBandCents).coerceIn(0.0, 1.0)
            val weight = if (edge <= 0.62) 1.0 else {
                val x = (edge - 0.62) / 0.38
                0.5 * (1.0 + cos(PI * x))
            }
            val mappedFrequency = frequency * ratio
            if (mappedFrequency <= 0.0 || mappedFrequency >= nyquist) continue
            if (weight > bestWeight) {
                bestWeight = weight
                best = Transform(ratio, weight)
            }
        }
        return best
    }

    private fun principalPhase(value: Double): Double {
        var x = value
        while (x > PI) x -= 2.0 * PI
        while (x < -PI) x += 2.0 * PI
        return x
    }
}

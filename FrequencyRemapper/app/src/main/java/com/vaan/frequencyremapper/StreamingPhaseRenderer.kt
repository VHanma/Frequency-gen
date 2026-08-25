package com.vaan.frequencyremapper

import android.content.Context
import java.nio.channels.FileChannel
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

/** Full-quality spectral renderer that decodes directly from the selected URI. */
object StreamingPhaseRenderer {
    private data class BinPlan(
        val ratio: DoubleArray,
        val weight: DoubleArray,
        val phaseRadians: DoubleArray,
        val activeCount: Int
    )

    fun estimatedDataBytes(source: StreamAudioSource): Long = safeMultiply(safeMultiply(source.totalFrames, source.channels.toLong()), 2L)
    fun estimatedFileBytes(source: StreamAudioSource): Long {
        val data = estimatedDataBytes(source)
        return data + if (shouldUseRf64(source, data)) 80L else 44L
    }

    private fun shouldUseRf64(source: StreamAudioSource, estimate: Long): Boolean =
        source.durationUs <= 0L || estimate >= 3_700_000_000L

    fun renderToChannel(
        context: Context,
        source: StreamAudioSource,
        channel: FileChannel,
        mappings: List<PhaseFrequencyMapping>,
        options: PhaseRemapOptions = PhaseRemapOptions(),
        onProgress: (Float) -> Unit = {}
    ): ScalablePhaseRenderer.RenderInfo {
        val nyquist = source.sampleRate / 2.0
        val active = mappings.filter {
            it.enabled && it.sourceHz.isFinite() && it.targetHz.isFinite() && it.phaseDegrees.isFinite() &&
                it.sourceHz > 0.0 && it.targetHz > 0.0 && it.sourceHz < nyquist && it.targetHz < nyquist &&
                (abs(it.targetHz - it.sourceHz) > 0.0001 || abs(normalizedDegrees(it.phaseDegrees)) > 0.0001)
        }

        val estimate = estimatedDataBytes(source)
        val writer = ScalableWavChannelWriter(
            channel = channel,
            sampleRate = source.sampleRate,
            channels = source.channels,
            expectedFrames = source.totalFrames,
            forceRf64 = shouldUseRf64(source, estimate)
        )

        if (active.isEmpty()) {
            renderPassThrough(context, source, writer, onProgress)
            return writer.finish()
        }

        val fftSize = if (source.sampleRate > 50000) 8192 else 4096
        val hop = fftSize / 4
        val channels = source.channels
        val plan = compilePlan(source.sampleRate, fftSize, active, options)
        val inputFrames = Array(channels) { FloatArray(fftSize) }
        val ola = Array(channels) { FloatArray(fftSize) }
        val norm = FloatArray(fftSize)
        val window = hammingWindow(fftSize)
        val windowSquared = FloatArray(fftSize) { window[it] * window[it] }
        val prevPhase = Array(channels) { FloatArray(fftSize / 2 + 1) }
        val mappedPhase = Array(channels) { FloatArray(fftSize / 2 + 1) }
        val phaseSeen = Array(channels) { BooleanArray(fftSize / 2 + 1) }
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val outReal = FloatArray(fftSize)
        val outImag = FloatArray(fftSize)
        val outputChunk = Array(channels) { FloatArray(hop) }

        StreamingPcmFrameReader(context, source).use { reader ->
            var valid = reader.readInto(inputFrames, 0, fftSize)
            var written = 0L
            var stftFrame = 0
            while (valid > 0) {
                for (ch in 0 until channels) {
                    for (i in 0 until fftSize) {
                        real[i] = inputFrames[ch][i] * window[i]
                        imag[i] = 0f
                    }
                    FastFft.transform(real, imag, false)
                    warpWithPlan(real, imag, outReal, outImag, prevPhase[ch], mappedPhase[ch], phaseSeen[ch], fftSize, hop, plan)
                    FastFft.transform(outReal, outImag, true)
                    for (i in 0 until fftSize) ola[ch][i] += outReal[i] * window[i]
                }
                for (i in 0 until fftSize) norm[i] += windowSquared[i]
                val toWrite = min(hop, valid.coerceAtLeast(1))
                for (i in 0 until toWrite) {
                    val scale = 1f / max(norm[i], 1e-7f)
                    for (ch in 0 until channels) outputChunk[ch][i] = ola[ch][i] * scale
                }
                writer.writeInterleaved(outputChunk, toWrite)
                written += toWrite
                stftFrame++
                if (stftFrame % 16 == 0 && source.totalFrames > 1L) {
                    onProgress((written.toDouble() / source.totalFrames).toFloat().coerceIn(0f, 0.99f))
                }

                for (ch in 0 until channels) {
                    inputFrames[ch].copyInto(inputFrames[ch], 0, hop, fftSize)
                    inputFrames[ch].fill(0f, fftSize - hop, fftSize)
                    ola[ch].copyInto(ola[ch], 0, hop, fftSize)
                    ola[ch].fill(0f, fftSize - hop, fftSize)
                }
                norm.copyInto(norm, 0, hop, fftSize)
                norm.fill(0f, fftSize - hop, fftSize)
                valid = reader.readInto(inputFrames, fftSize - hop, hop)
            }
        }
        onProgress(1f)
        return writer.finish()
    }

    private fun renderPassThrough(context: Context, source: StreamAudioSource, writer: ScalableWavChannelWriter, onProgress: (Float) -> Unit) {
        val chunk = 8192
        val frames = Array(source.channels) { FloatArray(chunk) }
        StreamingPcmFrameReader(context, source).use { reader ->
            var written = 0L
            while (true) {
                val read = reader.readInto(frames, 0, chunk)
                if (read <= 0) break
                writer.writeInterleaved(frames, read)
                written += read
                if (source.totalFrames > 1L) onProgress((written.toDouble() / source.totalFrames).toFloat().coerceIn(0f, 0.99f))
            }
        }
        onProgress(1f)
    }

    private fun compilePlan(sampleRate: Int, fftSize: Int, mappings: List<PhaseFrequencyMapping>, options: PhaseRemapOptions): BinPlan {
        val half = fftSize / 2
        val ratio = DoubleArray(half + 1) { 1.0 }
        val weight = DoubleArray(half + 1)
        val phase = DoubleArray(half + 1)
        val binHz = sampleRate.toDouble() / fftSize
        val nyquist = sampleRate / 2.0
        var active = 0
        for (k in 1 until half) {
            val frequency = k * binHz
            var bestWeight = 0.0; var bestRatio = 1.0; var bestPhase = 0.0
            for (mapping in mappings) {
                val harmonic = if (options.shiftHarmonicFamily) (frequency / mapping.sourceHz).roundToInt().coerceAtLeast(1) else 1
                if (harmonic > options.maxHarmonics) continue
                val center = mapping.sourceHz * harmonic
                if (center <= 0.0 || center >= nyquist) continue
                val distance = abs(1200.0 * log2(frequency / center))
                if (distance > options.bandCents) continue
                val edge = (distance / options.bandCents).coerceIn(0.0, 1.0)
                val w = if (edge <= 0.62) 1.0 else 0.5 * (1.0 + cos(PI * ((edge - 0.62) / 0.38)))
                val r = mapping.targetHz / mapping.sourceHz
                if (frequency * r <= 0.0 || frequency * r >= nyquist) continue
                if (w > bestWeight) {
                    bestWeight = w; bestRatio = r
                    val mult = if (options.shiftHarmonicFamily) harmonic.toDouble() else 1.0
                    bestPhase = normalizedDegrees(mapping.phaseDegrees * mult) * PI / 180.0
                }
            }
            if (bestWeight > 0.0001 && (abs(bestRatio - 1.0) > 1e-8 || abs(bestPhase) > 1e-8)) {
                ratio[k] = bestRatio; weight[k] = bestWeight; phase[k] = bestPhase; active++
            }
        }
        return BinPlan(ratio, weight, phase, active)
    }

    private fun warpWithPlan(
        real: FloatArray, imag: FloatArray, outReal: FloatArray, outImag: FloatArray,
        prevPhase: FloatArray, mappedPhase: FloatArray, phaseSeen: BooleanArray,
        fftSize: Int, hop: Int, plan: BinPlan
    ) {
        outReal.fill(0f); outImag.fill(0f)
        val half = fftSize / 2
        outReal[0] = real[0]; outReal[half] = real[half]
        for (k in 1 until half) {
            val w = plan.weight[k]
            if (w <= 0.0001) {
                outReal[k] += real[k]; outImag[k] += imag[k]
                prevPhase[k] = kotlin.math.atan2(imag[k].toDouble(), real[k].toDouble()).toFloat()
                continue
            }
            val re = real[k].toDouble(); val im = imag[k].toDouble(); val magnitude = hypot(re, im)
            val sourcePhase = kotlin.math.atan2(im, re)
            val keep = (1.0 - w).coerceIn(0.0, 1.0)
            outReal[k] += (re * keep).toFloat(); outImag[k] += (im * keep).toFloat()
            val baseOmega = 2.0 * PI * k / fftSize
            val targetOmega: Double
            if (!phaseSeen[k]) {
                mappedPhase[k] = sourcePhase.toFloat(); phaseSeen[k] = true; targetOmega = baseOmega * plan.ratio[k]
            } else {
                val expected = 2.0 * PI * k * hop / fftSize
                val delta = principalPhase(sourcePhase - prevPhase[k] - expected)
                val trueOmega = baseOmega + delta / hop
                targetOmega = trueOmega * plan.ratio[k]
                mappedPhase[k] = principalPhase(mappedPhase[k] + targetOmega * hop).toFloat()
            }
            prevPhase[k] = sourcePhase.toFloat()
            val destinationBin = targetOmega * fftSize / (2.0 * PI)
            if (destinationBin <= 0.0 || destinationBin >= half - 1.0) {
                outReal[k] += (re * w).toFloat(); outImag[k] += (im * w).toFloat(); continue
            }
            val lower = floor(destinationBin).toInt().coerceIn(1, half - 1)
            val upper = min(half - 1, lower + 1)
            val uw = destinationBin - lower
            val mag = magnitude * w
            val ph = principalPhase(mappedPhase[k].toDouble() + plan.phaseRadians[k])
            val sr = cos(ph) * mag; val si = sin(ph) * mag
            outReal[lower] += (sr * (1.0 - uw)).toFloat(); outImag[lower] += (si * (1.0 - uw)).toFloat()
            if (upper != lower) { outReal[upper] += (sr * uw).toFloat(); outImag[upper] += (si * uw).toFloat() }
        }
        for (k in 1 until half) { outReal[fftSize - k] = outReal[k]; outImag[fftSize - k] = -outImag[k] }
    }

    private fun normalizedDegrees(value: Double): Double { var x = value % 360.0; if (x > 180.0) x -= 360.0; if (x <= -180.0) x += 360.0; return x }
    private fun principalPhase(value: Double): Double { var x = value; while (x > PI) x -= 2.0 * PI; while (x < -PI) x += 2.0 * PI; return x }
    private fun safeMultiply(a: Long, b: Long): Long { if (a == 0L || b == 0L) return 0L; require(a <= Long.MAX_VALUE / b) { "Audio is too large to address." }; return a * b }
}

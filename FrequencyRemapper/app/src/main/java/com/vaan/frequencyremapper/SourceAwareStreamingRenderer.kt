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

/**
 * v1.6 renderer. The source URI is decoded in chunks, a time-frequency source
 * mask is computed for every STFT frame, then category rules are applied only
 * to bins currently owned by that category above its confidence threshold.
 */
object SourceAwareStreamingRenderer {
    private data class IndividualPlan(
        val ratio: DoubleArray,
        val weight: DoubleArray,
        val phaseRadians: DoubleArray,
        val categoryOrdinal: IntArray,
        val activeCount: Int
    )

    fun estimatedDataBytes(source: StreamAudioSource): Long =
        safeMultiply(safeMultiply(source.totalFrames.coerceAtLeast(1L), source.channels.toLong()), 2L)

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
        objects: List<SpectralObject>,
        frequencyEdits: List<SpectralFrequencyEdit>,
        categoryRules: List<CategoryTransformRule>,
        options: PhaseRemapOptions = PhaseRemapOptions(),
        manualRegions: List<ManualMaskRegion> = emptyList(),
        soloCategory: AudioCategory? = null,
        soloConfidence: Double = 0.45,
        onProgress: (Float) -> Unit = {}
    ): ScalablePhaseRenderer.RenderInfo {
        require(source.sampleRate in 4000..192000) { "Unsupported sample rate ${source.sampleRate} Hz." }
        require(source.channels > 0) { "Audio has no channels." }

        val expectedData = estimatedDataBytes(source)
        val writer = ScalableWavChannelWriter(
            channel = channel,
            sampleRate = source.sampleRate,
            channels = source.channels,
            expectedFrames = source.totalFrames.coerceAtLeast(1L),
            forceRf64 = shouldUseRf64(source, expectedData)
        )

        val activeEdits = frequencyEdits.filter {
            it.enabled && it.sourceHz.isFinite() && it.targetHz.isFinite() && it.phaseDegrees.isFinite() &&
                it.sourceHz > 0.0 && it.targetHz > 0.0 &&
                (abs(it.targetHz - it.sourceHz) > 0.0001 || abs(normalizedDegrees(it.phaseDegrees)) > 0.0001)
        }
        val activeRules = categoryRules.filter {
            it.enabled && it.confidenceThreshold.isFinite() && it.phaseDegrees.isFinite() &&
                (it.value != null || abs(normalizedDegrees(it.phaseDegrees)) > 0.0001)
        }

        if (activeEdits.isEmpty() && activeRules.isEmpty() && soloCategory == null) {
            renderPassThrough(context, source, writer, onProgress)
            return writer.finish()
        }

        val fftSize = if (source.sampleRate > 50000) 8192 else 4096
        val hop = fftSize / 4
        val channels = source.channels
        val half = fftSize / 2
        val window = hammingWindow(fftSize)
        val windowSq = FloatArray(fftSize) { window[it] * window[it] }
        val masker = TimeFrequencyCategoryMasker(objects, source.sampleRate, fftSize, manualRegions)
        val individualPlan = compileIndividualPlan(source.sampleRate, fftSize, activeEdits, options)
        val rulesByCategory = Array<CategoryTransformRule?>(AudioCategory.entries.size) { null }
        for (rule in activeRules) rulesByCategory[rule.category.ordinal] = rule

        val input = Array(channels) { FloatArray(fftSize) }
        val ola = Array(channels) { FloatArray(fftSize) }
        val norm = FloatArray(fftSize)
        val prevPhase = Array(channels) { FloatArray(half + 1) }
        val mappedPhase = Array(channels) { FloatArray(half + 1) }
        val phaseSeen = Array(channels) { BooleanArray(half + 1) }
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val outReal = FloatArray(fftSize)
        val outImag = FloatArray(fftSize)
        val monoReal = FloatArray(fftSize)
        val monoImag = FloatArray(fftSize)
        val power = DoubleArray(half + 1)
        val outputChunk = Array(channels) { FloatArray(hop) }

        StreamingPcmFrameReader(context, source).use { reader ->
            var windowValid = reader.readInto(input, 0, fftSize)
            var writtenFrames = 0L
            var stftFrame = 0L

            while (windowValid > 0) {
                // One mono FFT drives the category mask for all channels.
                for (i in 0 until fftSize) {
                    var mono = 0f
                    for (ch in 0 until channels) mono += input[ch][i]
                    monoReal[i] = (mono / channels) * window[i]
                    monoImag[i] = 0f
                }
                FastFft.transform(monoReal, monoImag, false)
                for (k in 0..half) {
                    val re = monoReal[k].toDouble()
                    val im = monoImag[k].toDouble()
                    power[k] = re * re + im * im
                }
                val timeSeconds = writtenFrames.toDouble() / source.sampleRate
                val mask = masker.classify(power, timeSeconds)

                for (ch in 0 until channels) {
                    for (i in 0 until fftSize) {
                        real[i] = input[ch][i] * window[i]
                        imag[i] = 0f
                    }
                    FastFft.transform(real, imag, false)
                    warpSourceAware(
                        real = real,
                        imag = imag,
                        outReal = outReal,
                        outImag = outImag,
                        prevPhase = prevPhase[ch],
                        mappedPhase = mappedPhase[ch],
                        phaseSeen = phaseSeen[ch],
                        fftSize = fftSize,
                        hop = hop,
                        sampleRate = source.sampleRate,
                        mask = mask,
                        individual = individualPlan,
                        rules = rulesByCategory,
                        soloCategory = soloCategory,
                        soloConfidence = soloConfidence
                    )
                    FastFft.transform(outReal, outImag, true)
                    for (i in 0 until fftSize) ola[ch][i] += outReal[i] * window[i]
                }

                for (i in 0 until fftSize) norm[i] += windowSq[i]
                val toWrite = min(hop, windowValid)
                for (i in 0 until toWrite) {
                    val scale = 1f / max(norm[i], 1e-7f)
                    for (ch in 0 until channels) outputChunk[ch][i] = ola[ch][i] * scale
                }
                writer.writeInterleaved(outputChunk, toWrite)
                writtenFrames += toWrite
                stftFrame++
                if (stftFrame % 16L == 0L) {
                    val denominator = source.totalFrames.coerceAtLeast(writtenFrames).coerceAtLeast(1L)
                    onProgress((writtenFrames.toDouble() / denominator).toFloat().coerceIn(0f, 0.995f))
                }

                for (ch in 0 until channels) {
                    input[ch].copyInto(input[ch], 0, hop, fftSize)
                    input[ch].fill(0f, fftSize - hop, fftSize)
                    ola[ch].copyInto(ola[ch], 0, hop, fftSize)
                    ola[ch].fill(0f, fftSize - hop, fftSize)
                }
                norm.copyInto(norm, 0, hop, fftSize)
                norm.fill(0f, fftSize - hop, fftSize)

                val newlyRead = reader.readInto(input, fftSize - hop, hop)
                windowValid = max(0, windowValid - hop) + newlyRead
            }
        }

        onProgress(1f)
        return writer.finish()
    }

    private fun renderPassThrough(
        context: Context,
        source: StreamAudioSource,
        writer: ScalableWavChannelWriter,
        onProgress: (Float) -> Unit
    ) {
        val chunk = 8192
        val frames = Array(source.channels) { FloatArray(chunk) }
        StreamingPcmFrameReader(context, source).use { reader ->
            var written = 0L
            while (true) {
                val read = reader.readInto(frames, 0, chunk)
                if (read <= 0) break
                writer.writeInterleaved(frames, read)
                written += read
                val denominator = source.totalFrames.coerceAtLeast(written).coerceAtLeast(1L)
                onProgress((written.toDouble() / denominator).toFloat().coerceIn(0f, 0.995f))
            }
        }
        onProgress(1f)
    }

    private fun compileIndividualPlan(
        sampleRate: Int,
        fftSize: Int,
        edits: List<SpectralFrequencyEdit>,
        options: PhaseRemapOptions
    ): IndividualPlan {
        val half = fftSize / 2
        val ratio = DoubleArray(half + 1) { 1.0 }
        val weight = DoubleArray(half + 1)
        val phase = DoubleArray(half + 1)
        val category = IntArray(half + 1) { -1 }
        val binHz = sampleRate.toDouble() / fftSize
        val nyquist = sampleRate / 2.0
        var active = 0

        for (k in 1 until half) {
            val frequency = k * binHz
            var bestWeight = 0.0
            var bestRatio = 1.0
            var bestPhase = 0.0
            var bestCategory = -1

            for (edit in edits) {
                if (edit.sourceHz <= 0.0 || edit.targetHz <= 0.0 || edit.sourceHz >= nyquist || edit.targetHz >= nyquist) continue
                val harmonic = if (options.shiftHarmonicFamily) {
                    (frequency / edit.sourceHz).roundToInt().coerceAtLeast(1)
                } else 1
                if (harmonic > options.maxHarmonics) continue
                val center = edit.sourceHz * harmonic
                if (center <= 0.0 || center >= nyquist) continue
                val distance = abs(1200.0 * log2(frequency / center))
                if (distance > options.bandCents) continue
                val edge = (distance / options.bandCents).coerceIn(0.0, 1.0)
                val w = if (edge <= 0.62) 1.0 else {
                    0.5 * (1.0 + cos(PI * ((edge - 0.62) / 0.38)))
                }
                val r = edit.targetHz / edit.sourceHz
                if (frequency * r <= 0.0 || frequency * r >= nyquist) continue
                if (w > bestWeight) {
                    bestWeight = w
                    bestRatio = r
                    val multiplier = if (options.shiftHarmonicFamily) harmonic.toDouble() else 1.0
                    bestPhase = normalizedDegrees(edit.phaseDegrees * multiplier) * PI / 180.0
                    bestCategory = edit.category.ordinal
                }
            }

            if (bestWeight > 0.0001 && bestCategory >= 0) {
                ratio[k] = bestRatio
                weight[k] = bestWeight
                phase[k] = bestPhase
                category[k] = bestCategory
                active++
            }
        }
        return IndividualPlan(ratio, weight, phase, category, active)
    }

    private fun warpSourceAware(
        real: FloatArray,
        imag: FloatArray,
        outReal: FloatArray,
        outImag: FloatArray,
        prevPhase: FloatArray,
        mappedPhase: FloatArray,
        phaseSeen: BooleanArray,
        fftSize: Int,
        hop: Int,
        sampleRate: Int,
        mask: FrameCategoryMask,
        individual: IndividualPlan,
        rules: Array<CategoryTransformRule?>,
        soloCategory: AudioCategory?,
        soloConfidence: Double
    ) {
        outReal.fill(0f)
        outImag.fill(0f)
        val half = fftSize / 2
        val binHz = sampleRate.toDouble() / fftSize
        outReal[0] = if (soloCategory == null) real[0] else 0f
        outImag[0] = 0f
        outReal[half] = if (soloCategory == null) real[half] else 0f
        outImag[half] = 0f

        for (k in 1 until half) {
            val re = real[k].toDouble()
            val im = imag[k].toDouble()
            val sourcePhase = kotlin.math.atan2(im, re)
            val categoryOrdinal = mask.categoryOrdinal[k]
            val confidence = mask.confidence[k].toDouble()

            if (soloCategory != null) {
                prevPhase[k] = sourcePhase.toFloat()
                if (categoryOrdinal == soloCategory.ordinal && confidence >= soloConfidence) {
                    outReal[k] = real[k]
                    outImag[k] = imag[k]
                }
                continue
            }

            var transformRatio = 1.0
            var transformPhase = 0.0
            var transformWeight = 0.0

            // A user's explicit per-frequency edit wins inside its own category.
            if (individual.weight[k] > 0.0001 && individual.categoryOrdinal[k] == categoryOrdinal && confidence >= 0.25) {
                transformRatio = individual.ratio[k]
                transformPhase = individual.phaseRadians[k]
                transformWeight = individual.weight[k]
            } else {
                val rule = rules.getOrNull(categoryOrdinal)
                if (rule != null && rule.enabled && confidence >= rule.confidenceThreshold) {
                    val frequency = k * binHz
                    val target = rule.targetFor(frequency)
                    if (target.isFinite() && target > 0.0 && target < sampleRate / 2.0) {
                        transformRatio = target / frequency
                    }
                    transformPhase = normalizedDegrees(rule.phaseDegrees) * PI / 180.0
                    if (abs(transformRatio - 1.0) > 1e-8 || abs(transformPhase) > 1e-8) {
                        transformWeight = 1.0
                    }
                }
            }

            if (transformWeight <= 0.0001) {
                outReal[k] += real[k]
                outImag[k] += imag[k]
                prevPhase[k] = sourcePhase.toFloat()
                continue
            }

            val magnitude = hypot(re, im)
            val keep = (1.0 - transformWeight).coerceIn(0.0, 1.0)
            outReal[k] += (re * keep).toFloat()
            outImag[k] += (im * keep).toFloat()

            val baseOmega = 2.0 * PI * k / fftSize
            val targetOmega: Double
            if (!phaseSeen[k]) {
                mappedPhase[k] = sourcePhase.toFloat()
                phaseSeen[k] = true
                targetOmega = baseOmega * transformRatio
            } else {
                val expectedAdvance = 2.0 * PI * k * hop / fftSize
                val delta = principalPhase(sourcePhase - prevPhase[k] - expectedAdvance)
                val trueOmega = baseOmega + delta / hop
                targetOmega = trueOmega * transformRatio
                mappedPhase[k] = principalPhase(mappedPhase[k] + targetOmega * hop).toFloat()
            }
            prevPhase[k] = sourcePhase.toFloat()

            val destinationBin = targetOmega * fftSize / (2.0 * PI)
            if (destinationBin <= 0.0 || destinationBin >= half - 1.0) {
                outReal[k] += (re * transformWeight).toFloat()
                outImag[k] += (im * transformWeight).toFloat()
                continue
            }

            val lower = floor(destinationBin).toInt().coerceIn(1, half - 1)
            val upper = min(half - 1, lower + 1)
            val upperWeight = destinationBin - lower
            val shiftedMagnitude = magnitude * transformWeight
            val shiftedPhase = principalPhase(mappedPhase[k].toDouble() + transformPhase)
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

    private fun safeMultiply(a: Long, b: Long): Long {
        require(a >= 0L && b >= 0L) { "Negative audio size." }
        if (a == 0L || b == 0L) return 0L
        require(a <= Long.MAX_VALUE / b) { "Audio is too large to address." }
        return a * b
    }
}

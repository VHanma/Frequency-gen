package com.vaan.frequencyremapper

import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * Full-quality renderer designed for long audio.
 * Mapping selection is compiled once per FFT bin instead of searched for every
 * frame. Output is written directly to a seekable channel. Classic RIFF WAV is
 * used up to its size limit; RF64 is selected automatically above it.
 */
object ScalablePhaseRenderer {
    private data class BinPlan(
        val ratio: DoubleArray,
        val weight: DoubleArray,
        val phaseRadians: DoubleArray,
        val activeCount: Int
    )

    data class RenderInfo(
        val bytesWritten: Long,
        val rf64: Boolean,
        val framesWritten: Long
    )

    fun estimatedDataBytes(source: PcmSource): Long =
        safeMultiply(safeMultiply(source.totalFrames, source.channels.toLong()), 2L)

    fun estimatedFileBytes(source: PcmSource): Long {
        val data = estimatedDataBytes(source)
        return data + if (requiresRf64(data)) 80L else 44L
    }

    fun requiresRf64(dataBytes: Long): Boolean = dataBytes > 0xfffffff0L - 36L

    fun renderToChannel(
        source: PcmSource,
        channel: FileChannel,
        mappings: List<PhaseFrequencyMapping>,
        options: PhaseRemapOptions = PhaseRemapOptions(),
        onProgress: (Float) -> Unit = {}
    ): RenderInfo {
        require(source.totalFrames > 0) { "The decoded audio is empty." }
        require(source.file.exists()) { "Decoded source audio is missing." }

        val nyquist = source.sampleRate / 2.0
        val active = mappings.filter {
            it.enabled && it.sourceHz.isFinite() && it.targetHz.isFinite() && it.phaseDegrees.isFinite() &&
                it.sourceHz > 0.0 && it.targetHz > 0.0 &&
                it.sourceHz < nyquist && it.targetHz < nyquist &&
                (abs(it.targetHz - it.sourceHz) > 0.0001 || abs(normalizedDegrees(it.phaseDegrees)) > 0.0001)
        }

        val expectedData = estimatedDataBytes(source)
        val writer = ScalableWavChannelWriter(
            channel = channel,
            sampleRate = source.sampleRate,
            channels = source.channels,
            expectedFrames = source.totalFrames,
            forceRf64 = requiresRf64(expectedData)
        )

        if (active.isEmpty()) {
            renderPassThrough(source, writer, onProgress)
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

        PcmFrameReader(source, fftSize).use { reader ->
            reader.readInto(inputFrames, 0, fftSize)
            var written = 0L
            var stftFrame = 0
            while (written < source.totalFrames) {
                for (ch in 0 until channels) {
                    for (i in 0 until fftSize) {
                        real[i] = inputFrames[ch][i] * window[i]
                        imag[i] = 0f
                    }
                    FastFft.transform(real, imag, inverse = false)
                    warpWithPlan(
                        real, imag, outReal, outImag,
                        prevPhase[ch], mappedPhase[ch], phaseSeen[ch],
                        fftSize, hop, plan
                    )
                    FastFft.transform(outReal, outImag, inverse = true)
                    for (i in 0 until fftSize) ola[ch][i] += outReal[i] * window[i]
                }

                for (i in 0 until fftSize) norm[i] += windowSquared[i]
                val toWrite = min(hop.toLong(), source.totalFrames - written).toInt()
                for (i in 0 until toWrite) {
                    val scale = 1f / max(norm[i], 1e-7f)
                    for (ch in 0 until channels) outputChunk[ch][i] = ola[ch][i] * scale
                }
                writer.writeInterleaved(outputChunk, toWrite)
                written += toWrite
                stftFrame++
                if (stftFrame % 16 == 0 || written >= source.totalFrames) {
                    onProgress((written.toDouble() / source.totalFrames).toFloat())
                }

                for (ch in 0 until channels) {
                    inputFrames[ch].copyInto(inputFrames[ch], 0, hop, fftSize)
                    inputFrames[ch].fill(0f, fftSize - hop, fftSize)
                    ola[ch].copyInto(ola[ch], 0, hop, fftSize)
                    ola[ch].fill(0f, fftSize - hop, fftSize)
                }
                norm.copyInto(norm, 0, hop, fftSize)
                norm.fill(0f, fftSize - hop, fftSize)
                reader.readInto(inputFrames, fftSize - hop, hop)
            }
        }

        onProgress(1f)
        return writer.finish()
    }

    private fun renderPassThrough(
        source: PcmSource,
        writer: ScalableWavChannelWriter,
        onProgress: (Float) -> Unit
    ) {
        val chunk = 8192
        val frames = Array(source.channels) { FloatArray(chunk) }
        PcmFrameReader(source, chunk).use { reader ->
            var written = 0L
            while (written < source.totalFrames) {
                val need = min(chunk.toLong(), source.totalFrames - written).toInt()
                val read = reader.readInto(frames, 0, need)
                if (read <= 0) break
                writer.writeInterleaved(frames, read)
                written += read
                onProgress((written.toDouble() / source.totalFrames).toFloat())
            }
        }
    }

    private fun compilePlan(
        sampleRate: Int,
        fftSize: Int,
        mappings: List<PhaseFrequencyMapping>,
        options: PhaseRemapOptions
    ): BinPlan {
        val half = fftSize / 2
        val ratio = DoubleArray(half + 1) { 1.0 }
        val weight = DoubleArray(half + 1)
        val phase = DoubleArray(half + 1)
        val binHz = sampleRate.toDouble() / fftSize
        val nyquist = sampleRate / 2.0
        var activeCount = 0

        for (k in 1 until half) {
            val frequency = k * binHz
            var bestWeight = 0.0
            var bestRatio = 1.0
            var bestPhase = 0.0

            for (mapping in mappings) {
                val source = mapping.sourceHz
                val target = mapping.targetHz
                val harmonic = if (options.shiftHarmonicFamily) {
                    (frequency / source).roundToInt().coerceAtLeast(1)
                } else 1
                if (harmonic > options.maxHarmonics) continue
                val center = source * harmonic
                if (center <= 0.0 || center >= nyquist) continue

                val distance = abs(1200.0 * log2(frequency / center))
                if (distance > options.bandCents) continue
                val edge = (distance / options.bandCents).coerceIn(0.0, 1.0)
                val w = if (edge <= 0.62) 1.0 else {
                    val x = (edge - 0.62) / 0.38
                    0.5 * (1.0 + cos(PI * x))
                }
                val r = target / source
                if (frequency * r <= 0.0 || frequency * r >= nyquist) continue
                if (w > bestWeight) {
                    bestWeight = w
                    bestRatio = r
                    val mult = if (options.shiftHarmonicFamily) harmonic.toDouble() else 1.0
                    bestPhase = normalizedDegrees(mapping.phaseDegrees * mult) * PI / 180.0
                }
            }

            if (bestWeight > 0.0001 && (abs(bestRatio - 1.0) > 1e-8 || abs(bestPhase) > 1e-8)) {
                ratio[k] = bestRatio
                weight[k] = bestWeight
                phase[k] = bestPhase
                activeCount++
            }
        }
        return BinPlan(ratio, weight, phase, activeCount)
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

    private fun safeMultiply(a: Long, b: Long): Long {
        require(a >= 0 && b >= 0) { "Negative audio size." }
        if (a == 0L || b == 0L) return 0L
        require(a <= Long.MAX_VALUE / b) { "Audio is too large to address on this device." }
        return a * b
    }
}

class ScalableWavChannelWriter(
    private val channel: FileChannel,
    private val sampleRate: Int,
    private val channels: Int,
    private val expectedFrames: Long,
    forceRf64: Boolean
) {
    private val rf64 = forceRf64
    private val headerSize = if (rf64) 80 else 44
    private var dataBytes = 0L
    private var finished = false

    init {
        channel.position(0)
        channel.truncate(0)
        writeFully(ByteBuffer.allocate(headerSize))
    }

    fun writeInterleaved(samples: Array<FloatArray>, frames: Int, offset: Int = 0) {
        if (frames <= 0) return
        val bytes = frames * channels * 2
        val buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            for (ch in 0 until channels) {
                val v = samples[ch][offset + i].coerceIn(-1f, 1f)
                buffer.putShort((v * 32767f).roundToInt().toShort())
            }
        }
        buffer.flip()
        writeFully(buffer)
        dataBytes += bytes.toLong()
    }

    fun finish(): ScalablePhaseRenderer.RenderInfo {
        if (finished) {
            return ScalablePhaseRenderer.RenderInfo(headerSize + dataBytes, rf64, dataBytes / (channels * 2L))
        }
        finished = true
        val end = channel.position()
        val header = if (rf64) rf64Header() else riffHeader()
        channel.position(0)
        writeFully(header)
        channel.position(end)
        channel.force(true)
        return ScalablePhaseRenderer.RenderInfo(end, rf64, dataBytes / (channels * 2L))
    }

    private fun riffHeader(): ByteBuffer {
        require(dataBytes <= 0xffffffffL - 36L) { "Classic WAV exceeded 4 GB; RF64 was required." }
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((36L + dataBytes).toInt())
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1.toShort())
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * channels * 2)
        b.putShort((channels * 2).toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes.toInt())
        b.flip()
        return b
    }

    private fun rf64Header(): ByteBuffer {
        val sampleCount = dataBytes / (channels * 2L)
        val riffSize = 72L + dataBytes
        val b = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RF64".toByteArray(Charsets.US_ASCII))
        b.putInt(-1)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("ds64".toByteArray(Charsets.US_ASCII))
        b.putInt(28)
        b.putLong(riffSize)
        b.putLong(dataBytes)
        b.putLong(sampleCount.coerceAtMost(expectedFrames))
        b.putInt(0)
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1.toShort())
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * channels * 2)
        b.putShort((channels * 2).toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(-1)
        b.flip()
        return b
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }
}

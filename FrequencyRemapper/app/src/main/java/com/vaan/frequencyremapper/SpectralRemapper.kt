package com.vaan.frequencyremapper

import android.content.Context
import java.io.File
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


data class RemapOptions(
    val shiftHarmonicFamily: Boolean = true,
    val bandCents: Double = 48.0,
    val maxHarmonics: Int = 48
)

object SpectralRemapper {
    private data class Transform(val ratio: Double, val weight: Double)

    fun render(
        context: Context,
        source: PcmSource,
        mappings: List<FrequencyMapping>,
        options: RemapOptions = RemapOptions(),
        onProgress: (Float) -> Unit = {}
    ): File {
        require(source.totalFrames > 0) { "The decoded audio is empty." }
        val nyquist = source.sampleRate / 2.0
        val active = mappings.filter {
            it.enabled &&
                it.sourceHz.isFinite() && it.targetHz.isFinite() &&
                it.sourceHz > 0.0 && it.targetHz > 0.0 &&
                it.sourceHz < nyquist && it.targetHz < nyquist &&
                abs(it.targetHz - it.sourceHz) > 0.0001
        }

        val output = File.createTempFile("frequency_remapped_", ".wav", context.cacheDir)
        if (active.isEmpty()) {
            renderPassThrough(source, output, onProgress)
            return output
        }

        val fftSize = if (source.sampleRate > 50000) 8192 else 4096
        val hop = fftSize / 4
        val channels = source.channels
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
            WavFileWriter(output, source.sampleRate, channels).use { writer ->
                var written = 0L
                var stftFrame = 0

                while (written < source.totalFrames) {
                    for (ch in 0 until channels) {
                        for (i in 0 until fftSize) {
                            real[i] = inputFrames[ch][i] * window[i]
                            imag[i] = 0f
                        }

                        FastFft.transform(real, imag, inverse = false)
                        warpSpectrum(
                            real = real,
                            imag = imag,
                            outReal = outReal,
                            outImag = outImag,
                            prevPhase = prevPhase[ch],
                            mappedPhase = mappedPhase[ch],
                            phaseSeen = phaseSeen[ch],
                            sampleRate = source.sampleRate,
                            fftSize = fftSize,
                            hop = hop,
                            mappings = active,
                            options = options
                        )
                        FastFft.transform(outReal, outImag, inverse = true)

                        for (i in 0 until fftSize) {
                            ola[ch][i] += outReal[i] * window[i]
                        }
                    }

                    for (i in 0 until fftSize) {
                        norm[i] += windowSquared[i]
                    }

                    val toWrite = min(hop.toLong(), source.totalFrames - written).toInt()
                    for (i in 0 until toWrite) {
                        val scale = 1f / max(norm[i], 1e-7f)
                        for (ch in 0 until channels) {
                            outputChunk[ch][i] = ola[ch][i] * scale
                        }
                    }
                    writer.writeInterleaved(outputChunk, toWrite)
                    written += toWrite
                    stftFrame++
                    if (stftFrame % 12 == 0 || written >= source.totalFrames) {
                        onProgress((written.toDouble() / source.totalFrames).toFloat())
                    }

                    for (ch in 0 until channels) {
                        inputFrames[ch].copyInto(
                            inputFrames[ch],
                            destinationOffset = 0,
                            startIndex = hop,
                            endIndex = fftSize
                        )
                        ola[ch].copyInto(
                            ola[ch],
                            destinationOffset = 0,
                            startIndex = hop,
                            endIndex = fftSize
                        )
                        inputFrames[ch].fill(0f, fftSize - hop, fftSize)
                        ola[ch].fill(0f, fftSize - hop, fftSize)
                    }
                    norm.copyInto(norm, destinationOffset = 0, startIndex = hop, endIndex = fftSize)
                    norm.fill(0f, fftSize - hop, fftSize)
                    reader.readInto(inputFrames, fftSize - hop, hop)
                }
            }
        }

        onProgress(1f)
        return output
    }

    private fun renderPassThrough(source: PcmSource, output: File, onProgress: (Float) -> Unit) {
        val chunk = 4096
        val frames = Array(source.channels) { FloatArray(chunk) }
        PcmFrameReader(source, chunk).use { reader ->
            WavFileWriter(output, source.sampleRate, source.channels).use { writer ->
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
    }

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
            val transform = findTransform(frequency, nyquist, mappings, options)

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
            if (destinationBin <= 0.0 || destinationBin >= half - 1.0) {
                outReal[k] += (re * transform.weight).toFloat()
                outImag[k] += (im * transform.weight).toFloat()
                continue
            }

            val lower = floor(destinationBin).toInt().coerceIn(1, half - 1)
            val upper = min(half - 1, lower + 1)
            val upperWeight = destinationBin - lower
            val lowerWeight = 1.0 - upperWeight
            val shiftedMagnitude = magnitude * transform.weight
            val shiftedPhase = mappedPhase[k].toDouble()
            val sr = cos(shiftedPhase) * shiftedMagnitude
            val si = sin(shiftedPhase) * shiftedMagnitude

            outReal[lower] += (sr * lowerWeight).toFloat()
            outImag[lower] += (si * lowerWeight).toFloat()
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
        options: RemapOptions
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
            if (!options.shiftHarmonicFamily && abs(frequency - source) > source * 0.08) continue

            val cents = 1200.0 * log2(frequency / center)
            val distance = abs(cents)
            if (distance > options.bandCents) continue

            val edge = (distance / options.bandCents).coerceIn(0.0, 1.0)
            val weight = if (edge <= 0.62) {
                1.0
            } else {
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

object FastFft {
    fun transform(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        require(real.size == imag.size)
        val n = real.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two." }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var length = 2
        while (length <= n) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / length
            val stepR = cos(angle)
            val stepI = sin(angle)
            val half = length / 2
            var start = 0
            while (start < n) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until half) {
                    val even = start + offset
                    val odd = even + half
                    val vr = real[odd] * wr - imag[odd] * wi
                    val vi = real[odd] * wi + imag[odd] * wr
                    val ur = real[even].toDouble()
                    val ui = imag[even].toDouble()

                    real[even] = (ur + vr).toFloat()
                    imag[even] = (ui + vi).toFloat()
                    real[odd] = (ur - vr).toFloat()
                    imag[odd] = (ui - vi).toFloat()

                    val nextWr = wr * stepR - wi * stepI
                    wi = wr * stepI + wi * stepR
                    wr = nextWr
                }
                start += length
            }
            length = length shl 1
        }

        if (inverse) {
            val inv = 1f / n
            for (i in 0 until n) {
                real[i] *= inv
                imag[i] *= inv
            }
        }
    }
}

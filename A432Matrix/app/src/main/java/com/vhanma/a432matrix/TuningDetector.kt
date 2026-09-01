package com.vhanma.a432matrix

import kotlin.math.*
import java.util.ArrayDeque

/**
 * Estimates the concert-A reference from pitched spectral peaks.
 * It does NOT look for a literal 440 Hz bin. Instead each peak votes for the
 * tuning offset of the nearest equal-tempered note, then votes are combined
 * on a circular cents axis so octave/harmonic content can agree.
 */
class TuningDetector(private val sampleRate: Int = 48_000) {
    data class Result(
        val referenceHz: Double,
        val confidence: Double,
        val classification: String,
        val shouldRetune: Boolean
    )

    private val history = ArrayDeque<Pair<Double, Double>>()
    private val fftSize = 4096

    @Synchronized
    fun analyzeStereoPcm(pcm: ShortArray, count: Int): Result? {
        if (count < fftSize * 2) return null
        val mono = DoubleArray(fftSize)
        var rms = 0.0
        for (i in 0 until fftSize) {
            val j = i * 2
            val v = ((pcm[j].toInt() + pcm[j + 1].toInt()) * 0.5) / 32768.0
            val w = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (fftSize - 1))
            mono[i] = v * w
            rms += v * v
        }
        rms = sqrt(rms / fftSize)
        if (rms < 0.004) return null

        val re = mono.copyOf()
        val im = DoubleArray(fftSize)
        fft(re, im)

        data class Peak(val mag: Double, val hz: Double)
        val peaks = ArrayList<Peak>()
        val minBin = max(2, (70.0 * fftSize / sampleRate).toInt())
        val maxBin = min(fftSize / 2 - 2, (4000.0 * fftSize / sampleRate).toInt())
        for (k in minBin + 1 until maxBin) {
            val m = re[k] * re[k] + im[k] * im[k]
            val ml = re[k - 1] * re[k - 1] + im[k - 1] * im[k - 1]
            val mr = re[k + 1] * re[k + 1] + im[k + 1] * im[k + 1]
            if (m > ml && m >= mr && m > 1e-7) {
                // Quadratic interpolation gives considerably finer tuning than raw FFT bins.
                val denom = (ml - 2.0 * m + mr)
                val delta = if (abs(denom) > 1e-20) 0.5 * (ml - mr) / denom else 0.0
                val hz = (k + delta.coerceIn(-0.5, 0.5)) * sampleRate / fftSize
                peaks.add(Peak(m, hz))
            }
        }
        if (peaks.size < 5) return null
        peaks.sortByDescending { it.mag }

        var sumX = 0.0
        var sumY = 0.0
        var sumW = 0.0
        val use = min(48, peaks.size)
        for (i in 0 until use) {
            val p = peaks[i]
            // MIDI coordinate under A4=440. The fractional remainder is tuning offset.
            val midi = 69.0 + 12.0 * log2(p.hz / 440.0)
            val nearest = round(midi)
            var cents = (midi - nearest) * 100.0
            while (cents > 50.0) cents -= 100.0
            while (cents <= -50.0) cents += 100.0
            val theta = cents / 100.0 * 2.0 * Math.PI
            val weight = sqrt(p.mag).coerceAtMost(1e4)
            sumX += cos(theta) * weight
            sumY += sin(theta) * weight
            sumW += weight
        }
        if (sumW <= 0.0) return null
        val vector = sqrt(sumX * sumX + sumY * sumY) / sumW
        var cents = atan2(sumY, sumX) / (2.0 * Math.PI) * 100.0
        if (cents > 50) cents -= 100.0
        if (cents <= -50) cents += 100.0
        val reference = 440.0 * 2.0.pow(cents / 1200.0)

        // Keep only credible frames, then use weighted circular temporal consensus.
        if (vector >= 0.22 && reference in 426.0..446.0) {
            history.addLast(reference to vector)
            while (history.size > 18) history.removeFirst()
        }
        if (history.size < 5) return null

        var tx = 0.0
        var ty = 0.0
        var tw = 0.0
        for ((hz, conf) in history) {
            val c = 1200.0 * log2(hz / 440.0)
            val th = c / 100.0 * 2.0 * Math.PI
            tx += cos(th) * conf
            ty += sin(th) * conf
            tw += conf
        }
        val temporalStrength = (sqrt(tx * tx + ty * ty) / tw).coerceIn(0.0, 1.0)
        val tc = atan2(ty, tx) / (2.0 * Math.PI) * 100.0
        val stableHz = 440.0 * 2.0.pow(tc / 1200.0)
        val confidence = (temporalStrength * min(1.0, history.size / 10.0)).coerceIn(0.0, 1.0)

        val is440 = stableHz in 438.5..441.5 && confidence >= 0.68
        val label = when {
            is440 -> "A440-family"
            stableHz in 430.5..433.5 && confidence >= 0.60 -> "A432-family"
            confidence < 0.48 -> "Unknown / bypass"
            else -> "A≈${"%.2f".format(stableHz)} / bypass"
        }
        return Result(stableHz, confidence, label, is440)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wLenR = cos(ang); val wLenI = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val uR = re[i + k]; val uI = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = uR + vr; im[i + k] = uI + vi
                    re[i + k + len / 2] = uR - vr; im[i + k + len / 2] = uI - vi
                    val nwr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR; wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

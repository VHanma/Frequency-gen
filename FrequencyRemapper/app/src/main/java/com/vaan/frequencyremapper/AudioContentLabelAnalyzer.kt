package com.vaan.frequencyremapper

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Lightweight mixed-audio source tagging. These tags are spectral estimates,
 * not source-separated stems. The shifted-vocal scan deliberately checks
 * several scaled formant regions so voice-like material can still be flagged
 * after speed/pitch changes.
 */
data class FrequencyContentTags(
    val midi: Int,
    val tags: List<String>,
    val vocalScore: Double,
    val instrumentScore: Double,
    val bassScore: Double,
    val shiftedVocalScore: Double,
    val bestVoiceScale: Double
)

object AudioContentLabelAnalyzer {
    private data class Acc(
        var frames: Int = 0,
        var local: Double = 0.0,
        var harmonicity: Double = 0.0,
        var vocal: Double = 0.0,
        var shiftedVocal: Double = 0.0,
        var bass: Double = 0.0,
        var instrument: Double = 0.0,
        var bestScaleWeighted: Double = 0.0,
        var bestScaleWeight: Double = 0.0
    )

    private val voiceScales = doubleArrayOf(0.55, 0.70, 0.85, 1.0, 1.18, 1.42, 1.72, 2.05)

    fun analyze(
        source: PcmSource,
        notes: List<DetectedNote>,
        onProgress: (Float) -> Unit = {}
    ): Map<Int, FrequencyContentTags> {
        if (notes.isEmpty()) return emptyMap()

        val fftSize = when {
            source.sampleRate >= 88200 -> 8192
            source.sampleRate >= 32000 -> 4096
            else -> 2048
        }
        val hop = fftSize / 2
        val channels = source.channels
        val frames = Array(channels) { FloatArray(fftSize) }
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val power = DoubleArray(fftSize / 2 + 1)
        val window = hammingWindow(fftSize)
        val binHz = source.sampleRate.toDouble() / fftSize
        val nyquist = source.sampleRate / 2.0
        val acc = notes.associate { it.midi to Acc() }.toMutableMap()

        PcmFrameReader(source, fftSize).use { reader ->
            var valid = reader.readInto(frames, 0, fftSize)
            var consumed = 0L
            var frameNumber = 0
            while (valid > 0 && consumed < source.totalFrames) {
                for (i in 0 until fftSize) {
                    var mono = 0f
                    for (ch in 0 until channels) mono += frames[ch][i]
                    real[i] = (mono / channels) * window[i]
                    imag[i] = 0f
                }
                FastFft.transform(real, imag, inverse = false)
                for (k in power.indices) {
                    val re = real[k].toDouble()
                    val im = imag[k].toDouble()
                    power[k] = re * re + im * im
                }

                val broad = bandPower(power, binHz, 70.0, min(7000.0, nyquist - binHz)).coerceAtLeast(1e-20)
                val lowBandRatio = bandPower(power, binHz, 35.0, min(320.0, nyquist)) / broad
                val flatness = spectralFlatness(power, binHz, 90.0, min(6500.0, nyquist - binHz))

                val scaleScores = DoubleArray(voiceScales.size)
                for (s in voiceScales.indices) {
                    scaleScores[s] = formantEnvelopeScore(power, binHz, nyquist, voiceScales[s], broad)
                }
                var normalVoice = 0.0
                var shiftedVoice = 0.0
                var bestShiftScale = 1.0
                for (s in voiceScales.indices) {
                    val scale = voiceScales[s]
                    val score = scaleScores[s]
                    if (scale in 0.82..1.25) {
                        if (score > normalVoice) normalVoice = score
                    } else if (score > shiftedVoice) {
                        shiftedVoice = score
                        bestShiftScale = scale
                    }
                }

                for (note in notes) {
                    val a = acc[note.midi] ?: continue
                    val f = note.frequencyHz
                    if (f <= 20.0 || f >= nyquist) continue
                    val localWidth = max(binHz * 1.8, f * 0.018)
                    val local = bandPower(power, binHz, f - localWidth, f + localWidth)
                    val localNorm = (local / broad).coerceIn(0.0, 1.0)
                    if (localNorm < 1e-6) continue

                    var harmonicEnergy = 0.0
                    var harmonicCount = 0
                    var h = 1
                    while (h <= 12) {
                        val hf = f * h
                        if (hf >= min(6000.0, nyquist - binHz)) break
                        val hw = max(binHz * 1.5, hf * 0.008)
                        harmonicEnergy += bandPower(power, binHz, hf - hw, hf + hw)
                        harmonicCount++
                        h++
                    }
                    val harmonicity = if (harmonicCount > 0) {
                        (harmonicEnergy / broad).pow(0.55).coerceIn(0.0, 1.0)
                    } else 0.0

                    val voicedStructure = (0.62 * harmonicity + 0.38 * (1.0 - flatness)).coerceIn(0.0, 1.0)
                    val vocalEvidence = (voicedStructure * normalVoice * (0.35 + 0.65 * sqrt(localNorm))).coerceIn(0.0, 1.0)
                    val shiftedEvidence = (voicedStructure * shiftedVoice * (0.35 + 0.65 * sqrt(localNorm))).coerceIn(0.0, 1.0)
                    val instrumentEvidence = (harmonicity * (0.55 + 0.45 * (1.0 - normalVoice * 0.65))).coerceIn(0.0, 1.0)
                    val bassEvidence = if (f <= 330.0) {
                        (sqrt(lowBandRatio.coerceIn(0.0, 1.0)) * (0.45 + 0.55 * sqrt(localNorm))).coerceIn(0.0, 1.0)
                    } else 0.0

                    a.frames++
                    a.local += localNorm
                    a.harmonicity += harmonicity
                    a.vocal += vocalEvidence
                    a.shiftedVocal += shiftedEvidence
                    a.instrument += instrumentEvidence
                    a.bass += bassEvidence
                    if (shiftedEvidence > 0.02) {
                        a.bestScaleWeighted += bestShiftScale * shiftedEvidence
                        a.bestScaleWeight += shiftedEvidence
                    }
                }

                frameNumber++
                consumed = min(source.totalFrames, consumed + hop)
                if (frameNumber % 18 == 0) {
                    onProgress((consumed.toDouble() / max(1L, source.totalFrames)).toFloat())
                }
                for (ch in 0 until channels) {
                    frames[ch].copyInto(frames[ch], 0, hop, fftSize)
                    frames[ch].fill(0f, fftSize - hop, fftSize)
                }
                valid = reader.readInto(frames, fftSize - hop, hop)
            }
        }

        val noteEnergyMax = notes.maxOfOrNull { it.energy }?.coerceAtLeast(1e-20) ?: 1.0
        val result = LinkedHashMap<Int, FrequencyContentTags>()
        for (note in notes) {
            val a = acc[note.midi] ?: Acc()
            val n = max(1, a.frames).toDouble()
            val vocal = (a.vocal / n).coerceIn(0.0, 1.0)
            val shifted = (a.shiftedVocal / n).coerceIn(0.0, 1.0)
            val instrument = (a.instrument / n).coerceIn(0.0, 1.0)
            val bass = (a.bass / n).coerceIn(0.0, 1.0)
            val relativeEnergy = (note.energy / noteEnergyMax).coerceIn(0.0, 1.0)
            val bestScale = if (a.bestScaleWeight > 1e-9) a.bestScaleWeighted / a.bestScaleWeight else 1.0

            val tags = ArrayList<String>()
            strengthTag("VOCAL", vocal)?.let(tags::add)
            strengthTag("INSTRUMENT", instrument)?.let(tags::add)
            if (note.frequencyHz <= 330.0) strengthTag("BASS", bass)?.let(tags::add)

            val shiftedDominant = shifted > 0.20 && shifted > vocal * 1.12
            if (shiftedDominant) {
                val speedText = if (bestScale >= 1.30) "SPED-UP VOCAL" else "SHIFTED VOCAL"
                tags += if (shifted >= 0.48) "$speedText LIKELY ×${formatScale(bestScale)}" else "$speedText POSSIBLE ×${formatScale(bestScale)}"
            }

            val hiddenEvidence = max(vocal, shifted)
            if (hiddenEvidence >= 0.20 && relativeEnergy < 0.34) {
                tags += if (hiddenEvidence >= 0.48) "HIDDEN VOCAL LIKELY" else "HIDDEN VOCAL POSSIBLE"
            }

            if (tags.isEmpty()) {
                tags += if (note.frequencyHz < 300.0) "LOW TONAL" else "TONAL"
            }
            result[note.midi] = FrequencyContentTags(
                midi = note.midi,
                tags = tags.distinct().take(6),
                vocalScore = vocal,
                instrumentScore = instrument,
                bassScore = bass,
                shiftedVocalScore = shifted,
                bestVoiceScale = bestScale
            )
        }
        onProgress(1f)
        return result
    }

    private fun strengthTag(name: String, score: Double): String? = when {
        score >= 0.58 -> "$name STRONG"
        score >= 0.31 -> "$name NEUTRAL"
        score >= 0.13 -> "$name WEAK"
        else -> null
    }

    private fun formantEnvelopeScore(
        power: DoubleArray,
        binHz: Double,
        nyquist: Double,
        scale: Double,
        broad: Double
    ): Double {
        fun ratio(lo: Double, hi: Double): Double {
            val a = max(60.0, lo * scale)
            val b = min(nyquist - binHz, hi * scale)
            if (b <= a) return 0.0
            return (bandPower(power, binHz, a, b) / broad).coerceIn(0.0, 1.0)
        }
        val f1 = ratio(250.0, 950.0)
        val f2 = ratio(700.0, 2400.0)
        val f3 = ratio(1600.0, 3900.0)
        if (f1 <= 1e-8 || f2 <= 1e-8) return 0.0
        val geometric = (f1 * f2 * max(f3, 0.004)).pow(1.0 / 3.0)
        val balance = exp(-1.4 * kotlin.math.abs(ln((f2 + 1e-9) / (f1 + 1e-9))))
        return (geometric * 3.4 * (0.72 + 0.28 * balance)).coerceIn(0.0, 1.0)
    }

    private fun bandPower(power: DoubleArray, binHz: Double, loHz: Double, hiHz: Double): Double {
        if (hiHz <= loHz) return 0.0
        val lo = max(0, (loHz / binHz).toInt())
        val hi = min(power.lastIndex, (hiHz / binHz).toInt())
        var sum = 0.0
        for (k in lo..hi) sum += power[k]
        return sum
    }

    private fun spectralFlatness(power: DoubleArray, binHz: Double, loHz: Double, hiHz: Double): Double {
        val lo = max(1, (loHz / binHz).toInt())
        val hi = min(power.lastIndex, (hiHz / binHz).toInt())
        if (hi <= lo) return 1.0
        var logSum = 0.0
        var arithmetic = 0.0
        var count = 0
        for (k in lo..hi) {
            val p = max(1e-20, power[k])
            logSum += ln(p)
            arithmetic += p
            count++
        }
        if (count == 0 || arithmetic <= 0.0) return 1.0
        val geometric = exp(logSum / count)
        return (geometric / (arithmetic / count)).coerceIn(0.0, 1.0)
    }

    private fun formatScale(scale: Double): String = String.format(java.util.Locale.US, "%.2f", scale)
}

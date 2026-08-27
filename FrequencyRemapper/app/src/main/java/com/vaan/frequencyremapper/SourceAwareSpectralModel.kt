package com.vaan.frequencyremapper

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class CategoryFrequencyMode(val title: String, val valueLabel: String) {
    EXACT("EXACT Hz", "Target Hz"),
    OFFSET_HZ("OFFSET Hz", "+/- Hz"),
    RATIO("RATIO", "Multiplier"),
    SEMITONES("SEMITONES", "+/- semitones");

    fun next(): CategoryFrequencyMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}

data class SpectralObject(
    val id: Long,
    val noteLabel: String,
    val sourceHz: Double,
    val primaryCategory: AudioCategory,
    val tags: List<String>,
    val vocalScore: Double,
    val shiftedVocalScore: Double,
    val bassScore: Double,
    val instrumentScore: Double,
    val relativeEnergy: Double
) {
    fun scoreFor(category: AudioCategory): Double {
        val hidden = max(shiftedVocalScore, if (tags.any { it.contains("HIDDEN VOCAL", true) }) vocalScore else 0.0)
        return when (category) {
            AudioCategory.VOCAL -> vocalScore * (0.72 + 0.28 * relativeEnergy)
            AudioCategory.LOW_VOCAL -> vocalScore * (0.58 + 0.42 * (1.0 - relativeEnergy))
            AudioCategory.HIDDEN_VOCAL -> hidden * (0.70 + 0.30 * relativeEnergy)
            AudioCategory.HIDDEN_LOW_VOCAL -> hidden * (0.56 + 0.44 * (1.0 - relativeEnergy))
            AudioCategory.BASS -> bassScore
            AudioCategory.INSTRUMENT -> instrumentScore
            AudioCategory.OTHER -> 0.10
            AudioCategory.CUSTOM -> if (primaryCategory == AudioCategory.CUSTOM) 1.0 else 0.0
        }.coerceIn(0.0, 1.0)
    }

    fun withNextCategory(): SpectralObject {
        val cycle = listOf(
            AudioCategory.VOCAL,
            AudioCategory.LOW_VOCAL,
            AudioCategory.HIDDEN_LOW_VOCAL,
            AudioCategory.HIDDEN_VOCAL,
            AudioCategory.BASS,
            AudioCategory.INSTRUMENT,
            AudioCategory.OTHER
        )
        val index = cycle.indexOf(primaryCategory)
        return copy(primaryCategory = cycle[if (index < 0) 0 else (index + 1) % cycle.size])
    }
}

data class SpectralFrequencyEdit(
    val objectId: Long,
    val sourceHz: Double,
    val targetHz: Double,
    val phaseDegrees: Double,
    val category: AudioCategory,
    val enabled: Boolean = true
)

data class CategoryTransformRule(
    val category: AudioCategory,
    val mode: CategoryFrequencyMode,
    val value: Double?,
    val phaseDegrees: Double,
    val confidenceThreshold: Double,
    val enabled: Boolean
) {
    fun targetFor(sourceHz: Double): Double {
        val v = value ?: return sourceHz
        return when (mode) {
            CategoryFrequencyMode.EXACT -> v
            CategoryFrequencyMode.OFFSET_HZ -> sourceHz + v
            CategoryFrequencyMode.RATIO -> sourceHz * v
            CategoryFrequencyMode.SEMITONES -> sourceHz * Math.pow(2.0, v / 12.0)
        }
    }
}

data class ManualMaskRegion(
    val id: Long,
    val startSeconds: Double,
    val endSeconds: Double,
    val lowHz: Double,
    val highHz: Double,
    val category: AudioCategory
) {
    fun contains(timeSeconds: Double, frequencyHz: Double): Boolean =
        timeSeconds in min(startSeconds, endSeconds)..max(startSeconds, endSeconds) &&
            frequencyHz in min(lowHz, highHz)..max(lowHz, highHz)
}

data class FrameCategoryMask(
    val categoryOrdinal: IntArray,
    val confidence: FloatArray
)

object SpectralObjectFactory {
    fun create(note: DetectedNote, content: FrequencyContentTags?, relativeEnergy: Double): SpectralObject {
        return SpectralObject(
            id = note.midi.toLong(),
            noteLabel = note.label,
            sourceHz = note.frequencyHz,
            primaryCategory = AudioCategoryResolver.resolve(note, content),
            tags = content?.tags ?: listOf("TONAL"),
            vocalScore = content?.vocalScore ?: 0.0,
            shiftedVocalScore = content?.shiftedVocalScore ?: 0.0,
            bassScore = content?.bassScore ?: 0.0,
            instrumentScore = content?.instrumentScore ?: 0.0,
            relativeEnergy = relativeEnergy.coerceIn(0.0, 1.0)
        )
    }
}

/**
 * Streaming, frame-local source mask. It deliberately does not store a full
 * spectrogram. Priors come from whole-track analysis, while every STFT frame
 * re-evaluates vocal/formant, shifted-vocal, bass, tonality and local-energy
 * evidence before assigning a category to each bin.
 */
class TimeFrequencyCategoryMasker(
    private val objects: List<SpectralObject>,
    private val sampleRate: Int,
    private val fftSize: Int,
    private val manualRegions: List<ManualMaskRegion> = emptyList()
) {
    private val half = fftSize / 2
    private val binHz = sampleRate.toDouble() / fftSize
    private val categories = AudioCategory.entries
    private val priors = Array(categories.size) { DoubleArray(half + 1) }

    init {
        compilePriors()
    }

    fun classify(power: DoubleArray, timeSeconds: Double): FrameCategoryMask {
        require(power.size >= half + 1)
        val categoryOut = IntArray(half + 1) { AudioCategory.OTHER.ordinal }
        val confidenceOut = FloatArray(half + 1)

        var maxPower = 1e-20
        for (k in 1 until min(power.size, half + 1)) if (power[k] > maxPower) maxPower = power[k]

        val nyquist = sampleRate / 2.0
        val broad = bandPower(power, 65.0, min(7200.0, nyquist - binHz)).coerceAtLeast(1e-20)
        val lowRatio = (bandPower(power, 28.0, min(360.0, nyquist)) / broad).coerceIn(0.0, 1.0)
        val flatness = spectralFlatness(power, 85.0, min(6500.0, nyquist - binHz))
        val tonality = (1.0 - flatness).coerceIn(0.0, 1.0)
        val normalVoice = max(
            formantScore(power, 0.85, broad, nyquist),
            max(formantScore(power, 1.0, broad, nyquist), formantScore(power, 1.18, broad, nyquist))
        )
        val shiftedVoice = maxOf(
            formantScore(power, 0.55, broad, nyquist),
            formantScore(power, 0.70, broad, nyquist),
            formantScore(power, 1.42, broad, nyquist),
            formantScore(power, 1.72, broad, nyquist),
            formantScore(power, 2.05, broad, nyquist)
        )

        for (k in 1 until half) {
            val frequency = k * binHz
            val region = manualRegions.lastOrNull { it.contains(timeSeconds, frequency) }
            if (region != null) {
                categoryOut[k] = region.category.ordinal
                confidenceOut[k] = 1f
                continue
            }

            val localStrength = sqrt((power[k] / maxPower).coerceIn(0.0, 1.0))
            var bestCategory = AudioCategory.OTHER
            var best = 0.0
            var second = 0.0

            for (category in categories) {
                val prior = priors[category.ordinal][k]
                if (prior <= 1e-8) continue
                val dynamic = dynamicEvidence(
                    category = category,
                    frequency = frequency,
                    localStrength = localStrength,
                    normalVoice = normalVoice,
                    shiftedVoice = shiftedVoice,
                    lowRatio = lowRatio,
                    tonality = tonality
                )
                val score = prior * (0.30 + 0.70 * dynamic)
                if (score > best) {
                    second = best
                    best = score
                    bestCategory = category
                } else if (score > second) {
                    second = score
                }
            }

            if (best <= 1e-8) {
                categoryOut[k] = AudioCategory.OTHER.ordinal
                confidenceOut[k] = (0.12 + 0.18 * localStrength).toFloat()
                continue
            }

            val separation = best / (best + second + 1e-9)
            val confidence = (separation * (0.42 + 0.58 * localStrength)).coerceIn(0.0, 1.0)
            categoryOut[k] = bestCategory.ordinal
            confidenceOut[k] = confidence.toFloat()
        }

        return FrameCategoryMask(categoryOut, confidenceOut)
    }

    private fun compilePriors() {
        if (objects.isEmpty()) return
        for (obj in objects) {
            if (!obj.sourceHz.isFinite() || obj.sourceHz <= 0.0) continue
            val primaryFloor = 0.58 + 0.32 * obj.relativeEnergy
            for (category in categories) {
                var candidate = obj.scoreFor(category)
                if (category == obj.primaryCategory) candidate = max(candidate, primaryFloor)
                if (candidate < 0.035) continue
                addFrequencyPrior(category, obj.sourceHz, candidate, 82.0)

                // Harmonic priors are weaker but let the mask follow vocal and
                // instrument families rather than a single fundamental bin.
                var harmonic = 2
                while (harmonic <= 12) {
                    val center = obj.sourceHz * harmonic
                    if (center >= sampleRate / 2.0) break
                    val harmonicWeight = candidate / harmonic.toDouble().pow(0.58)
                    addFrequencyPrior(category, center, harmonicWeight, 58.0)
                    harmonic++
                }
            }
        }
    }

    private fun addFrequencyPrior(category: AudioCategory, centerHz: Double, strength: Double, widthCents: Double) {
        if (centerHz <= 0.0) return
        val loHz = centerHz * Math.pow(2.0, -widthCents / 1200.0)
        val hiHz = centerHz * Math.pow(2.0, widthCents / 1200.0)
        val lo = max(1, (loHz / binHz).toInt())
        val hi = min(half - 1, (hiHz / binHz).toInt() + 1)
        if (hi < lo) return
        for (k in lo..hi) {
            val f = k * binHz
            if (f <= 0.0) continue
            val cents = abs(1200.0 * log2(f / centerHz))
            val x = (cents / widthCents).coerceIn(0.0, 1.0)
            val shape = exp(-3.8 * x * x)
            priors[category.ordinal][k] = max(priors[category.ordinal][k], strength * shape)
        }
    }

    private fun dynamicEvidence(
        category: AudioCategory,
        frequency: Double,
        localStrength: Double,
        normalVoice: Double,
        shiftedVoice: Double,
        lowRatio: Double,
        tonality: Double
    ): Double {
        val voiceBase = (0.58 * normalVoice + 0.42 * tonality).coerceIn(0.0, 1.0)
        val hiddenBase = (0.64 * shiftedVoice + 0.36 * tonality).coerceIn(0.0, 1.0)
        return when (category) {
            AudioCategory.VOCAL -> voiceBase * (0.55 + 0.45 * localStrength)
            AudioCategory.LOW_VOCAL -> voiceBase * (0.46 + 0.54 * (1.0 - localStrength))
            AudioCategory.HIDDEN_VOCAL -> hiddenBase * (0.50 + 0.50 * localStrength)
            AudioCategory.HIDDEN_LOW_VOCAL -> hiddenBase * (0.42 + 0.58 * (1.0 - localStrength))
            AudioCategory.BASS -> if (frequency <= 430.0) {
                (0.66 * lowRatio + 0.34 * localStrength).coerceIn(0.0, 1.0)
            } else 0.025
            AudioCategory.INSTRUMENT -> (tonality * (0.72 + 0.28 * localStrength) * (1.0 - 0.40 * normalVoice)).coerceIn(0.0, 1.0)
            AudioCategory.OTHER -> 0.20 + 0.30 * localStrength
            AudioCategory.CUSTOM -> 1.0
        }
    }

    private fun formantScore(power: DoubleArray, scale: Double, broad: Double, nyquist: Double): Double {
        fun ratio(lo: Double, hi: Double): Double {
            val a = max(60.0, lo * scale)
            val b = min(nyquist - binHz, hi * scale)
            if (b <= a) return 0.0
            return (bandPower(power, a, b) / broad).coerceIn(0.0, 1.0)
        }
        val f1 = ratio(250.0, 950.0)
        val f2 = ratio(700.0, 2400.0)
        val f3 = ratio(1600.0, 3900.0)
        if (f1 <= 1e-9 || f2 <= 1e-9) return 0.0
        val geometric = (f1 * f2 * max(f3, 0.004)).pow(1.0 / 3.0)
        val balance = exp(-1.35 * abs(ln((f2 + 1e-9) / (f1 + 1e-9))))
        return (geometric * 3.4 * (0.72 + 0.28 * balance)).coerceIn(0.0, 1.0)
    }

    private fun bandPower(power: DoubleArray, loHz: Double, hiHz: Double): Double {
        if (hiHz <= loHz) return 0.0
        val lo = max(0, (loHz / binHz).toInt())
        val hi = min(power.lastIndex, (hiHz / binHz).toInt())
        if (hi < lo) return 0.0
        var sum = 0.0
        for (k in lo..hi) sum += power[k]
        return sum
    }

    private fun spectralFlatness(power: DoubleArray, loHz: Double, hiHz: Double): Double {
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
        return (exp(logSum / count) / (arithmetic / count)).coerceIn(0.0, 1.0)
    }
}

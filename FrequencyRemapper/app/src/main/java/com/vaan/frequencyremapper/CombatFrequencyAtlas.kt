package com.vaan.frequencyremapper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin

enum class CombatAtlasFamily(val title: String) {
    POWER("POWER / FAST-TWITCH"),
    STRUCTURE("TENDON / BONE / CARTILAGE"),
    ENERGY("ENERGY / VASCULAR"),
    NEURAL("NEURAL / LEARNING"),
    RECOVERY("RECOVERY / STRESS"),
    SIGNALING("MECHANICAL SIGNALING")
}

data class CombatFrequencyEntry(
    val id: String,
    val originalHz: Double,
    val displayHz: String,
    val documentTarget: String,
    val experimentalBenefit: String,
    val family: CombatAtlasFamily
) {
    val experimentalLabel: String get() = "Experimental: $experimentalBenefit"
}

enum class CombatAtlasProgram(val title: String) {
    FULL("FULL COMBAT ATLAS"),
    EXPLOSIVE("EXPLOSIVE FIGHTER"),
    IRON_FRAME("IRON FRAME"),
    ENDURANCE("ENDLESS GAS"),
    NEURAL("NEURAL PREDATOR"),
    RECOVERY("RECOVERY ARMOR");

    fun next(): CombatAtlasProgram = entries[(ordinal + 1) % entries.size]
}

enum class CombatAtlasMode(val title: String) {
    ROTATE("ROTATE"),
    STACK("STACK"),
    CONSTELLATION("CONSTELLATION");

    fun next(): CombatAtlasMode = entries[(ordinal + 1) % entries.size]
}

enum class CombatAtlasRepresentation(val title: String) {
    AUTO("AUTO"),
    DIRECT("DIRECT WHEN PLAYABLE"),
    OCTAVE_FOLD("OCTAVE FOLD");

    fun next(): CombatAtlasRepresentation = entries[(ordinal + 1) % entries.size]
}

data class CombatAtlasConfig(
    val enabled: Boolean = false,
    val program: CombatAtlasProgram = CombatAtlasProgram.FULL,
    val mode: CombatAtlasMode = CombatAtlasMode.ROTATE,
    val representation: CombatAtlasRepresentation = CombatAtlasRepresentation.AUTO,
    val rotationSeconds: Double = 4.0,
    val intensity: Double = 0.38,
    val confidenceThreshold: Double = 0.25
)

data class CombatAtlasDecision(
    val entry: CombatFrequencyEntry,
    val targetHz: Double?,
    val gain: Double,
    val representationNote: String
)

object CombatFrequencyAtlas {
    val entries: List<CombatFrequencyEntry> = listOf(
        CombatFrequencyEntry("col1_vegf_scx_1", 1.0, "1 Hz", "COL1A1 / VEGF-A / SCX", "tendon + vasculature", CombatAtlasFamily.STRUCTURE),
        CombatFrequencyEntry("col2_2", 2.0, "2 Hz", "COL2A1", "cartilage • UP 2.7× in source document", CombatAtlasFamily.STRUCTURE),
        CombatFrequencyEntry("regen_15", 15.0, "15 Hz", "COL2A1 / TGF-β / BMP", "tissue regeneration + bone", CombatAtlasFamily.RECOVERY),
        CombatFrequencyEntry("hsp70_60", 60.0, "60 Hz", "HSP70", "chaperone / stress-response", CombatAtlasFamily.RECOVERY),
        CombatFrequencyEntry("ptgs2_440", 440.0, "440 Hz", "PTGS2 / COX-2", "FAK-PGE2 signaling", CombatAtlasFamily.SIGNALING),
        CombatFrequencyEntry("piezo1_8156", 8156.6, "8156.6–8157 Hz", "PIEZO1", "mechanosensing", CombatAtlasFamily.SIGNALING),
        CombatFrequencyEntry("myh2_10729", 10729.6, "10,729.6 Hz", "MYH2", "fast-twitch myosin", CombatAtlasFamily.POWER),
        CombatFrequencyEntry("col11a1_12995", 12995.5, "12,995.5 Hz", "COL11A1", "bone / cartilage collagen", CombatAtlasFamily.STRUCTURE),
        CombatFrequencyEntry("col1a1_13651", 13651.9, "13,651.9 Hz", "COL1A1", "tendon collagen", CombatAtlasFamily.STRUCTURE),
        CombatFrequencyEntry("col2a1_14378", 14378.1, "14,378.1 Hz", "COL2A1", "cartilage collagen", CombatAtlasFamily.STRUCTURE),
        CombatFrequencyEntry("pgc1a_20141", 20141.0, "20,141 Hz", "PGC-1α", "mitochondrial biogenesis", CombatAtlasFamily.ENERGY),
        CombatFrequencyEntry("actn3_22599", 22598.9, "22,598.9 / 22,599 Hz", "ACTN3", "speed / power fiber • fast-twitch power fibers", CombatAtlasFamily.POWER),
        CombatFrequencyEntry("nrf2_30441", 30441.0, "30,441 Hz", "NRF2", "antioxidant master switch", CombatAtlasFamily.RECOVERY),
        CombatFrequencyEntry("hspa1a_31008", 31008.0, "31,008 Hz", "HSPA1A / HSP70", "heat-shock / stress protein", CombatAtlasFamily.RECOVERY),
        CombatFrequencyEntry("runx2_36199", 36199.0, "36,166–36,199 Hz", "RUNX2", "osteoblast / bone formation", CombatAtlasFamily.STRUCTURE),
        CombatFrequencyEntry("creb1_47393", 47393.0, "47,393 Hz", "CREB1", "memory / synaptic", CombatAtlasFamily.NEURAL),
        CombatFrequencyEntry("myod1_61162", 61162.0, "61,162 Hz", "MYOD1", "myogenesis", CombatAtlasFamily.POWER),
        CombatFrequencyEntry("spp1_65359", 65359.0, "65,359 Hz", "SPP1", "bone mineralization", CombatAtlasFamily.STRUCTURE),
        CombatFrequencyEntry("bdnf_73529", 73529.0, "73,529 Hz", "BDNF", "neuroplasticity", CombatAtlasFamily.NEURAL),
        CombatFrequencyEntry("sod2_88889", 88889.0, "88,889 Hz", "SOD2", "mitochondrial antioxidant", CombatAtlasFamily.RECOVERY),
        CombatFrequencyEntry("vegfa_103093", 103093.0, "103,093 Hz", "VEGFA", "angiogenesis", CombatAtlasFamily.ENERGY),
        CombatFrequencyEntry("igf1_129032", 129032.0, "129,032 Hz", "IGF1", "growth factor / muscle / bone", CombatAtlasFamily.ENERGY)
    )

    private val byId = entries.associateBy { it.id }

    fun entriesFor(program: CombatAtlasProgram, category: AudioCategory): List<CombatFrequencyEntry> {
        val base = when (program) {
            CombatAtlasProgram.FULL -> entries
            CombatAtlasProgram.EXPLOSIVE -> ids("myh2_10729", "actn3_22599", "myod1_61162", "pgc1a_20141", "piezo1_8156", "igf1_129032")
            CombatAtlasProgram.IRON_FRAME -> ids("col1_vegf_scx_1", "col2_2", "regen_15", "piezo1_8156", "col11a1_12995", "col1a1_13651", "col2a1_14378", "runx2_36199", "spp1_65359")
            CombatAtlasProgram.ENDURANCE -> ids("pgc1a_20141", "vegfa_103093", "igf1_129032", "sod2_88889", "nrf2_30441", "hsp70_60")
            CombatAtlasProgram.NEURAL -> ids("creb1_47393", "bdnf_73529", "piezo1_8156", "actn3_22599")
            CombatAtlasProgram.RECOVERY -> ids("regen_15", "hsp70_60", "nrf2_30441", "hspa1a_31008", "sod2_88889", "col2_2", "ptgs2_440")
        }
        if (base.isEmpty()) return entries

        // Same atlas remains available to every active source family, but order
        // follows the user's foreground/background preference: vocal families
        // reach higher/power/neural entries first; background reaches lower and
        // structural/recovery entries first. FULL still cycles through all 22.
        return if (category.isAtlasVocalFamily()) {
            base.sortedWith(compareBy<CombatFrequencyEntry> {
                when (it.family) {
                    CombatAtlasFamily.POWER -> 0
                    CombatAtlasFamily.NEURAL -> 1
                    CombatAtlasFamily.ENERGY -> 2
                    CombatAtlasFamily.SIGNALING -> 3
                    CombatAtlasFamily.RECOVERY -> 4
                    CombatAtlasFamily.STRUCTURE -> 5
                }
            }.thenByDescending { it.originalHz })
        } else {
            base.sortedWith(compareBy<CombatFrequencyEntry> {
                when (it.family) {
                    CombatAtlasFamily.STRUCTURE -> 0
                    CombatAtlasFamily.RECOVERY -> 1
                    CombatAtlasFamily.SIGNALING -> 2
                    CombatAtlasFamily.ENERGY -> 3
                    CombatAtlasFamily.POWER -> 4
                    CombatAtlasFamily.NEURAL -> 5
                }
            }.thenBy { it.originalHz })
        }
    }

    fun decision(
        config: CombatAtlasConfig,
        categoryOrdinal: Int,
        sourceHz: Double,
        timeSeconds: Double,
        sampleRate: Int,
        fftSize: Int
    ): CombatAtlasDecision? {
        if (!config.enabled || !sourceHz.isFinite() || sourceHz <= 0.0) return null
        val category = AudioCategory.entries.getOrNull(categoryOrdinal) ?: AudioCategory.OTHER
        if (category == AudioCategory.CUSTOM) return null
        val candidates = entriesFor(config.program, category)
        if (candidates.isEmpty()) return null

        val index = when (config.mode) {
            CombatAtlasMode.ROTATE -> {
                val seconds = config.rotationSeconds.coerceIn(0.25, 60.0)
                (floor(timeSeconds / seconds).toLong() + category.ordinal * 3L).floorMod(candidates.size)
            }
            CombatAtlasMode.STACK -> {
                val bucket = floor(log2(sourceHz.coerceAtLeast(1.0)) * 9.0 + sourceHz / 173.0).toLong()
                (bucket + category.ordinal * 5L).floorMod(candidates.size)
            }
            CombatAtlasMode.CONSTELLATION -> {
                val bucket = floor(log2(sourceHz.coerceAtLeast(1.0)) * 13.0 + sourceHz / 97.0).toLong()
                (bucket + category.ordinal * 7L).floorMod(candidates.size)
            }
        }
        val entry = candidates[index]
        val intensity = config.intensity.coerceIn(0.0, 1.0)

        // Sub-audio document values are represented as a modulation rate rather
        // than pretending a normal music STFT can create a clean 1 or 2 Hz pitch.
        if (entry.originalHz < 20.0) {
            val depth = (0.10 + 0.34 * intensity).coerceIn(0.05, 0.48)
            val lfo = 0.5 + 0.5 * sin(2.0 * PI * entry.originalHz * timeSeconds)
            val gain = (1.0 - depth + depth * lfo).coerceIn(0.45, 1.0)
            return CombatAtlasDecision(entry, null, gain, "${entry.displayHz} sub-audio modulation")
        }

        val target = resolveTarget(
            originalHz = entry.originalHz,
            sourceHz = sourceHz,
            sampleRate = sampleRate,
            fftSize = fftSize,
            representation = config.representation,
            category = category,
            constellation = config.mode == CombatAtlasMode.CONSTELLATION
        ) ?: return CombatAtlasDecision(entry, null, 1.0, "${entry.displayHz} unavailable at this sample rate")

        val note = if (abs(target - entry.originalHz) < 0.001) {
            "${entry.displayHz} direct"
        } else {
            "${entry.displayHz} → ${formatHz(target)} Hz octave representation"
        }
        return CombatAtlasDecision(entry, target, 1.0, note)
    }

    fun resolvedPreview(entry: CombatFrequencyEntry, sampleRate: Int, category: AudioCategory, representation: CombatAtlasRepresentation): String {
        if (entry.originalHz < 20.0) return "${entry.displayHz} modulation"
        val resolved = resolveTarget(entry.originalHz, 440.0, sampleRate, if (sampleRate > 50000) 8192 else 4096, representation, category, false)
            ?: return "unavailable"
        return if (abs(resolved - entry.originalHz) < 0.001) "${formatHz(resolved)} Hz direct"
        else "${formatHz(resolved)} Hz folded"
    }

    private fun resolveTarget(
        originalHz: Double,
        sourceHz: Double,
        sampleRate: Int,
        fftSize: Int,
        representation: CombatAtlasRepresentation,
        category: AudioCategory,
        constellation: Boolean
    ): Double? {
        val nyquist = sampleRate / 2.0
        val minBin = sampleRate.toDouble() / fftSize
        val vocal = category.isAtlasVocalFamily()
        val autoCeiling = if (vocal) nyquist * 0.94 else minOf(nyquist * 0.55, 12000.0)
        val hardCeiling = nyquist * 0.94

        if (representation == CombatAtlasRepresentation.DIRECT) {
            return originalHz.takeIf { it >= minBin && it < hardCeiling }
        }

        if (constellation) {
            var candidate = originalHz
            while (candidate >= hardCeiling) candidate /= 2.0
            while (candidate / 2.0 >= minBin && abs(log2((candidate / 2.0) / sourceHz)) < abs(log2(candidate / sourceHz))) candidate /= 2.0
            while (candidate * 2.0 < hardCeiling && abs(log2((candidate * 2.0) / sourceHz)) < abs(log2(candidate / sourceHz))) candidate *= 2.0
            return candidate.takeIf { it >= minBin && it < hardCeiling }
        }

        var candidate = originalHz
        val ceiling = if (representation == CombatAtlasRepresentation.AUTO) autoCeiling else hardCeiling
        while (candidate >= ceiling) candidate /= 2.0
        return candidate.takeIf { it >= minBin && it < hardCeiling }
    }

    private fun ids(vararg ids: String): List<CombatFrequencyEntry> = ids.mapNotNull { byId[it] }

    private fun Long.floorMod(size: Int): Int {
        val s = size.toLong().coerceAtLeast(1L)
        val r = this % s
        return (if (r < 0L) r + s else r).toInt()
    }

    private fun formatHz(value: Double): String = if (abs(value - value.toInt()) < 0.0001) value.toInt().toString()
    else String.format(java.util.Locale.US, "%.3f", value)
}

fun AudioCategory.isAtlasVocalFamily(): Boolean = when (this) {
    AudioCategory.VOCAL,
    AudioCategory.LOW_VOCAL,
    AudioCategory.HIDDEN_VOCAL,
    AudioCategory.HIDDEN_LOW_VOCAL -> true
    else -> false
}

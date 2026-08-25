package com.vaan.frequencyremapper

import kotlin.math.max

enum class AudioCategory(val title: String, val detail: String) {
    VOCAL("VOCAL", "Clear/normal vocal-dominant frequency centers"),
    LOW_VOCAL("LOW VOCAL", "Weak or low-level vocal-dominant frequency centers"),
    HIDDEN_VOCAL("HIDDEN VOCAL", "Buried, shifted, or sped-up voice-like frequency centers"),
    HIDDEN_LOW_VOCAL("HIDDEN LOW VOCAL", "Lower-confidence/low-level hidden or shifted voice-like centers"),
    BASS("BASS", "Bass-dominant frequency centers"),
    INSTRUMENT("INSTRUMENT", "Instrument-dominant tonal frequency centers"),
    OTHER("OTHER / TONAL", "Tonal centers that do not strongly match the main groups"),
    CUSTOM("CUSTOM EXACT", "Manually added exact frequency centers")
}

object AudioCategoryResolver {
    fun resolve(note: DetectedNote, content: FrequencyContentTags?): AudioCategory {
        if (content == null) return AudioCategory.OTHER

        val joined = content.tags.joinToString(" ").uppercase()
        val hiddenTag = joined.contains("HIDDEN VOCAL") ||
            joined.contains("SHIFTED VOCAL") || joined.contains("SPED-UP VOCAL")
        val hiddenScore = max(content.vocalScore, content.shiftedVocalScore)

        if (hiddenTag) {
            val lowLevel = joined.contains("POSSIBLE") || joined.contains("VOCAL WEAK") || hiddenScore < 0.48
            return if (lowLevel) AudioCategory.HIDDEN_LOW_VOCAL else AudioCategory.HIDDEN_VOCAL
        }

        val vocal = content.vocalScore
        val instrument = content.instrumentScore
        val bass = content.bassScore

        // A row gets exactly one primary category. Secondary evidence still
        // remains visible in its tag list, but batch edits use only this group.
        if (vocal >= 0.13 && vocal >= instrument * 0.58 && vocal >= bass * 0.72) {
            return if (joined.contains("VOCAL WEAK") || vocal < 0.31) {
                AudioCategory.LOW_VOCAL
            } else {
                AudioCategory.VOCAL
            }
        }

        if (note.frequencyHz <= 360.0 && bass >= 0.13 && bass >= instrument * 0.64) {
            return AudioCategory.BASS
        }

        if (instrument >= 0.13) return AudioCategory.INSTRUMENT
        if (vocal >= 0.10) return AudioCategory.LOW_VOCAL
        if (note.frequencyHz <= 180.0 && bass >= 0.08) return AudioCategory.BASS
        return AudioCategory.OTHER
    }
}

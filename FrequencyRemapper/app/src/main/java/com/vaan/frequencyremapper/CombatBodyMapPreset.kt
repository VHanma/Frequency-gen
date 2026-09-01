package com.vaan.frequencyremapper

/**
 * One-tap category map assembled from the user's uploaded combat/body frequency
 * documents. These labels describe the source-document associations; the app
 * treats them as an editable audio-remapping preset rather than medical facts.
 */
data class CombatBodyFrequencyTarget(
    val category: AudioCategory,
    val hz: Double,
    val documentLabel: String
)

object CombatBodyMapPreset {
    val targets: List<CombatBodyFrequencyTarget> = listOf(
        // Higher foreground rail for all vocal families.
        CombatBodyFrequencyTarget(AudioCategory.VOCAL, 4000.0, "BDNF / VEGF / HSP70 • neural + vascular + stress"),
        CombatBodyFrequencyTarget(AudioCategory.LOW_VOCAL, 3300.0, "MYH / PGC-1α • muscle / hypertrophy"),
        CombatBodyFrequencyTarget(AudioCategory.HIDDEN_VOCAL, 2500.0, "Reaction speed carrier • MyoD / MyHC"),
        CombatBodyFrequencyTarget(AudioCategory.HIDDEN_LOW_VOCAL, 2400.0, "CREB / BDNF / mitochondrial"),

        // Lower rail for the rest of the detected mix.
        CombatBodyFrequencyTarget(AudioCategory.BASS, 174.0, "Pain-tolerance / foundation entry"),
        CombatBodyFrequencyTarget(AudioCategory.INSTRUMENT, 528.0, "Power / explosiveness entry"),
        CombatBodyFrequencyTarget(AudioCategory.OTHER, 852.0, "Mental-clarity entry")
    )

    fun supportedTargets(sampleRate: Int): List<CombatBodyFrequencyTarget> {
        val nyquist = sampleRate / 2.0
        return targets.filter { it.hz > 0.0 && it.hz < nyquist }
    }

    fun summary(): String = targets.joinToString("  •  ") { "${it.category.title} ${trimHz(it.hz)} Hz" }

    private fun trimHz(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}

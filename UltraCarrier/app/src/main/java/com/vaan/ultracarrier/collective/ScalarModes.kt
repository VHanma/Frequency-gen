package com.vaan.ultracarrier.collective

import com.vaan.ultracarrier.audio.BeamLabMode
import com.vaan.ultracarrier.audio.GodXMode
import com.vaan.ultracarrier.audio.ThoughtMode

enum class ScalarMode(val label: String, val description: String, val category: String) {
    LONGITUDINAL_PAIR("Longitudinal Pair", "Opposed stereo pressure-style phase pattern inspired by claimed longitudinal scalar-wave models.", "Longitudinal / paired"),
    PHASE_CONJUGATE_PAIR("Phase-Conjugate Pair", "Forward pattern plus a time-reversed / conjugate-like partner, inspired by Bearden's phase-conjugation descriptions.", "Phase conjugation"),
    ZERO_VECTOR_STRESS("Zero-Vector Stress", "Matched anti-phase components explore a low-resultant / hidden-structure signal pattern inspired by zero-vector scalar claims.", "Cancellation / null"),
    SCALAR_INTERFEROMETER("Scalar Interferometer", "Two independently phased carrier structures intersect mathematically and produce a controllable difference-pattern envelope.", "Interferometer"),
    WHITTAKER_SPECTRAL_PAIR("Whittaker Spectral Pair", "A multi-frequency paired-wave decomposition inspired by Bearden's Whittaker-potential interpretation.", "Spectral decomposition"),
    TESLA_BIFILAR_SPIRAL("Tesla Bifilar Spiral", "Counter-wound spiral phase motion with paired harmonics, inspired by Tesla/bifilar and scalar-circuit lore.", "Spiral / vortex"),
    COUNTER_ROTATING_VORTEX("Counter-Rotating Vortex", "Left/right channels trace opposite rotating phase paths and periodically rejoin at center.", "Spiral / vortex"),
    STANDING_POTENTIAL_NODE("Standing Potential Node", "Two counter-propagating virtual waves form moving and stationary nodes for null/antinodal experimentation.", "Standing wave / node"),
    DIFFERENCE_FREQUENCY_PUMP("Difference-Frequency Pump", "Two nearby high-frequency acoustic carriers are separated by the selected low-rate value.", "Pump / beat"),
    NESTED_ELF_SCALAR("Nested ELF Scalar", "A slow selected envelope is nested inside a faster paired carrier/interference structure.", "Nested modulation"),
    TIME_REVERSED_CHIRP("Time-Reversed Chirp", "Alternating forward and reverse chirps test phase-reversal and matched-filter-like perceptual structure.", "Time reversal"),
    SPIRAL_PHASE_LATTICE("Spiral Phase Lattice", "Three phase-locked carriers rotate through a repeating spiral lattice before collapsing back to center.", "Spiral / lattice")
}

data class MethodPreset(
    val name: String,
    val category: String,
    val note: String,
    val carrierHz: Float? = null,
    val rateHz: Float? = null,
    val depth: Float? = null,
    val targetDeg: Float? = null,
    val nullDeg: Float? = null,
    val spacingMm: Float? = null,
    val ditherDeg: Float? = null,
    val ditherRateHz: Float? = null,
    val headWidthCm: Float? = null,
    val distanceCm: Float? = null
) {
    fun variableSummary(): String = buildList {
        carrierHz?.let { add("carrier ${it.toInt()} Hz") }
        rateHz?.let { add("rate ${"%.2f".format(it)} Hz") }
        depth?.let { add("depth ${(it * 100).toInt()}%") }
        targetDeg?.let { add("target ${it.toInt()}°") }
        nullDeg?.let { add("null ${it.toInt()}°") }
        spacingMm?.let { add("spacing ${"%.1f".format(it)} mm") }
        ditherDeg?.let { add("dither ±${"%.1f".format(it)}°") }
        ditherRateHz?.let { add("motion ${"%.2f".format(it)} Hz") }
        headWidthCm?.let { add("head ${"%.1f".format(it)} cm") }
        distanceCm?.let { add("distance ${it.toInt()} cm") }
    }.joinToString(" • ")
}

object PresetCatalog {
    fun scalar(mode: ScalarMode): MethodPreset = when (mode) {
        ScalarMode.LONGITUDINAL_PAIR -> MethodPreset(mode.label, mode.category, "Paired 180° pressure-style structure.", 18_000f, 7.83f, .52f, 0f, 35f, 8.5f, 0f, .20f)
        ScalarMode.PHASE_CONJUGATE_PAIR -> MethodPreset(mode.label, mode.category, "Forward + reversed phase partner.", 18_500f, 7.83f, .48f, 0f, 35f, 8.5f, 1.5f, .16f)
        ScalarMode.ZERO_VECTOR_STRESS -> MethodPreset(mode.label, mode.category, "Strong anti-phase cancellation experiment.", 17_500f, 10f, .44f, 0f, 45f, 8.5f, 0f, .12f)
        ScalarMode.SCALAR_INTERFEROMETER -> MethodPreset(mode.label, mode.category, "Two-pump interference with slow difference envelope.", 19_000f, 7.83f, .50f, 0f, 32f, 9.5f, 2f, .21f)
        ScalarMode.WHITTAKER_SPECTRAL_PAIR -> MethodPreset(mode.label, mode.category, "Three paired spectral components with conjugate phase structure.", 18_250f, 6.18f, .42f, 0f, 30f, 8.5f, 1f, .13f)
        ScalarMode.TESLA_BIFILAR_SPIRAL -> MethodPreset(mode.label, mode.category, "Counter-wound spiral phase motion.", 19_500f, 7.83f, .46f, 0f, 40f, 10f, 6f, .18f)
        ScalarMode.COUNTER_ROTATING_VORTEX -> MethodPreset(mode.label, mode.category, "Opposite stereo rotations that periodically center-lock.", 18_000f, 8f, .44f, 0f, 35f, 8.5f, 8f, .25f)
        ScalarMode.STANDING_POTENTIAL_NODE -> MethodPreset(mode.label, mode.category, "Stable standing-node pattern.", 17_000f, 5f, .40f, 0f, 45f, 12f, 0f, .10f)
        ScalarMode.DIFFERENCE_FREQUENCY_PUMP -> MethodPreset(mode.label, mode.category, "Carrier pair separated by the selected rate.", 19_000f, 7.83f, .40f, 0f, 30f, 8.5f, 0f, .15f)
        ScalarMode.NESTED_ELF_SCALAR -> MethodPreset(mode.label, mode.category, "Slow envelope nested in paired high-frequency structure.", 18_500f, 7.83f, .58f, 0f, 35f, 8.5f, 1f, .12f)
        ScalarMode.TIME_REVERSED_CHIRP -> MethodPreset(mode.label, mode.category, "Forward/reverse chirp alternation.", 17_500f, 4f, .42f, 0f, 35f, 8.5f, 3f, .20f)
        ScalarMode.SPIRAL_PHASE_LATTICE -> MethodPreset(mode.label, mode.category, "Three-carrier rotating lattice.", 18_750f, 6.18f, .48f, 0f, 40f, 9f, 9f, .18f)
    }

    fun world(mode: BeamLabMode): MethodPreset {
        val category = worldCategory(mode)
        return when (mode) {
            BeamLabMode.ELF_BEAM -> MethodPreset(mode.label, category, "Directional carrier with slow envelope.", 18_000f, 7.83f, .36f, 0f, 35f, 8.5f, 2f, .25f)
            BeamLabMode.DUAL_PUMP_ELF -> MethodPreset(mode.label, category, "Two pumps separated by low-rate difference.", 19_000f, 7.83f, .38f, 0f, 35f, 8.5f, 0f, .20f)
            BeamLabMode.RUSSIAN_SSB_BEAM -> MethodPreset(mode.label, category, "Precompensated SSB-style directional encoding.", 18_500f, 7.83f, .45f, 0f, 35f, 8.5f)
            BeamLabMode.SOVIET_PULSE_BEAM -> MethodPreset(mode.label, category, "Slow pulse gating on carrier.", 18_000f, 10f, .42f, 0f, 35f, 8.5f)
            BeamLabMode.PSYCHOTRONIC_NESTED_BEAM -> MethodPreset(mode.label, category, "Slow rate nested with 40 Hz envelope.", 18_000f, 7.83f, .48f, 0f, 35f, 8.5f)
            BeamLabMode.SMIRNOV_MASK_BEAM -> MethodPreset(mode.label, category, "Speech + shaped masker inside beam.", 18_500f, 8f, .40f, 0f, 35f, 8.5f)
            BeamLabMode.US_VIRTUAL_SPEAKER -> MethodPreset(mode.label, category, "Steered apparent-source path.", 19_000f, 7.83f, .38f, 12f, 35f, 8.5f)
            BeamLabMode.US_LOCALIZED_SPOT -> MethodPreset(mode.label, category, "Complementary overlap focus.", 19_000f, 7.83f, .44f, 0f, 35f, 8.5f)
            BeamLabMode.US_QUIET_ZONE -> MethodPreset(mode.label, category, "Bright target + neighboring null.", 18_500f, 7.83f, .42f, 0f, 35f, 8.5f)
            BeamLabMode.US_VIRTUAL_HEADSET -> MethodPreset(mode.label, category, "Independent ear-oriented field.", 18_000f, 7f, .38f, 0f, 35f, 8.5f, 0f, .18f, 15.5f, 45f)
            BeamLabMode.FREY_CODEC_ACOUSTIC -> MethodPreset(mode.label, category, "Speech-edge preprocessing on acoustic carrier.", 18_500f, 18f, .38f, 0f, 35f, 8.5f)
            BeamLabMode.US_PULSE_FM_ANALOG -> MethodPreset(mode.label, category, "Voice-dependent pulse density + FM.", 18_000f, 18f, .40f, 0f, 35f, 8.5f)
            BeamLabMode.SETI_DRIFT_BEAM -> MethodPreset(mode.label, category, "Very slow carrier drift.", 18_500f, 7.83f, .36f, 0f, 35f, 8.5f)
            BeamLabMode.CHIRP_SPREAD_BEAM -> MethodPreset(mode.label, category, "Repeating chirped carrier.", 18_000f, 8f, .38f, 0f, 35f, 8.5f)
            BeamLabMode.CROSSED_BEAM_FOCUS -> MethodPreset(mode.label, category, "Reference + sideband overlap.", 19_000f, 7.83f, .44f, 0f, 35f, 8.5f)
            BeamLabMode.BRIGHT_DARK_BUBBLE -> MethodPreset(mode.label, category, "Bright core + dark direction.", 18_500f, 7.83f, .42f, 0f, 35f, 8.5f)
            BeamLabMode.BEAM_LOCK -> MethodPreset(mode.label, category, "Small steering dither around target.", 18_500f, 7.83f, .40f, 0f, 35f, 8.5f, 2f, .25f)
            BeamLabMode.SWEET_SPOT_XTC -> MethodPreset(mode.label, category, "Crosstalk-cancel sweet spot.", null, 7f, .32f, 0f, null, null, null, null, 15.5f, 45f)
            BeamLabMode.ALIEN_TIME_REVERSAL -> MethodPreset(mode.label, category, "Phase-conjugate focusing seed.", 18_500f, 7.83f, .42f, 0f, 35f, 8.5f)
            BeamLabMode.ALIEN_HOLOGRAM_FOCUS -> MethodPreset(mode.label, category, "Three-frequency holographic phase seed.", 19_000f, 6.18f, .44f, 0f, 35f, 8.5f)
            BeamLabMode.ALIEN_VORTEX_OAM -> MethodPreset(mode.label, category, "Quadrature vortex seed.", 18_500f, 7.83f, .40f, 0f, 35f, 8.5f, 4f, .18f)
            BeamLabMode.ALIEN_FREQUENCY_KEY -> MethodPreset(mode.label, category, "Multi-carrier phase-key overlap.", 18_750f, 7.83f, .42f, 0f, 35f, 8.5f)
            BeamLabMode.ALIEN_BESSEL_SELF_HEAL -> MethodPreset(mode.label, category, "Dual-cone Bessel-like seed.", 18_500f, 7.83f, .42f, 8f, 35f, 8.5f)
            BeamLabMode.ALIEN_QUIET_SHELL -> MethodPreset(mode.label, category, "Bright core with moving neighboring null.", 18_500f, 7.83f, .44f, 0f, 35f, 8.5f, 4f, .18f)
            BeamLabMode.ALIEN_DUAL_EAR_FIELD -> MethodPreset(mode.label, category, "Independent ear-target phase coding.", 18_000f, 7f, .38f, 0f, 35f, 8.5f, 0f, .18f, 15.5f, 45f)
        }
    }

    fun perception(mode: CollectiveMode): MethodPreset = when (mode) {
        CollectiveMode.THOUGHT_GHOST -> MethodPreset(mode.label, "Internal-location", "Center-lock + micro-delay drift.", null, .17f, .48f, null, null, null, null, .17f)
        CollectiveMode.PHONEMIC_RESTORE -> MethodPreset(mode.label, "Perceptual completion", "Masked gaps test speech restoration.", null, 7f, .48f)
        CollectiveMode.CONTINUITY_GHOST -> MethodPreset(mode.label, "Perceptual completion", "Masker bridges interrupted speech.", null, 7f, .46f)
        CollectiveMode.SILENT_GAP_ECHO -> MethodPreset(mode.label, "After-perception", "Brief silence after strong fragments.", null, 6f, .44f)
        CollectiveMode.AUDITORY_AFTERIMAGE -> MethodPreset(mode.label, "Afterimage", "Notched-noise induction / quiet window.", null, 6f, .40f)
        CollectiveMode.MIND_CANVAS -> MethodPreset(mode.label, "Cross-modal imagery", "Pitch-height and spatial motion cues.", null, .09f, .45f, null, null, null, null, .09f)
        CollectiveMode.IMAGE_SEED_GEOMETRY -> MethodPreset(mode.label, "Cross-modal imagery", "Circular pitch-space trajectory.", null, .125f, .46f, null, null, null, null, .125f)
        CollectiveMode.HYPERPHANTASIA_SEED -> MethodPreset(mode.label, "Cross-modal imagery", "Layered harmonic motion scene.", null, .06f, .50f, null, null, null, null, .06f)
        CollectiveMode.ATTENTION_NULL -> MethodPreset(mode.label, "Attention / fading", "Peripheral auditory motion during fixation.", null, .11f, .40f, null, null, null, null, .11f)
        CollectiveMode.LACERTA_FILTER_TEST -> MethodPreset(mode.label, "Attention / fading", "Low-novelty centered sound paired with visual fade timing.", null, 7.83f, .36f)
        CollectiveMode.SOUND_FLASH_SEED -> MethodPreset(mode.label, "Cross-modal timing", "Paired click timing for flash illusion tests.", null, 13.33f, .42f)
        CollectiveMode.MISSING_FUNDAMENTAL -> MethodPreset(mode.label, "Constructed pitch", "Harmonics imply an absent fundamental.", null, 6f, .44f)
    }

    fun labX(mode: GodXMode): MethodPreset = when (mode) {
        GodXMode.VOICE_OF_GOD_STACK -> MethodPreset(mode.label, "Internal voice stack", "Centered voice + 7 Hz stereo bed + slow micro-motion.", null, 7f, .34f, null, null, null, null, .18f)
        GodXMode.EMF_ENVELOPE -> MethodPreset(mode.label, "Low-rate envelope", "Selected acoustic envelope rate.", null, 7.83f, .34f)
        GodXMode.EMF_SCAN -> MethodPreset(mode.label, "Pattern scan", "Cycles 7.83/10/16.67/25/40/50/60/80 Hz.", null, 7.83f, .30f)
        GodXMode.ASSR_40 -> MethodPreset(mode.label, "Auditory steady-state", "40 Hz acoustic AM.", null, 40f, .35f)
        GodXMode.CROSS_FREQUENCY_NEST -> MethodPreset(mode.label, "Nested modulation", "Slow envelope nested with 40 Hz.", null, 7.83f, .34f)
        GodXMode.BINAURAL_CORE -> MethodPreset(mode.label, "Stereo beat", "Separated-channel difference tone.", null, 7f, .30f)
        GodXMode.MONAURAL_BEAT -> MethodPreset(mode.label, "Physical beat", "Both nearby tones in both channels.", null, 7f, .30f)
        GodXMode.MICRO_MOTION -> MethodPreset(mode.label, "Internal spatial motion", "Sub-millisecond ITD movement.", null, .18f, .30f, null, null, null, null, .18f)
        GodXMode.COHERENCE_SNAP -> MethodPreset(mode.label, "Center-lock contrast", "Decorrelated field periodically snaps to center.", null, .33f, .30f)
        GodXMode.PHASE_FLIP -> MethodPreset(mode.label, "Phase / correlation", "Slow correlated-to-inverted right channel.", null, .18f, .30f, null, null, null, null, .18f)
    }

    fun classic(mode: ThoughtMode): MethodPreset = when (mode) {
        ThoughtMode.INNER_VOICE -> MethodPreset(mode.label, "Internal voice", "Direct centered speech.", null, 7f, .38f)
        ThoughtMode.CENTER_LOCK -> MethodPreset(mode.label, "Internal voice", "Strong dual-mono center localization.", null, 7f, .42f)
        ThoughtMode.FREY_ACOUSTIC_SIM -> MethodPreset(mode.label, "Pulse / click", "Acoustic cranial-click simulation.", null, 18f, .46f)
        ThoughtMode.MASKED_WHISPER -> MethodPreset(mode.label, "Masking", "Speech embedded in shaped stereo noise.", null, 7f, .44f)
        ThoughtMode.BONE_TAP -> MethodPreset(mode.label, "Tactile-style pulse", "Low-frequency tap packets.", null, 12f, .42f)
        ThoughtMode.PATENT_SSB -> MethodPreset(mode.label, "Carrier codec", "Analytic-signal SSB carrier.", 18_000f, 7.83f, .48f, 0f, null, 8.5f)
        ThoughtMode.FM_SLOPE -> MethodPreset(mode.label, "Carrier codec", "Voice-controlled FM deviation.", 18_000f, 7.83f, .42f, 0f, null, 8.5f)
        ThoughtMode.BEAM_WHISPER -> MethodPreset(mode.label, "Directional carrier", "Low-pilot AM-style carrier.", 18_500f, 7.83f, .46f, 0f, null, 8.5f)
        ThoughtMode.AIR_HETERODYNE -> MethodPreset(mode.label, "Parametric / heterodyne", "Square-root preprocessed AM carrier.", 19_000f, 7.83f, .44f, 0f, null, 8.5f)
        ThoughtMode.ARRAY_STEER -> MethodPreset(mode.label, "Array steering", "Stereo phase steering.", 18_500f, 7.83f, .42f, 0f, null, 8.5f)
        ThoughtMode.CHIRP_CARRIER -> MethodPreset(mode.label, "Chirp carrier", "Repeating chirped carrier.", 18_000f, 8f, .40f, 0f, null, 8.5f)
    }

    fun worldCategory(mode: BeamLabMode): String = when (mode) {
        BeamLabMode.ELF_BEAM, BeamLabMode.US_QUIET_ZONE, BeamLabMode.BRIGHT_DARK_BUBBLE, BeamLabMode.BEAM_LOCK, BeamLabMode.ALIEN_QUIET_SHELL -> "Privacy / quiet-zone"
        BeamLabMode.US_LOCALIZED_SPOT, BeamLabMode.CROSSED_BEAM_FOCUS, BeamLabMode.ALIEN_TIME_REVERSAL, BeamLabMode.ALIEN_HOLOGRAM_FOCUS, BeamLabMode.ALIEN_FREQUENCY_KEY, BeamLabMode.ALIEN_BESSEL_SELF_HEAL -> "Focus / overlap"
        BeamLabMode.US_VIRTUAL_SPEAKER, BeamLabMode.US_VIRTUAL_HEADSET, BeamLabMode.SWEET_SPOT_XTC, BeamLabMode.ALIEN_VORTEX_OAM, BeamLabMode.ALIEN_DUAL_EAR_FIELD -> "Spatial / virtual source"
        else -> "Modulation / codec"
    }

    fun perceptionCategory(mode: CollectiveMode): String = perception(mode).category
    fun labXCategory(mode: GodXMode): String = labX(mode).category
    fun classicCategory(mode: ThoughtMode): String = classic(mode).category
}

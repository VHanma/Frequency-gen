package com.vaan.ultracarrier.collective

enum class ResonanceMode(val label: String, val description: String, val category: String) {
    DNA_SONIFICATION("DNA Sonification", "Four-state nucleotide-style harmonic mapping driven by source audio.", "DNA / sonification"),
    DNA_MEYL_RESONANCE("Meyl DNA Resonance", "Acoustic phase-conjugate and helical representation inspired by Meyl-style DNA resonance claims.", "DNA / fringe resonance"),
    DNA_MONTAGNIER_LF_SIGNAL("Montagnier Low-Frequency DNA Signal", "Low-frequency acoustic representation inspired by controversial DNA-signal experiments associated with Montagnier.", "DNA / fringe resonance"),
    DNA_EM_RESONANCE("DNA Electromagnetic Resonance Representation", "Acoustic carrier and sideband representation of proposed DNA electromagnetic-resonance ideas.", "DNA / resonance representation"),
    DNA_HELICAL_MODULATION("DNA Helical Modulation", "Stereo phase rotates around a carrier to represent a helical strand.", "DNA / helix"),
    DNA_CADUCEUS("DNA Caduceus", "Two counter-wound helical phase paths cross and re-cross.", "DNA / helix"),
    DNA_LIGHT_SOUND("DNA Light + Sound", "Synchronized amplitude pulse structure intended to pair with the app's live visual display.", "DNA / audiovisual"),
    DNA_FORWARD_REVERSE_STRAND("DNA Forward / Reverse Strand", "Forward and reverse phase evolution represent paired strands.", "DNA / paired strands"),
    DNA_HARMONIC_ENCODING("DNA Harmonic Encoding", "Source energy drives a harmonic stack using integer strand-like ratios.", "DNA / information"),
    DNA_LONGITUDINAL("DNA Longitudinal-Inspired", "Anti-phase acoustic pair representing claimed longitudinal DNA-wave concepts.", "DNA / fringe resonance"),
    DNA_SCALAR("DNA Scalar-Inspired", "Conjugate-like paired acoustic carrier with slow envelope.", "DNA / fringe resonance"),
    DNA_INFORMATION_FREQUENCY("DNA Information-Frequency", "Source values are quantized into a small information-bearing tone alphabet.", "DNA / information"),
    DNA_RESONANT_ANTENNA("DNA Resonant Antenna Representation", "Coupled carrier and sideband resonance representation inspired by DNA-antenna metaphors.", "DNA / resonance representation"),

    SCHUMANN_FUNDAMENTAL("Schumann Fundamental Mapping", "Maps the source onto a 7.83 Hz acoustic amplitude envelope.", "Earth / Schumann"),
    SCHUMANN_MULTI("Multiple Schumann Modes", "Combines several commonly cited Schumann resonance bands as an acoustic modulation bank.", "Earth / Schumann"),
    SCHUMANN_NEAREST("Nearest-Schumann Mapping", "Estimates source periodicity and maps it toward the nearest Schumann-band representation.", "Earth / Schumann"),
    SCHUMANN_AM("Schumann Amplitude Modulation", "Applies a 7.83 Hz acoustic AM envelope to the selected carrier.", "Earth / Schumann"),
    SCHUMANN_HARMONIC_SUBHARMONIC("Schumann Harmonic / Subharmonic", "Layers the fundamental with octave-like harmonic and subharmonic relationships.", "Earth / Schumann"),
    SCHUMANN_SOURCE_SPECTRUM("Source Spectrum → Nearest Schumann", "Tracks source zero-crossing periodicity and folds it to the nearest Schumann-band representation.", "Earth / Schumann"),

    RESONANT_OSCILLATOR("Resonant Oscillator", "Source drives a damped acoustic oscillator.", "Tesla / resonance"),
    STANDING_WAVES("Standing Waves", "Counter-phase oscillator pair creates a stereo standing-wave representation.", "Tesla / resonance"),
    PULSED_RESONANCE("Pulsed Resonance", "Resonant oscillator is gated into controlled acoustic pulse packets.", "Tesla / resonance"),
    HV_IMPULSE_SONIFICATION("High-Voltage Impulse Sonification", "Sharp source transients are converted to bipolar acoustic impulse events. No electrical high voltage is generated.", "Tesla / impulse sonification"),
    EARTH_RESONANCE("Earth Resonance", "Multi-band Schumann-style acoustic resonance representation.", "Earth / resonance"),
    LONGITUDINAL_WAVE_REP("Longitudinal-Inspired Wave", "Opposed stereo phase pair representing longitudinal-wave descriptions acoustically.", "Tesla / longitudinal representation"),
    WIRELESS_RESONANCE("Wireless-Resonance Representation", "Two coupled acoustic oscillators exchange energy through a controllable beat relationship.", "Tesla / resonance"),
    HARMONIC_RESONANCE("Harmonic Resonance", "Integer-ratio harmonic oscillator stack driven by source amplitude.", "Tesla / resonance"),
    MECHANICAL_RESONANCE("Oscillatory Mechanical Resonance", "Damped mechanical-style acoustic resonator driven by source audio.", "Mechanical resonance"),

    TRI_NODE_120("Three-Node 120° Rotation", "Three synchronized oscillator nodes begin 120° apart; source controls amplitude and frequency while motion controls rotation and presence controls convergence.", "Rotating field geometry"),
    QUAD_PHASE_ROTATION("0° / 90° / 180° / 270° Rotation", "Four mathematical phase nodes rotate continuously and are mixed to stereo.", "Quadrupole / four-node"),
    EXTREME_FAST_MOD("Extremely Fast Modulation Representation", "Source-dependent rapid acoustic modulation and carrier phase movement.", "Fast modulation"),
    CHIRPED_OSCILLATION("Chirped Oscillation", "Repeating acoustic frequency sweep around the selected carrier.", "Quadrupole / chirp"),
    QUADRUPOLE_FOUR_NODE("Quadrupole Four-Node Pattern", "Four phase nodes form two opposing pairs and rotate as a quadrupolar acoustic pattern.", "Quadrupole / four-node"),
    COUNTER_OSCILLATING_PAIRS("Counter-Oscillating Pairs", "Two oscillator pairs run in opposite phase relationships.", "Quadrupole / paired oscillators"),
    FREQ_ACCELERATION("Frequency Acceleration", "Repeated upward accelerating frequency trajectory.", "Quadrupole / chirp"),
    FREQ_DECELERATION("Frequency Deceleration", "Repeated downward decelerating frequency trajectory.", "Quadrupole / chirp"),
    GRAV_WAVE_CHIRP("Gravitational-Wave Chirp Sonification", "Rising frequency and amplitude curve inspired by gravitational-wave chirp sonification.", "Quadrupole / chirp"),
    OSCILLATING_QUADRUPOLE_VISUAL("Oscillating Quadrupole Visualization", "Four-node rotating quadrupole audio intended to be watched in the live waveform/spectrum display.", "Quadrupole / visualization"),
    PAIS_AUDIO_STACK("Pais-Inspired Audio Stack", "Fast modulation, oscillating carrier, rotating stereo phase, harmonic layer and pulsed envelope combined as an acoustic-only representation.", "Pais-inspired acoustic representation"),

    SINGLE_HELIX("Single Helix", "One continuously rotating stereo phase helix.", "Helical geometry"),
    DOUBLE_HELIX("Double Helix", "Two coupled helical phase paths.", "Helical geometry"),
    COUNTER_ROTATING_HELIX("Counter-Rotating Helix", "Paired helices rotate in opposite directions.", "Helical geometry"),
    CADUCEUS_HELIX("Caduceus", "Counter-wound nested helices cross periodically.", "Helical geometry"),
    CROSSING_FREQ_SWEEPS("Crossing Frequency Sweeps", "Left and right frequency sweeps cross at the midpoint.", "Helical spectral movement"),
    LEFT_ASC_RIGHT_DESC("Left Ascending / Right Descending", "Left frequency rises while right frequency falls.", "Helical spectral movement"),
    LEFT_DESC_RIGHT_ASC("Left Descending / Right Ascending", "Left frequency falls while right frequency rises.", "Helical spectral movement"),
    INTERTWINED_PHASE("Intertwined Phase Modulation", "Two phase paths weave through one another with opposite rotation.", "Helical geometry"),
    HELICAL_STEREO_ROTATION("Helical Stereo Rotation", "Continuous opposite-direction phase rotation across the stereo field.", "Helical geometry"),
    HELICAL_SPECTRAL_MOVEMENT("Helical Spectral Movement", "Three spectral bands rotate at related angular rates.", "Helical spectral movement"),
    NESTED_HELICES("Nested Helices", "Fast and slow helical phase structures are nested together.", "Helical geometry")
}

object ResonancePresetCatalog {
    fun preset(mode: ResonanceMode): MethodPreset = when (mode) {
        ResonanceMode.DNA_MONTAGNIER_LF_SIGNAL -> MethodPreset(mode.label, mode.category, "Experimental acoustic low-frequency representation.", null, 7f, .42f)
        ResonanceMode.SCHUMANN_FUNDAMENTAL, ResonanceMode.SCHUMANN_AM -> MethodPreset(mode.label, mode.category, "7.83 Hz acoustic mapping.", 18_000f, 7.83f, .42f, 0f, 35f, 8.5f)
        ResonanceMode.SCHUMANN_MULTI, ResonanceMode.EARTH_RESONANCE -> MethodPreset(mode.label, mode.category, "7.83 / 14.3 / 20.8 / 27.3 / 33.8 Hz bank.", 18_000f, 7.83f, .40f)
        ResonanceMode.SCHUMANN_HARMONIC_SUBHARMONIC -> MethodPreset(mode.label, mode.category, "3.915 / 7.83 / 15.66 Hz layered mapping.", 18_000f, 7.83f, .42f)
        ResonanceMode.SCHUMANN_NEAREST, ResonanceMode.SCHUMANN_SOURCE_SPECTRUM -> MethodPreset(mode.label, mode.category, "Dynamic source-periodicity mapping to nearest Schumann-band representation.", 18_000f, 7.83f, .42f)
        ResonanceMode.TRI_NODE_120 -> MethodPreset(mode.label, mode.category, "Three nodes 120° apart; motion rotates and presence controls phase convergence.", 18_000f, 7.83f, .46f, 0f, 35f, 8.5f, 6f, .20f)
        ResonanceMode.QUAD_PHASE_ROTATION, ResonanceMode.QUADRUPOLE_FOUR_NODE, ResonanceMode.OSCILLATING_QUADRUPOLE_VISUAL, ResonanceMode.COUNTER_OSCILLATING_PAIRS -> MethodPreset(mode.label, mode.category, "Four-node quadrature geometry rendered to stereo.", 18_000f, 7.83f, .44f, 0f, 35f, 8.5f, 8f, .18f)
        ResonanceMode.GRAV_WAVE_CHIRP, ResonanceMode.CHIRPED_OSCILLATION, ResonanceMode.FREQ_ACCELERATION, ResonanceMode.FREQ_DECELERATION -> MethodPreset(mode.label, mode.category, "Repeated chirp trajectory.", 8_000f, 4f, .44f, null, null, null, 4f, .20f)
        ResonanceMode.EXTREME_FAST_MOD -> MethodPreset(mode.label, mode.category, "Rapid source-dependent acoustic modulation representation.", 18_000f, 40f, .38f, 0f, 35f, 8.5f, 2f, .25f)
        ResonanceMode.PAIS_AUDIO_STACK -> MethodPreset(mode.label, mode.category, "Acoustic-only stack: rapid modulation + carrier + rotating phase + harmonics + pulses.", 18_500f, 7.83f, .46f, 0f, 35f, 8.5f, 8f, .22f)
        ResonanceMode.SINGLE_HELIX, ResonanceMode.DNA_HELICAL_MODULATION, ResonanceMode.HELICAL_STEREO_ROTATION -> MethodPreset(mode.label, mode.category, "Single rotating stereo helix.", 12_000f, 7.83f, .42f, null, null, null, 8f, .18f)
        ResonanceMode.DOUBLE_HELIX, ResonanceMode.COUNTER_ROTATING_HELIX, ResonanceMode.CADUCEUS_HELIX, ResonanceMode.DNA_CADUCEUS, ResonanceMode.NESTED_HELICES, ResonanceMode.INTERTWINED_PHASE -> MethodPreset(mode.label, mode.category, "Counter-wound / nested phase helices.", 12_000f, 7.83f, .46f, null, null, null, 10f, .16f)
        ResonanceMode.CROSSING_FREQ_SWEEPS, ResonanceMode.LEFT_ASC_RIGHT_DESC, ResonanceMode.LEFT_DESC_RIGHT_ASC, ResonanceMode.HELICAL_SPECTRAL_MOVEMENT -> MethodPreset(mode.label, mode.category, "Moving stereo spectral trajectories.", 8_000f, 6.18f, .44f, null, null, null, 8f, .18f)
        ResonanceMode.DNA_SONIFICATION, ResonanceMode.DNA_INFORMATION_FREQUENCY, ResonanceMode.DNA_HARMONIC_ENCODING -> MethodPreset(mode.label, mode.category, "Source-driven information/harmonic sonification.", null, 7.83f, .44f)
        ResonanceMode.DNA_LIGHT_SOUND -> MethodPreset(mode.label, mode.category, "Audio amplitude pulse synchronized to live display response.", 4_000f, 7.83f, .48f)
        ResonanceMode.DNA_FORWARD_REVERSE_STRAND, ResonanceMode.DNA_LONGITUDINAL, ResonanceMode.LONGITUDINAL_WAVE_REP -> MethodPreset(mode.label, mode.category, "Forward/reverse or opposed phase-pair representation.", 18_000f, 7.83f, .46f)
        ResonanceMode.DNA_MEYL_RESONANCE, ResonanceMode.DNA_SCALAR -> MethodPreset(mode.label, mode.category, "Phase-conjugate helical acoustic representation.", 18_000f, 7.83f, .48f, null, null, null, 8f, .16f)
        ResonanceMode.DNA_EM_RESONANCE, ResonanceMode.DNA_RESONANT_ANTENNA -> MethodPreset(mode.label, mode.category, "Acoustic carrier/sideband resonance representation.", 18_500f, 7.83f, .44f)
        ResonanceMode.RESONANT_OSCILLATOR -> MethodPreset(mode.label, mode.category, "Damped source-driven acoustic resonator.", null, 60f, .42f)
        ResonanceMode.STANDING_WAVES -> MethodPreset(mode.label, mode.category, "Counter-phase standing-wave representation.", 4_000f, 7.83f, .42f)
        ResonanceMode.PULSED_RESONANCE -> MethodPreset(mode.label, mode.category, "Short resonance packets at selected pulse rate.", 4_000f, 7.83f, .48f)
        ResonanceMode.HV_IMPULSE_SONIFICATION -> MethodPreset(mode.label, mode.category, "Source transients rendered as acoustic bipolar impulses only.", null, 12f, .34f)
        ResonanceMode.WIRELESS_RESONANCE -> MethodPreset(mode.label, mode.category, "Coupled oscillator / beat representation.", 6_000f, 7.83f, .42f)
        ResonanceMode.HARMONIC_RESONANCE -> MethodPreset(mode.label, mode.category, "Integer-ratio harmonic stack.", 4_000f, 7.83f, .44f)
        ResonanceMode.MECHANICAL_RESONANCE -> MethodPreset(mode.label, mode.category, "Damped mechanical-style resonance.", null, 18f, .46f)
        else -> MethodPreset(mode.label, mode.category, "Experimental acoustic representation.", 18_000f, 7.83f, .44f, 0f, 35f, 8.5f, 4f, .18f)
    }
}

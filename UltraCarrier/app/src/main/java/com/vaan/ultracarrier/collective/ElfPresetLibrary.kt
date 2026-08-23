package com.vaan.ultracarrier.collective

data class ElfPreset(
    val label: String,
    val category: String,
    val note: String,
    val rateHz: Float,
    val mode: MatrixMode = MatrixMode.ELF_LAYER,
    val carrierHz: Float = 18_000f,
    val depth: Float = .44f
)

object ElfPresetLibrary {
    val presets: List<ElfPreset> = listOf(
        ElfPreset("Custom ELF Carrier", "Core / custom", "General slow-rate acoustic AM carrier. Pick this, then type any rate with the normal Rate control.", 7.83f, MatrixMode.ELF_LAYER, 18_000f, .44f),
        ElfPreset("Acoustic + ELF", "Core / custom", "Direct source plus a slow acoustic modulation layer.", 7.83f, MatrixMode.ACOUSTIC_ELF_STACK, 12_000f, .42f),
        ElfPreset("ELF Stereo Difference", "Core / custom", "Selected low rate becomes the left/right difference relationship for headphone experiments.", 7f, MatrixMode.BINAURAL_MATRIX, 400f, .30f),
        ElfPreset("ELF Phase Rotation", "Core / custom", "Low rate drives interchannel phase evolution.", 7.83f, MatrixMode.PHASE_OFFSET_STEREO, 6_000f, .38f),
        ElfPreset("ELF Counter-Rotation", "Core / custom", "Opposed stereo phase motion with the selected low rate.", 7.83f, MatrixMode.COUNTER_ROTATING_STEREO, 6_000f, .38f),
        ElfPreset("ELF Soliton Pulse", "Core / custom", "Stable traveling pulse-envelope representation at the selected low repetition rate.", 4f, MatrixMode.SOLITON_PULSE, 6_000f, .44f),
        ElfPreset("ELF Standing Pattern", "Core / custom", "Opposing acoustic components create a standing-wave-style stereo representation.", 7.83f, MatrixMode.STANDING_WAVE_MATRIX, 4_000f, .40f),
        ElfPreset("ELF Coherence Lock", "Core / custom", "Low-rate modulation is combined with a phase-order/coherence representation.", 7.83f, MatrixMode.COHERENCE_METER_AUDIO, 6_000f, .40f),

        ElfPreset("Schumann Fundamental 7.83", "Earth / Schumann", "7.83 Hz reference mapped as an acoustic modulation envelope.", 7.83f, MatrixMode.SCHUMANN_LAYER, 18_000f, .42f),
        ElfPreset("Schumann 14.3", "Earth / Schumann", "14.3 Hz Schumann-band reference as acoustic modulation.", 14.3f, MatrixMode.SCHUMANN_LAYER, 18_000f, .40f),
        ElfPreset("Schumann 20.8", "Earth / Schumann", "20.8 Hz Schumann-band reference as acoustic modulation.", 20.8f, MatrixMode.SCHUMANN_LAYER, 18_000f, .40f),
        ElfPreset("Schumann 27.3", "Earth / Schumann", "27.3 Hz Schumann-band reference as acoustic modulation.", 27.3f, MatrixMode.SCHUMANN_LAYER, 18_000f, .38f),
        ElfPreset("Schumann 33.8", "Earth / Schumann", "33.8 Hz Schumann-band reference as acoustic modulation.", 33.8f, MatrixMode.SCHUMANN_LAYER, 18_000f, .38f),
        ElfPreset("Schumann 39.8", "Earth / Schumann", "39.8 Hz Schumann-band reference as acoustic modulation.", 39.8f, MatrixMode.SCHUMANN_LAYER, 18_000f, .36f),
        ElfPreset("Schumann 45.9", "Earth / Schumann", "45.9 Hz Schumann-band reference as acoustic modulation.", 45.9f, MatrixMode.SCHUMANN_LAYER, 18_000f, .36f),
        ElfPreset("Schumann + Geomagnetic", "Earth / Schumann", "Schumann-rate acoustic modulation combined with the app's slow geomagnetic-style drift representation.", 7.83f, MatrixMode.SCHUMANN_GEOMAGNETIC, 12_000f, .40f),
        ElfPreset("Geomagnetic Drift", "Earth / Schumann", "Slow field-drift style acoustic modulation.", 7.83f, MatrixMode.GEOMAGNETIC, 6_000f, .34f),
        ElfPreset("Auto Nearest-Schumann", "Earth / Schumann", "Tracks source periodicity and folds it toward a Schumann-related acoustic rate.", 7.83f, MatrixMode.AUTO_SCHUMANN_ROUND, 18_000f, .40f),

        ElfPreset("0.10 Hz", "Exact low-rate references", "Very slow acoustic envelope reference.", .10f),
        ElfPreset("0.50 Hz", "Exact low-rate references", "Half-hertz acoustic envelope reference.", .50f),
        ElfPreset("1 Hz", "Exact low-rate references", "One-cycle-per-second acoustic envelope reference.", 1f),
        ElfPreset("2 Hz", "Exact low-rate references", "2 Hz acoustic envelope reference.", 2f),
        ElfPreset("3 Hz", "Exact low-rate references", "3 Hz acoustic envelope reference.", 3f),
        ElfPreset("4 Hz", "Exact low-rate references", "4 Hz acoustic envelope reference.", 4f),
        ElfPreset("5 Hz", "Exact low-rate references", "5 Hz acoustic envelope reference.", 5f),
        ElfPreset("6 Hz", "Exact low-rate references", "6 Hz acoustic envelope reference.", 6f),
        ElfPreset("7 Hz", "Exact low-rate references", "7 Hz transcript/historical reference as acoustic modulation.", 7f, MatrixMode.MONTAGNIER_7HZ),
        ElfPreset("8 Hz", "Exact low-rate references", "8 Hz acoustic envelope reference.", 8f),
        ElfPreset("10 Hz", "Exact low-rate references", "10 Hz acoustic envelope reference.", 10f),
        ElfPreset("12 Hz", "Exact low-rate references", "12 Hz acoustic envelope reference.", 12f),
        ElfPreset("16.67 Hz", "Exact low-rate references", "16.67 Hz low-frequency reference.", 16.67f),
        ElfPreset("20 Hz", "Exact low-rate references", "20 Hz acoustic envelope reference.", 20f),
        ElfPreset("25 Hz", "Exact low-rate references", "25 Hz low-frequency reference.", 25f),
        ElfPreset("30 Hz", "Exact low-rate references", "30 Hz acoustic envelope reference.", 30f),
        ElfPreset("40 Hz", "Exact low-rate references", "40 Hz acoustic amplitude-modulation reference.", 40f, MatrixMode.ELF_LAYER, 18_000f, .36f),
        ElfPreset("50 Hz", "Exact low-rate references", "50 Hz mains-frequency reference represented acoustically.", 50f, MatrixMode.ELF_LAYER, 18_000f, .32f),
        ElfPreset("60 Hz", "Exact low-rate references", "60 Hz mains-frequency reference represented acoustically.", 60f, MatrixMode.ELF_LAYER, 18_000f, .32f),
        ElfPreset("80 Hz", "Exact low-rate references", "80 Hz low-rate acoustic modulation reference.", 80f, MatrixMode.ELF_LAYER, 18_000f, .30f),
        ElfPreset("100 Hz", "Exact low-rate references", "100 Hz acoustic modulation reference.", 100f, MatrixMode.ELF_LAYER, 18_000f, .28f),
        ElfPreset("120 Hz", "Exact low-rate references", "Upper edge of this clone's low-rate control range.", 120f, MatrixMode.ELF_LAYER, 18_000f, .26f),

        ElfPreset("Slow 0.5–4 Reference", "Rhythm-band references", "Preset centered at 2 Hz for exploring very slow periodic acoustic structure; no biological effect is assumed.", 2f, MatrixMode.ELF_LAYER, 12_000f, .42f),
        ElfPreset("4–8 Reference", "Rhythm-band references", "Preset centered at 6 Hz for low-rate acoustic comparison.", 6f, MatrixMode.ELF_LAYER, 12_000f, .40f),
        ElfPreset("8–12 Reference", "Rhythm-band references", "Preset centered at 10 Hz for acoustic comparison.", 10f, MatrixMode.ELF_LAYER, 12_000f, .38f),
        ElfPreset("12–30 Reference", "Rhythm-band references", "Preset centered at 20 Hz for acoustic comparison.", 20f, MatrixMode.ELF_LAYER, 12_000f, .36f),
        ElfPreset("30–80 Reference", "Rhythm-band references", "Preset centered at 40 Hz for acoustic comparison.", 40f, MatrixMode.ELF_LAYER, 12_000f, .34f),

        ElfPreset("7.83 + Fractal", "Experimental ELF patterns", "Schumann-rate envelope routed through the fractal/multiscale matrix processor.", 7.83f, MatrixMode.FRACTAL_SIGNAL, 8_000f, .42f),
        ElfPreset("7.83 + Heterodyne Proxy", "Experimental ELF patterns", "Selected low rate controls an acoustic difference relationship between proxy carriers.", 7.83f, MatrixMode.HETERODYNE_MATRIX, 12_000f, .38f),
        ElfPreset("7.83 + Phase Conjugate", "Experimental ELF patterns", "Low-rate motion combined with forward/conjugate-like stereo phase paths.", 7.83f, MatrixMode.PHASE_CONJUGATE, 18_000f, .40f),
        ElfPreset("7.83 + Toroidal", "Experimental ELF patterns", "Schumann-rate modulation combined with the toroidal phase visualization/sonification.", 7.83f, MatrixMode.TOROIDAL_VISUAL, 8_000f, .42f),
        ElfPreset("7.83 + Longitudinal-Inspired", "Experimental ELF patterns", "Low-rate envelope combined with an opposed pressure/phase acoustic representation.", 7.83f, MatrixMode.LONGITUDINAL_SIM, 6_000f, .42f),
        ElfPreset("7 + DNA/Scalar Stack", "Experimental ELF patterns", "7 Hz envelope applied to the automatic DNA/scalar acoustic matrix representation.", 7f, MatrixMode.AUTO_DNA_SCALAR_STACK, 18_000f, .42f),
        ElfPreset("40 Hz Coherence", "Experimental ELF patterns", "40 Hz acoustic modulation paired with phase-order processing.", 40f, MatrixMode.COHERENCE_METER_AUDIO, 6_000f, .34f),
        ElfPreset("60 Hz Interference", "Experimental ELF patterns", "60 Hz reference combined with delayed acoustic interference.", 60f, MatrixMode.INTERFERENCE, 6_000f, .30f)
    )

    val grouped: Map<String, List<ElfPreset>> = presets.groupBy { it.category }
}

package com.vaan.ultracarrier.collective

data class ResearchPreset(
    val label: String,
    val category: String,
    val note: String,
    val mode: MatrixMode,
    val carrierHz: Float? = null,
    val rateHz: Float = 7.83f,
    val depth: Float = .42f
)

object ResearchPresetLibrary {
    val presets = listOf(
        ResearchPreset("Hassler Longitudinal Carrier", "US 10,022,517 / programmable-matter patent", "Acoustic phase-paired representation of the patent's claimed longitudinal/scalar carrier. This app generates PCM audio, not a scalar electromagnetic field.", MatrixMode.PHASE_CONJUGATE, 18_000f, 7.83f, .42f),
        ResearchPreset("Hassler Information Modulation", "US 10,022,517 / programmable-matter patent", "Source envelope modulates an acoustic proxy carrier as a testable signal-processing analogue of the patent's information-on-carrier language.", MatrixMode.AUDIO_TO_RF_SYMBOLIC, 18_000f, 7.83f, .44f),
        ResearchPreset("Hassler Light + Sound", "US 10,022,517 / programmable-matter patent", "Audio plus synchronized visual/light representation based on the patent's light-and/or-sound modulation description.", MatrixMode.ACOUSTIC_LIGHT_SYNC, null, 7.83f, .46f),
        ResearchPreset("Holographic Programmable Layer", "US 10,022,517 / programmable-matter patent", "Acoustic hologram/interference representation inspired by the patent's holographic programmable-layer claim.", MatrixMode.ACOUSTIC_HOLOGRAM, 18_000f, 7.83f, .42f),
        ResearchPreset("Carbon-Fiber Layer Resonance", "US 10,022,517 / programmable-matter patent", "Resonant acoustic material-layer representation inspired by the carbon-fiber embodiment.", MatrixMode.RESONANCE_LOCK, 4_000f, 7.83f, .40f),
        ResearchPreset("QC Coil / Chamber Geometry", "US 10,022,517 / programmable-matter patent", "Rotating/interference geometry used as an acoustic visualization of a coil/chamber information encoder.", MatrixMode.TSIEN_KANCHEN_GEOMETRY, 8_000f, 7.83f, .42f),
        ResearchPreset("Eight-Code Harmonic Bank", "US 10,022,517 / software concept", "Broad harmonic bank for experimenting with eight-code style information stacks. Add eight individually tuned layers to Layer Stack for exact independent frequencies.", MatrixMode.BROADBAND_SPECTRUM, 4_000f, 7.83f, .40f),
        ResearchPreset("Patent 0.5 Hz Low Edge", "US 10,022,517 / frequency-library reference", "0.5 Hz is represented literally as the modulation/envelope rate. Physical transducer response determines whether sub-audio motion exists at the output.", MatrixMode.ELF_LAYER, 18_000f, .5f, .46f),
        ResearchPreset("Patent 7.83 Hz", "US 10,022,517 / frequency-library example", "7.83 Hz reference preserved as literal acoustic modulation rate.", MatrixMode.ELF_LAYER, 18_000f, 7.83f, .44f),
        ResearchPreset("Patent 369 Hz", "US 10,022,517 / frequency-library example", "369 Hz literal PCM carrier when the selected audio path supports it.", MatrixMode.HARMONIC_RESONANCE_MATRIX, 369f, 7.83f, .38f),
        ResearchPreset("Patent 417 Hz", "US 10,022,517 / frequency-library example", "417 Hz literal PCM carrier when supported.", MatrixMode.HARMONIC_RESONANCE_MATRIX, 417f, 7.83f, .38f),
        ResearchPreset("Patent 432 Hz", "US 10,022,517 / frequency-library example", "432 Hz literal PCM carrier when supported.", MatrixMode.HARMONIC_RESONANCE_MATRIX, 432f, 7.83f, .38f),
        ResearchPreset("Patent 528 Hz", "US 10,022,517 / frequency-library example", "528 Hz literal PCM carrier when supported.", MatrixMode.PRESET_528, 528f, 7.83f, .38f),
        ResearchPreset("Patent 34 kHz High Edge", "US 10,022,517 / frequency-library reference", "34,000 Hz literal PCM carrier on a high-rate external audio path. A 96 kHz or higher output path is normally required to represent it cleanly.", MatrixMode.HETERODYNE_MATRIX, 34_000f, 7.83f, .30f),

        ResearchPreset("Basal Ganglia Theta 4–7", "Basal ganglia / neuroscience reference", "Acoustic sonification centered at 5.5 Hz, representing a literature frequency band rather than stimulating a brain structure.", MatrixMode.ELF_LAYER, 6_000f, 5.5f, .34f),
        ResearchPreset("Basal Ganglia Alpha 8–12", "Basal ganglia / neuroscience reference", "Acoustic sonification centered at 10 Hz.", MatrixMode.ELF_LAYER, 6_000f, 10f, .34f),
        ResearchPreset("Basal Ganglia Beta 13–30", "Basal ganglia / neuroscience reference", "Acoustic sonification centered at 20 Hz; beta-band activity is widely studied in basal-ganglia circuits.", MatrixMode.ELF_LAYER, 6_000f, 20f, .34f),
        ResearchPreset("Low Beta 13–20", "Basal ganglia / neuroscience reference", "Acoustic sonification centered at 16.5 Hz.", MatrixMode.ELF_LAYER, 6_000f, 16.5f, .34f),
        ResearchPreset("High Beta 21–35", "Basal ganglia / neuroscience reference", "Acoustic sonification centered at 28 Hz.", MatrixMode.ELF_LAYER, 6_000f, 28f, .32f),
        ResearchPreset("Basal Ganglia Gamma 70–80", "Basal ganglia / neuroscience reference", "Acoustic sonification centered at 75 Hz.", MatrixMode.ELF_LAYER, 6_000f, 75f, .30f),
        ResearchPreset("Basal Ganglia HFO 200–400", "Basal ganglia / neuroscience reference", "300 Hz literal carrier reference representing a recorded high-frequency-oscillation range discussed in the literature.", MatrixMode.RESONANCE_LOCK, 300f, 20f, .26f),
        ResearchPreset("Beta → Gamma Coupling", "Basal ganglia / neuroscience reference", "20 Hz envelope on a 75 Hz carrier as a software cross-frequency-coupling sonification.", MatrixMode.PHOTON_PHONON, 75f, 20f, .32f),
        ResearchPreset("Beta Burst Packets", "Basal ganglia / neuroscience reference", "20 Hz pulse/soliton-style packets for visualizing transient beta bursts.", MatrixMode.SOLITON_PULSE, 220f, 20f, .32f),
        ResearchPreset("Theta / Beta Stack Seed", "Basal ganglia / neuroscience reference", "5.5 Hz modulation with beta-range carrier. Add another gamma layer in Layer Stack to build a multi-band research recipe.", MatrixMode.PHASE_OFFSET_STEREO, 20f, 5.5f, .30f)
    )

    val grouped = presets.groupBy { it.category }
}

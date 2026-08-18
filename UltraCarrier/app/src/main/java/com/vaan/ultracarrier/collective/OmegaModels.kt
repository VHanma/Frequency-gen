package com.vaan.ultracarrier.collective

import com.vaan.ultracarrier.audio.BeamLabMode
import com.vaan.ultracarrier.audio.GodXMode
import com.vaan.ultracarrier.audio.HardwareMode
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.audio.ThoughtMode

enum class OmegaFamily(val label: String) {
    WORLD_BEAM("World Beam"),
    PERCEPTION_LAB("Perception Lab"),
    LAB_X("Lab X Originals"),
    THOUGHTBEAM("ThoughtBeam Classic"),
    SCALAR_LAB("Scalar Lab Original"),
    RESONANCE_LAB("Resonance Lab • DNA / Earth / Helix"),
    MATRIX_LAB("Matrix Lab • Transcript Library")
}

data class OmegaLayerRecipe(
    val id: Long,
    val label: String,
    val family: OmegaFamily,
    val worldMode: BeamLabMode,
    val collectiveMode: CollectiveMode,
    val labXMode: GodXMode,
    val classicMode: ThoughtMode,
    val scalarMode: ScalarMode,
    val resonanceMode: ResonanceMode,
    val matrixMode: MatrixMode,
    val listeningPath: ListeningPath,
    val carrierHz: Float,
    val presence: Float,
    val elfRateHz: Float,
    val elfDepth: Float,
    val targetAngleDeg: Float,
    val nullAngleDeg: Float,
    val spacingMm: Float,
    val ditherDeg: Float,
    val ditherRateHz: Float,
    val headWidthCm: Float,
    val listenerDistanceCm: Float
)

data class OmegaUiState(
    val text: String = "",
    val sourceName: String? = null,
    val source: CollectiveSource? = null,
    val hardware: HardwareMode? = null,
    val family: OmegaFamily = OmegaFamily.PERCEPTION_LAB,
    val worldMode: BeamLabMode = BeamLabMode.ELF_BEAM,
    val collectiveMode: CollectiveMode = CollectiveMode.THOUGHT_GHOST,
    val labXMode: GodXMode = GodXMode.VOICE_OF_GOD_STACK,
    val classicMode: ThoughtMode = ThoughtMode.CENTER_LOCK,
    val scalarMode: ScalarMode = ScalarMode.PHASE_CONJUGATE_PAIR,
    val resonanceMode: ResonanceMode = ResonanceMode.DNA_SONIFICATION,
    val matrixMode: MatrixMode = MatrixMode.VACUUM_POLARIZATION,
    val listeningPath: ListeningPath = ListeningPath.PHONE_SPEAKER,
    val presence: Float = 0.48f,
    val carrierHz: Float = 18_000f,
    val elfRateHz: Float = 7.83f,
    val elfDepth: Float = 0.36f,
    val targetAngleDeg: Float = 0f,
    val nullAngleDeg: Float = 35f,
    val spacingMm: Float = 8.5f,
    val ditherDeg: Float = 2f,
    val ditherRateHz: Float = 0.25f,
    val headWidthCm: Float = 15.5f,
    val listenerDistanceCm: Float = 45f,
    val loop: Boolean = true,
    val exportFormat: ExportFormat = ExportFormat.WAV_24,
    val busy: Boolean = false,
    val playing: Boolean = false,
    val stackPlaying: Boolean = false,
    val exporting: Boolean = false,
    val backgroundActive: Boolean = false,
    val exportProgress: Double? = null,
    val status: String = "Omega Matrix build ready.",
    val report: CollectiveReport? = null,
    val stackLayers: List<OmegaLayerRecipe> = emptyList(),
    val fadeRunning: Boolean = false,
    val fadeElapsedMs: Long? = null,
    val fadeTrials: List<FadeTrial> = emptyList(),
    val remotePatient: String = "",
    val remoteIntention: String = "",
    val remoteConsent: Boolean = false,
    val remoteSilent: Boolean = false,
    val remoteActive: Boolean = false,
    val remoteStatus: String = "Remote Resonance Session idle."
)

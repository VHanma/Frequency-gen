package com.vaan.ultracarrier.collective

import android.net.Uri
import com.vaan.ultracarrier.audio.BeamLabMode
import com.vaan.ultracarrier.audio.GodXMode
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.audio.ThoughtMode
import java.io.File

data class StreamInfo(
    val sampleRate: Int,
    val channels: Int,
    val durationSeconds: Double?,
    val formatLabel: String
)

sealed interface CollectiveSource {
    val info: StreamInfo

    data class UriSource(val uri: Uri, override val info: StreamInfo) : CollectiveSource
    data class FileSource(val file: File, override val info: StreamInfo) : CollectiveSource
}

enum class CollectiveFamily(val label: String) {
    WORLD_BEAM("World Beam"),
    PERCEPTION_LAB("Perception Lab"),
    LAB_X("Lab X Originals"),
    THOUGHTBEAM("ThoughtBeam Classic")
}

enum class CollectiveMode(val label: String, val description: String) {
    THOUGHT_GHOST(
        "Thought Ghost",
        "Center-locked speech with minimized room cues, narrow speech bandwidth, micro-delay drift and spectral softening to explore an internally located voice percept."
    ),
    PHONEMIC_RESTORE(
        "Phonemic Restore",
        "Brief pieces of speech are replaced by shaped noise. The brain can perceptually restore missing speech when masking sound occupies the gap."
    ),
    CONTINUITY_GHOST(
        "Continuity Ghost",
        "Speech is periodically occluded by noise to explore the auditory continuity illusion, where interrupted sound may be heard as continuing through the masker."
    ),
    SILENT_GAP_ECHO(
        "Silent Gap Echo",
        "Short intentional dropouts follow strongly encoded speech fragments to test whether the phrase continues subjectively during silence."
    ),
    AUDITORY_AFTERIMAGE(
        "Auditory Afterimage",
        "Notched-noise induction alternates with quiet windows to explore Zwicker-tone-like auditory afterimages."
    ),
    MIND_CANVAS(
        "Mind Canvas",
        "Voice energy drives spatial pitch trajectories and harmonic layers using pitch-height and size-pitch crossmodal correspondences as imagery cues."
    ),
    IMAGE_SEED_GEOMETRY(
        "Image Seed Geometry",
        "Spatial and pitch motion traces repeating circle, rise, fall and corner-like trajectories as an eyes-closed visual-imagery experiment."
    ),
    HYPERPHANTASIA_SEED(
        "Hyperphantasia Seed",
        "A richer harmonic scene engine uses slow depth, spatial motion and spectral contrast to encourage dynamic visual imagery during eyes-closed listening."
    ),
    ATTENTION_NULL(
        "Attention Null",
        "A stable center voice is paired with slow peripheral auditory motion for a self-test of whether redirected attention changes visual fading time."
    ),
    LACERTA_FILTER_TEST(
        "Lacerta Filter Test",
        "A self-experiment inspired by the Lacerta camouflage claim. It pairs low-novelty centered audio with the app's visual fading test and records disappearance time."
    ),
    SOUND_FLASH_SEED(
        "Sound→Flash Seed",
        "Tightly timed click pairs are designed for the on-screen flash test, based on the sound-induced flash illusion."
    ),
    MISSING_FUNDAMENTAL(
        "Missing Fundamental Thought",
        "A harmonic stack omits its fundamental while preserving the implied pitch, testing a sound that is perceptually constructed rather than physically present."
    )
}

enum class ExportFormat(val label: String, val bits: Int, val floatPcm: Boolean) {
    WAV_16("WAV 16-bit", 16, false),
    WAV_24("WAV 24-bit", 24, false),
    WAV_FLOAT32("WAV 32-bit float", 32, true)
}

data class CollectiveConfig(
    val family: CollectiveFamily,
    val worldMode: BeamLabMode,
    val collectiveMode: CollectiveMode,
    val labXMode: GodXMode,
    val classicMode: ThoughtMode,
    val listeningPath: ListeningPath,
    val requestedSampleRate: Int,
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

data class CollectiveReport(
    val sampleRate: Int,
    val routeName: String,
    val family: CollectiveFamily,
    val modeLabel: String,
    val carrierHz: Float,
    val dominantHz: Float = 0f
)

data class FadeTrial(
    val modeLabel: String,
    val elapsedMs: Long,
    val timestampMs: Long
)

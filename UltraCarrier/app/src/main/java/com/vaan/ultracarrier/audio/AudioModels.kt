package com.vaan.ultracarrier.audio

import android.media.AudioDeviceInfo
import android.net.Uri

data class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationSeconds: Double
)

data class AudioStreamInfo(
    val sampleRate: Int,
    val channels: Int,
    val durationSeconds: Double?,
    val formatLabel: String
)

sealed interface PreparedAudioSource {
    data class Memory(val pcm: PcmAudio) : PreparedAudioSource
    data class StreamFile(val uri: Uri, val info: AudioStreamInfo) : PreparedAudioSource
}

enum class ThoughtMode(val label: String, val description: String) {
    INNER_VOICE(
        "Inner Voice",
        "Centered, narrow-band speech for headphones, bone conduction, or the phone speaker."
    ),
    PATENT_SSB(
        "Patent SSB",
        "Experimental upper-sideband, suppressed-carrier translation inspired by US5159703A."
    ),
    FM_SLOPE(
        "FM Slope",
        "Experimental high-frequency FM inspired by the patent's slope-detection example."
    ),
    BEAM_WHISPER(
        "Beam Whisper",
        "Reduced-carrier directional mode inherited from UltraCarrier Beam."
    )
}

enum class ListeningPath(val label: String, val description: String) {
    HEADPHONES(
        "Headphones",
        "Identical left and right channels place the voice near the center of the head."
    ),
    BONE_CONDUCTION(
        "Bone Conduction",
        "Filtered speech for a bone-conduction headset. Start at low volume."
    ),
    PHONE_SPEAKER(
        "Phone Speaker",
        "Full speaker support. Beam Whisper is the most directional profile."
    )
}

data class HardwareMode(
    val label: String,
    val outputDevice: AudioDeviceInfo?,
    val requestedSampleRate: Int,
    val carrierMinHz: Float,
    val carrierMaxHz: Float,
    val external: Boolean,
    val detail: String
)

data class TransmissionReport(
    val actualSampleRate: Int,
    val actualCarrierHz: Float,
    val messageBandwidthHz: Float,
    val routedDeviceName: String,
    val thoughtMode: ThoughtMode,
    val listeningPath: ListeningPath,
    val outputGain: Float
)

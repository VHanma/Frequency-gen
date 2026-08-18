package com.vaan.ultracarrier.audio

import android.media.AudioDeviceInfo

data class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationSeconds: Double
)

enum class ThoughtMode(val label: String, val description: String) {
    INNER_VOICE(
        "Inner Voice",
        "The proven centered voice engine from the working InnerVoice app."
    ),
    CENTER_LOCK(
        "Center Lock",
        "Dual-mono narrow-band speech with reduced spatial cues for a stronger inside-the-head headphone image."
    ),
    FREY_ACOUSTIC_SIM(
        "Cranial Click",
        "Ordinary audio blended with brief zero-DC click packets to imitate the reported click-like quality without microwave output."
    ),
    MASKED_WHISPER(
        "Masked Whisper",
        "Low-level speech blended with shaped noise so the voice feels less like a room source and more like an internal trace."
    ),
    BONE_TAP(
        "Bone Tap",
        "Speech with low-frequency tap emphasis for bone-conduction headsets or close-contact listening."
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
    ),
    AIR_HETERODYNE(
        "Air Heterodyne",
        "Square-root precompensated AM for an external ultrasonic parametric speaker. The audible signal forms through nonlinear interaction in air."
    ),
    ARRAY_STEER(
        "Stereo Array Steer",
        "Two-channel acoustic phase steering for a dual-transducer ultrasonic array."
    ),
    CHIRP_CARRIER(
        "Chirp Carrier",
        "A swept acoustic carrier experiment. This spreads carrier energy over a selectable frequency span."
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
        "Uses the phone speaker. Inner Voice, Cranial Click, Masked Whisper, and Beam Whisper are the useful phone-only profiles."
    ),
    EXTERNAL_ARRAY(
        "External Ultrasonic Array",
        "Stereo USB DAC or external driver feeding two ultrasonic transducers. Required for real acoustic beam steering."
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
    val outputGain: Float,
    val arrayPhaseDegrees: Float = 0f,
    val chirpSweepHz: Float = 0f,
    val clickRateHz: Float = 0f,
    val clickWidthMs: Float = 0f
)

package com.vaan.ultracarrier.audio

import android.media.AudioDeviceInfo

data class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationSeconds: Double
)

enum class ModulationMode(val label: String) {
    AM("AM envelope"),
    DSB_SC("Reduced-carrier DSB")
}

enum class PrivacyMode(val label: String, val description: String) {
    PHONE_BEAM(
        "Phone Beam",
        "Reduces audible leakage and uses the highest safe phone carrier. The phone speaker still limits beam width."
    ),
    STANDARD(
        "Standard",
        "Strongest phone output with less leakage suppression."
    ),
    EXTERNAL_ARRAY(
        "External Array",
        "For a USB DAC and ultrasonic transducer array. This is the path to a genuinely narrow beam."
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
    val privacyMode: PrivacyMode,
    val outputGain: Float
)

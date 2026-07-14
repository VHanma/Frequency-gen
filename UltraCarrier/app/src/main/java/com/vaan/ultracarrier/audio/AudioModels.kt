package com.vaan.ultracarrier.audio

import android.media.AudioDeviceInfo

data class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationSeconds: Double
)

enum class ModulationMode(val label: String) {
    AM("Strong envelope AM"),
    DSB_SC("Double-sideband suppressed carrier")
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
    val routedDeviceName: String
)

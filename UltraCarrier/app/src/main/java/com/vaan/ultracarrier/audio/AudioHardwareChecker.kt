package com.vaan.ultracarrier.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlin.math.max
import kotlin.math.min

class AudioHardwareChecker(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var callback: AudioDeviceCallback? = null

    fun start(onChanged: (HardwareMode) -> Unit) {
        stop()
        callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                onChanged(detect())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                onChanged(detect())
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onChanged(detect())
    }

    fun stop() {
        callback?.let(audioManager::unregisterAudioDeviceCallback)
        callback = null
    }

    fun detect(): HardwareMode {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val external = outputs
            .filter { it.type in EXTERNAL_TYPES }
            .maxByOrNull(::priority)

        val nativeRate = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.coerceIn(44_100, 192_000)
            ?: 48_000

        if (external == null) {
            val builtIn = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val builtInRates = builtIn?.sampleRates?.filter { it in 44_100..192_000 }.orEmpty()
            val rate = max(48_000, builtInRates.maxOrNull() ?: nativeRate)
            return HardwareMode(
                label = "Internal speaker mode",
                outputDevice = builtIn,
                requestedSampleRate = rate,
                carrierMinHz = 15_000f,
                carrierMaxHz = min(22_000f, rate * 0.458f),
                external = false,
                detail = "Primary output reports ${rate / 1000.0} kHz. Carrier ceiling is 22 kHz."
            )
        }

        val reported = external.sampleRates.filter { it in 44_100..192_000 }.sortedDescending()
        val rate = when {
            192_000 in reported -> 192_000
            176_400 in reported -> 176_400
            96_000 in reported -> 96_000
            88_200 in reported -> 88_200
            reported.any { it > 48_000 } -> reported.first { it > 48_000 }
            external.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                external.type == AudioDeviceInfo.TYPE_USB_HEADSET -> 96_000
            else -> nativeRate
        }

        val name = external.productName?.toString().orEmpty().ifBlank { typeName(external.type) }
        val report = if (reported.isEmpty()) "sample rates not reported" else "reports ${reported.joinToString()} Hz"
        return HardwareMode(
            label = if (rate > 48_000) "External high-resolution mode" else "External output mode",
            outputDevice = external,
            requestedSampleRate = rate,
            carrierMinHz = 15_000f,
            carrierMaxHz = min(40_000f, rate * 0.45f),
            external = true,
            detail = "$name, $report. Requesting ${rate / 1000.0} kHz."
        )
    }

    private fun priority(device: AudioDeviceInfo): Int = when (device.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> 100
        AudioDeviceInfo.TYPE_USB_HEADSET -> 95
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> 90
        AudioDeviceInfo.TYPE_LINE_ANALOG -> 85
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> 80
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 70
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 65
        else -> 0
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "digital line output"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "analog line output"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI output"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC output"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC output"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        else -> "external audio device"
    }

    companion object {
        private val EXTERNAL_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        )
    }
}

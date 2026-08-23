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
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { onChanged(detect()) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { onChanged(detect()) }
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
        val external = outputs.filter { it.type in EXTERNAL_TYPES }.maxByOrNull(::priority)
        val nativeRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.coerceIn(44_100, 192_000) ?: 48_000

        if (external == null) {
            val builtIn = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val builtInRates = builtIn?.sampleRates?.filter { it in 44_100..192_000 }.orEmpty()
            val rate = max(48_000, builtInRates.maxOrNull() ?: nativeRate)
            return HardwareMode(
                label = "Internal speaker mode",
                outputDevice = builtIn,
                requestedSampleRate = rate,
                carrierMinHz = 13_500f,
                carrierMaxHz = min(22_000f, rate * 0.458f),
                external = false,
                detail = "Primary output reports ${rate / 1000.0} kHz. Internal carrier ceiling remains 22 kHz."
            )
        }

        val reported = external.sampleRates.filter { it in 44_100..192_000 }.sortedDescending()
        val rate = when {
            192_000 in reported -> 192_000
            176_400 in reported -> 176_400
            96_000 in reported -> 96_000
            88_200 in reported -> 88_200
            reported.any { it > 48_000 } -> reported.first { it > 48_000 }
            external.type == AudioDeviceInfo.TYPE_USB_DEVICE || external.type == AudioDeviceInfo.TYPE_USB_HEADSET -> 96_000
            else -> nativeRate
        }

        val name = external.productName?.toString().orEmpty().ifBlank { typeName(external.type) }
        val report = if (reported.isEmpty()) "sample rates not reported" else "reports ${reported.joinToString()} Hz"
        val pcmMax = rate * 0.45f
        return HardwareMode(
            label = if (rate > 48_000) "External literal-wideband mode" else "External listening mode",
            outputDevice = external,
            requestedSampleRate = rate,
            carrierMinHz = if (rate > 48_000) 80f else 13_500f,
            carrierMaxHz = if (rate > 48_000) pcmMax else min(22_000f, pcmMax),
            external = true,
            detail = if (rate > 48_000) "$name, $report. Requesting ${rate / 1000.0} kHz; literal PCM carrier control available from 80 Hz to about ${"%.1f".format(pcmMax / 1000f)} kHz. Sub-80-Hz references use the low-rate/envelope control." else "$name, $report. Requesting ${rate / 1000.0} kHz."
        )
    }

    private fun priority(device: AudioDeviceInfo): Int = when (device.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> 110
        AudioDeviceInfo.TYPE_USB_HEADSET -> 105
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 100
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 95
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 90
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 85
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 80
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> 75
        AudioDeviceInfo.TYPE_LINE_ANALOG -> 70
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> 65
        else -> 0
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "digital line output"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "analog line output"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI output"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC output"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC output"
        else -> "external audio device"
    }

    companion object {
        private val EXTERNAL_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC
        )
    }
}

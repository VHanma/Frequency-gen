package com.vhanma.lightcode

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.view.Display

internal object HardwareDiagnostics {
    fun report(context: Context, display: Display?): String {
        val lines = mutableListOf<String>()
        lines += "DEVICE: ${Build.MANUFACTURER} ${Build.MODEL}"
        lines += "ANDROID: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        if (display != null) {
            lines += "CURRENT DISPLAY: ${"%.2f".format(display.refreshRate)} Hz"
            val modes = display.supportedModes
                .sortedByDescending { it.refreshRate }
                .joinToString { "${it.physicalWidth}x${it.physicalHeight}@${"%.2f".format(it.refreshRate)}" }
            lines += "DISPLAY MODES: $modes"
        }

        val cameraManager = context.getSystemService(CameraManager::class.java)
        var flashFound = false
        for (id in cameraManager.cameraIdList) {
            val c = cameraManager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                flashFound = true
                val max = if (Build.VERSION.SDK_INT >= 33) {
                    c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                } else 1
                lines += "TORCH: camera $id, strength levels $max"
            }
        }
        if (!flashFound) lines += "TORCH: unavailable"

        val audioManager = context.getSystemService(AudioManager::class.java)
        val routes = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in setOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL
            ) }
        lines += if (routes.isEmpty()) {
            "EXTERNAL LED-DAC ROUTE: none connected"
        } else {
            "EXTERNAL LED-DAC ROUTES: " + routes.joinToString { it.productName?.toString() ?: "type ${it.type}" }
        }

        lines += "RASTER DAC: experimental native SurfaceView row encoding enabled"
        lines += "FULL-FRAME LIMIT: bounded by the physical display refresh rate"
        lines += "USB LED DAC: 48,000 optical samples/s when an external LED driver route is connected"
        return lines.joinToString("\n")
    }
}

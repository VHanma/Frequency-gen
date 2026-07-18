package com.vhanma.lightcode.photophone

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

internal class TorchLoopEngine(
    context: Context,
    private val program: OpticalSignal,
    private val updateRateHz: Int,
    private val modulationGain: Float,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraId: String
    private val maximumStrength: Int
    private val worker = HandlerThread("PhotophoneLoopTorch")
    private lateinit var handler: Handler

    @Volatile
    private var running = false
    private var startedAtNanos = 0L
    private var startedAtUptimeMs = 0L
    private var tick = 0L

    init {
        var selectedId: String? = null
        var selectedMaximum = 1
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                selectedId = id
                selectedMaximum = if (Build.VERSION.SDK_INT >= 33) {
                    characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                } else {
                    1
                }
                break
            }
        }
        cameraId = requireNotNull(selectedId) {
            "This phone reports no controllable camera flashlight."
        }
        maximumStrength = selectedMaximum.coerceAtLeast(1)
    }

    fun start() {
        if (running) return
        worker.start()
        handler = Handler(worker.looper)
        running = true
        startedAtNanos = System.nanoTime()
        startedAtUptimeMs = SystemClock.uptimeMillis()
        tick = 0L
        onStatus(
            "Torch loop active at ${updateRateHz.coerceIn(1, 40)} updates/s with " +
                "$maximumStrength strength level${if (maximumStrength == 1) "" else "s"}. Press Stop to end."
        )
        handler.post(step)
    }

    fun stop() {
        if (!running) return
        running = false
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        runCatching { cameraManager.setTorchMode(cameraId, false) }
        worker.quitSafely()
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            val rate = updateRateHz.coerceIn(1, 40)
            val elapsed = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0
            if (!program.loop && elapsed >= program.durationSeconds) {
                stop()
                onFinished()
                return
            }
            val sample = program.sampleAt(elapsed)
            val light = (0.5f + 0.5f * sample * modulationGain.coerceIn(0.05f, 2f))
                .coerceIn(0f, 1f)

            val result = runCatching {
                if (Build.VERSION.SDK_INT >= 33 && maximumStrength > 1 && light > 0.015f) {
                    val level = (1f + light * (maximumStrength - 1).toFloat())
                        .toInt()
                        .coerceIn(1, maximumStrength)
                    cameraManager.turnOnTorchWithStrengthLevel(cameraId, level)
                } else {
                    cameraManager.setTorchMode(cameraId, light >= 0.5f)
                }
            }

            if (result.isFailure) {
                onStatus("Torchlight stopped: ${result.exceptionOrNull()?.message}")
                stop()
                onFinished()
                return
            }

            tick++
            val nextAt = startedAtUptimeMs + tick * 1000L / rate.toLong()
            handler.postAtTime(this, nextAt)
        }
    }
}

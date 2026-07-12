package com.vhanma.lightcode

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import kotlin.concurrent.thread
import kotlin.math.floor

internal class TorchEngine(
    context: Context,
    private val program: OpticalProgram,
    private val requestedRateHz: Int,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraId: String
    private val maxStrength: Int
    private val handlerThread = HandlerThread("LightCodeTorch")
    private lateinit var handler: Handler
    @Volatile private var running = false
    private var startedAtNanos = 0L

    init {
        var foundId: String? = null
        var strength = 1
        for (id in cameraManager.cameraIdList) {
            val c = cameraManager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                foundId = id
                if (Build.VERSION.SDK_INT >= 33) {
                    strength = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                }
                break
            }
        }
        cameraId = requireNotNull(foundId) { "This phone reports no controllable camera flash." }
        maxStrength = strength.coerceAtLeast(1)
    }

    fun start() {
        if (running) return
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        running = true
        startedAtNanos = System.nanoTime()
        onStatus("Torch optical output: ${requestedRateHz.coerceIn(1, 40)} updates/s, $maxStrength strength levels")
        handler.post(step)
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            val rate = requestedRateHz.coerceIn(1, 40)
            val elapsed = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0
            if (!program.loop && elapsed >= program.durationSeconds) {
                stop()
                onFinished()
                return
            }

            val sample = sampleAt(elapsed)
            val light = ((sample + 1f) * 0.5f).coerceIn(0f, 1f)
            runCatching {
                if (Build.VERSION.SDK_INT >= 33 && maxStrength > 1 && light > 0.03f) {
                    val level = (1 + light * (maxStrength - 1)).toInt().coerceIn(1, maxStrength)
                    cameraManager.turnOnTorchWithStrengthLevel(cameraId, level)
                } else {
                    cameraManager.setTorchMode(cameraId, light >= 0.5f)
                }
            }.onFailure {
                onStatus("Torch update failed: ${it.message}")
                stop()
                onFinished()
                return
            }
            handler.postDelayed(this, (1000L / rate.toLong()).coerceAtLeast(1L))
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { cameraManager.setTorchMode(cameraId, false) }
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
    }

    private fun sampleAt(seconds: Double): Float {
        if (program.samples.isEmpty()) return 0f
        var t = seconds
        val duration = program.durationSeconds
        if (program.loop && duration > 0.0) t %= duration
        if (t < 0.0 || t >= duration) return 0f
        val pos = t * program.sampleRate
        val i = floor(pos).toInt().coerceIn(0, program.samples.lastIndex)
        return program.samples[i]
    }
}

internal class UsbLedEngine(
    context: Context,
    private val program: OpticalProgram,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    @Volatile private var running = false
    private var audioTrack: AudioTrack? = null

    fun availableRoutes(): List<AudioDeviceInfo> = audioManager
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.isExternalOpticalRoute() }

    fun start() {
        if (running) return
        val route = availableRoutes().firstOrNull()
            ?: error("Connect a USB-C audio adapter, USB audio device, or wired output first.")

        val sampleRate = 48_000
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4_096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * 4)
            .build()

        check(track.setPreferredDevice(route)) { "Android refused the selected external audio route." }
        audioTrack = track
        running = true
        onStatus("USB LED DAC active at 48 kHz through ${route.productName ?: "external route"}")

        thread(name = "LightCodeUsbDac") {
            try {
                track.play()
                val block = FloatArray(2_048)
                var outputIndex = 0L
                val outputRate = sampleRate.toDouble()
                while (running) {
                    var count = 0
                    while (count < block.size && running) {
                        val seconds = outputIndex.toDouble() / outputRate
                        if (!program.loop && seconds >= program.durationSeconds) {
                            running = false
                            break
                        }
                        block[count++] = sampleAt(seconds)
                        outputIndex++
                    }
                    if (count > 0) {
                        val written = track.write(block, 0, count, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) error("AudioTrack write failed: $written")
                    }
                }
            } catch (t: Throwable) {
                onStatus("USB LED DAC stopped: ${t.message}")
            } finally {
                runCatching { track.stop() }
                track.release()
                audioTrack = null
                onFinished()
            }
        }
    }

    fun stop() {
        running = false
    }

    private fun sampleAt(seconds: Double): Float {
        if (program.samples.isEmpty()) return 0f
        var t = seconds
        val duration = program.durationSeconds
        if (program.loop && duration > 0.0) t %= duration
        if (t < 0.0 || t >= duration) return 0f

        val position = t * program.sampleRate.toDouble()
        val index = floor(position).toInt().coerceIn(0, program.samples.lastIndex)
        val next = (index + 1).coerceAtMost(program.samples.lastIndex)
        val fraction = (position - index).toFloat()
        return program.samples[index] * (1f - fraction) + program.samples[next] * fraction
    }

    private fun AudioDeviceInfo.isExternalOpticalRoute(): Boolean = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> true
        else -> false
    }
}

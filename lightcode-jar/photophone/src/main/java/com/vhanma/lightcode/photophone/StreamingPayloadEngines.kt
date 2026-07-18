package com.vhanma.lightcode.photophone

import android.app.PendingIntent
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.concurrent.thread

internal class StreamingPayloadTorchEngine(
    context: Context,
    private val encoder: UniversalWaveEncoder,
    private val updateRateHz: Int,
    private val modulationGain: Float,
    private val onProgress: (Long, Long) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraId: String
    private val maximumStrength: Int
    private val worker = HandlerThread("UniversalPayloadTorch")
    private lateinit var handler: Handler
    @Volatile private var running = false
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
                } else 1
                break
            }
        }
        cameraId = requireNotNull(selectedId) { "This phone reports no controllable camera flashlight." }
        maximumStrength = selectedMaximum.coerceAtLeast(1)
    }

    fun start() {
        if (running) return
        worker.start()
        handler = Handler(worker.looper)
        running = true
        startedAtUptimeMs = SystemClock.uptimeMillis()
        tick = 0L
        onStatus(
            "Streaming payload through Slow OOK torch at ${updateRateHz.coerceIn(1, 40)} updates/s. " +
                "This is the most robust torch carrier, but it is intentionally slow."
        )
        handler.post(step)
    }

    fun stop() {
        if (!running) return
        running = false
        encoder.close()
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        runCatching { cameraManager.setTorchMode(cameraId, false) }
        worker.quitSafely()
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            val sample = encoder.nextSample()
            if (sample == null) {
                stop()
                onFinished()
                return
            }
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
                onStatus("Torch payload stopped: ${result.exceptionOrNull()?.message}")
                stop()
                onFinished()
                return
            }

            tick++
            if (tick % 20L == 0L) onProgress(encoder.payloadBytesRead, encoder.passCount)
            val rate = updateRateHz.coerceIn(1, 40)
            val nextAt = startedAtUptimeMs + tick * 1000L / rate.toLong()
            handler.postAtTime(this, nextAt)
        }
    }
}

internal class StreamingUsbPayloadEngine(
    context: Context,
    private val encoder: UniversalWaveEncoder,
    private val onProgress: (Long, Long) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    @Volatile private var running = false

    fun findTarget(): UsbLightTarget? {
        val candidates = mutableListOf<Pair<Int, UsbLightTarget>>()
        for (device in usbManager.deviceList.values) {
            var control: UsbInterface? = null
            for (interfaceIndex in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(interfaceIndex)
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_COMM) control = usbInterface
            }
            for (interfaceIndex in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(interfaceIndex)
                for (endpointIndex in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    if (
                        endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        val score = when (usbInterface.interfaceClass) {
                            UsbConstants.USB_CLASS_CDC_DATA -> 3
                            UsbConstants.USB_CLASS_VENDOR_SPEC -> 2
                            else -> 1
                        }
                        candidates += score to UsbLightTarget(device, usbInterface, endpoint, control)
                    }
                }
            }
        }
        return candidates.maxByOrNull { it.first }?.second
    }

    fun hasPermission(target: UsbLightTarget): Boolean = usbManager.hasPermission(target.device)

    fun requestPermission(target: UsbLightTarget, intent: PendingIntent) {
        usbManager.requestPermission(target.device, intent)
    }

    fun start(target: UsbLightTarget) {
        check(!running) { "USB payload transmission is already active." }
        check(usbManager.hasPermission(target.device)) { "USB permission has not been granted." }
        val connection = usbManager.openDevice(target.device)
            ?: error("Android could not open the USB light controller.")
        check(connection.claimInterface(target.dataInterface, true)) {
            connection.close()
            "Android could not claim the USB bulk interface."
        }
        val control = target.controlInterface
        if (control != null && control.id != target.dataInterface.id) {
            runCatching {
                connection.claimInterface(control, true)
                configureCdc(connection, control)
            }
        }

        running = true
        onStatus("Unlimited payload streaming at 48,000 waveform samples/s through ${target.description}.")
        thread(name = "UniversalPayloadUsb") {
            try {
                sendConfig(connection, target.endpointOut)
                streamSamples(connection, target.endpointOut)
            } catch (error: Throwable) {
                onStatus("USB payload stopped: ${error.message}")
            } finally {
                running = false
                encoder.close()
                runCatching { connection.releaseInterface(target.dataInterface) }
                if (control != null && control.id != target.dataInterface.id) {
                    runCatching { connection.releaseInterface(control) }
                }
                connection.close()
                onFinished()
            }
        }
    }

    fun stop() {
        running = false
    }

    private fun configureCdc(connection: UsbDeviceConnection, control: UsbInterface) {
        val lineCoding = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(115_200)
            .put(0)
            .put(0)
            .put(8)
            .array()
        connection.controlTransfer(0x21, 0x20, 0, control.id, lineCoding, lineCoding.size, 1_000)
        connection.controlTransfer(0x21, 0x22, 0x03, control.id, null, 0, 1_000)
    }

    private fun sendConfig(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val packet = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        packet.put(byteArrayOf('L'.code.toByte(), 'P'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte()))
        packet.putInt(48_000)
        packet.putInt(1)
        packet.putInt(16)
        packet.putInt(2_048)
        packet.putInt(250_000)
        transferAll(connection, endpoint, packet.array())
    }

    private fun streamSamples(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val samplesPerPacket = 480
        var sequence = 0
        var progressTicker = 0
        while (running) {
            val packet = ByteBuffer.allocate(24 + samplesPerPacket * 2).order(ByteOrder.LITTLE_ENDIAN)
            packet.put(byteArrayOf('L'.code.toByte(), 'P'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte()))
            packet.putInt(sequence)
            packet.putInt(48_000)
            packet.putShort(samplesPerPacket.toShort())
            packet.putShort(0)
            val crcPosition = packet.position()
            packet.putInt(0)
            packet.putInt(0)
            val payloadStart = packet.position()

            var samplesWritten = 0
            repeat(samplesPerPacket) {
                val sample = encoder.nextSample()
                if (sample == null) {
                    running = false
                    packet.putShort(0)
                } else {
                    packet.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    samplesWritten++
                }
            }
            if (samplesWritten == 0) break

            val bytes = packet.array()
            val crc = CRC32().apply {
                update(bytes, 4, 12)
                update(bytes, payloadStart, samplesPerPacket * 2)
            }.value.toInt()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(crcPosition, crc)
            transferAll(connection, endpoint, bytes)
            sequence++
            progressTicker++
            if (progressTicker % 50 == 0) onProgress(encoder.payloadBytesRead, encoder.passCount)
        }
    }

    private fun transferAll(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        bytes: ByteArray
    ) {
        var offset = 0
        val maxChunk = endpoint.maxPacketSize.coerceAtLeast(64) * 8
        while (offset < bytes.size && running) {
            val count = minOf(maxChunk, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + count)
            val sent = connection.bulkTransfer(endpoint, chunk, chunk.size, 1_500)
            if (sent <= 0) error("USB bulk transfer failed at byte $offset.")
            offset += sent
        }
    }
}

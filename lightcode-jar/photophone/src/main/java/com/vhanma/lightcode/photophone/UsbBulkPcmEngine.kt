package com.vhanma.lightcode.photophone

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.concurrent.thread

internal data class UsbLightTarget(
    val device: UsbDevice,
    val dataInterface: UsbInterface,
    val endpointOut: UsbEndpoint,
    val controlInterface: UsbInterface?
) {
    val description: String
        get() = buildString {
            append(device.productName ?: "USB device")
            append(" · VID ")
            append(device.vendorId)
            append(" PID ")
            append(device.productId)
            append(" · endpoint 0x")
            append(endpointOut.address.toString(16))
        }
}

internal class UsbBulkPcmEngine(
    context: Context,
    private val program: OpticalSignal,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    @Volatile private var running = false
    private var connection: UsbDeviceConnection? = null

    fun findTarget(): UsbLightTarget? {
        val candidates = mutableListOf<Pair<Int, UsbLightTarget>>()
        for (device in usbManager.deviceList.values) {
            var control: UsbInterface? = null
            for (interfaceIndex in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(interfaceIndex)
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                    control = usbInterface
                }
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

    fun requestPermission(target: UsbLightTarget, permissionIntent: PendingIntent) {
        usbManager.requestPermission(target.device, permissionIntent)
    }

    fun start(target: UsbLightTarget) {
        check(!running) { "USB light output is already running." }
        check(usbManager.hasPermission(target.device)) { "USB permission has not been granted yet." }

        val deviceConnection = usbManager.openDevice(target.device)
            ?: error("Android could not open the USB light controller.")
        check(deviceConnection.claimInterface(target.dataInterface, true)) {
            deviceConnection.close()
            "Android could not claim the USB bulk interface."
        }

        val control = target.controlInterface
        if (control != null && control.id != target.dataInterface.id) {
            runCatching {
                deviceConnection.claimInterface(control, true)
                configureCdc(deviceConnection, control)
            }
        }

        connection = deviceConnection
        running = true
        onStatus("USB bulk photophone active: ${target.description}. Media volume is bypassed.")

        thread(name = "PhotophoneUsbBulk") {
            try {
                sendConfig(deviceConnection, target.endpointOut)
                streamPcm(deviceConnection, target.endpointOut)
            } catch (error: Throwable) {
                onStatus("USB bulk photophone stopped: ${error.message}")
            } finally {
                running = false
                runCatching { deviceConnection.releaseInterface(target.dataInterface) }
                if (control != null && control.id != target.dataInterface.id) {
                    runCatching { deviceConnection.releaseInterface(control) }
                }
                deviceConnection.close()
                connection = null
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
        connection.controlTransfer(
            0x21,
            0x20,
            0,
            control.id,
            lineCoding,
            lineCoding.size,
            1_000
        )
        connection.controlTransfer(
            0x21,
            0x22,
            0x03,
            control.id,
            null,
            0,
            1_000
        )
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

    private fun streamPcm(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val outputRate = 48_000
        val samplesPerPacket = 480
        var sequence = 0
        var outputSampleIndex = 0L

        while (running) {
            val packet = ByteBuffer
                .allocate(24 + samplesPerPacket * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            packet.put(byteArrayOf('L'.code.toByte(), 'P'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte()))
            packet.putInt(sequence)
            packet.putInt(outputRate)
            packet.putShort(samplesPerPacket.toShort())
            packet.putShort(0)
            val crcPosition = packet.position()
            packet.putInt(0)
            packet.putInt(0)

            val payloadStart = packet.position()
            repeat(samplesPerPacket) {
                val seconds = outputSampleIndex.toDouble() / outputRate.toDouble()
                if (!program.loop && seconds >= program.durationSeconds) {
                    running = false
                }
                val sample = if (running || program.loop) program.sampleAt(seconds) else 0f
                val pcm = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                packet.putShort(pcm)
                outputSampleIndex++
            }

            val bytes = packet.array()
            val crc = CRC32().apply {
                update(bytes, 4, 12)
                update(bytes, payloadStart, samplesPerPacket * 2)
            }.value.toInt()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(crcPosition, crc)
            transferAll(connection, endpoint, bytes)
            sequence++
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

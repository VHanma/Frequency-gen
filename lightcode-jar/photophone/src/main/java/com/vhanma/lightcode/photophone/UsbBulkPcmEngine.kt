package com.vhanma.lightcode.photophone

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
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

internal data class UsbEfficiencySnapshot(
    val packetsCompleted: Long,
    val samplesQueued: Long,
    val queueDepth: Int,
    val throughputBytesPerSecond: Long,
    val sequence: Int,
    val asynchronous: Boolean
)

internal class UsbBulkPcmEngine(
    context: Context,
    private val program: OpticalProgram,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit,
    private val onEfficiency: (UsbEfficiencySnapshot) -> Unit = {}
) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    @Volatile private var running = false
    private var connection: UsbDeviceConnection? = null
    private val activeRequests = mutableListOf<UsbRequest>()

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
        onStatus("USB asynchronous photophone active: ${target.description}. Two packet buffers are pipelined and media volume is bypassed.")

        thread(name = "PhotophoneUsbAsync") {
            try {
                sendConfig(deviceConnection, target.endpointOut)
                streamPcmAsynchronously(deviceConnection, target.endpointOut)
            } catch (error: Throwable) {
                if (running) onStatus("USB photophone stopped: ${error.message}")
            } finally {
                running = false
                synchronized(activeRequests) {
                    activeRequests.forEach { runCatching { it.close() } }
                    activeRequests.clear()
                }
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
        synchronized(activeRequests) {
            activeRequests.forEach { runCatching { it.cancel() } }
        }
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
        val bytes = packet.array()
        val sent = connection.bulkTransfer(endpoint, bytes, bytes.size, 1_500)
        if (sent != bytes.size) error("USB configuration packet was incomplete: $sent/${bytes.size} bytes.")
    }

    private data class RequestSlot(
        val request: UsbRequest,
        val buffer: ByteBuffer,
        val slot: Int
    )

    private data class FillResult(
        val nextSampleIndex: Long,
        val sourceEnded: Boolean,
        val realSamples: Int
    )

    private fun streamPcmAsynchronously(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint
    ) {
        val outputRate = 48_000
        val samplesPerPacket = 480
        val packetBytes = 24 + samplesPerPacket * 2
        val slots = Array(2) { slotIndex ->
            val request = UsbRequest()
            check(request.initialize(connection, endpoint)) { "USB request $slotIndex could not initialize." }
            val slot = RequestSlot(
                request = request,
                buffer = ByteBuffer.allocateDirect(packetBytes).order(ByteOrder.LITTLE_ENDIAN),
                slot = slotIndex
            )
            request.clientData = slot
            slot
        }
        synchronized(activeRequests) {
            activeRequests.clear()
            activeRequests.addAll(slots.map { it.request })
        }

        var sequence = 0
        var sampleIndex = 0L
        var sourceEnded = false
        var pending = 0
        var packetsCompleted = 0L
        var samplesQueued = 0L
        val startedNanos = System.nanoTime()

        for (slot in slots) {
            if (sourceEnded || !running) break
            val result = fillPacket(slot.buffer, sequence, sampleIndex, outputRate, samplesPerPacket)
            sampleIndex = result.nextSampleIndex
            sourceEnded = result.sourceEnded
            samplesQueued += result.realSamples
            check(slot.request.queue(slot.buffer)) { "USB request ${slot.slot} could not be queued." }
            pending++
            sequence++
        }

        while (running && pending > 0) {
            val completed = connection.requestWait()
                ?: error("USB request completion returned null.")
            pending--
            packetsCompleted++
            val slot = completed.clientData as? RequestSlot
                ?: error("USB request lost its packet slot.")

            if (!sourceEnded && running) {
                val result = fillPacket(slot.buffer, sequence, sampleIndex, outputRate, samplesPerPacket)
                sampleIndex = result.nextSampleIndex
                sourceEnded = result.sourceEnded
                samplesQueued += result.realSamples
                check(completed.queue(slot.buffer)) { "USB request ${slot.slot} could not be re-queued." }
                pending++
                sequence++
            }

            if (packetsCompleted % 25L == 0L || pending == 0) {
                val elapsedNanos = (System.nanoTime() - startedNanos).coerceAtLeast(1L)
                val bytesPerSecond = packetsCompleted * packetBytes.toLong() * 1_000_000_000L / elapsedNanos
                onEfficiency(
                    UsbEfficiencySnapshot(
                        packetsCompleted = packetsCompleted,
                        samplesQueued = samplesQueued,
                        queueDepth = pending,
                        throughputBytesPerSecond = bytesPerSecond,
                        sequence = sequence,
                        asynchronous = true
                    )
                )
            }
        }
    }

    private fun fillPacket(
        buffer: ByteBuffer,
        sequence: Int,
        startSampleIndex: Long,
        outputRate: Int,
        samplesPerPacket: Int
    ): FillResult {
        buffer.clear()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('L'.code.toByte())
        buffer.put('P'.code.toByte())
        buffer.put('P'.code.toByte())
        buffer.put('1'.code.toByte())
        buffer.putInt(sequence)
        buffer.putInt(outputRate)
        buffer.putShort(samplesPerPacket.toShort())
        buffer.putShort(0)
        val crcPosition = buffer.position()
        buffer.putInt(0)
        buffer.putInt(0)

        val crc = CRC32()
        updateCrcIntLe(crc, sequence)
        updateCrcIntLe(crc, outputRate)
        updateCrcShortLe(crc, samplesPerPacket)
        updateCrcShortLe(crc, 0)

        var sampleIndex = startSampleIndex
        var sourceEnded = false
        var realSamples = 0
        repeat(samplesPerPacket) {
            val seconds = sampleIndex.toDouble() / outputRate.toDouble()
            val hasSource = program.loop || seconds < program.durationSeconds
            val pcm = if (hasSource) {
                realSamples++
                SignalCore.floatToPcm(SignalCore.sampleAt(program, seconds))
            } else {
                sourceEnded = true
                0
            }
            buffer.putShort(pcm)
            val unsigned = pcm.toInt() and 0xFFFF
            crc.update(unsigned and 0xFF)
            crc.update((unsigned ushr 8) and 0xFF)
            sampleIndex++
        }

        buffer.putInt(crcPosition, crc.value.toInt())
        buffer.flip()
        return FillResult(sampleIndex, sourceEnded, realSamples)
    }

    private fun updateCrcIntLe(crc: CRC32, value: Int) {
        crc.update(value and 0xFF)
        crc.update((value ushr 8) and 0xFF)
        crc.update((value ushr 16) and 0xFF)
        crc.update((value ushr 24) and 0xFF)
    }

    private fun updateCrcShortLe(crc: CRC32, value: Int) {
        crc.update(value and 0xFF)
        crc.update((value ushr 8) and 0xFF)
    }
}

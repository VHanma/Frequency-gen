package com.vhanma.lightcode.photophone

import android.app.PendingIntent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.concurrent.thread

internal class SequentialResampler(private val source: SequentialOpticalStream) {
    private var phase = 0.0
    private var current = 0f
    private var initialized = false

    fun next(outputRate: Double): Float {
        if (!initialized) {
            current = source.nextSample()
            initialized = true
        }
        phase += source.sampleRate.toDouble() / outputRate.coerceAtLeast(1.0)
        while (phase >= 1.0 && !source.finished) {
            current = source.nextSample()
            phase -= 1.0
        }
        return current
    }
}

internal class StreamingLightView(
    context: Context,
    private val stream: SequentialOpticalStream,
    private val fullFrame: Boolean,
    private val modulationGain: Float,
    private val reverseRows: Boolean,
    private val colorMode: LightColorMode,
    private val requestedRows: Int,
    private val geometry: BeamGeometry,
    private val onProgress: (Long, Long, Long) -> Unit,
    private val onFinished: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val resampler = SequentialResampler(stream)
    private var running = false
    private var rows = 384
    private var frameCounter = 0
    private var lastTap = 0L

    init {
        holder.addCallback(this)
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        requestFastestRefresh(holder.surface)
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        rows = if (requestedRows > 0) requestedRows.coerceIn(64, 768)
        else (height / 4).coerceIn(192, 640)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = stop()

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        stream.close()
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { holder.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !holder.surface.isValid) return
        val refresh = display?.refreshRate?.toDouble()?.coerceAtLeast(30.0) ?: 60.0
        val canvas = runCatching { holder.lockCanvas() }.getOrNull()
        if (canvas != null) {
            try {
                if (fullFrame) drawFullFrame(canvas, refresh) else drawRows(canvas, refresh)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
        frameCounter++
        if (frameCounter % 12 == 0) {
            onProgress(stream.payloadProgressBytes, stream.payloadSizeBytes, stream.completedPasses)
        }
        if (stream.finished) {
            stop()
            onFinished()
        } else if (running) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun drawFullFrame(canvas: Canvas, refresh: Double) {
        val sample = resampler.next(refresh)
        val brightness = (0.5f + 0.49f * sample * modulationGain).coerceIn(0.01f, 1f)
        canvas.drawColor(Color.BLACK)
        paint.color = opticalColor(brightness)
        drawGeometry(canvas, 0f, canvas.height.toFloat())
    }

    private fun drawRows(canvas: Canvas, refresh: Double) {
        canvas.drawColor(Color.BLACK)
        val rowRate = refresh * rows.toDouble()
        val height = canvas.height.toFloat() / rows.toFloat()
        for (scanRow in 0 until rows) {
            val drawRow = if (reverseRows) rows - 1 - scanRow else scanRow
            val sample = resampler.next(rowRate)
            val brightness = (0.5f + 0.49f * sample * modulationGain).coerceIn(0.01f, 1f)
            paint.color = opticalColor(brightness)
            val top = drawRow * height
            drawGeometry(canvas, top, top + height + 1f)
        }
    }

    private fun drawGeometry(canvas: Canvas, top: Float, bottom: Float) {
        val width = canvas.width.toFloat()
        when (geometry) {
            BeamGeometry.FULL_APERTURE -> canvas.drawRect(0f, top, width, bottom, paint)
            BeamGeometry.HOLLOW_BEAM -> {
                canvas.drawRect(0f, top, width * 0.34f, bottom, paint)
                canvas.drawRect(width * 0.66f, top, width, bottom, paint)
            }
            BeamGeometry.CENTRAL_SHAFT -> canvas.drawRect(width * 0.24f, top, width * 0.76f, bottom, paint)
            BeamGeometry.TWIN_BEAM -> {
                canvas.drawRect(width * 0.08f, top, width * 0.38f, bottom, paint)
                canvas.drawRect(width * 0.62f, top, width * 0.92f, bottom, paint)
            }
        }
    }

    private fun opticalColor(value: Float): Int {
        fun channel(scale: Float) = (255f * value * scale).toInt().coerceIn(0, 255)
        return when (colorMode) {
            LightColorMode.WHITE -> Color.rgb(channel(1f), channel(1f), channel(1f))
            LightColorMode.RED -> Color.rgb(channel(1f), 0, 0)
            LightColorMode.GREEN -> Color.rgb(0, channel(1f), 0)
            LightColorMode.BLUE -> Color.rgb(0, 0, channel(1f))
            LightColorMode.AMBER -> Color.rgb(channel(1f), channel(0.55f), 0)
            LightColorMode.CYAN -> Color.rgb(0, channel(1f), channel(1f))
            LightColorMode.MAGENTA -> Color.rgb(channel(1f), 0, channel(1f))
        }
    }

    private fun requestFastestRefresh(surface: Surface) {
        val fastest = display?.supportedModes?.maxByOrNull { it.refreshRate }?.refreshRate
            ?: display?.refreshRate ?: 60f
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(fastest, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS)
            } else if (Build.VERSION.SDK_INT >= 30) {
                surface.setFrameRate(fastest, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTap < 350L) {
                stop()
                onFinished()
            }
            lastTap = now
        }
        return true
    }
}

internal class StreamingTorchEngine(
    context: Context,
    private val stream: SequentialOpticalStream,
    private val updateRateHz: Int,
    private val gain: Float,
    private val onProgress: (Long, Long, Long) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val cameraId: String
    private val maximumStrength: Int
    private val worker = HandlerThread("UniversalPayloadTorch")
    private lateinit var handler: Handler
    private val resampler = SequentialResampler(stream)
    private var ticks = 0L
    @Volatile private var running = false

    init {
        var found: String? = null
        var strength = 1
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                found = id
                if (Build.VERSION.SDK_INT >= 33) {
                    strength = characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                }
                break
            }
        }
        cameraId = requireNotNull(found) { "No controllable phone torch was found." }
        maximumStrength = strength.coerceAtLeast(1)
    }

    fun start() {
        if (running) return
        worker.start()
        handler = Handler(worker.looper)
        running = true
        onStatus("Streaming payload through torch at ${updateRateHz.coerceIn(1, 40)} updates/s.")
        handler.post(step)
    }

    fun stop() {
        if (!running) return
        running = false
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        runCatching { manager.setTorchMode(cameraId, false) }
        stream.close()
        worker.quitSafely()
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            val rate = updateRateHz.coerceIn(1, 40)
            val sample = resampler.next(rate.toDouble())
            val light = (0.5f + 0.5f * sample * gain).coerceIn(0f, 1f)
            runCatching {
                if (Build.VERSION.SDK_INT >= 33 && maximumStrength > 1 && light > 0.015f) {
                    val level = (1 + light * (maximumStrength - 1)).toInt().coerceIn(1, maximumStrength)
                    manager.turnOnTorchWithStrengthLevel(cameraId, level)
                } else {
                    manager.setTorchMode(cameraId, light >= 0.5f)
                }
            }.onFailure {
                onStatus("Torch stream failed: ${it.message}")
                stop()
                onFinished()
                return
            }
            ticks++
            if (ticks % 20L == 0L) onProgress(stream.payloadProgressBytes, stream.payloadSizeBytes, stream.completedPasses)
            if (stream.finished) {
                stop()
                onFinished()
            } else {
                handler.postDelayed(this, (1000L / rate).coerceAtLeast(1L))
            }
        }
    }
}

internal data class StreamingUsbTarget(
    val device: UsbDevice,
    val usbInterface: UsbInterface,
    val endpoint: UsbEndpoint
) {
    val description: String
        get() = "${device.productName ?: "USB device"} · VID ${device.vendorId} PID ${device.productId}"
}

internal class StreamingUsbEngine(
    context: Context,
    private val stream: SequentialOpticalStream,
    private val onProgress: (Long, Long, Long) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val resampler = SequentialResampler(stream)
    @Volatile private var running = false
    private var connection: UsbDeviceConnection? = null

    fun findTarget(): StreamingUsbTarget? {
        for (device in manager.deviceList.values) {
            for (interfaceIndex in 0 until device.interfaceCount) {
                val intf = device.getInterface(interfaceIndex)
                for (endpointIndex in 0 until intf.endpointCount) {
                    val endpoint = intf.getEndpoint(endpointIndex)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        return StreamingUsbTarget(device, intf, endpoint)
                    }
                }
            }
        }
        return null
    }

    fun hasPermission(target: StreamingUsbTarget) = manager.hasPermission(target.device)
    fun requestPermission(target: StreamingUsbTarget, intent: PendingIntent) = manager.requestPermission(target.device, intent)

    fun start(target: StreamingUsbTarget) {
        check(manager.hasPermission(target.device)) { "USB permission is missing." }
        val opened = manager.openDevice(target.device) ?: error("Unable to open the USB controller.")
        check(opened.claimInterface(target.usbInterface, true)) { "Unable to claim USB interface." }
        connection = opened
        running = true
        onStatus("Universal payload USB light stream active at 48 kHz.")
        thread(name = "UniversalPayloadUsb") {
            try {
                val outputRate = 48_000.0
                val samplesPerPacket = 480
                var sequence = 0
                while (running && !stream.finished) {
                    val packet = ByteBuffer.allocate(24 + samplesPerPacket * 2).order(ByteOrder.LITTLE_ENDIAN)
                    packet.put(byteArrayOf('L'.code.toByte(), 'P'.code.toByte(), 'P'.code.toByte(), '2'.code.toByte()))
                    packet.putInt(sequence)
                    packet.putInt(48_000)
                    packet.putShort(samplesPerPacket.toShort())
                    packet.putShort(0)
                    val crcPosition = packet.position()
                    packet.putInt(0)
                    packet.putInt(0)
                    val payloadStart = packet.position()
                    repeat(samplesPerPacket) {
                        val sample = resampler.next(outputRate)
                        packet.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    }
                    val bytes = packet.array()
                    val crc = CRC32().apply {
                        update(bytes, 4, 12)
                        update(bytes, payloadStart, samplesPerPacket * 2)
                    }.value.toInt()
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(crcPosition, crc)
                    transferAll(opened, target.endpoint, bytes)
                    sequence++
                    if (sequence % 20 == 0) onProgress(stream.payloadProgressBytes, stream.payloadSizeBytes, stream.completedPasses)
                }
            } finally {
                running = false
                stream.close()
                runCatching { opened.releaseInterface(target.usbInterface) }
                opened.close()
                connection = null
                onFinished()
            }
        }
    }

    fun stop() {
        running = false
        stream.close()
    }

    private fun transferAll(connection: UsbDeviceConnection, endpoint: UsbEndpoint, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size && running) {
            val count = minOf(bytes.size - offset, endpoint.maxPacketSize.coerceAtLeast(64) * 8)
            val chunk = bytes.copyOfRange(offset, offset + count)
            val sent = connection.bulkTransfer(endpoint, chunk, chunk.size, 1_500)
            if (sent <= 0) error("USB transfer failed at byte $offset.")
            offset += sent
        }
    }
}

package com.vhanma.lightcode.photophone

import android.app.PendingIntent
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

internal enum class UniversalCarrier(
    val displayName: String,
    val estimatedBitsPerSecond: Double,
    val description: String
) {
    FAST_4FSK(
        "Fast 4-FSK",
        2_400.0,
        "Two bits per tone symbol. Fastest universal carrier in this build."
    ),
    MANCHESTER_BPSK(
        "Manchester BPSK",
        600.0,
        "Every bit contains a transition, giving the receiver its own clock."
    ),
    PPM16(
        "16-position PPM",
        1_000.0,
        "Four bits are carried by the position of one pulse inside sixteen slots."
    ),
    CHIRP_CSK(
        "Up/down chirp CSK",
        80.0,
        "Rising and falling chirps represent the two bit states."
    ),
    DSSS_PRBS31(
        "PRBS-31 spread spectrum",
        96.77,
        "Each bit is spread across thirty-one deterministic chips for correlation recovery."
    ),
    GOLD31(
        "Gold-31 coded spread",
        96.77,
        "A Gold-like thirty-one-chip code or its inverse represents every bit."
    ),
    TORCH_SLOW_OOK(
        "Torch-safe slow OOK",
        5.0,
        "Five on/off bits per second for the limited phone-flashlight control path."
    )
}

internal data class UniversalPayloadDescriptor(
    val fileName: String,
    val payloadBytes: Long,
    val sha256: ByteArray,
    val openInput: () -> InputStream
)

internal data class UniversalPayloadManifest(
    val fileName: String,
    val payloadBytes: Long,
    val carrier: UniversalCarrier,
    val fecEnabled: Boolean,
    val sha256Hex: String,
    val estimatedChannelBytes: Long,
    val estimatedSecondsPerPass: Double,
    val loop: Boolean
) {
    fun asText(): String = buildString {
        append("UNIVERSAL LIGHT PAYLOAD V3\n")
        append("Filename: ").append(fileName).append('\n')
        append("Original bytes: ").append(payloadBytes).append('\n')
        append("Carrier: ").append(carrier.displayName).append('\n')
        append("Carrier description: ").append(carrier.description).append('\n')
        append("FEC: ").append(if (fecEnabled) "extended Hamming (8,4)" else "off").append('\n')
        append("SHA-256: ").append(sha256Hex).append('\n')
        append("Estimated channel bytes: ").append(estimatedChannelBytes).append('\n')
        append("Estimated seconds per pass: ").append("%.2f".format(estimatedSecondsPerPass)).append('\n')
        append("Loop: ").append(loop).append('\n')
        append("Framing: ULP3 + 64-bit length + 4096-byte blocks + block CRC32 + whole-file SHA-256\n")
        append("Payload size limit: none imposed by the encoder; practical limits are readable storage, run time, battery and receiver capacity.")
    }
}

internal object UniversalPayloadFactory {
    private const val BLOCK_BYTES = 4_096
    private const val FIXED_OVERHEAD = 192L
    private const val BLOCK_OVERHEAD = 24L

    fun manifest(
        descriptor: UniversalPayloadDescriptor,
        carrier: UniversalCarrier,
        fecEnabled: Boolean,
        loop: Boolean
    ): UniversalPayloadManifest {
        val blocks = if (descriptor.payloadBytes <= 0L) 0L
        else (descriptor.payloadBytes + BLOCK_BYTES - 1L) / BLOCK_BYTES.toLong()
        val framed = descriptor.payloadBytes + FIXED_OVERHEAD + blocks * BLOCK_OVERHEAD
        val channelBytes = if (fecEnabled) safeMultiply(framed, 2L) else framed
        val seconds = channelBytes.toDouble() * 8.0 / carrier.estimatedBitsPerSecond
        return UniversalPayloadManifest(
            fileName = descriptor.fileName,
            payloadBytes = descriptor.payloadBytes,
            carrier = carrier,
            fecEnabled = fecEnabled,
            sha256Hex = descriptor.sha256.joinToString("") { "%02x".format(it) },
            estimatedChannelBytes = channelBytes,
            estimatedSecondsPerPass = seconds,
            loop = loop
        )
    }

    fun waveSource(
        descriptor: UniversalPayloadDescriptor,
        carrier: UniversalCarrier,
        fecEnabled: Boolean,
        loop: Boolean,
        sampleRate: Int
    ): UniversalWaveStream {
        val packetFactory = {
            UniversalPacketByteStream(
                descriptor = descriptor,
                carrier = carrier,
                fecEnabled = fecEnabled
            )
        }
        return UniversalWaveStream(packetFactory, carrier, loop, sampleRate)
    }

    private fun safeMultiply(a: Long, b: Long): Long =
        if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
}

private class UniversalPacketByteStream(
    private val descriptor: UniversalPayloadDescriptor,
    private val carrier: UniversalCarrier,
    private val fecEnabled: Boolean
) : Closeable {
    private enum class Stage { HEADER, BLOCKS, FOOTER, DONE }

    private var stage = Stage.HEADER
    private var input: InputStream = descriptor.openInput()
    private var segment = header()
    private var segmentIndex = 0
    private var blockIndex = 0L
    private var payloadRead = 0L

    fun nextByte(): Int? {
        while (true) {
            if (segmentIndex < segment.size) return segment[segmentIndex++].toInt() and 0xFF
            when (stage) {
                Stage.HEADER -> {
                    stage = Stage.BLOCKS
                    segment = nextBlock() ?: footer()
                    segmentIndex = 0
                    if (segment.isNotEmpty()) continue
                }
                Stage.BLOCKS -> {
                    val next = nextBlock()
                    if (next == null) {
                        stage = Stage.FOOTER
                        segment = footer()
                    } else {
                        segment = next
                    }
                    segmentIndex = 0
                    continue
                }
                Stage.FOOTER -> {
                    stage = Stage.DONE
                    segment = ByteArray(0)
                    segmentIndex = 0
                }
                Stage.DONE -> return null
            }
        }
    }

    fun payloadFraction(): Double = when {
        descriptor.payloadBytes <= 0L -> if (stage == Stage.DONE) 1.0 else 0.0
        else -> (payloadRead.toDouble() / descriptor.payloadBytes.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun header(): ByteArray = ByteArrayOutputStream().apply {
        repeat(128) { write(0x55) }
        write("ULP3".toByteArray(Charsets.US_ASCII))
        write(3)
        write(carrier.ordinal)
        write(if (fecEnabled) 1 else 0)
        write(0)
        val name = descriptor.fileName.toByteArray(Charsets.UTF_8).take(1_024).toByteArray()
        write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(name.size.toShort()).array())
        write(name)
        write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(descriptor.payloadBytes).array())
        write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(4_096).array())
        write(descriptor.sha256)
        val headerBody = toByteArray()
        val crc = CRC32().apply { update(headerBody, 128, headerBody.size - 128) }.value.toInt()
        write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc).array())
    }.toByteArray()

    private fun nextBlock(): ByteArray? {
        val buffer = ByteArray(4_096)
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) continue
            count += read
        }
        if (count == 0) {
            input.close()
            return null
        }
        payloadRead += count.toLong()
        val payload = if (count == buffer.size) buffer else buffer.copyOf(count)
        val crc = CRC32().apply { update(payload) }.value.toInt()
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0xD5.toByte(), 0xAA.toByte(), 0x96.toByte(), 0x69.toByte()))
            write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockIndex).array())
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(count).array())
            write(payload)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc).array())
            blockIndex++
        }.toByteArray()
    }

    private fun footer(): ByteArray = ByteArrayOutputStream().apply {
        write("END3".toByteArray(Charsets.US_ASCII))
        write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(blockIndex).array())
        write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(payloadRead).array())
        write(descriptor.sha256)
    }.toByteArray()

    override fun close() {
        runCatching { input.close() }
    }
}

private class FecByteStream(
    private val packet: UniversalPacketByteStream,
    private val enabled: Boolean
) : Closeable {
    private var pending = -1

    fun nextByte(): Int? {
        if (pending >= 0) return pending.also { pending = -1 }
        val raw = packet.nextByte() ?: return null
        if (!enabled) return raw
        pending = hamming84(raw and 0x0F)
        return hamming84((raw ushr 4) and 0x0F)
    }

    fun payloadFraction(): Double = packet.payloadFraction()

    private fun hamming84(nibble: Int): Int {
        val d1 = (nibble ushr 3) and 1
        val d2 = (nibble ushr 2) and 1
        val d3 = (nibble ushr 1) and 1
        val d4 = nibble and 1
        val p1 = d1 xor d2 xor d4
        val p2 = d1 xor d3 xor d4
        val p3 = d2 xor d3 xor d4
        var code = (p1 shl 7) or (p2 shl 6) or (d1 shl 5) or
            (p3 shl 4) or (d2 shl 3) or (d3 shl 2) or (d4 shl 1)
        code = code or (Integer.bitCount(code) and 1)
        return code
    }

    override fun close() = packet.close()
}

private class PacketBitStream(
    private val bytes: FecByteStream
) : Closeable {
    private var current = 0
    private var bitsRemaining = 0

    fun nextBit(): Int? {
        if (bitsRemaining == 0) {
            current = bytes.nextByte() ?: return null
            bitsRemaining = 8
        }
        bitsRemaining--
        return (current ushr bitsRemaining) and 1
    }

    fun nextBits(count: Int): Int? {
        var value = 0
        repeat(count) {
            val bit = nextBit() ?: return null
            value = (value shl 1) or bit
        }
        return value
    }

    fun payloadFraction(): Double = bytes.payloadFraction()
    override fun close() = bytes.close()
}

internal class UniversalWaveStream(
    private val packetFactory: () -> UniversalPacketByteStream,
    val carrier: UniversalCarrier,
    private val loop: Boolean,
    val sampleRate: Int
) : Closeable {
    private var packet = newBits()
    private var finished = false
    private var phase = 0.0
    private var symbolSample = 0
    private var symbolValue = 0
    private var manchesterHalf = 0
    private var dsssChip = 0
    private var dsssBitSign = 1
    private var samplesGenerated = 0L
    private val prbs31 = intArrayOf(
        1, 1, 1, 1, 1, -1, 1, -1, 1, 1, -1, -1, 1, 1, 1, -1,
        -1, -1, 1, -1, -1, 1, 1, -1, 1, -1, -1, -1, -1, 1, -1
    )
    private val gold31 = intArrayOf(
        1, 1, 1, -1, 1, -1, -1, 1, -1, 1, 1, 1, -1, -1, 1, -1,
        1, 1, -1, 1, -1, -1, -1, 1, 1, -1, 1, 1, -1, -1, -1
    )

    fun nextSample(): Float? {
        if (finished) return null
        val sample = when (carrier) {
            UniversalCarrier.FAST_4FSK -> next4Fsk()
            UniversalCarrier.MANCHESTER_BPSK -> nextManchester()
            UniversalCarrier.PPM16 -> nextPpm()
            UniversalCarrier.CHIRP_CSK -> nextChirp()
            UniversalCarrier.DSSS_PRBS31 -> nextDsss(prbs31)
            UniversalCarrier.GOLD31 -> nextDsss(gold31)
            UniversalCarrier.TORCH_SLOW_OOK -> nextOok()
        }
        if (sample != null) samplesGenerated++
        return sample
    }

    fun elapsedSeconds(): Double = samplesGenerated.toDouble() / sampleRate.toDouble()
    fun payloadFraction(): Double = packet.payloadFraction()
    fun isFinished(): Boolean = finished

    private fun next4Fsk(): Float? {
        val symbolSamples = (sampleRate / 1_200).coerceAtLeast(1)
        if (symbolSample == 0) {
            symbolValue = nextBitsLooping(2) ?: return finish()
        }
        val frequencies = doubleArrayOf(900.0, 1_500.0, 2_100.0, 2_700.0)
        phase += 2.0 * PI * frequencies[symbolValue] / sampleRate.toDouble()
        val value = (0.94 * sin(phase)).toFloat()
        symbolSample++
        if (symbolSample >= symbolSamples) symbolSample = 0
        return value
    }

    private fun nextManchester(): Float? {
        val halfSamples = (sampleRate / (600 * 2)).coerceAtLeast(1)
        if (symbolSample == 0 && manchesterHalf == 0) {
            symbolValue = nextBitsLooping(1) ?: return finish()
        }
        val sign = if (symbolValue == 1) {
            if (manchesterHalf == 0) 1 else -1
        } else {
            if (manchesterHalf == 0) -1 else 1
        }
        phase += 2.0 * PI * 2_400.0 / sampleRate.toDouble()
        val value = (0.92 * sign * sin(phase)).toFloat()
        symbolSample++
        if (symbolSample >= halfSamples) {
            symbolSample = 0
            manchesterHalf++
            if (manchesterHalf >= 2) manchesterHalf = 0
        }
        return value
    }

    private fun nextPpm(): Float? {
        val slotSamples = (sampleRate / 4_000).coerceAtLeast(2)
        val symbolSamples = slotSamples * 16
        if (symbolSample == 0) {
            symbolValue = nextBitsLooping(4) ?: return finish()
        }
        val slot = symbolSample / slotSamples
        val within = symbolSample % slotSamples
        val pulse = slot == symbolValue && within < (slotSamples / 2).coerceAtLeast(1)
        symbolSample++
        if (symbolSample >= symbolSamples) symbolSample = 0
        return if (pulse) 0.98f else -0.98f
    }

    private fun nextChirp(): Float? {
        val bitSamples = (sampleRate / 80).coerceAtLeast(8)
        if (symbolSample == 0) {
            symbolValue = nextBitsLooping(1) ?: return finish()
            phase = 0.0
        }
        val fraction = symbolSample.toDouble() / bitSamples.toDouble()
        val start = 600.0
        val end = 5_400.0.coerceAtMost(sampleRate * 0.42)
        val frequency = if (symbolValue == 1) {
            start + (end - start) * fraction
        } else {
            end - (end - start) * fraction
        }
        phase += 2.0 * PI * frequency / sampleRate.toDouble()
        val value = (0.94 * sin(phase)).toFloat()
        symbolSample++
        if (symbolSample >= bitSamples) symbolSample = 0
        return value
    }

    private fun nextDsss(code: IntArray): Float? {
        val chipRate = 3_000
        val chipSamples = (sampleRate / chipRate).coerceAtLeast(1)
        if (symbolSample == 0 && dsssChip == 0) {
            val bit = nextBitsLooping(1) ?: return finish()
            dsssBitSign = if (bit == 1) 1 else -1
        }
        val sign = dsssBitSign * code[dsssChip]
        phase += 2.0 * PI * minOf(5_000.0, sampleRate * 0.40) / sampleRate.toDouble()
        val value = (0.90 * sign * sin(phase)).toFloat()
        symbolSample++
        if (symbolSample >= chipSamples) {
            symbolSample = 0
            dsssChip++
            if (dsssChip >= code.size) dsssChip = 0
        }
        return value
    }

    private fun nextOok(): Float? {
        val bitSamples = (sampleRate / 5).coerceAtLeast(1)
        if (symbolSample == 0) {
            symbolValue = nextBitsLooping(1) ?: return finish()
        }
        symbolSample++
        if (symbolSample >= bitSamples) symbolSample = 0
        return if (symbolValue == 1) 1f else -1f
    }

    private fun nextBitsLooping(count: Int): Int? {
        var value = packet.nextBits(count)
        if (value != null) return value
        if (!loop) return null
        packet.close()
        packet = newBits()
        phase = 0.0
        symbolSample = 0
        manchesterHalf = 0
        dsssChip = 0
        value = packet.nextBits(count)
        return value
    }

    private fun newBits(): PacketBitStream = PacketBitStream(
        FecByteStream(packetFactory(), packetFactoryFecEnabled())
    )

    private fun packetFactoryFecEnabled(): Boolean {
        val packet = packetFactory()
        packet.close()
        // The actual packet factory already embeds the FEC flag in the frame. The stream wrapper
        // receives the same setting through PacketFactoryHolder below.
        return PacketFactoryHolder.currentFec.get() ?: false
    }

    private fun finish(): Float? {
        finished = true
        return null
    }

    override fun close() = packet.close()
}

/** Thread-local bridge avoids capturing an extra public constructor argument in generated sources. */
private object PacketFactoryHolder {
    val currentFec = ThreadLocal<Boolean>()
}

internal class UniversalStreamFactory(
    private val descriptor: UniversalPayloadDescriptor,
    private val carrier: UniversalCarrier,
    private val fecEnabled: Boolean,
    private val loop: Boolean
) {
    fun create(sampleRate: Int): UniversalWaveStream {
        PacketFactoryHolder.currentFec.set(fecEnabled)
        return try {
            UniversalWaveStream(
                packetFactory = {
                    UniversalPacketByteStream(descriptor, carrier, fecEnabled)
                },
                carrier = carrier,
                loop = loop,
                sampleRate = sampleRate
            )
        } finally {
            PacketFactoryHolder.currentFec.remove()
        }
    }
}

private class UniversalResampler(
    private val source: UniversalWaveStream
) {
    private var current: Float? = null
    private var next: Float? = null
    private var phase = 0.0

    fun nextSample(outputRate: Double): Float? {
        if (current == null) {
            current = source.nextSample() ?: return null
            next = source.nextSample() ?: current
        }
        val a = current ?: return null
        val b = next ?: a
        val value = (a * (1.0 - phase) + b * phase).toFloat()
        phase += source.sampleRate.toDouble() / outputRate.coerceAtLeast(1.0)
        while (phase >= 1.0) {
            current = next
            next = source.nextSample()
            phase -= 1.0
            if (current == null) return null
        }
        return value
    }
}

internal class UniversalPayloadLightView(
    context: Context,
    private val source: UniversalWaveStream,
    private val wholeFrame: Boolean,
    private val modulationGain: Float,
    private val reverseRows: Boolean,
    private val colorMode: LightColorMode,
    private val requestedRows: Int,
    private val geometry: BeamGeometry,
    private val onProgress: (Double, Double) -> Unit,
    private val onFinished: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val resampler = UniversalResampler(source)
    private var running = false
    private var rows = 384
    private var lastTap = 0L
    private var frameCounter = 0

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
        rows = if (requestedRows > 0) requestedRows.coerceIn(96, 768)
        else minOf(640, maxOf(256, height / 4))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = stop()

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        source.close()
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { holder.surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !holder.surface.isValid) return
        val refresh = display?.refreshRate?.toDouble()?.coerceAtLeast(30.0) ?: 60.0
        val canvas = runCatching { holder.lockCanvas() }.getOrNull()
        var ended = false
        if (canvas != null) {
            try {
                canvas.drawColor(Color.BLACK)
                if (wholeFrame) {
                    val sample = resampler.nextSample(refresh)
                    if (sample == null) ended = true
                    else drawGeometry(canvas, brightness(sample), 0f, canvas.height.toFloat())
                } else {
                    val outputRate = refresh * rows.toDouble()
                    val bandHeight = canvas.height.toFloat() / rows.toFloat()
                    for (scanIndex in 0 until rows) {
                        val sample = resampler.nextSample(outputRate)
                        if (sample == null) {
                            ended = true
                            break
                        }
                        val row = if (reverseRows) rows - 1 - scanIndex else scanIndex
                        val top = row * bandHeight
                        drawGeometry(canvas, brightness(sample), top, top + bandHeight + 1f)
                    }
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
        if (ended) {
            running = false
            source.close()
            onFinished()
            return
        }
        frameCounter++
        if (frameCounter % 15 == 0) onProgress(source.elapsedSeconds(), source.payloadFraction())
        if (running) Choreographer.getInstance().postFrameCallback(this)
    }

    private fun brightness(sample: Float): Float =
        (0.50f + 0.49f * sample * modulationGain.coerceIn(0.05f, 2f)).coerceIn(0.005f, 0.995f)

    private fun drawGeometry(canvas: Canvas, brightness: Float, top: Float, bottom: Float) {
        paint.color = opticalColor(brightness)
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
        fun c(scale: Float): Int = (255f * value * scale).toInt().coerceIn(0, 255)
        return when (colorMode) {
            LightColorMode.WHITE -> Color.rgb(c(1f), c(1f), c(1f))
            LightColorMode.RED -> Color.rgb(c(1f), 0, 0)
            LightColorMode.GREEN -> Color.rgb(0, c(1f), 0)
            LightColorMode.BLUE -> Color.rgb(0, 0, c(1f))
            LightColorMode.AMBER -> Color.rgb(c(1f), c(0.55f), 0)
            LightColorMode.CYAN -> Color.rgb(0, c(1f), c(1f))
            LightColorMode.MAGENTA -> Color.rgb(c(1f), 0, c(1f))
        }
    }

    private fun requestFastestRefresh(surface: Surface) {
        val fastest = display?.supportedModes?.maxByOrNull { it.refreshRate }?.refreshRate
            ?: display?.refreshRate ?: 60f
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(
                    fastest,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
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

internal class UniversalPayloadTorchEngine(
    context: Context,
    private val source: UniversalWaveStream,
    private val updateRateHz: Int,
    private val modulationGain: Float,
    private val onStatus: (String) -> Unit,
    private val onFinished: () -> Unit
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraId: String
    private val maximumStrength: Int
    private val worker = HandlerThread("UniversalPayloadTorch")
    private lateinit var handler: Handler
    @Volatile private var running = false
    private var startedAt = 0L
    private var tick = 0L

    init {
        var id: String? = null
        var strength = 1
        for (candidate in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(candidate)
            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                id = candidate
                if (Build.VERSION.SDK_INT >= 33) {
                    strength = characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                }
                break
            }
        }
        cameraId = requireNotNull(id) { "This phone reports no controllable flashlight." }
        maximumStrength = strength.coerceAtLeast(1)
    }

    fun start() {
        if (running) return
        worker.start()
        handler = Handler(worker.looper)
        running = true
        startedAt = SystemClock.uptimeMillis()
        tick = 0L
        onStatus("Universal torch payload active at ${updateRateHz.coerceIn(1, 40)} updates/s.")
        handler.post(step)
    }

    fun stop() {
        running = false
        if (::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        runCatching { cameraManager.setTorchMode(cameraId, false) }
        source.close()
        worker.quitSafely()
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            val sample = source.nextSample()
            if (sample == null) {
                stop()
                onFinished()
                return
            }
            val light = (0.5f + 0.5f * sample * modulationGain.coerceIn(0.05f, 2f)).coerceIn(0f, 1f)
            val result = runCatching {
                if (Build.VERSION.SDK_INT >= 33 && maximumStrength > 1 && light > 0.015f) {
                    val level = (1f + light * (maximumStrength - 1)).toInt().coerceIn(1, maximumStrength)
                    cameraManager.turnOnTorchWithStrengthLevel(cameraId, level)
                } else {
                    cameraManager.setTorchMode(cameraId, light >= 0.5f)
                }
            }
            if (result.isFailure) {
                onStatus("Universal torch output stopped: ${result.exceptionOrNull()?.message}")
                stop()
                onFinished()
                return
            }
            tick++
            val rate = updateRateHz.coerceIn(1, 40)
            handler.postAtTime(this, startedAt + tick * 1000L / rate.toLong())
        }
    }
}

internal class UniversalPayloadUsbEngine(
    context: Context,
    private val source: UniversalWaveStream,
    private val onStatus: (String) -> Unit,
    private val onProgress: (Double, Double) -> Unit,
    private val onFinished: () -> Unit
) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    @Volatile private var running = false

    fun findTarget(): UsbLightTarget? {
        val candidates = mutableListOf<Pair<Int, UsbLightTarget>>()
        for (device in usbManager.deviceList.values) {
            var control: UsbInterface? = null
            for (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_COMM) control = usbInterface
            }
            for (index in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(index)
                for (endpointIndex in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
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
    fun requestPermission(target: UsbLightTarget, intent: PendingIntent) =
        usbManager.requestPermission(target.device, intent)

    fun start(target: UsbLightTarget) {
        check(usbManager.hasPermission(target.device)) { "USB permission has not been granted." }
        val connection = usbManager.openDevice(target.device)
            ?: error("Android could not open the USB light controller.")
        check(connection.claimInterface(target.dataInterface, true)) {
            connection.close()
            "Android could not claim the USB bulk interface."
        }
        target.controlInterface?.let { control ->
            if (control.id != target.dataInterface.id) {
                runCatching {
                    connection.claimInterface(control, true)
                    configureCdc(connection, control)
                }
            }
        }
        running = true
        onStatus("Universal payload streaming through ${target.description} at 48 kHz PCM.")

        thread(name = "UniversalPayloadUsb") {
            try {
                sendConfig(connection, target.endpointOut)
                stream(connection, target.endpointOut)
            } catch (error: Throwable) {
                onStatus("Universal USB stream stopped: ${error.message}")
            } finally {
                running = false
                source.close()
                runCatching { connection.releaseInterface(target.dataInterface) }
                target.controlInterface?.let { runCatching { connection.releaseInterface(it) } }
                connection.close()
                onFinished()
            }
        }
    }

    fun stop() {
        running = false
        source.close()
    }

    private fun stream(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        var sequence = 0
        val samplesPerPacket = 480
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
            var ended = false
            repeat(samplesPerPacket) {
                val sample = source.nextSample()
                if (sample == null) ended = true
                packet.putShort(((sample ?: 0f).coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            val bytes = packet.array()
            val crc = CRC32().apply {
                update(bytes, 4, 12)
                update(bytes, payloadStart, samplesPerPacket * 2)
            }.value.toInt()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(crcPosition, crc)
            transferAll(connection, endpoint, bytes)
            if (sequence % 50 == 0) onProgress(source.elapsedSeconds(), source.payloadFraction())
            sequence++
            if (ended) running = false
        }
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

    private fun configureCdc(connection: UsbDeviceConnection, control: UsbInterface) {
        val lineCoding = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(115_200).put(0).put(0).put(8).array()
        connection.controlTransfer(0x21, 0x20, 0, control.id, lineCoding, lineCoding.size, 1_000)
        connection.controlTransfer(0x21, 0x22, 0x03, control.id, null, 0, 1_000)
    }

    private fun transferAll(connection: UsbDeviceConnection, endpoint: UsbEndpoint, bytes: ByteArray) {
        var offset = 0
        val chunkSize = endpoint.maxPacketSize.coerceAtLeast(64) * 8
        while (offset < bytes.size && running) {
            val count = minOf(chunkSize, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + count)
            val sent = connection.bulkTransfer(endpoint, chunk, chunk.size, 1_500)
            if (sent <= 0) error("USB bulk transfer failed at byte $offset.")
            offset += sent
        }
    }
}

internal fun byteArrayDescriptor(name: String, bytes: ByteArray, sha256: ByteArray): UniversalPayloadDescriptor =
    UniversalPayloadDescriptor(
        fileName = name,
        payloadBytes = bytes.size.toLong(),
        sha256 = sha256,
        openInput = { ByteArrayInputStream(bytes) }
    )

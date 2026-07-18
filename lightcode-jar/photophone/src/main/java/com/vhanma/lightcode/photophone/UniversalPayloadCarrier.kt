package com.vhanma.lightcode.photophone

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

internal data class UniversalPayloadDescriptor(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: ByteArray
) {
    val sha256Hex: String
        get() = sha256.joinToString("") { "%02x".format(it) }
}

internal object UniversalPayloadInspector {
    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "payload.bin"
    }

    fun inspect(
        context: Context,
        uri: Uri,
        preferredName: String? = null,
        onProgress: (Long) -> Unit = {}
    ): UniversalPayloadDescriptor {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val buffer = ByteArray(256 * 1024)
        context.contentResolver.openInputStream(uri)?.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                count += read.toLong()
                onProgress(count)
            }
        } ?: error("Unable to open the selected payload.")

        return UniversalPayloadDescriptor(
            uri = uri,
            displayName = preferredName?.takeIf { it.isNotBlank() } ?: displayName(context, uri),
            sizeBytes = count,
            sha256 = digest.digest()
        )
    }
}

internal enum class UniversalCarrierMode(
    val displayName: String,
    val estimatedBitsPerSecond: Int,
    val sampleRate: Int
) {
    FSK4("4-FSK · 1,200 bits/s", 1_200, 24_000),
    MANCHESTER_BPSK("Manchester BPSK · 300 bits/s", 300, 24_000),
    PPM16("16-position PPM · 600 bits/s", 600, 24_000),
    PRBS_SPREAD("PRBS spread-spectrum · ~342 bits/s", 342, 24_000),
    TORCH_OOK("Torch OOK · 10 bits/s", 10, 40)
}

/**
 * Streaming LPU2 packet format.
 *
 * Header:
 *   64 × 0x55
 *   "LPU2"
 *   uint8 version
 *   uint8 carrier id
 *   uint16 UTF-8 filename length
 *   filename
 *   uint64 payload length
 *   32-byte SHA-256
 *
 * Each block:
 *   "BLK2"
 *   uint32 block index
 *   uint32 payload length
 *   uint32 CRC32
 *   payload bytes
 *
 * End:
 *   "END2"
 *   uint32 block count
 *   uint64 payload length
 *   32-byte SHA-256
 */
internal class UniversalPacketByteStream(
    private val context: Context,
    private val descriptor: UniversalPayloadDescriptor,
    private val carrierMode: UniversalCarrierMode,
    private val loop: Boolean,
    private val blockSize: Int = 16 * 1024
) : Closeable {
    private enum class Stage { HEADER, BLOCK, END, RESET, DONE }

    private var input: InputStream? = null
    private var stage = Stage.HEADER
    private var current = ByteArray(0)
    private var position = 0
    private var blockIndex = 0
    private var payloadBytesRead = 0L
    private var completedPasses = 0L

    val progressBytes: Long
        get() = payloadBytesRead

    val passes: Long
        get() = completedPasses

    init {
        reopenPayload()
    }

    fun nextByte(): Int {
        while (position >= current.size) {
            if (!advanceChunk()) return -1
        }
        return current[position++].toInt() and 0xFF
    }

    private fun advanceChunk(): Boolean {
        position = 0
        current = when (stage) {
            Stage.HEADER -> {
                stage = Stage.BLOCK
                buildHeader()
            }
            Stage.BLOCK -> {
                val payload = ByteArray(blockSize)
                val read = input?.read(payload) ?: -1
                if (read < 0) {
                    stage = Stage.END
                    return advanceChunk()
                }
                if (read == 0) return advanceChunk()
                payloadBytesRead += read.toLong()
                buildBlock(payload, read, blockIndex++)
            }
            Stage.END -> {
                completedPasses++
                stage = if (loop) Stage.RESET else Stage.DONE
                buildEnd()
            }
            Stage.RESET -> {
                reopenPayload()
                blockIndex = 0
                payloadBytesRead = 0L
                stage = Stage.BLOCK
                buildHeader()
            }
            Stage.DONE -> return false
        }
        return current.isNotEmpty()
    }

    private fun reopenPayload() {
        input?.close()
        input = context.contentResolver.openInputStream(descriptor.uri)
            ?: error("Unable to reopen the payload stream.")
    }

    private fun buildHeader(): ByteArray = ByteArrayOutputStream().apply {
        repeat(64) { write(0x55) }
        write("LPU2".toByteArray(Charsets.US_ASCII))
        write(2)
        write(carrierMode.ordinal)
        val name = descriptor.displayName.toByteArray(Charsets.UTF_8)
        require(name.size <= 65_535) { "Filename is too long for the packet header." }
        write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(name.size.toShort()).array())
        write(name)
        write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(descriptor.sizeBytes).array())
        write(descriptor.sha256)
    }.toByteArray()

    private fun buildBlock(payload: ByteArray, length: Int, index: Int): ByteArray {
        val crc = CRC32().apply { update(payload, 0, length) }.value.toInt()
        return ByteArrayOutputStream(16 + length).apply {
            write("BLK2".toByteArray(Charsets.US_ASCII))
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(index).array())
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(length).array())
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc).array())
            write(payload, 0, length)
        }.toByteArray()
    }

    private fun buildEnd(): ByteArray = ByteArrayOutputStream().apply {
        write("END2".toByteArray(Charsets.US_ASCII))
        write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(blockIndex).array())
        write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(descriptor.sizeBytes).array())
        write(descriptor.sha256)
    }.toByteArray()

    override fun close() {
        input?.close()
        input = null
        stage = Stage.DONE
    }
}

internal interface SequentialOpticalStream : Closeable {
    val sampleRate: Int
    val label: String
    val payloadSizeBytes: Long
    val payloadProgressBytes: Long
    val completedPasses: Long
    val finished: Boolean
    fun nextSample(): Float
}

internal class UniversalCarrierSampleStream(
    context: Context,
    descriptor: UniversalPayloadDescriptor,
    val mode: UniversalCarrierMode,
    loop: Boolean
) : SequentialOpticalStream {
    private val packet = UniversalPacketByteStream(context, descriptor, mode, loop)
    private var currentByte = 0
    private var bitPosition = 8
    private var ended = false

    private var phase = 0.0
    private var symbolSample = 0
    private var currentFrequency = 900.0
    private var currentManchesterSign = 1
    private var manchesterHalf = 0
    private var currentPpmNibble = 0
    private var currentSpreadSign = 1
    private var spreadChip = 0
    private var currentOokBit = 0

    override val sampleRate: Int = mode.sampleRate
    override val label: String = "${mode.displayName} · ${descriptor.displayName}"
    override val payloadSizeBytes: Long = descriptor.sizeBytes
    override val payloadProgressBytes: Long
        get() = packet.progressBytes
    override val completedPasses: Long
        get() = packet.passes
    override val finished: Boolean
        get() = ended

    override fun nextSample(): Float {
        if (ended) return 0f
        return when (mode) {
            UniversalCarrierMode.FSK4 -> nextFsk4()
            UniversalCarrierMode.MANCHESTER_BPSK -> nextManchester()
            UniversalCarrierMode.PPM16 -> nextPpm16()
            UniversalCarrierMode.PRBS_SPREAD -> nextPrbsSpread()
            UniversalCarrierMode.TORCH_OOK -> nextTorchOok()
        }
    }

    private fun nextFsk4(): Float {
        val samplesPerSymbol = sampleRate / 600
        if (symbolSample == 0) {
            val first = nextBit()
            val second = nextBit()
            if (first < 0) return finish()
            val symbol = (first shl 1) or if (second < 0) 0 else second
            currentFrequency = doubleArrayOf(900.0, 1_500.0, 2_100.0, 2_700.0)[symbol]
        }
        phase += 2.0 * PI * currentFrequency / sampleRate.toDouble()
        val value = (0.94 * sin(phase)).toFloat()
        symbolSample = (symbolSample + 1) % samplesPerSymbol
        return value
    }

    private fun nextManchester(): Float {
        val samplesPerHalf = sampleRate / 600
        if (symbolSample == 0 && manchesterHalf == 0) {
            val bit = nextBit()
            if (bit < 0) return finish()
            currentManchesterSign = if (bit == 1) 1 else -1
        }
        val sign = if (manchesterHalf == 0) currentManchesterSign else -currentManchesterSign
        phase += 2.0 * PI * 2_400.0 / sampleRate.toDouble()
        val value = (0.93 * sign * sin(phase)).toFloat()
        symbolSample++
        if (symbolSample >= samplesPerHalf) {
            symbolSample = 0
            manchesterHalf = (manchesterHalf + 1) % 2
        }
        return value
    }

    private fun nextPpm16(): Float {
        val slotSamples = 10
        val symbolSamples = 16 * slotSamples
        if (symbolSample == 0) {
            var nibble = 0
            repeat(4) {
                val bit = nextBit()
                if (bit < 0) return finish()
                nibble = (nibble shl 1) or bit
            }
            currentPpmNibble = nibble
        }
        val slot = symbolSample / slotSamples
        val inside = symbolSample % slotSamples
        val value = if (slot == currentPpmNibble && inside < 4) 1f else -1f
        symbolSample = (symbolSample + 1) % symbolSamples
        return value
    }

    private fun nextPrbsSpread(): Float {
        val chips = intArrayOf(1, 1, 1, -1, -1, 1, -1)
        val samplesPerChip = sampleRate / 2_400
        if (symbolSample == 0 && spreadChip == 0) {
            val bit = nextBit()
            if (bit < 0) return finish()
            currentSpreadSign = if (bit == 1) 1 else -1
        }
        val sign = currentSpreadSign * chips[spreadChip]
        phase += 2.0 * PI * 3_600.0 / sampleRate.toDouble()
        val value = (0.92 * sign * sin(phase)).toFloat()
        symbolSample++
        if (symbolSample >= samplesPerChip) {
            symbolSample = 0
            spreadChip = (spreadChip + 1) % chips.size
        }
        return value
    }

    private fun nextTorchOok(): Float {
        val samplesPerBit = 4
        if (symbolSample == 0) {
            val bit = nextBit()
            if (bit < 0) return finish()
            currentOokBit = bit
        }
        val value = if (currentOokBit == 1) 1f else -1f
        symbolSample = (symbolSample + 1) % samplesPerBit
        return value
    }

    private fun nextBit(): Int {
        if (bitPosition >= 8) {
            val next = packet.nextByte()
            if (next < 0) return -1
            currentByte = next
            bitPosition = 0
        }
        val bit = (currentByte ushr (7 - bitPosition)) and 1
        bitPosition++
        return bit
    }

    private fun finish(): Float {
        ended = true
        return 0f
    }

    override fun close() {
        packet.close()
        ended = true
    }
}

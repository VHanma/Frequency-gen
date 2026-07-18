package com.vhanma.lightcode.photophone

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

internal enum class UniversalCarrier(val displayName: String, val identifier: Int) {
    MANCHESTER_BPSK("Manchester BPSK", 1),
    FSK4("4-FSK", 2),
    PRBS31_SPREAD("PRBS-31 spread BPSK", 3),
    CHIRP_SPREAD("Binary chirp spread", 4),
    PPM16("16-position PPM", 5),
    SLOW_OOK("Slow Manchester OOK", 6)
}

internal data class PayloadMetadata(
    val name: String,
    val length: Long,
    val sha256: ByteArray
) {
    val sha256Hex: String
        get() = sha256.joinToString("") { "%02x".format(it) }
}

internal sealed class PreparedPayload {
    abstract val metadata: PayloadMetadata
    abstract fun openInput(): InputStream

    class Text(
        private val bytes: ByteArray,
        override val metadata: PayloadMetadata
    ) : PreparedPayload() {
        override fun openInput(): InputStream = ByteArrayInputStream(bytes)
    }

    class File(
        private val context: Context,
        private val uri: Uri,
        override val metadata: PayloadMetadata
    ) : PreparedPayload() {
        override fun openInput(): InputStream =
            context.contentResolver.openInputStream(uri)
                ?: error("Android could not reopen the selected payload file.")
    }
}

internal object PayloadPreparer {
    fun prepareText(text: String, name: String = "message.txt"): PreparedPayload {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return PreparedPayload.Text(bytes, PayloadMetadata(name, bytes.size.toLong(), digest))
    }

    fun prepareFile(
        context: Context,
        uri: Uri,
        fallbackName: String,
        onProgress: (Long, Long?) -> Unit
    ): PreparedPayload {
        val resolver = context.contentResolver
        var reportedLength: Long? = null
        var name = fallbackName.ifBlank { "payload.bin" }
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) reportedLength = cursor.getLong(sizeIndex)
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val buffer = ByteArray(256 * 1024)
        resolver.openInputStream(uri)?.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                count += read.toLong()
                onProgress(count, reportedLength)
            }
        } ?: error("Android could not read the selected payload file.")

        return PreparedPayload.File(
            context.applicationContext,
            uri,
            PayloadMetadata(name, count, digest.digest())
        )
    }
}

/**
 * Streaming frame structure. Only one block is held in memory at a time.
 *
 * LCU4 header:
 * magic, version, carrier id, UTF-8 filename, 64-bit payload length,
 * SHA-256, block size.
 *
 * Each block:
 * BLK4, 64-bit sequence, 32-bit block length, CRC32, bytes.
 *
 * Footer:
 * END4, 64-bit block count, SHA-256.
 */
internal class FramedPayloadByteStream(
    private val payload: PreparedPayload,
    private val carrier: UniversalCarrier,
    private val loop: Boolean,
    private val blockSize: Int = 16 * 1024
) {
    private var input: InputStream = payload.openInput()
    private var segment = buildHeader()
    private var segmentIndex = 0
    private var sequence = 0L
    private var footerQueued = false
    private var ended = false

    var payloadBytesRead: Long = 0L
        private set
    var passCount: Long = 0L
        private set

    fun nextByte(): Int? {
        while (true) {
            if (segmentIndex < segment.size) {
                return segment[segmentIndex++].toInt() and 0xFF
            }
            if (!loadNextSegment()) return null
        }
    }

    fun close() {
        runCatching { input.close() }
    }

    private fun loadNextSegment(): Boolean {
        if (ended) return false
        if (footerQueued) {
            passCount++
            if (!loop) {
                ended = true
                close()
                return false
            }
            resetPass()
            return true
        }

        val data = ByteArray(blockSize)
        val count = input.read(data)
        if (count < 0) {
            segment = buildFooter()
            segmentIndex = 0
            footerQueued = true
            return true
        }
        if (count == 0) return loadNextSegment()

        val crc = CRC32().apply { update(data, 0, count) }.value.toInt()
        segment = ByteArrayOutputStream(20 + count).apply {
            write("BLK4".toByteArray(Charsets.US_ASCII))
            write(longBytes(sequence))
            write(intBytes(count))
            write(intBytes(crc))
            write(data, 0, count)
        }.toByteArray()
        segmentIndex = 0
        sequence++
        payloadBytesRead += count.toLong()
        return true
    }

    private fun resetPass() {
        close()
        input = payload.openInput()
        segment = buildHeader()
        segmentIndex = 0
        sequence = 0L
        footerQueued = false
        payloadBytesRead = 0L
    }

    private fun buildHeader(): ByteArray {
        val name = payload.metadata.name.toByteArray(Charsets.UTF_8)
        require(name.size <= 65_535) { "The payload filename is too long." }
        return ByteArrayOutputStream(192 + name.size).apply {
            repeat(128) { write(0x55) }
            write("LCU4".toByteArray(Charsets.US_ASCII))
            write(4)
            write(carrier.identifier)
            write(shortBytes(name.size))
            write(name)
            write(longBytes(payload.metadata.length))
            write(payload.metadata.sha256)
            write(intBytes(blockSize))
        }.toByteArray()
    }

    private fun buildFooter(): ByteArray = ByteArrayOutputStream(48).apply {
        write("END4".toByteArray(Charsets.US_ASCII))
        write(longBytes(sequence))
        write(payload.metadata.sha256)
    }.toByteArray()

    private fun shortBytes(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()).array()

    private fun intBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    private fun longBytes(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
}

internal class UniversalWaveEncoder(
    payload: PreparedPayload,
    private val carrier: UniversalCarrier,
    val sampleRate: Int,
    loop: Boolean
) {
    private val byteStream = FramedPayloadByteStream(payload, carrier, loop)
    private var currentByte = 0
    private var bitIndex = -1
    private var phase = 0.0

    private var currentBit = 0
    private var manchesterHalf = 0
    private var manchesterSample = 0

    private var currentSymbol = 0
    private var symbolSample = 0

    private val prbs = intArrayOf(
        1, 1, 1, 1, 1, 0, 1, 0, 1, 1, 0, 0, 1, 0, 0, 0,
        1, 1, 1, 0, 1, 0, 0, 1, 1, 0, 1, 1, 0, 0, 0
    )
    private var spreadBit = 0
    private var spreadChip = 0
    private var spreadSample = 0

    private var nibble = 0
    private var nibbleHalf = 0

    val payloadBytesRead: Long
        get() = byteStream.payloadBytesRead
    val passCount: Long
        get() = byteStream.passCount

    fun nextSample(): Float? = when (carrier) {
        UniversalCarrier.MANCHESTER_BPSK -> nextManchester()
        UniversalCarrier.FSK4 -> nextFsk4()
        UniversalCarrier.PRBS31_SPREAD -> nextPrbsSpread()
        UniversalCarrier.CHIRP_SPREAD -> nextChirp()
        UniversalCarrier.PPM16 -> nextPpm16()
        UniversalCarrier.SLOW_OOK -> nextSlowOok()
    }

    fun close() = byteStream.close()

    private fun nextManchester(): Float? {
        val bitRate = if (sampleRate < 1_000) 5 else 300
        val samplesPerHalf = max(1, sampleRate / (bitRate * 2))
        if (manchesterSample == 0 && manchesterHalf == 0) {
            currentBit = nextBit() ?: return null
        }
        val sign = if (currentBit == 1) {
            if (manchesterHalf == 0) 1.0 else -1.0
        } else {
            if (manchesterHalf == 0) -1.0 else 1.0
        }
        phase += 2.0 * PI * minOf(2_400.0, sampleRate * 0.20) / sampleRate.toDouble()
        val sample = (0.94 * sign * sin(phase)).toFloat()
        manchesterSample++
        if (manchesterSample >= samplesPerHalf) {
            manchesterSample = 0
            manchesterHalf++
            if (manchesterHalf >= 2) manchesterHalf = 0
        }
        return sample
    }

    private fun nextFsk4(): Float? {
        val symbolRate = if (sampleRate < 1_000) 5 else 600
        val samplesPerSymbol = max(1, sampleRate / symbolRate)
        if (symbolSample == 0) currentSymbol = nextBits(2) ?: return null
        val maximum = sampleRate * 0.38
        val frequencies = doubleArrayOf(
            minOf(1_200.0, maximum * 0.40),
            minOf(1_800.0, maximum * 0.58),
            minOf(2_400.0, maximum * 0.78),
            minOf(3_000.0, maximum)
        )
        phase += 2.0 * PI * frequencies[currentSymbol] / sampleRate.toDouble()
        val sample = (0.94 * sin(phase)).toFloat()
        symbolSample++
        if (symbolSample >= samplesPerSymbol) symbolSample = 0
        return sample
    }

    private fun nextPrbsSpread(): Float? {
        val chipRate = if (sampleRate < 1_000) 20 else 2_400
        val samplesPerChip = max(1, sampleRate / chipRate)
        if (spreadSample == 0 && spreadChip == 0) spreadBit = nextBit() ?: return null
        val chip = prbs[spreadChip]
        val sign = if ((spreadBit xor chip) == 1) 1.0 else -1.0
        phase += 2.0 * PI * minOf(4_800.0, sampleRate * 0.22) / sampleRate.toDouble()
        val sample = (0.92 * sign * sin(phase)).toFloat()
        spreadSample++
        if (spreadSample >= samplesPerChip) {
            spreadSample = 0
            spreadChip++
            if (spreadChip >= prbs.size) spreadChip = 0
        }
        return sample
    }

    private fun nextChirp(): Float? {
        val symbolRate = if (sampleRate < 1_000) 2 else 50
        val samplesPerSymbol = max(8, sampleRate / symbolRate)
        if (symbolSample == 0) currentBit = nextBit() ?: return null
        val fraction = symbolSample.toDouble() / samplesPerSymbol.toDouble()
        val low = minOf(800.0, sampleRate * 0.08)
        val high = minOf(4_000.0, sampleRate * 0.36)
        val frequency = if (currentBit == 1) {
            low + (high - low) * fraction
        } else {
            high - (high - low) * fraction
        }
        phase += 2.0 * PI * frequency / sampleRate.toDouble()
        val sample = (0.94 * sin(phase)).toFloat()
        symbolSample++
        if (symbolSample >= samplesPerSymbol) symbolSample = 0
        return sample
    }

    private fun nextPpm16(): Float? {
        val slotSamples = max(2, sampleRate / 4_000)
        val symbolSamples = 16 * slotSamples
        if (symbolSample == 0) {
            if (nibbleHalf == 0) {
                val value = byteStream.nextByte() ?: return null
                nibble = value ushr 4
                currentByte = value
                nibbleHalf = 1
            } else {
                nibble = currentByte and 0x0F
                nibbleHalf = 0
            }
        }
        val selectedStart = nibble * slotSamples
        val pulseWidth = max(1, slotSamples / 2)
        val on = symbolSample in selectedStart until (selectedStart + pulseWidth)
        val sample = if (on) 0.96f else -0.96f
        symbolSample++
        if (symbolSample >= symbolSamples) symbolSample = 0
        return sample
    }

    private fun nextSlowOok(): Float? {
        val bitRate = max(1, minOf(5, sampleRate / 8))
        val samplesPerHalf = max(1, sampleRate / (bitRate * 2))
        if (manchesterSample == 0 && manchesterHalf == 0) currentBit = nextBit() ?: return null
        val on = if (currentBit == 1) manchesterHalf == 0 else manchesterHalf == 1
        val sample = if (on) 0.96f else -0.96f
        manchesterSample++
        if (manchesterSample >= samplesPerHalf) {
            manchesterSample = 0
            manchesterHalf++
            if (manchesterHalf >= 2) manchesterHalf = 0
        }
        return sample
    }

    private fun nextBit(): Int? {
        if (bitIndex < 0) {
            currentByte = byteStream.nextByte() ?: return null
            bitIndex = 7
        }
        val bit = (currentByte ushr bitIndex) and 1
        bitIndex--
        return bit
    }

    private fun nextBits(count: Int): Int? {
        var value = 0
        repeat(count) {
            val bit = nextBit() ?: return null
            value = (value shl 1) or bit
        }
        return value
    }
}

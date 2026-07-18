package com.vhanma.lightcode.photophone

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

internal enum class UniversalCarrier(val displayName: String) {
    MANCHESTER("Manchester exact-data carrier"),
    FSK4("4-FSK exact-data carrier"),
    PPM16("16-position PPM exact-data carrier"),
    PRBS_SPREAD("PRBS-127 spread-spectrum payload"),
    GOLD_SPREAD("Gold-code spread-spectrum payload"),
    CHIRP_SPREAD("Up/down chirp-spread payload"),
    KIRLIAN_FSK4("Kirlian-timing 4-FSK payload")
}

internal data class PreparedPayload(
    val containerFile: File,
    val originalName: String,
    val originalLength: Long,
    val containerLength: Long,
    val sha256Hex: String,
    val blockCount: Long
)

internal object UniversalPayloadEncoder {
    private const val BLOCK_SIZE = 64 * 1024
    private const val CONTAINER_MAGIC = "LCUPV1\u0000\u0000"
    private const val BLOCK_MAGIC = 0x4C434231 // LCB1

    fun carrierNames(): List<String> = UniversalCarrier.entries.map { it.displayName }

    fun prepareText(context: Context, text: String, name: String = "typed-message.txt"): PreparedPayload {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return prepareStream(context, ByteArrayInputStream(bytes), name)
    }

    fun prepareUri(context: Context, uri: Uri, displayName: String): PreparedPayload {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Android could not open the selected payload.")
        return input.use { prepareStream(context, it, displayName.ifBlank { "payload.bin" }) }
    }

    private fun prepareStream(context: Context, source: InputStream, displayName: String): PreparedPayload {
        val folder = File(context.cacheDir, "lightcode_payloads").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "payload.bin" }
        val container = File(folder, "${System.currentTimeMillis()}_$safeName.lcup")
        val nameBytes = displayName.toByteArray(Charsets.UTF_8).take(1024).toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
        var originalLength = 0L
        var blockCount = 0L

        RandomAccessFile(container, "rw").use { file ->
            file.setLength(0L)
            file.write(CONTAINER_MAGIC.toByteArray(Charsets.US_ASCII))
            file.writeInt(1)
            file.writeInt(nameBytes.size)
            file.write(nameBytes)
            val originalLengthPosition = file.filePointer
            file.writeLong(0L)
            val digestPosition = file.filePointer
            file.write(ByteArray(32))
            file.writeInt(BLOCK_SIZE)
            val blockCountPosition = file.filePointer
            file.writeLong(0L)

            val input = BufferedInputStream(source, BLOCK_SIZE)
            val buffer = ByteArray(BLOCK_SIZE)
            var blockIndex = 0L
            while (true) {
                var filled = 0
                while (filled < buffer.size) {
                    val count = input.read(buffer, filled, buffer.size - filled)
                    if (count < 0) break
                    if (count == 0) continue
                    filled += count
                }
                if (filled == 0) break

                digest.update(buffer, 0, filled)
                val crc = CRC32().apply { update(buffer, 0, filled) }.value.toInt()
                file.writeInt(BLOCK_MAGIC)
                file.writeLong(blockIndex)
                file.writeInt(filled)
                file.writeInt(crc)
                file.write(buffer, 0, filled)
                originalLength += filled.toLong()
                blockCount++
                blockIndex++
                if (filled < buffer.size) break
            }

            val hash = digest.digest()
            val end = file.filePointer
            file.seek(originalLengthPosition)
            file.writeLong(originalLength)
            file.seek(digestPosition)
            file.write(hash)
            file.seek(blockCountPosition)
            file.writeLong(blockCount)
            file.seek(end)
        }

        val hashHex = RandomAccessFile(container, "r").use { file ->
            file.seek(8L + 4L)
            val nameLength = file.readInt()
            file.skipBytes(nameLength)
            file.readLong()
            val hash = ByteArray(32)
            file.readFully(hash)
            hash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        return PreparedPayload(
            containerFile = container,
            originalName = displayName,
            originalLength = originalLength,
            containerLength = container.length(),
            sha256Hex = hashHex,
            blockCount = blockCount
        )
    }
}

internal class UniversalPayloadSignal(
    private val prepared: PreparedPayload,
    private val carrier: UniversalCarrier,
    override val loop: Boolean
) : OpticalSignal {
    override val sampleRate: Int = 24_000
    override val label: String = "${carrier.displayName} · ${prepared.originalName}"

    private val file = RandomAccessFile(prepared.containerFile, "r")
    private val totalBits = prepared.containerLength.coerceAtLeast(1L) * 8L
    private val cache = ByteArray(16 * 1024)
    private var cacheStart = -1L
    private var cacheLength = 0
    private var closed = false

    private val mSequence = generateMSequence()
    private val goldCode = generateGoldCode()

    override val durationSeconds: Double = when (carrier) {
        UniversalCarrier.MANCHESTER -> totalBits.toDouble() / 300.0
        UniversalCarrier.FSK4 -> totalBits.toDouble() / 1_200.0
        UniversalCarrier.PPM16 -> totalBits.toDouble() / 300.0
        UniversalCarrier.PRBS_SPREAD -> totalBits.toDouble() / 50.0
        UniversalCarrier.GOLD_SPREAD -> totalBits.toDouble() / 50.0
        UniversalCarrier.CHIRP_SPREAD -> totalBits.toDouble() / 20.0
        UniversalCarrier.KIRLIAN_FSK4 -> totalBits.toDouble() / 600.0
    }

    override fun sampleAt(seconds: Double): Float {
        if (closed || durationSeconds <= 0.0) return 0f
        var time = seconds
        if (loop) {
            time %= durationSeconds
            if (time < 0.0) time += durationSeconds
        }
        if (time < 0.0 || time >= durationSeconds) return 0f

        return when (carrier) {
            UniversalCarrier.MANCHESTER -> manchesterSample(time)
            UniversalCarrier.FSK4 -> fsk4Sample(time, 600.0, false)
            UniversalCarrier.PPM16 -> ppm16Sample(time)
            UniversalCarrier.PRBS_SPREAD -> spreadSample(time, mSequence)
            UniversalCarrier.GOLD_SPREAD -> spreadSample(time, goldCode)
            UniversalCarrier.CHIRP_SPREAD -> chirpSample(time)
            UniversalCarrier.KIRLIAN_FSK4 -> fsk4Sample(time, 300.0, true)
        }
    }

    private fun manchesterSample(time: Double): Float {
        val bitRate = 300.0
        val bitPosition = time * bitRate
        val bitIndex = floor(bitPosition).toLong().coerceAtMost(totalBits - 1L)
        val bit = bitAt(bitIndex)
        val secondHalf = bitPosition - floor(bitPosition) >= 0.5
        val sign = if (bit == 1) {
            if (secondHalf) -1.0 else 1.0
        } else {
            if (secondHalf) 1.0 else -1.0
        }
        return (0.94 * sign * sin(2.0 * PI * 2_400.0 * time)).toFloat()
    }

    private fun fsk4Sample(time: Double, symbolRate: Double, kirlianGate: Boolean): Float {
        val symbolPosition = time * symbolRate
        val symbolIndex = floor(symbolPosition).toLong()
        val firstBitIndex = (symbolIndex * 2L).coerceAtMost(totalBits - 1L)
        val dibit = (bitAt(firstBitIndex) shl 1) or bitAt((firstBitIndex + 1L).coerceAtMost(totalBits - 1L))
        val frequency = when (dibit) {
            0 -> 1_200.0
            1 -> 1_800.0
            2 -> 2_400.0
            else -> 3_000.0
        }
        val gate = if (!kirlianGate || sin(2.0 * PI * 37.0 * time) > 0.35) 1.0 else 0.0
        return (0.94 * gate * sin(2.0 * PI * frequency * time)).toFloat()
    }

    private fun ppm16Sample(time: Double): Float {
        val symbolRate = 75.0
        val symbolPosition = time * symbolRate
        val symbolIndex = floor(symbolPosition).toLong()
        val firstBit = symbolIndex * 4L
        var nibble = 0
        repeat(4) { offset ->
            val index = (firstBit + offset).coerceAtMost(totalBits - 1L)
            nibble = (nibble shl 1) or bitAt(index)
        }
        val local = symbolPosition - floor(symbolPosition)
        val slot = floor(local * 16.0).toInt().coerceIn(0, 15)
        val slotFraction = local * 16.0 - floor(local * 16.0)
        return if (slot == nibble && slotFraction < 0.32) 1f else -0.72f
    }

    private fun spreadSample(time: Double, code: IntArray): Float {
        val bitRate = 50.0
        val chipsPerBit = 16
        val chipRate = bitRate * chipsPerBit
        val chipIndex = floor(time * chipRate).toLong()
        val payloadBitIndex = (chipIndex / chipsPerBit).coerceAtMost(totalBits - 1L)
        val payloadSign = if (bitAt(payloadBitIndex) == 1) 1.0 else -1.0
        val codeSign = if (code[(chipIndex % code.size).toInt()] == 1) 1.0 else -1.0
        return (0.92 * payloadSign * codeSign * sin(2.0 * PI * 3_200.0 * time)).toFloat()
    }

    private fun chirpSample(time: Double): Float {
        val bitRate = 20.0
        val symbolDuration = 1.0 / bitRate
        val symbolIndex = floor(time * bitRate).toLong().coerceAtMost(totalBits - 1L)
        val bit = bitAt(symbolIndex)
        val local = time - floor(time * bitRate) / bitRate
        val low = 700.0
        val high = 5_200.0
        val start = if (bit == 1) low else high
        val end = if (bit == 1) high else low
        val slope = (end - start) / symbolDuration
        val phase = 2.0 * PI * (start * local + 0.5 * slope * local * local)
        return (0.94 * sin(phase)).toFloat()
    }

    private fun bitAt(bitIndex: Long): Int {
        val byteIndex = bitIndex ushr 3
        val bitOffset = 7 - (bitIndex and 7L).toInt()
        return (byteAt(byteIndex).toInt() ushr bitOffset) and 1
    }

    @Synchronized
    private fun byteAt(index: Long): Byte {
        if (index < 0L || index >= prepared.containerLength) return 0
        if (cacheStart < 0L || index < cacheStart || index >= cacheStart + cacheLength) {
            cacheStart = (index / cache.size.toLong()) * cache.size.toLong()
            file.seek(cacheStart)
            cacheLength = file.read(cache)
            if (cacheLength < 0) {
                cacheLength = 0
                return 0
            }
        }
        val local = (index - cacheStart).toInt()
        return if (local in 0 until cacheLength) cache[local] else 0
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { file.close() }
        runCatching { prepared.containerFile.delete() }
    }

    private fun generateMSequence(): IntArray {
        var state = 0x7F
        return IntArray(127) {
            val output = state and 1
            val feedback = ((state ushr 6) xor (state ushr 5)) and 1
            state = (state ushr 1) or (feedback shl 6)
            output
        }
    }

    private fun generateGoldCode(): IntArray {
        val a = generateMSequence()
        var state = 0x5D
        val b = IntArray(127) {
            val output = state and 1
            val feedback = ((state ushr 6) xor (state ushr 5) xor (state ushr 4) xor (state ushr 1)) and 1
            state = (state ushr 1) or (feedback shl 6)
            output
        }
        return IntArray(127) { index -> a[index] xor b[(index + 19) % 127] }
    }
}

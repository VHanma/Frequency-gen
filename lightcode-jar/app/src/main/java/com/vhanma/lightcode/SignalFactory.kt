package com.vhanma.lightcode

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

internal data class OpticalProgram(
    val samples: FloatArray,
    val sampleRate: Int,
    val label: String,
    val loop: Boolean = false
) {
    val durationSeconds: Double
        get() = if (sampleRate <= 0) 0.0 else samples.size.toDouble() / sampleRate.toDouble()
}

internal object SignalFactory {
    fun tone(frequencyHz: Double, seconds: Double = 12.0, sampleRate: Int = 24_000): OpticalProgram {
        val count = (seconds * sampleRate).toInt().coerceAtLeast(1)
        val samples = FloatArray(count) { i ->
            (sin(2.0 * PI * frequencyHz * i.toDouble() / sampleRate.toDouble()) * 0.92).toFloat()
        }
        return OpticalProgram(samples, sampleRate, "Tone ${frequencyHz.toInt()} Hz", loop = true)
    }

    fun logarithmicSweep(
        startHz: Double = 30.0,
        endHz: Double = 4_000.0,
        seconds: Double = 25.0,
        sampleRate: Int = 24_000
    ): OpticalProgram {
        val count = (seconds * sampleRate).toInt().coerceAtLeast(1)
        val ratio = endHz / startHz
        val samples = FloatArray(count)
        var phase = 0.0
        for (i in 0 until count) {
            val t = i.toDouble() / count.toDouble()
            val f = startHz * exp(kotlin.math.ln(ratio) * t)
            phase += 2.0 * PI * f / sampleRate.toDouble()
            samples[i] = (sin(phase) * 0.9).toFloat()
        }
        return OpticalProgram(samples, sampleRate, "Log sweep ${startHz.toInt()}-${endHz.toInt()} Hz")
    }

    /** Encodes arbitrary bytes as audible AFSK, not Morse. */
    fun afskFile(bytes: ByteArray, fileName: String, sampleRate: Int = 24_000): OpticalProgram {
        val payload = ByteArrayOutputStream().apply {
            repeat(48) { write(0x55) }
            write("LJC1".toByteArray(Charsets.US_ASCII))
            val name = fileName.toByteArray(Charsets.UTF_8).take(120).toByteArray()
            write(name.size)
            write(name)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array())
            write(bytes)
            val crc = CRC32().apply { update(bytes) }.value.toInt()
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc).array())
        }.toByteArray()

        val baud = 600
        val markHz = 1_200.0
        val spaceHz = 2_400.0
        val samplesPerBit = sampleRate / baud
        val output = FloatArray(payload.size * 8 * samplesPerBit)
        var out = 0
        var phase = 0.0

        for (byteValue in payload) {
            val unsigned = byteValue.toInt() and 0xFF
            for (bitIndex in 7 downTo 0) {
                val bit = (unsigned ushr bitIndex) and 1
                val frequency = if (bit == 1) markHz else spaceHz
                val step = 2.0 * PI * frequency / sampleRate.toDouble()
                repeat(samplesPerBit) {
                    output[out++] = (sin(phase) * 0.94).toFloat()
                    phase += step
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                }
            }
        }
        return OpticalProgram(output, sampleRate, "AFSK file: $fileName")
    }

    fun normalize(samples: FloatArray): FloatArray {
        var peak = 0f
        for (sample in samples) peak = maxOf(peak, kotlin.math.abs(sample))
        if (peak < 1e-6f) return samples
        val gain = 0.95f / peak
        return FloatArray(samples.size) { i -> (samples[i] * gain).coerceIn(-1f, 1f) }
    }

    fun removeDc(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var sum = 0.0
        for (sample in samples) sum += sample
        val mean = (sum / samples.size.toDouble()).toFloat()
        return FloatArray(samples.size) { i -> samples[i] - mean }
    }
}

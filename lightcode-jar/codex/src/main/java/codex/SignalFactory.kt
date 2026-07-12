package com.vhanma.lightcode

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tanh

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
    private const val MAX_PACKET_BYTES = 262_144

    fun tone(frequencyHz: Double, seconds: Double = 12.0, sampleRate: Int = 24_000): OpticalProgram {
        val count = (seconds * sampleRate).toInt().coerceAtLeast(1)
        val samples = FloatArray(count) { i ->
            (sin(2.0 * PI * frequencyHz * i.toDouble() / sampleRate.toDouble()) * 0.92).toFloat()
        }
        return OpticalProgram(samples, sampleRate, "Tone ${frequencyHz.toInt()} Hz", loop = true)
    }

    fun logarithmicSweep(
        startHz: Double = 35.0,
        endHz: Double = 4_500.0,
        seconds: Double = 24.0,
        sampleRate: Int = 24_000
    ): OpticalProgram {
        val count = (seconds * sampleRate).toInt().coerceAtLeast(1)
        val ratio = endHz / startHz
        val samples = FloatArray(count)
        var phase = 0.0
        for (i in 0 until count) {
            val t = i.toDouble() / count.toDouble()
            val f = startHz * exp(ln(ratio) * t)
            phase += 2.0 * PI * f / sampleRate.toDouble()
            val fade = when {
                t < 0.03 -> t / 0.03
                t > 0.97 -> (1.0 - t) / 0.03
                else -> 1.0
            }
            samples[i] = (sin(phase) * 0.90 * fade).toFloat()
        }
        return OpticalProgram(samples, sampleRate, "Jar sweep ${startHz.toInt()}-${endHz.toInt()} Hz")
    }

    fun speechEnhance(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val clean = removeDc(samples)
        val output = FloatArray(clean.size)
        var previous = 0f
        var envelope = 0f
        for (i in clean.indices) {
            val x = clean[i]
            val edge = x - 0.93f * previous
            previous = x
            var mixed = 0.72f * x + 0.88f * edge
            val absolute = kotlin.math.abs(mixed)
            envelope = if (absolute > envelope) {
                0.80f * envelope + 0.20f * absolute
            } else {
                0.995f * envelope + 0.005f * absolute
            }
            val compression = if (envelope > 0.20f) 0.20f / envelope else 1f
            mixed *= (0.55f + 0.45f * compression)
            output[i] = (tanh((mixed * 2.35f).toDouble()) / tanh(2.35)).toFloat()
        }
        return normalize(output)
    }

    /** Classic audible AFSK packet. It is a modem waveform, not Morse. */
    fun afskFile(bytes: ByteArray, fileName: String, sampleRate: Int = 24_000): OpticalProgram {
        val payload = framedPayload(bytes, fileName, "LJC1")
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

    /**
     * Aeneas-inspired synchronized timing channel implemented as 16-position optical pulses.
     * Each nibble selects one pulse location inside a fixed symbol window.
     */
    fun waterClockPpm(bytes: ByteArray, fileName: String, repeats: Int = 2, sampleRate: Int = 24_000): OpticalProgram {
        val frame = framedPayload(bytes, fileName, "LJC2")
        val repeated = ByteArrayOutputStream().apply {
            repeat(repeats.coerceIn(1, 5)) {
                write(frame)
                repeat(12) { write(0) }
            }
        }.toByteArray()

        val slotSamples = 15
        val symbolSamples = slotSamples * 16
        val beaconSamples = sampleRate * 2
        val output = FloatArray(beaconSamples + repeated.size * 2 * symbolSamples)

        // Attention handshake: five bright Gaussian-like bursts before the timed payload.
        repeat(5) { burst ->
            val center = sampleRate / 5 + burst * sampleRate / 3
            val width = sampleRate / 120.0
            for (i in (center - sampleRate / 50).coerceAtLeast(0)..(center + sampleRate / 50).coerceAtMost(beaconSamples - 1)) {
                val z = (i - center) / width
                output[i] = exp(-0.5 * z * z).toFloat()
            }
        }

        var base = beaconSamples
        for (byteValue in repeated) {
            val value = byteValue.toInt() and 0xFF
            val nibbles = intArrayOf(value ushr 4, value and 0x0F)
            for (nibble in nibbles) {
                val pulseCenter = base + nibble * slotSamples + slotSamples / 2
                val width = slotSamples / 4.0
                for (i in 0 until symbolSamples) {
                    val z = (base + i - pulseCenter) / width
                    output[base + i] = (0.98 * exp(-0.5 * z * z)).toFloat()
                }
                base += symbolSamples
            }
        }
        return OpticalProgram(output, sampleRate, "Water-clock 16-PPM: $fileName")
    }

    /**
     * Polybius-inspired five-lane carrier bank. Five simultaneous frequencies carry five bits
     * per symbol while a quiet pilot preserves timing through a resonant jar.
     */
    fun fiveFlameCarrier(bytes: ByteArray, fileName: String, repeats: Int = 2, sampleRate: Int = 24_000): OpticalProgram {
        val frame = framedPayload(bytes, fileName, "LJC3")
        val bits = ArrayList<Int>(frame.size * 8 * repeats)
        repeat(repeats.coerceIn(1, 4)) {
            for (byteValue in frame) {
                val value = byteValue.toInt() and 0xFF
                for (bit in 7 downTo 0) bits += (value ushr bit) and 1
            }
            repeat(80) { bits += 0 }
        }

        val carriers = doubleArrayOf(620.0, 860.0, 1_100.0, 1_340.0, 1_580.0)
        val symbolSamples = sampleRate / 50 // 20 ms
        val symbolCount = (bits.size + 4) / 5
        val beaconSamples = sampleRate
        val output = FloatArray(beaconSamples + symbolCount * symbolSamples)

        // Repeating beacon ladder from low to high before payload.
        var phase = 0.0
        for (i in 0 until beaconSamples) {
            val segment = (i * 5 / beaconSamples).coerceIn(0, 4)
            phase += 2.0 * PI * carriers[segment] / sampleRate
            output[i] = (sin(phase) * 0.80).toFloat()
        }

        val phases = DoubleArray(5)
        for (symbol in 0 until symbolCount) {
            val base = beaconSamples + symbol * symbolSamples
            for (n in 0 until symbolSamples) {
                val edge = sin(PI * (n + 1).toDouble() / (symbolSamples + 1).toDouble())
                val window = edge * edge
                var value = 0.14 * sin(2.0 * PI * 310.0 * n / sampleRate)
                for (lane in 0 until 5) {
                    val bitIndex = symbol * 5 + lane
                    if (bitIndex < bits.size && bits[bitIndex] == 1) {
                        phases[lane] += 2.0 * PI * carriers[lane] / sampleRate
                        value += 0.22 * sin(phases[lane])
                    } else {
                        phases[lane] += 2.0 * PI * carriers[lane] / sampleRate
                    }
                }
                output[base + n] = (value * window).toFloat().coerceIn(-0.98f, 0.98f)
            }
        }
        return OpticalProgram(normalize(output), sampleRate, "Five-Flame carrier: $fileName")
    }

    /** SETI-style repeated beacon followed by pulse-position text payload. */
    fun fireflyText(text: String, sampleRate: Int = 24_000): OpticalProgram {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val ppm = waterClockPpm(bytes, "firefly-message.txt", repeats = 3, sampleRate = sampleRate)
        return ppm.copy(label = "Firefly beacon text")
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

    private fun framedPayload(bytes: ByteArray, fileName: String, magic: String): ByteArray {
        require(bytes.size <= MAX_PACKET_BYTES) {
            "This experimental in-memory encoder currently supports files up to 256 KB."
        }
        return ByteArrayOutputStream().apply {
            repeat(48) { write(0x55) }
            write(magic.toByteArray(Charsets.US_ASCII))
            val name = fileName.toByteArray(Charsets.UTF_8).take(120).toByteArray()
            write(name.size)
            write(name)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array())
            write(bytes)
            val crc = CRC32().apply { update(bytes) }.value.toInt()
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc).array())
        }.toByteArray()
    }
}

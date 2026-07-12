package com.vhanma.lightcode.photophone

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
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

internal enum class MusicProcessing {
    DIRECT,
    CLARITY,
    COMPRESSED
}

internal object SignalCore {
    fun tone(frequencyHz: Double, seconds: Double = 15.0, sampleRate: Int = 24_000): OpticalProgram {
        val count = (seconds * sampleRate).toInt().coerceAtLeast(1)
        val output = FloatArray(count) { index ->
            (0.94 * sin(2.0 * PI * frequencyHz * index.toDouble() / sampleRate.toDouble())).toFloat()
        }
        return OpticalProgram(output, sampleRate, "Calibration tone ${frequencyHz.toInt()} Hz", loop = true)
    }

    fun sweep(
        startHz: Double = 35.0,
        endHz: Double = 4_500.0,
        seconds: Double = 24.0,
        sampleRate: Int = 24_000
    ): OpticalProgram {
        val count = (seconds * sampleRate).toInt().coerceAtLeast(1)
        val ratio = endHz / startHz
        val output = FloatArray(count)
        var phase = 0.0
        for (index in output.indices) {
            val progress = index.toDouble() / count.toDouble()
            val frequency = startHz * exp(ln(ratio) * progress)
            phase += 2.0 * PI * frequency / sampleRate.toDouble()
            val fade = when {
                progress < 0.03 -> progress / 0.03
                progress > 0.97 -> (1.0 - progress) / 0.03
                else -> 1.0
            }
            output[index] = (0.92 * fade * sin(phase)).toFloat()
        }
        return OpticalProgram(output, sampleRate, "Photophone jar sweep")
    }

    fun process(program: OpticalProgram, mode: MusicProcessing): OpticalProgram {
        val dcFree = removeDc(program.samples)
        val processed = when (mode) {
            MusicProcessing.DIRECT -> normalize(dcFree)
            MusicProcessing.CLARITY -> clarity(dcFree)
            MusicProcessing.COMPRESSED -> compressed(dcFree)
        }
        val suffix = when (mode) {
            MusicProcessing.DIRECT -> "direct PCM"
            MusicProcessing.CLARITY -> "clarity optical EQ"
            MusicProcessing.COMPRESSED -> "compressed optical PCM"
        }
        return program.copy(samples = processed, label = "${program.label} · $suffix")
    }

    fun normalize(samples: FloatArray, targetPeak: Float = 0.96f): FloatArray {
        var peak = 0f
        for (sample in samples) peak = maxOf(peak, abs(sample))
        if (peak < 1e-7f) return samples.copyOf()
        val gain = targetPeak / peak
        return FloatArray(samples.size) { index ->
            (samples[index] * gain).coerceIn(-targetPeak, targetPeak)
        }
    }

    fun removeDc(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var sum = 0.0
        for (sample in samples) sum += sample
        val mean = (sum / samples.size.toDouble()).toFloat()
        return FloatArray(samples.size) { index -> samples[index] - mean }
    }

    private fun clarity(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val output = FloatArray(samples.size)
        var previous = 0f
        var smooth = 0f
        for (index in samples.indices) {
            val current = samples[index]
            smooth = 0.985f * smooth + 0.015f * current
            val upper = current - smooth
            val edge = current - 0.92f * previous
            previous = current
            val mixed = 0.72f * current + 0.42f * upper + 0.40f * edge
            output[index] = (tanh((mixed * 1.8f).toDouble()) / tanh(1.8)).toFloat()
        }
        return normalize(output)
    }

    private fun compressed(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val output = FloatArray(samples.size)
        var envelope = 0f
        for (index in samples.indices) {
            val sample = samples[index]
            val magnitude = abs(sample)
            envelope = if (magnitude > envelope) {
                0.82f * envelope + 0.18f * magnitude
            } else {
                0.997f * envelope + 0.003f * magnitude
            }
            val gain = when {
                envelope < 0.08f -> 1.9f
                envelope < 0.20f -> 1.35f
                else -> (0.27f / envelope).coerceIn(0.45f, 1.0f)
            }
            output[index] = (tanh((sample * gain * 2.2f).toDouble()) / tanh(2.2)).toFloat()
        }
        return normalize(output)
    }

    fun sampleAt(program: OpticalProgram, seconds: Double): Float {
        if (program.samples.isEmpty() || program.sampleRate <= 0) return 0f
        var time = seconds
        val duration = program.durationSeconds
        if (program.loop && duration > 0.0) {
            time %= duration
            if (time < 0.0) time += duration
        }
        if (time < 0.0 || time >= duration) return 0f

        val position = time * program.sampleRate.toDouble()
        val index = floor(position).toInt().coerceIn(0, program.samples.lastIndex)
        val next = (index + 1).coerceAtMost(program.samples.lastIndex)
        val fraction = (position - index.toDouble()).toFloat()
        return program.samples[index] * (1f - fraction) + program.samples[next] * fraction
    }
}

package com.vaan.ultracarrier.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object DspMath {
    fun safeMessageBandwidth(sampleRate: Int, carrierHz: Float): Float {
        val nyquist = sampleRate / 2f
        return (nyquist - carrierHz - 500f).coerceIn(300f, 8_000f)
    }

    fun clampCarrier(sampleRate: Int, requestedHz: Float): Float =
        requestedHz.coerceIn(100f, sampleRate / 2f - 800f)

    fun interpolate(samples: FloatArray, position: Double): Float {
        if (samples.isEmpty()) return 0f
        val index = position.toInt().coerceIn(0, samples.lastIndex)
        val next = (index + 1).coerceAtMost(samples.lastIndex)
        val fraction = (position - index).toFloat().coerceIn(0f, 1f)
        return samples[index] + (samples[next] - samples[index]) * fraction
    }

    class LowPass(sampleRate: Int, cutoffHz: Float) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val cutoff = cutoffHz.coerceIn(20f, sampleRate * 0.45f)
            val q = 1.0 / sqrt(2.0)
            val omega = 2.0 * PI * cutoff / sampleRate
            val alpha = sin(omega) / (2.0 * q)
            val cosOmega = cos(omega)
            val a0 = 1.0 + alpha
            b0 = ((1.0 - cosOmega) / 2.0) / a0
            b1 = (1.0 - cosOmega) / a0
            b2 = b0
            a1 = (-2.0 * cosOmega) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(input: Float): Float {
            val x0 = input.toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            return y0.toFloat().coerceIn(-1f, 1f)
        }
    }

    class HighPass(sampleRate: Int, cutoffHz: Float) {
        private val alpha: Float
        private var previousInput = 0f
        private var previousOutput = 0f

        init {
            val cutoff = cutoffHz.coerceIn(20f, sampleRate * 0.2f)
            val rc = 1.0 / (2.0 * PI * cutoff)
            val dt = 1.0 / sampleRate
            alpha = (rc / (rc + dt)).toFloat()
        }

        fun process(input: Float): Float {
            val output = alpha * (previousOutput + input - previousInput)
            previousInput = input
            previousOutput = output
            return output.coerceIn(-1f, 1f)
        }
    }
}

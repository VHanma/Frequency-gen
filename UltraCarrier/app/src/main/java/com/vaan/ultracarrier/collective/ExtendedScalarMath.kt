package com.vaan.ultracarrier.collective

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Extended experimental sonification bank.
 * All outputs are ordinary stereo acoustic DSP. Names inspired by fringe or
 * speculative literature describe the mapping idea, not a claim of exotic EM output.
 */
internal class ExtendedScalarMath(
    private val config: CollectiveConfig,
    private val sampleRate: Int,
    private val carrier: Float
) {
    private var p1 = 0.0
    private var p2 = 0.0
    private var p3 = 0.0
    private var p4 = 0.0
    private var lastVoice = 0f
    private var framesSinceCross = 1
    private var sourceHz = 7.83f
    private var resonator = 0f
    private var resonatorVelocity = 0f

    fun sample(mode: ScalarMode, voice: Float, frame: Long): Pair<Float, Float> {
        updateSourceTracker(voice)
        val t = frame.toDouble() / sampleRate
        val rate = config.elfRateHz.coerceIn(.02f, 120f)
        val depth = config.elfDepth.coerceIn(0f, .98f)
        val motion = config.ditherRateHz.coerceIn(.02f, 5f)
        val presence = config.presence.coerceIn(.05f, 1f)
        val env = (1f - depth * .5f + depth * .5f * (1f + sin(2.0 * PI * rate * t).toFloat())).coerceIn(.02f, 1.98f)
        val amp = sqrt((1f + presence * voice).coerceIn(.02f, 1.98f))
        val maxF = sampleRate / 2f - 700f
        val c = carrier.coerceIn(500f, maxF)

        val out = when (mode) {
            ScalarMode.DNA_SONIFICATION -> {
                val q = ((voice + 1f) * 1.999f).toInt().coerceIn(0, 3)
                val ratios = floatArrayOf(1f, 4f / 3f, 3f / 2f, 2f)
                val f = (min(c * .18f, 1800f) * ratios[q]).coerceIn(90f, 4200f)
                advance1(f)
                val x = sin(p1).toFloat() * (.45f + .5f * abs(voice))
                x to x
            }
            ScalarMode.DNA_MEYL_RESONANCE, ScalarMode.DNA_SCALAR -> {
                val spin = 2.0 * PI * motion * t
                advance1(c)
                val l = cos(p1 + spin).toFloat() * amp * env
                val r = cos(-p1 - spin).toFloat() * amp * env
                l to r
            }
            ScalarMode.DNA_MONTAGNIER_LF_SIGNAL -> {
                val lf = sin(2.0 * PI * rate * t).toFloat()
                val x = voice * (.55f + .35f * lf) + lf * .08f
                x to x
            }
            ScalarMode.DNA_EM_RESONANCE, ScalarMode.DNA_RESONANT_ANTENNA -> {
                advance1(c)
                advance2((c + rate * 2f).coerceAtMost(maxF))
                val side = (.65 * cos(p1) + .35 * cos(p2)).toFloat() * amp * env
                side to (side * .92f + voice * .08f)
            }
            ScalarMode.DNA_HELICAL_MODULATION, ScalarMode.SINGLE_HELIX, ScalarMode.HELICAL_STEREO_ROTATION -> {
                val spin = 2.0 * PI * motion * t
                advance1(c)
                cos(p1 + spin).toFloat() * amp to cos(p1 - spin).toFloat() * amp
            }
            ScalarMode.DNA_CADUCEUS, ScalarMode.CADUCEUS_HELIX -> {
                val spin = 2.0 * PI * motion * t
                advance1(c)
                advance2((c * .5f).coerceAtLeast(250f))
                val l = (cos(p1 + spin) + .45 * cos(p2 - 2.0 * spin)).toFloat() * amp / 1.45f
                val r = (cos(p1 - spin) + .45 * cos(p2 + 2.0 * spin)).toFloat() * amp / 1.45f
                l to r
            }
            ScalarMode.DNA_LIGHT_SOUND -> {
                val pulse = (.5f + .5f * sin(2.0 * PI * rate * t).toFloat())
                advance1((c * .25f).coerceIn(180f, 5000f))
                val x = (voice * .62f + sin(p1).toFloat() * .38f) * (.25f + .75f * pulse)
                x to x
            }
            ScalarMode.DNA_FORWARD_REVERSE_STRAND -> {
                advance1(c)
                cos(p1).toFloat() * amp to cos(-p1).toFloat() * amp
            }
            ScalarMode.DNA_HARMONIC_ENCODING, ScalarMode.HARMONIC_RESONANCE -> harmonicStack(c, amp)
            ScalarMode.DNA_LONGITUDINAL, ScalarMode.LONGITUDINAL_WAVE_REP -> {
                advance1(c)
                val x = cos(p1).toFloat() * amp * env
                x to -x
            }
            ScalarMode.DNA_INFORMATION_FREQUENCY -> {
                val base = (180f + (voice + 1f) * 620f).coerceIn(120f, 1400f)
                val snapped = floatArrayOf(196f, 261.63f, 329.63f, 392f, 523.25f, 659.25f).minBy { abs(it - base) }
                advance1(snapped)
                val x = sin(p1).toFloat() * (.35f + .55f * abs(voice))
                x to x
            }

            ScalarMode.SCHUMANN_FUNDAMENTAL, ScalarMode.SCHUMANN_AM -> carrierAm(c, 7.83f, voice, depth)
            ScalarMode.SCHUMANN_MULTI, ScalarMode.EARTH_RESONANCE -> {
                val rs = floatArrayOf(7.83f, 14.3f, 20.8f, 27.3f, 33.8f)
                var m = 0f
                rs.forEach { m += sin(2.0 * PI * it * t).toFloat() }
                m /= rs.size
                advance1(c)
                val x = cos(p1).toFloat() * amp * (.62f + .30f * m)
                x to x
            }
            ScalarMode.SCHUMANN_NEAREST -> carrierAm(c, nearestSchumann(sourceHz), voice, depth)
            ScalarMode.SCHUMANN_HARMONIC_SUBHARMONIC -> {
                val m = (sin(2.0 * PI * 3.915 * t) + sin(2.0 * PI * 7.83 * t) + sin(2.0 * PI * 15.66 * t)).toFloat() / 3f
                advance1(c)
                val x = cos(p1).toFloat() * amp * (.65f + .3f * m)
                x to x
            }
            ScalarMode.SCHUMANN_SOURCE_SPECTRUM -> {
                val n = nearestSchumann(sourceHz)
                val m = (sin(2.0 * PI * n * t) + .5 * sin(4.0 * PI * n * t)).toFloat() / 1.5f
                advance1(c)
                val x = cos(p1).toFloat() * amp * (.64f + .31f * m)
                x to x
            }

            ScalarMode.RESONANT_OSCILLATOR -> resonantVoice(voice, rate)
            ScalarMode.STANDING_WAVES -> {
                advance1((c * .2f).coerceIn(100f, 5000f))
                val a = sin(p1).toFloat() * (.35f + .6f * abs(voice))
                a to -a
            }
            ScalarMode.PULSED_RESONANCE -> {
                val gate = if ((t * rate) % 1.0 < .18) 1f else .06f
                advance1((c * .25f).coerceIn(120f, 6000f))
                val x = sin(p1).toFloat() * amp * gate
                x to x
            }
            ScalarMode.HV_IMPULSE_SONIFICATION -> {
                val derivative = abs(voice - lastVoice)
                val impulse = if (derivative > .08f && ((frame / 8) % 2L == 0L)) (voice - lastVoice).coerceIn(-1f, 1f) else 0f
                impulse to -impulse
            }
            ScalarMode.WIRELESS_RESONANCE -> {
                advance1((c * .22f).coerceIn(150f, 5000f))
                advance2((c * .22f + rate).coerceIn(150f, 5000f))
                val l = (sin(p1) + .65 * sin(p2)).toFloat() * amp / 1.65f
                val r = (sin(p2) + .65 * sin(p1)).toFloat() * amp / 1.65f
                l to r
            }
            ScalarMode.MECHANICAL_RESONANCE -> resonantVoice(voice, (rate * 12f).coerceIn(30f, 900f))

            ScalarMode.TRI_NODE_120 -> triNode(c, voice, t)
            ScalarMode.QUAD_PHASE_ROTATION, ScalarMode.QUADRUPOLE_FOUR_NODE, ScalarMode.OSCILLATING_QUADRUPOLE_VISUAL -> quadNode(c, voice, t)
            ScalarMode.COUNTER_OSCILLATING_PAIRS -> {
                advance1(c)
                advance2((c + rate).coerceAtMost(maxF))
                val a = cos(p1).toFloat() * amp
                val b = sin(p2).toFloat() * amp
                (a + b) * .5f to (-a + b) * .5f
            }
            ScalarMode.EXTREME_FAST_MOD -> {
                val fast = (120f + abs(voice) * 2400f).coerceAtMost(5000f)
                val fm = sin(2.0 * PI * fast * t).toFloat()
                advance1((c + 220f * fm).coerceIn(500f, maxF))
                cos(p1).toFloat() * amp to cos(p1 + PI / 2).toFloat() * amp
            }
            ScalarMode.CHIRPED_OSCILLATION -> chirpPair(c, t, false)
            ScalarMode.FREQ_ACCELERATION -> chirpPair(c, t, false)
            ScalarMode.FREQ_DECELERATION -> chirpPair(c, t, true)
            ScalarMode.GRAV_WAVE_CHIRP -> {
                val period = 3.0
                val u = ((t % period) / period).coerceIn(0.0, 1.0)
                val curve = u * u * u
                val f = (120f + curve.toFloat() * 2600f)
                advance1(f)
                val x = sin(p1).toFloat() * (.15f + .78f * u.toFloat()) * (.5f + .5f * abs(voice))
                x to -x
            }
            ScalarMode.PAIS_AUDIO_STACK -> {
                val rot = 2.0 * PI * motion * t
                val fast = sin(2.0 * PI * (400.0 + 600.0 * abs(voice)) * t).toFloat()
                val pulse = if ((t * rate) % 1.0 < .35) 1f else .35f
                advance1((c + fast * 300f).coerceIn(500f, maxF))
                advance2((c * .5f).coerceIn(250f, maxF))
                val l = (cos(p1 + rot) + .3 * cos(p2 + 2 * rot)).toFloat() * amp * pulse / 1.3f
                val r = (cos(p1 - rot) + .3 * cos(p2 - 2 * rot)).toFloat() * amp * pulse / 1.3f
                l to r
            }

            ScalarMode.DOUBLE_HELIX, ScalarMode.COUNTER_ROTATING_HELIX -> {
                val spin = 2.0 * PI * motion * t
                advance1(c)
                advance2((c * .618f).coerceIn(300f, maxF))
                val l = (cos(p1 + spin) + .5 * cos(p2 - spin)).toFloat() * amp / 1.5f
                val r = (cos(p1 - spin) + .5 * cos(p2 + spin)).toFloat() * amp / 1.5f
                l to r
            }
            ScalarMode.CROSSING_FREQ_SWEEPS -> {
                val u = ((t * motion) % 1.0).toFloat()
                val lo = 180f
                val hi = min(4200f, maxF)
                val fl = lo + (hi - lo) * u
                val fr = hi - (hi - lo) * u
                advance1(fl); advance2(fr)
                sin(p1).toFloat() * amp to sin(p2).toFloat() * amp
            }
            ScalarMode.LEFT_ASC_RIGHT_DESC -> splitSweep(t, motion, true, amp, maxF)
            ScalarMode.LEFT_DESC_RIGHT_ASC -> splitSweep(t, motion, false, amp, maxF)
            ScalarMode.INTERTWINED_PHASE -> {
                val spin = sin(2.0 * PI * motion * t) * PI
                advance1(c); advance2((c * .75f).coerceAtLeast(300f))
                val l = (cos(p1 + spin) + .45 * sin(p2 - spin)).toFloat() * amp / 1.45f
                val r = (cos(p1 - spin) + .45 * sin(p2 + spin)).toFloat() * amp / 1.45f
                l to r
            }
            ScalarMode.HELICAL_SPECTRAL_MOVEMENT -> {
                val spin = 2.0 * PI * motion * t
                advance1((c * .35f).coerceIn(180f, maxF))
                advance2((c * .55f).coerceIn(240f, maxF))
                advance3((c * .82f).coerceIn(320f, maxF))
                val l = (sin(p1 + spin) + .6 * sin(p2 + 2 * spin) + .35 * sin(p3 + 3 * spin)).toFloat() * amp / 1.95f
                val r = (sin(p1 - spin) + .6 * sin(p2 - 2 * spin) + .35 * sin(p3 - 3 * spin)).toFloat() * amp / 1.95f
                l to r
            }
            ScalarMode.NESTED_HELICES -> {
                val spin = 2.0 * PI * motion * t
                val slowSpin = sin(2.0 * PI * rate * t) * PI
                advance1(c); advance2((c * .618f).coerceAtLeast(300f)); advance3((c * .382f).coerceAtLeast(180f))
                val l = (cos(p1 + spin) + .5 * cos(p2 - spin + slowSpin) + .25 * cos(p3 + 2 * slowSpin)).toFloat() * amp / 1.75f
                val r = (cos(p1 - spin) + .5 * cos(p2 + spin - slowSpin) + .25 * cos(p3 - 2 * slowSpin)).toFloat() * amp / 1.75f
                l to r
            }

            else -> voice to voice
        }
        lastVoice = voice
        wrap()
        return out
    }

    private fun harmonicStack(baseCarrier: Float, amp: Float): Pair<Float, Float> {
        val base = (baseCarrier * .12f).coerceIn(90f, 1800f)
        advance1(base)
        advance2(base * 2f)
        advance3(base * 3f)
        advance4(base * 5f)
        val x = (sin(p1) + .55 * sin(p2) + .34 * sin(p3) + .21 * sin(p4)).toFloat() * amp / 2.1f
        return x to x
    }

    private fun carrierAm(c: Float, modHz: Float, voice: Float, depth: Float): Pair<Float, Float> {
        val t = totalPhaseTime()
        val mod = 1f - depth * .5f + depth * .5f * (1f + sin(2.0 * PI * modHz * t).toFloat())
        advance1(c)
        val x = cos(p1).toFloat() * sqrt((1f + config.presence * voice).coerceIn(.02f, 1.98f)) * mod
        return x to x
    }

    private fun resonantVoice(voice: Float, hz: Float): Pair<Float, Float> {
        val f = hz.coerceIn(20f, min(3000f, sampleRate / 5f))
        val stiffness = (2.0 * PI * f / sampleRate).coerceAtMost(.45)
        val damping = .998 - .01 * config.elfDepth.coerceIn(0f, .98f)
        resonatorVelocity = ((resonatorVelocity + (voice - resonator) * stiffness.toFloat()) * damping.toFloat()).coerceIn(-2f, 2f)
        resonator = (resonator + resonatorVelocity * stiffness.toFloat()).coerceIn(-1.2f, 1.2f)
        return resonator to resonator
    }

    private fun triNode(c: Float, voice: Float, t: Double): Pair<Float, Float> {
        val rotation = 2.0 * PI * config.ditherRateHz.coerceIn(.02f, 5f) * t
        val convergence = config.presence.coerceIn(.05f, 1f)
        val freq = (c + voice * 220f).coerceIn(500f, sampleRate / 2f - 700f)
        advance1(freq)
        val offsets = doubleArrayOf(0.0, 2.0 * PI / 3.0, 4.0 * PI / 3.0)
        val n0 = cos(p1 + offsets[0] * (1.0 - convergence) + rotation).toFloat()
        val n1 = cos(p1 + offsets[1] * (1.0 - convergence) + rotation).toFloat()
        val n2 = cos(p1 + offsets[2] * (1.0 - convergence) + rotation).toFloat()
        val a = .35f + .6f * abs(voice)
        return ((n0 + .75f * n1 + .25f * n2) / 2f * a) to ((n0 + .25f * n1 + .75f * n2) / 2f * a)
    }

    private fun quadNode(c: Float, voice: Float, t: Double): Pair<Float, Float> {
        val rot = 2.0 * PI * config.ditherRateHz.coerceIn(.02f, 5f) * t
        val f = (c + voice * 180f).coerceIn(500f, sampleRate / 2f - 700f)
        advance1(f)
        val q0 = cos(p1 + rot).toFloat()
        val q1 = cos(p1 + PI / 2 + rot).toFloat()
        val q2 = cos(p1 + PI + rot).toFloat()
        val q3 = cos(p1 + 3 * PI / 2 + rot).toFloat()
        val a = .38f + .58f * abs(voice)
        return ((q0 + .7f * q1 + .25f * q2 + .1f * q3) / 2.05f * a) to ((q2 + .7f * q3 + .25f * q0 + .1f * q1) / 2.05f * a)
    }

    private fun chirpPair(c: Float, t: Double, reverse: Boolean): Pair<Float, Float> {
        val period = 2.5
        var u = ((t % period) / period).toFloat()
        if (reverse) u = 1f - u
        val span = min(3600f, c * .25f)
        val f = (c - span * .5f + span * u).coerceIn(500f, sampleRate / 2f - 700f)
        advance1(f)
        return cos(p1).toFloat() to cos(-p1).toFloat()
    }

    private fun splitSweep(t: Double, motion: Float, leftUp: Boolean, amp: Float, maxF: Float): Pair<Float, Float> {
        val u = ((t * motion) % 1.0).toFloat()
        val lo = 180f
        val hi = min(4200f, maxF)
        val up = lo + (hi - lo) * u
        val down = hi - (hi - lo) * u
        val fl = if (leftUp) up else down
        val fr = if (leftUp) down else up
        advance1(fl); advance2(fr)
        return sin(p1).toFloat() * amp to sin(p2).toFloat() * amp
    }

    private fun updateSourceTracker(v: Float) {
        framesSinceCross++
        if ((v >= 0f) != (lastVoice >= 0f) && abs(v - lastVoice) > .01f) {
            val estimate = sampleRate.toFloat() / (2f * max(1, framesSinceCross))
            if (estimate in 20f..5000f) sourceHz = sourceHz * .9f + estimate * .1f
            framesSinceCross = 0
        }
    }

    private fun nearestSchumann(hz: Float): Float {
        val values = floatArrayOf(7.83f, 14.3f, 20.8f, 27.3f, 33.8f, 39.9f, 45.9f)
        val folded = if (hz <= 50f) hz else {
            var x = hz
            while (x > 50f) x *= .5f
            x
        }
        return values.minBy { abs(it - folded) }
    }

    private fun totalPhaseTime(): Double = p4 / (2.0 * PI * 100.0).coerceAtLeast(1.0)

    private fun advance1(f: Float) { p1 += 2.0 * PI * f / sampleRate; p4 += 2.0 * PI * 100.0 / sampleRate }
    private fun advance2(f: Float) { p2 += 2.0 * PI * f / sampleRate }
    private fun advance3(f: Float) { p3 += 2.0 * PI * f / sampleRate }
    private fun advance4(f: Float) { p4 += 2.0 * PI * f / sampleRate }

    private fun wrap() {
        val tau = 2.0 * PI
        if (abs(p1) > tau * 64) p1 %= tau
        if (abs(p2) > tau * 64) p2 %= tau
        if (abs(p3) > tau * 64) p3 %= tau
        if (abs(p4) > tau * 64) p4 %= tau
    }
}

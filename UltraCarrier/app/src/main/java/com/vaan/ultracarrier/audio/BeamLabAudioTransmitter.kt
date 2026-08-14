package com.vaan.ultracarrier.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

enum class BeamLabMode(val label: String, val description: String) {
    ELF_BEAM("ELF Beam", "ELF-rate envelope carried inside a directional ultrasonic parametric beam. The beam provides privacy; the low-rate envelope rides inside it."),
    DUAL_PUMP_ELF("Dual-Pump ELF Beam", "Two nearby ultrasonic pump frequencies are separated by the selected ELF rate. Their nonlinear overlap produces that difference-frequency pattern while preserving the high-frequency beam geometry."),
    RUSSIAN_SSB_BEAM("Russian SSB Beam", "Precompensated envelope plus quadrature suppressed-sideband ultrasonic modulation inspired by Russian parametric-loudspeaker patent RU2569914C2."),
    US_VIRTUAL_SPEAKER("US Virtual Speaker", "Steered parametric audio intended to reflect from a wall or ceiling so the apparent source forms at the reflection point."),
    US_LOCALIZED_SPOT("US Localized Spot", "Two complementary ultrasonic outputs are designed to create stronger audible reconstruction where their beams overlap."),
    SETI_DRIFT_BEAM("SETI Drift Beam", "A slowly drifting ultrasonic carrier inspired by frequency-drift signal searches, while the selected voice remains inside the beam."),
    CHIRP_SPREAD_BEAM("Chirp Spread Beam", "A repeating ultrasonic chirp carries the voice through the directional path, inspired by chirp-spread-spectrum communication."),
    CROSSED_BEAM_FOCUS("Crossed-Beam Focus", "Left output is an ultrasonic carrier and right output is a suppressed-carrier speech sideband. Aim two emitters so audible reconstruction is strongest where the beams overlap."),
    BRIGHT_DARK_BUBBLE("Bright / Dark Bubble", "Two-element beamforming solves for a bright target direction and a simultaneous acoustic null direction."),
    BEAM_LOCK("Beam Lock", "A narrow ultrasonic beam gently dithers around the target angle to reduce tiny aim errors while staying centered on one listening zone."),
    FREY_CODEC_ACOUSTIC("Frey-Codec Acoustic", "Speech de-emphasis, bias/root predistortion and suppressed-carrier modulation inspired by RF-hearing patents, but rendered on an acoustic ultrasonic carrier."),
    SWEET_SPOT_XTC("Sweet-Spot XTC", "Experimental two-speaker crosstalk cancellation tuned to one listening position so the spatial effect collapses rapidly away from the sweet spot.")
}

data class BeamLabReport(
    val mode: BeamLabMode,
    val sampleRate: Int,
    val carrierHz: Float,
    val routeName: String,
    val elfRateHz: Float,
    val targetAngleDeg: Float,
    val nullAngleDeg: Float,
    val phaseLeftDeg: Float,
    val phaseRightDeg: Float
)

class BeamLabAudioTransmitter {
    private val stopped = AtomicBoolean(true)
    @Volatile private var activeTrack: AudioTrack? = null

    fun stop() {
        stopped.set(true)
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
        }
    }

    fun transmit(
        pcm: PcmAudio,
        requestedSampleRate: Int,
        requestedCarrierHz: Float,
        mode: BeamLabMode,
        listeningPath: ListeningPath,
        presence: Float,
        elfRateHz: Float,
        elfDepth: Float,
        targetAngleDeg: Float,
        nullAngleDeg: Float,
        spacingMm: Float,
        beamDitherDeg: Float,
        ditherRateHz: Float,
        chirpSweepHz: Float = 4_000f,
        chirpPeriodMs: Float = 20f,
        speakerSeparationCm: Float,
        listenerDistanceCm: Float,
        headWidthCm: Float,
        preferredDevice: AudioDeviceInfo?,
        onStarted: (BeamLabReport) -> Unit,
        onWaveform: (FloatArray) -> Unit
    ) {
        stop()
        stopped.set(false)

        val (track, sampleRate) = createBestTrack(requestedSampleRate, preferredDevice, true)
        activeTrack = track
        val carrier = if (mode == BeamLabMode.SWEET_SPOT_XTC) 0f else DspMath.clampCarrier(sampleRate, requestedCarrierHz)
        val narrowSpeech = mode == BeamLabMode.FREY_CODEC_ACOUSTIC || mode == BeamLabMode.RUSSIAN_SSB_BEAM
        val lowPass = DspMath.LowPass(sampleRate, if (narrowSpeech) 2800f else 3800f)
        val highPass = DspMath.HighPass(sampleRate, 180f)
        val preComp1 = DspMath.LowPass(sampleRate, 1600f)
        val preComp2 = DspMath.LowPass(sampleRate, 1600f)
        val sourceStep = pcm.sampleRate.toDouble() / sampleRate
        val totalFrames = (pcm.samples.size / sourceStep).toLong().coerceAtLeast(1L)
        val block = FloatArray(BLOCK_FRAMES * 2)
        val monitor = FloatArray(BLOCK_FRAMES)
        val fadeFrames = max(1, (sampleRate * 0.08f).roundToInt())
        val safePresence = presence.coerceIn(0.05f, 1f)
        val safeElfRate = elfRateHz.coerceIn(0.5f, 40f)
        val safeElfDepth = elfDepth.coerceIn(0f, 0.95f)
        val safeTarget = targetAngleDeg.coerceIn(-60f, 60f)
        val safeNull = nullAngleDeg.coerceIn(-75f, 75f)
        val safeSpacing = spacingMm.coerceIn(1f, 50f) / 1000.0
        val safeDither = beamDitherDeg.coerceIn(0f, 12f)
        val safeDitherRate = ditherRateHz.coerceIn(0.03f, 3f)
        val safeSweep = chirpSweepHz.coerceIn(50f, 12_000f)
        val safePeriodMs = chirpPeriodMs.coerceIn(2f, 2000f)
        val outputGain = when (listeningPath) {
            ListeningPath.HEADPHONES -> 0.12f + 0.22f * safePresence
            ListeningPath.BONE_CONDUCTION -> 0.14f + 0.24f * safePresence
            ListeningPath.PHONE_SPEAKER -> 0.18f + 0.28f * safePresence
            ListeningPath.EXTERNAL_ARRAY -> 0.18f + 0.40f * safePresence
        }
        val hilbert = HilbertTransformer()
        val iq = FloatArray(2)
        var sourcePos = 0.0
        var outputFrame = 0L
        var phase = 0.0
        var phase2 = 0.0
        var waveformCounter = 0

        val staticWeights = if (mode == BeamLabMode.BRIGHT_DARK_BUBBLE && carrier > 0f) {
            solveBrightDarkWeights(carrier.toDouble(), safeSpacing, safeTarget.toDouble(), safeNull.toDouble())
        } else null
        val xtc = if (mode == BeamLabMode.SWEET_SPOT_XTC) XtcCanceller(sampleRate, speakerSeparationCm, listenerDistanceCm, headWidthCm) else null

        try {
            track.play()
            val routeName = track.routedDevice?.productName?.toString().orEmpty()
                .ifBlank { preferredDevice?.productName?.toString().orEmpty() }
                .ifBlank { "system-selected output" }
            val staticPhase = if (carrier > 0f) steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble()) else 0.0
            onStarted(BeamLabReport(mode, sampleRate, carrier, routeName, safeElfRate, safeTarget, safeNull,
                staticWeights?.first?.phaseDegrees?.toFloat() ?: 0f,
                staticWeights?.second?.phaseDegrees?.toFloat() ?: (staticPhase * 180.0 / PI).toFloat()))

            while (!stopped.get() && outputFrame < totalFrames) {
                val frames = min(BLOCK_FRAMES.toLong(), totalFrames - outputFrame).toInt()
                for (i in 0 until frames) {
                    val absoluteFrame = outputFrame + i
                    val raw = DspMath.interpolate(pcm.samples, sourcePos)
                    val voice = tanh((highPass.process(lowPass.process(raw)) * 2.3f).toDouble()).toFloat()
                    val fade = min(1f, min(absoluteFrame.toFloat() / fadeFrames, (totalFrames - absoluteFrame).toFloat() / fadeFrames)).coerceAtLeast(0f)
                    val elf = (1f - safeElfDepth * 0.5f) + safeElfDepth * 0.5f * (1f + sin(2.0 * PI * safeElfRate * absoluteFrame / sampleRate).toFloat())
                    val periodFrames = max(1.0, sampleRate * safePeriodMs / 1000.0)
                    val cycle = (absoluteFrame % periodFrames.toLong()).toDouble() / periodFrames
                    val triangle = if (cycle < 0.5) cycle * 4.0 - 1.0 else 3.0 - cycle * 4.0

                    var left: Float
                    var right: Float
                    when (mode) {
                        BeamLabMode.ELF_BEAM -> {
                            val envelope = sqrt((1f + safePresence * voice * elf).coerceIn(0.02f, 1.98f))
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble())
                            left = cos(phase).toFloat() * envelope
                            right = cos(phase + steer).toFloat() * envelope
                            phase += 2.0 * PI * carrier / sampleRate
                        }
                        BeamLabMode.DUAL_PUMP_ELF -> {
                            val f1 = (carrier - safeElfRate * 0.5f).coerceAtLeast(1_000f)
                            val f2 = (carrier + safeElfRate * 0.5f).coerceAtMost(sampleRate / 2f - 500f)
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            left = cos(phase + steeringPhase(f1.toDouble(), safeSpacing, safeTarget.toDouble()) * 0.5).toFloat() * envelope
                            right = cos(phase2 + steeringPhase(f2.toDouble(), safeSpacing, safeTarget.toDouble()) * 0.5).toFloat() * envelope
                            phase += 2.0 * PI * f1 / sampleRate
                            phase2 += 2.0 * PI * f2 / sampleRate
                        }
                        BeamLabMode.RUSSIAN_SSB_BEAM -> {
                            val smoothed = preComp2.process(preComp1.process(voice))
                            val precomp = (sqrt((0.56f + 0.40f * safePresence * smoothed).coerceIn(0.02f, 1.20f)) - sqrt(0.56f)).coerceIn(-1f, 1f)
                            hilbert.process(precomp, iq)
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble())
                            left = iq[0] * cos(phase).toFloat() - iq[1] * sin(phase).toFloat()
                            right = iq[0] * cos(phase + steer).toFloat() - iq[1] * sin(phase + steer).toFloat()
                            phase += 2.0 * PI * carrier / sampleRate
                        }
                        BeamLabMode.US_VIRTUAL_SPEAKER -> {
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble())
                            left = cos(phase).toFloat() * envelope
                            right = cos(phase + steer).toFloat() * envelope
                            phase += 2.0 * PI * carrier / sampleRate
                        }
                        BeamLabMode.US_LOCALIZED_SPOT -> {
                            hilbert.process(voice * safePresence, iq)
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble())
                            left = iq[0] * cos(phase).toFloat() - iq[1] * sin(phase).toFloat()
                            right = iq[0] * cos(phase + steer).toFloat() + iq[1] * sin(phase + steer).toFloat()
                            phase += 2.0 * PI * carrier / sampleRate
                        }
                        BeamLabMode.SETI_DRIFT_BEAM -> {
                            val driftHz = safeSweep * 0.5f * sin(2.0 * PI * absoluteFrame / periodFrames).toFloat()
                            val f = (carrier + driftHz).coerceIn(1_000f, sampleRate / 2f - 600f)
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            val steer = steeringPhase(f.toDouble(), safeSpacing, safeTarget.toDouble())
                            left = cos(phase).toFloat() * envelope
                            right = cos(phase + steer).toFloat() * envelope
                            phase += 2.0 * PI * f / sampleRate
                        }
                        BeamLabMode.CHIRP_SPREAD_BEAM -> {
                            val f = (carrier + safeSweep * 0.5f * triangle.toFloat()).coerceIn(1_000f, sampleRate / 2f - 600f)
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            val steer = steeringPhase(f.toDouble(), safeSpacing, safeTarget.toDouble())
                            left = cos(phase).toFloat() * envelope
                            right = cos(phase + steer).toFloat() * envelope
                            phase += 2.0 * PI * f / sampleRate
                        }
                        BeamLabMode.CROSSED_BEAM_FOCUS -> {
                            hilbert.process(voice * safePresence, iq)
                            left = cos(phase).toFloat()
                            right = iq[0] * cos(phase).toFloat() - iq[1] * sin(phase).toFloat()
                            phase += 2.0 * PI * carrier / sampleRate
                        }
                        BeamLabMode.BRIGHT_DARK_BUBBLE -> {
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            val w = staticWeights!!
                            left = w.first.magnitude.toFloat() * cos(phase + w.first.phase).toFloat() * envelope
                            right = w.second.magnitude.toFloat() * cos(phase + w.second.phase).toFloat() * envelope
                            phase += 2.0 * PI * carrier / sampleRate
                        }
                        BeamLabMode.BEAM_LOCK -> {
                            val dither = safeDither * sin(2.0 * PI * safeDitherRate * absoluteFrame / sampleRate).toFloat()
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, (safeTarget + dither).toDouble())
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            left = cos(phase).toFloat() * envelope
                            right = cos(phase + steer).toFloat() * envelope
                            phase += 2.0 * PI * carrier / sampleRate
                        }
                        BeamLabMode.FREY_CODEC_ACOUSTIC -> {
                            val emphasized = preComp2.process(preComp1.process(voice))
                            val centered = (sqrt((0.55f + emphasized * 0.42f * safePresence).coerceIn(0.02f, 1.20f)) - sqrt(0.55f)).coerceIn(-1f, 1f)
                            hilbert.process(centered, iq)
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble())
                            left = iq[0] * cos(phase).toFloat() - iq[1] * sin(phase).toFloat()
                            right = iq[0] * cos(phase + steer).toFloat() - iq[1] * sin(phase + steer).toFloat()
                            phase += 2.0 * PI * carrier / sampleRate
                        }
                        BeamLabMode.SWEET_SPOT_XTC -> {
                            val pair = xtc!!.process(voice * safePresence)
                            left = pair.first
                            right = pair.second
                        }
                    }
                    if (phase >= 2.0 * PI || phase <= -2.0 * PI) phase %= 2.0 * PI
                    if (phase2 >= 2.0 * PI || phase2 <= -2.0 * PI) phase2 %= 2.0 * PI
                    block[i * 2] = (left * outputGain * fade).coerceIn(-0.96f, 0.96f)
                    block[i * 2 + 1] = (right * outputGain * fade).coerceIn(-0.96f, 0.96f)
                    monitor[i] = voice
                    sourcePos += sourceStep
                }
                var written = 0
                val sampleCount = frames * 2
                while (written < sampleCount) {
                    val n = track.write(block, written, sampleCount - written, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) error("AudioTrack write failed with code $n")
                    written += n
                }
                outputFrame += frames
                waveformCounter++
                if (waveformCounter % 2 == 0) onWaveform(decimate(monitor, frames, 512))
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.flush() }
            track.release()
            activeTrack = null
            stopped.set(true)
        }
    }

    private fun steeringPhase(carrier: Double, spacingM: Double, angleDeg: Double): Double =
        normalize(2.0 * PI * carrier * spacingM * sin(angleDeg * PI / 180.0) / SPEED_OF_SOUND)

    private data class Weight(val magnitude: Double, val phase: Double) { val phaseDegrees: Double get() = phase * 180.0 / PI }

    private fun solveBrightDarkWeights(carrier: Double, spacingM: Double, targetDeg: Double, nullDeg: Double): Pair<Weight, Weight> {
        val at = steeringPhase(carrier, spacingM, targetDeg)
        val an = steeringPhase(carrier, spacingM, nullDeg)
        val et = Complex(cos(-at), sin(-at))
        val en = Complex(cos(-an), sin(-an))
        val denom = et - en
        val w2 = Complex(1.0, 0.0) / denom
        val w1 = (w2 * en) * -1.0
        val peak = max(w1.mag, w2.mag).coerceAtLeast(1.0)
        return Weight(w1.mag / peak, w1.phase) to Weight(w2.mag / peak, w2.phase)
    }

    private data class Complex(val re: Double, val im: Double) {
        val mag: Double get() = sqrt(re * re + im * im)
        val phase: Double get() = kotlin.math.atan2(im, re)
        operator fun minus(o: Complex) = Complex(re - o.re, im - o.im)
        operator fun times(o: Complex) = Complex(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun times(v: Double) = Complex(re * v, im * v)
        operator fun div(o: Complex): Complex {
            val d = o.re * o.re + o.im * o.im
            return Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }
    }

    private class XtcCanceller(sampleRate: Int, speakerSeparationCm: Float, listenerDistanceCm: Float, headWidthCm: Float) {
        private val buffer: FloatArray
        private var write = 0
        private val delaySamples: Int
        private val crossGain: Float
        init {
            val s = speakerSeparationCm.coerceIn(4f, 200f) / 100f
            val z = listenerDistanceCm.coerceIn(10f, 400f) / 100f
            val h = headWidthCm.coerceIn(10f, 24f) / 100f
            val direct = sqrt(z * z + ((s - h) * 0.5f) * ((s - h) * 0.5f))
            val cross = sqrt(z * z + ((s + h) * 0.5f) * ((s + h) * 0.5f))
            delaySamples = max(1, (sampleRate * ((cross - direct) / SPEED_OF_SOUND.toFloat()).coerceAtLeast(0f)).roundToInt())
            buffer = FloatArray(delaySamples + 8)
            crossGain = (direct / cross).coerceIn(0.25f, 0.92f)
        }
        fun process(v: Float): Pair<Float, Float> {
            val delayed = buffer[(write - delaySamples + buffer.size) % buffer.size]
            buffer[write] = v
            write = (write + 1) % buffer.size
            val x = v - delayed * crossGain
            return x to x
        }
    }

    private class HilbertTransformer(private val taps: Int = 63) {
        private val coefficients = FloatArray(taps)
        private val buffer = FloatArray(taps)
        private var writeIndex = 0
        private val center = (taps - 1) / 2
        init {
            for (i in 0 until taps) {
                val n = i - center
                val ideal = if (n != 0 && abs(n) % 2 == 1) 2.0 / (PI * n) else 0.0
                coefficients[i] = (ideal * (0.54 - 0.46 * cos(2.0 * PI * i / (taps - 1)))).toFloat()
            }
        }
        fun process(input: Float, output: FloatArray) {
            buffer[writeIndex] = input
            var q = 0f
            for (i in 0 until taps) q += coefficients[i] * buffer[(writeIndex - i + taps) % taps]
            output[0] = buffer[(writeIndex - center + taps) % taps]
            output[1] = q
            writeIndex = (writeIndex + 1) % taps
        }
    }

    private fun createBestTrack(requestedSampleRate: Int, preferredDevice: AudioDeviceInfo?, stereo: Boolean): Pair<AudioTrack, Int> {
        val maximum = requestedSampleRate.coerceIn(44_100, 192_000)
        val candidates = listOf(maximum, 192_000, 176_400, 96_000, 88_200, 48_000, 44_100).distinct().filter { it <= maximum }
        var last: Throwable? = null
        for (rate in candidates) {
            try {
                val track = buildTrack(rate, stereo)
                if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); continue }
                if (preferredDevice != null) track.setPreferredDevice(preferredDevice)
                return track to track.sampleRate
            } catch (t: Throwable) { last = t }
        }
        throw IllegalStateException("No usable stereo PCM-float AudioTrack could be opened", last)
    }

    private fun buildTrack(sampleRate: Int, stereo: Boolean): AudioTrack {
        val mask = if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val channels = if (stereo) 2 else 1
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        require(minBytes > 0) { "AudioTrack rejected $sampleRate Hz" }
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBytes, BLOCK_FRAMES * channels * 4 * 4))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun normalize(v: Double): Double {
        var p = v % (2.0 * PI)
        if (p > PI) p -= 2.0 * PI
        if (p < -PI) p += 2.0 * PI
        return p
    }

    private fun decimate(input: FloatArray, count: Int, target: Int): FloatArray {
        if (count <= target) return input.copyOf(count)
        val out = FloatArray(target)
        val step = count.toDouble() / target
        for (i in out.indices) out[i] = input[(i * step).toInt().coerceAtMost(count - 1)]
        return out
    }

    companion object {
        private const val BLOCK_FRAMES = 4096
        private const val SPEED_OF_SOUND = 343.0
    }
}

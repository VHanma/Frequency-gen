package com.vaan.ultracarrier.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

enum class BeamLabMode(val label: String, val description: String) {
    ELF_BEAM("ELF Beam", "ELF-rate envelope riding inside a directional ultrasonic parametric carrier."),
    DUAL_PUMP_ELF("RU Dual-Pump ELF", "Two nearby ultrasonic pumps separated by the selected ELF rate."),
    RUSSIAN_SSB_BEAM("RU SSB Beam", "Precompensated suppressed-sideband ultrasonic modulation inspired by RU2569914C2."),
    SOVIET_PULSE_BEAM("RU/Soviet Pulse Beam", "Low-rate pulse gating carried inside the directional ultrasonic beam."),
    PSYCHOTRONIC_NESTED_BEAM("RU Fringe Nested Beam", "Speculative slow + 40 Hz nested modulation inside the directional carrier."),
    SMIRNOV_MASK_BEAM("RU Mask Beam", "Speech and shaped masking noise encoded together inside the beam."),
    US_VIRTUAL_SPEAKER("US Virtual Speaker", "Steered parametric audio intended to place the apparent source at a reflection point."),
    US_LOCALIZED_SPOT("US Localized Spot", "Complementary ultrasonic outputs designed for stronger reconstruction where beams overlap."),
    US_QUIET_ZONE("US Quiet-Zone Beam", "Target reinforcement plus a chosen null direction, inspired by American focused-parametric patents."),
    US_VIRTUAL_HEADSET("US Virtual Headset", "Separate left/right ultrasonic channels steered toward opposite ears."),
    FREY_CODEC_ACOUSTIC("USAF/Frey Codec Acoustic", "U.S. Air Force-inspired speech predistortion translated to an acoustic ultrasonic carrier."),
    US_PULSE_FM_ANALOG("US Pulse-FM Acoustic", "Speech amplitude controls burst repetition density on the ultrasonic carrier."),
    SETI_DRIFT_BEAM("SETI Drift Beam", "Slow carrier drift inspired by narrowband SETI searches while keeping the voice encoded in the beam."),
    CHIRP_SPREAD_BEAM("Chirp Spread Beam", "Repeating ultrasonic chirp carrying the voice through the directional path."),
    CROSSED_BEAM_FOCUS("Crossed-Beam Focus", "Reference carrier and suppressed-carrier speech sideband intended for two independently aimed emitters."),
    BRIGHT_DARK_BUBBLE("Bright / Dark Bubble", "Two-element beamforming reinforces one direction while suppressing another."),
    BEAM_LOCK("Beam Lock", "Steering gently dithers around the target to tolerate small aim errors."),
    SWEET_SPOT_XTC("Sweet-Spot XTC", "Two-speaker crosstalk cancellation tuned to one listening geometry."),
    ALIEN_TIME_REVERSAL("Alien Time-Reversal Seed", "Two-channel phase-conjugate focusing seed inspired by time-reversal acoustics."),
    ALIEN_HOLOGRAM_FOCUS("Alien Hologram Focus", "Multi-frequency phase superposition inspired by acoustic holography and metasurfaces."),
    ALIEN_VORTEX_OAM("Alien OAM Vortex", "Quadrature helical-phase seed inspired by acoustic orbital-angular-momentum arrays."),
    ALIEN_FREQUENCY_KEY("Alien Frequency-Key Focus", "Several ultrasonic carriers use different phase keys so the intended field is richest at overlap."),
    ALIEN_BESSEL_SELF_HEAL("Alien Bessel Self-Heal", "Dual-cone phase seed inspired by Bessel-like self-reconstructing acoustic beams."),
    ALIEN_QUIET_SHELL("Alien Quiet Shell", "Alternating side nulls explore a bright-core / dark-shell personal audio zone."),
    ALIEN_DUAL_EAR_FIELD("Alien Dual-Ear Field", "Independent ear-target phase coding for a virtual-headset style listening field.")
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
        val narrowSpeech = mode in setOf(
            BeamLabMode.FREY_CODEC_ACOUSTIC,
            BeamLabMode.RUSSIAN_SSB_BEAM,
            BeamLabMode.US_PULSE_FM_ANALOG,
            BeamLabMode.SOVIET_PULSE_BEAM,
            BeamLabMode.PSYCHOTRONIC_NESTED_BEAM
        )
        val lowPass = DspMath.LowPass(sampleRate, if (narrowSpeech) 2_800f else 3_800f)
        val highPass = DspMath.HighPass(sampleRate, 180f)
        val preComp1 = DspMath.LowPass(sampleRate, 1_600f)
        val preComp2 = DspMath.LowPass(sampleRate, 1_600f)
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
        val safePeriodMs = chirpPeriodMs.coerceIn(2f, 2_000f)
        val safeDistanceM = listenerDistanceCm.coerceIn(10f, 400f) / 100f
        val safeHeadM = headWidthCm.coerceIn(10f, 24f) / 100f
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
        var phase3 = 0.0
        var pulsePhase = 0.0
        var waveformCounter = 0
        var noiseState = 0x5A17C3E1
        var noiseSmooth = 0f

        val staticWeights = if (mode in setOf(BeamLabMode.BRIGHT_DARK_BUBBLE, BeamLabMode.US_QUIET_ZONE) && carrier > 0f) {
            solveBrightDarkWeights(carrier.toDouble(), safeSpacing, safeTarget.toDouble(), safeNull.toDouble())
        } else null
        val xtc = if (mode == BeamLabMode.SWEET_SPOT_XTC) XtcCanceller(sampleRate, speakerSeparationCm, listenerDistanceCm, headWidthCm) else null

        try {
            track.play()
            val routeName = track.routedDevice?.productName?.toString().orEmpty()
                .ifBlank { preferredDevice?.productName?.toString().orEmpty() }
                .ifBlank { "system-selected output" }
            val staticPhase = if (carrier > 0f) steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble()) else 0.0
            onStarted(
                BeamLabReport(
                    mode = mode,
                    sampleRate = sampleRate,
                    carrierHz = carrier,
                    routeName = routeName,
                    elfRateHz = safeElfRate,
                    targetAngleDeg = safeTarget,
                    nullAngleDeg = safeNull,
                    phaseLeftDeg = staticWeights?.first?.phaseDegrees?.toFloat() ?: 0f,
                    phaseRightDeg = staticWeights?.second?.phaseDegrees?.toFloat() ?: (staticPhase * 180.0 / PI).toFloat()
                )
            )

            while (!stopped.get() && outputFrame < totalFrames) {
                val frames = min(BLOCK_FRAMES.toLong(), totalFrames - outputFrame).toInt()
                for (i in 0 until frames) {
                    val absoluteFrame = outputFrame + i
                    val raw = DspMath.interpolate(pcm.samples, sourcePos)
                    val voice = tanh((highPass.process(lowPass.process(raw)) * 2.3f).toDouble()).toFloat()
                    val fade = min(1f, min(absoluteFrame.toFloat() / fadeFrames, (totalFrames - absoluteFrame).toFloat() / fadeFrames)).coerceAtLeast(0f)
                    val elf = (1f - safeElfDepth * 0.5f) + safeElfDepth * 0.5f *
                        (1f + sin(2.0 * PI * safeElfRate * absoluteFrame / sampleRate).toFloat())
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

                        BeamLabMode.SOVIET_PULSE_BEAM -> {
                            val pulse = if (sin(2.0 * PI * safeElfRate * absoluteFrame / sampleRate) > 0.15) 1f else 0.05f
                            val envelope = sqrt((1f + safePresence * voice * pulse).coerceIn(0.02f, 1.98f))
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble())
                            left = cos(phase).toFloat() * envelope * pulse
                            right = cos(phase + steer).toFloat() * envelope * pulse
                            phase += 2.0 * PI * carrier / sampleRate
                        }

                        BeamLabMode.PSYCHOTRONIC_NESTED_BEAM -> {
                            val slow = 0.62f + 0.38f * sin(2.0 * PI * safeElfRate * absoluteFrame / sampleRate).toFloat()
                            val fast = 0.72f + 0.28f * sin(2.0 * PI * 40.0 * absoluteFrame / sampleRate).toFloat()
                            val envelope = sqrt((1f + safePresence * voice * slow * fast).coerceIn(0.02f, 1.98f))
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble())
                            left = cos(phase).toFloat() * envelope
                            right = cos(phase + steer).toFloat() * envelope
                            phase += 2.0 * PI * carrier / sampleRate
                        }

                        BeamLabMode.SMIRNOV_MASK_BEAM -> {
                            noiseState = noiseState * 1664525 + 1013904223
                            val white = (((noiseState ushr 8) and 0x00FFFFFF) / 8_388_607.5f) - 1f
                            noiseSmooth += (white - noiseSmooth) * 0.08f
                            val masked = (voice * 0.88f + noiseSmooth * 0.10f * safePresence).coerceIn(-1f, 1f)
                            val envelope = sqrt((1f + safePresence * masked).coerceIn(0.02f, 1.98f))
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble())
                            left = cos(phase).toFloat() * envelope
                            right = cos(phase + steer).toFloat() * envelope
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

                        BeamLabMode.US_QUIET_ZONE,
                        BeamLabMode.BRIGHT_DARK_BUBBLE -> {
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            val w = staticWeights!!
                            left = w.first.magnitude.toFloat() * cos(phase + w.first.phase).toFloat() * envelope
                            right = w.second.magnitude.toFloat() * cos(phase + w.second.phase).toFloat() * envelope
                            phase += 2.0 * PI * carrier / sampleRate
                        }

                        BeamLabMode.US_VIRTUAL_HEADSET,
                        BeamLabMode.ALIEN_DUAL_EAR_FIELD -> {
                            val earAngle = (atan(((safeHeadM * 0.5f) / safeDistanceM).toDouble()) * 180.0 / PI).toFloat().coerceIn(1f, 25f)
                            val leftAngle = (safeTarget - earAngle).coerceIn(-70f, 70f)
                            val rightAngle = (safeTarget + earAngle).coerceIn(-70f, 70f)
                            val lSteer = steeringPhase(carrier.toDouble(), safeSpacing, leftAngle.toDouble())
                            val rSteer = steeringPhase(carrier.toDouble(), safeSpacing, rightAngle.toDouble())
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            val extra = if (mode == BeamLabMode.ALIEN_DUAL_EAR_FIELD) PI / 6.0 else 0.0
                            left = cos(phase + lSteer - extra).toFloat() * envelope
                            right = cos(phase + rSteer + extra).toFloat() * envelope
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

                        BeamLabMode.US_PULSE_FM_ANALOG -> {
                            val densityHz = (1_000f + 8_000f * abs(voice)).coerceAtMost(sampleRate * 0.20f)
                            pulsePhase += 2.0 * PI * densityHz / sampleRate
                            if (pulsePhase >= 2.0 * PI) pulsePhase %= 2.0 * PI
                            val gate = if (sin(pulsePhase) > 0.82) 1f else 0f
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble())
                            val sign = if (voice >= 0f) 1f else -1f
                            left = cos(phase).toFloat() * gate * sign
                            right = cos(phase + steer).toFloat() * gate * sign
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

                        BeamLabMode.BEAM_LOCK -> {
                            val dither = safeDither * sin(2.0 * PI * safeDitherRate * absoluteFrame / sampleRate).toFloat()
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, (safeTarget + dither).toDouble())
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            left = cos(phase).toFloat() * envelope
                            right = cos(phase + steer).toFloat() * envelope
                            phase += 2.0 * PI * carrier / sampleRate
                        }

                        BeamLabMode.SWEET_SPOT_XTC -> {
                            val pair = xtc!!.process(voice * safePresence)
                            left = pair.first
                            right = pair.second
                        }

                        BeamLabMode.ALIEN_TIME_REVERSAL -> {
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble())
                            left = cos(phase - steer * 0.5).toFloat() * envelope
                            right = cos(phase + steer * 0.5).toFloat() * envelope
                            phase += 2.0 * PI * carrier / sampleRate
                        }

                        BeamLabMode.ALIEN_HOLOGRAM_FOCUS -> {
                            val f1 = (carrier - 900f).coerceAtLeast(1_000f)
                            val f2 = carrier
                            val f3 = (carrier + 900f).coerceAtMost(sampleRate / 2f - 500f)
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            val s1 = steeringPhase(f1.toDouble(), safeSpacing, (safeTarget - 5f).toDouble())
                            val s2 = steeringPhase(f2.toDouble(), safeSpacing, safeTarget.toDouble())
                            val s3 = steeringPhase(f3.toDouble(), safeSpacing, (safeTarget + 5f).toDouble())
                            left = ((cos(phase - s1) + cos(phase2 - s2) + cos(phase3 - s3)) / 3.0).toFloat() * envelope
                            right = ((cos(phase + s1) + cos(phase2 + s2) + cos(phase3 + s3)) / 3.0).toFloat() * envelope
                            phase += 2.0 * PI * f1 / sampleRate
                            phase2 += 2.0 * PI * f2 / sampleRate
                            phase3 += 2.0 * PI * f3 / sampleRate
                        }

                        BeamLabMode.ALIEN_VORTEX_OAM -> {
                            val steer = steeringPhase(carrier.toDouble(), safeSpacing, safeTarget.toDouble())
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            left = cos(phase - steer).toFloat() * envelope
                            right = sin(phase + steer).toFloat() * envelope
                            phase += 2.0 * PI * carrier / sampleRate
                        }

                        BeamLabMode.ALIEN_FREQUENCY_KEY -> {
                            val f1 = (carrier - 1_200f).coerceAtLeast(1_000f)
                            val f2 = carrier
                            val f3 = (carrier + 1_200f).coerceAtMost(sampleRate / 2f - 500f)
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            val k1 = steeringPhase(f1.toDouble(), safeSpacing, (safeTarget - 7f).toDouble())
                            val k2 = steeringPhase(f2.toDouble(), safeSpacing, safeTarget.toDouble())
                            val k3 = steeringPhase(f3.toDouble(), safeSpacing, (safeTarget + 7f).toDouble())
                            left = ((cos(phase + k1) + cos(phase2 + k2 + 2.0 * PI / 3.0) + cos(phase3 + k3 + 4.0 * PI / 3.0)) / 3.0).toFloat() * envelope
                            right = ((cos(phase - k1) + cos(phase2 - k2 + 2.0 * PI / 3.0) + cos(phase3 - k3 + 4.0 * PI / 3.0)) / 3.0).toFloat() * envelope
                            phase += 2.0 * PI * f1 / sampleRate
                            phase2 += 2.0 * PI * f2 / sampleRate
                            phase3 += 2.0 * PI * f3 / sampleRate
                        }

                        BeamLabMode.ALIEN_BESSEL_SELF_HEAL -> {
                            val cone = 12f
                            val sA = steeringPhase(carrier.toDouble(), safeSpacing, (safeTarget - cone).toDouble())
                            val sB = steeringPhase(carrier.toDouble(), safeSpacing, (safeTarget + cone).toDouble())
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            left = ((cos(phase - sA) + cos(phase - sB)) * 0.5).toFloat() * envelope
                            right = ((cos(phase + sA) + cos(phase + sB)) * 0.5).toFloat() * envelope
                            phase += 2.0 * PI * carrier / sampleRate
                        }

                        BeamLabMode.ALIEN_QUIET_SHELL -> {
                            val side = if (sin(2.0 * PI * safeDitherRate * absoluteFrame / sampleRate) >= 0.0) 1.0 else -1.0
                            val shellNull = (safeTarget + side.toFloat() * 18f).coerceIn(-75f, 75f)
                            val w = solveBrightDarkWeights(carrier.toDouble(), safeSpacing, safeTarget.toDouble(), shellNull.toDouble())
                            val envelope = sqrt((1f + safePresence * voice).coerceIn(0.02f, 1.98f))
                            left = w.first.magnitude.toFloat() * cos(phase + w.first.phase).toFloat() * envelope
                            right = w.second.magnitude.toFloat() * cos(phase + w.second.phase).toFloat() * envelope
                            phase += 2.0 * PI * carrier / sampleRate
                        }
                    }

                    if (phase >= 2.0 * PI || phase <= -2.0 * PI) phase %= 2.0 * PI
                    if (phase2 >= 2.0 * PI || phase2 <= -2.0 * PI) phase2 %= 2.0 * PI
                    if (phase3 >= 2.0 * PI || phase3 <= -2.0 * PI) phase3 %= 2.0 * PI

                    block[i * 2] = (left * outputGain * fade).coerceIn(-0.96f, 0.96f)
                    block[i * 2 + 1] = (right * outputGain * fade).coerceIn(-0.96f, 0.96f)
                    monitor[i] = voice
                    sourcePos += sourceStep
                }

                val sampleCount = frames * 2
                var written = 0
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

    private fun steeringPhase(carrier: Double, spacingM: Double, angleDeg: Double): Double {
        return normalize(2.0 * PI * carrier * spacingM * sin(angleDeg * PI / 180.0) / SPEED_OF_SOUND)
    }

    private data class Weight(val magnitude: Double, val phase: Double) {
        val phaseDegrees: Double get() = phase * 180.0 / PI
    }

    private fun solveBrightDarkWeights(carrier: Double, spacingM: Double, targetDeg: Double, nullDeg: Double): Pair<Weight, Weight> {
        val at = steeringPhase(carrier, spacingM, targetDeg)
        val an = steeringPhase(carrier, spacingM, nullDeg)
        val et = Complex(cos(-at), sin(-at))
        val en = Complex(cos(-an), sin(-an))
        var denom = et - en
        if (denom.mag < 0.05) denom = Complex(0.05, 0.0)
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
            val d = (o.re * o.re + o.im * o.im).coerceAtLeast(1e-8)
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
            val deltaSec = ((cross - direct) / SPEED_OF_SOUND.toFloat()).coerceAtLeast(0f)
            delaySamples = max(1, (sampleRate * deltaSec).roundToInt())
            buffer = FloatArray(delaySamples + 8)
            crossGain = (direct / cross).coerceIn(0.25f, 0.92f)
        }

        fun process(v: Float): Pair<Float, Float> {
            val delayedIndex = (write - delaySamples + buffer.size) % buffer.size
            val delayed = buffer[delayedIndex]
            buffer[write] = v
            write = (write + 1) % buffer.size
            val cancel = delayed * crossGain
            return (v - cancel) to (v - cancel)
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
                val window = 0.54 - 0.46 * cos(2.0 * PI * i / (taps - 1))
                coefficients[i] = (ideal * window).toFloat()
            }
        }

        fun process(input: Float, output: FloatArray) {
            buffer[writeIndex] = input
            var q = 0f
            for (i in 0 until taps) {
                val index = (writeIndex - i + taps) % taps
                q += coefficients[i] * buffer[index]
            }
            output[0] = buffer[(writeIndex - center + taps) % taps]
            output[1] = q
            writeIndex = (writeIndex + 1) % taps
        }
    }

    private fun createBestTrack(requestedSampleRate: Int, preferredDevice: AudioDeviceInfo?, stereo: Boolean): Pair<AudioTrack, Int> {
        val maximum = requestedSampleRate.coerceIn(44_100, 192_000)
        val candidates = listOf(maximum, 192_000, 176_400, 96_000, 88_200, 48_000, 44_100)
            .distinct()
            .filter { it in 44_100..maximum }
        var last: Throwable? = null
        for (rate in candidates) {
            try {
                val track = buildTrack(rate, stereo)
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    continue
                }
                if (preferredDevice != null) track.setPreferredDevice(preferredDevice)
                return track to track.sampleRate
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("No usable stereo PCM-float AudioTrack could be opened", last)
    }

    private fun buildTrack(sampleRate: Int, stereo: Boolean): AudioTrack {
        val mask = if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val channels = if (stereo) 2 else 1
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        require(minBytes > 0) { "AudioTrack rejected $sampleRate Hz" }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .build()
            )
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

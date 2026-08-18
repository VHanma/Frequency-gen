package com.vaan.ultracarrier.collective

import android.content.ContentResolver
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import com.vaan.ultracarrier.audio.BeamLabMode
import com.vaan.ultracarrier.audio.ListeningPath
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

class CollectiveAudioEngine(private val resolver: ContentResolver) {
    private val stopped = AtomicBoolean(true)
    @Volatile private var activeTrack: AudioTrack? = null

    fun stop() {
        stopped.set(true)
        activeTrack?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
        }
    }

    fun play(
        source: CollectiveSource,
        decoder: CollectiveStreamDecoder,
        config: CollectiveConfig,
        preferredDevice: AudioDeviceInfo?,
        onStarted: (CollectiveReport) -> Unit,
        onScope: (FloatArray, Int) -> Unit
    ) {
        stop()
        stopped.set(false)
        val (track, sampleRate) = createBestTrack(config.requestedSampleRate, preferredDevice)
        activeTrack = track
        val session = RenderSession(config.copy(requestedSampleRate = sampleRate), sampleRate, onScope)
        try {
            track.play()
            val route = track.routedDevice?.productName?.toString().orEmpty()
                .ifBlank { preferredDevice?.productName?.toString().orEmpty() }
                .ifBlank { "system-selected output" }
            onStarted(
                CollectiveReport(
                    sampleRate = sampleRate,
                    routeName = route,
                    family = config.family,
                    modeLabel = if (config.family == CollectiveFamily.WORLD_BEAM) config.worldMode.label else config.collectiveMode.label,
                    carrierHz = session.carrier
                )
            )
            val resampler = StreamingResampler(source.info.sampleRate, sampleRate) { mono, count ->
                if (stopped.get()) return@StreamingResampler
                session.process(mono, count) { stereo, stereoCount -> writeFully(track, stereo, stereoCount) }
            }
            decoder.stream(source) { chunk, count, _ ->
                if (stopped.get()) false else {
                    resampler.append(chunk, 0, count)
                    !stopped.get()
                }
            }
            if (!stopped.get()) resampler.finish()
        } finally {
            runCatching { track.stop() }
            runCatching { track.flush() }
            track.release()
            activeTrack = null
            stopped.set(true)
        }
    }

    fun export(
        source: CollectiveSource,
        decoder: CollectiveStreamDecoder,
        config: CollectiveConfig,
        destination: Uri,
        format: ExportFormat,
        onProgress: (Double?) -> Unit,
        onScope: (FloatArray, Int) -> Unit
    ) {
        stop()
        stopped.set(false)
        val sampleRate = config.requestedSampleRate.coerceIn(44_100, 192_000)
        val session = RenderSession(config.copy(requestedSampleRate = sampleRate), sampleRate, onScope)
        val pfd = resolver.openFileDescriptor(destination, "rw") ?: error("Could not open save destination.")
        pfd.use {
            val writer = WaveWriter(FileOutputStream(it.fileDescriptor).channel, sampleRate, 2, format)
            try {
                var inputFrames = 0L
                val totalInputFrames = source.info.durationSeconds?.let { d -> (d * source.info.sampleRate).toLong() }
                val resampler = StreamingResampler(source.info.sampleRate, sampleRate) { mono, count ->
                    if (stopped.get()) return@StreamingResampler
                    session.process(mono, count) { stereo, stereoCount -> writer.write(stereo, stereoCount) }
                }
                decoder.stream(source) { chunk, count, _ ->
                    if (stopped.get()) false else {
                        inputFrames += count
                        resampler.append(chunk, 0, count)
                        onProgress(totalInputFrames?.takeIf { t -> t > 0 }?.let { t -> (inputFrames.toDouble() / t).coerceIn(0.0, 1.0) })
                        !stopped.get()
                    }
                }
                if (!stopped.get()) resampler.finish()
            } finally {
                writer.close()
            }
        }
        stopped.set(true)
    }

    private class RenderSession(
        private val config: CollectiveConfig,
        private val sampleRate: Int,
        private val onScope: (FloatArray, Int) -> Unit
    ) {
        val carrier: Float = config.carrierHz.coerceIn(500f, sampleRate / 2f - 600f)
        private val low = OnePoleLowPass(sampleRate, if (config.family == CollectiveFamily.WORLD_BEAM) 3_400f else 4_200f)
        private val high = OnePoleHighPass(sampleRate, 150f)
        private val preLow = OnePoleLowPass(sampleRate, 1_700f)
        private val hilbert = Hilbert63()
        private val iq = FloatArray(2)
        private val block = FloatArray(BLOCK * 2)
        private val scope = FloatArray(BLOCK)
        private val delay = FloatArray((sampleRate * 0.0035f).roundToInt().coerceAtLeast(64))
        private var delayWrite = 0
        private var phase = 0.0
        private var phase2 = 0.0
        private var phase3 = 0.0
        private var outputFrame = 0L
        private var scopeCounter = 0
        private var noise = 0x514E2A19
        private var noiseSmooth = 0f
        private var previousVoice = 0f

        fun process(mono: FloatArray, count: Int, sink: (FloatArray, Int) -> Unit) {
            var offset = 0
            while (offset < count) {
                val frames = min(BLOCK, count - offset)
                for (i in 0 until frames) {
                    val frame = outputFrame + i
                    val raw = mono[offset + i]
                    val voice = tanh((high.process(low.process(raw)) * 2.25f).toDouble()).toFloat()
                    val pair = if (config.family == CollectiveFamily.WORLD_BEAM) worldSample(config.worldMode, voice, frame)
                    else perceptionSample(config.collectiveMode, voice, frame)
                    val gain = if (config.family == CollectiveFamily.WORLD_BEAM) {
                        when (config.listeningPath) {
                            ListeningPath.EXTERNAL_ARRAY -> 0.28f + config.presence * 0.34f
                            ListeningPath.PHONE_SPEAKER -> 0.14f + config.presence * 0.22f
                            else -> 0.10f + config.presence * 0.18f
                        }
                    } else {
                        when (config.listeningPath) {
                            ListeningPath.PHONE_SPEAKER -> 0.22f + config.presence * 0.34f
                            else -> 0.16f + config.presence * 0.28f
                        }
                    }
                    val l = (pair.first * gain).coerceIn(-0.96f, 0.96f)
                    val r = (pair.second * gain).coerceIn(-0.96f, 0.96f)
                    block[i * 2] = l
                    block[i * 2 + 1] = r
                    scope[i] = (l + r) * 0.5f
                    previousVoice = voice
                }
                sink(block, frames * 2)
                outputFrame += frames
                offset += frames
                scopeCounter++
                if (scopeCounter % 2 == 0) onScope(decimate(scope, frames, 512), sampleRate)
            }
        }

        private fun worldSample(mode: BeamLabMode, voice: Float, frame: Long): Pair<Float, Float> {
            val p = config.presence.coerceIn(0.05f, 1f)
            val rate = config.elfRateHz.coerceIn(0.25f, 80f)
            val depth = config.elfDepth.coerceIn(0f, 0.98f)
            val t = frame.toDouble() / sampleRate
            val slow = 1f - depth * 0.5f + depth * 0.5f * (1f + sin(2.0 * PI * rate * t).toFloat())
            val target = steeringPhase(carrier, config.spacingMm, config.targetAngleDeg)
            val nullPhase = steeringPhase(carrier, config.spacingMm, config.nullAngleDeg)
            val env = sqrt((1f + p * voice * slow).coerceIn(0.02f, 1.98f))
            val step = 2.0 * PI * carrier / sampleRate
            val left: Float
            val right: Float

            when (mode) {
                BeamLabMode.ELF_BEAM -> {
                    left = cos(phase).toFloat() * env
                    right = cos(phase + target).toFloat() * env
                    phase += step
                }
                BeamLabMode.DUAL_PUMP_ELF -> {
                    val f1 = (carrier - rate * 0.5f).coerceAtLeast(500f)
                    val f2 = (carrier + rate * 0.5f).coerceAtMost(sampleRate / 2f - 500f)
                    left = cos(phase).toFloat() * sqrt((1f + p * voice).coerceIn(0.02f, 1.98f))
                    right = cos(phase2 + steeringPhase(f2, config.spacingMm, config.targetAngleDeg)).toFloat() * sqrt((1f + p * voice).coerceIn(0.02f, 1.98f))
                    phase += 2.0 * PI * f1 / sampleRate
                    phase2 += 2.0 * PI * f2 / sampleRate
                }
                BeamLabMode.RUSSIAN_SSB_BEAM -> {
                    val shaped = preLow.process(voice)
                    hilbert.process(shaped, iq)
                    left = iq[0] * cos(phase).toFloat() - iq[1] * sin(phase).toFloat()
                    right = iq[0] * cos(phase + target).toFloat() - iq[1] * sin(phase + target).toFloat()
                    phase += step
                }
                BeamLabMode.SOVIET_PULSE_BEAM -> {
                    val gate = if (sin(2.0 * PI * rate * t) > 0.25) 1f else 0.04f
                    left = cos(phase).toFloat() * env * gate
                    right = cos(phase + target).toFloat() * env * gate
                    phase += step
                }
                BeamLabMode.PSYCHOTRONIC_NESTED_BEAM -> {
                    val fast = 0.72f + 0.28f * sin(2.0 * PI * 40.0 * t).toFloat()
                    left = cos(phase).toFloat() * env * fast
                    right = cos(phase + target).toFloat() * env * fast
                    phase += step
                }
                BeamLabMode.SMIRNOV_MASK_BEAM -> {
                    val n = shapedNoise()
                    val e = sqrt((1f + p * (voice * 0.90f + n * 0.10f)).coerceIn(0.02f, 1.98f))
                    left = cos(phase).toFloat() * e
                    right = cos(phase + target).toFloat() * e
                    phase += step
                }
                BeamLabMode.US_VIRTUAL_SPEAKER -> {
                    left = cos(phase).toFloat() * env
                    right = cos(phase + target).toFloat() * env
                    phase += step
                }
                BeamLabMode.US_LOCALIZED_SPOT -> {
                    hilbert.process(voice, iq)
                    left = cos(phase).toFloat() * 0.45f
                    right = (iq[0] * cos(phase + target).toFloat() - iq[1] * sin(phase + target).toFloat()) * p
                    phase += step
                }
                BeamLabMode.US_QUIET_ZONE,
                BeamLabMode.BRIGHT_DARK_BUBBLE -> {
                    val c = cos(phase).toFloat() * env
                    left = c
                    right = cos(phase + target).toFloat() * env - 0.62f * cos(phase + nullPhase).toFloat() * env
                    phase += step
                }
                BeamLabMode.US_VIRTUAL_HEADSET,
                BeamLabMode.ALIEN_DUAL_EAR_FIELD -> {
                    val earAngle = Math.toDegrees(atan((config.headWidthCm / 200.0) / (config.listenerDistanceCm / 100.0))).toFloat().coerceIn(1f, 35f)
                    val pl = steeringPhase(carrier, config.spacingMm, config.targetAngleDeg - earAngle)
                    val pr = steeringPhase(carrier, config.spacingMm, config.targetAngleDeg + earAngle)
                    left = cos(phase + pl).toFloat() * env
                    right = cos(phase + pr).toFloat() * env
                    phase += step
                }
                BeamLabMode.FREY_CODEC_ACOUSTIC -> {
                    val edge = (voice - previousVoice * 0.86f).coerceIn(-1f, 1f)
                    hilbert.process(edge, iq)
                    left = iq[0] * cos(phase).toFloat() - iq[1] * sin(phase).toFloat()
                    right = iq[0] * cos(phase + target).toFloat() - iq[1] * sin(phase + target).toFloat()
                    phase += step
                }
                BeamLabMode.US_PULSE_FM_ANALOG -> {
                    val density = 12.0 + abs(voice) * 120.0
                    val gate = if (sin(2.0 * PI * density * t) > 0.55) 1f else 0f
                    val instant = (carrier + voice * 900f).coerceIn(500f, sampleRate / 2f - 500f)
                    left = cos(phase).toFloat() * gate
                    right = cos(phase + target).toFloat() * gate
                    phase += 2.0 * PI * instant / sampleRate
                }
                BeamLabMode.SETI_DRIFT_BEAM -> {
                    val drift = 120f * sin(2.0 * PI * 0.025 * t).toFloat()
                    val f = (carrier + drift).coerceIn(500f, sampleRate / 2f - 500f)
                    left = cos(phase).toFloat() * env
                    right = cos(phase + steeringPhase(f, config.spacingMm, config.targetAngleDeg)).toFloat() * env
                    phase += 2.0 * PI * f / sampleRate
                }
                BeamLabMode.CHIRP_SPREAD_BEAM -> {
                    val cycle = (t % 0.08) / 0.08
                    val f = (carrier + (cycle.toFloat() * 2f - 1f) * 2_200f).coerceIn(500f, sampleRate / 2f - 500f)
                    left = cos(phase).toFloat() * env
                    right = cos(phase + steeringPhase(f, config.spacingMm, config.targetAngleDeg)).toFloat() * env
                    phase += 2.0 * PI * f / sampleRate
                }
                BeamLabMode.CROSSED_BEAM_FOCUS -> {
                    hilbert.process(voice, iq)
                    left = cos(phase).toFloat() * 0.55f
                    right = (iq[0] * cos(phase).toFloat() - iq[1] * sin(phase).toFloat()) * p
                    phase += step
                }
                BeamLabMode.BEAM_LOCK -> {
                    val angle = config.targetAngleDeg + config.ditherDeg * sin(2.0 * PI * config.ditherRateHz * t).toFloat()
                    val steer = steeringPhase(carrier, config.spacingMm, angle)
                    left = cos(phase).toFloat() * env
                    right = cos(phase + steer).toFloat() * env
                    phase += step
                }
                BeamLabMode.SWEET_SPOT_XTC -> {
                    val maxDelay = (sampleRate * 0.0012f).roundToInt().coerceAtLeast(1)
                    delay[delayWrite] = voice
                    val delayed = delay[(delayWrite - maxDelay + delay.size) % delay.size]
                    delayWrite = (delayWrite + 1) % delay.size
                    left = voice - delayed * 0.42f
                    right = voice - delayed * 0.42f
                }
                BeamLabMode.ALIEN_TIME_REVERSAL -> {
                    left = cos(phase - target).toFloat() * env
                    right = cos(phase + target).toFloat() * env
                    phase += step
                }
                BeamLabMode.ALIEN_HOLOGRAM_FOCUS -> {
                    val f2 = (carrier - 730f).coerceAtLeast(500f)
                    val f3 = (carrier + 910f).coerceAtMost(sampleRate / 2f - 500f)
                    left = (cos(phase) + cos(phase2 - target) + cos(phase3 - target * 2.0)).toFloat() * env / 3f
                    right = (cos(phase + target) + cos(phase2 + target * 2.0) + cos(phase3 + target * 3.0)).toFloat() * env / 3f
                    phase += step; phase2 += 2.0 * PI * f2 / sampleRate; phase3 += 2.0 * PI * f3 / sampleRate
                }
                BeamLabMode.ALIEN_VORTEX_OAM -> {
                    left = cos(phase).toFloat() * env
                    right = sin(phase + target).toFloat() * env
                    phase += step
                }
                BeamLabMode.ALIEN_FREQUENCY_KEY -> {
                    val f2 = (carrier - 420f).coerceAtLeast(500f)
                    val f3 = (carrier + 640f).coerceAtMost(sampleRate / 2f - 500f)
                    left = (cos(phase) + cos(phase2) + cos(phase3)).toFloat() * env / 3f
                    right = (cos(phase + target) + cos(phase2 + target * 1.7) + cos(phase3 + target * 2.3)).toFloat() * env / 3f
                    phase += step; phase2 += 2.0 * PI * f2 / sampleRate; phase3 += 2.0 * PI * f3 / sampleRate
                }
                BeamLabMode.ALIEN_BESSEL_SELF_HEAL -> {
                    val cone = max(4f, abs(config.targetAngleDeg) + 8f)
                    val a = steeringPhase(carrier, config.spacingMm, cone)
                    left = (cos(phase - a) + cos(phase + a)).toFloat() * env * 0.5f
                    right = (cos(phase - a + target) + cos(phase + a + target)).toFloat() * env * 0.5f
                    phase += step
                }
                BeamLabMode.ALIEN_QUIET_SHELL -> {
                    val sweepNull = config.nullAngleDeg + config.ditherDeg * sin(2.0 * PI * config.ditherRateHz * t).toFloat()
                    val np = steeringPhase(carrier, config.spacingMm, sweepNull)
                    left = cos(phase).toFloat() * env
                    right = cos(phase + target).toFloat() * env - 0.55f * cos(phase + np).toFloat() * env
                    phase += step
                }
            }
            wrapPhases()
            return left to right
        }

        private fun perceptionSample(mode: CollectiveMode, voice: Float, frame: Long): Pair<Float, Float> {
            val t = frame.toDouble() / sampleRate
            val p = config.presence.coerceIn(0.05f, 1f)
            val micro = (sampleRate * 0.00018f).roundToInt().coerceAtLeast(1)
            delay[delayWrite] = voice
            val delayed = delay[(delayWrite - micro + delay.size) % delay.size]
            delayWrite = (delayWrite + 1) % delay.size
            val n = shapedNoise()

            return when (mode) {
                CollectiveMode.THOUGHT_GHOST -> {
                    val drift = sin(2.0 * PI * 0.17 * t).toFloat()
                    val l = if (drift < 0) delayed else voice
                    val r = if (drift > 0) delayed else voice
                    (l * 0.96f) to (r * 0.96f)
                }
                CollectiveMode.PHONEMIC_RESTORE -> {
                    val cycle = frame % (sampleRate / 3).coerceAtLeast(1)
                    val gap = cycle < (sampleRate * 0.045f).toLong()
                    val v = if (gap) n * (0.32f + 0.24f * p) else voice
                    v to v
                }
                CollectiveMode.CONTINUITY_GHOST -> {
                    val cycle = frame % (sampleRate * 0.85f).toLong().coerceAtLeast(1)
                    val gap = cycle in (sampleRate * 0.30f).toLong()..(sampleRate * 0.46f).toLong()
                    val v = if (gap) n * 0.42f else voice
                    v to v
                }
                CollectiveMode.SILENT_GAP_ECHO -> {
                    val cycle = frame % (sampleRate * 1.20f).toLong().coerceAtLeast(1)
                    val silent = cycle > (sampleRate * 1.02f).toLong()
                    val v = if (silent) 0f else voice
                    v to v
                }
                CollectiveMode.AUDITORY_AFTERIMAGE -> {
                    val six = t % 6.0
                    if (six < 4.5) {
                        val center = 3_200.0
                        val band = sin(2.0 * PI * center * t).toFloat()
                        val notchNoise = (n - band * 0.12f).coerceIn(-1f, 1f)
                        notchNoise to notchNoise
                    } else if (six < 5.3) {
                        0f to 0f
                    } else {
                        voice * 0.65f to voice * 0.65f
                    }
                }
                CollectiveMode.MIND_CANVAS -> {
                    val env = abs(voice)
                    val base = 150.0 + env * 520.0
                    val highTone = sin(2.0 * PI * base * t).toFloat() * (0.04f + env * 0.14f)
                    val lowTone = sin(2.0 * PI * (base * 0.5) * t).toFloat() * (0.03f + env * 0.10f)
                    val pan = sin(2.0 * PI * 0.09 * t).toFloat()
                    (voice * 0.82f + highTone * (1f - pan) + lowTone * 0.3f) to
                        (voice * 0.82f + highTone * (1f + pan) + lowTone * 0.3f)
                }
                CollectiveMode.IMAGE_SEED_GEOMETRY -> {
                    val period = 8.0
                    val u = (t % period) / period
                    val angle = 2.0 * PI * u
                    val x = cos(angle).toFloat()
                    val y = sin(angle).toFloat()
                    val tone = sin(2.0 * PI * (280.0 + 180.0 * (y + 1.0)) * t).toFloat() * (0.03f + abs(voice) * 0.11f)
                    (voice * 0.76f + tone * (1f - x) * 0.7f) to (voice * 0.76f + tone * (1f + x) * 0.7f)
                }
                CollectiveMode.HYPERPHANTASIA_SEED -> {
                    val e = abs(voice)
                    val a = sin(2.0 * PI * (180.0 + 80.0 * sin(2.0 * PI * 0.05 * t)) * t).toFloat()
                    val b = sin(2.0 * PI * (270.0 + 120.0 * sin(2.0 * PI * 0.071 * t)) * t).toFloat()
                    val c = sin(2.0 * PI * (420.0 + 180.0 * sin(2.0 * PI * 0.037 * t)) * t).toFloat()
                    val pan = sin(2.0 * PI * 0.06 * t).toFloat()
                    val bed = (a + b * 0.7f + c * 0.45f) * (0.025f + 0.08f * e)
                    (voice * 0.72f + bed * (1f - pan)) to (voice * 0.72f + bed * (1f + pan))
                }
                CollectiveMode.ATTENTION_NULL -> {
                    val cue = sin(2.0 * PI * 720.0 * t).toFloat() * 0.028f
                    val pan = sin(2.0 * PI * 0.11 * t).toFloat()
                    (voice * 0.88f + cue * (1f - pan)) to (voice * 0.88f + cue * (1f + pan))
                }
                CollectiveMode.LACERTA_FILTER_TEST -> {
                    val steady = sin(2.0 * PI * 196.0 * t).toFloat() * 0.018f
                    val lowNovelty = (voice * 0.78f + steady)
                    lowNovelty to lowNovelty
                }
                CollectiveMode.SOUND_FLASH_SEED -> {
                    val period = (sampleRate * 1.4f).toLong().coerceAtLeast(1)
                    val pos = frame % period
                    val clickFrames = (sampleRate * 0.004f).toLong()
                    val second = (sampleRate * 0.075f).toLong()
                    val click = if (pos < clickFrames || pos in second until (second + clickFrames)) 0.42f else 0f
                    (voice * 0.45f + click) to (voice * 0.45f + click)
                }
                CollectiveMode.MISSING_FUNDAMENTAL -> {
                    val implied = 110.0 + abs(voice) * 55.0
                    var stack = 0f
                    for (h in 2..6) stack += sin(2.0 * PI * implied * h * t).toFloat() / h
                    val v = voice * 0.75f + stack * 0.11f
                    v to v
                }
            }
        }

        private fun shapedNoise(): Float {
            noise = noise * 1664525 + 1013904223
            val white = (((noise ushr 8) and 0x00ffffff) / 8_388_607.5f) - 1f
            noiseSmooth += (white - noiseSmooth) * 0.11f
            return noiseSmooth.coerceIn(-1f, 1f)
        }

        private fun steeringPhase(freq: Float, spacingMm: Float, angleDeg: Float): Double {
            val d = spacingMm.coerceIn(1f, 80f) / 1000.0
            val a = Math.toRadians(angleDeg.coerceIn(-80f, 80f).toDouble())
            return 2.0 * PI * freq * d * sin(a) / 343.0
        }

        private fun wrapPhases() {
            val tau = 2.0 * PI
            if (abs(phase) > tau * 16) phase %= tau
            if (abs(phase2) > tau * 16) phase2 %= tau
            if (abs(phase3) > tau * 16) phase3 %= tau
        }
    }

    private class StreamingResampler(
        sourceRate: Int,
        targetRate: Int,
        private val emit: (FloatArray, Int) -> Unit
    ) {
        private val ratio = sourceRate.toDouble() / targetRate
        private var tail = FloatArray(0)
        private var position = 0.0
        private val output = FloatArray(BLOCK)
        private var count = 0

        fun append(input: FloatArray, offset: Int, amount: Int) {
            if (amount <= 0) return
            if (abs(ratio - 1.0) < 1e-9) {
                var off = offset
                var left = amount
                while (left > 0) {
                    val n = min(BLOCK, left)
                    input.copyInto(output, 0, off, off + n)
                    emit(output, n)
                    off += n; left -= n
                }
                return
            }
            val combined = FloatArray(tail.size + amount)
            tail.copyInto(combined)
            input.copyInto(combined, tail.size, offset, offset + amount)
            while (position + 1.0 < combined.size) {
                val i = floor(position).toInt()
                val f = (position - i).toFloat()
                val s = combined[i] + (combined[i + 1] - combined[i]) * f
                output[count++] = s
                if (count == output.size) { emit(output, count); count = 0 }
                position += ratio
            }
            val consumed = floor(position).toInt().coerceIn(0, max(0, combined.size - 1))
            tail = combined.copyOfRange(consumed, combined.size)
            position -= consumed
        }

        fun finish() {
            if (count > 0) emit(output, count)
            count = 0
        }
    }

    private class OnePoleLowPass(rate: Int, cutoff: Float) {
        private val a = (1.0 - kotlin.math.exp(-2.0 * PI * cutoff.coerceAtMost(rate / 2f - 100f) / rate)).toFloat()
        private var y = 0f
        fun process(x: Float): Float { y += a * (x - y); return y }
    }

    private class OnePoleHighPass(rate: Int, cutoff: Float) {
        private val a = kotlin.math.exp(-2.0 * PI * cutoff / rate).toFloat()
        private var y = 0f
        private var last = 0f
        fun process(x: Float): Float { y = a * (y + x - last); last = x; return y }
    }

    private class Hilbert63 {
        private val delay = FloatArray(63)
        private var pos = 0
        fun process(x: Float, out: FloatArray) {
            delay[pos] = x
            var q = 0f
            var k = 0
            for (n in -31..31) {
                if (n != 0 && (abs(n) and 1) == 1) {
                    val h = (2.0 / (PI * n)).toFloat()
                    val idx = (pos - k + delay.size) % delay.size
                    q += delay[idx] * h
                }
                k++
            }
            val center = (pos - 31 + delay.size) % delay.size
            out[0] = delay[center]
            out[1] = q.coerceIn(-2f, 2f)
            pos = (pos + 1) % delay.size
        }
    }

    private class WaveWriter(
        private val channel: FileChannel,
        private val sampleRate: Int,
        private val channels: Int,
        private val format: ExportFormat
    ) : AutoCloseable {
        private var dataBytes = 0L
        private var sampleFrames = 0L
        private val bytesPerSample = format.bits / 8

        init { writeHeader(false) }

        fun write(samples: FloatArray, count: Int) {
            val b = ByteBuffer.allocate(count * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val x = samples[i].coerceIn(-1f, 1f)
                when (format) {
                    ExportFormat.WAV_16 -> b.putShort((x * 32767f).roundToInt().toShort())
                    ExportFormat.WAV_24 -> {
                        val v = (x * 8_388_607f).roundToInt()
                        b.put((v and 255).toByte()); b.put(((v ushr 8) and 255).toByte()); b.put(((v ushr 16) and 255).toByte())
                    }
                    ExportFormat.WAV_FLOAT32 -> b.putFloat(x)
                }
            }
            b.flip()
            while (b.hasRemaining()) channel.write(b)
            dataBytes += count.toLong() * bytesPerSample
            sampleFrames += count / channels
        }

        private fun writeHeader(final: Boolean) {
            val large = final && dataBytes > 0xffff_ff00L
            channel.position(0)
            val h = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
            putAscii(h, if (large) "RF64" else "RIFF")
            h.putInt(if (large) -1 else if (final) (72L + dataBytes).coerceAtMost(0xffff_ffffL).toInt() else 0)
            putAscii(h, "WAVE")
            putAscii(h, if (large) "ds64" else "JUNK")
            h.putInt(28)
            if (large) {
                h.putLong(72L + dataBytes)
                h.putLong(dataBytes)
                h.putLong(sampleFrames)
                h.putInt(0)
            } else repeat(28) { h.put(0) }
            putAscii(h, "fmt ")
            h.putInt(16)
            h.putShort(if (format.floatPcm) 3 else 1)
            h.putShort(channels.toShort())
            h.putInt(sampleRate)
            h.putInt(sampleRate * channels * bytesPerSample)
            h.putShort((channels * bytesPerSample).toShort())
            h.putShort(format.bits.toShort())
            putAscii(h, "data")
            h.putInt(if (large) -1 else if (final) dataBytes.coerceAtMost(0xffff_ffffL).toInt() else 0)
            h.flip()
            while (h.hasRemaining()) channel.write(h)
            if (!final) channel.position(80)
        }

        override fun close() {
            writeHeader(true)
            channel.force(true)
            channel.close()
        }

        private fun putAscii(b: ByteBuffer, s: String) { s.forEach { b.put(it.code.toByte()) } }
    }

    private fun createBestTrack(requested: Int, preferred: AudioDeviceInfo?): Pair<AudioTrack, Int> {
        val maxRate = requested.coerceIn(44_100, 192_000)
        val candidates = listOf(maxRate, 192_000, 176_400, 96_000, 88_200, 48_000, 44_100).distinct().filter { it <= maxRate }
        var last: Throwable? = null
        for (rate in candidates) {
            try {
                val mask = AudioFormat.CHANNEL_OUT_STEREO
                val minBytes = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT)
                if (minBytes <= 0) continue
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(mask).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(max(minBytes, BLOCK * 2 * 4 * 4))
                    .build()
                if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); continue }
                if (preferred != null) track.setPreferredDevice(preferred)
                return track to track.sampleRate
            } catch (t: Throwable) { last = t }
        }
        throw IllegalStateException("Could not open a stereo AudioTrack.", last)
    }

    private fun writeFully(track: AudioTrack, data: FloatArray, count: Int) {
        var off = 0
        while (off < count) {
            val n = track.write(data, off, count - off, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) error("AudioTrack write failed: $n")
            off += n
        }
    }

    companion object {
        private const val BLOCK = 1024
        private fun decimate(input: FloatArray, count: Int, target: Int): FloatArray {
            if (count <= target) return input.copyOf(count)
            val out = FloatArray(target)
            val step = count.toDouble() / target
            for (i in out.indices) out[i] = input[(i * step).toInt().coerceAtMost(count - 1)]
            return out
        }
    }
}

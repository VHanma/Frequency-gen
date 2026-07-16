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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

class AudioTransmitter {
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
        depth: Float,
        thoughtMode: ThoughtMode,
        listeningPath: ListeningPath,
        steeringAngleDeg: Float,
        transducerSpacingMm: Float,
        chirpSweepHz: Float,
        chirpPeriodMs: Float,
        preferredDevice: AudioDeviceInfo?,
        onStarted: (TransmissionReport) -> Unit,
        onWaveform: (FloatArray) -> Unit
    ) {
        stop()
        stopped.set(false)

        val stereo = listeningPath != ListeningPath.PHONE_SPEAKER || thoughtMode == ThoughtMode.ARRAY_STEER
        val (track, sampleRate) = createBestTrack(requestedSampleRate, preferredDevice, stereo)
        val channels = if (stereo) 2 else 1
        activeTrack = track

        val carrier = if (thoughtMode == ThoughtMode.INNER_VOICE) 0f else {
            DspMath.clampCarrier(sampleRate, requestedCarrierHz)
        }
        val maximumBandwidth = if (carrier > 0f) {
            DspMath.safeMessageBandwidth(sampleRate, carrier)
        } else {
            4_000f
        }
        val bandwidth = when (thoughtMode) {
            ThoughtMode.INNER_VOICE -> 3_800f
            ThoughtMode.PATENT_SSB -> min(maximumBandwidth, 3_400f)
            ThoughtMode.FM_SLOPE -> min(maximumBandwidth, 3_200f)
            ThoughtMode.BEAM_WHISPER -> min(maximumBandwidth, 3_100f)
            ThoughtMode.AIR_HETERODYNE -> min(maximumBandwidth, 4_000f)
            ThoughtMode.ARRAY_STEER -> min(maximumBandwidth, 3_600f)
            ThoughtMode.CHIRP_CARRIER -> min(maximumBandwidth, 3_200f)
        }
        val highPassCutoff = when (listeningPath) {
            ListeningPath.BONE_CONDUCTION -> 350f
            ListeningPath.HEADPHONES -> 220f
            ListeningPath.PHONE_SPEAKER -> 260f
            ListeningPath.EXTERNAL_ARRAY -> 180f
        }
        val lowPass = DspMath.LowPass(sampleRate, bandwidth)
        val highPass = DspMath.HighPass(sampleRate, highPassCutoff)
        val sourceStep = pcm.sampleRate.toDouble() / sampleRate
        val totalOutputFrames = (pcm.samples.size / sourceStep).toLong().coerceAtLeast(1L)
        val constantPhaseStep = if (carrier > 0f) 2.0 * PI * carrier / sampleRate else 0.0
        val fadeSeconds = if (thoughtMode == ThoughtMode.INNER_VOICE) 0.12 else 0.08
        val fadeFrames = max(1, (sampleRate * fadeSeconds).toInt())
        val block = FloatArray(BLOCK_FRAMES * channels)
        val messageMonitor = FloatArray(BLOCK_FRAMES)
        val hilbert = HilbertTransformer()
        val iq = FloatArray(2)
        var sourcePosition = 0.0
        var outputFrame = 0L
        var phase = 0.0
        var waveformCounter = 0
        var gateEnvelope = 0f
        val effectiveDepth = depth.coerceIn(0.05f, 1f)
        val outputGain = outputGain(thoughtMode, listeningPath, effectiveDepth)
        val fmDeviation = 350f + 1_650f * effectiveDepth

        val angleRadians = steeringAngleDeg.coerceIn(-60f, 60f) * PI / 180.0
        val spacingMeters = transducerSpacingMm.coerceIn(1f, 50f) / 1000.0
        val rawPhaseOffset = if (carrier > 0f) {
            2.0 * PI * carrier * spacingMeters * sin(angleRadians) / SPEED_OF_SOUND_MPS
        } else {
            0.0
        }
        val phaseOffset = normalizePhase(rawPhaseOffset)
        val phaseDegrees = (phaseOffset * 180.0 / PI).toFloat()

        val safeSweep = chirpSweepHz.coerceIn(100f, 12_000f)
        val chirpLow = (carrier - safeSweep / 2f).coerceAtLeast(100f)
        val chirpHigh = (carrier + safeSweep / 2f).coerceAtMost(sampleRate / 2f - 800f)
        val chirpFrames = max(1L, (sampleRate * chirpPeriodMs.coerceIn(2f, 250f) / 1000f).toLong())

        try {
            track.play()
            val routeName = track.routedDevice?.productName?.toString().orEmpty()
                .ifBlank { preferredDevice?.productName?.toString().orEmpty() }
                .ifBlank { "system-selected output" }
            onStarted(
                TransmissionReport(
                    actualSampleRate = sampleRate,
                    actualCarrierHz = carrier,
                    messageBandwidthHz = bandwidth,
                    routedDeviceName = routeName,
                    thoughtMode = thoughtMode,
                    listeningPath = listeningPath,
                    outputGain = outputGain,
                    arrayPhaseDegrees = phaseDegrees,
                    chirpSweepHz = if (thoughtMode == ThoughtMode.CHIRP_CARRIER) chirpHigh - chirpLow else 0f
                )
            )

            while (!stopped.get() && outputFrame < totalOutputFrames) {
                val frames = min(BLOCK_FRAMES.toLong(), totalOutputFrames - outputFrame).toInt()
                for (i in 0 until frames) {
                    val raw = DspMath.interpolate(pcm.samples, sourcePosition)
                    val bandLimited = highPass.process(lowPass.process(raw))
                    val drive = if (thoughtMode == ThoughtMode.INNER_VOICE) 2.2f else 3.1f
                    val message = tanh((bandLimited * drive).toDouble()).toFloat().coerceIn(-1f, 1f)
                    val gateTarget = if (abs(message) > 0.006f) 1f else 0f
                    val gateSpeed = if (gateTarget > gateEnvelope) 0.035f else 0.004f
                    gateEnvelope += (gateTarget - gateEnvelope) * gateSpeed

                    val absoluteFrame = outputFrame + i
                    val fadeIn = absoluteFrame.toFloat() / fadeFrames
                    val fadeOut = (totalOutputFrames - absoluteFrame).toFloat() / fadeFrames
                    val fade = min(1f, min(fadeIn, fadeOut)).coerceAtLeast(0f)

                    var leftEncoded: Float
                    var rightEncoded: Float
                    when (thoughtMode) {
                        ThoughtMode.INNER_VOICE -> {
                            leftEncoded = message
                            rightEncoded = message
                        }

                        ThoughtMode.PATENT_SSB -> {
                            hilbert.process(message, iq)
                            val upperSideband = iq[0] * cos(phase).toFloat() - iq[1] * sin(phase).toFloat()
                            phase += constantPhaseStep
                            leftEncoded = upperSideband * gateEnvelope
                            rightEncoded = leftEncoded
                        }

                        ThoughtMode.FM_SLOPE -> {
                            val sample = cos(phase).toFloat() * gateEnvelope
                            val instantaneous = (carrier + fmDeviation * message)
                                .coerceIn(100f, sampleRate / 2f - 800f)
                            phase += 2.0 * PI * instantaneous / sampleRate
                            leftEncoded = sample
                            rightEncoded = sample
                        }

                        ThoughtMode.BEAM_WHISPER -> {
                            val carrierSample = cos(phase).toFloat()
                            phase += constantPhaseStep
                            val pilot = 0.055f
                            val sideband = message * (0.18f + 0.42f * effectiveDepth)
                            leftEncoded = carrierSample * (pilot + sideband) * gateEnvelope
                            rightEncoded = leftEncoded
                        }

                        ThoughtMode.AIR_HETERODYNE -> {
                            val envelope = sqrt((1f + effectiveDepth * message).coerceIn(0.02f, 2f))
                            val carrierSample = cos(phase).toFloat()
                            phase += constantPhaseStep
                            leftEncoded = carrierSample * envelope * gateEnvelope
                            rightEncoded = leftEncoded
                        }

                        ThoughtMode.ARRAY_STEER -> {
                            val envelope = sqrt((1f + effectiveDepth * message).coerceIn(0.02f, 2f)) * gateEnvelope
                            leftEncoded = cos(phase).toFloat() * envelope
                            rightEncoded = cos(phase + phaseOffset).toFloat() * envelope
                            phase += constantPhaseStep
                        }

                        ThoughtMode.CHIRP_CARRIER -> {
                            val chirpPosition = (absoluteFrame % chirpFrames).toDouble() / chirpFrames
                            val instantaneous = chirpLow + (chirpHigh - chirpLow) * chirpPosition.toFloat()
                            val envelope = sqrt((1f + effectiveDepth * message).coerceIn(0.02f, 2f)) * gateEnvelope
                            val sample = cos(phase).toFloat() * envelope
                            phase += 2.0 * PI * instantaneous / sampleRate
                            leftEncoded = sample
                            rightEncoded = sample
                        }
                    }
                    if (phase >= 2.0 * PI || phase <= -2.0 * PI) phase %= 2.0 * PI

                    val left = (leftEncoded * outputGain * fade).coerceIn(-0.96f, 0.96f)
                    val right = (rightEncoded * outputGain * fade).coerceIn(-0.96f, 0.96f)
                    if (channels == 2) {
                        val base = i * 2
                        block[base] = left
                        block[base + 1] = right
                    } else {
                        block[i] = left
                    }
                    messageMonitor[i] = message
                    sourcePosition += sourceStep
                }

                val sampleCount = frames * channels
                var writtenTotal = 0
                while (writtenTotal < sampleCount) {
                    val written = track.write(block, writtenTotal, sampleCount - writtenTotal, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) error("AudioTrack write failed with code $written.")
                    if (written == 0) error("AudioTrack stopped accepting samples.")
                    writtenTotal += written
                }
                outputFrame += frames

                waveformCounter++
                if (waveformCounter % 2 == 0) onWaveform(decimate(messageMonitor, frames, 512))
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.flush() }
            track.release()
            activeTrack = null
            stopped.set(true)
        }
    }

    private fun outputGain(mode: ThoughtMode, path: ListeningPath, depth: Float): Float = when (path) {
        ListeningPath.HEADPHONES -> when (mode) {
            ThoughtMode.INNER_VOICE -> 0.035f + 0.18f * depth
            ThoughtMode.PATENT_SSB, ThoughtMode.FM_SLOPE -> 0.05f + 0.22f * depth
            ThoughtMode.BEAM_WHISPER -> 0.07f + 0.24f * depth
            ThoughtMode.AIR_HETERODYNE, ThoughtMode.ARRAY_STEER, ThoughtMode.CHIRP_CARRIER -> 0.04f + 0.16f * depth
        }
        ListeningPath.BONE_CONDUCTION -> when (mode) {
            ThoughtMode.INNER_VOICE -> 0.06f + 0.27f * depth
            else -> 0.08f + 0.28f * depth
        }
        ListeningPath.PHONE_SPEAKER -> when (mode) {
            ThoughtMode.INNER_VOICE -> 0.10f + 0.26f * depth
            ThoughtMode.PATENT_SSB, ThoughtMode.FM_SLOPE -> 0.12f + 0.30f * depth
            ThoughtMode.BEAM_WHISPER -> 0.15f + 0.34f * depth
            ThoughtMode.AIR_HETERODYNE, ThoughtMode.ARRAY_STEER, ThoughtMode.CHIRP_CARRIER -> 0.10f + 0.24f * depth
        }
        ListeningPath.EXTERNAL_ARRAY -> when (mode) {
            ThoughtMode.INNER_VOICE -> 0.08f + 0.25f * depth
            ThoughtMode.PATENT_SSB, ThoughtMode.FM_SLOPE, ThoughtMode.BEAM_WHISPER -> 0.12f + 0.32f * depth
            ThoughtMode.AIR_HETERODYNE, ThoughtMode.ARRAY_STEER, ThoughtMode.CHIRP_CARRIER -> 0.18f + 0.42f * depth
        }
    }

    private fun createBestTrack(
        requestedSampleRate: Int,
        preferredDevice: AudioDeviceInfo?,
        stereo: Boolean
    ): Pair<AudioTrack, Int> {
        val maximum = requestedSampleRate.coerceIn(44_100, 192_000)
        val candidates = listOf(maximum, 192_000, 176_400, 96_000, 88_200, 48_000, 44_100)
            .distinct()
            .filter { it in 44_100..maximum }
        var lastError: Throwable? = null
        for (rate in candidates) {
            try {
                val track = buildTrack(rate, stereo)
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    continue
                }
                if (preferredDevice != null) track.setPreferredDevice(preferredDevice)
                return track to track.sampleRate
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException("No usable PCM-float AudioTrack could be opened.", lastError)
    }

    private fun buildTrack(sampleRate: Int, stereo: Boolean): AudioTrack {
        val channelMask = if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val channelCount = if (stereo) 2 else 1
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        require(minBytes > 0) { "AudioTrack rejected $sampleRate Hz." }
        val bufferBytes = max(minBytes, BLOCK_FRAMES * channelCount * 4 * 4)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun normalizePhase(value: Double): Double {
        var phase = value % (2.0 * PI)
        if (phase > PI) phase -= 2.0 * PI
        if (phase < -PI) phase += 2.0 * PI
        return phase
    }

    private fun decimate(input: FloatArray, count: Int, target: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        if (count <= target) return input.copyOf(count)
        val output = FloatArray(target)
        val step = count.toDouble() / target
        for (i in output.indices) output[i] = input[(i * step).toInt().coerceAtMost(count - 1)]
        return output
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
            var quadrature = 0f
            for (i in 0 until taps) {
                val index = (writeIndex - i + taps) % taps
                quadrature += coefficients[i] * buffer[index]
            }
            val delayedIndex = (writeIndex - center + taps) % taps
            output[0] = buffer[delayedIndex]
            output[1] = quadrature
            writeIndex = (writeIndex + 1) % taps
        }
    }

    companion object {
        private const val BLOCK_FRAMES = 4096
        private const val SPEED_OF_SOUND_MPS = 343.0
    }
}

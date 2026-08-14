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
import kotlin.math.tanh

enum class GodXMode(val label: String, val description: String) {
    VOICE_OF_GOD_STACK(
        "Voice of God Stack X",
        "Center-locked voice with low-rate envelope, binaural bed, micro-delay motion, and a light 40 Hz modulation ripple."
    ),
    EMF_ENVELOPE(
        "EMF-Inspired Envelope",
        "Maps a selected low-frequency rate onto ordinary acoustic amplitude modulation. The app is not emitting that frequency as an electromagnetic field."
    ),
    EMF_SCAN(
        "EMF Pattern Scan",
        "Cycles the voice envelope through a bank of low-frequency experimental rates including 7.83, 10, 16.67, 25, 40, 50, 60, and 80 Hz."
    ),
    ASSR_40(
        "40 Hz ASSR",
        "Uses a 40 Hz acoustic amplitude modulation with a faint low-frequency anchor tone."
    ),
    CROSS_FREQUENCY_NEST(
        "Cross-Frequency Nest",
        "Nests a selected slow envelope inside a subtle 40 Hz modulation pattern."
    ),
    BINAURAL_CORE(
        "Binaural Core",
        "Voice plus different low tones in left and right channels so the binaural difference exists only with separated stereo listening."
    ),
    MONAURAL_BEAT(
        "Monaural Beat",
        "Places both nearby tones into both channels, producing a physical acoustic beat at the selected difference frequency."
    ),
    MICRO_MOTION(
        "Micro-Motion",
        "Moves the stereo image using slowly changing sub-millisecond interaural delay."
    ),
    COHERENCE_SNAP(
        "Coherence Snap",
        "Alternates a lightly decorrelated stereo image with brief perfectly matched center-lock moments."
    ),
    PHASE_FLIP(
        "Phase Correlation Flip",
        "Slowly moves the right channel between correlated and inverted versions of the voice to explore center-image changes."
    )
}

data class GodXReport(
    val mode: GodXMode,
    val sampleRate: Int,
    val routeName: String,
    val outputGain: Float,
    val modulationHz: Float,
    val beatHz: Float,
    val microDelayUs: Float
)

class GodXAudioTransmitter {
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
        mode: GodXMode,
        listeningPath: ListeningPath,
        presence: Float,
        modulationHz: Float,
        modulationDepth: Float,
        beatHz: Float,
        baseHz: Float,
        microDelayUs: Float,
        motionRateHz: Float,
        preferredDevice: AudioDeviceInfo?,
        onStarted: (GodXReport) -> Unit,
        onWaveform: (FloatArray) -> Unit
    ) {
        stop()
        stopped.set(false)

        val stereo = listeningPath != ListeningPath.PHONE_SPEAKER
        val (track, sampleRate) = createBestTrack(requestedSampleRate, preferredDevice, stereo)
        activeTrack = track
        val channels = if (stereo) 2 else 1

        val lowPass = DspMath.LowPass(sampleRate, 3_600f)
        val highPass = DspMath.HighPass(sampleRate, if (listeningPath == ListeningPath.BONE_CONDUCTION) 300f else 180f)
        val sourceStep = pcm.sampleRate.toDouble() / sampleRate
        val totalFrames = (pcm.samples.size / sourceStep).toLong().coerceAtLeast(1L)
        val block = FloatArray(BLOCK_FRAMES * channels)
        val monitor = FloatArray(BLOCK_FRAMES)
        val fadeFrames = max(1, (sampleRate * 0.10f).roundToInt())

        val p = presence.coerceIn(0.05f, 1f)
        val modHz = modulationHz.coerceIn(1f, 120f)
        val modDepth = modulationDepth.coerceIn(0f, 0.90f)
        val safeBeat = beatHz.coerceIn(1f, 60f)
        val safeBase = baseHz.coerceIn(120f, 900f)
        val safeDelayUs = microDelayUs.coerceIn(0f, 650f)
        val safeMotion = motionRateHz.coerceIn(0.03f, 4f)
        val maxDelaySamples = max(1, (sampleRate * safeDelayUs / 1_000_000f).roundToInt())
        val delayBuffer = FloatArray(maxDelaySamples + 8)
        var delayWrite = 0
        var sourcePosition = 0.0
        var outputFrame = 0L
        var waveformCounter = 0
        var noiseStateL = 0x13579BDF
        var noiseStateR = 0x2468ACE1
        var noiseSmoothL = 0f
        var noiseSmoothR = 0f
        val outputGain = outputGain(listeningPath, p)
        val scanRates = floatArrayOf(7.83f, 10f, 16.67f, 25f, 40f, 50f, 60f, 80f)

        try {
            track.play()
            val routeName = track.routedDevice?.productName?.toString().orEmpty()
                .ifBlank { preferredDevice?.productName?.toString().orEmpty() }
                .ifBlank { "system-selected output" }
            onStarted(
                GodXReport(
                    mode = mode,
                    sampleRate = sampleRate,
                    routeName = routeName,
                    outputGain = outputGain,
                    modulationHz = modHz,
                    beatHz = safeBeat,
                    microDelayUs = safeDelayUs
                )
            )

            while (!stopped.get() && outputFrame < totalFrames) {
                val frames = min(BLOCK_FRAMES.toLong(), totalFrames - outputFrame).toInt()
                for (i in 0 until frames) {
                    val frame = outputFrame + i
                    val raw = DspMath.interpolate(pcm.samples, sourcePosition)
                    val filtered = highPass.process(lowPass.process(raw))
                    val voice = tanh((filtered * (1.9f + 0.8f * p)).toDouble()).toFloat()
                    delayBuffer[delayWrite] = voice

                    val motion = sin(2.0 * PI * safeMotion * frame / sampleRate).toFloat()
                    val signedDelay = (motion * maxDelaySamples).roundToInt()
                    val delayAmount = abs(signedDelay)
                    val delayedIndex = (delayWrite - delayAmount + delayBuffer.size) % delayBuffer.size
                    val delayed = delayBuffer[delayedIndex]
                    var leftVoice = if (signedDelay < 0) delayed else voice
                    var rightVoice = if (signedDelay > 0) delayed else voice

                    val envelope = acousticEnvelope(modHz, modDepth, frame, sampleRate)
                    val fast40 = acousticEnvelope(40f, 0.16f + 0.12f * p, frame, sampleRate)
                    val beatLeftHz = safeBase - safeBeat / 2f
                    val beatRightHz = safeBase + safeBeat / 2f
                    val toneL = sin(2.0 * PI * beatLeftHz * frame / sampleRate).toFloat()
                    val toneR = sin(2.0 * PI * beatRightHz * frame / sampleRate).toFloat()
                    val beatBed = 0.007f + 0.022f * p

                    var left: Float
                    var right: Float
                    when (mode) {
                        GodXMode.VOICE_OF_GOD_STACK -> {
                            val nested = envelope * fast40
                            left = leftVoice * 0.90f * nested + toneL * beatBed
                            right = rightVoice * 0.90f * nested + toneR * beatBed
                        }

                        GodXMode.EMF_ENVELOPE -> {
                            left = voice * envelope
                            right = left
                        }

                        GodXMode.EMF_SCAN -> {
                            val segmentFrames = max(1L, (sampleRate * 4.0f).toLong())
                            val index = ((frame / segmentFrames) % scanRates.size).toInt()
                            val scanEnvelope = acousticEnvelope(scanRates[index], modDepth, frame, sampleRate)
                            left = voice * scanEnvelope
                            right = left
                        }

                        GodXMode.ASSR_40 -> {
                            val anchor = sin(2.0 * PI * 500.0 * frame / sampleRate).toFloat() * (0.004f + 0.012f * p)
                            left = voice * acousticEnvelope(40f, max(0.25f, modDepth), frame, sampleRate) + anchor
                            right = left
                        }

                        GodXMode.CROSS_FREQUENCY_NEST -> {
                            val slow = acousticEnvelope(modHz, modDepth, frame, sampleRate)
                            left = voice * slow * fast40
                            right = left
                        }

                        GodXMode.BINAURAL_CORE -> {
                            left = voice * 0.93f + toneL * beatBed
                            right = voice * 0.93f + toneR * beatBed
                        }

                        GodXMode.MONAURAL_BEAT -> {
                            val physicalBeat = (toneL + toneR) * (beatBed * 0.72f)
                            left = voice * 0.92f + physicalBeat
                            right = left
                        }

                        GodXMode.MICRO_MOTION -> {
                            left = leftVoice
                            right = rightVoice
                        }

                        GodXMode.COHERENCE_SNAP -> {
                            noiseStateL = noiseStateL * 1664525 + 1013904223
                            noiseStateR = noiseStateR * 22695477 + 1
                            val whiteL = (((noiseStateL ushr 8) and 0x00FFFFFF) / 8_388_607.5f) - 1f
                            val whiteR = (((noiseStateR ushr 8) and 0x00FFFFFF) / 8_388_607.5f) - 1f
                            noiseSmoothL += (whiteL - noiseSmoothL) * 0.16f
                            noiseSmoothR += (whiteR - noiseSmoothR) * 0.16f
                            val decorL = (whiteL - noiseSmoothL) * 0.018f * p
                            val decorR = (whiteR - noiseSmoothR) * 0.018f * p
                            val cycleFrames = max(1L, (sampleRate * 2.8f).toLong())
                            val snapFrames = max(1L, (sampleRate * 0.38f).toLong())
                            val inSnap = (frame % cycleFrames) < snapFrames
                            if (inSnap) {
                                left = voice
                                right = voice
                            } else {
                                left = voice * 0.94f + decorL
                                right = voice * 0.94f + decorR
                            }
                        }

                        GodXMode.PHASE_FLIP -> {
                            val correlation = cos(2.0 * PI * safeMotion * frame / sampleRate).toFloat()
                            left = voice
                            right = voice * correlation
                        }
                    }

                    if (!stereo) {
                        val mono = (left + right) * 0.5f
                        left = mono
                        right = mono
                    }

                    val fadeIn = frame.toFloat() / fadeFrames
                    val fadeOut = (totalFrames - frame).toFloat() / fadeFrames
                    val fade = min(1f, min(fadeIn, fadeOut)).coerceAtLeast(0f)
                    left = (left * outputGain * fade).coerceIn(-0.96f, 0.96f)
                    right = (right * outputGain * fade).coerceIn(-0.96f, 0.96f)

                    if (channels == 2) {
                        val base = i * 2
                        block[base] = left
                        block[base + 1] = right
                    } else {
                        block[i] = left
                    }
                    monitor[i] = voice
                    sourcePosition += sourceStep
                    delayWrite = (delayWrite + 1) % delayBuffer.size
                }

                val sampleCount = frames * channels
                var writtenTotal = 0
                while (writtenTotal < sampleCount) {
                    val written = track.write(block, writtenTotal, sampleCount - writtenTotal, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) error("AudioTrack write failed with code $written")
                    if (written == 0) error("AudioTrack stopped accepting samples")
                    writtenTotal += written
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

    private fun acousticEnvelope(rateHz: Float, depth: Float, frame: Long, sampleRate: Int): Float {
        val d = depth.coerceIn(0f, 0.95f)
        val s = sin(2.0 * PI * rateHz * frame / sampleRate).toFloat()
        return (1f - d * 0.5f) + d * 0.5f * (1f + s)
    }

    private fun outputGain(path: ListeningPath, presence: Float): Float = when (path) {
        ListeningPath.HEADPHONES -> 0.05f + 0.15f * presence
        ListeningPath.BONE_CONDUCTION -> 0.07f + 0.20f * presence
        ListeningPath.PHONE_SPEAKER -> 0.14f + 0.28f * presence
        ListeningPath.EXTERNAL_ARRAY -> 0.08f + 0.20f * presence
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
        throw IllegalStateException("No usable PCM-float AudioTrack could be opened", lastError)
    }

    private fun buildTrack(sampleRate: Int, stereo: Boolean): AudioTrack {
        val channelMask = if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val channelCount = if (stereo) 2 else 1
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        require(minBytes > 0) { "AudioTrack rejected $sampleRate Hz" }
        val bufferBytes = max(minBytes, BLOCK_FRAMES * channelCount * 4 * 4)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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

    private fun decimate(input: FloatArray, count: Int, target: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        if (count <= target) return input.copyOf(count)
        val output = FloatArray(target)
        val step = count.toDouble() / target
        for (i in output.indices) output[i] = input[(i * step).toInt().coerceAtMost(count - 1)]
        return output
    }

    companion object {
        private const val BLOCK_FRAMES = 4096
    }
}

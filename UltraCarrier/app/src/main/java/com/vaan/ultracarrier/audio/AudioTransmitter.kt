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
        mode: ModulationMode,
        privacyMode: PrivacyMode,
        preferredDevice: AudioDeviceInfo?,
        onStarted: (TransmissionReport) -> Unit,
        onWaveform: (FloatArray) -> Unit
    ) {
        stop()
        stopped.set(false)

        val (track, sampleRate) = createBestTrack(requestedSampleRate, preferredDevice)
        activeTrack = track

        val carrier = DspMath.clampCarrier(sampleRate, requestedCarrierHz)
        val maximumBandwidth = DspMath.safeMessageBandwidth(sampleRate, carrier)
        val bandwidth = when (privacyMode) {
            PrivacyMode.PHONE_BEAM -> min(maximumBandwidth, 3_200f)
            PrivacyMode.EXTERNAL_ARRAY -> min(maximumBandwidth, 6_000f)
            PrivacyMode.STANDARD -> maximumBandwidth
        }
        val lowPass = DspMath.LowPass(sampleRate, bandwidth)
        val highPass = DspMath.HighPass(
            sampleRate,
            if (privacyMode == PrivacyMode.PHONE_BEAM) 260f else 45f
        )
        val sourceStep = pcm.sampleRate.toDouble() / sampleRate
        val totalOutputFrames = (pcm.samples.size / sourceStep).toLong().coerceAtLeast(1L)
        val phaseStep = 2.0 * PI * carrier / sampleRate
        val fadeSeconds = if (privacyMode == PrivacyMode.PHONE_BEAM) 0.08 else 0.025
        val fadeFrames = max(1, (sampleRate * fadeSeconds).toInt())
        val block = FloatArray(BLOCK_FRAMES)
        val messageMonitor = FloatArray(BLOCK_FRAMES)
        var sourcePosition = 0.0
        var outputFrame = 0L
        var phase = 0.0
        var waveformCounter = 0
        val effectiveDepth = depth.coerceIn(0.05f, 1f)
        val effectiveMode = if (privacyMode == PrivacyMode.PHONE_BEAM) ModulationMode.DSB_SC else mode
        val outputGain = when (privacyMode) {
            PrivacyMode.PHONE_BEAM -> 0.18f + 0.30f * effectiveDepth
            PrivacyMode.EXTERNAL_ARRAY -> 0.50f + 0.35f * effectiveDepth
            PrivacyMode.STANDARD -> 0.58f + 0.40f * effectiveDepth
        }

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
                    privacyMode = privacyMode,
                    outputGain = outputGain
                )
            )

            while (!stopped.get() && outputFrame < totalOutputFrames) {
                val frames = min(BLOCK_FRAMES.toLong(), totalOutputFrames - outputFrame).toInt()
                for (i in 0 until frames) {
                    val rawMessage = DspMath.interpolate(pcm.samples, sourcePosition)
                    val bandLimited = highPass.process(lowPass.process(rawMessage))
                    val gated = if (privacyMode == PrivacyMode.PHONE_BEAM && abs(bandLimited) < 0.008f) 0f else bandLimited
                    val drive = if (privacyMode == PrivacyMode.PHONE_BEAM) 3.4f else 2.8f
                    val message = tanh((gated * drive).toDouble()).toFloat().coerceIn(-1f, 1f)
                    val carrierSample = cos(phase).toFloat()
                    val fadeIn = (outputFrame + i).toFloat() / fadeFrames
                    val fadeOut = (totalOutputFrames - (outputFrame + i)).toFloat() / fadeFrames
                    val fade = min(1f, min(fadeIn, fadeOut)).coerceAtLeast(0f)

                    val encoded = when (effectiveMode) {
                        ModulationMode.AM -> {
                            val encodedEnvelope = ((message + 1f) * 0.5f).coerceIn(0f, 1f)
                            val floor = if (privacyMode == PrivacyMode.PHONE_BEAM) 0.07f else 0.03f + (1f - effectiveDepth) * 0.45f
                            val ceiling = if (privacyMode == PrivacyMode.PHONE_BEAM) 0.48f else 0.95f
                            carrierSample * (floor + (ceiling - floor) * encodedEnvelope)
                        }

                        ModulationMode.DSB_SC -> {
                            if (privacyMode == PrivacyMode.PHONE_BEAM) {
                                val pilot = 0.10f
                                val sidebands = message * (0.22f + 0.30f * effectiveDepth)
                                carrierSample * (pilot + sidebands)
                            } else {
                                carrierSample * message * (0.25f + 0.70f * effectiveDepth)
                            }
                        }
                    }

                    block[i] = (encoded * outputGain * fade).coerceIn(-0.98f, 0.98f)
                    messageMonitor[i] = message
                    sourcePosition += sourceStep
                    phase += phaseStep
                    if (phase >= 2.0 * PI) phase -= 2.0 * PI
                }

                val written = track.write(block, 0, frames, AudioTrack.WRITE_BLOCKING)
                if (written < 0) error("AudioTrack write failed with code $written.")
                outputFrame += written

                waveformCounter++
                if (waveformCounter % 2 == 0) onWaveform(decimate(messageMonitor, written, 512))
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.flush() }
            track.release()
            activeTrack = null
            stopped.set(true)
        }
    }

    private fun createBestTrack(
        requestedSampleRate: Int,
        preferredDevice: AudioDeviceInfo?
    ): Pair<AudioTrack, Int> {
        val candidates = listOf(requestedSampleRate, 192_000, 176_400, 96_000, 88_200, 48_000, 44_100)
            .distinct()
            .filter { it in 44_100..requestedSampleRate.coerceAtMost(192_000) }
        var lastError: Throwable? = null
        for (rate in candidates) {
            try {
                val track = buildTrack(rate)
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

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        require(minBytes > 0) { "AudioTrack rejected $sampleRate Hz." }
        val bufferBytes = max(minBytes, BLOCK_FRAMES * 4 * 4)
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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
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

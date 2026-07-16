package com.vaan.ultracarrier.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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
        source: PreparedAudioSource,
        decoder: AudioFileDecoder,
        requestedSampleRate: Int,
        requestedCarrierHz: Float,
        depth: Float,
        thoughtMode: ThoughtMode,
        listeningPath: ListeningPath,
        preferredDevice: AudioDeviceInfo?,
        onStarted: (TransmissionReport) -> Unit,
        onWaveform: (FloatArray) -> Unit
    ) {
        stop()
        stopped.set(false)

        val sourceRate = when (source) {
            is PreparedAudioSource.Memory -> source.pcm.sampleRate
            is PreparedAudioSource.StreamFile -> source.info.sampleRate
        }
        val durationSeconds = when (source) {
            is PreparedAudioSource.Memory -> source.pcm.durationSeconds
            is PreparedAudioSource.StreamFile -> source.info.durationSeconds
        }
        val stereo = listeningPath != ListeningPath.PHONE_SPEAKER
        val (track, outputRate) = createBestTrack(requestedSampleRate, preferredDevice, stereo)
        activeTrack = track

        val carrier = if (thoughtMode == ThoughtMode.INNER_VOICE) 0f else {
            DspMath.clampCarrier(outputRate, requestedCarrierHz)
        }
        val maximumBandwidth = if (carrier > 0f) DspMath.safeMessageBandwidth(outputRate, carrier) else 4_000f
        val bandwidth = when (thoughtMode) {
            ThoughtMode.INNER_VOICE -> 3_800f
            ThoughtMode.PATENT_SSB -> min(maximumBandwidth, 3_400f)
            ThoughtMode.FM_SLOPE -> min(maximumBandwidth, 3_200f)
            ThoughtMode.BEAM_WHISPER -> min(maximumBandwidth, 3_100f)
        }
        val effectiveDepth = depth.coerceIn(0.05f, 1f)
        val outputGain = outputGain(thoughtMode, listeningPath, effectiveDepth)
        val totalOutputFrames = durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it * outputRate).toLong().coerceAtLeast(1L) }

        val session = EncoderSession(
            track = track,
            sampleRate = outputRate,
            stereo = stereo,
            carrier = carrier,
            bandwidth = bandwidth,
            depth = effectiveDepth,
            thoughtMode = thoughtMode,
            listeningPath = listeningPath,
            outputGain = outputGain,
            totalOutputFrames = totalOutputFrames,
            onWaveform = onWaveform
        )

        try {
            track.play()
            val routeName = track.routedDevice?.productName?.toString().orEmpty()
                .ifBlank { preferredDevice?.productName?.toString().orEmpty() }
                .ifBlank { "system-selected output" }
            onStarted(
                TransmissionReport(
                    actualSampleRate = outputRate,
                    actualCarrierHz = carrier,
                    messageBandwidthHz = bandwidth,
                    routedDeviceName = routeName,
                    thoughtMode = thoughtMode,
                    listeningPath = listeningPath,
                    outputGain = outputGain
                )
            )

            val resampler = StreamingResampler(sourceRate, outputRate) { chunk, count ->
                if (!stopped.get()) session.process(chunk, count)
            }

            when (source) {
                is PreparedAudioSource.Memory -> {
                    var offset = 0
                    while (!stopped.get() && offset < source.pcm.samples.size) {
                        val count = min(INPUT_CHUNK_FRAMES, source.pcm.samples.size - offset)
                        resampler.append(source.pcm.samples, offset, count)
                        offset += count
                    }
                }

                is PreparedAudioSource.StreamFile -> {
                    decoder.streamUri(source.uri) { chunk, count ->
                        if (stopped.get()) {
                            false
                        } else {
                            resampler.append(chunk, 0, count)
                            !stopped.get()
                        }
                    }
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

    private fun outputGain(mode: ThoughtMode, path: ListeningPath, depth: Float): Float = when (path) {
        ListeningPath.HEADPHONES -> when (mode) {
            ThoughtMode.INNER_VOICE -> 0.035f + 0.18f * depth
            ThoughtMode.PATENT_SSB, ThoughtMode.FM_SLOPE -> 0.05f + 0.22f * depth
            ThoughtMode.BEAM_WHISPER -> 0.07f + 0.24f * depth
        }

        ListeningPath.BONE_CONDUCTION -> when (mode) {
            ThoughtMode.INNER_VOICE -> 0.06f + 0.27f * depth
            else -> 0.08f + 0.28f * depth
        }

        ListeningPath.PHONE_SPEAKER -> when (mode) {
            ThoughtMode.INNER_VOICE -> 0.10f + 0.26f * depth
            ThoughtMode.PATENT_SSB, ThoughtMode.FM_SLOPE -> 0.12f + 0.30f * depth
            ThoughtMode.BEAM_WHISPER -> 0.15f + 0.34f * depth
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
        val bufferBytes = max(minBytes, OUTPUT_BLOCK_FRAMES * channelCount * 4 * 4)
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

    private class EncoderSession(
        private val track: AudioTrack,
        private val sampleRate: Int,
        private val stereo: Boolean,
        private val carrier: Float,
        bandwidth: Float,
        private val depth: Float,
        private val thoughtMode: ThoughtMode,
        listeningPath: ListeningPath,
        private val outputGain: Float,
        private val totalOutputFrames: Long?,
        private val onWaveform: (FloatArray) -> Unit
    ) {
        private val channels = if (stereo) 2 else 1
        private val lowPass = DspMath.LowPass(sampleRate, bandwidth)
        private val highPass = DspMath.HighPass(
            sampleRate,
            when (listeningPath) {
                ListeningPath.BONE_CONDUCTION -> 350f
                ListeningPath.HEADPHONES -> 220f
                ListeningPath.PHONE_SPEAKER -> 260f
            }
        )
        private val fadeFrames = max(1, (sampleRate * if (thoughtMode == ThoughtMode.INNER_VOICE) 0.12 else 0.08).toInt())
        private val constantPhaseStep = if (carrier > 0f) 2.0 * PI * carrier / sampleRate else 0.0
        private val fmDeviation = 350f + 1_650f * depth
        private val hilbert = HilbertTransformer()
        private val iq = FloatArray(2)
        private val outputBlock = FloatArray(OUTPUT_BLOCK_FRAMES * channels)
        private val monitor = FloatArray(OUTPUT_BLOCK_FRAMES)
        private var phase = 0.0
        private var gateEnvelope = 0f
        private var outputFrame = 0L
        private var waveformCounter = 0

        fun process(samples: FloatArray, count: Int) {
            var offset = 0
            while (offset < count) {
                val frames = min(OUTPUT_BLOCK_FRAMES, count - offset)
                for (i in 0 until frames) {
                    val raw = samples[offset + i]
                    val bandLimited = highPass.process(lowPass.process(raw))
                    val drive = if (thoughtMode == ThoughtMode.INNER_VOICE) 2.2f else 3.1f
                    val message = tanh((bandLimited * drive).toDouble()).toFloat().coerceIn(-1f, 1f)
                    val gateTarget = if (abs(message) > 0.006f) 1f else 0f
                    val gateSpeed = if (gateTarget > gateEnvelope) 0.035f else 0.004f
                    gateEnvelope += (gateTarget - gateEnvelope) * gateSpeed

                    val absoluteFrame = outputFrame + i
                    val fadeIn = absoluteFrame.toFloat() / fadeFrames
                    val fadeOut = totalOutputFrames?.let { (it - absoluteFrame).toFloat() / fadeFrames } ?: 1f
                    val fade = min(1f, min(fadeIn, fadeOut)).coerceAtLeast(0f)

                    val encoded = when (thoughtMode) {
                        ThoughtMode.INNER_VOICE -> message

                        ThoughtMode.PATENT_SSB -> {
                            hilbert.process(message, iq)
                            val upperSideband = iq[0] * cos(phase).toFloat() - iq[1] * sin(phase).toFloat()
                            phase += constantPhaseStep
                            upperSideband * gateEnvelope
                        }

                        ThoughtMode.FM_SLOPE -> {
                            val sample = cos(phase).toFloat() * gateEnvelope
                            val instantaneous = (carrier + fmDeviation * message)
                                .coerceIn(100f, sampleRate / 2f - 800f)
                            phase += 2.0 * PI * instantaneous / sampleRate
                            sample
                        }

                        ThoughtMode.BEAM_WHISPER -> {
                            val carrierSample = cos(phase).toFloat()
                            phase += constantPhaseStep
                            val pilot = 0.055f
                            val sideband = message * (0.18f + 0.42f * depth)
                            carrierSample * (pilot + sideband) * gateEnvelope
                        }
                    }
                    if (phase >= 2.0 * PI) phase %= 2.0 * PI

                    val output = (encoded * outputGain * fade).coerceIn(-0.96f, 0.96f)
                    if (stereo) {
                        val base = i * 2
                        outputBlock[base] = output
                        outputBlock[base + 1] = output
                    } else {
                        outputBlock[i] = output
                    }
                    monitor[i] = message
                }

                writeFully(outputBlock, frames * channels)
                outputFrame += frames
                offset += frames
                waveformCounter++
                if (waveformCounter % 2 == 0) onWaveform(decimate(monitor, frames, 512))
            }
        }

        private fun writeFully(buffer: FloatArray, count: Int) {
            var writtenTotal = 0
            while (writtenTotal < count) {
                val written = track.write(buffer, writtenTotal, count - writtenTotal, AudioTrack.WRITE_BLOCKING)
                if (written < 0) error("AudioTrack write failed with code $written.")
                if (written == 0) error("AudioTrack stopped accepting samples.")
                writtenTotal += written
            }
        }
    }

    private class StreamingResampler(
        private val sourceRate: Int,
        private val targetRate: Int,
        private val onChunk: (FloatArray, Int) -> Unit
    ) {
        private val ratio = sourceRate.toDouble() / targetRate
        private var tail = FloatArray(0)
        private var position = 0.0
        private val output = FloatArray(OUTPUT_BLOCK_FRAMES)
        private var outputCount = 0

        fun append(input: FloatArray, offset: Int, count: Int) {
            if (count <= 0) return
            if (sourceRate == targetRate) {
                var sourceOffset = offset
                var remaining = count
                while (remaining > 0) {
                    val amount = min(OUTPUT_BLOCK_FRAMES, remaining)
                    onChunk(input.copyOfRange(sourceOffset, sourceOffset + amount), amount)
                    sourceOffset += amount
                    remaining -= amount
                }
                return
            }

            val combined = FloatArray(tail.size + count)
            tail.copyInto(combined)
            input.copyInto(combined, tail.size, offset, offset + count)

            while (position + 1.0 < combined.size) {
                val index = floor(position).toInt()
                val fraction = (position - index).toFloat()
                val sample = combined[index] + (combined[index + 1] - combined[index]) * fraction
                emit(sample)
                position += ratio
            }

            val consumed = floor(position).toInt().coerceIn(0, max(0, combined.size - 1))
            tail = combined.copyOfRange(consumed, combined.size)
            position -= consumed
        }

        fun finish() {
            if (sourceRate != targetRate && tail.isNotEmpty()) emit(tail.last())
            flush()
        }

        private fun emit(sample: Float) {
            output[outputCount++] = sample.coerceIn(-1f, 1f)
            if (outputCount == output.size) flush()
        }

        private fun flush() {
            if (outputCount <= 0) return
            onChunk(output.copyOf(outputCount), outputCount)
            outputCount = 0
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
        private const val INPUT_CHUNK_FRAMES = 8_192
        private const val OUTPUT_BLOCK_FRAMES = 4_096
    }
}

private fun decimate(input: FloatArray, count: Int, target: Int): FloatArray {
    if (count <= 0) return FloatArray(0)
    if (count <= target) return input.copyOf(count)
    val output = FloatArray(target)
    val step = count.toDouble() / target
    for (i in output.indices) output[i] = input[(i * step).toInt().coerceAtMost(count - 1)]
    return output
}

package com.vaan.ultracarrier.collective

import android.content.ContentResolver
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

class ScalarStreamingAudioEngine(private val resolver: ContentResolver) {
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
                    family = CollectiveFamily.SCALAR_LAB,
                    modeLabel = config.scalarMode.label,
                    carrierHz = session.carrier
                )
            )
            val resampler = StreamingResampler(source.info.sampleRate, sampleRate) { mono, count ->
                if (!stopped.get()) session.process(mono, count) { stereo, stereoCount -> writeFully(track, stereo, stereoCount) }
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
        val pfd = resolver.openFileDescriptor(destination, "rwt") ?: error("Could not open save destination.")
        pfd.use {
            val writer = WaveWriter(FileOutputStream(it.fileDescriptor).channel, sampleRate, 2, format)
            try {
                var inputFrames = 0L
                val total = source.info.durationSeconds?.let { d -> (d * source.info.sampleRate).toLong() }
                val resampler = StreamingResampler(source.info.sampleRate, sampleRate) { mono, count ->
                    if (!stopped.get()) session.process(mono, count) { stereo, stereoCount -> writer.write(stereo, stereoCount) }
                }
                decoder.stream(source) { chunk, count, _ ->
                    if (stopped.get()) false else {
                        inputFrames += count
                        resampler.append(chunk, 0, count)
                        onProgress(total?.takeIf { it > 0 }?.let { t -> (inputFrames.toDouble() / t).coerceIn(0.0, 1.0) })
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
        val carrier: Float = config.carrierHz.coerceIn(500f, sampleRate / 2f - 700f)
        private val low = OnePoleLowPass(sampleRate, 3_600f)
        private val high = OnePoleHighPass(sampleRate, 150f)
        private val block = FloatArray(BLOCK * 2)
        private val scope = FloatArray(BLOCK)
        private var phase = 0.0
        private var phase2 = 0.0
        private var phase3 = 0.0
        private var outputFrame = 0L
        private var scopeCounter = 0

        fun process(mono: FloatArray, count: Int, sink: (FloatArray, Int) -> Unit) {
            var offset = 0
            while (offset < count) {
                val frames = min(BLOCK, count - offset)
                for (i in 0 until frames) {
                    val frame = outputFrame + i
                    val raw = mono[offset + i]
                    val voice = tanh((high.process(low.process(raw)) * 2.25f).toDouble()).toFloat()
                    val pair = scalarSample(config.scalarMode, voice, frame)
                    val gain = when (config.listeningPath) {
                        com.vaan.ultracarrier.audio.ListeningPath.PHONE_SPEAKER -> .18f + .22f * config.presence
                        com.vaan.ultracarrier.audio.ListeningPath.EXTERNAL_ARRAY -> .22f + .30f * config.presence
                        else -> .12f + .20f * config.presence
                    }
                    val l = (pair.first * gain).coerceIn(-.96f, .96f)
                    val r = (pair.second * gain).coerceIn(-.96f, .96f)
                    block[i * 2] = l
                    block[i * 2 + 1] = r
                    scope[i] = (l + r) * .5f
                }
                sink(block, frames * 2)
                outputFrame += frames
                offset += frames
                scopeCounter++
                if (scopeCounter % 2 == 0) onScope(decimate(scope, frames, 512), sampleRate)
            }
        }

        private fun scalarSample(mode: ScalarMode, voice: Float, frame: Long): Pair<Float, Float> {
            val t = frame.toDouble() / sampleRate
            val p = config.presence.coerceIn(.05f, 1f)
            val rate = config.elfRateHz.coerceIn(.25f, 80f)
            val depth = config.elfDepth.coerceIn(0f, .98f)
            val motionRate = config.ditherRateHz.coerceIn(.02f, 5f)
            val slow = 1f - depth * .5f + depth * .5f * (1f + sin(2.0 * PI * rate * t).toFloat())
            val envelope = sqrt((1f + p * voice * slow).coerceIn(.02f, 1.98f))
            val step = 2.0 * PI * carrier / sampleRate
            val target = steeringPhase(carrier, config.spacingMm, config.targetAngleDeg)
            val motion = 2.0 * PI * motionRate * t
            val result = when (mode) {
                ScalarMode.LONGITUDINAL_PAIR -> {
                    val a = cos(phase).toFloat() * envelope
                    phase += step
                    a to (-a)
                }
                ScalarMode.PHASE_CONJUGATE_PAIR -> {
                    val l = cos(phase + target).toFloat() * envelope
                    val r = cos(-phase + target).toFloat() * envelope
                    phase += step
                    l to r
                }
                ScalarMode.ZERO_VECTOR_STRESS -> {
                    val base = cos(phase).toFloat() * envelope
                    val residual = voice * .06f + sin(2.0 * PI * rate * t).toFloat() * .015f
                    phase += step
                    base to (-base + residual)
                }
                ScalarMode.SCALAR_INTERFEROMETER -> {
                    val f1 = (carrier - rate * .5f).coerceAtLeast(500f)
                    val f2 = (carrier + rate * .5f).coerceAtMost(sampleRate / 2f - 700f)
                    val l = cos(phase + target).toFloat() * envelope
                    val r = cos(phase2 - target).toFloat() * envelope
                    phase += 2.0 * PI * f1 / sampleRate
                    phase2 += 2.0 * PI * f2 / sampleRate
                    l to r
                }
                ScalarMode.WHITTAKER_SPECTRAL_PAIR -> {
                    val f2 = (carrier * .809f).coerceAtLeast(500f)
                    val f3 = (carrier * 1.118f).coerceAtMost(sampleRate / 2f - 700f)
                    val l = (cos(phase) + .68 * cos(phase2 + target) + .42 * cos(phase3 + target * 2.0)).toFloat() * envelope / 2.1f
                    val r = (cos(-phase) + .68 * cos(-phase2 - target) + .42 * cos(-phase3 - target * 2.0)).toFloat() * envelope / 2.1f
                    phase += step
                    phase2 += 2.0 * PI * f2 / sampleRate
                    phase3 += 2.0 * PI * f3 / sampleRate
                    l to r
                }
                ScalarMode.TESLA_BIFILAR_SPIRAL -> {
                    val spin = sin(motion) * PI
                    val l = cos(phase + spin).toFloat() * envelope
                    val r = cos(phase - spin + PI).toFloat() * envelope
                    phase += step
                    l to r
                }
                ScalarMode.COUNTER_ROTATING_VORTEX -> {
                    val spin = motion
                    val l = (cos(phase + spin) * .72 + sin(phase - spin) * .28).toFloat() * envelope
                    val r = (cos(phase - spin) * .72 + sin(phase + spin) * .28).toFloat() * envelope
                    phase += step
                    l to r
                }
                ScalarMode.STANDING_POTENTIAL_NODE -> {
                    val moving = cos(phase).toFloat()
                    val counter = cos(-phase + target).toFloat()
                    val node = (moving + counter) * .5f * envelope
                    phase += step
                    node to (-node * .92f)
                }
                ScalarMode.DIFFERENCE_FREQUENCY_PUMP -> {
                    val f1 = (carrier - rate * .5f).coerceAtLeast(500f)
                    val f2 = (carrier + rate * .5f).coerceAtMost(sampleRate / 2f - 700f)
                    val l = cos(phase).toFloat() * sqrt((1f + p * voice).coerceIn(.02f, 1.98f))
                    val r = cos(phase2 + target).toFloat() * sqrt((1f + p * voice).coerceIn(.02f, 1.98f))
                    phase += 2.0 * PI * f1 / sampleRate
                    phase2 += 2.0 * PI * f2 / sampleRate
                    l to r
                }
                ScalarMode.NESTED_ELF_SCALAR -> {
                    val fast = .78f + .22f * sin(2.0 * PI * 40.0 * t).toFloat()
                    val l = cos(phase + target).toFloat() * envelope * fast
                    val r = cos(-phase - target).toFloat() * envelope * fast
                    phase += step
                    l to r
                }
                ScalarMode.TIME_REVERSED_CHIRP -> {
                    val period = 2.0
                    val u = (t % period) / period
                    val forward = u < .5
                    val local = if (forward) u * 2.0 else (1.0 - u) * 2.0
                    val sweep = 2_400f
                    val f = (carrier - sweep * .5f + sweep * local.toFloat()).coerceIn(500f, sampleRate / 2f - 700f)
                    val l = cos(phase + target).toFloat() * envelope
                    val r = cos(-phase - target).toFloat() * envelope
                    phase += 2.0 * PI * f / sampleRate
                    l to r
                }
                ScalarMode.SPIRAL_PHASE_LATTICE -> {
                    val f2 = (carrier - 618f).coerceAtLeast(500f)
                    val f3 = (carrier + 1000f).coerceAtMost(sampleRate / 2f - 700f)
                    val spin = motion
                    val l = (cos(phase + spin) + cos(phase2 + spin * 2.0) + cos(phase3 + spin * 3.0)).toFloat() * envelope / 3f
                    val r = (cos(phase - spin) + cos(phase2 - spin * 2.0) + cos(phase3 - spin * 3.0)).toFloat() * envelope / 3f
                    phase += step
                    phase2 += 2.0 * PI * f2 / sampleRate
                    phase3 += 2.0 * PI * f3 / sampleRate
                    l to r
                }
            }
            wrap()
            return result
        }

        private fun steeringPhase(freq: Float, spacingMm: Float, angleDeg: Float): Double {
            val d = spacingMm.coerceIn(1f, 80f) / 1000.0
            val a = Math.toRadians(angleDeg.coerceIn(-80f, 80f).toDouble())
            return 2.0 * PI * freq * d * sin(a) / 343.0
        }

        private fun wrap() {
            val tau = 2.0 * PI
            if (abs(phase) > tau * 32) phase %= tau
            if (abs(phase2) > tau * 32) phase2 %= tau
            if (abs(phase3) > tau * 32) phase3 %= tau
        }
    }

    private class StreamingResampler(sourceRate: Int, targetRate: Int, private val emit: (FloatArray, Int) -> Unit) {
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
                    off += n
                    left -= n
                }
                return
            }
            val combined = FloatArray(tail.size + amount)
            tail.copyInto(combined)
            input.copyInto(combined, tail.size, offset, offset + amount)
            while (position + 1.0 < combined.size) {
                val i = floor(position).toInt()
                val f = (position - i).toFloat()
                output[count++] = combined[i] + (combined[i + 1] - combined[i]) * f
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
                        b.put((v and 255).toByte())
                        b.put(((v ushr 8) and 255).toByte())
                        b.put(((v ushr 16) and 255).toByte())
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
        throw IllegalStateException("Could not open stereo AudioTrack.", last)
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

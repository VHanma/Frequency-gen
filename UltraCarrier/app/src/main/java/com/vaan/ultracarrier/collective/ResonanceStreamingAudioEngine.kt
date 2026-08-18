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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh

class ResonanceStreamingAudioEngine(private val resolver: ContentResolver) {
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
        mode: ResonanceMode,
        preferredDevice: AudioDeviceInfo?,
        onStarted: (CollectiveReport) -> Unit,
        onScope: (FloatArray, Int) -> Unit
    ) {
        stop()
        stopped.set(false)
        val (track, sampleRate) = createBestTrack(config.requestedSampleRate, preferredDevice)
        activeTrack = track
        val session = RenderSession(config.copy(requestedSampleRate = sampleRate), mode, sampleRate, onScope)
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
                    modeLabel = mode.label,
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
        mode: ResonanceMode,
        destination: Uri,
        format: ExportFormat,
        onProgress: (Double?) -> Unit,
        onScope: (FloatArray, Int) -> Unit
    ) {
        stop()
        stopped.set(false)
        val sampleRate = config.requestedSampleRate.coerceIn(44_100, 192_000)
        val session = RenderSession(config.copy(requestedSampleRate = sampleRate), mode, sampleRate, onScope)
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
        private val mode: ResonanceMode,
        private val sampleRate: Int,
        private val onScope: (FloatArray, Int) -> Unit
    ) {
        val carrier: Float = config.carrierHz.coerceIn(500f, sampleRate / 2f - 700f)
        private val low = OnePoleLowPass(sampleRate, 3_600f)
        private val high = OnePoleHighPass(sampleRate, 150f)
        private val math = ExtendedScalarMath(config, sampleRate, carrier)
        private val block = FloatArray(BLOCK * 2)
        private val scope = FloatArray(BLOCK)
        private var outputFrame = 0L
        private var scopeCounter = 0

        fun process(mono: FloatArray, count: Int, sink: (FloatArray, Int) -> Unit) {
            var offset = 0
            while (offset < count) {
                val frames = min(BLOCK, count - offset)
                for (i in 0 until frames) {
                    val raw = mono[offset + i]
                    val voice = tanh((high.process(low.process(raw)) * 2.25f).toDouble()).toFloat()
                    val pair = math.sample(mode, voice, outputFrame + i)
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

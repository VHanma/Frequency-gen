package com.vaan.ultracarrier.collective

import android.content.ContentResolver
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.min

class CollectiveStreamDecoder(private val resolver: ContentResolver) {

    fun inspectUri(uri: Uri): StreamInfo = if (isWavUri(uri)) {
        resolver.openInputStream(uri)?.buffered()?.use { inspectWav(it) }
            ?: error("The selected file could not be opened.")
    } else {
        inspectExtractor { ex -> setUriSource(ex, uri) }
    }

    fun inspectFile(file: File): StreamInfo = if (isWavFile(file)) {
        file.inputStream().buffered().use { inspectWav(it) }
    } else {
        inspectExtractor { ex -> ex.setDataSource(file.absolutePath) }
    }

    fun stream(source: CollectiveSource, onChunk: (FloatArray, Int, Int) -> Boolean) {
        when (source) {
            is CollectiveSource.UriSource -> {
                if (isWavUri(source.uri)) {
                    resolver.openInputStream(source.uri)?.buffered()?.use { streamWav(it, onChunk) }
                        ?: error("The selected file could not be opened.")
                } else {
                    streamExtractor({ ex -> setUriSource(ex, source.uri) }, onChunk)
                }
            }
            is CollectiveSource.FileSource -> {
                if (isWavFile(source.file)) {
                    source.file.inputStream().buffered().use { streamWav(it, onChunk) }
                } else {
                    streamExtractor({ ex -> ex.setDataSource(source.file.absolutePath) }, onChunk)
                }
            }
        }
    }

    private fun isWavUri(uri: Uri): Boolean = runCatching {
        resolver.openInputStream(uri)?.buffered()?.use { input ->
            input.mark(16)
            val h = ByteArray(12)
            val n = input.read(h)
            input.reset()
            n == 12 && isWavHeader(h)
        } ?: false
    }.getOrDefault(false)

    private fun isWavFile(file: File): Boolean = runCatching {
        file.inputStream().buffered().use { input ->
            input.mark(16)
            val h = ByteArray(12)
            val n = input.read(h)
            input.reset()
            n == 12 && isWavHeader(h)
        }
    }.getOrDefault(false)

    private fun isWavHeader(h: ByteArray): Boolean {
        if (h.size < 12) return false
        val riff = String(h, 0, 4, StandardCharsets.US_ASCII)
        return (riff == "RIFF" || riff == "RF64") && String(h, 8, 4, StandardCharsets.US_ASCII) == "WAVE"
    }

    private data class WavMeta(
        val formatTag: Int,
        val channels: Int,
        val sampleRate: Int,
        val bits: Int,
        val bytesPerSample: Int,
        val dataBytes: Long
    )

    private fun inspectWav(input: InputStream): StreamInfo {
        val meta = parseWavToData(input)
        val frameBytes = meta.bytesPerSample * meta.channels
        val duration = if (meta.dataBytes == Long.MAX_VALUE) null
        else meta.dataBytes.toDouble() / frameBytes / meta.sampleRate
        return StreamInfo(meta.sampleRate, meta.channels, duration, "${meta.bits}-bit WAV")
    }

    private fun streamWav(input: InputStream, onChunk: (FloatArray, Int, Int) -> Boolean) {
        val meta = parseWavToData(input)
        val frameBytes = meta.bytesPerSample * meta.channels
        val raw = ByteArray(frameBytes * WAV_FRAMES + frameBytes)
        val mono = FloatArray(WAV_FRAMES)
        var carry = 0
        var remaining = meta.dataBytes

        while (remaining > 0L) {
            val capacity = raw.size - carry
            val request = if (remaining == Long.MAX_VALUE) capacity else min(capacity.toLong(), remaining).toInt()
            val read = input.read(raw, carry, request)
            if (read < 0) break
            if (read == 0) continue
            if (remaining != Long.MAX_VALUE) remaining -= read.toLong()

            val total = carry + read
            val frames = total / frameBytes
            var offset = 0
            for (frame in 0 until frames) {
                var sum = 0f
                repeat(meta.channels) {
                    sum += decodeWavSample(raw, offset, meta.formatTag, meta.bits)
                    offset += meta.bytesPerSample
                }
                mono[frame] = (sum / meta.channels).coerceIn(-1f, 1f)
            }
            val consumed = frames * frameBytes
            carry = total - consumed
            if (carry > 0) System.arraycopy(raw, consumed, raw, 0, carry)
            if (frames > 0 && !onChunk(mono, frames, meta.sampleRate)) return
        }
    }

    private fun parseWavToData(input: InputStream): WavMeta {
        val header = ByteArray(12)
        readFully(input, header)
        require(isWavHeader(header)) { "Invalid WAV header." }
        val rf64 = String(header, 0, 4, StandardCharsets.US_ASCII) == "RF64"
        var formatTag = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var rf64DataBytes: Long? = null

        while (true) {
            val chunk = ByteArray(8)
            readFully(input, chunk)
            val id = String(chunk, 0, 4, StandardCharsets.US_ASCII)
            val size = littleUInt(chunk, 4)
            when (id) {
                "ds64" -> {
                    val take = min(size, 64L).toInt()
                    val b = ByteArray(take)
                    readFully(input, b)
                    if (take >= 16) rf64DataBytes = littleLong(b, 8)
                    skipFully(input, size - take)
                }
                "fmt " -> {
                    val take = min(size, 64L).toInt()
                    val b = ByteArray(take)
                    readFully(input, b)
                    require(take >= 16) { "Damaged WAV format chunk." }
                    formatTag = littleShort(b, 0)
                    channels = littleShort(b, 2)
                    sampleRate = littleInt(b, 4)
                    bits = littleShort(b, 14)
                    skipFully(input, size - take)
                }
                "data" -> {
                    require(channels in 1..32) { "Unsupported WAV channel count: $channels" }
                    require(sampleRate in 8_000..384_000) { "Unsupported WAV sample rate: $sampleRate Hz" }
                    require(formatTag == 1 || formatTag == 3) { "Use PCM or float WAV." }
                    val bps = (bits + 7) / 8
                    require(bps in 1..4) { "Unsupported WAV bit depth: $bits" }
                    val dataBytes = if (rf64 && size == 0xffff_ffffL) rf64DataBytes ?: Long.MAX_VALUE else size
                    return WavMeta(formatTag, channels, sampleRate, bits, bps, dataBytes)
                }
                else -> skipFully(input, size)
            }
            if ((size and 1L) == 1L) skipFully(input, 1L)
        }
    }

    private fun inspectExtractor(setSource: (MediaExtractor) -> Unit): StreamInfo {
        val ex = MediaExtractor()
        try {
            setSource(ex)
            val idx = findAudioTrack(ex)
            val f = ex.getTrackFormat(idx)
            val rate = integerOr(f, MediaFormat.KEY_SAMPLE_RATE, 48_000)
            val channels = integerOr(f, MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            val durationUs = longOr(f, MediaFormat.KEY_DURATION, -1L)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: "audio"
            return StreamInfo(rate, channels, if (durationUs > 0) durationUs / 1_000_000.0 else null, mime.substringAfter("audio/", mime).uppercase())
        } finally { ex.release() }
    }

    private fun streamExtractor(setSource: (MediaExtractor) -> Unit, onChunk: (FloatArray, Int, Int) -> Boolean) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            setSource(ex)
            val idx = findAudioTrack(ex)
            ex.selectTrack(idx)
            val inFormat = ex.getTrackFormat(idx)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: error("Missing audio MIME type.")
            codec = MediaCodec.createDecoderByType(mime).apply { configure(inFormat, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var keep = true
            var rate = integerOr(inFormat, MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var channels = integerOr(inFormat, MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputDone && keep) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i) ?: error("Null codec input buffer.")
                        b.clear()
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                when (val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = integerOr(f, MediaFormat.KEY_SAMPLE_RATE, rate)
                        channels = integerOr(f, MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        encoding = integerOr(f, MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (o >= 0) {
                        val b = codec.getOutputBuffer(o)
                        if (b != null && info.size > 0) {
                            b.position(info.offset)
                            b.limit(info.offset + info.size)
                            b.order(ByteOrder.nativeOrder())
                            val mono = downmix(b, encoding, channels)
                            if (mono.isNotEmpty()) keep = onChunk(mono, mono.size, rate)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private fun downmix(buffer: ByteBuffer, encoding: Int, channels: Int): FloatArray = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val f = buffer.asFloatBuffer()
            val frames = f.remaining() / channels
            FloatArray(frames) {
                var sum = 0f
                repeat(channels) { sum += f.get() }
                (sum / channels).coerceIn(-1f, 1f)
            }
        }
        AudioFormat.ENCODING_PCM_16BIT -> {
            val s = buffer.asShortBuffer()
            val frames = s.remaining() / channels
            FloatArray(frames) {
                var sum = 0f
                repeat(channels) { sum += s.get() / 32768f }
                (sum / channels).coerceIn(-1f, 1f)
            }
        }
        else -> error("Unsupported decoded PCM format $encoding")
    }

    private fun setUriSource(ex: MediaExtractor, uri: Uri) {
        val afd = resolver.openAssetFileDescriptor(uri, "r") ?: error("Could not open selected audio.")
        afd.use {
            if (it.declaredLength >= 0) ex.setDataSource(it.fileDescriptor, it.startOffset, it.declaredLength)
            else ex.setDataSource(it.fileDescriptor)
        }
    }

    private fun findAudioTrack(ex: MediaExtractor): Int = (0 until ex.trackCount).firstOrNull { i ->
        ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: error("No audio track was found.")

    private fun decodeWavSample(b: ByteArray, o: Int, tag: Int, bits: Int): Float {
        if (tag == 3 && bits == 32) return Float.fromBits(littleInt(b, o)).coerceIn(-1f, 1f)
        return when (bits) {
            8 -> ((b[o].toInt() and 255) - 128) / 128f
            16 -> littleShort(b, o).toShort().toInt() / 32768f
            24 -> {
                var v = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16)
                if ((v and 0x800000) != 0) v = v or -0x1000000
                v / 8_388_608f
            }
            32 -> littleInt(b, o) / 2_147_483_648f
            else -> error("Unsupported WAV bit depth $bits")
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var off = 0
        while (off < target.size) {
            val n = input.read(target, off, target.size - off)
            if (n < 0) error("Unexpected end of audio file.")
            off += n
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count.coerceAtLeast(0)
        while (left > 0) {
            val n = input.skip(left)
            if (n > 0) left -= n else { if (input.read() < 0) break else left-- }
        }
    }

    private fun littleShort(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun littleInt(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)
    private fun littleUInt(b: ByteArray, o: Int) = littleInt(b, o).toLong() and 0xffff_ffffL
    private fun littleLong(b: ByteArray, o: Int): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[o + i].toLong() and 255L) shl (8 * i)); return v }
    private fun integerOr(f: MediaFormat, key: String, fallback: Int) = if (f.containsKey(key)) f.getInteger(key) else fallback
    private fun longOr(f: MediaFormat, key: String, fallback: Long) = if (f.containsKey(key)) f.getLong(key) else fallback

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val WAV_FRAMES = 4096
    }
}

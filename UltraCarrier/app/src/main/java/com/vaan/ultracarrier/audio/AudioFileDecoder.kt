package com.vaan.ultracarrier.audio

import android.content.ContentResolver
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.min

class AudioFileDecoder(private val contentResolver: ContentResolver) {

    suspend fun decodeUri(uri: Uri): PcmAudio {
        contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            input.mark(16)
            val header = ByteArray(12)
            val count = input.read(header)
            input.reset()
            if (count == 12 && isWavHeader(header)) return decodeWav(input)
        }

        return decodeExtractor { extractor ->
            val afd = contentResolver.openAssetFileDescriptor(uri, "r")
                ?: error("The selected file could not be opened.")
            afd.use {
                if (it.declaredLength >= 0) {
                    extractor.setDataSource(it.fileDescriptor, it.startOffset, it.declaredLength)
                } else {
                    extractor.setDataSource(it.fileDescriptor)
                }
            }
        }
    }

    suspend fun decodeFile(file: File): PcmAudio {
        file.inputStream().buffered().use { input ->
            input.mark(16)
            val header = ByteArray(12)
            val count = input.read(header)
            input.reset()
            if (count == 12 && isWavHeader(header)) return decodeWav(input)
        }
        return decodeExtractor { extractor -> extractor.setDataSource(file.absolutePath) }
    }

    private fun isWavHeader(header: ByteArray): Boolean {
        val riff = String(header, 0, 4, StandardCharsets.US_ASCII)
        val wave = String(header, 8, 4, StandardCharsets.US_ASCII)
        return (riff == "RIFF" || riff == "RF64") && wave == "WAVE"
    }

    private fun decodeWav(input: InputStream): PcmAudio {
        val riffHeader = ByteArray(12)
        readFully(input, riffHeader)
        require(isWavHeader(riffHeader)) { "The WAV header is invalid." }
        val rf64 = String(riffHeader, 0, 4, StandardCharsets.US_ASCII) == "RF64"

        var formatTag = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var rf64DataSize: Long? = null

        while (true) {
            val chunkHeader = ByteArray(8)
            if (!readFullyOrEof(input, chunkHeader)) break
            val chunkId = String(chunkHeader, 0, 4, StandardCharsets.US_ASCII)
            val rawChunkSize = littleUnsignedInt(chunkHeader, 4)

            when (chunkId) {
                "ds64" -> {
                    val headBytes = min(rawChunkSize, 64L).toInt()
                    val ds64 = ByteArray(headBytes)
                    readFully(input, ds64)
                    if (ds64.size >= 16) rf64DataSize = littleLong(ds64, 8)
                    skipFully(input, rawChunkSize - headBytes)
                }

                "fmt " -> {
                    val headBytes = min(rawChunkSize, 64L).toInt()
                    val fmt = ByteArray(headBytes)
                    readFully(input, fmt)
                    require(fmt.size >= 16) { "The WAV format chunk is incomplete." }
                    formatTag = littleShort(fmt, 0)
                    channels = littleShort(fmt, 2)
                    sampleRate = littleInt(fmt, 4)
                    bitsPerSample = littleShort(fmt, 14)
                    skipFully(input, rawChunkSize - headBytes)
                }

                "data" -> {
                    require(channels in 1..8) { "Unsupported WAV channel count: $channels." }
                    require(sampleRate in 8_000..384_000) { "Unsupported WAV sample rate: $sampleRate Hz." }
                    require(formatTag == 1 || formatTag == 3) {
                        "WAV encoding $formatTag is unsupported. Use PCM or floating-point WAV."
                    }
                    val bytesPerSample = (bitsPerSample + 7) / 8
                    require(bytesPerSample in 1..4) { "Unsupported WAV bit depth: $bitsPerSample-bit." }
                    val bytesPerFrame = bytesPerSample * channels
                    val dataBytes = if (rf64 && rawChunkSize == 0xffff_ffffL) {
                        rf64DataSize ?: error("RF64 WAV is missing its ds64 data-size field.")
                    } else {
                        rawChunkSize
                    }

                    val output = FloatArrayBuilder()
                    val buffer = ByteArray(STREAM_BUFFER_BYTES + bytesPerFrame)
                    var carry = 0
                    var remaining = dataBytes

                    while (remaining > 0L) {
                        val request = min(STREAM_BUFFER_BYTES.toLong(), remaining).toInt()
                        val read = input.read(buffer, carry, request)
                        if (read < 0) break
                        if (read == 0) continue
                        remaining -= read.toLong()
                        val available = carry + read
                        val completeBytes = available - (available % bytesPerFrame)
                        var offset = 0
                        while (offset < completeBytes) {
                            var sum = 0f
                            repeat(channels) {
                                sum += decodeWavSample(buffer, offset, formatTag, bitsPerSample)
                                offset += bytesPerSample
                            }
                            output.add((sum / channels).coerceIn(-1f, 1f))
                        }
                        carry = available - completeBytes
                        if (carry > 0) {
                            System.arraycopy(buffer, completeBytes, buffer, 0, carry)
                        }
                    }

                    val samples = output.toArray()
                    if (samples.isEmpty()) error("The WAV file contains no complete audio frames.")
                    removeDcAndNormalize(samples)
                    return PcmAudio(
                        samples = samples,
                        sampleRate = sampleRate,
                        durationSeconds = samples.size.toDouble() / sampleRate
                    )
                }

                else -> skipFully(input, rawChunkSize)
            }

            if (rawChunkSize and 1L == 1L) skipFully(input, 1L)
        }

        error("The WAV file has no audio data chunk.")
    }

    private fun decodeWavSample(bytes: ByteArray, offset: Int, formatTag: Int, bits: Int): Float {
        if (formatTag == 3 && bits == 32) {
            return Float.fromBits(littleInt(bytes, offset)).coerceIn(-1f, 1f)
        }
        return when (bits) {
            8 -> ((bytes[offset].toInt() and 0xff) - 128) / 128f
            16 -> littleSignedShort(bytes, offset) / 32768f
            24 -> {
                var value = (bytes[offset].toInt() and 0xff) or
                    ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xff) shl 16)
                if (value and 0x800000 != 0) value = value or -0x1000000
                value / 8_388_608f
            }
            32 -> littleInt(bytes, offset) / 2_147_483_648f
            else -> error("Unsupported WAV bit depth: $bits-bit.")
        }
    }

    private fun decodeExtractor(setSource: (MediaExtractor) -> Unit): PcmAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            setSource(extractor)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track was found in this file.")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("The audio MIME type is missing.")

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val output = FloatArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var sampleRate = inputFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var channels = inputFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                            ?: error("Decoder returned a null input buffer.")
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        pcmEncoding = format.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            appendDownmixed(buffer, pcmEncoding, channels, output)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    }
                }
            }

            val samples = output.toArray()
            if (samples.isEmpty()) error("The decoder produced no PCM samples.")
            removeDcAndNormalize(samples)
            return PcmAudio(samples, sampleRate, samples.size.toDouble() / sampleRate)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun appendDownmixed(
        buffer: java.nio.ByteBuffer,
        pcmEncoding: Int,
        channels: Int,
        output: FloatArrayBuilder
    ) {
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                while (floats.remaining() >= channels) {
                    var sum = 0f
                    repeat(channels) { sum += floats.get() }
                    output.add((sum / channels).coerceIn(-1f, 1f))
                }
            }

            AudioFormat.ENCODING_PCM_16BIT -> {
                val shorts = buffer.asShortBuffer()
                while (shorts.remaining() >= channels) {
                    var sum = 0f
                    repeat(channels) { sum += shorts.get() / 32768f }
                    output.add((sum / channels).coerceIn(-1f, 1f))
                }
            }

            else -> error("Decoder PCM format $pcmEncoding is unsupported. Try WAV, MP3, or FLAC.")
        }
    }

    private fun removeDcAndNormalize(samples: FloatArray) {
        var mean = 0.0
        for (sample in samples) mean += sample
        mean /= samples.size

        var peak = 0f
        for (i in samples.indices) {
            samples[i] = (samples[i] - mean.toFloat()).coerceIn(-1f, 1f)
            peak = maxOf(peak, abs(samples[i]))
        }
        if (peak > 0.0001f) {
            val gain = (0.95f / peak).coerceAtMost(12f)
            for (i in samples.indices) samples[i] = (samples[i] * gain).coerceIn(-0.98f, 0.98f)
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) error("Unexpected end of audio file.")
            if (read == 0) continue
            offset += read
        }
    }

    private fun readFullyOrEof(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return offset == 0
            if (read == 0) continue
            offset += read
        }
        return true
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes.coerceAtLeast(0L)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) break
                remaining--
            }
        }
    }

    private fun littleShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleSignedShort(bytes: ByteArray, offset: Int): Int = littleShort(bytes, offset).toShort().toInt()

    private fun littleInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun littleUnsignedInt(bytes: ByteArray, offset: Int): Long = littleInt(bytes, offset).toLong() and 0xffff_ffffL

    private fun littleLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((bytes[offset + i].toLong() and 0xffL) shl (8 * i))
        return value
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}

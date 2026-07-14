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
        val bytes = input.readBytes()
        require(bytes.size >= 44) { "The WAV file is too short or damaged." }
        require(bytes.size <= MAX_WAV_BYTES) { "WAV file is too large. Trim it below five minutes." }
        require(isWavHeader(bytes.copyOfRange(0, 12))) { "The WAV header is invalid." }

        var formatTag = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataStart = -1
        var dataSize = 0
        var position = 12

        while (position + 8 <= bytes.size) {
            val chunkId = String(bytes, position, 4, StandardCharsets.US_ASCII)
            val chunkSize = littleInt(bytes, position + 4).coerceAtLeast(0)
            val chunkStart = position + 8
            if (chunkStart > bytes.size) break
            val available = min(chunkSize, bytes.size - chunkStart)

            when (chunkId) {
                "fmt " -> if (available >= 16) {
                    formatTag = littleShort(bytes, chunkStart)
                    channels = littleShort(bytes, chunkStart + 2)
                    sampleRate = littleInt(bytes, chunkStart + 4)
                    bitsPerSample = littleShort(bytes, chunkStart + 14)
                }

                "data" -> {
                    dataStart = chunkStart
                    dataSize = available
                    break
                }
            }
            position = chunkStart + available + (available and 1)
        }

        require(channels in 1..8) { "Unsupported WAV channel count: $channels." }
        require(sampleRate in 8_000..384_000) { "Unsupported WAV sample rate: $sampleRate Hz." }
        require(dataStart >= 0 && dataSize > 0) { "The WAV file has no audio data chunk." }
        require(formatTag == 1 || formatTag == 3) {
            "WAV encoding $formatTag is unsupported. Use PCM or floating-point WAV."
        }

        val bytesPerSample = (bitsPerSample + 7) / 8
        require(bytesPerSample in 1..4) { "Unsupported WAV bit depth: $bitsPerSample-bit." }
        val bytesPerFrame = bytesPerSample * channels
        val frameCount = min(dataSize / bytesPerFrame, MAX_SECONDS * sampleRate)
        require(frameCount > 0) { "The WAV file contains no complete audio frames." }

        val output = FloatArray(frameCount)
        var offset = dataStart
        for (frame in 0 until frameCount) {
            var sum = 0f
            repeat(channels) {
                sum += decodeWavSample(bytes, offset, formatTag, bitsPerSample)
                offset += bytesPerSample
            }
            output[frame] = (sum / channels).coerceIn(-1f, 1f)
        }
        removeDcAndNormalize(output)
        return PcmAudio(
            samples = output,
            sampleRate = sampleRate,
            durationSeconds = output.size.toDouble() / sampleRate
        )
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
            var maxSamples = MAX_SECONDS * sampleRate

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
                        maxSamples = MAX_SECONDS * sampleRate
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            appendDownmixed(buffer, pcmEncoding, channels, output, maxSamples)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    }
                }

                if (output.size >= maxSamples) error("Audio is longer than $MAX_SECONDS seconds. Trim it and try again.")
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
        output: FloatArrayBuilder,
        maxSamples: Int
    ) {
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                while (floats.remaining() >= channels && output.size < maxSamples) {
                    var sum = 0f
                    repeat(channels) { sum += floats.get() }
                    output.add((sum / channels).coerceIn(-1f, 1f))
                }
            }

            AudioFormat.ENCODING_PCM_16BIT -> {
                val shorts = buffer.asShortBuffer()
                while (shorts.remaining() >= channels && output.size < maxSamples) {
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

    private fun littleShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleSignedShort(bytes: ByteArray, offset: Int): Int = littleShort(bytes, offset).toShort().toInt()

    private fun littleInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_SECONDS = 300
        private const val MAX_WAV_BYTES = 160 * 1024 * 1024
    }
}

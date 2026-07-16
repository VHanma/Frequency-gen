package com.vaan.ultracarrier.audio

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

class AudioFileDecoder(private val contentResolver: ContentResolver) {

    suspend fun inspectUri(uri: Uri): AudioStreamInfo {
        return if (isWavUri(uri)) inspectWavUri(uri) else inspectExtractor(uri)
    }

    fun streamUri(uri: Uri, onChunk: (FloatArray, Int) -> Boolean) {
        if (isWavUri(uri)) {
            val input = contentResolver.openInputStream(uri)?.buffered()
                ?: error("The selected file could not be opened.")
            input.use { streamWav(it, onChunk) }
        } else {
            streamExtractor(uri, onChunk)
        }
    }

    suspend fun decodeFile(file: File): PcmAudio {
        val output = FloatArrayBuilder()
        var sampleRate = 48_000
        val header = file.inputStream().buffered().use { input ->
            input.mark(16)
            val bytes = ByteArray(12)
            val count = input.read(bytes)
            input.reset()
            if (count == 12) bytes else ByteArray(0)
        }

        if (header.size == 12 && isWavHeader(header)) {
            file.inputStream().buffered().use { input ->
                val format = parseWavHeader(input)
                sampleRate = format.sampleRate
                streamWavData(input, format) { chunk, count ->
                    for (i in 0 until count) output.add(chunk[i])
                    true
                }
            }
        } else {
            streamExtractor(file) { chunk, rate, count ->
                sampleRate = rate
                for (i in 0 until count) output.add(chunk[i])
                true
            }
        }

        val samples = output.toArray()
        require(samples.isNotEmpty()) { "The decoder produced no PCM samples." }
        return PcmAudio(samples, sampleRate, samples.size.toDouble() / sampleRate)
    }

    private fun isWavUri(uri: Uri): Boolean {
        return runCatching {
            contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                input.mark(16)
                val header = ByteArray(12)
                val count = input.read(header)
                input.reset()
                count == 12 && isWavHeader(header)
            } ?: false
        }.getOrDefault(false)
    }

    private fun isWavHeader(header: ByteArray): Boolean {
        if (header.size < 12) return false
        val riff = String(header, 0, 4, StandardCharsets.US_ASCII)
        val wave = String(header, 8, 4, StandardCharsets.US_ASCII)
        return (riff == "RIFF" || riff == "RF64") && wave == "WAVE"
    }

    private fun inspectWavUri(uri: Uri): AudioStreamInfo {
        val input = contentResolver.openInputStream(uri)?.buffered()
            ?: error("The selected WAV file could not be opened.")
        input.use {
            val format = parseWavHeader(it)
            val bytesPerFrame = format.bytesPerSample * format.channels
            val duration = if (format.dataBytes == Long.MAX_VALUE) null else {
                format.dataBytes.toDouble() / bytesPerFrame / format.sampleRate
            }
            return AudioStreamInfo(
                sampleRate = format.sampleRate,
                channels = format.channels,
                durationSeconds = duration,
                formatLabel = "${format.bitsPerSample}-bit WAV"
            )
        }
    }

    private fun inspectExtractor(uri: Uri): AudioStreamInfo {
        val extractor = MediaExtractor()
        try {
            setExtractorSource(extractor, uri)
            val trackIndex = findAudioTrack(extractor)
            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            val channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            val durationUs = format.getLongOr(MediaFormat.KEY_DURATION, -1L)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio"
            return AudioStreamInfo(
                sampleRate = sampleRate,
                channels = channels,
                durationSeconds = if (durationUs > 0) durationUs / 1_000_000.0 else null,
                formatLabel = mime.substringAfter("audio/", mime).uppercase()
            )
        } finally {
            extractor.release()
        }
    }

    private fun streamWav(input: InputStream, onChunk: (FloatArray, Int) -> Boolean) {
        val format = parseWavHeader(input)
        streamWavData(input, format, onChunk)
    }

    private fun streamWavData(
        input: InputStream,
        format: WavFormat,
        onChunk: (FloatArray, Int) -> Boolean
    ) {
        val bytesPerFrame = format.bytesPerSample * format.channels
        val raw = ByteArray(bytesPerFrame * WAV_FRAMES_PER_CHUNK + bytesPerFrame)
        val mono = FloatArray(WAV_FRAMES_PER_CHUNK)
        var carry = 0
        var remaining = format.dataBytes

        while (remaining > 0) {
            val maximum = raw.size - carry
            val wanted = if (remaining == Long.MAX_VALUE) maximum else min(maximum.toLong(), remaining).toInt()
            val read = input.read(raw, carry, wanted)
            if (read < 0) break
            if (read == 0) continue
            if (remaining != Long.MAX_VALUE) remaining -= read

            val total = carry + read
            val frames = total / bytesPerFrame
            var offset = 0
            for (frame in 0 until frames) {
                var sum = 0f
                repeat(format.channels) {
                    sum += decodeWavSample(raw, offset, format.formatTag, format.bitsPerSample)
                    offset += format.bytesPerSample
                }
                mono[frame] = (sum / format.channels).coerceIn(-1f, 1f)
            }

            val consumed = frames * bytesPerFrame
            carry = total - consumed
            if (carry > 0) raw.copyInto(raw, 0, consumed, total)
            if (frames > 0 && !onChunk(mono, frames)) return
        }
    }

    private fun parseWavHeader(input: InputStream): WavFormat {
        val header = ByteArray(12)
        readFully(input, header)
        require(isWavHeader(header)) { "The WAV header is invalid." }
        val rf64 = String(header, 0, 4, StandardCharsets.US_ASCII) == "RF64"

        var formatTag = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var rf64DataBytes: Long? = null

        while (true) {
            val chunkHeader = ByteArray(8)
            readFully(input, chunkHeader)
            val chunkId = String(chunkHeader, 0, 4, StandardCharsets.US_ASCII)
            val chunkSize = littleUInt(chunkHeader, 4)

            when (chunkId) {
                "ds64" -> {
                    val readSize = min(chunkSize, 64L).toInt()
                    val data = ByteArray(readSize)
                    readFully(input, data)
                    if (data.size >= 16) rf64DataBytes = littleLong(data, 8)
                    skipFully(input, chunkSize - readSize)
                }

                "fmt " -> {
                    val readSize = min(chunkSize, 64L).toInt()
                    val data = ByteArray(readSize)
                    readFully(input, data)
                    require(data.size >= 16) { "The WAV format chunk is damaged." }
                    formatTag = littleShort(data, 0)
                    channels = littleShort(data, 2)
                    sampleRate = littleInt(data, 4)
                    bitsPerSample = littleShort(data, 14)
                    skipFully(input, chunkSize - readSize)
                }

                "data" -> {
                    require(channels in 1..32) { "Unsupported WAV channel count: $channels." }
                    require(sampleRate in 8_000..384_000) { "Unsupported WAV sample rate: $sampleRate Hz." }
                    require(formatTag == 1 || formatTag == 3) {
                        "WAV encoding $formatTag is unsupported. Use PCM or floating-point WAV."
                    }
                    val bytesPerSample = (bitsPerSample + 7) / 8
                    require(bytesPerSample in 1..4) { "Unsupported WAV bit depth: $bitsPerSample-bit." }
                    val dataBytes = if (rf64 && chunkSize == 0xffff_ffffL) {
                        rf64DataBytes ?: Long.MAX_VALUE
                    } else {
                        chunkSize
                    }
                    return WavFormat(
                        formatTag = formatTag,
                        channels = channels,
                        sampleRate = sampleRate,
                        bitsPerSample = bitsPerSample,
                        bytesPerSample = bytesPerSample,
                        dataBytes = dataBytes
                    )
                }

                else -> skipFully(input, chunkSize)
            }
            if (chunkSize and 1L == 1L) skipFully(input, 1)
        }
    }

    private fun streamExtractor(uri: Uri, onChunk: (FloatArray, Int) -> Boolean) {
        streamExtractorInternal(
            setSource = { extractor -> setExtractorSource(extractor, uri) },
            onChunk = { chunk, _, count -> onChunk(chunk, count) }
        )
    }

    private fun streamExtractor(
        file: File,
        onChunk: (FloatArray, Int, Int) -> Boolean
    ) {
        streamExtractorInternal(
            setSource = { extractor -> extractor.setDataSource(file.absolutePath) },
            onChunk = onChunk
        )
    }

    private fun streamExtractorInternal(
        setSource: (MediaExtractor) -> Unit,
        onChunk: (FloatArray, Int, Int) -> Boolean
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            setSource(extractor)
            val trackIndex = findAudioTrack(extractor)
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("The audio MIME type is missing.")

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var keepGoing = true
            var sampleRate = inputFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var channels = inputFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputEnded && keepGoing) {
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
                            val chunk = downmix(buffer, pcmEncoding, channels)
                            if (chunk.isNotEmpty()) keepGoing = onChunk(chunk, sampleRate, chunk.size)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
                    }
                }
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun downmix(buffer: ByteBuffer, pcmEncoding: Int, channels: Int): FloatArray {
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                val frames = floats.remaining() / channels
                FloatArray(frames) { _ ->
                    var sum = 0f
                    repeat(channels) { sum += floats.get() }
                    (sum / channels).coerceIn(-1f, 1f)
                }
            }

            AudioFormat.ENCODING_PCM_16BIT -> {
                val shorts = buffer.asShortBuffer()
                val frames = shorts.remaining() / channels
                FloatArray(frames) { _ ->
                    var sum = 0f
                    repeat(channels) { sum += shorts.get() / 32768f }
                    (sum / channels).coerceIn(-1f, 1f)
                }
            }

            else -> error("Decoder PCM format $pcmEncoding is unsupported. Try WAV, MP3, M4A, OGG, or FLAC.")
        }
    }

    private fun setExtractorSource(extractor: MediaExtractor, uri: Uri) {
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

    private fun findAudioTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track was found in this file.")

    private fun decodeWavSample(bytes: ByteArray, offset: Int, formatTag: Int, bits: Int): Float {
        if (formatTag == 3 && bits == 32) {
            return Float.fromBits(littleInt(bytes, offset)).coerceIn(-1f, 1f)
        }
        return when (bits) {
            8 -> ((bytes[offset].toInt() and 0xff) - 128) / 128f
            16 -> littleShort(bytes, offset).toShort().toInt() / 32768f
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

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) error("The audio file ended unexpectedly.")
            offset += read
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) error("The audio file ended unexpectedly.")
                remaining--
            }
        }
    }

    private fun littleShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun littleUInt(bytes: ByteArray, offset: Int): Long = littleInt(bytes, offset).toLong() and 0xffff_ffffL

    private fun littleLong(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) result = result or ((bytes[offset + i].toLong() and 0xffL) shl (8 * i))
        return result
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private fun MediaFormat.getLongOr(key: String, fallback: Long): Long =
        if (containsKey(key)) getLong(key) else fallback

    private data class WavFormat(
        val formatTag: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val bytesPerSample: Int,
        val dataBytes: Long
    )

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val WAV_FRAMES_PER_CHUNK = 8_192
    }
}

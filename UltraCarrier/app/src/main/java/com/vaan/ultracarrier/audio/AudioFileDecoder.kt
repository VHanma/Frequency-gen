package com.vaan.ultracarrier.audio

import android.content.ContentResolver
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

class AudioFileDecoder(private val contentResolver: ContentResolver) {

    suspend fun decodeUri(uri: Uri): PcmAudio = decodeExtractor { extractor ->
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

    suspend fun decodeFile(file: File): PcmAudio = decodeExtractor { extractor ->
        extractor.setDataSource(file.absolutePath)
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
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
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
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEnded = true
                        }
                    }
                }

                if (output.size >= maxSamples) {
                    error("Audio is longer than $MAX_SECONDS seconds. Trim it and try again.")
                }
            }

            val samples = output.toArray()
            if (samples.isEmpty()) error("The decoder produced no PCM samples.")
            removeDcAndNormalize(samples)
            return PcmAudio(
                samples = samples,
                sampleRate = sampleRate,
                durationSeconds = samples.size.toDouble() / sampleRate
            )
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

            else -> error("Decoder PCM format $pcmEncoding is not supported. Try WAV, MP3, or FLAC.")
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
            val gain = (0.95f / peak).coerceAtMost(8f)
            for (i in samples.indices) samples[i] *= gain
        }
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_SECONDS = 300
    }
}

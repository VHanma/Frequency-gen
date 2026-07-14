package com.vhanma.lightcode.photophone

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

internal object AudioDecoder {
    private const val MAX_MONO_SAMPLES = 24_000_000

    fun decode(context: Context, uri: Uri, label: String): OpticalProgram {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var sourceFormat: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                trackIndex = index
                sourceFormat = format
                break
            }
        }
        require(trackIndex >= 0 && sourceFormat != null) { "No decodable audio track was found." }
        val selectedFormat = sourceFormat ?: error("No audio format.")
        extractor.selectTrack(trackIndex)

        val mime = selectedFormat.getString(MediaFormat.KEY_MIME)
            ?: error("Audio track has no MIME type.")
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(selectedFormat, null, null, 0)
        codec.start()

        var sampleRate = selectedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = selectedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val samples = ShortBuilder()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false

        try {
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: error("Decoder input buffer unavailable.")
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.order(ByteOrder.nativeOrder())

                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val buffer = output.asFloatBuffer()
                                val frameCount = buffer.remaining() / channels
                                repeat(frameCount) {
                                    var sum = 0f
                                    repeat(channels) { sum += buffer.get() }
                                    samples.add(SignalCore.floatToPcm(sum / channels.toFloat()))
                                }
                            } else {
                                val buffer = output.asShortBuffer()
                                val frameCount = buffer.remaining() / channels
                                repeat(frameCount) {
                                    var sum = 0
                                    repeat(channels) { sum += buffer.get().toInt() }
                                    samples.add((sum / channels).coerceIn(-32767, 32767).toShort())
                                }
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        val mono = samples.toArray()
        require(mono.isNotEmpty()) { "The decoded song was empty." }
        return OpticalProgram(mono, sampleRate, label)
    }

    private class ShortBuilder(initialCapacity: Int = 524_288) {
        private var values = ShortArray(initialCapacity)
        private var size = 0

        fun add(value: Short) {
            require(size < MAX_MONO_SAMPLES) {
                "This memory-efficient build supports roughly eight minutes at 48 kHz."
            }
            if (size == values.size) {
                values = values.copyOf((values.size * 2).coerceAtMost(MAX_MONO_SAMPLES))
            }
            values[size++] = value
        }

        fun toArray(): ShortArray = values.copyOf(size)
    }
}

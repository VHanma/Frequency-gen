package com.vhanma.lightcode

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

internal object AudioDecoder {
    fun decode(context: Context, uri: Uri, label: String): OpticalProgram {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                trackIndex = i
                inputFormat = format
                break
            }
        }
        require(trackIndex >= 0 && inputFormat != null) { "No decodable audio track found." }
        val selectedFormat = inputFormat ?: error("No decodable audio track found.")

        extractor.selectTrack(trackIndex)
        val mime = selectedFormat.getString(MediaFormat.KEY_MIME)
            ?: error("Audio format has no MIME type.")
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(selectedFormat, null, null, 0)
        codec.start()

        val builder = FloatBuilder()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var sampleRate = selectedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = selectedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        try {
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: error("Decoder input buffer unavailable.")
                        val size = extractor.readSampleData(inputBuffer, 0)
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
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())

                            when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val floats = buffer.asFloatBuffer()
                                    val frameCount = floats.remaining() / channelCount.coerceAtLeast(1)
                                    repeat(frameCount) {
                                        var sum = 0f
                                        repeat(channelCount) { sum += floats.get() }
                                        builder.add(sum / channelCount.toFloat())
                                    }
                                }
                                else -> {
                                    val shorts = buffer.asShortBuffer()
                                    val frameCount = shorts.remaining() / channelCount.coerceAtLeast(1)
                                    repeat(frameCount) {
                                        var sum = 0f
                                        repeat(channelCount) { sum += shorts.get() / 32768f }
                                        builder.add(sum / channelCount.toFloat())
                                    }
                                }
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val mono = SignalFactory.normalize(SignalFactory.removeDc(builder.toArray()))
        require(mono.isNotEmpty()) { "Decoded audio was empty." }
        return OpticalProgram(mono, sampleRate, label)
    }

    private class FloatBuilder(initialCapacity: Int = 262_144) {
        private var values = FloatArray(initialCapacity)
        private var size = 0

        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value.coerceIn(-1f, 1f)
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }
}

package com.vaan.voiceforgex

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs

object MediaVoiceImporter {
    data class Result(val wav: File, val sourceMime: String, val seconds: Float)

    fun import(context: Context, uri: Uri): Result {
        val extractor = MediaExtractor()
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: error("Could not open selected media")
        afd.use { d ->
            if (d.declaredLength >= 0) extractor.setDataSource(d.fileDescriptor, d.startOffset, d.declaredLength)
            else extractor.setDataSource(d.fileDescriptor)
        }

        try {
            var track = -1
            var format: MediaFormat? = null
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (m.startsWith("audio/")) {
                    track = i; format = f; mime = m; break
                }
            }
            require(track >= 0 && format != null) { "No audio track was found in this file" }
            extractor.selectTrack(track)

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            var sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val mono = ArrayList<Float>(sampleRate * 18)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxSamples = 48_000 * 45 // hard memory/processing ceiling before best-segment crop

            try {
                while (!outputDone && mono.size < maxSamples) {
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = decoder.outputFormat
                            sampleRate = of.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channels = of.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                            pcmEncoding = of.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outIndex >= 0) {
                            if (info.size > 0) {
                                val b = decoder.getOutputBuffer(outIndex)!!.apply {
                                    position(info.offset); limit(info.offset + info.size); order(ByteOrder.LITTLE_ENDIAN)
                                }
                                when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> {
                                        while (b.remaining() >= 4 * channels && mono.size < maxSamples) {
                                            var sum = 0f
                                            repeat(channels) { sum += b.float.coerceIn(-1f, 1f) }
                                            mono += sum / channels
                                        }
                                    }
                                    else -> {
                                        while (b.remaining() >= 2 * channels && mono.size < maxSamples) {
                                            var sum = 0f
                                            repeat(channels) { sum += b.short.toFloat() / 32768f }
                                            mono += (sum / channels).coerceIn(-1f, 1f)
                                        }
                                    }
                                }
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
            }

            require(mono.size >= sampleRate) { "The selected media contains less than one second of decodable speech audio" }
            val cleaned = chooseBestSpeechWindow(mono.toFloatArray(), sampleRate, 15)
            val peak = cleaned.maxOf { abs(it) }
            val gain = if (peak in 0.001f..0.88f) (0.88f / peak).coerceAtMost(4f) else 1f
            for (i in cleaned.indices) cleaned[i] = (cleaned[i] * gain).coerceIn(-0.98f, 0.98f)

            val wav = File(context.cacheDir, "media_clone_${System.currentTimeMillis()}.wav")
            WavUtils.writeFloatPcm16Wav(cleaned, wav, sampleRate)
            // Parse our own output as a final contract check before exposing it to the clone vault.
            WavUtils.readPcm16Mono(wav)
            return Result(wav, mime, cleaned.size.toFloat() / sampleRate)
        } finally {
            extractor.release()
        }
    }

    /** Selects the most speech-dense contiguous block instead of blindly taking the first seconds. */
    private fun chooseBestSpeechWindow(samples: FloatArray, rate: Int, maxSeconds: Int): FloatArray {
        val target = (rate * maxSeconds).coerceAtMost(samples.size)
        if (samples.size <= target) return trimEdges(samples, rate)
        val hop = (rate / 2).coerceAtLeast(1)
        var bestStart = 0
        var bestScore = -1.0
        var start = 0
        while (start + target <= samples.size) {
            var score = 0.0
            var i = start
            val end = start + target
            while (i < end) {
                val v = abs(samples[i])
                // De-emphasize tiny room noise and cap loud music/transients.
                if (v > 0.008f) score += v.coerceAtMost(0.35f)
                i += 8
            }
            if (score > bestScore) { bestScore = score; bestStart = start }
            start += hop
        }
        return trimEdges(samples.copyOfRange(bestStart, bestStart + target), rate)
    }

    private fun trimEdges(input: FloatArray, rate: Int): FloatArray {
        val threshold = 0.006f
        var first = 0
        while (first < input.size && abs(input[first]) < threshold) first++
        var last = input.lastIndex
        while (last > first && abs(input[last]) < threshold) last--
        val pad = rate / 10
        first = (first - pad).coerceAtLeast(0)
        last = (last + pad).coerceAtMost(input.lastIndex)
        return if (last > first) input.copyOfRange(first, last + 1) else input
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback
}

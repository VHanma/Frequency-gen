package com.vaan.frequencyremapper

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt


data class PcmSource(
    val file: File,
    val sampleRate: Int,
    val channels: Int,
    val totalFrames: Long,
    val durationUs: Long,
    val sourceName: String
)

data class DetectedNote(
    val midi: Int,
    val label: String,
    val frequencyHz: Double,
    val energy: Double,
    val hits: Int
)

data class FrequencyMapping(
    val sourceHz: Double,
    val targetHz: Double,
    val enabled: Boolean = true
)

object AudioFileDecoder {
    fun decodeToPcm16(context: Context, uri: Uri): PcmSource {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var audioTrack = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                audioTrack = i
                inputFormat = candidate
                break
            }
        }
        require(audioTrack >= 0 && inputFormat != null) { "No decodable audio track was found." }

        extractor.selectTrack(audioTrack)
        val sourceFormat = inputFormat!!
        val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
            ?: error("Audio track has no MIME type.")
        val durationUs = if (sourceFormat.containsKey(MediaFormat.KEY_DURATION)) {
            sourceFormat.getLong(MediaFormat.KEY_DURATION)
        } else 0L

        if (Build.VERSION.SDK_INT >= 24) {
            sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        val outputFile = File.createTempFile("frequency_remapper_source_", ".pcm", context.cacheDir)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(sourceFormat, null, null, 0)
        codec.start()

        var sampleRate = if (sourceFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 48000
        var channels = if (sourceFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            FileOutputStream(outputFile).use { out ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                                ?: error("Decoder input buffer unavailable.")
                            inputBuffer.clear()
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    extractor.sampleTime.coerceAtLeast(0L),
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER,
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                        else -> if (outputIndex >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && info.size > 0) {
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                writeAsPcm16(outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN), pcmEncoding, out)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            outputFile.delete()
            throw t
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        val bytesPerFrame = max(1, channels) * 2L
        val frames = outputFile.length() / bytesPerFrame
        return PcmSource(
            file = outputFile,
            sampleRate = sampleRate,
            channels = max(1, channels),
            totalFrames = frames,
            durationUs = durationUs,
            sourceName = displayName(context, uri)
        )
    }

    private fun writeAsPcm16(buffer: ByteBuffer, encoding: Int, out: FileOutputStream) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val converted = ByteBuffer.allocate((buffer.remaining() / 4) * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                while (buffer.remaining() >= 4) {
                    val f = buffer.float.coerceIn(-1f, 1f)
                    converted.putShort((f * 32767f).roundToInt().toShort())
                }
                out.write(converted.array(), 0, converted.position())
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                val converted = ByteBuffer.allocate(buffer.remaining() * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                while (buffer.hasRemaining()) {
                    val sample = ((buffer.get().toInt() and 0xff) - 128) shl 8
                    converted.putShort(sample.toShort())
                }
                out.write(converted.array(), 0, converted.position())
            }

            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val converted = ByteBuffer.allocate((buffer.remaining() / 3) * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                while (buffer.remaining() >= 3) {
                    val b0 = buffer.get().toInt() and 0xff
                    val b1 = buffer.get().toInt() and 0xff
                    val b2 = buffer.get().toInt()
                    val sample24 = b0 or (b1 shl 8) or (b2 shl 16)
                    converted.putShort((sample24 shr 8).toShort())
                }
                out.write(converted.array(), 0, converted.position())
            }

            AudioFormat.ENCODING_PCM_32BIT -> {
                val converted = ByteBuffer.allocate((buffer.remaining() / 4) * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                while (buffer.remaining() >= 4) {
                    converted.putShort((buffer.int shr 16).toShort())
                }
                out.write(converted.array(), 0, converted.position())
            }

            else -> {
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                out.write(bytes)
            }
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        return uri.lastPathSegment ?: "audio"
    }
}

class PcmFrameReader(source: PcmSource, maxFramesPerRead: Int) : Closeable {
    private val input = FileInputStream(source.file)
    private val channel = input.channel
    private val channels = source.channels
    private val bytesPerFrame = channels * 2
    private var byteBuffer = ByteBuffer.allocate(max(1, maxFramesPerRead) * bytesPerFrame)
        .order(ByteOrder.LITTLE_ENDIAN)

    fun readInto(target: Array<FloatArray>, offset: Int, frameCount: Int): Int {
        require(target.size == channels)
        val bytesNeeded = frameCount * bytesPerFrame
        if (byteBuffer.capacity() < bytesNeeded) {
            byteBuffer = ByteBuffer.allocate(bytesNeeded).order(ByteOrder.LITTLE_ENDIAN)
        }
        byteBuffer.clear()
        byteBuffer.limit(bytesNeeded)

        var bytesRead = 0
        while (bytesRead < bytesNeeded) {
            val n = channel.read(byteBuffer)
            if (n <= 0) break
            bytesRead += n
        }
        byteBuffer.flip()
        val actualFrames = bytesRead / bytesPerFrame

        for (frame in 0 until actualFrames) {
            for (ch in 0 until channels) {
                target[ch][offset + frame] = byteBuffer.short / 32768f
            }
        }
        for (frame in actualFrames until frameCount) {
            for (ch in 0 until channels) {
                target[ch][offset + frame] = 0f
            }
        }
        return actualFrames
    }

    override fun close() {
        channel.close()
        input.close()
    }
}

object PitchAnalyzer {
    private data class Accumulator(
        var weightedFrequency: Double = 0.0,
        var weight: Double = 0.0,
        var energy: Double = 0.0,
        var hits: Int = 0
    )

    fun analyze(
        source: PcmSource,
        maxNotes: Int = 60,
        onProgress: (Float) -> Unit = {}
    ): List<DetectedNote> {
        val fftSize = chooseFftSize(source.sampleRate)
        val hop = fftSize / 4
        val channels = source.channels
        val frames = Array(channels) { FloatArray(fftSize) }
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val window = hammingWindow(fftSize)
        val accumulators = Array(128) { Accumulator() }
        val binHz = source.sampleRate.toDouble() / fftSize
        val minBin = max(2, (27.5 / binHz).roundToInt())
        val maxBin = min(fftSize / 2 - 2, (8000.0 / binHz).roundToInt())

        PcmFrameReader(source, fftSize).use { reader ->
            var valid = reader.readInto(frames, 0, fftSize)
            var consumed = 0L
            var frameIndex = 0

            while (valid > 0 && consumed < source.totalFrames) {
                var i = 0
                while (i < fftSize) {
                    var mono = 0f
                    var ch = 0
                    while (ch < channels) {
                        mono += frames[ch][i]
                        ch++
                    }
                    real[i] = (mono / channels) * window[i]
                    imag[i] = 0f
                    i++
                }

                FastFft.transform(real, imag, inverse = false)

                var maxPower = 0.0
                var k = minBin
                while (k <= maxBin) {
                    val p = real[k].toDouble() * real[k] + imag[k].toDouble() * imag[k]
                    if (p > maxPower) maxPower = p
                    k++
                }

                if (maxPower > 1e-12) {
                    val threshold = maxPower * 0.0015
                    val peaks = ArrayList<Pair<Int, Double>>(24)
                    k = minBin
                    while (k <= maxBin) {
                        val p0 = real[k - 1].toDouble() * real[k - 1] + imag[k - 1].toDouble() * imag[k - 1]
                        val p1 = real[k].toDouble() * real[k] + imag[k].toDouble() * imag[k]
                        val p2 = real[k + 1].toDouble() * real[k + 1] + imag[k + 1].toDouble() * imag[k + 1]
                        if (p1 >= threshold && p1 > p0 && p1 >= p2) {
                            peaks.add(k to p1)
                        }
                        k++
                    }

                    peaks.sortByDescending { it.second }
                    val take = min(16, peaks.size)
                    for (index in 0 until take) {
                        val peakBin = peaks[index].first
                        val peakPower = peaks[index].second
                        val refinedBin = parabolicBin(real, imag, peakBin)
                        val frequency = refinedBin * binHz
                        if (frequency < 20.0 || frequency > source.sampleRate / 2.0) continue

                        val midi = (69.0 + 12.0 * log2(frequency / 440.0)).roundToInt()
                        if (midi !in 0..127) continue
                        val nearest = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
                        val cents = 1200.0 * log2(frequency / nearest)
                        if (abs(cents) > 55.0) continue

                        val weight = sqrt(peakPower)
                        val acc = accumulators[midi]
                        acc.weightedFrequency += frequency * weight
                        acc.weight += weight
                        acc.energy += peakPower
                        acc.hits++
                    }
                }

                frameIndex++
                consumed = min(source.totalFrames, consumed + hop)
                if (frameIndex % 20 == 0) {
                    onProgress((consumed.toDouble() / max(1L, source.totalFrames)).toFloat())
                }

                for (ch in 0 until channels) {
                    frames[ch].copyInto(frames[ch], destinationOffset = 0, startIndex = hop, endIndex = fftSize)
                }
                valid = reader.readInto(frames, fftSize - hop, hop)
            }
        }

        val maxEnergy = accumulators.maxOfOrNull { it.energy } ?: 0.0
        val minEnergy = maxEnergy * 0.001
        val notes = ArrayList<DetectedNote>()
        for (midi in accumulators.indices) {
            val acc = accumulators[midi]
            if (acc.hits < 2 || acc.weight <= 0.0 || acc.energy < minEnergy) continue
            val frequency = acc.weightedFrequency / acc.weight
            notes += DetectedNote(
                midi = midi,
                label = midiLabel(midi),
                frequencyHz = frequency,
                energy = acc.energy,
                hits = acc.hits
            )
        }

        val strongest = notes.sortedByDescending { it.energy }.take(maxNotes)
        onProgress(1f)
        return strongest.sortedBy { it.frequencyHz }
    }

    private fun chooseFftSize(sampleRate: Int): Int = when {
        sampleRate >= 88200 -> 16384
        sampleRate >= 32000 -> 8192
        else -> 4096
    }

    private fun parabolicBin(real: FloatArray, imag: FloatArray, k: Int): Double {
        fun logPower(i: Int): Double {
            val p = real[i].toDouble() * real[i] + imag[i].toDouble() * imag[i]
            return ln(max(1e-20, p))
        }
        val left = logPower(k - 1)
        val center = logPower(k)
        val right = logPower(k + 1)
        val denom = left - 2.0 * center + right
        val delta = if (abs(denom) < 1e-12) 0.0 else 0.5 * (left - right) / denom
        return k + delta.coerceIn(-0.5, 0.5)
    }

    private fun midiLabel(midi: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val octave = midi / 12 - 1
        return names[midi % 12] + octave
    }
}

fun hammingWindow(size: Int): FloatArray {
    val window = FloatArray(size)
    if (size <= 1) return window
    for (i in 0 until size) {
        window[i] = (0.54 - 0.46 * kotlin.math.cos(2.0 * Math.PI * i / (size - 1))).toFloat()
    }
    return window
}

class WavFileWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    fun writeInterleaved(samples: Array<FloatArray>, frames: Int, offset: Int = 0) {
        if (frames <= 0) return
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            for (ch in 0 until channels) {
                val v = samples[ch][offset + i].coerceIn(-1f, 1f)
                buffer.putShort((v * 32767f).roundToInt().toShort())
            }
        }
        raf.write(buffer.array(), 0, buffer.position())
        dataBytes += buffer.position()
    }

    override fun close() {
        writeHeader()
        raf.close()
    }

    private fun writeHeader() {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36L + dataBytes).coerceAtMost(0xffffffffL).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.coerceAtMost(0xffffffffL).toInt())
        raf.seek(0)
        raf.write(header.array())
    }
}

object AudioSaver {
    fun saveToMusic(context: Context, wavFile: File, requestedName: String): Uri {
        val cleanName = sanitizeName(requestedName).let {
            if (it.endsWith(".wav", ignoreCase = true)) it else "$it.wav"
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, cleanName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/FrequencyRemapper")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Android could not create the output audio file.")
            try {
                resolver.openOutputStream(uri, "w")!!.use { output ->
                    FileInputStream(wavFile).use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }

        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val folder = File(root, "FrequencyRemapper").apply { mkdirs() }
        val destination = uniqueFile(folder, cleanName)
        wavFile.copyTo(destination, overwrite = false)
        return Uri.fromFile(destination)
    }

    fun copyToUri(context: Context, wavFile: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
            FileInputStream(wavFile).use { input -> input.copyTo(output) }
        } ?: error("Could not open the selected save location.")
    }

    fun defaultOutputName(sourceName: String): String {
        val stem = sourceName.substringBeforeLast('.', sourceName)
        return sanitizeName(stem) + "_frequency-remapped.wav"
    }

    private fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "frequency-remapped" }
    }

    private fun uniqueFile(folder: File, name: String): File {
        var file = File(folder, name)
        if (!file.exists()) return file
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "wav")
        var i = 2
        while (file.exists()) {
            file = File(folder, "${stem}_$i.$ext")
            i++
        }
        return file
    }
}

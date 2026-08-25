package com.vaan.frequencyremapper

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Metadata for an original selected audio URI. No decoded full-file PCM is stored. */
data class StreamAudioSource(
    val uri: Uri,
    val sampleRate: Int,
    val channels: Int,
    val totalFrames: Long,
    val durationUs: Long,
    val sourceName: String
)

object StreamingAudioProbe {
    fun probe(context: Context, uri: Uri): StreamAudioSource {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    format = f
                    break
                }
            }
            val audio = requireNotNull(format) { "No decodable audio track was found." }
            val sampleRate = audio.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val durationUs = if (audio.containsKey(MediaFormat.KEY_DURATION)) audio.getLong(MediaFormat.KEY_DURATION) else 0L
            val frames = if (durationUs > 0L) {
                ((durationUs.toDouble() * sampleRate.toDouble()) / 1_000_000.0).toLong().coerceAtLeast(1L)
            } else 1L
            return StreamAudioSource(
                uri = uri,
                sampleRate = sampleRate,
                channels = channels,
                totalFrames = frames,
                durationUs = durationUs,
                sourceName = AudioFileDecoder.displayName(context, uri)
            )
        } finally {
            extractor.release()
        }
    }
}

/**
 * MediaCodec-backed frame reader. It retains only the current decoder output
 * buffer converted to PCM16, so input storage stays essentially constant even
 * for multi-hour compressed audio.
 */
class StreamingPcmFrameReader(
    context: Context,
    private val source: StreamAudioSource
) : Closeable {
    private val extractor = MediaExtractor()
    private lateinit var codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var outputChannels = source.channels
    private var pending = ShortArray(0)
    private var pendingIndex = 0

    init {
        extractor.setDataSource(context, source.uri, null)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                track = i
                format = f
                break
            }
        }
        require(track >= 0 && format != null) { "No decodable audio track was found." }
        extractor.selectTrack(track)
        val decodeFormat = format!!
        val mime = decodeFormat.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME type missing.")
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            decodeFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        codec = MediaCodec.createDecoderByType(mime)
        codec.configure(decodeFormat, null, null, 0)
        codec.start()
    }

    fun readInto(target: Array<FloatArray>, offset: Int, frameCount: Int): Int {
        require(target.size == source.channels)
        var writtenFrames = 0
        while (writtenFrames < frameCount) {
            if (pendingIndex >= pending.size) {
                pending = nextDecodedSamples()
                pendingIndex = 0
                if (pending.isEmpty()) break
            }
            val availableFrames = (pending.size - pendingIndex) / outputChannels
            if (availableFrames <= 0) {
                pending = ShortArray(0)
                pendingIndex = 0
                continue
            }
            val take = min(frameCount - writtenFrames, availableFrames)
            for (i in 0 until take) {
                for (ch in 0 until source.channels) {
                    val srcCh = ch.coerceAtMost(outputChannels - 1)
                    val sample = pending[pendingIndex + i * outputChannels + srcCh]
                    target[ch][offset + writtenFrames + i] = sample / 32768f
                }
            }
            pendingIndex += take * outputChannels
            writtenFrames += take
        }
        for (i in writtenFrames until frameCount) {
            for (ch in 0 until source.channels) target[ch][offset + i] = 0f
        }
        return writtenFrames
    }

    private fun nextDecodedSamples(): ShortArray {
        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Decoder input buffer unavailable.")
                    inputBuffer.clear()
                    val size = extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outputChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    require(outputChannels == source.channels) {
                        "Decoder channel count changed from ${source.channels} to $outputChannels."
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val out = codec.getOutputBuffer(outputIndex)
                    val samples = if (out != null && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        toPcm16(out.slice().order(ByteOrder.LITTLE_ENDIAN), outputEncoding)
                    } else ShortArray(0)
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (isEos) outputDone = true
                    if (samples.isNotEmpty()) return samples
                }
            }
        }
        return ShortArray(0)
    }

    private fun toPcm16(buffer: ByteBuffer, encoding: Int): ShortArray = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> ShortArray(buffer.remaining() / 4) { (buffer.float.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort() }
        AudioFormat.ENCODING_PCM_8BIT -> ShortArray(buffer.remaining()) { (((buffer.get().toInt() and 0xff) - 128) shl 8).toShort() }
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> ShortArray(buffer.remaining() / 3) {
            val b0 = buffer.get().toInt() and 0xff
            val b1 = buffer.get().toInt() and 0xff
            val b2 = buffer.get().toInt()
            ((b0 or (b1 shl 8) or (b2 shl 16)) shr 8).toShort()
        }
        AudioFormat.ENCODING_PCM_32BIT -> ShortArray(buffer.remaining() / 4) { (buffer.int shr 16).toShort() }
        else -> ShortArray(buffer.remaining() / 2) { buffer.short }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }
}

object StreamingPitchAnalyzer {
    private data class Acc(var wf: Double = 0.0, var w: Double = 0.0, var e: Double = 0.0, var hits: Int = 0)

    fun analyze(context: Context, source: StreamAudioSource, maxNotes: Int = 128, onProgress: (Float) -> Unit = {}): List<DetectedNote> {
        val fftSize = when {
            source.sampleRate >= 88200 -> 16384
            source.sampleRate >= 32000 -> 8192
            else -> 4096
        }
        val hop = fftSize / 4
        val frames = Array(source.channels) { FloatArray(fftSize) }
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val window = hammingWindow(fftSize)
        val acc = Array(128) { Acc() }
        val binHz = source.sampleRate.toDouble() / fftSize
        val minBin = max(2, (27.5 / binHz).roundToInt())
        val maxBin = min(fftSize / 2 - 2, (8000.0 / binHz).roundToInt())

        StreamingPcmFrameReader(context, source).use { reader ->
            var valid = reader.readInto(frames, 0, fftSize)
            var consumed = 0L
            var frameIndex = 0
            while (valid > 0) {
                for (i in 0 until fftSize) {
                    var mono = 0f
                    for (ch in frames.indices) mono += frames[ch][i]
                    real[i] = (mono / source.channels) * window[i]
                    imag[i] = 0f
                }
                FastFft.transform(real, imag, false)
                var maxPower = 0.0
                for (k in minBin..maxBin) {
                    val p = real[k].toDouble() * real[k] + imag[k].toDouble() * imag[k]
                    if (p > maxPower) maxPower = p
                }
                if (maxPower > 1e-12) {
                    val threshold = maxPower * 0.0015
                    val peaks = ArrayList<Pair<Int, Double>>(24)
                    for (k in minBin..maxBin) {
                        fun p(i: Int) = real[i].toDouble() * real[i] + imag[i].toDouble() * imag[i]
                        val p0 = p(k - 1); val p1 = p(k); val p2 = p(k + 1)
                        if (p1 >= threshold && p1 > p0 && p1 >= p2) peaks += k to p1
                    }
                    peaks.sortByDescending { it.second }
                    for (idx in 0 until min(16, peaks.size)) {
                        val k = peaks[idx].first
                        val peakPower = peaks[idx].second
                        val frequency = parabolicBin(real, imag, k) * binHz
                        if (frequency < 20.0 || frequency > source.sampleRate / 2.0) continue
                        val midi = (69.0 + 12.0 * log2(frequency / 440.0)).roundToInt()
                        if (midi !in 0..127) continue
                        val nearest = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)
                        if (abs(1200.0 * log2(frequency / nearest)) > 55.0) continue
                        val weight = sqrt(peakPower)
                        acc[midi].apply { wf += frequency * weight; w += weight; e += peakPower; hits++ }
                    }
                }
                frameIndex++
                consumed += hop
                if (frameIndex % 20 == 0 && source.totalFrames > 1L) onProgress((consumed.toDouble() / source.totalFrames).toFloat().coerceIn(0f, 0.99f))
                for (ch in frames.indices) {
                    frames[ch].copyInto(frames[ch], 0, hop, fftSize)
                    frames[ch].fill(0f, fftSize - hop, fftSize)
                }
                valid = reader.readInto(frames, fftSize - hop, hop)
            }
        }
        val maxEnergy = acc.maxOfOrNull { it.e } ?: 0.0
        val minEnergy = maxEnergy * 0.001
        val notes = ArrayList<DetectedNote>()
        for (midi in acc.indices) {
            val a = acc[midi]
            if (a.hits < 2 || a.w <= 0.0 || a.e < minEnergy) continue
            notes += DetectedNote(midi, midiLabel(midi), a.wf / a.w, a.e, a.hits)
        }
        onProgress(1f)
        return notes.sortedByDescending { it.energy }.take(maxNotes).sortedBy { it.frequencyHz }
    }

    private fun parabolicBin(real: FloatArray, imag: FloatArray, k: Int): Double {
        fun lp(i: Int): Double = ln(max(1e-20, real[i].toDouble() * real[i] + imag[i].toDouble() * imag[i]))
        val l = lp(k - 1); val c = lp(k); val r = lp(k + 1)
        val d = l - 2.0 * c + r
        return k + (if (abs(d) < 1e-12) 0.0 else 0.5 * (l - r) / d).coerceIn(-0.5, 0.5)
    }

    private fun midiLabel(midi: Int): String {
        val n = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return n[midi % 12] + (midi / 12 - 1)
    }
}

object StreamingContentAnalyzer {
    private data class Acc(var frames: Int = 0, var vocal: Double = 0.0, var shifted: Double = 0.0, var bass: Double = 0.0, var inst: Double = 0.0, var scaleW: Double = 0.0, var scaleWeight: Double = 0.0)
    private val voiceScales = doubleArrayOf(0.55, 0.70, 0.85, 1.0, 1.18, 1.42, 1.72, 2.05)

    fun analyze(context: Context, source: StreamAudioSource, notes: List<DetectedNote>, onProgress: (Float) -> Unit = {}): Map<Int, FrequencyContentTags> {
        if (notes.isEmpty()) return emptyMap()
        val fftSize = when { source.sampleRate >= 88200 -> 8192; source.sampleRate >= 32000 -> 4096; else -> 2048 }
        val hop = fftSize / 2
        val frames = Array(source.channels) { FloatArray(fftSize) }
        val real = FloatArray(fftSize); val imag = FloatArray(fftSize)
        val power = DoubleArray(fftSize / 2 + 1)
        val window = hammingWindow(fftSize)
        val binHz = source.sampleRate.toDouble() / fftSize
        val nyquist = source.sampleRate / 2.0
        val acc = notes.associate { it.midi to Acc() }.toMutableMap()

        StreamingPcmFrameReader(context, source).use { reader ->
            var valid = reader.readInto(frames, 0, fftSize)
            var consumed = 0L; var frameNumber = 0
            while (valid > 0) {
                for (i in 0 until fftSize) {
                    var mono = 0f; for (ch in frames.indices) mono += frames[ch][i]
                    real[i] = (mono / source.channels) * window[i]; imag[i] = 0f
                }
                FastFft.transform(real, imag, false)
                for (k in power.indices) { val re = real[k].toDouble(); val im = imag[k].toDouble(); power[k] = re * re + im * im }
                val broad = bandPower(power, binHz, 70.0, min(7000.0, nyquist - binHz)).coerceAtLeast(1e-20)
                val lowRatio = bandPower(power, binHz, 35.0, min(320.0, nyquist)) / broad
                val flatness = spectralFlatness(power, binHz, 90.0, min(6500.0, nyquist - binHz))
                var normalVoice = 0.0; var shiftedVoice = 0.0; var bestScale = 1.0
                for (scale in voiceScales) {
                    val s = formantScore(power, binHz, nyquist, scale, broad)
                    if (scale in 0.82..1.25) normalVoice = max(normalVoice, s)
                    else if (s > shiftedVoice) { shiftedVoice = s; bestScale = scale }
                }
                for (note in notes) {
                    val a = acc[note.midi] ?: continue
                    val f = note.frequencyHz
                    if (f <= 20.0 || f >= nyquist) continue
                    val localW = max(binHz * 1.8, f * 0.018)
                    val localNorm = (bandPower(power, binHz, f - localW, f + localW) / broad).coerceIn(0.0, 1.0)
                    if (localNorm < 1e-6) continue
                    var harmonicEnergy = 0.0; var hc = 0
                    for (h in 1..12) {
                        val hf = f * h; if (hf >= min(6000.0, nyquist - binHz)) break
                        val hw = max(binHz * 1.5, hf * 0.008)
                        harmonicEnergy += bandPower(power, binHz, hf - hw, hf + hw); hc++
                    }
                    val harmonicity = if (hc > 0) (harmonicEnergy / broad).pow(0.55).coerceIn(0.0, 1.0) else 0.0
                    val voiced = (0.62 * harmonicity + 0.38 * (1.0 - flatness)).coerceIn(0.0, 1.0)
                    val vocal = (voiced * normalVoice * (0.35 + 0.65 * sqrt(localNorm))).coerceIn(0.0, 1.0)
                    val shifted = (voiced * shiftedVoice * (0.35 + 0.65 * sqrt(localNorm))).coerceIn(0.0, 1.0)
                    val inst = (harmonicity * (0.55 + 0.45 * (1.0 - normalVoice * 0.65))).coerceIn(0.0, 1.0)
                    val bass = if (f <= 330.0) (sqrt(lowRatio.coerceIn(0.0, 1.0)) * (0.45 + 0.55 * sqrt(localNorm))).coerceIn(0.0, 1.0) else 0.0
                    a.frames++; a.vocal += vocal; a.shifted += shifted; a.inst += inst; a.bass += bass
                    if (shifted > 0.02) { a.scaleW += bestScale * shifted; a.scaleWeight += shifted }
                }
                frameNumber++; consumed += hop
                if (frameNumber % 18 == 0 && source.totalFrames > 1L) onProgress((consumed.toDouble() / source.totalFrames).toFloat().coerceIn(0f, 0.99f))
                for (ch in frames.indices) { frames[ch].copyInto(frames[ch], 0, hop, fftSize); frames[ch].fill(0f, fftSize - hop, fftSize) }
                valid = reader.readInto(frames, fftSize - hop, hop)
            }
        }

        val maxEnergy = notes.maxOfOrNull { it.energy }?.coerceAtLeast(1e-20) ?: 1.0
        val result = LinkedHashMap<Int, FrequencyContentTags>()
        for (note in notes) {
            val a = acc[note.midi] ?: Acc(); val n = max(1, a.frames).toDouble()
            val vocal = (a.vocal / n).coerceIn(0.0, 1.0); val shifted = (a.shifted / n).coerceIn(0.0, 1.0)
            val inst = (a.inst / n).coerceIn(0.0, 1.0); val bass = (a.bass / n).coerceIn(0.0, 1.0)
            val rel = (note.energy / maxEnergy).coerceIn(0.0, 1.0)
            val scale = if (a.scaleWeight > 1e-9) a.scaleW / a.scaleWeight else 1.0
            val tags = ArrayList<String>()
            strengthTag("VOCAL", vocal)?.let(tags::add); strengthTag("INSTRUMENT", inst)?.let(tags::add)
            if (note.frequencyHz <= 330.0) strengthTag("BASS", bass)?.let(tags::add)
            if (shifted > 0.20 && shifted > vocal * 1.12) {
                val name = if (scale >= 1.30) "SPED-UP VOCAL" else "SHIFTED VOCAL"
                tags += if (shifted >= 0.48) "$name LIKELY ×${fmt(scale)}" else "$name POSSIBLE ×${fmt(scale)}"
            }
            val hidden = max(vocal, shifted)
            if (hidden >= 0.20 && rel < 0.34) tags += if (hidden >= 0.48) "HIDDEN VOCAL LIKELY" else "HIDDEN VOCAL POSSIBLE"
            if (tags.isEmpty()) tags += if (note.frequencyHz < 300.0) "LOW TONAL" else "TONAL"
            result[note.midi] = FrequencyContentTags(note.midi, tags.distinct().take(6), vocal, inst, bass, shifted, scale)
        }
        onProgress(1f); return result
    }

    private fun strengthTag(name: String, s: Double): String? = when { s >= 0.58 -> "$name STRONG"; s >= 0.31 -> "$name NEUTRAL"; s >= 0.13 -> "$name WEAK"; else -> null }
    private fun formantScore(power: DoubleArray, binHz: Double, nyquist: Double, scale: Double, broad: Double): Double {
        fun ratio(lo: Double, hi: Double): Double { val a = max(60.0, lo * scale); val b = min(nyquist - binHz, hi * scale); return if (b <= a) 0.0 else (bandPower(power, binHz, a, b) / broad).coerceIn(0.0, 1.0) }
        val f1 = ratio(250.0, 950.0); val f2 = ratio(700.0, 2400.0); val f3 = ratio(1600.0, 3900.0)
        if (f1 <= 1e-8 || f2 <= 1e-8) return 0.0
        val geometric = (f1 * f2 * max(f3, 0.004)).pow(1.0 / 3.0)
        val balance = exp(-1.4 * abs(ln((f2 + 1e-9) / (f1 + 1e-9))))
        return (geometric * 3.4 * (0.72 + 0.28 * balance)).coerceIn(0.0, 1.0)
    }
    private fun bandPower(p: DoubleArray, binHz: Double, loHz: Double, hiHz: Double): Double { if (hiHz <= loHz) return 0.0; val lo = max(0, (loHz / binHz).toInt()); val hi = min(p.lastIndex, (hiHz / binHz).toInt()); var s = 0.0; for (k in lo..hi) s += p[k]; return s }
    private fun spectralFlatness(p: DoubleArray, binHz: Double, loHz: Double, hiHz: Double): Double { val lo = max(1, (loHz / binHz).toInt()); val hi = min(p.lastIndex, (hiHz / binHz).toInt()); if (hi <= lo) return 1.0; var ls = 0.0; var ar = 0.0; var c = 0; for (k in lo..hi) { val v = max(1e-20, p[k]); ls += ln(v); ar += v; c++ }; if (c == 0 || ar <= 0.0) return 1.0; return (exp(ls / c) / (ar / c)).coerceIn(0.0, 1.0) }
    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
}

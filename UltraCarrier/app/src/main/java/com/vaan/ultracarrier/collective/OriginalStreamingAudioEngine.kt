package com.vaan.ultracarrier.collective

import android.content.ContentResolver
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import com.vaan.ultracarrier.audio.GodXMode
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.audio.ThoughtMode
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

class OriginalStreamingAudioEngine(private val resolver: ContentResolver) {
    private val stopped = AtomicBoolean(true)
    @Volatile private var activeTrack: AudioTrack? = null

    fun stop() {
        stopped.set(true)
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
        }
    }

    fun play(
        source: CollectiveSource,
        decoder: CollectiveStreamDecoder,
        config: CollectiveConfig,
        preferredDevice: AudioDeviceInfo?,
        onStarted: (CollectiveReport) -> Unit,
        onScope: (FloatArray, Int) -> Unit
    ) {
        stop()
        stopped.set(false)
        val (track, sampleRate) = createBestTrack(config.requestedSampleRate, preferredDevice)
        activeTrack = track
        val session = RenderSession(config.copy(requestedSampleRate = sampleRate), sampleRate, onScope)
        try {
            track.play()
            val route = track.routedDevice?.productName?.toString().orEmpty()
                .ifBlank { preferredDevice?.productName?.toString().orEmpty() }
                .ifBlank { "system-selected output" }
            onStarted(
                CollectiveReport(
                    sampleRate = sampleRate,
                    routeName = route,
                    family = config.family,
                    modeLabel = session.modeLabel,
                    carrierHz = session.reportCarrier
                )
            )
            val resampler = StreamingResampler(source.info.sampleRate, sampleRate) { mono, count ->
                if (!stopped.get()) session.process(mono, count) { stereo, stereoCount ->
                    writeFully(track, stereo, stereoCount)
                }
            }
            decoder.stream(source) { chunk, count, _ ->
                if (stopped.get()) false else {
                    resampler.append(chunk, 0, count)
                    !stopped.get()
                }
            }
            if (!stopped.get()) resampler.finish()
        } finally {
            runCatching { track.stop() }
            runCatching { track.flush() }
            track.release()
            activeTrack = null
            stopped.set(true)
        }
    }

    fun export(
        source: CollectiveSource,
        decoder: CollectiveStreamDecoder,
        config: CollectiveConfig,
        destination: Uri,
        format: ExportFormat,
        onProgress: (Double?) -> Unit,
        onScope: (FloatArray, Int) -> Unit
    ) {
        stop()
        stopped.set(false)
        val sampleRate = config.requestedSampleRate.coerceIn(44_100, 192_000)
        val session = RenderSession(config.copy(requestedSampleRate = sampleRate), sampleRate, onScope)
        val pfd = resolver.openFileDescriptor(destination, "rw") ?: error("Could not open save destination.")
        pfd.use {
            val writer = WaveWriter(FileOutputStream(it.fileDescriptor).channel, sampleRate, 2, format)
            try {
                var inputFrames = 0L
                val total = source.info.durationSeconds?.let { d -> (d * source.info.sampleRate).toLong() }
                val resampler = StreamingResampler(source.info.sampleRate, sampleRate) { mono, count ->
                    if (!stopped.get()) session.process(mono, count) { stereo, stereoCount -> writer.write(stereo, stereoCount) }
                }
                decoder.stream(source) { chunk, count, _ ->
                    if (stopped.get()) false else {
                        inputFrames += count
                        resampler.append(chunk, 0, count)
                        onProgress(total?.takeIf { it > 0 }?.let { t -> (inputFrames.toDouble() / t).coerceIn(0.0, 1.0) })
                        !stopped.get()
                    }
                }
                if (!stopped.get()) resampler.finish()
            } finally {
                writer.close()
            }
        }
        stopped.set(true)
    }

    private class RenderSession(
        private val config: CollectiveConfig,
        private val sampleRate: Int,
        private val onScope: (FloatArray, Int) -> Unit
    ) {
        val modeLabel: String = when (config.family) {
            CollectiveFamily.LAB_X -> config.labXMode.label
            CollectiveFamily.THOUGHTBEAM -> config.classicMode.label
            else -> "Original"
        }
        val reportCarrier: Float = if (config.family == CollectiveFamily.THOUGHTBEAM && classicUsesCarrier(config.classicMode)) {
            config.carrierHz.coerceIn(500f, sampleRate / 2f - 700f)
        } else 0f

        private val low = OnePoleLowPass(sampleRate, 3_800f)
        private val high = OnePoleHighPass(sampleRate, if (config.listeningPath == ListeningPath.BONE_CONDUCTION) 300f else 170f)
        private val hilbert = Hilbert63()
        private val iq = FloatArray(2)
        private val block = FloatArray(BLOCK * 2)
        private val scope = FloatArray(BLOCK)
        private val delay = FloatArray((sampleRate * 0.005f).roundToInt().coerceAtLeast(256))
        private var delayWrite = 0
        private var phase = 0.0
        private var phase2 = 0.0
        private var phase3 = 0.0
        private var outputFrame = 0L
        private var scopeCounter = 0
        private var noiseStateL = 0x13579BDF
        private var noiseStateR = 0x2468ACE1
        private var noiseSmoothL = 0f
        private var noiseSmoothR = 0f

        fun process(mono: FloatArray, count: Int, sink: (FloatArray, Int) -> Unit) {
            var offset = 0
            while (offset < count) {
                val frames = min(BLOCK, count - offset)
                for (i in 0 until frames) {
                    val frame = outputFrame + i
                    val raw = mono[offset + i]
                    val voice = tanh((high.process(low.process(raw)) * 2.35f).toDouble()).toFloat()
                    val pair = when (config.family) {
                        CollectiveFamily.LAB_X -> labXSample(config.labXMode, voice, frame)
                        CollectiveFamily.THOUGHTBEAM -> classicSample(config.classicMode, voice, frame)
                        else -> voice to voice
                    }
                    val gain = outputGain()
                    val l = (pair.first * gain).coerceIn(-0.96f, 0.96f)
                    val r = (pair.second * gain).coerceIn(-0.96f, 0.96f)
                    block[i * 2] = l
                    block[i * 2 + 1] = r
                    scope[i] = (l + r) * 0.5f
                }
                sink(block, frames * 2)
                outputFrame += frames
                offset += frames
                scopeCounter++
                if (scopeCounter % 2 == 0) onScope(decimate(scope, frames, 512), sampleRate)
            }
        }

        private fun labXSample(mode: GodXMode, voice: Float, frame: Long): Pair<Float, Float> {
            val t = frame.toDouble() / sampleRate
            val p = config.presence.coerceIn(0.05f, 1f)
            val modHz = config.elfRateHz.coerceIn(1f, 80f)
            val depth = config.elfDepth.coerceIn(0f, 0.90f)
            val envelope = 1f - depth * 0.5f + depth * 0.5f * (1f + sin(2.0 * PI * modHz * t).toFloat())
            val fast40 = 0.86f + 0.14f * sin(2.0 * PI * 40.0 * t).toFloat()
            val beat = modHz.coerceIn(1f, 40f)
            val base = 220f
            val toneL = sin(2.0 * PI * (base - beat * 0.5f) * t).toFloat()
            val toneR = sin(2.0 * PI * (base + beat * 0.5f) * t).toFloat()
            val bed = 0.006f + 0.020f * p
            val maxDelay = (sampleRate * 180.0 / 1_000_000.0).roundToInt().coerceAtLeast(1)
            delay[delayWrite] = voice
            val motion = sin(2.0 * PI * config.ditherRateHz.coerceIn(0.03f, 4f) * t).toFloat()
            val signedDelay = (motion * maxDelay).roundToInt()
            val delayed = delay[(delayWrite - abs(signedDelay) + delay.size) % delay.size]
            delayWrite = (delayWrite + 1) % delay.size
            val leftVoice = if (signedDelay < 0) delayed else voice
            val rightVoice = if (signedDelay > 0) delayed else voice

            return when (mode) {
                GodXMode.VOICE_OF_GOD_STACK -> {
                    (leftVoice * 0.90f * envelope * fast40 + toneL * bed) to
                        (rightVoice * 0.90f * envelope * fast40 + toneR * bed)
                }
                GodXMode.EMF_ENVELOPE -> (voice * envelope) to (voice * envelope)
                GodXMode.EMF_SCAN -> {
                    val rates = floatArrayOf(7.83f, 10f, 16.67f, 25f, 40f, 50f, 60f, 80f)
                    val segment = max(1L, (sampleRate * 4L))
                    val rate = rates[((frame / segment) % rates.size).toInt()]
                    val e = 1f - depth * 0.5f + depth * 0.5f * (1f + sin(2.0 * PI * rate * t).toFloat())
                    (voice * e) to (voice * e)
                }
                GodXMode.ASSR_40 -> {
                    val e = 0.65f + 0.35f * sin(2.0 * PI * 40.0 * t).toFloat()
                    val anchor = sin(2.0 * PI * 500.0 * t).toFloat() * (0.004f + 0.012f * p)
                    (voice * e + anchor) to (voice * e + anchor)
                }
                GodXMode.CROSS_FREQUENCY_NEST -> (voice * envelope * fast40) to (voice * envelope * fast40)
                GodXMode.BINAURAL_CORE -> (voice * 0.93f + toneL * bed) to (voice * 0.93f + toneR * bed)
                GodXMode.MONAURAL_BEAT -> {
                    val physical = (toneL + toneR) * bed * 0.72f
                    (voice * 0.92f + physical) to (voice * 0.92f + physical)
                }
                GodXMode.MICRO_MOTION -> leftVoice to rightVoice
                GodXMode.COHERENCE_SNAP -> {
                    val snap = (t % 3.0) < 0.38
                    if (snap) voice to voice else {
                        val nl = shapedNoise(true) * 0.015f
                        val nr = shapedNoise(false) * 0.015f
                        (leftVoice * 0.96f + nl) to (rightVoice * 0.96f + nr)
                    }
                }
                GodXMode.PHASE_FLIP -> {
                    val mix = sin(2.0 * PI * config.ditherRateHz.coerceIn(0.03f, 1f) * t).toFloat()
                    voice to (voice * mix)
                }
            }
        }

        private fun classicSample(mode: ThoughtMode, voice: Float, frame: Long): Pair<Float, Float> {
            val t = frame.toDouble() / sampleRate
            val p = config.presence.coerceIn(0.05f, 1f)
            val carrier = config.carrierHz.coerceIn(500f, sampleRate / 2f - 700f)
            val step = 2.0 * PI * carrier / sampleRate
            val targetPhase = steeringPhase(carrier, config.spacingMm, config.targetAngleDeg)

            return when (mode) {
                ThoughtMode.INNER_VOICE -> voice to voice
                ThoughtMode.CENTER_LOCK -> {
                    val centered = tanh((voice * 1.35f).toDouble()).toFloat()
                    centered to centered
                }
                ThoughtMode.FREY_ACOUSTIC_SIM -> {
                    val rate = config.elfRateHz.coerceIn(2f, 40f)
                    val interval = max(1L, (sampleRate / rate).toLong())
                    val width = max(2L, (sampleRate * 0.0012f).toLong())
                    val local = frame % interval
                    val click = if (local < width) {
                        val pos = local.toDouble() / width
                        val window = 0.5 - 0.5 * cos(2.0 * PI * pos)
                        (sin(2.0 * PI * (1200.0 + 1200.0 * p) * local / sampleRate) * window).toFloat()
                    } else 0f
                    val v = voice * (0.82f - 0.18f * p) + click * (0.10f + 0.30f * p) * sqrt(abs(voice) + 0.05f)
                    v to v
                }
                ThoughtMode.MASKED_WHISPER -> {
                    val maskL = shapedNoise(true) * (0.018f + 0.055f * p)
                    val maskR = shapedNoise(false) * (0.018f + 0.055f * p)
                    (voice * (0.40f + 0.22f * p) + maskL) to (voice * (0.40f + 0.22f * p) + maskR)
                }
                ThoughtMode.BONE_TAP -> {
                    val rate = config.elfRateHz.coerceIn(2f, 30f)
                    val interval = max(1L, (sampleRate / rate).toLong())
                    val width = max(2L, (sampleRate * 0.008f).toLong())
                    val local = frame % interval
                    val tap = if (local < width) {
                        val pos = local.toDouble() / width
                        val window = 0.5 - 0.5 * cos(2.0 * PI * pos)
                        (sin(2.0 * PI * (160.0 + 120.0 * p) * local / sampleRate) * window).toFloat()
                    } else 0f
                    val v = voice * 0.72f + tap * (0.08f + 0.20f * p)
                    v to v
                }
                ThoughtMode.PATENT_SSB -> {
                    hilbert.process(voice, iq)
                    val l = iq[0] * cos(phase).toFloat() - iq[1] * sin(phase).toFloat()
                    val r = iq[0] * cos(phase + targetPhase).toFloat() - iq[1] * sin(phase + targetPhase).toFloat()
                    phase += step
                    wrapPhases()
                    l to r
                }
                ThoughtMode.FM_SLOPE -> {
                    val deviation = 350f + 1650f * p
                    val instant = (carrier + deviation * voice).coerceIn(500f, sampleRate / 2f - 700f)
                    val l = cos(phase).toFloat()
                    val r = cos(phase + targetPhase).toFloat()
                    phase += 2.0 * PI * instant / sampleRate
                    wrapPhases()
                    l to r
                }
                ThoughtMode.BEAM_WHISPER -> {
                    val env = 0.075f + voice * (0.22f + 0.46f * p)
                    val l = cos(phase).toFloat() * env
                    val r = cos(phase + targetPhase).toFloat() * env
                    phase += step
                    wrapPhases()
                    l to r
                }
                ThoughtMode.AIR_HETERODYNE -> {
                    val env = sqrt((1f + p * voice).coerceIn(0.02f, 1.98f))
                    val l = cos(phase).toFloat() * env
                    val r = cos(phase).toFloat() * env
                    phase += step
                    wrapPhases()
                    l to r
                }
                ThoughtMode.ARRAY_STEER -> {
                    val env = sqrt((1f + p * voice).coerceIn(0.02f, 1.98f))
                    val l = cos(phase).toFloat() * env
                    val r = cos(phase + targetPhase).toFloat() * env
                    phase += step
                    wrapPhases()
                    l to r
                }
                ThoughtMode.CHIRP_CARRIER -> {
                    val sweep = 4_000f.coerceAtMost(sampleRate / 2f - carrier - 800f).coerceAtLeast(100f)
                    val cycle = (t % 0.020) / 0.020
                    val triangle = if (cycle < 0.5) cycle * 4.0 - 1.0 else 3.0 - cycle * 4.0
                    val f = (carrier + triangle.toFloat() * sweep * 0.5f).coerceIn(500f, sampleRate / 2f - 700f)
                    val env = sqrt((1f + p * voice).coerceIn(0.02f, 1.98f))
                    val steer = steeringPhase(f, config.spacingMm, config.targetAngleDeg)
                    val l = cos(phase).toFloat() * env
                    val r = cos(phase + steer).toFloat() * env
                    phase += 2.0 * PI * f / sampleRate
                    wrapPhases()
                    l to r
                }
            }
        }

        private fun outputGain(): Float {
            val p = config.presence.coerceIn(0.05f, 1f)
            return when (config.listeningPath) {
                ListeningPath.HEADPHONES -> if (config.family == CollectiveFamily.LAB_X) 0.08f + 0.18f * p else 0.06f + 0.20f * p
                ListeningPath.BONE_CONDUCTION -> 0.09f + 0.24f * p
                ListeningPath.PHONE_SPEAKER -> 0.16f + 0.30f * p
                ListeningPath.EXTERNAL_ARRAY -> 0.18f + 0.38f * p
            }
        }

        private fun shapedNoise(left: Boolean): Float {
            if (left) {
                noiseStateL = noiseStateL * 1664525 + 1013904223
                val white = (((noiseStateL ushr 8) and 0x00FFFFFF) / 8_388_607.5f) - 1f
                noiseSmoothL += (white - noiseSmoothL) * 0.16f
                return noiseSmoothL
            }
            noiseStateR = noiseStateR * 1664525 + 1013904223
            val white = (((noiseStateR ushr 8) and 0x00FFFFFF) / 8_388_607.5f) - 1f
            noiseSmoothR += (white - noiseSmoothR) * 0.16f
            return noiseSmoothR
        }

        private fun steeringPhase(freq: Float, spacingMm: Float, angleDeg: Float): Double {
            val d = spacingMm.coerceIn(1f, 80f) / 1000.0
            val a = Math.toRadians(angleDeg.coerceIn(-80f, 80f).toDouble())
            return 2.0 * PI * freq * d * sin(a) / 343.0
        }

        private fun wrapPhases() {
            val tau = 2.0 * PI
            if (abs(phase) > tau * 16) phase %= tau
            if (abs(phase2) > tau * 16) phase2 %= tau
            if (abs(phase3) > tau * 16) phase3 %= tau
        }

        companion object {
            private fun classicUsesCarrier(mode: ThoughtMode): Boolean = mode in setOf(
                ThoughtMode.PATENT_SSB,
                ThoughtMode.FM_SLOPE,
                ThoughtMode.BEAM_WHISPER,
                ThoughtMode.AIR_HETERODYNE,
                ThoughtMode.ARRAY_STEER,
                ThoughtMode.CHIRP_CARRIER
            )
        }
    }

    private class StreamingResampler(
        sourceRate: Int,
        targetRate: Int,
        private val emit: (FloatArray, Int) -> Unit
    ) {
        private val ratio = sourceRate.toDouble() / targetRate
        private var tail = FloatArray(0)
        private var position = 0.0
        private val output = FloatArray(BLOCK)
        private var count = 0

        fun append(input: FloatArray, offset: Int, amount: Int) {
            if (amount <= 0) return
            if (abs(ratio - 1.0) < 1e-9) {
                var off = offset
                var left = amount
                while (left > 0) {
                    val n = min(BLOCK, left)
                    input.copyInto(output, 0, off, off + n)
                    emit(output, n)
                    off += n
                    left -= n
                }
                return
            }
            val combined = FloatArray(tail.size + amount)
            tail.copyInto(combined)
            input.copyInto(combined, tail.size, offset, offset + amount)
            while (position + 1.0 < combined.size) {
                val i = floor(position).toInt()
                val f = (position - i).toFloat()
                output[count++] = combined[i] + (combined[i + 1] - combined[i]) * f
                if (count == output.size) {
                    emit(output, count)
                    count = 0
                }
                position += ratio
            }
            val consumed = floor(position).toInt().coerceIn(0, max(0, combined.size - 1))
            tail = combined.copyOfRange(consumed, combined.size)
            position -= consumed
        }

        fun finish() {
            if (count > 0) emit(output, count)
            count = 0
        }
    }

    private class OnePoleLowPass(rate: Int, cutoff: Float) {
        private val a = (1.0 - kotlin.math.exp(-2.0 * PI * cutoff.coerceAtMost(rate / 2f - 100f) / rate)).toFloat()
        private var y = 0f
        fun process(x: Float): Float { y += a * (x - y); return y }
    }

    private class OnePoleHighPass(rate: Int, cutoff: Float) {
        private val a = kotlin.math.exp(-2.0 * PI * cutoff / rate).toFloat()
        private var y = 0f
        private var last = 0f
        fun process(x: Float): Float { y = a * (y + x - last); last = x; return y }
    }

    private class Hilbert63 {
        private val delay = FloatArray(63)
        private var pos = 0
        fun process(x: Float, out: FloatArray) {
            delay[pos] = x
            var q = 0f
            var k = 0
            for (n in -31..31) {
                if (n != 0 && (abs(n) and 1) == 1) {
                    val h = (2.0 / (PI * n)).toFloat()
                    val idx = (pos - k + delay.size) % delay.size
                    q += delay[idx] * h
                }
                k++
            }
            val center = (pos - 31 + delay.size) % delay.size
            out[0] = delay[center]
            out[1] = q.coerceIn(-2f, 2f)
            pos = (pos + 1) % delay.size
        }
    }

    private class WaveWriter(
        private val channel: FileChannel,
        private val sampleRate: Int,
        private val channels: Int,
        private val format: ExportFormat
    ) : AutoCloseable {
        private var dataBytes = 0L
        private var sampleFrames = 0L
        private val bytesPerSample = format.bits / 8

        init { writeHeader(false) }

        fun write(samples: FloatArray, count: Int) {
            val b = ByteBuffer.allocate(count * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val x = samples[i].coerceIn(-1f, 1f)
                when (format) {
                    ExportFormat.WAV_16 -> b.putShort((x * 32767f).roundToInt().toShort())
                    ExportFormat.WAV_24 -> {
                        val v = (x * 8_388_607f).roundToInt()
                        b.put((v and 255).toByte())
                        b.put(((v ushr 8) and 255).toByte())
                        b.put(((v ushr 16) and 255).toByte())
                    }
                    ExportFormat.WAV_FLOAT32 -> b.putFloat(x)
                }
            }
            b.flip()
            while (b.hasRemaining()) channel.write(b)
            dataBytes += count.toLong() * bytesPerSample
            sampleFrames += count / channels
        }

        private fun writeHeader(final: Boolean) {
            val large = final && dataBytes > 0xffff_ff00L
            channel.position(0)
            val h = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
            putAscii(h, if (large) "RF64" else "RIFF")
            h.putInt(if (large) -1 else if (final) (72L + dataBytes).coerceAtMost(0xffff_ffffL).toInt() else 0)
            putAscii(h, "WAVE")
            putAscii(h, if (large) "ds64" else "JUNK")
            h.putInt(28)
            if (large) {
                h.putLong(72L + dataBytes)
                h.putLong(dataBytes)
                h.putLong(sampleFrames)
                h.putInt(0)
            } else repeat(28) { h.put(0) }
            putAscii(h, "fmt ")
            h.putInt(16)
            h.putShort(if (format.floatPcm) 3 else 1)
            h.putShort(channels.toShort())
            h.putInt(sampleRate)
            h.putInt(sampleRate * channels * bytesPerSample)
            h.putShort((channels * bytesPerSample).toShort())
            h.putShort(format.bits.toShort())
            putAscii(h, "data")
            h.putInt(if (large) -1 else if (final) dataBytes.coerceAtMost(0xffff_ffffL).toInt() else 0)
            h.flip()
            while (h.hasRemaining()) channel.write(h)
            if (!final) channel.position(80)
        }

        override fun close() {
            writeHeader(true)
            channel.force(true)
            channel.close()
        }

        private fun putAscii(buffer: ByteBuffer, value: String) {
            value.forEach { buffer.put(it.code.toByte()) }
        }
    }

    private fun createBestTrack(requested: Int, preferred: AudioDeviceInfo?): Pair<AudioTrack, Int> {
        val maxRate = requested.coerceIn(44_100, 192_000)
        val candidates = listOf(maxRate, 192_000, 176_400, 96_000, 88_200, 48_000, 44_100)
            .distinct()
            .filter { it <= maxRate }
        var last: Throwable? = null
        for (rate in candidates) {
            try {
                val mask = AudioFormat.CHANNEL_OUT_STEREO
                val minBytes = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT)
                if (minBytes <= 0) continue
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(max(minBytes, BLOCK * 2 * 4 * 4))
                    .build()
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    continue
                }
                if (preferred != null) track.setPreferredDevice(preferred)
                return track to track.sampleRate
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("Could not open a stereo AudioTrack.", last)
    }

    private fun writeFully(track: AudioTrack, data: FloatArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val written = track.write(data, offset, count - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) error("AudioTrack write failed: $written")
            offset += written
        }
    }

    companion object {
        private const val BLOCK = 1024

        private fun decimate(input: FloatArray, count: Int, target: Int): FloatArray {
            if (count <= target) return input.copyOf(count)
            val out = FloatArray(target)
            val step = count.toDouble() / target
            for (i in out.indices) out[i] = input[(i * step).toInt().coerceAtMost(count - 1)]
            return out
        }
    }
}

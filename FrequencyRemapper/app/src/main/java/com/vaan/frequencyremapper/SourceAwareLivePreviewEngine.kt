package com.vaan.frequencyremapper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.io.Closeable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v1.6 live engine: original URI streaming + frame-local source mask + dry
 * Spectral Solo + non-destructive category edit graph. Transient decoder/audio
 * failures rebuild the session up to three times instead of killing the app.
 */
class SourceAwareLivePreviewEngine(private val context: Context) : Closeable {
    private data class Config(
        val objects: List<SpectralObject>,
        val edits: List<SpectralFrequencyEdit>,
        val rules: List<CategoryTransformRule>,
        val manualRegions: List<ManualMaskRegion>,
        val options: PhaseRemapOptions,
        val soloCategory: AudioCategory?,
        val soloConfidence: Double,
        val version: Long
    )

    private data class IndividualPlan(
        val ratio: DoubleArray,
        val weight: DoubleArray,
        val phaseRadians: DoubleArray,
        val categoryOrdinal: IntArray
    )

    private data class RuntimePlan(
        val version: Long,
        val masker: TimeFrequencyCategoryMasker,
        val individual: IndividualPlan,
        val rules: Array<CategoryTransformRule?>,
        val hasProcessing: Boolean,
        val soloCategory: AudioCategory?,
        val soloConfidence: Double
    )

    @Volatile private var running = false
    @Volatile private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var onError: ((String) -> Unit)? = null
    @Volatile private var onEnded: (() -> Unit)? = null
    @Volatile private var config = Config(emptyList(), emptyList(), emptyList(), emptyList(), PhaseRemapOptions(), null, 0.45, 0L)
    private var versionCounter = 0L

    @Synchronized
    fun start(
        source: StreamAudioSource,
        objects: List<SpectralObject>,
        edits: List<SpectralFrequencyEdit>,
        rules: List<CategoryTransformRule>,
        options: PhaseRemapOptions,
        soloCategory: AudioCategory? = null,
        soloConfidence: Double = 0.45,
        manualRegions: List<ManualMaskRegion> = emptyList(),
        onError: (String) -> Unit = {},
        onEnded: () -> Unit = {}
    ) {
        stop()
        versionCounter++
        config = Config(
            objects.toList(), edits.toList(), rules.toList(), manualRegions.toList(),
            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), versionCounter
        )
        this.onError = onError
        this.onEnded = onEnded
        running = true
        worker = Thread({
            var failures = 0
            try {
                while (running) {
                    try {
                        runSession(source)
                        failures = 0
                    } catch (t: Throwable) {
                        if (!running) break
                        failures++
                        releaseTrack()
                        if (failures >= 3) {
                            throw IllegalStateException(
                                "Source-aware live preview failed after 3 recovery attempts: ${t.message ?: t.javaClass.simpleName}",
                                t
                            )
                        }
                        Thread.sleep((160L * failures).coerceAtMost(480L))
                    }
                }
            } catch (t: Throwable) {
                val report = running
                running = false
                if (report) runCatching { this.onError?.invoke(t.message ?: t.javaClass.simpleName) }
            } finally {
                running = false
                releaseTrack()
                worker = null
                runCatching { this.onEnded?.invoke() }
            }
        }, "FrequencyRemapper-SourceAwareLive").apply { start() }
    }

    @Synchronized
    fun update(
        objects: List<SpectralObject>,
        edits: List<SpectralFrequencyEdit>,
        rules: List<CategoryTransformRule>,
        options: PhaseRemapOptions,
        soloCategory: AudioCategory?,
        soloConfidence: Double,
        manualRegions: List<ManualMaskRegion> = emptyList()
    ) {
        versionCounter++
        config = Config(
            objects.toList(), edits.toList(), rules.toList(), manualRegions.toList(),
            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), versionCounter
        )
    }

    @Synchronized
    fun stop() {
        running = false
        val local = track
        runCatching { local?.pause() }
        runCatching { local?.flush() }
        val w = worker
        if (w != null && w !== Thread.currentThread()) {
            runCatching { w.join(1400) }
            if (w.isAlive) runCatching { w.interrupt() }
        }
    }

    override fun close() = stop()

    private fun releaseTrack() {
        val owned = track
        runCatching { owned?.stop() }
        runCatching { owned?.flush() }
        runCatching { owned?.release() }
        if (track === owned) track = null
    }

    private fun runSession(source: StreamAudioSource) {
        require(source.sampleRate in 4000..192000) { "Unsupported sample rate ${source.sampleRate} Hz." }
        require(source.channels > 0) { "Audio has no channels." }
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

        val fftSize = if (source.sampleRate <= 50000) 1024 else 2048
        val hop = fftSize / 4
        val half = fftSize / 2
        val inChannels = source.channels
        val outChannels = if (inChannels == 1) 1 else 2
        val channelMask = if (outChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(source.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "Android rejected ${source.sampleRate} Hz live output." }

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(source.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuffer * 4, hop * outChannels * 2 * 14))
            .build()
        require(audioTrack.state == AudioTrack.STATE_INITIALIZED) { "Android audio output failed to initialize." }
        track = audioTrack

        val input = Array(inChannels) { FloatArray(fftSize) }
        val ola = Array(outChannels) { FloatArray(fftSize) }
        val norm = FloatArray(fftSize)
        val window = hammingWindow(fftSize)
        val windowSq = FloatArray(fftSize) { window[it] * window[it] }
        val prevPhase = Array(outChannels) { FloatArray(half + 1) }
        val mappedPhase = Array(outChannels) { FloatArray(half + 1) }
        val phaseSeen = Array(outChannels) { BooleanArray(half + 1) }
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        val outReal = FloatArray(fftSize)
        val outImag = FloatArray(fftSize)
        val monoReal = FloatArray(fftSize)
        val monoImag = FloatArray(fftSize)
        val power = DoubleArray(half + 1)
        val interleaved = ShortArray(hop * outChannels)
        var plan = compileRuntime(source.sampleRate, fftSize, config)

        audioTrack.play()

        while (running) {
            for (a in input) a.fill(0f)
            for (a in ola) a.fill(0f)
            norm.fill(0f)
            for (a in prevPhase) a.fill(0f)
            for (a in mappedPhase) a.fill(0f)
            for (a in phaseSeen) a.fill(false)

            var any = false
            var timelineFrames = 0L
            StreamingPcmFrameReader(context, source).use { reader ->
                var windowValid = reader.readInto(input, 0, fftSize)
                while (running && windowValid > 0) {
                    any = true
                    val latest = config
                    if (latest.version != plan.version) {
                        plan = compileRuntime(source.sampleRate, fftSize, latest)
                        for (a in phaseSeen) a.fill(false)
                    }

                    val toWrite = min(hop, windowValid)
                    if (!plan.hasProcessing) {
                        for (i in 0 until toWrite) {
                            for (ch in 0 until outChannels) {
                                val srcCh = if (inChannels == 1) 0 else ch.coerceAtMost(inChannels - 1)
                                interleaved[i * outChannels + ch] =
                                    (input[srcCh][i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                            }
                        }
                    } else {
                        for (i in 0 until fftSize) {
                            var mono = 0f
                            for (ch in 0 until inChannels) mono += input[ch][i]
                            monoReal[i] = (mono / inChannels) * window[i]
                            monoImag[i] = 0f
                        }
                        FastFft.transform(monoReal, monoImag, false)
                        for (k in 0..half) {
                            val re = monoReal[k].toDouble()
                            val im = monoImag[k].toDouble()
                            power[k] = re * re + im * im
                        }
                        val timeSeconds = timelineFrames.toDouble() / source.sampleRate
                        val mask = plan.masker.classify(power, timeSeconds)

                        for (outCh in 0 until outChannels) {
                            val srcCh = if (inChannels == 1) 0 else outCh.coerceAtMost(inChannels - 1)
                            for (i in 0 until fftSize) {
                                real[i] = input[srcCh][i] * window[i]
                                imag[i] = 0f
                            }
                            FastFft.transform(real, imag, false)
                            warp(
                                real, imag, outReal, outImag,
                                prevPhase[outCh], mappedPhase[outCh], phaseSeen[outCh],
                                fftSize, hop, source.sampleRate, mask, plan
                            )
                            FastFft.transform(outReal, outImag, true)
                            for (i in 0 until fftSize) ola[outCh][i] += outReal[i] * window[i]
                        }
                        for (i in 0 until fftSize) norm[i] += windowSq[i]
                        for (i in 0 until toWrite) {
                            val scale = 1f / max(norm[i], 1e-7f)
                            for (ch in 0 until outChannels) {
                                interleaved[i * outChannels + ch] =
                                    ((ola[ch][i] * scale).coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                            }
                        }
                    }

                    var shortOffset = 0
                    val shortCount = toWrite * outChannels
                    while (running && shortOffset < shortCount) {
                        val wrote = audioTrack.write(interleaved, shortOffset, shortCount - shortOffset, AudioTrack.WRITE_BLOCKING)
                        if (wrote == AudioTrack.ERROR_DEAD_OBJECT) error("Audio output disconnected.")
                        if (wrote < 0) error("Audio output write failed: $wrote")
                        if (wrote == 0) break
                        shortOffset += wrote
                    }

                    timelineFrames += toWrite
                    for (ch in 0 until inChannels) {
                        input[ch].copyInto(input[ch], 0, hop, fftSize)
                        input[ch].fill(0f, fftSize - hop, fftSize)
                    }
                    for (ch in 0 until outChannels) {
                        ola[ch].copyInto(ola[ch], 0, hop, fftSize)
                        ola[ch].fill(0f, fftSize - hop, fftSize)
                    }
                    norm.copyInto(norm, 0, hop, fftSize)
                    norm.fill(0f, fftSize - hop, fftSize)
                    val newlyRead = reader.readInto(input, fftSize - hop, hop)
                    windowValid = max(0, windowValid - hop) + newlyRead
                }
            }

            if (!running) break
            if (!any) error("Decoder returned no playable audio.")
            // Loop at EOF so the matrix remains auditionable while editing.
        }
    }

    private fun compileRuntime(sampleRate: Int, fftSize: Int, cfg: Config): RuntimePlan {
        val individual = compileIndividual(sampleRate, fftSize, cfg.edits, cfg.options)
        val rules = Array<CategoryTransformRule?>(AudioCategory.entries.size) { null }
        var activeRules = 0
        for (rule in cfg.rules) {
            if (!rule.enabled) continue
            if (rule.value == null && abs(normalizedDegrees(rule.phaseDegrees)) < 1e-8) continue
            rules[rule.category.ordinal] = rule
            activeRules++
        }
        return RuntimePlan(
            version = cfg.version,
            masker = TimeFrequencyCategoryMasker(cfg.objects, sampleRate, fftSize, cfg.manualRegions),
            individual = individual,
            rules = rules,
            hasProcessing = cfg.soloCategory != null || activeRules > 0 || cfg.edits.any {
                it.enabled && (abs(it.targetHz - it.sourceHz) > 0.0001 || abs(normalizedDegrees(it.phaseDegrees)) > 0.0001)
            },
            soloCategory = cfg.soloCategory,
            soloConfidence = cfg.soloConfidence
        )
    }

    private fun compileIndividual(
        sampleRate: Int,
        fftSize: Int,
        edits: List<SpectralFrequencyEdit>,
        options: PhaseRemapOptions
    ): IndividualPlan {
        val half = fftSize / 2
        val ratio = DoubleArray(half + 1) { 1.0 }
        val weight = DoubleArray(half + 1)
        val phase = DoubleArray(half + 1)
        val category = IntArray(half + 1) { -1 }
        val binHz = sampleRate.toDouble() / fftSize
        val nyquist = sampleRate / 2.0

        for (k in 1 until half) {
            val frequency = k * binHz
            var bestWeight = 0.0
            for (edit in edits) {
                if (!edit.enabled || edit.sourceHz <= 0.0 || edit.targetHz <= 0.0 || edit.sourceHz >= nyquist || edit.targetHz >= nyquist) continue
                if (abs(edit.targetHz - edit.sourceHz) <= 0.0001 && abs(normalizedDegrees(edit.phaseDegrees)) <= 0.0001) continue
                val h = if (options.shiftHarmonicFamily) (frequency / edit.sourceHz).roundToInt().coerceAtLeast(1) else 1
                if (h > options.maxHarmonics) continue
                val center = edit.sourceHz * h
                if (center <= 0.0 || center >= nyquist) continue
                val minCents = if (center > binHz) 1200.0 * log2((center + binHz) / center) else options.bandCents
                val band = max(options.bandCents, minCents)
                val distance = abs(1200.0 * log2(frequency / center))
                if (distance > band) continue
                val edge = (distance / band).coerceIn(0.0, 1.0)
                val w = if (edge <= 0.62) 1.0 else 0.5 * (1.0 + cos(PI * ((edge - 0.62) / 0.38)))
                val r = edit.targetHz / edit.sourceHz
                if (frequency * r <= 0.0 || frequency * r >= nyquist) continue
                if (w > bestWeight) {
                    bestWeight = w
                    ratio[k] = r
                    weight[k] = w
                    val mult = if (options.shiftHarmonicFamily) h.toDouble() else 1.0
                    phase[k] = normalizedDegrees(edit.phaseDegrees * mult) * PI / 180.0
                    category[k] = edit.category.ordinal
                }
            }
        }
        return IndividualPlan(ratio, weight, phase, category)
    }

    private fun warp(
        real: FloatArray,
        imag: FloatArray,
        outReal: FloatArray,
        outImag: FloatArray,
        prevPhase: FloatArray,
        mappedPhase: FloatArray,
        phaseSeen: BooleanArray,
        fftSize: Int,
        hop: Int,
        sampleRate: Int,
        mask: FrameCategoryMask,
        plan: RuntimePlan
    ) {
        outReal.fill(0f)
        outImag.fill(0f)
        val half = fftSize / 2
        val binHz = sampleRate.toDouble() / fftSize
        outReal[0] = if (plan.soloCategory == null) real[0] else 0f
        outReal[half] = if (plan.soloCategory == null) real[half] else 0f

        for (k in 1 until half) {
            val re = real[k].toDouble()
            val im = imag[k].toDouble()
            val sourcePhase = kotlin.math.atan2(im, re)
            val cat = mask.categoryOrdinal[k]
            val confidence = mask.confidence[k].toDouble()

            if (plan.soloCategory != null) {
                prevPhase[k] = sourcePhase.toFloat()
                if (cat == plan.soloCategory.ordinal && confidence >= plan.soloConfidence) {
                    outReal[k] = real[k]
                    outImag[k] = imag[k]
                }
                continue
            }

            var ratio = 1.0
            var phase = 0.0
            var weight = 0.0
            val individual = plan.individual
            if (individual.weight[k] > 0.0001 && individual.categoryOrdinal[k] == cat && confidence >= 0.25) {
                ratio = individual.ratio[k]
                phase = individual.phaseRadians[k]
                weight = individual.weight[k]
            } else {
                val rule = plan.rules.getOrNull(cat)
                if (rule != null && confidence >= rule.confidenceThreshold) {
                    val frequency = k * binHz
                    val target = rule.targetFor(frequency)
                    if (target.isFinite() && target > 0.0 && target < sampleRate / 2.0) ratio = target / frequency
                    phase = normalizedDegrees(rule.phaseDegrees) * PI / 180.0
                    if (abs(ratio - 1.0) > 1e-8 || abs(phase) > 1e-8) weight = 1.0
                }
            }

            if (weight <= 0.0001) {
                outReal[k] += real[k]
                outImag[k] += imag[k]
                prevPhase[k] = sourcePhase.toFloat()
                continue
            }

            val magnitude = hypot(re, im)
            val keep = (1.0 - weight).coerceIn(0.0, 1.0)
            outReal[k] += (re * keep).toFloat()
            outImag[k] += (im * keep).toFloat()
            val baseOmega = 2.0 * PI * k / fftSize
            val targetOmega: Double
            if (!phaseSeen[k]) {
                mappedPhase[k] = sourcePhase.toFloat()
                phaseSeen[k] = true
                targetOmega = baseOmega * ratio
            } else {
                val expected = 2.0 * PI * k * hop / fftSize
                val delta = principalPhase(sourcePhase - prevPhase[k] - expected)
                val trueOmega = baseOmega + delta / hop
                targetOmega = trueOmega * ratio
                mappedPhase[k] = principalPhase(mappedPhase[k] + targetOmega * hop).toFloat()
            }
            prevPhase[k] = sourcePhase.toFloat()

            val destination = targetOmega * fftSize / (2.0 * PI)
            if (destination <= 0.0 || destination >= half - 1.0) {
                outReal[k] += (re * weight).toFloat()
                outImag[k] += (im * weight).toFloat()
                continue
            }
            val lower = floor(destination).toInt().coerceIn(1, half - 1)
            val upper = min(half - 1, lower + 1)
            val upperWeight = destination - lower
            val shiftedMagnitude = magnitude * weight
            val shiftedPhase = principalPhase(mappedPhase[k].toDouble() + phase)
            val sr = cos(shiftedPhase) * shiftedMagnitude
            val si = sin(shiftedPhase) * shiftedMagnitude
            outReal[lower] += (sr * (1.0 - upperWeight)).toFloat()
            outImag[lower] += (si * (1.0 - upperWeight)).toFloat()
            if (upper != lower) {
                outReal[upper] += (sr * upperWeight).toFloat()
                outImag[upper] += (si * upperWeight).toFloat()
            }
        }

        for (k in 1 until half) {
            outReal[fftSize - k] = outReal[k]
            outImag[fftSize - k] = -outImag[k]
        }
    }

    private fun normalizedDegrees(value: Double): Double {
        var x = value % 360.0
        if (x > 180.0) x -= 360.0
        if (x <= -180.0) x += 360.0
        return x
    }

    private fun principalPhase(value: Double): Double {
        var x = value
        while (x > PI) x -= 2.0 * PI
        while (x < -PI) x += 2.0 * PI
        return x
    }
}

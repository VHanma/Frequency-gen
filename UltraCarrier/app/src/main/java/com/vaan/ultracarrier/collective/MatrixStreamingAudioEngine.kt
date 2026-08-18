package com.vaan.ultracarrier.collective

import android.content.ContentResolver
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

class MatrixStreamingAudioEngine(private val resolver: ContentResolver) {
    private val stopped = AtomicBoolean(true)
    @Volatile private var activeTrack: AudioTrack? = null

    fun stop() {
        stopped.set(true)
        activeTrack?.let { t ->
            runCatching { t.pause() }
            runCatching { t.flush() }
            runCatching { t.stop() }
        }
    }

    fun play(
        source: CollectiveSource,
        decoder: CollectiveStreamDecoder,
        config: CollectiveConfig,
        mode: MatrixMode,
        preferredDevice: AudioDeviceInfo?,
        onStarted: (CollectiveReport) -> Unit,
        onScope: (FloatArray, Int) -> Unit
    ) {
        stop(); stopped.set(false)
        val (track, sampleRate) = createBestTrack(config.requestedSampleRate, preferredDevice)
        activeTrack = track
        val session = Session(config, mode, sampleRate, onScope)
        try {
            track.play()
            onStarted(CollectiveReport(sampleRate, track.routedDevice?.productName?.toString() ?: "system-selected output", CollectiveFamily.SCALAR_LAB, mode.label, session.carrier))
            val resampler = StreamingResampler(source.info.sampleRate, sampleRate) { mono, count ->
                if (!stopped.get()) session.process(mono, count) { stereo, n -> writeFully(track, stereo, n) }
            }
            decoder.stream(source) { chunk, count, _ ->
                if (stopped.get()) false else { resampler.append(chunk, 0, count); true }
            }
            if (!stopped.get()) resampler.finish()
        } finally {
            runCatching { track.stop() }; runCatching { track.flush() }; track.release(); activeTrack = null; stopped.set(true)
        }
    }

    fun export(
        source: CollectiveSource,
        decoder: CollectiveStreamDecoder,
        config: CollectiveConfig,
        mode: MatrixMode,
        destination: Uri,
        format: ExportFormat,
        onProgress: (Double?) -> Unit,
        onScope: (FloatArray, Int) -> Unit
    ) {
        stop(); stopped.set(false)
        val sampleRate = config.requestedSampleRate.coerceIn(44_100, 192_000)
        val session = Session(config, mode, sampleRate, onScope)
        val pfd = resolver.openFileDescriptor(destination, "rwt") ?: error("Could not open save destination.")
        pfd.use {
            val writer = WaveWriter(FileOutputStream(it.fileDescriptor).channel, sampleRate, 2, format)
            try {
                var inputFrames = 0L
                val total = source.info.durationSeconds?.let { d -> (d * source.info.sampleRate).toLong() }
                val resampler = StreamingResampler(source.info.sampleRate, sampleRate) { mono, count ->
                    if (!stopped.get()) session.process(mono, count) { stereo, n -> writer.write(stereo, n) }
                }
                decoder.stream(source) { chunk, count, _ ->
                    if (stopped.get()) false else {
                        inputFrames += count
                        resampler.append(chunk, 0, count)
                        onProgress(total?.takeIf { it > 0 }?.let { t -> (inputFrames.toDouble() / t).coerceIn(0.0, 1.0) })
                        true
                    }
                }
                if (!stopped.get()) resampler.finish()
            } finally { writer.close() }
        }
        stopped.set(true)
    }

    private class Session(
        private val cfg: CollectiveConfig,
        private val mode: MatrixMode,
        private val rateOut: Int,
        private val onScope: (FloatArray, Int) -> Unit
    ) {
        val carrier = cfg.carrierHz.coerceIn(80f, rateOut / 2f - 700f)
        private val block = FloatArray(BLOCK * 2)
        private val scope = FloatArray(BLOCK)
        private val delayL = FloatArray(4096)
        private val delayR = FloatArray(4096)
        private var delayPos = 0
        private var frame = 0L
        private var p1 = 0.0
        private var p2 = 0.0
        private var p3 = 0.0
        private var p4 = 0.0
        private var lp = 0f
        private var memory = 0f
        private var randomWalk = 0f
        private var zcFrames = 1
        private var last = 0f
        private var trackedHz = 220f
        private var scopeCounter = 0
        private var seed = 0x5EED1234

        fun process(mono: FloatArray, count: Int, sink: (FloatArray, Int) -> Unit) {
            var off = 0
            while (off < count) {
                val n = min(BLOCK, count - off)
                for (i in 0 until n) {
                    val x0 = tanh((mono[off + i] * 2.1).toDouble()).toFloat()
                    track(x0)
                    val pair = sample(x0, frame + i)
                    val gain = when (cfg.listeningPath) {
                        com.vaan.ultracarrier.audio.ListeningPath.PHONE_SPEAKER -> .16f + .20f * cfg.presence
                        com.vaan.ultracarrier.audio.ListeningPath.EXTERNAL_ARRAY -> .20f + .26f * cfg.presence
                        else -> .12f + .18f * cfg.presence
                    }
                    val l = (pair.first * gain).coerceIn(-.96f, .96f)
                    val r = (pair.second * gain).coerceIn(-.96f, .96f)
                    block[i * 2] = l; block[i * 2 + 1] = r; scope[i] = (l + r) * .5f
                }
                sink(block, n * 2)
                frame += n; off += n; scopeCounter++
                if (scopeCounter % 2 == 0) onScope(decimate(scope, n, 512), rateOut)
            }
        }

        private fun sample(x: Float, f: Long): Pair<Float, Float> {
            val t = f.toDouble() / rateOut
            val modRate = cfg.elfRateHz.coerceIn(.02f, 120f)
            val depth = cfg.elfDepth.coerceIn(0f, .98f)
            val motion = cfg.ditherRateHz.coerceIn(.02f, 5f)
            val maxF = rateOut / 2f - 700f
            val amp = (.2f + .8f * abs(x)).coerceIn(.05f, 1f)
            val slow = (.5f + .5f * sin(2.0 * PI * modRate * t).toFloat())
            val env = 1f - depth + depth * slow
            val spin = 2.0 * PI * motion * t
            val base = trackedHz.coerceIn(60f, 5000f)
            val out = when (mode.kind) {
                MatrixKind.VACUUM -> {
                    noiseStep()
                    advance1((carrier * (.94f + .06f * slow)).coerceIn(80f, maxF))
                    advance2((carrier * (1.06f - .04f * slow)).coerceIn(80f, maxF))
                    val bias = x * depth
                    ((cos(p1) * (1 + bias) + .18 * randomWalk).toFloat() * amp) to ((cos(p2) * (1 - bias) - .18 * randomWalk).toFloat() * amp)
                }
                MatrixKind.LANGUAGE -> {
                    val cell = ((x + 1f) * 7.5f).toInt().coerceIn(0, 15)
                    val ratios = floatArrayOf(1f, 1.125f, 1.25f, 1.333f, 1.5f, 1.667f, 1.875f, 2f)
                    val hz = (180f * ratios[cell % ratios.size] * (1f + cell / 16f)).coerceIn(100f, 2200f)
                    advance1(hz); advance2((hz * 1.5f).coerceAtMost(4000f))
                    val gate = if ((t * (2.0 + modRate / 6.0)) % 1.0 < .72) 1f else .2f
                    ((sin(p1) + .3 * sin(p2)).toFloat() * amp * gate / 1.3f) to ((sin(p1 + .04) + .3 * sin(p2 - .05)).toFloat() * amp * gate / 1.3f)
                }
                MatrixKind.SOLITON -> {
                    val u = ((t * modRate) % 1.0 - .5) * 7.0
                    val pulse = (1.0 / kotlin.math.cosh(u)).toFloat()
                    advance1(carrier.coerceIn(100f, maxF))
                    val y = cos(p1).toFloat() * amp * pulse
                    y to (y * .97f)
                }
                MatrixKind.FRACTAL -> {
                    val m = (sin(2 * PI * modRate * t) + .55 * sin(2 * PI * modRate * 3.0 * t) + .34 * sin(2 * PI * modRate / 3.0 * t)).toFloat() / 1.89f
                    advance1(base); advance2((base * 2).coerceAtMost(5000f)); advance3((base * .5f).coerceAtLeast(60f))
                    val y = (sin(p1) + .5 * sin(p2) + .35 * sin(p3)).toFloat() * amp * (.62f + .32f * m) / 1.85f
                    y to (y * (.96f + .04f * m))
                }
                MatrixKind.NUMBER_MAP -> {
                    val index = ((abs(x) * 31f).toInt() + ((t * modRate).toInt() % 17)).coerceAtLeast(0)
                    val seq = if (mode == MatrixMode.FIBONACCI_MAP) fib(index % 12 + 1) else prime(index % 24 + 1)
                    val hz = (90f + (seq % 53) * 37f).coerceIn(90f, 2600f)
                    advance1(hz); advance2((hz * (1f + (seq % 5) * .1f)).coerceAtMost(4200f))
                    sin(p1).toFloat() * amp to sin(p2).toFloat() * amp
                }
                MatrixKind.HOLOGRAM -> {
                    advance1((carrier * .82f).coerceIn(100f, maxF)); advance2(carrier); advance3((carrier * 1.07f).coerceAtMost(maxF)); advance4((carrier * 1.13f).coerceAtMost(maxF))
                    val l = (cos(p1 + spin) + .7 * cos(p2 - spin) + .5 * cos(p3 + 2 * spin) + .35 * cos(p4 - 2 * spin)).toFloat() * amp * env / 2.55f
                    val r = (cos(p1 - spin) + .7 * cos(p2 + spin) + .5 * cos(p3 - 2 * spin) + .35 * cos(p4 + 2 * spin)).toFloat() * amp * env / 2.55f
                    l to r
                }
                MatrixKind.LIGHT -> {
                    val opticalCode = when (mode) {
                        MatrixMode.HENE_632NM -> .632f; MatrixMode.DNA_UV_390NM -> .390f; MatrixMode.RED_LIGHT -> .650f; MatrixMode.BLUE_LIGHT -> .470f; MatrixMode.ULTRAVIOLET -> .365f; MatrixMode.INFRARED -> .850f; MatrixMode.NEAR_INFRARED -> .780f; else -> .55f
                    }
                    val hz = (220f + opticalCode * 1800f).coerceIn(120f, 2600f)
                    advance1(hz); advance2((hz * 2).coerceAtMost(5000f))
                    val pulse = (.3f + .7f * env)
                    val y = (sin(p1) + .25 * sin(p2)).toFloat() * amp * pulse / 1.25f
                    y to (y * (.92f + .08f * sin(spin).toFloat()))
                }
                MatrixKind.POLARIZATION -> {
                    advance1(carrier.coerceIn(100f, maxF))
                    val a = spin + x * PI * depth
                    cos(p1 + a).toFloat() * amp to cos(p1 - a).toFloat() * amp
                }
                MatrixKind.SPIN -> {
                    val state = if (x >= 0) 1f else -1f
                    advance1(carrier.coerceIn(100f, maxF)); advance2((carrier + modRate).coerceAtMost(maxF))
                    (cos(p1) * state).toFloat() * amp to (cos(p2) * -state).toFloat() * amp
                }
                MatrixKind.PHASE -> {
                    advance1(carrier.coerceIn(100f, maxF))
                    val off = when (mode) {
                        MatrixMode.PHASE_REVERSED -> PI
                        MatrixMode.PHASE_OFFSET_STEREO -> PI / 2
                        MatrixMode.BINAURAL_MATRIX -> 2 * PI * modRate * t
                        else -> spin
                    }
                    cos(p1 + off).toFloat() * amp to cos(if (mode == MatrixMode.PHASE_CONJUGATE) -p1 - off else p1 - off).toFloat() * amp
                }
                MatrixKind.INTERFERENCE -> {
                    val d = (80 + (cfg.ditherDeg * 20).toInt()).coerceIn(1, delayL.size - 2)
                    val delayed = delayL[(delayPos - d + delayL.size) % delayL.size]
                    delayL[delayPos] = x
                    val sign = if (mode == MatrixMode.CONSTRUCTIVE_INTERFERENCE) 1f else -1f
                    val y = (x + sign * delayed * (.35f + .45f * depth)) / 1.8f
                    advance1((carrier * .25f).coerceIn(100f, maxF))
                    val node = cos(p1).toFloat() * .18f * amp
                    (y + node) to (y - node)
                }
                MatrixKind.RESONANCE -> {
                    val hz = when (mode) {
                        MatrixMode.PRESET_528 -> 528f
                        MatrixMode.PINEAL_RESONANCE -> (base * .72f).coerceIn(80f, 1200f)
                        MatrixMode.ORGANIC_PARTICLE_ACCELERATOR -> (180f + ((t * modRate) % 1.0).pow(2.0).toFloat() * 3200f)
                        else -> base
                    }
                    advance1(hz.coerceIn(60f, maxF)); advance2((hz * 2).coerceAtMost(maxF)); advance3((hz * .5f).coerceAtLeast(40f))
                    val y = (sin(p1) + .45 * sin(p2) + .28 * sin(p3)).toFloat() * amp / 1.73f
                    y to (y * .97f + x * .03f)
                }
                MatrixKind.COHERENCE -> {
                    noiseStep()
                    advance1(base); advance2((base * 1.5f).coerceAtMost(5000f)); advance3((base * 2f).coerceAtMost(6000f))
                    val order = if (mode == MatrixMode.COHERENCE_DISRUPTION || mode == MatrixMode.ENTROPY_COHERENCE) slow else (memory * .5f + .5f).coerceIn(0f, 1f)
                    val ordered = (sin(p1) + .5 * sin(p2) + .3 * sin(p3)).toFloat() / 1.8f
                    val y = ordered * order + randomWalk * (1f - order)
                    y * amp to (y * amp * (.94f + .06f * order))
                }
                MatrixKind.BAND_SYMBOLIC -> {
                    val code = when (mode) {
                        MatrixMode.MHZ_LAYER -> 1.7f; MatrixMode.GHZ_LAYER -> 2.4f; MatrixMode.THZ_LAYER, MatrixMode.FROHLICH_044THZ -> 3.2f
                        MatrixMode.BAND_640_700KHZ -> 1.35f; MatrixMode.BAND_10_100KHZ -> 1.15f; MatrixMode.MONTAGNIER_7HZ -> .7f
                        else -> 1f
                    }
                    val proxy = (carrier / code).coerceIn(100f, maxF)
                    advance1(proxy); advance2((proxy + modRate * code).coerceAtMost(maxF)); advance3((proxy * .5f).coerceAtLeast(60f))
                    val y = (cos(p1) + .45 * cos(p2) + .25 * sin(p3)).toFloat() * amp * env / 1.7f
                    y to (y * .95f + x * .05f)
                }
                MatrixKind.ULTRASONIC -> {
                    val c = carrier.coerceIn(12000f.coerceAtMost(maxF), maxF)
                    advance1(c); advance2((c - 1200f).coerceAtLeast(1000f))
                    val direct = if (mode == MatrixMode.ACOUSTIC_ULTRASONIC_STACK) x * .35f else 0f
                    val mod = sqrt((1f + cfg.presence * x).coerceIn(.02f, 1.98f))
                    (direct + cos(p1).toFloat() * mod * .65f) to (direct + cos(p1 + .08).toFloat() * mod * .65f)
                }
                MatrixKind.HETERODYNE -> {
                    val c1 = carrier.coerceIn(200f, maxF)
                    val c2 = (c1 + modRate * 2f + abs(x) * 400f).coerceAtMost(maxF)
                    advance1(c1); advance2(c2)
                    val y1 = cos(p1).toFloat() * (1f + x * depth)
                    val y2 = cos(p2).toFloat() * (1f - x * depth)
                    (y1 + y2) * .5f to (y1 - y2) * .5f
                }
                MatrixKind.SPECTRAL -> {
                    lp += .025f * (x - lp)
                    memory = memory * .9975f + abs(x) * .0025f
                    val hi = x - lp
                    advance1(base); advance2((base * 2).coerceAtMost(6000f)); advance3((base * 3).coerceAtMost(7000f))
                    val harmonic = (sin(p1) + .5 * sin(p2) + .3 * sin(p3)).toFloat() * memory / 1.8f
                    val y = when (mode) {
                        MatrixMode.ENVELOPE_EXTRACTION -> harmonic * (.2f + .8f * abs(x))
                        MatrixMode.SPECTRAL_MORPH, MatrixMode.REFERENCE_TARGET_MORPH -> x * (1f - slow) + harmonic * slow
                        MatrixMode.CONVOLUTION_IMPRINT -> lp * .65f + hi * memory
                        else -> harmonic * .6f + x * .4f
                    }
                    y to (y * .97f + hi * .03f)
                }
                MatrixKind.TIME -> {
                    val d = when (mode) { MatrixMode.TIME_STRETCH_RESONANCE -> 1200; MatrixMode.TIME_COMPRESSION_RESONANCE -> 120; else -> 600 }
                    val idx = (delayPos - d.coerceAtMost(delayL.size - 1) + delayL.size) % delayL.size
                    val old = delayL[idx]
                    delayL[delayPos] = x
                    val y = when (mode) {
                        MatrixMode.REVERSE_TIME, MatrixMode.TEMPORAL_MIRROR -> old
                        else -> x * .65f + old * .35f
                    }
                    y to (if (mode == MatrixMode.TEMPORAL_MIRROR) x * .5f + old * .5f else y)
                }
                MatrixKind.MEMORY -> {
                    memory = max(abs(x), memory * (.9992f - depth * .0004f))
                    advance1(base); advance2((base * 2).coerceAtMost(5000f))
                    val ghost = (sin(p1) + .4 * sin(p2)).toFloat() * memory / 1.4f
                    (x * .45f + ghost * .55f) to (x * .45f + ghost * .52f)
                }
                MatrixKind.FIELD -> {
                    val drift = sin(2 * PI * (modRate / 20.0).coerceAtLeast(.01) * t).toFloat()
                    advance1((base + drift * 120f).coerceIn(60f, 5000f)); advance2((base * 1.618f).coerceAtMost(6000f))
                    val y = (sin(p1) + .38 * sin(p2)).toFloat() * amp * (.7f + .25f * env) / 1.38f
                    y to (-y * .35f + x * .65f)
                }
                MatrixKind.GEOMETRY -> {
                    advance1((carrier * .25f).coerceIn(100f, maxF)); advance2((carrier * .382f).coerceIn(120f, maxF)); advance3((carrier * .618f).coerceIn(180f, maxF)); advance4((carrier * .82f).coerceIn(220f, maxF))
                    val l = (cos(p1 + spin) + .6 * cos(p2 - 2 * spin) + .4 * cos(p3 + 3 * spin) + .25 * cos(p4 - 4 * spin)).toFloat() * amp / 2.25f
                    val r = (cos(p1 - spin) + .6 * cos(p2 + 2 * spin) + .4 * cos(p3 - 3 * spin) + .25 * cos(p4 + 4 * spin)).toFloat() * amp / 2.25f
                    l to r
                }
                MatrixKind.WATER -> {
                    noiseStep(); memory = memory * .998f + abs(x) * .002f
                    advance1((base * .7f).coerceAtLeast(50f)); advance2(base); advance3((base * 1.4f).coerceAtMost(5000f))
                    val ordered = (sin(p1) + .55 * sin(p2) + .3 * sin(p3)).toFloat() / 1.85f
                    val y = ordered * (.55f + .4f * memory) + randomWalk * .08f
                    y * amp to y * amp * (.96f + .04f * slow)
                }
                MatrixKind.BIO -> {
                    advance1((base * .5f).coerceAtLeast(40f)); advance2(base); advance3((base * 2).coerceAtMost(5000f)); advance4((base * 3).coerceAtMost(6500f))
                    val pulse = (.55f + .45f * slow)
                    val l = (sin(p1) + .55 * sin(p2 + spin) + .32 * sin(p3) + .18 * sin(p4 - spin)).toFloat() * amp * pulse / 2.05f
                    val r = (sin(p1) + .55 * sin(p2 - spin) + .32 * sin(p3) + .18 * sin(p4 + spin)).toFloat() * amp * pulse / 2.05f
                    l to r
                }
                MatrixKind.NOISE_ORDER -> {
                    noiseStep(); advance1(base); advance2((base * 1.5f).coerceAtMost(5000f))
                    val order = if (mode == MatrixMode.BROWNIAN_BASELINE) 0f else slow
                    val periodic = (sin(p1) + .5 * sin(p2)).toFloat() / 1.5f
                    val y = randomWalk * (1f - order) + periodic * order
                    y * amp to (y * amp * (.92f + .08f * order))
                }
                MatrixKind.SIGNATURE -> {
                    memory = memory * .995f + abs(x) * .005f
                    val code = (mode.ordinal % 9) + 1
                    val hz = (140f + code * 73f + trackedHz * .35f).coerceIn(80f, 2500f)
                    advance1(hz); advance2((hz * 1.5f).coerceAtMost(4200f)); advance3((hz * 2f).coerceAtMost(5000f))
                    val y = (sin(p1) + .45 * sin(p2) + .25 * sin(p3)).toFloat() * (.25f + .75f * memory) / 1.7f
                    y to y
                }
                MatrixKind.AUTO -> {
                    val rounded = nearestSchumann(trackedHz)
                    val hz = when (mode) {
                        MatrixMode.AUTO_SCHUMANN_ROUND -> (180f + rounded * 24f).coerceIn(120f, 2600f)
                        else -> base
                    }
                    advance1(hz); advance2((hz * 2).coerceAtMost(6000f)); advance3((hz * 3).coerceAtMost(7000f))
                    val harmonic = (sin(p1) + .5 * sin(p2) + .3 * sin(p3)).toFloat() / 1.8f
                    val fract = (sin(2 * PI * modRate * t) + .5 * sin(2 * PI * modRate * 3 * t)).toFloat() / 1.5f
                    val y = when (mode) {
                        MatrixMode.AUTO_PHASE_COHERENCE -> harmonic * amp
                        MatrixMode.AUTO_FRACTALIZE -> harmonic * amp * (.65f + .3f * fract)
                        MatrixMode.AUTO_SOLITON -> harmonic * amp * (1.0 / kotlin.math.cosh((((t * modRate) % 1.0) - .5) * 7.0)).toFloat()
                        MatrixMode.AUTO_DNA_SCALAR_STACK -> harmonic * amp * env
                        else -> harmonic * (.3f + .7f * abs(x))
                    }
                    y to (if (mode == MatrixMode.AUTO_DNA_SCALAR_STACK) -y * .92f else y * .98f)
                }
            }
            delayR[delayPos] = out.second; delayPos = (delayPos + 1) % delayL.size
            wrap(); return out
        }

        private fun track(x: Float) {
            zcFrames++
            if ((x >= 0f) != (last >= 0f)) {
                if (zcFrames > 2) {
                    val estimate = rateOut / (2f * zcFrames)
                    if (estimate in 40f..5000f) trackedHz = trackedHz * .92f + estimate * .08f
                }
                zcFrames = 0
            }
            last = x
        }

        private fun noiseStep() {
            seed = seed * 1664525 + 1013904223
            val n = (((seed ushr 8) and 0xffff) / 32767.5f - 1f)
            randomWalk = (randomWalk * .985f + n * .015f).coerceIn(-1f, 1f)
        }

        private fun advance1(hz: Float) { p1 += 2 * PI * hz / rateOut }
        private fun advance2(hz: Float) { p2 += 2 * PI * hz / rateOut }
        private fun advance3(hz: Float) { p3 += 2 * PI * hz / rateOut }
        private fun advance4(hz: Float) { p4 += 2 * PI * hz / rateOut }
        private fun wrap() {
            val tau = 2 * PI
            if (abs(p1) > tau * 64) p1 %= tau; if (abs(p2) > tau * 64) p2 %= tau
            if (abs(p3) > tau * 64) p3 %= tau; if (abs(p4) > tau * 64) p4 %= tau
        }
        private fun nearestSchumann(hz: Float): Float {
            val r = floatArrayOf(7.83f, 14.3f, 20.8f, 27.3f, 33.8f, 39.9f, 45.8f)
            var folded = hz
            while (folded > 60f) folded *= .5f
            while (folded < 4f) folded *= 2f
            return r.minBy { abs(it - folded) }
        }
        private fun prime(n: Int): Int {
            var count = 0; var v = 1
            while (count < n) { v++; var ok = true; var d = 2; while (d * d <= v) { if (v % d == 0) { ok = false; break }; d++ }; if (ok) count++ }
            return v
        }
        private fun fib(n: Int): Int { var a = 1; var b = 1; repeat((n - 1).coerceAtLeast(0)) { val c = a + b; a = b; b = c }; return a }
    }

    private class StreamingResampler(sourceRate: Int, targetRate: Int, private val emit: (FloatArray, Int) -> Unit) {
        private val ratio = sourceRate.toDouble() / targetRate
        private var tail = FloatArray(0); private var position = 0.0
        private val output = FloatArray(BLOCK); private var count = 0
        fun append(input: FloatArray, offset: Int, amount: Int) {
            if (amount <= 0) return
            if (abs(ratio - 1.0) < 1e-9) {
                var off = offset; var left = amount
                while (left > 0) { val n = min(BLOCK, left); input.copyInto(output, 0, off, off + n); emit(output, n); off += n; left -= n }
                return
            }
            val combined = FloatArray(tail.size + amount); tail.copyInto(combined); input.copyInto(combined, tail.size, offset, offset + amount)
            while (position + 1.0 < combined.size) {
                val i = floor(position).toInt(); val f = (position - i).toFloat(); output[count++] = combined[i] + (combined[i + 1] - combined[i]) * f
                if (count == output.size) { emit(output, count); count = 0 }; position += ratio
            }
            val consumed = floor(position).toInt().coerceIn(0, max(0, combined.size - 1)); tail = combined.copyOfRange(consumed, combined.size); position -= consumed
        }
        fun finish() { if (count > 0) emit(output, count); count = 0 }
    }

    private class WaveWriter(private val channel: FileChannel, private val sampleRate: Int, private val channels: Int, private val format: ExportFormat) : AutoCloseable {
        private var dataBytes = 0L; private var frames = 0L; private val bps = format.bits / 8
        init { header(false) }
        fun write(samples: FloatArray, count: Int) {
            val b = ByteBuffer.allocate(count * bps).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val x = samples[i].coerceIn(-1f, 1f)
                when (format) {
                    ExportFormat.WAV_16 -> b.putShort((x * 32767f).roundToInt().toShort())
                    ExportFormat.WAV_24 -> { val v = (x * 8_388_607f).roundToInt(); b.put((v and 255).toByte()); b.put(((v ushr 8) and 255).toByte()); b.put(((v ushr 16) and 255).toByte()) }
                    ExportFormat.WAV_FLOAT32 -> b.putFloat(x)
                }
            }
            b.flip(); while (b.hasRemaining()) channel.write(b); dataBytes += count.toLong() * bps; frames += count / channels
        }
        private fun header(final: Boolean) {
            val large = final && dataBytes > 0xffff_ff00L; channel.position(0); val h = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
            ascii(h, if (large) "RF64" else "RIFF"); h.putInt(if (large) -1 else if (final) (72L + dataBytes).coerceAtMost(0xffff_ffffL).toInt() else 0); ascii(h, "WAVE")
            ascii(h, if (large) "ds64" else "JUNK"); h.putInt(28)
            if (large) { h.putLong(72L + dataBytes); h.putLong(dataBytes); h.putLong(frames); h.putInt(0) } else repeat(28) { h.put(0) }
            ascii(h, "fmt "); h.putInt(16); h.putShort(if (format.floatPcm) 3 else 1); h.putShort(channels.toShort()); h.putInt(sampleRate); h.putInt(sampleRate * channels * bps); h.putShort((channels * bps).toShort()); h.putShort(format.bits.toShort())
            ascii(h, "data"); h.putInt(if (large) -1 else if (final) dataBytes.coerceAtMost(0xffff_ffffL).toInt() else 0); h.flip(); while (h.hasRemaining()) channel.write(h); if (!final) channel.position(80)
        }
        override fun close() { header(true); channel.force(true); channel.close() }
        private fun ascii(b: ByteBuffer, s: String) { s.forEach { b.put(it.code.toByte()) } }
    }

    private fun createBestTrack(requested: Int, preferred: AudioDeviceInfo?): Pair<AudioTrack, Int> {
        val maxRate = requested.coerceIn(44_100, 192_000)
        val candidates = listOf(maxRate, 192_000, 176_400, 96_000, 88_200, 48_000, 44_100).distinct().filter { it <= maxRate }
        var last: Throwable? = null
        for (rate in candidates) try {
            val mask = AudioFormat.CHANNEL_OUT_STEREO; val minBytes = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT); if (minBytes <= 0) continue
            val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(mask).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(max(minBytes, BLOCK * 2 * 4 * 4)).build()
            if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); continue }; if (preferred != null) track.setPreferredDevice(preferred); return track to track.sampleRate
        } catch (t: Throwable) { last = t }
        throw IllegalStateException("Could not open stereo AudioTrack.", last)
    }
    private fun writeFully(track: AudioTrack, data: FloatArray, count: Int) { var off = 0; while (off < count) { val n = track.write(data, off, count - off, AudioTrack.WRITE_BLOCKING); if (n <= 0) error("AudioTrack write failed: $n"); off += n } }

    companion object {
        private const val BLOCK = 1024
        private fun decimate(input: FloatArray, count: Int, target: Int): FloatArray { if (count <= target) return input.copyOf(count); val out = FloatArray(target); val step = count.toDouble() / target; for (i in out.indices) out[i] = input[(i * step).toInt().coerceAtMost(count - 1)]; return out }
    }
}

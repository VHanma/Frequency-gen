package com.vaan.ultracarrier.collective

import android.content.Context
import android.media.AudioDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal data class StackRenderLayer(
    val label: String,
    val family: OmegaFamily,
    val config: CollectiveConfig,
    val resonanceMode: ResonanceMode? = null,
    val matrixMode: MatrixMode? = null
)

internal class LayerStackPlaybackManager(context: Context) {
    private val app = context.applicationContext
    private val stoppers = CopyOnWriteArrayList<() -> Unit>()

    fun stop() {
        stoppers.forEach { runCatching { it() } }
        stoppers.clear()
    }

    suspend fun playOnce(
        source: CollectiveSource,
        layers: List<StackRenderLayer>,
        preferredDevice: AudioDeviceInfo?,
        onStarted: (CollectiveReport) -> Unit,
        onScope: (FloatArray, Int) -> Unit
    ) = coroutineScope {
        require(layers.isNotEmpty()) { "Layer stack is empty." }
        stop()
        val firstStarted = AtomicBoolean(false)
        val latest = HashMap<Int, FloatArray>()
        val rates = HashMap<Int, Int>()
        val lock = Any()

        fun scopeFor(index: Int): (FloatArray, Int) -> Unit = { wave, rate ->
            synchronized(lock) {
                latest[index] = wave
                rates[index] = rate
                val present = latest.values.filter { it.isNotEmpty() }
                if (present.isNotEmpty()) {
                    val n = present.minOf { it.size }.coerceAtMost(512)
                    val mix = FloatArray(n)
                    present.forEach { w -> for (i in 0 until n) mix[i] += w[i] / present.size }
                    onScope(mix, rates.values.maxOrNull() ?: rate)
                }
            }
        }

        val jobs = layers.mapIndexed { index, layer ->
            async(Dispatchers.IO) {
                val decoder = CollectiveStreamDecoder(app.contentResolver)
                val started: (CollectiveReport) -> Unit = { report ->
                    if (firstStarted.compareAndSet(false, true)) {
                        onStarted(report.copy(modeLabel = "Layer Stack ×${layers.size}"))
                    }
                }
                when (layer.family) {
                    OmegaFamily.WORLD_BEAM, OmegaFamily.PERCEPTION_LAB -> {
                        val engine = CollectiveAudioEngine(app.contentResolver)
                        stoppers += engine::stop
                        engine.play(source, decoder, layer.config, preferredDevice, started, scopeFor(index))
                    }
                    OmegaFamily.LAB_X, OmegaFamily.THOUGHTBEAM -> {
                        val engine = OriginalStreamingAudioEngine(app.contentResolver)
                        stoppers += engine::stop
                        engine.play(source, decoder, layer.config, preferredDevice, started, scopeFor(index))
                    }
                    OmegaFamily.SCALAR_LAB -> {
                        val engine = ScalarStreamingAudioEngine(app.contentResolver)
                        stoppers += engine::stop
                        engine.play(source, decoder, layer.config, preferredDevice, started, scopeFor(index))
                    }
                    OmegaFamily.RESONANCE_LAB -> {
                        val engine = ResonanceStreamingAudioEngine(app.contentResolver)
                        stoppers += engine::stop
                        engine.play(source, decoder, layer.config, layer.resonanceMode ?: ResonanceMode.DNA_SONIFICATION, preferredDevice, started, scopeFor(index))
                    }
                    OmegaFamily.MATRIX_LAB -> {
                        val engine = MatrixStreamingAudioEngine(app.contentResolver)
                        stoppers += engine::stop
                        engine.play(source, decoder, layer.config, layer.matrixMode ?: MatrixMode.VACUUM_POLARIZATION, preferredDevice, started, scopeFor(index))
                    }
                }
            }
        }
        try { jobs.awaitAll() } finally { stop() }
    }
}

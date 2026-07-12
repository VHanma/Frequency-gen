package com.vhanma.lightcode

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

internal data class JarProfile(
    val strongestHz: Int,
    val peaks: List<Pair<Int, Float>>,
    val report: String
)

internal class JarAnalyzer(
    private val sweepSeconds: Double = 24.0,
    private val startHz: Double = 35.0,
    private val endHz: Double = 4_500.0,
    private val onStatus: (String) -> Unit,
    private val onComplete: (JarProfile) -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "LightCodeJarAnalyzer") {
            runCatching { recordAndAnalyze() }
                .onFailure { error ->
                    running = false
                    onError(error.message ?: "Jar analysis failed.")
                }
        }
    }

    fun stop() {
        running = false
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
    }

    private fun recordAndAnalyze() {
        val sampleRate = 16_000
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4_096)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minimum * 4
        )
        require(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Microphone recorder could not initialize." }
        recorder = audioRecord

        val targetSamples = ((sweepSeconds + 0.4) * sampleRate).toInt()
        val captured = ShortArray(targetSamples)
        val buffer = ShortArray(minimum / 2)
        var offset = 0

        onStatus("Listening to the jar while the light sweep runs…")
        audioRecord.startRecording()
        try {
            while (running && offset < captured.size) {
                val count = audioRecord.read(buffer, 0, minOf(buffer.size, captured.size - offset))
                if (count < 0) error("Microphone read failed: $count")
                if (count == 0) continue
                buffer.copyInto(captured, offset, 0, count)
                offset += count
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
            recorder = null
        }

        if (!running && offset < sampleRate) return
        running = false
        val profile = analyze(captured.copyOf(offset), sampleRate)
        onComplete(profile)
    }

    private fun analyze(samples: ShortArray, sampleRate: Int): JarProfile {
        val windowSamples = (sampleRate * 0.080).toInt()
        val hopSamples = (sampleRate * 0.040).toInt()
        val values = mutableListOf<Pair<Double, Double>>()
        var start = (sampleRate * 0.20).toInt()

        while (start + windowSamples <= samples.size) {
            val time = start.toDouble() / sampleRate.toDouble()
            if (time >= sweepSeconds - 0.20) break
            var sumSquares = 0.0
            var mean = 0.0
            for (i in start until start + windowSamples) mean += samples[i]
            mean /= windowSamples.toDouble()
            for (i in start until start + windowSamples) {
                val centered = samples[i] - mean
                sumSquares += centered * centered
            }
            val rms = sqrt(sumSquares / windowSamples.toDouble())
            val sweepTime = (time - 0.10).coerceIn(0.0, sweepSeconds)
            val frequency = startHz * exp(ln(endHz / startHz) * sweepTime / sweepSeconds)
            values += frequency to rms
            start += hopSamples
        }

        require(values.isNotEmpty()) { "The microphone recording was too short to analyze." }
        val sortedLevels = values.map { it.second }.sorted()
        val floor = sortedLevels[sortedLevels.size / 5].coerceAtLeast(1.0)
        val candidates = values
            .map { it.first to (it.second / floor).toFloat() }
            .sortedByDescending { it.second }

        val peaks = mutableListOf<Pair<Int, Float>>()
        for ((frequency, relative) in candidates) {
            val hz = frequency.toInt()
            val separated = peaks.all { existing ->
                val ratio = maxOf(hz, existing.first).toDouble() / minOf(hz, existing.first).coerceAtLeast(1).toDouble()
                ratio > 1.13
            }
            if (separated) peaks += hz to relative
            if (peaks.size == 6) break
        }

        val strongest = peaks.firstOrNull()?.first ?: values.maxBy { it.second }.first.toInt()
        val report = buildString {
            append("Strongest measured region: ").append(strongest).append(" Hz\n\n")
            append("Candidate jar peaks:\n")
            peaks.forEachIndexed { index, peak ->
                append(index + 1)
                    .append(". ")
                    .append(peak.first)
                    .append(" Hz   relative response ")
                    .append("%.2f".format(peak.second))
                    .append("×\n")
            }
            append("\nTest each peak with Tone mode. The result is a microphone measurement of this jar, phone position, color and room arrangement.")
        }
        return JarProfile(strongest, peaks, report)
    }
}

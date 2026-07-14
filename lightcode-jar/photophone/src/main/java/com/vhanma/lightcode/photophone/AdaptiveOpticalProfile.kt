package com.vhanma.lightcode.photophone

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class CalibrationBand(
    val centerHz: Int,
    val startSeconds: Double,
    val toneSeconds: Double
)

internal data class CalibrationPlan(
    val program: OpticalProgram,
    val bands: List<CalibrationBand>,
    val firstSyncSeconds: Double,
    val syncSpacingSeconds: Double,
    val syncCount: Int,
    val syncHz: Int
)

internal data class OpticalProfile(
    val centersHz: IntArray,
    val responseDbfs: FloatArray,
    val snrDb: FloatArray,
    val inverseEqDb: FloatArray,
    val noiseDbfs: Float,
    val usableLowHz: Int,
    val usableHighHz: Int,
    val powerScore: Int,
    val speechScore: Int,
    val musicScore: Int,
    val grade: String,
    val report: String
)

internal object CalibrationSignalFactory {
    private val centers = intArrayOf(
        100, 160, 250, 400, 630, 1_000,
        1_600, 2_500, 4_000, 6_300, 8_000, 10_000, 12_500
    )

    fun create(sampleRate: Int = 24_000): CalibrationPlan {
        val firstSync = 0.65
        val syncTone = 0.10
        val syncGap = 0.10
        val syncSpacing = syncTone + syncGap
        val syncCount = 3
        val postSyncSilence = 0.55
        val toneSeconds = 0.48
        val gapSeconds = 0.14

        var cursor = firstSync + syncCount * syncSpacing + postSyncSilence
        val bands = centers.map { center ->
            CalibrationBand(center, cursor, toneSeconds).also {
                cursor += toneSeconds + gapSeconds
            }
        }
        val totalSeconds = cursor + 0.45
        val output = FloatArray((totalSeconds * sampleRate).toInt().coerceAtLeast(1))

        repeat(syncCount) { index ->
            addTone(
                output,
                sampleRate,
                startSeconds = firstSync + index * syncSpacing,
                durationSeconds = syncTone,
                frequencyHz = 1_000.0,
                amplitude = 0.96
            )
        }
        bands.forEach { band ->
            addTone(
                output,
                sampleRate,
                band.startSeconds,
                band.toneSeconds,
                band.centerHz.toDouble(),
                0.94
            )
        }

        return CalibrationPlan(
            program = OpticalProgram(output, sampleRate, "Rig-grade multiband calibration"),
            bands = bands,
            firstSyncSeconds = firstSync,
            syncSpacingSeconds = syncSpacing,
            syncCount = syncCount,
            syncHz = 1_000
        )
    }

    private fun addTone(
        target: FloatArray,
        sampleRate: Int,
        startSeconds: Double,
        durationSeconds: Double,
        frequencyHz: Double,
        amplitude: Double
    ) {
        val start = (startSeconds * sampleRate).toInt().coerceAtLeast(0)
        val count = (durationSeconds * sampleRate).toInt().coerceAtLeast(1)
        val fadeSamples = (sampleRate * 0.012).toInt().coerceAtLeast(1)
        for (localIndex in 0 until count) {
            val outputIndex = start + localIndex
            if (outputIndex !in target.indices) break
            val fade = when {
                localIndex < fadeSamples -> localIndex.toDouble() / fadeSamples.toDouble()
                localIndex >= count - fadeSamples -> (count - 1 - localIndex).coerceAtLeast(0).toDouble() / fadeSamples.toDouble()
                else -> 1.0
            }
            target[outputIndex] = (
                amplitude * fade * sin(2.0 * PI * frequencyHz * localIndex.toDouble() / sampleRate.toDouble())
                ).toFloat()
        }
    }
}

internal class RigCalibrationRecorder(
    private val plan: CalibrationPlan,
    private val onStatus: (String) -> Unit,
    private val onComplete: (OpticalProfile) -> Unit,
    private val onError: (String) -> Unit
) {
    private val recordRate = 48_000
    @Volatile private var running = false
    private var recorder: AudioRecord? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "PhotophoneRigCalibration") {
            runCatching { captureAndAnalyze() }
                .onFailure { error ->
                    running = false
                    onError(error.message ?: "Rig calibration failed.")
                }
        }
    }

    fun stopAndAnalyze() {
        running = false
        runCatching { recorder?.stop() }
    }

    fun cancel() {
        running = false
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
    }

    private fun captureAndAnalyze() {
        val minimumBytes = AudioRecord.getMinBufferSize(
            recordRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8_192)
        var audioRecord = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            recordRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minimumBytes * 4
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                recordRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minimumBytes * 4
            )
        }
        require(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "The microphone could not initialize at 48 kHz."
        }

        recorder = audioRecord
        val builder = ShortBuilder()
        val buffer = ShortArray(minimumBytes / 2)
        onStatus("Listening to the jar while the multiband light test runs…")
        audioRecord.startRecording()
        try {
            while (running) {
                val count = audioRecord.read(buffer, 0, buffer.size)
                if (count < 0) error("Microphone read failed: $count")
                if (count > 0) builder.add(buffer, count)
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
            recorder = null
        }

        val captured = builder.toArray()
        require(captured.size >= recordRate * 3) {
            "The calibration recording was too short."
        }
        onStatus("Building the jar transfer profile and inverse EQ…")
        onComplete(analyze(captured))
    }

    private fun analyze(samples: ShortArray): OpticalProfile {
        val programStart = detectProgramStart(samples)
        val noiseStart = (programStart + 0.05 * recordRate).toInt().coerceAtLeast(0)
        val noiseLength = (0.35 * recordRate).toInt()
        val noiseEnd = (noiseStart + noiseLength).coerceAtMost(samples.size)
        val noiseRms = rms(samples, noiseStart, noiseEnd).coerceAtLeast(1e-8)
        val noiseDb = dbfs(noiseRms).toFloat()

        val response = FloatArray(plan.bands.size)
        val snr = FloatArray(plan.bands.size)
        for ((index, band) in plan.bands.withIndex()) {
            val centerTime = programStart / recordRate.toDouble() + band.startSeconds + band.toneSeconds * 0.5
            val windowSeconds = minOf(0.30, band.toneSeconds * 0.72)
            val start = ((centerTime - windowSeconds * 0.5) * recordRate).toInt().coerceAtLeast(0)
            val end = (start + windowSeconds * recordRate).toInt().coerceAtMost(samples.size)
            val amplitude = goertzelAmplitude(samples, start, end, band.centerHz.toDouble(), recordRate)
                .coerceAtLeast(1e-9)
            response[index] = dbfs(amplitude).toFloat()
            val localNoise = goertzelAmplitude(
                samples,
                noiseStart,
                noiseEnd,
                band.centerHz.toDouble(),
                recordRate
            ).coerceAtLeast(1e-9)
            snr[index] = (20.0 * log10(amplitude / localNoise)).toFloat().coerceIn(-20f, 80f)
        }

        val usable = response.indices.filter { snr[it] >= 6f && response[it] > -72f }
        val usableLow = usable.firstOrNull()?.let { plan.bands[it].centerHz } ?: 0
        val usableHigh = usable.lastOrNull()?.let { plan.bands[it].centerHz } ?: 0

        val strongResponses = usable.map { response[it] }.sorted()
        val targetDb = if (strongResponses.isNotEmpty()) {
            strongResponses[(strongResponses.size * 3 / 4).coerceIn(0, strongResponses.lastIndex)]
        } else {
            response.maxOrNull() ?: -80f
        }

        val inverseEq = FloatArray(response.size) { index ->
            if (snr[index] < 3f) {
                -12f
            } else {
                (targetDb - response[index]).coerceIn(-12f, 15f)
            }
        }
        smoothGains(inverseEq)

        val bestSnr = snr.maxOrNull() ?: 0f
        val powerScore = (((bestSnr - 3f) / 27f) * 100f).toInt().coerceIn(0, 100)
        val speechIndices = plan.bands.indices.filter {
            plan.bands[it].centerHz in 250..4_000
        }
        val musicIndices = plan.bands.indices.filter {
            plan.bands[it].centerHz in 100..10_000
        }
        val speechScore = readinessScore(speechIndices, snr)
        val musicScore = readinessScore(musicIndices, snr)
        val overall = (powerScore * 0.30 + speechScore * 0.35 + musicScore * 0.35).toInt()
        val grade = when {
            overall >= 85 -> "A"
            overall >= 70 -> "B"
            overall >= 55 -> "C"
            overall >= 40 -> "D"
            else -> "F"
        }

        val centers = plan.bands.map { it.centerHz }.toIntArray()
        val report = buildString {
            append("RIG GRADE: ").append(grade).append("\n\n")
            append("Optical/acoustic power: ").append(powerScore).append("/100\n")
            append("Speech readiness: ").append(speechScore).append("/100\n")
            append("Music readiness: ").append(musicScore).append("/100\n")
            append("Noise floor: ").append("%.1f".format(noiseDb)).append(" dBFS\n")
            append("Usable measured band: ")
            if (usableLow == 0) append("none above the current threshold")
            else append(usableLow).append("–").append(usableHigh).append(" Hz")
            append("\n\n")
            centers.indices.forEach { index ->
                append(centers[index]).append(" Hz  ")
                    .append("response ").append("%.1f".format(response[index])).append(" dBFS  ")
                    .append("SNR ").append("%.1f".format(snr[index])).append(" dB  ")
                    .append("EQ ").append(if (inverseEq[index] >= 0) "+" else "")
                    .append("%.1f".format(inverseEq[index])).append(" dB\n")
            }
            append("\n")
            when {
                powerScore < 35 -> append("Main bottleneck: too little modulated optical energy reaches a fast absorber. Use the external LED path and a low-mass carbon absorber insert.")
                speechScore < 50 -> append("Main bottleneck: the rig does not pass enough of the 250–4000 Hz speech band. The saved EQ will redistribute energy, but absorber and cavity changes are still needed.")
                musicScore < 60 -> append("Speech may become recognizable, but the measured bandwidth is still too narrow for a full song. A broadband porous absorber is the next upgrade.")
                else -> append("The rig has enough measured bandwidth to attempt calibrated speech and music. Use PDM plus the saved inverse EQ.")
            }
        }

        return OpticalProfile(
            centersHz = centers,
            responseDbfs = response,
            snrDb = snr,
            inverseEqDb = inverseEq,
            noiseDbfs = noiseDb,
            usableLowHz = usableLow,
            usableHighHz = usableHigh,
            powerScore = powerScore,
            speechScore = speechScore,
            musicScore = musicScore,
            grade = grade,
            report = report
        )
    }

    private fun detectProgramStart(samples: ShortArray): Int {
        val scanLimit = minOf(samples.size - recordRate, recordRate * 4)
        val window = (0.075 * recordRate).toInt()
        val step = (0.010 * recordRate).toInt().coerceAtLeast(1)
        var bestScore = Double.NEGATIVE_INFINITY
        var bestCandidate = 0
        var candidate = 0
        while (candidate < scanLimit) {
            var score = 0.0
            repeat(plan.syncCount) { pulseIndex ->
                val pulseStart = candidate + (
                    (plan.firstSyncSeconds + pulseIndex * plan.syncSpacingSeconds) * recordRate
                    ).toInt()
                val pulseEnd = (pulseStart + window).coerceAtMost(samples.size)
                if (pulseEnd > pulseStart) {
                    score += goertzelAmplitude(
                        samples,
                        pulseStart,
                        pulseEnd,
                        plan.syncHz.toDouble(),
                        recordRate
                    )
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestCandidate = candidate
            }
            candidate += step
        }
        return bestCandidate
    }

    private fun readinessScore(indices: List<Int>, snr: FloatArray): Int {
        if (indices.isEmpty()) return 0
        var sum = 0.0
        var covered = 0
        for (index in indices) {
            val value = snr[index]
            if (value >= 6f) covered++
            sum += ((value - 3f) / 22f).coerceIn(0f, 1f)
        }
        val averageQuality = sum / indices.size.toDouble()
        val coverage = covered.toDouble() / indices.size.toDouble()
        return ((averageQuality * 0.58 + coverage * 0.42) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun smoothGains(gains: FloatArray) {
        if (gains.size < 3) return
        val original = gains.copyOf()
        for (index in 1 until gains.lastIndex) {
            gains[index] = (
                original[index - 1] * 0.22f +
                    original[index] * 0.56f +
                    original[index + 1] * 0.22f
                ).coerceIn(-12f, 15f)
        }
    }

    private fun rms(samples: ShortArray, start: Int, end: Int): Double {
        if (end <= start) return 0.0
        var sum = 0.0
        for (index in start until end) {
            val value = samples[index].toDouble() / 32768.0
            sum += value * value
        }
        return sqrt(sum / (end - start).toDouble())
    }

    private fun goertzelAmplitude(
        samples: ShortArray,
        start: Int,
        end: Int,
        frequencyHz: Double,
        sampleRate: Int
    ): Double {
        if (end - start < 8 || frequencyHz <= 0.0 || frequencyHz >= sampleRate * 0.49) return 0.0
        val omega = 2.0 * PI * frequencyHz / sampleRate.toDouble()
        val coefficient = 2.0 * cos(omega)
        var q0: Double
        var q1 = 0.0
        var q2 = 0.0
        val count = end - start
        for (localIndex in 0 until count) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * localIndex.toDouble() / (count - 1).coerceAtLeast(1).toDouble())
            val value = samples[start + localIndex].toDouble() / 32768.0 * window
            q0 = coefficient * q1 - q2 + value
            q2 = q1
            q1 = q0
        }
        val power = q1 * q1 + q2 * q2 - coefficient * q1 * q2
        return 2.0 * sqrt(power.coerceAtLeast(0.0)) / count.toDouble()
    }

    private fun dbfs(amplitude: Double): Double = 20.0 * log10(amplitude.coerceAtLeast(1e-9))

    private class ShortBuilder(initialCapacity: Int = 524_288) {
        private var values = ShortArray(initialCapacity)
        private var size = 0
        private val maximum = 48_000 * 60

        fun add(source: ShortArray, count: Int) {
            if (size >= maximum) return
            val accepted = minOf(count, maximum - size)
            ensure(size + accepted)
            source.copyInto(values, size, 0, accepted)
            size += accepted
        }

        private fun ensure(required: Int) {
            if (required <= values.size) return
            var next = values.size * 2
            while (next < required) next *= 2
            values = values.copyOf(next.coerceAtMost(maximum))
        }

        fun toArray(): ShortArray = values.copyOf(size)
    }
}

internal object AdaptiveOpticalEqualizer {
    fun apply(program: OpticalProgram, profile: OpticalProfile): OpticalProgram {
        if (program.samples.isEmpty()) return program
        var working = SignalCore.removeDc(program.samples)
        for (index in profile.centersHz.indices) {
            val center = profile.centersHz[index].toDouble()
            if (center >= program.sampleRate * 0.44) continue
            val gain = profile.inverseEqDb[index].toDouble().coerceIn(-12.0, 12.0)
            if (abs(gain) < 0.35) continue
            working = peakingEq(
                working,
                program.sampleRate.toDouble(),
                center,
                q = 1.15,
                gainDb = gain
            )
        }
        working = opticalLimiter(working)
        return program.copy(
            samples = SignalCore.normalize(working, 0.95f),
            label = "${program.label} · measured inverse EQ"
        )
    }

    private fun peakingEq(
        input: FloatArray,
        sampleRate: Double,
        centerHz: Double,
        q: Double,
        gainDb: Double
    ): FloatArray {
        val a = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * centerHz / sampleRate
        val alpha = sin(omega) / (2.0 * q)
        val cosOmega = cos(omega)

        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosOmega
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosOmega
        val a2 = 1.0 - alpha / a

        val nb0 = b0 / a0
        val nb1 = b1 / a0
        val nb2 = b2 / a0
        val na1 = a1 / a0
        val na2 = a2 / a0

        val output = FloatArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (index in input.indices) {
            val x0 = input[index].toDouble()
            val y0 = nb0 * x0 + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
            output[index] = y0.toFloat().coerceIn(-8f, 8f)
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return output
    }

    private fun opticalLimiter(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        var envelope = 0f
        for (index in input.indices) {
            val value = input[index]
            envelope = if (abs(value) > envelope) {
                envelope * 0.75f + abs(value) * 0.25f
            } else {
                envelope * 0.997f + abs(value) * 0.003f
            }
            val gain = if (envelope > 0.92f) 0.92f / envelope else 1f
            output[index] = (kotlin.math.tanh((value * gain * 1.45f).toDouble()) / kotlin.math.tanh(1.45)).toFloat()
        }
        return output
    }
}

internal object OpticalProfileStore {
    private const val PREFS = "photophone_v2_profile"

    fun save(context: Context, profile: OpticalProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("centers", profile.centersHz.joinToString(","))
            .putString("response", profile.responseDbfs.joinToString(","))
            .putString("snr", profile.snrDb.joinToString(","))
            .putString("eq", profile.inverseEqDb.joinToString(","))
            .putFloat("noise", profile.noiseDbfs)
            .putInt("low", profile.usableLowHz)
            .putInt("high", profile.usableHighHz)
            .putInt("power", profile.powerScore)
            .putInt("speech", profile.speechScore)
            .putInt("music", profile.musicScore)
            .putString("grade", profile.grade)
            .putString("report", profile.report)
            .apply()
    }

    fun load(context: Context): OpticalProfile? {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val centers = preferences.getString("centers", null)?.split(",")
            ?.mapNotNull { it.toIntOrNull() }?.toIntArray() ?: return null
        val response = parseFloats(preferences.getString("response", null)) ?: return null
        val snr = parseFloats(preferences.getString("snr", null)) ?: return null
        val eq = parseFloats(preferences.getString("eq", null)) ?: return null
        if (centers.size != response.size || centers.size != snr.size || centers.size != eq.size) return null
        return OpticalProfile(
            centersHz = centers,
            responseDbfs = response,
            snrDb = snr,
            inverseEqDb = eq,
            noiseDbfs = preferences.getFloat("noise", -90f),
            usableLowHz = preferences.getInt("low", 0),
            usableHighHz = preferences.getInt("high", 0),
            powerScore = preferences.getInt("power", 0),
            speechScore = preferences.getInt("speech", 0),
            musicScore = preferences.getInt("music", 0),
            grade = preferences.getString("grade", "F") ?: "F",
            report = preferences.getString("report", "No report saved.") ?: "No report saved."
        )
    }

    private fun parseFloats(value: String?): FloatArray? = value
        ?.split(",")
        ?.mapNotNull { it.toFloatOrNull() }
        ?.toFloatArray()
}

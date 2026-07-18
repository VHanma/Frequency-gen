package com.vhanma.lightcode.photophone

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

internal data class ProofResult(
    val savedLocation: String,
    val correlationPercent: Int,
    val recordedRmsDbfs: Double,
    val secondsRecorded: Double,
    val report: String
)

internal class ProofRecorder(
    private val context: Context,
    private val sourceProgram: OpticalSignal,
    private val onStatus: (String) -> Unit,
    private val onComplete: (ProofResult) -> Unit,
    private val onError: (String) -> Unit
) {
    private val sampleRate = 16_000
    @Volatile private var running = false
    private var recorder: AudioRecord? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "PhotophoneProofRecorder") {
            runCatching { capture() }
                .onFailure { error ->
                    running = false
                    onError(error.message ?: "Proof recording failed.")
                }
        }
    }

    fun stop() {
        running = false
        runCatching { recorder?.stop() }
    }

    private fun capture() {
        val minimumBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4_096)

        var audioRecord = buildRecorder(MediaRecorder.AudioSource.UNPROCESSED, minimumBytes)
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            audioRecord = buildRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, minimumBytes)
        }
        require(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "The phone microphone could not initialize for Proof Mode."
        }

        recorder = audioRecord
        val builder = ShortBuilder()
        val buffer = ShortArray(minimumBytes / 2)
        onStatus("Proof Mode is recording the receiver. The app has no phone-speaker playback path.")
        audioRecord.startRecording()

        try {
            while (running) {
                val count = audioRecord.read(buffer, 0, buffer.size)
                if (count < 0) {
                    if (!running) break
                    error("Microphone read failed: $count")
                }
                if (count == 0) continue
                builder.add(buffer, count)
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
            recorder = null
        }

        val captured = builder.toArray()
        if (captured.size < sampleRate / 2) {
            onError("Proof recording was too short to analyze.")
            return
        }

        val wav = encodeWav(captured, sampleRate)
        val location = saveWav(wav)
        val correlation = envelopeCorrelation(sourceProgram, captured, sampleRate)
        val rms = rmsDbfs(captured)
        val seconds = captured.size.toDouble() / sampleRate.toDouble()
        val result = ProofResult(
            savedLocation = location,
            correlationPercent = (correlation * 100.0).toInt().coerceIn(0, 100),
            recordedRmsDbfs = rms,
            secondsRecorded = seconds,
            report = buildString {
                append("Proof WAV saved to:\n")
                append(location)
                append("\n\nRecorded duration: ")
                append("%.1f".format(seconds))
                append(" seconds\n")
                append("Recorded level: ")
                append("%.1f".format(rms))
                append(" dBFS\n")
                append("Best source-envelope match: ")
                append((correlation * 100.0).toInt().coerceIn(0, 100))
                append("%\n\n")
                append("The transmitter used light output and did not create an Android AudioTrack for the payload.")
            }
        )
        onComplete(result)
    }

    private fun buildRecorder(source: Int, minimumBytes: Int): AudioRecord = AudioRecord(
        source,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minimumBytes * 4
    )

    private fun envelopeCorrelation(
        source: OpticalSignal,
        captured: ShortArray,
        capturedRate: Int
    ): Double {
        val blockSeconds = 0.020
        val capturedBlock = (capturedRate * blockSeconds).toInt().coerceAtLeast(1)
        val sourceEnvelope = mutableListOf<Double>()
        val capturedEnvelope = mutableListOf<Double>()
        val capturedSeconds = captured.size.toDouble() / capturedRate.toDouble()
        val sourceSeconds = if (source.loop) capturedSeconds else minOf(source.durationSeconds, capturedSeconds)
        val sourceBlocks = (sourceSeconds / blockSeconds).toInt().coerceAtLeast(0)
        val tapsPerBlock = 12

        repeat(sourceBlocks) { blockIndex ->
            val blockStart = blockIndex * blockSeconds
            var sum = 0.0
            repeat(tapsPerBlock) { tap ->
                val time = blockStart + blockSeconds * (tap + 0.5) / tapsPerBlock.toDouble()
                val value = source.sampleAt(time).toDouble()
                sum += value * value
            }
            sourceEnvelope += sqrt(sum / tapsPerBlock.toDouble())
        }

        var index = 0
        while (index + capturedBlock <= captured.size) {
            var sum = 0.0
            for (i in index until index + capturedBlock) {
                val value = captured[i].toDouble() / 32768.0
                sum += value * value
            }
            capturedEnvelope += sqrt(sum / capturedBlock.toDouble())
            index += capturedBlock
        }

        if (sourceEnvelope.size < 20 || capturedEnvelope.size < 20) return 0.0
        val maximumLag = minOf(150, capturedEnvelope.size / 3)
        var best = 0.0
        for (lag in 0..maximumLag) {
            val count = minOf(sourceEnvelope.size, capturedEnvelope.size - lag)
            if (count < 20) continue
            var sourceMean = 0.0
            var captureMean = 0.0
            for (i in 0 until count) {
                sourceMean += sourceEnvelope[i]
                captureMean += capturedEnvelope[i + lag]
            }
            sourceMean /= count.toDouble()
            captureMean /= count.toDouble()

            var numerator = 0.0
            var sourceEnergy = 0.0
            var captureEnergy = 0.0
            for (i in 0 until count) {
                val a = sourceEnvelope[i] - sourceMean
                val b = capturedEnvelope[i + lag] - captureMean
                numerator += a * b
                sourceEnergy += a * a
                captureEnergy += b * b
            }
            val denominator = sqrt(sourceEnergy * captureEnergy)
            if (denominator > 1e-12) best = maxOf(best, numerator / denominator)
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun rmsDbfs(samples: ShortArray): Double {
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble() / 32768.0
            sum += value * value
        }
        val rms = sqrt(sum / samples.size.toDouble()).coerceAtLeast(1e-9)
        return 20.0 * log10(rms)
    }

    private fun encodeWav(samples: ShortArray, rate: Int): ByteArray {
        val dataSize = samples.size * 2
        val output = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(rate)
        header.putInt(rate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        output.write(header.array())
        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) pcm.putShort(sample)
        output.write(pcm.array())
        return output.toByteArray()
    }

    private fun saveWav(bytes: ByteArray): String {
        val fileName = "Photophone_Proof_${System.currentTimeMillis()}.wav"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LightCode-Photophone")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Android could not create the proof WAV in Downloads.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Android could not write the proof WAV.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            "Download/LightCode-Photophone/$fileName"
        } else {
            val folder = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LightCode-Photophone"
            )
            folder.mkdirs()
            val file = File(folder, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        }
    }

    private class ShortBuilder(initialCapacity: Int = 262_144) {
        private var values = ShortArray(initialCapacity)
        private var size = 0
        private val maximumSamples = 16_000 * 12 * 60

        fun add(source: ShortArray, count: Int) {
            if (size >= maximumSamples) return
            val accepted = minOf(count, maximumSamples - size)
            ensure(size + accepted)
            source.copyInto(values, size, 0, accepted)
            size += accepted
        }

        private fun ensure(required: Int) {
            if (required <= values.size) return
            var next = values.size * 2
            while (next < required) next *= 2
            values = values.copyOf(next.coerceAtMost(maximumSamples))
        }

        fun toArray(): ShortArray = values.copyOf(size)
    }
}

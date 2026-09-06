package com.vaan.voiceforgex

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class VoiceRecorder(private val context: Context) {
    private var record: AudioRecord? = null
    private var job: Job? = null
    @Volatile private var running = false
    private val sampleRate = 16000
    val isRecording get() = running

    fun start(onDone: (Result<File>) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onDone(Result.failure(SecurityException("Microphone permission required"))); return
        }
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192))
        record = r
        val raw = File(context.cacheDir, "clone_${System.currentTimeMillis()}.pcm")
        val wav = File(context.cacheDir, raw.nameWithoutExtension + ".wav")
        running = true
        r.startRecording()
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = runCatching {
                FileOutputStream(raw).use { out ->
                    val buf = ByteArray(8192)
                    while (running) {
                        val n = r.read(buf, 0, buf.size)
                        if (n > 0) out.write(buf, 0, n) else if (!running) break
                    }
                }
                WavUtils.writePcm16Wav(raw, wav, sampleRate)
                raw.delete()
                wav
            }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { record?.stop() }
        record?.release(); record = null
        job = null
    }
}

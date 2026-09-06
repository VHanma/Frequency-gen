package com.vaan.contactomega

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

class AudioItcEngine(
    private val activity: Activity,
    private val store: SessionStore,
    private val onState: (String) -> Unit,
    private val onTranscript: (String, Boolean, Double?, Boolean) -> Unit,
    private val onMeter: (Double, FloatArray) -> Unit,
    private val sourceActive: () -> Boolean,
    private val onEntropyTrial: (Int, Double) -> Unit = { _, _ -> }
) {
    private val active = AtomicBoolean(false)
    private var thread: Thread? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var rawWriter: WavWriter? = null
    private var cleanWriter: WavWriter? = null
    private val ring = ShortArray(48000 * 8)
    private var ringPos = 0
    private var ringCount = 0
    private var lastCandidateMs = 0L
    private val entropyBits = ArrayList<Int>(256)
    private var hpPrevIn = 0.0
    private var hpPrevOut = 0.0
    var modelReady = false
        private set
    @Volatile var monitorEchoEnabled = false
    @Volatile var monitorEchoDelayMs = 650
    @Volatile var monitorEchoGain = 0.35f

    fun loadModelAsync() {
        if (modelReady) return
        Thread {
            try {
                onState("Loading offline EVP subtitle model…")
                val target = File(activity.filesDir, "vosk-model-small-en-us-0.15")
                if (!target.exists() || target.listFiles().isNullOrEmpty()) copyAssetFolder("vosk-model-small-en-us-0.15", target)
                model = Model(target.absolutePath)
                modelReady = true
                onState("Offline EVP subtitles ready")
            } catch (t: Throwable) {
                onState("Subtitle model unavailable: ${t.message ?: "unknown"}")
            }
        }.start()
    }

    private fun copyAssetFolder(assetPath: String, dest: File) {
        val children = activity.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            activity.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            children.forEach { copyAssetFolder("$assetPath/$it", File(dest, it)) }
        }
    }

    fun start(label: String = "EVP_LISTEN") {
        if (!active.compareAndSet(false, true)) return
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            active.set(false); onState("Microphone permission required"); return
        }
        val folder = store.ensure(label)
        store.event("AUDIO_START", mapOf("sampleRate" to 48000, "source" to "UNPROCESSED/MIC fallback"))
        rawWriter = WavWriter(File(folder, "raw.wav"), 48000)
        cleanWriter = WavWriter(File(folder, "processed.wav"), 48000)
        recognizer = if (modelReady) try { Recognizer(model, 16000f).apply { setWords(true) } } catch (_: Throwable) { null } else null
        thread = Thread { captureLoop() }.also { it.start() }
    }

    fun stop() {
        if (!active.get()) return
        active.set(false)
        try { thread?.join(800) } catch (_: Throwable) {}
        thread = null
        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
        try { rawWriter?.close() } catch (_: Throwable) {}
        try { cleanWriter?.close() } catch (_: Throwable) {}
        rawWriter = null; cleanWriter = null
        store.event("AUDIO_STOP")
    }

    fun isRunning() = active.get()

    private fun captureLoop() {
        val sr = 48000
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = max(min, 8192)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
        } catch (_: Throwable) {
            AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
        }
        val echoTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(size * 4).build().also { it.play() }
        } catch (_: Throwable) { null }
        val echoRing = ShortArray(sr * 10)
        var echoPos = 0
        val buf = ShortArray(size)
        var lastUi = 0L
        try {
            rec.startRecording()
            onState("Recording RAW WAV + processed WAV + live EVP subtitles")
            while (active.get()) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                rawWriter?.write(buf, n)
                val processed = ShortArray(n)
                for (pi in 0 until n) {
                    val x = buf[pi].toDouble()
                    val y = 0.985 * (hpPrevOut + x - hpPrevIn)
                    hpPrevIn = x; hpPrevOut = y
                    processed[pi] = (if (kotlin.math.abs(y) < 75.0) 0.0 else y).toInt().coerceIn(-32767,32767).toShort()
                    if (pi % 23 == 0) entropyBits += ((buf[pi].toInt() xor System.nanoTime().toInt()) and 1)
                }
                cleanWriter?.write(processed, n)
                if (entropyBits.size >= 200) {
                    val ones = entropyBits.take(200).sum(); entropyBits.subList(0,200).clear()
                    val z = (ones - 100) / kotlin.math.sqrt(50.0)
                    store.event("ENTROPY_TRIAL", mapOf("ones" to ones, "z" to z, "source" to "mic_lsb+jitter"))
                    onEntropyTrial(ones, z)
                }
                synchronized(ring) {
                    for (i in 0 until n) {
                        ring[ringPos] = buf[i]
                        ringPos++
                        if (ringPos >= ring.size) ringPos = 0
                        if (ringCount < ring.size) ringCount++
                    }
                }
                var sum = 0.0; var crossings = 0
                for (i in 0 until n) {
                    val x = buf[i].toDouble(); sum += x*x
                    if (i > 0 && (buf[i] >= 0) != (buf[i-1] >= 0)) crossings++
                }
                val rms = sqrt(sum / n) / 32768.0
                val now = System.currentTimeMillis()
                if (now - lastUi > 160) {
                    lastUi = now
                    val spec = SignalMath.spectrum32(buf, n)
                    onMeter(rms, spec)
                    val zcr = crossings.toDouble() / n
                    if (rms > 0.055 && zcr in 0.01..0.35 && now - lastCandidateMs > 2500) {
                        lastCandidateMs = now
                        bookmarkCandidate("AUDIO_ENERGY", rms)
                    }
                }
                if (echoTrack != null) {
                    val out = ShortArray(n)
                    val delay = (sr * monitorEchoDelayMs.coerceIn(80, 10000) / 1000).coerceIn(1, echoRing.size - 1)
                    for (ei in 0 until n) {
                        echoRing[echoPos] = buf[ei]
                        var read = echoPos - delay; if (read < 0) read += echoRing.size
                        val dry = echoRing[read].toInt()
                        out[ei] = if (monitorEchoEnabled) (dry * monitorEchoGain).toInt().coerceIn(-32767,32767).toShort() else 0
                        echoPos++; if (echoPos >= echoRing.size) echoPos = 0
                    }
                    if (monitorEchoEnabled) try { echoTrack.write(out, 0, n, AudioTrack.WRITE_BLOCKING) } catch (_: Throwable) {}
                }
                feedRecognizer(buf, n)
            }
        } catch (t: Throwable) {
            onState("Audio engine: ${t.message ?: "stopped"}")
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            rec.release(); try { echoTrack?.stop() } catch (_: Throwable) {}; try { echoTrack?.release() } catch (_: Throwable) {}
        }
    }

    private fun feedRecognizer(buf48: ShortArray, n: Int) {
        val r = recognizer ?: return
        val n16 = n / 3
        if (n16 <= 0) return
        val down = ShortArray(n16)
        var j = 0; var i = 0
        while (j < n16 && i + 2 < n) {
            down[j++] = ((buf48[i].toInt() + buf48[i+1].toInt() + buf48[i+2].toInt()) / 3).toShort(); i += 3
        }
        try {
            if (r.acceptWaveForm(down, down.size)) parseResult(r.result, false) else parseResult(r.partialResult, true)
        } catch (_: Throwable) {}
    }

    private fun parseResult(json: String, partial: Boolean) {
        try {
            val o = JSONObject(json)
            val text = if (partial) o.optString("partial") else o.optString("text")
            if (text.isBlank()) return
            var conf: Double? = null
            if (!partial && o.has("result")) {
                val a = o.getJSONArray("result")
                if (a.length() > 0) {
                    var s = 0.0
                    for (i in 0 until a.length()) s += a.getJSONObject(i).optDouble("conf", 0.0)
                    conf = s / a.length()
                }
            }
            val overlap = sourceActive()
            store.transcript(text, partial, conf, overlap)
            onTranscript(text, partial, conf, overlap)
            if (!partial) bookmarkCandidate("TRANSCRIPT:${text.take(60)}", conf ?: 0.0)
        } catch (_: Throwable) {}
    }

    private fun bookmarkCandidate(reason: String, score: Double) {
        store.event("AUDIO_CANDIDATE", mapOf("reason" to reason, "score" to score, "sourceOverlap" to sourceActive()))
        val folder = store.dir ?: return
        val copy: ShortArray = synchronized(ring) {
            val out = ShortArray(ringCount)
            val start = if (ringCount == ring.size) ringPos else 0
            for (i in 0 until ringCount) out[i] = ring[(start + i) % ring.size]
            out
        }
        if (copy.isEmpty()) return
        val id = store.elapsedMs()
        try {
            val w = WavWriter(File(folder, "candidate-$id.wav"), 48000)
            w.write(copy, copy.size); w.close()
        } catch (_: Throwable) {}
    }
}

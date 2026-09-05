package com.vaan.contactomega

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.*
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private val transmitting = AtomicBoolean(false)
    private var listening by mutableStateOf(false)
    private var flashFrame by mutableStateOf(false)
    private var status by mutableStateOf("IDLE · READY FOR CONTACT")
    private var sensorLine by mutableStateOf("Sensors awaiting session")
    private var eventLog by mutableStateOf(listOf<String>())
    private var strongestScore by mutableStateOf(0.0)
    private var sessionStart = 0L
    private var sessionId = ""
    private var cameraId: String? = null
    private lateinit var cameraManager: CameraManager
    private lateinit var sensorMonitor: SensorMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = findFlashCamera()
        sensorMonitor = SensorMonitor(this) { line, event, score ->
            runOnUiThread {
                sensorLine = line
                if (event != null) addEvent(event)
                if (score > strongestScore) strongestScore = score
            }
        }
        requestNeededPermissions()
        setContent { ContactConsole() }
    }

    override fun onDestroy() {
        super.onDestroy()
        transmitting.set(false)
        sensorMonitor.stop()
        setTorch(false)
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) wanted += Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) wanted += Manifest.permission.CAMERA
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 41)
    }

    @Composable
    private fun ContactConsole() {
        var message by remember { mutableStateOf("WE ARE HERE IN PEACE. IDENTIFY YOUR PATTERN.") }
        var hypothesis by remember { mutableStateOf("UNKNOWN / OTHER NHI") }
        var protocol by remember { mutableStateOf("Universal Math Handshake") }
        var useLight by remember { mutableStateOf(true) }
        var useScreen by remember { mutableStateOf(true) }
        var useAudio by remember { mutableStateOf(true) }
        var useVibration by remember { mutableStateOf(false) }
        var impression by remember { mutableStateOf("") }

        val bg = if (flashFrame) Color.White else Color(0xFF03060C)
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF76FFF2), secondary = Color(0xFFB68CFF), background = Color(0xFF03060C), surface = Color(0xFF0B1220))) {
            Box(Modifier.fillMaxSize().background(bg)) {
                if (!flashFrame) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Ω CONTACT ENGINE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Color(0xFF76FFF2))
                        Text("NHI / INTERDIMENSIONAL / PLASMA / UNKNOWN CONTACT LAB", style = MaterialTheme.typography.labelMedium, color = Color(0xFFB68CFF))
                        StatusCard()

                        Selector("CONTACT HYPOTHESIS", hypothesis, listOf("UNKNOWN / OTHER NHI", "EXTRATERRESTRIAL", "INTERDIMENSIONAL", "PLASMA / FIELD INTELLIGENCE", "MACHINE / SYNTHETIC NHI", "CONSCIOUSNESS-MEDIATED")) { hypothesis = it }
                        Selector("PROTOCOL", protocol, listOf("Universal Math Handshake", "Prime Beacon", "Fibonacci Beacon", "Plasma Coupling Sweep", "Binary Payload Only")) { protocol = it }

                        OutlinedTextField(value = message, onValueChange = { message = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Outbound message") }, minLines = 3)

                        Text("TRANSMISSION CHANNELS", fontWeight = FontWeight.Bold)
                        ToggleRow("Torch optical pulses", useLight) { useLight = it }
                        ToggleRow("Full-screen optical pulses", useScreen) { useScreen = it }
                        ToggleRow("Dual-tone acoustic data", useAudio) { useAudio = it }
                        ToggleRow("Haptic pulse carrier", useVibration) { useVibration = it }

                        Button(onClick = { transmit(message, hypothesis, protocol, useLight, useScreen, useAudio, useVibration) }, enabled = !transmitting.get() && !listening, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text("TRANSMIT + OPEN REPLY WINDOW", fontWeight = FontWeight.Black)
                        }
                        OutlinedButton(onClick = { startListenWindow(60, hypothesis, protocol, "MANUAL LISTEN") }, enabled = !listening && !transmitting.get(), modifier = Modifier.fillMaxWidth()) {
                            Text("LISTEN ONLY · 60 SECONDS")
                        }
                        if (listening) OutlinedButton(onClick = { stopListenWindow("MANUAL STOP") }, modifier = Modifier.fillMaxWidth()) { Text("STOP LISTENING") }

                        Divider()
                        Text("CONSCIOUSNESS / IMPRESSION CHANNEL", fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = impression, onValueChange = { impression = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Immediate impression, image, word, feeling") })
                        Button(onClick = { if (impression.isNotBlank()) { markImpression(impression); impression = "" } }, modifier = Modifier.fillMaxWidth()) { Text("TIME-STAMP IMPRESSION") }

                        Divider()
                        Text("LIVE RECEIVER", fontWeight = FontWeight.Bold)
                        Text(sensorLine, style = MaterialTheme.typography.bodySmall)
                        Text("Strongest anomaly score: ${"%.2f".format(strongestScore)}σ", color = Color(0xFFFFD37A))
                        eventLog.takeLast(14).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { saveSession() }, modifier = Modifier.weight(1f)) { Text("SAVE SESSION") }
                            OutlinedButton(onClick = { eventLog = emptyList(); strongestScore = 0.0 }, modifier = Modifier.weight(1f)) { Text("CLEAR VIEW") }
                        }
                        Spacer(Modifier.height(30.dp))
                    }
                }
            }
        }
    }

    @Composable private fun StatusCard() {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(status, fontWeight = FontWeight.Bold)
                Text(if (listening) "Receiver armed · baseline + anomaly timing active" else "Receiver idle", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }

    @Composable private fun Selector(title: String, current: String, options: List<String>, onSelect: (String) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Column {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Color(0xFF9FAEC2))
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(current) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { onSelect(item); open = false }) }
            }
        }
    }

    private fun transmit(message: String, hypothesis: String, protocol: String, light: Boolean, screen: Boolean, audio: Boolean, vibration: Boolean) {
        if (!transmitting.compareAndSet(false, true)) return
        eventLog = emptyList(); strongestScore = 0.0
        sessionStart = System.currentTimeMillis()
        sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(sessionStart))
        val bits = buildPacket(message, protocol)
        addEvent("TX SESSION $sessionId · $hypothesis")
        addEvent("Protocol: $protocol · ${bits.size} symbols")
        status = "TRANSMITTING · ${bits.size} SYMBOLS"

        Thread {
            val audioTrack = if (audio) buildAudio(bits) else null
            try {
                audioTrack?.play()
                bits.forEachIndexed { index, bit ->
                    if (!transmitting.get()) return@Thread
                    val onMs = if (bit == 1) 210L else 75L
                    if (light) setTorch(true)
                    if (screen) runOnUiThread { flashFrame = true }
                    if (vibration) vibrate(onMs)
                    Thread.sleep(onMs)
                    if (light) setTorch(false)
                    if (screen) runOnUiThread { flashFrame = false }
                    Thread.sleep(95L)
                    if (index % 16 == 0) runOnUiThread { status = "TRANSMITTING · ${index + 1}/${bits.size}" }
                }
            } finally {
                setTorch(false)
                runOnUiThread { flashFrame = false }
                audioTrack?.stop(); audioTrack?.release()
                transmitting.set(false)
                runOnUiThread {
                    addEvent("TX complete · reply window begins")
                    startListenWindow(60, hypothesis, protocol, "POST-TX REPLY WINDOW")
                }
            }
        }.start()
    }

    private fun buildPacket(message: String, protocol: String): List<Int> {
        val bits = mutableListOf<Int>()
        fun appendPattern(s: String) { s.filter { it == '0' || it == '1' }.forEach { bits += if (it == '1') 1 else 0 } }
        fun appendByte(v: Int) { for (i in 7 downTo 0) bits += (v shr i) and 1 }
        if (protocol != "Binary Payload Only") {
            appendPattern("101010101111000011110101")
            when (protocol) {
                "Prime Beacon", "Universal Math Handshake" -> listOf(2,3,5,7,11,13,17,19).forEach { appendByte(it); appendPattern("00") }
                "Fibonacci Beacon" -> listOf(1,1,2,3,5,8,13,21,34,55).forEach { appendByte(it); appendPattern("00") }
                "Plasma Coupling Sweep" -> listOf(3,5,8,13,21,34,55,89).forEach { appendByte(it); appendPattern("01") }
            }
            if (protocol == "Universal Math Handshake") appendPattern("11100011100010100101")
        }
        message.toByteArray(StandardCharsets.UTF_8).forEach { appendByte(it.toInt() and 0xFF) }
        val crc = CRC32().apply { update(message.toByteArray(StandardCharsets.UTF_8)) }.value
        for (i in 31 downTo 0) bits += ((crc shr i) and 1L).toInt()
        appendPattern("1111000010101111")
        return bits
    }

    private fun buildAudio(bits: List<Int>): AudioTrack? {
        return try {
            val sr = 44100
            val samples = ArrayList<Short>()
            bits.forEach { bit ->
                val onMs = if (bit == 1) 210 else 75
                val hz = if (bit == 1) 1440.0 else 720.0
                val n = sr * onMs / 1000
                for (i in 0 until n) {
                    val env = min(1.0, min(i / 150.0, (n - i).coerceAtLeast(0) / 150.0))
                    samples += (sin(2.0 * Math.PI * hz * i / sr) * 9000.0 * env).toInt().toShort()
                }
                repeat(sr * 95 / 1000) { samples += 0 }
            }
            val pcm = ShortArray(samples.size) { samples[it] }
            AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build().also { it.write(pcm, 0, pcm.size) }
        } catch (_: Throwable) { null }
    }

    private fun startListenWindow(seconds: Int, hypothesis: String, protocol: String, source: String) {
        if (listening) return
        if (sessionStart == 0L) {
            sessionStart = System.currentTimeMillis()
            sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(sessionStart))
        }
        listening = true
        status = "LISTENING · BASELINE 5s"
        addEvent("RX $source · $hypothesis · $protocol")
        sensorMonitor.start(sessionStart)
        Handler(Looper.getMainLooper()).postDelayed({ if (listening) status = "LISTENING · PATTERN WATCH ACTIVE" }, 5000)
        Handler(Looper.getMainLooper()).postDelayed({ if (listening) stopListenWindow("WINDOW COMPLETE") }, seconds * 1000L)
    }

    private fun stopListenWindow(reason: String) {
        if (!listening) return
        listening = false
        sensorMonitor.stop()
        status = "SESSION COMPLETE · ${eventLog.count { it.contains("ANOMALY") }} EVENTS"
        addEvent("RX ended · $reason · peak ${"%.2f".format(strongestScore)}σ")
        saveSession()
    }

    private fun markImpression(text: String) {
        val t = if (sessionStart > 0) (System.currentTimeMillis() - sessionStart) / 1000.0 else 0.0
        addEvent("IMPRESSION +${"%.2f".format(t)}s · $text")
    }

    private fun addEvent(text: String) {
        val t = if (sessionStart > 0) (System.currentTimeMillis() - sessionStart) / 1000.0 else 0.0
        eventLog = eventLog + "[+${"%.2f".format(t)}s] $text"
    }

    private fun saveSession() {
        if (eventLog.isEmpty()) return
        try {
            val dir = File(getExternalFilesDir(null), "OMEGA-Contact-Sessions").apply { mkdirs() }
            val id = if (sessionId.isBlank()) SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) else sessionId
            File(dir, "omega-$id.txt").writeText("OMEGA NHI CONTACT SESSION\nPeak anomaly: ${"%.3f".format(strongestScore)} sigma\n\n" + eventLog.joinToString("\n"))
            status = "SAVED · omega-$id.txt"
        } catch (e: Throwable) { status = "SAVE ERROR · ${e.message}" }
    }

    private fun findFlashCamera(): String? = try {
        cameraManager.cameraIdList.firstOrNull { id -> cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (_: Throwable) { null }

    private fun setTorch(on: Boolean) {
        try { if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraId?.let { cameraManager.setTorchMode(it, on) } } catch (_: Throwable) {}
    }

    private fun vibrate(ms: Long) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            v.vibrate(VibrationEffect.createOneShot(ms, 180))
        } catch (_: Throwable) {}
    }

    private class RunningStats {
        var n = 0; var mean = 0.0; var m2 = 0.0
        fun add(x: Double) { n++; val d = x - mean; mean += d / n; m2 += d * (x - mean) }
        fun sd(): Double = if (n > 2) sqrt(m2 / (n - 1)).coerceAtLeast(1e-6) else 1.0
        fun z(x: Double): Double = abs(x - mean) / sd()
    }

    private class SensorMonitor(private val activity: Activity, private val cb: (String, String?, Double) -> Unit) : SensorEventListener {
        private val sm = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val stats = mutableMapOf<Int, RunningStats>()
        private val values = mutableMapOf<Int, Double>()
        private val lastEvent = mutableMapOf<Int, Long>()
        private var active = false
        private var baselineUntil = 0L
        private var sessionStart = 0L
        private var audioThread: Thread? = null
        private val audioActive = AtomicBoolean(false)
        private val names = mapOf(Sensor.TYPE_ACCELEROMETER to "ACCEL", Sensor.TYPE_GYROSCOPE to "GYRO", Sensor.TYPE_MAGNETIC_FIELD to "MAG", Sensor.TYPE_LIGHT to "LIGHT", 999 to "MIC")

        fun start(startMs: Long) {
            stop(); active = true; sessionStart = startMs; baselineUntil = System.currentTimeMillis() + 5000
            listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_LIGHT).forEach { type ->
                sm.getDefaultSensor(type)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            }
            startAudio()
        }

        fun stop() { active = false; sm.unregisterListener(this); audioActive.set(false); audioThread = null }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            if (!active) return
            val v = if (e.values.size >= 3) sqrt((e.values[0]*e.values[0] + e.values[1]*e.values[1] + e.values[2]*e.values[2]).toDouble()) else e.values[0].toDouble()
            process(e.sensor.type, v)
        }

        private fun process(type: Int, value: Double) {
            values[type] = value
            val s = stats.getOrPut(type) { RunningStats() }
            val now = System.currentTimeMillis()
            if (now < baselineUntil) { s.add(value); emitLine(0.0); return }
            if (s.n < 8) { s.add(value); return }
            val z = s.z(value)
            var event: String? = null
            if (z >= 4.0 && now - (lastEvent[type] ?: 0L) > 1200) {
                lastEvent[type] = now
                val delay = (now - sessionStart) / 1000.0
                event = "ANOMALY ${names[type] ?: type} · ${"%.2f".format(z)}σ · delay ${"%.2f".format(delay)}s · value ${"%.3f".format(value)}"
            }
            emitLine(z, event)
        }

        private fun emitLine(z: Double, event: String? = null) {
            val line = values.entries.joinToString("  ") { "${names[it.key] ?: it.key}:${"%.2f".format(it.value)}" }
            cb(line, event, z)
        }

        private fun startAudio() {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            audioActive.set(true)
            audioThread = Thread {
                val sr = 44100; val size = max(AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
                val rec = try { AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2) } catch (_: Throwable) { null } ?: return@Thread
                val buf = ShortArray(size)
                try {
                    rec.startRecording()
                    while (audioActive.get()) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            var sum = 0.0
                            for (i in 0 until n) { val x = buf[i].toDouble(); sum += x*x }
                            process(999, sqrt(sum / n) / 32768.0)
                        }
                    }
                } catch (_: Throwable) {} finally { try { rec.stop() } catch (_: Throwable) {}; rec.release() }
            }.also { it.start() }
        }
    }
}

package com.vaan.ultracarrier.collective

import android.content.Context
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import com.vaan.ultracarrier.audio.AudioHardwareChecker
import com.vaan.ultracarrier.audio.BeamLabMode
import com.vaan.ultracarrier.audio.HardwareMode
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.audio.TtsSynthesizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class CollectiveUiState(
    val text: String = "",
    val sourceName: String? = null,
    val source: CollectiveSource? = null,
    val hardware: HardwareMode? = null,
    val family: CollectiveFamily = CollectiveFamily.PERCEPTION_LAB,
    val worldMode: BeamLabMode = BeamLabMode.ELF_BEAM,
    val collectiveMode: CollectiveMode = CollectiveMode.THOUGHT_GHOST,
    val listeningPath: ListeningPath = ListeningPath.PHONE_SPEAKER,
    val presence: Float = 0.48f,
    val carrierHz: Float = 18_000f,
    val elfRateHz: Float = 7.83f,
    val elfDepth: Float = 0.36f,
    val targetAngleDeg: Float = 0f,
    val nullAngleDeg: Float = 35f,
    val spacingMm: Float = 8.5f,
    val ditherDeg: Float = 2f,
    val ditherRateHz: Float = 0.25f,
    val headWidthCm: Float = 15.5f,
    val listenerDistanceCm: Float = 45f,
    val loop: Boolean = true,
    val exportFormat: ExportFormat = ExportFormat.WAV_24,
    val busy: Boolean = false,
    val playing: Boolean = false,
    val exporting: Boolean = false,
    val exportProgress: Double? = null,
    val status: String = "Collective Beam Lab ready. Files stream from storage instead of being loaded into RAM.",
    val report: CollectiveReport? = null,
    val fadeRunning: Boolean = false,
    val fadeElapsedMs: Long? = null,
    val fadeTrials: List<FadeTrial> = emptyList()
)

class CollectiveController(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decoder = CollectiveStreamDecoder(app.contentResolver)
    private val engine = CollectiveAudioEngine(app.contentResolver)
    private val tts = TtsSynthesizer(app)
    private val hardwareChecker = AudioHardwareChecker(app)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var job: Job? = null
    private var session = 0L
    private var tempTts: java.io.File? = null
    private var fadeStart = 0L

    private val _state = MutableStateFlow(CollectiveUiState())
    val state: StateFlow<CollectiveUiState> = _state.asStateFlow()
    private val _scopeData = MutableStateFlow(FloatArray(0))
    val scopeData: StateFlow<FloatArray> = _scopeData.asStateFlow()
    private val _scopeRate = MutableStateFlow(48_000)
    val scopeRate: StateFlow<Int> = _scopeRate.asStateFlow()

    init {
        hardwareChecker.start { h ->
            val old = _state.value
            val carrier = if (old.carrierHz in h.carrierMinHz..h.carrierMaxHz) old.carrierHz
            else (h.carrierMaxHz - 300f).coerceIn(h.carrierMinHz, h.carrierMaxHz)
            _state.value = old.copy(hardware = h, carrierHz = carrier, status = if (old.playing) old.status else routeMessage(old.listeningPath, h))
        }
    }

    fun setText(v: String) { _state.value = _state.value.copy(text = v) }
    fun setFamily(v: CollectiveFamily) { _state.value = _state.value.copy(family = v, status = v.label) }
    fun setWorldMode(v: BeamLabMode) { _state.value = _state.value.copy(worldMode = v, status = v.description) }
    fun setCollectiveMode(v: CollectiveMode) { _state.value = _state.value.copy(collectiveMode = v, status = v.description) }
    fun setPath(v: ListeningPath) { val s = _state.value; _state.value = s.copy(listeningPath = v, status = s.hardware?.let { routeMessage(v, it) } ?: v.description) }
    fun setPresence(v: Float) { _state.value = _state.value.copy(presence = v.coerceIn(0.05f, 1f)) }
    fun setCarrier(v: Float) { val h = _state.value.hardware ?: return; _state.value = _state.value.copy(carrierHz = v.coerceIn(h.carrierMinHz, h.carrierMaxHz)) }
    fun setElfRate(v: Float) { _state.value = _state.value.copy(elfRateHz = v.coerceIn(0.25f, 80f)) }
    fun setElfDepth(v: Float) { _state.value = _state.value.copy(elfDepth = v.coerceIn(0f, 0.98f)) }
    fun setTarget(v: Float) { _state.value = _state.value.copy(targetAngleDeg = v.coerceIn(-70f, 70f)) }
    fun setNull(v: Float) { _state.value = _state.value.copy(nullAngleDeg = v.coerceIn(-80f, 80f)) }
    fun setSpacing(v: Float) { _state.value = _state.value.copy(spacingMm = v.coerceIn(1f, 80f)) }
    fun setDither(v: Float) { _state.value = _state.value.copy(ditherDeg = v.coerceIn(0f, 15f)) }
    fun setDitherRate(v: Float) { _state.value = _state.value.copy(ditherRateHz = v.coerceIn(0.02f, 5f)) }
    fun setHeadWidth(v: Float) { _state.value = _state.value.copy(headWidthCm = v.coerceIn(10f, 24f)) }
    fun setDistance(v: Float) { _state.value = _state.value.copy(listenerDistanceCm = v.coerceIn(10f, 500f)) }
    fun setLoop(v: Boolean) { _state.value = _state.value.copy(loop = v, status = if (v) "Loop until Stop enabled." else "Loop disabled.") }
    fun setExportFormat(v: ExportFormat) { _state.value = _state.value.copy(exportFormat = v) }

    fun loadFile(uri: Uri) {
        scope.launch {
            setBusy("Inspecting audio stream…")
            try {
                runCatching { app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val info = withContext(Dispatchers.IO) { decoder.inspectUri(uri) }
                val name = queryName(uri)
                tempTts?.delete(); tempTts = null
                _scopeData.value = FloatArray(0)
                _state.value = _state.value.copy(
                    source = CollectiveSource.UriSource(uri, info),
                    sourceName = name,
                    busy = false,
                    status = "Ready: $name • ${formatDuration(info.durationSeconds)} • ${info.formatLabel} • streaming"
                )
            } catch (t: Throwable) { fail(t) }
        }
    }

    fun prepareText() {
        val text = _state.value.text.trim()
        if (text.isBlank()) { _state.value = _state.value.copy(status = "Enter text first."); return }
        scope.launch {
            setBusy("Synthesizing speech…")
            try {
                val file = withContext(Dispatchers.IO) { tts.synthesize(text) }
                val info = withContext(Dispatchers.IO) { decoder.inspectFile(file) }
                tempTts?.delete(); tempTts = file
                _state.value = _state.value.copy(
                    source = CollectiveSource.FileSource(file, info),
                    sourceName = "Voice: ${text.take(42)}",
                    busy = false,
                    status = "Speech ready • ${formatDuration(info.durationSeconds)}"
                )
            } catch (t: Throwable) { fail(t) }
        }
    }

    fun play() {
        val source = _state.value.source ?: run { _state.value = _state.value.copy(status = "Prepare text or choose a file first."); return }
        val h = _state.value.hardware ?: run { _state.value = _state.value.copy(status = "Audio hardware is still initializing."); return }
        stopInternal()
        session++
        val id = session
        _state.value = _state.value.copy(busy = true, playing = true, exporting = false, report = null, status = "Opening stream…")
        job = scope.launch(Dispatchers.IO) {
            try {
                var pass = 0
                do {
                    pass++
                    val cfg = config(h)
                    engine.play(
                        source = source,
                        decoder = decoder,
                        config = cfg,
                        preferredDevice = h.outputDevice,
                        onStarted = { r -> if (id == session) _state.value = _state.value.copy(busy = false, playing = true, report = r, status = "${r.modeLabel}${if (_state.value.loop) " • loop $pass" else ""} • ${r.sampleRate} Hz") },
                        onScope = { wave, rate -> if (id == session) { _scopeData.value = wave; _scopeRate.value = rate } }
                    )
                    if (id != session) return@launch
                } while (_state.value.loop)
                if (id == session) _state.value = _state.value.copy(busy = false, playing = false, status = "Finished")
            } catch (t: Throwable) { if (id == session) fail(t) }
        }
    }

    fun export(destination: Uri) {
        val source = _state.value.source ?: run { _state.value = _state.value.copy(status = "Choose or prepare audio first."); return }
        val h = _state.value.hardware ?: run { _state.value = _state.value.copy(status = "Audio hardware is still initializing."); return }
        stopInternal()
        session++
        val id = session
        val fmt = _state.value.exportFormat
        _state.value = _state.value.copy(busy = true, exporting = true, playing = false, exportProgress = 0.0, status = "Rendering ${fmt.label}…")
        job = scope.launch(Dispatchers.IO) {
            try {
                engine.export(
                    source = source,
                    decoder = decoder,
                    config = config(h),
                    destination = destination,
                    format = fmt,
                    onProgress = { p -> if (id == session) _state.value = _state.value.copy(exportProgress = p, status = p?.let { "Rendering ${(it * 100).roundToInt()}%…" } ?: "Rendering stream…") },
                    onScope = { wave, rate -> if (id == session) { _scopeData.value = wave; _scopeRate.value = rate } }
                )
                if (id == session) _state.value = _state.value.copy(busy = false, exporting = false, exportProgress = 1.0, status = "Saved ${fmt.label} successfully.")
            } catch (t: Throwable) { if (id == session) fail(t) }
        }
    }

    fun stop() {
        session++
        stopInternal()
        _state.value = _state.value.copy(busy = false, playing = false, exporting = false, status = "Stopped")
    }

    fun startFadeTrial() {
        fadeStart = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(fadeRunning = true, fadeElapsedMs = null, status = "Fade trial running. Fixate the center mark; tap FADED when the peripheral target disappears.")
    }

    fun markFaded() {
        if (!_state.value.fadeRunning) return
        val elapsed = SystemClock.elapsedRealtime() - fadeStart
        val label = if (_state.value.family == CollectiveFamily.WORLD_BEAM) _state.value.worldMode.label else _state.value.collectiveMode.label
        val trial = FadeTrial(label, elapsed, System.currentTimeMillis())
        _state.value = _state.value.copy(fadeRunning = false, fadeElapsedMs = elapsed, fadeTrials = (_state.value.fadeTrials + trial).takeLast(20), status = "Fade recorded: ${"%.2f".format(elapsed / 1000.0)} s")
    }

    fun clearFadeTrials() { _state.value = _state.value.copy(fadeRunning = false, fadeElapsedMs = null, fadeTrials = emptyList(), status = "Fade trials cleared.") }

    fun setListeningVolume() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val f = when (_state.value.listeningPath) {
            ListeningPath.HEADPHONES -> 0.25f
            ListeningPath.BONE_CONDUCTION -> 0.20f
            ListeningPath.PHONE_SPEAKER -> 0.52f
            ListeningPath.EXTERNAL_ARRAY -> 0.35f
        }
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * f).roundToInt().coerceIn(1, max), AudioManager.FLAG_SHOW_UI)
    }

    private fun config(h: HardwareMode): CollectiveConfig {
        val s = _state.value
        return CollectiveConfig(
            family = s.family,
            worldMode = s.worldMode,
            collectiveMode = s.collectiveMode,
            listeningPath = s.listeningPath,
            requestedSampleRate = h.requestedSampleRate,
            carrierHz = s.carrierHz,
            presence = s.presence,
            elfRateHz = s.elfRateHz,
            elfDepth = s.elfDepth,
            targetAngleDeg = s.targetAngleDeg,
            nullAngleDeg = s.nullAngleDeg,
            spacingMm = s.spacingMm,
            ditherDeg = s.ditherDeg,
            ditherRateHz = s.ditherRateHz,
            headWidthCm = s.headWidthCm,
            listenerDistanceCm = s.listenerDistanceCm
        )
    }

    private fun stopInternal() { engine.stop(); job?.cancel(); job = null }
    private fun setBusy(msg: String) { _state.value = _state.value.copy(busy = true, status = msg) }
    private fun fail(t: Throwable) { _state.value = _state.value.copy(busy = false, playing = false, exporting = false, status = "Error: ${t.message ?: t::class.java.simpleName}") }

    private fun routeMessage(path: ListeningPath, h: HardwareMode): String = when (path) {
        ListeningPath.EXTERNAL_ARRAY -> if (h.external) "External route detected. Directional stereo/ultrasonic fields available." else "External-array mode selected. Connect your DAC/array when ready."
        ListeningPath.PHONE_SPEAKER -> "Phone speaker ready. Beam modes are previews; Perception Lab modes are directly testable. ${h.detail}"
        ListeningPath.HEADPHONES -> if (h.external) "Headphone route detected." else "Headphone mode selected. Connect headphones for stereo internal-image modes."
        ListeningPath.BONE_CONDUCTION -> if (h.external) "Bone-conduction route detected." else "Bone-conduction mode selected."
    }

    private fun queryName(uri: Uri): String {
        var c: Cursor? = null
        return try {
            c = app.contentResolver.query(uri, null, null, null, null)
            val i = c?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
            if (c?.moveToFirst() == true && i >= 0) c.getString(i) else "Selected audio"
        } finally { c?.close() }
    }

    private fun formatDuration(seconds: Double?): String {
        if (seconds == null || !seconds.isFinite()) return "unknown length"
        val total = seconds.roundToInt().coerceAtLeast(0)
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    override fun close() {
        session++
        stopInternal()
        hardwareChecker.stop()
        tts.close()
        tempTts?.delete()
        scope.cancel()
    }
}

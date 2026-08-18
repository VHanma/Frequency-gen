package com.vaan.ultracarrier.collective

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.vaan.ultracarrier.audio.AudioHardwareChecker
import com.vaan.ultracarrier.audio.BeamLabMode
import com.vaan.ultracarrier.audio.GodXMode
import com.vaan.ultracarrier.audio.HardwareMode
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.audio.ThoughtMode
import com.vaan.ultracarrier.audio.TtsSynthesizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

class OmegaController(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decoder = CollectiveStreamDecoder(app.contentResolver)
    private val worldEngine = CollectiveAudioEngine(app.contentResolver)
    private val originalEngine = OriginalStreamingAudioEngine(app.contentResolver)
    private val scalarEngine = ScalarStreamingAudioEngine(app.contentResolver)
    private val resonanceEngine = ResonanceStreamingAudioEngine(app.contentResolver)
    private val matrixEngine = MatrixStreamingAudioEngine(app.contentResolver)
    private val stackManager = LayerStackPlaybackManager(app)
    private val tts = TtsSynthesizer(app)
    private val hardwareChecker = AudioHardwareChecker(app)
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var job: Job? = null
    private var session = 0L
    private var tempTts: java.io.File? = null
    private var fadeStart = 0L

    private val _state = MutableStateFlow(OmegaUiState())
    val state: StateFlow<OmegaUiState> = _state.asStateFlow()
    private val _scopeData = MutableStateFlow(FloatArray(0))
    val scopeData: StateFlow<FloatArray> = _scopeData.asStateFlow()
    private val _scopeRate = MutableStateFlow(48_000)
    val scopeRate: StateFlow<Int> = _scopeRate.asStateFlow()

    init {
        hardwareChecker.start { h ->
            val s = _state.value
            val carrier = if (s.carrierHz in h.carrierMinHz..h.carrierMaxHz) s.carrierHz else (h.carrierMaxHz - 300f).coerceIn(h.carrierMinHz, h.carrierMaxHz)
            _state.value = s.copy(hardware = h, carrierHz = carrier, status = if (s.playing || s.exporting) s.status else "Audio ready • ${h.requestedSampleRate} Hz")
        }
    }

    fun setText(v: String) { _state.value = _state.value.copy(text = v) }
    fun setFamily(v: OmegaFamily) { val s = _state.value.copy(family = v); _state.value = applyPresetTo(s, presetFor(s), "${v.label} preset loaded") }
    fun setWorldMode(v: BeamLabMode) { val s = _state.value.copy(worldMode = v); _state.value = applyPresetTo(s, PresetCatalog.world(v), "${v.label} preset loaded") }
    fun setCollectiveMode(v: CollectiveMode) { val s = _state.value.copy(collectiveMode = v); _state.value = applyPresetTo(s, PresetCatalog.perception(v), "${v.label} preset loaded") }
    fun setLabXMode(v: GodXMode) { val s = _state.value.copy(labXMode = v); _state.value = applyPresetTo(s, PresetCatalog.labX(v), "${v.label} preset loaded") }
    fun setClassicMode(v: ThoughtMode) { val s = _state.value.copy(classicMode = v); _state.value = applyPresetTo(s, PresetCatalog.classic(v), "${v.label} preset loaded") }
    fun setScalarMode(v: ScalarMode) { val s = _state.value.copy(scalarMode = v); _state.value = applyPresetTo(s, PresetCatalog.scalar(v), "${v.label} preset loaded") }
    fun setResonanceMode(v: ResonanceMode) { val s = _state.value.copy(resonanceMode = v); _state.value = applyPresetTo(s, ResonancePresetCatalog.preset(v), "${v.label} preset loaded") }
    fun setMatrixMode(v: MatrixMode) { val s = _state.value.copy(matrixMode = v); _state.value = applyPresetTo(s, MatrixPresetCatalog.preset(v), "${v.label} preset loaded") }

    fun resetPreset() { val s = _state.value; val p = presetFor(s); _state.value = applyPresetTo(s, p, "Restored ${p.name} recommended variables") }
    fun setPath(v: ListeningPath) { _state.value = _state.value.copy(listeningPath = v, status = "Output: ${v.label}") }
    fun setPresence(v: Float) { _state.value = _state.value.copy(presence = v.coerceIn(.05f, 1f)) }
    fun setCarrier(v: Float) { val h = _state.value.hardware; _state.value = _state.value.copy(carrierHz = v.coerceIn(h?.carrierMinHz ?: 500f, h?.carrierMaxHz ?: 22_000f)) }
    fun setElfRate(v: Float) { _state.value = _state.value.copy(elfRateHz = v.coerceIn(.02f, 120f)) }
    fun setElfDepth(v: Float) { _state.value = _state.value.copy(elfDepth = v.coerceIn(0f, .98f)) }
    fun setTarget(v: Float) { _state.value = _state.value.copy(targetAngleDeg = v.coerceIn(-80f, 80f)) }
    fun setNull(v: Float) { _state.value = _state.value.copy(nullAngleDeg = v.coerceIn(-80f, 80f)) }
    fun setSpacing(v: Float) { _state.value = _state.value.copy(spacingMm = v.coerceIn(1f, 80f)) }
    fun setDither(v: Float) { _state.value = _state.value.copy(ditherDeg = v.coerceIn(0f, 15f)) }
    fun setDitherRate(v: Float) { _state.value = _state.value.copy(ditherRateHz = v.coerceIn(.02f, 5f)) }
    fun setHeadWidth(v: Float) { _state.value = _state.value.copy(headWidthCm = v.coerceIn(10f, 24f)) }
    fun setDistance(v: Float) { _state.value = _state.value.copy(listenerDistanceCm = v.coerceIn(10f, 500f)) }
    fun setLoop(v: Boolean) { _state.value = _state.value.copy(loop = v, status = if (v) "Loop until Stop enabled." else "Loop disabled.") }
    fun setExportFormat(v: ExportFormat) { _state.value = _state.value.copy(exportFormat = v) }

    fun addCurrentToStack() {
        val s = _state.value
        val layer = OmegaLayerRecipe(
            id = System.nanoTime(), label = selectedModeLabel(s), family = s.family,
            worldMode = s.worldMode, collectiveMode = s.collectiveMode, labXMode = s.labXMode, classicMode = s.classicMode,
            scalarMode = s.scalarMode, resonanceMode = s.resonanceMode, matrixMode = s.matrixMode, listeningPath = s.listeningPath,
            carrierHz = s.carrierHz, presence = s.presence, elfRateHz = s.elfRateHz, elfDepth = s.elfDepth,
            targetAngleDeg = s.targetAngleDeg, nullAngleDeg = s.nullAngleDeg, spacingMm = s.spacingMm,
            ditherDeg = s.ditherDeg, ditherRateHz = s.ditherRateHz, headWidthCm = s.headWidthCm, listenerDistanceCm = s.listenerDistanceCm
        )
        _state.value = s.copy(stackLayers = s.stackLayers + layer, status = "Added ${layer.label} to Layer Stack • ${s.stackLayers.size + 1} layers")
    }

    fun removeStackLayer(id: Long) { val s = _state.value; _state.value = s.copy(stackLayers = s.stackLayers.filterNot { it.id == id }, status = "Layer removed • ${s.stackLayers.count { it.id != id }} remain") }
    fun clearStack() { _state.value = _state.value.copy(stackLayers = emptyList(), status = "Layer Stack cleared.") }

    fun setRemotePatient(v: String) { _state.value = _state.value.copy(remotePatient = v) }
    fun setRemoteIntention(v: String) { _state.value = _state.value.copy(remoteIntention = v) }
    fun setRemoteConsent(v: Boolean) { _state.value = _state.value.copy(remoteConsent = v, remoteStatus = if (v) "Consent marked for this session." else "Consent not marked.") }
    fun setRemoteSilent(v: Boolean) { _state.value = _state.value.copy(remoteSilent = v) }
    fun startRemoteSession() {
        val s = _state.value
        if (!s.remoteConsent) { _state.value = s.copy(remoteStatus = "Mark patient consent before starting.", status = "Remote session needs consent."); return }
        _state.value = s.copy(remoteActive = true, remoteStatus = "Remote Resonance Session active${s.remotePatient.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""}.")
        if (!s.remoteSilent) {
            if (s.stackLayers.isNotEmpty()) playStack() else play()
        }
    }
    fun endRemoteSession() { stop(); _state.value = _state.value.copy(remoteActive = false, remoteStatus = "Remote Resonance Session ended.") }

    fun loadFile(uri: Uri) = launchUi("Inspecting audio stream…") {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val info = withContext(Dispatchers.IO) { decoder.inspectUri(uri) }; val name = queryName(uri)
        tempTts?.delete(); tempTts = null; _scopeData.value = FloatArray(0)
        _state.value = _state.value.copy(source = CollectiveSource.UriSource(uri, info), sourceName = name, busy = false, status = "Ready: $name • ${formatDuration(info.durationSeconds)} • ${info.formatLabel} • streaming")
    }

    fun prepareText() {
        val text = _state.value.text.trim(); if (text.isBlank()) { _state.value = _state.value.copy(status = "Enter text first."); return }
        launchUi("Starting Android TTS…") {
            val file = withContext(Dispatchers.IO) { tts.synthesize(text) }; if (!file.exists() || file.length() <= 44L) error("TTS engine returned an empty audio file.")
            val info = withContext(Dispatchers.IO) { decoder.inspectFile(file) }; tempTts?.delete(); tempTts = file
            _state.value = _state.value.copy(source = CollectiveSource.FileSource(file, info), sourceName = "Voice: ${text.take(42)}", busy = false, status = "TTS audio ready • ${formatDuration(info.durationSeconds)} • ${info.formatLabel}")
        }
    }

    fun play() {
        val s = _state.value; val source = s.source ?: run { _state.value = s.copy(status = "Prepare text or choose a file first."); return }; val h = s.hardware ?: run { _state.value = s.copy(status = "Audio hardware is still initializing."); return }
        stopInternal(); session++; val id = session; OmegaPlaybackService.start(app, selectedModeLabel(s))
        _state.value = s.copy(busy = true, playing = true, stackPlaying = false, exporting = false, backgroundActive = true, report = null, status = "Opening background stream…")
        job = scope.launch(Dispatchers.IO) {
            try {
                var pass = 0
                do {
                    pass++; val now = _state.value; val cfg = config(now, h)
                    val started: (CollectiveReport) -> Unit = { r -> if (id == session) _state.value = _state.value.copy(busy = false, playing = true, backgroundActive = true, report = r, status = "${selectedModeLabel(_state.value)}${if (_state.value.loop) " • loop $pass" else ""} • ${r.sampleRate} Hz • background service active") }
                    val scopeFn: (FloatArray, Int) -> Unit = { wave, rate -> if (id == session) { _scopeData.value = wave; _scopeRate.value = rate } }
                    when (now.family) {
                        OmegaFamily.LAB_X, OmegaFamily.THOUGHTBEAM -> originalEngine.play(source, decoder, cfg, h.outputDevice, started, scopeFn)
                        OmegaFamily.SCALAR_LAB -> scalarEngine.play(source, decoder, cfg, h.outputDevice, started, scopeFn)
                        OmegaFamily.RESONANCE_LAB -> resonanceEngine.play(source, decoder, cfg, now.resonanceMode, h.outputDevice, started, scopeFn)
                        OmegaFamily.MATRIX_LAB -> matrixEngine.play(source, decoder, cfg, now.matrixMode, h.outputDevice, started, scopeFn)
                        else -> worldEngine.play(source, decoder, cfg, h.outputDevice, started, scopeFn)
                    }
                    if (id != session) return@launch
                } while (_state.value.loop)
                if (id == session) { _state.value = _state.value.copy(busy = false, playing = false, backgroundActive = false, status = "Finished"); OmegaPlaybackService.shutdown(app) }
            } catch (t: Throwable) { if (id == session) { fail(t); OmegaPlaybackService.shutdown(app) } }
        }
    }

    fun playStack() {
        val s = _state.value
        val source = s.source ?: run { _state.value = s.copy(status = "Prepare text or choose a file first."); return }
        val h = s.hardware ?: run { _state.value = s.copy(status = "Audio hardware is still initializing."); return }
        val recipes = s.stackLayers
        if (recipes.isEmpty()) { _state.value = s.copy(status = "Add at least one mode to Layer Stack first."); return }
        stopInternal(); session++; val id = session
        OmegaPlaybackService.start(app, "Layer Stack ×${recipes.size}")
        _state.value = s.copy(busy = true, playing = true, stackPlaying = true, exporting = false, backgroundActive = true, report = null, status = "Opening ${recipes.size}-layer simultaneous stream…")
        job = scope.launch(Dispatchers.IO) {
            try {
                var pass = 0
                do {
                    pass++
                    val renderLayers = recipes.map { recipe -> stackLayer(recipe, h, recipes.size) }
                    val started: (CollectiveReport) -> Unit = { r -> if (id == session) _state.value = _state.value.copy(busy = false, playing = true, stackPlaying = true, backgroundActive = true, report = r, status = "Layer Stack ×${recipes.size}${if (_state.value.loop) " • loop $pass" else ""} • simultaneous • background active") }
                    val scopeFn: (FloatArray, Int) -> Unit = { wave, rate -> if (id == session) { _scopeData.value = wave; _scopeRate.value = rate } }
                    stackManager.playOnce(source, renderLayers, h.outputDevice, started, scopeFn)
                    if (id != session) return@launch
                } while (_state.value.loop)
                if (id == session) { _state.value = _state.value.copy(busy = false, playing = false, stackPlaying = false, backgroundActive = false, status = "Layer Stack finished"); OmegaPlaybackService.shutdown(app) }
            } catch (t: Throwable) { if (id == session) { fail(t); OmegaPlaybackService.shutdown(app) } }
        }
    }

    fun export(destination: Uri) { startExport(destination, null, null) }

    fun saveToDownloads() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { _state.value = _state.value.copy(status = "Use SAVE AS on Android 9 or earlier."); return }
        val source = _state.value.source ?: run { _state.value = _state.value.copy(status = "Choose or prepare audio first."); return }; if (source.info.sampleRate <= 0) return
        val name = "Omega-${_state.value.family.name}-${selectedModeLabel(_state.value).replace(Regex("[^A-Za-z0-9_-]+"), "-")}-${System.currentTimeMillis()}.wav"
        val values = ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav"); put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/UltraCarrier"); put(MediaStore.Audio.Media.IS_PENDING, 1) }
        val uri = app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: run { _state.value = _state.value.copy(status = "Android could not create the Music/UltraCarrier save target. Try SAVE AS."); return }
        startExport(uri, onSuccess = { val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }; app.contentResolver.update(uri, done, null, null) }, onFailure = { runCatching { app.contentResolver.delete(uri, null, null) } })
    }

    private fun startExport(destination: Uri, onSuccess: (() -> Unit)?, onFailure: (() -> Unit)?) {
        val s = _state.value; val source = s.source ?: run { _state.value = s.copy(status = "Choose or prepare audio first."); return }; val h = s.hardware ?: run { _state.value = s.copy(status = "Audio hardware is still initializing."); return }
        stopInternal(); session++; val id = session; val fmt = s.exportFormat
        _state.value = s.copy(busy = true, exporting = true, playing = false, stackPlaying = false, backgroundActive = false, exportProgress = 0.0, status = "Rendering ${fmt.label}…")
        job = scope.launch(Dispatchers.IO) {
            try {
                val now = _state.value; val cfg = config(now, h)
                val progress: (Double?) -> Unit = { p -> if (id == session) _state.value = _state.value.copy(exportProgress = p, status = p?.let { "Rendering ${(it * 100).roundToInt()}%…" } ?: "Rendering stream…") }
                val scopeFn: (FloatArray, Int) -> Unit = { wave, rate -> if (id == session) { _scopeData.value = wave; _scopeRate.value = rate } }
                when (now.family) {
                    OmegaFamily.LAB_X, OmegaFamily.THOUGHTBEAM -> originalEngine.export(source, decoder, cfg, destination, fmt, progress, scopeFn)
                    OmegaFamily.SCALAR_LAB -> scalarEngine.export(source, decoder, cfg, destination, fmt, progress, scopeFn)
                    OmegaFamily.RESONANCE_LAB -> resonanceEngine.export(source, decoder, cfg, now.resonanceMode, destination, fmt, progress, scopeFn)
                    OmegaFamily.MATRIX_LAB -> matrixEngine.export(source, decoder, cfg, now.matrixMode, destination, fmt, progress, scopeFn)
                    else -> worldEngine.export(source, decoder, cfg, destination, fmt, progress, scopeFn)
                }
                onSuccess?.invoke(); if (id == session) _state.value = _state.value.copy(busy = false, exporting = false, exportProgress = 1.0, status = "Saved ${fmt.label} successfully.")
            } catch (t: Throwable) { onFailure?.invoke(); if (id == session) fail(t) }
        }
    }

    fun stop() { session++; stopInternal(); _state.value = _state.value.copy(busy = false, playing = false, stackPlaying = false, exporting = false, backgroundActive = false, remoteActive = false, status = "Stopped"); OmegaPlaybackService.shutdown(app) }
    internal fun stopFromService() { session++; stopInternal(); _state.value = _state.value.copy(busy = false, playing = false, stackPlaying = false, exporting = false, backgroundActive = false, remoteActive = false, status = "Stopped from notification") }
    private fun stopInternal() { worldEngine.stop(); originalEngine.stop(); scalarEngine.stop(); resonanceEngine.stop(); matrixEngine.stop(); stackManager.stop(); job?.cancel(); job = null }

    fun setListeningVolume() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val f = when (_state.value.listeningPath) { ListeningPath.PHONE_SPEAKER -> .45f; ListeningPath.HEADPHONES -> .32f; ListeningPath.BONE_CONDUCTION -> .38f; ListeningPath.EXTERNAL_ARRAY -> .35f }
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * f).roundToInt().coerceIn(1, max), AudioManager.FLAG_SHOW_UI)
    }

    fun startFadeTrial() { fadeStart = SystemClock.elapsedRealtime(); _state.value = _state.value.copy(fadeRunning = true, fadeElapsedMs = null, status = "Fade trial running. Keep fixation on the center cross.") }
    fun markFaded() { if (!_state.value.fadeRunning) return; val elapsed = SystemClock.elapsedRealtime() - fadeStart; val trial = FadeTrial(selectedModeLabel(_state.value), elapsed, System.currentTimeMillis()); _state.value = _state.value.copy(fadeRunning = false, fadeElapsedMs = elapsed, fadeTrials = _state.value.fadeTrials + trial, status = "Fade recorded: ${"%.2f".format(elapsed / 1000.0)} s") }
    fun clearFadeTrials() { _state.value = _state.value.copy(fadeTrials = emptyList(), fadeElapsedMs = null, status = "Fade trials cleared.") }

    private fun presetFor(s: OmegaUiState): MethodPreset = when (s.family) {
        OmegaFamily.WORLD_BEAM -> PresetCatalog.world(s.worldMode); OmegaFamily.PERCEPTION_LAB -> PresetCatalog.perception(s.collectiveMode); OmegaFamily.LAB_X -> PresetCatalog.labX(s.labXMode); OmegaFamily.THOUGHTBEAM -> PresetCatalog.classic(s.classicMode); OmegaFamily.SCALAR_LAB -> PresetCatalog.scalar(s.scalarMode); OmegaFamily.RESONANCE_LAB -> ResonancePresetCatalog.preset(s.resonanceMode); OmegaFamily.MATRIX_LAB -> MatrixPresetCatalog.preset(s.matrixMode)
    }

    private fun applyPresetTo(s: OmegaUiState, p: MethodPreset, message: String): OmegaUiState {
        val h = s.hardware; val carrier = p.carrierHz?.let { it.coerceIn(h?.carrierMinHz ?: 500f, h?.carrierMaxHz ?: 22_000f) } ?: s.carrierHz
        return s.copy(carrierHz = carrier, elfRateHz = p.rateHz ?: s.elfRateHz, elfDepth = p.depth ?: s.elfDepth, targetAngleDeg = p.targetDeg ?: s.targetAngleDeg, nullAngleDeg = p.nullDeg ?: s.nullAngleDeg, spacingMm = p.spacingMm ?: s.spacingMm, ditherDeg = p.ditherDeg ?: s.ditherDeg, ditherRateHz = p.ditherRateHz ?: s.ditherRateHz, headWidthCm = p.headWidthCm ?: s.headWidthCm, listenerDistanceCm = p.distanceCm ?: s.listenerDistanceCm, status = "$message • ${p.variableSummary()}")
    }

    private fun config(s: OmegaUiState, h: HardwareMode): CollectiveConfig = CollectiveConfig(
        family = when (s.family) { OmegaFamily.WORLD_BEAM -> CollectiveFamily.WORLD_BEAM; OmegaFamily.PERCEPTION_LAB -> CollectiveFamily.PERCEPTION_LAB; OmegaFamily.LAB_X -> CollectiveFamily.LAB_X; OmegaFamily.THOUGHTBEAM -> CollectiveFamily.THOUGHTBEAM; OmegaFamily.SCALAR_LAB, OmegaFamily.RESONANCE_LAB, OmegaFamily.MATRIX_LAB -> CollectiveFamily.SCALAR_LAB },
        worldMode = s.worldMode, collectiveMode = s.collectiveMode, labXMode = s.labXMode, classicMode = s.classicMode, scalarMode = s.scalarMode, listeningPath = s.listeningPath, requestedSampleRate = h.requestedSampleRate, carrierHz = s.carrierHz, presence = s.presence, elfRateHz = s.elfRateHz, elfDepth = s.elfDepth, targetAngleDeg = s.targetAngleDeg, nullAngleDeg = s.nullAngleDeg, spacingMm = s.spacingMm, ditherDeg = s.ditherDeg, ditherRateHz = s.ditherRateHz, headWidthCm = s.headWidthCm, listenerDistanceCm = s.listenerDistanceCm
    )

    private fun stackLayer(r: OmegaLayerRecipe, h: HardwareMode, count: Int): StackRenderLayer {
        val normalizedPresence = (r.presence / sqrt(count.toFloat().coerceAtLeast(1f))).coerceIn(.05f, 1f)
        val cfg = CollectiveConfig(
            family = when (r.family) { OmegaFamily.WORLD_BEAM -> CollectiveFamily.WORLD_BEAM; OmegaFamily.PERCEPTION_LAB -> CollectiveFamily.PERCEPTION_LAB; OmegaFamily.LAB_X -> CollectiveFamily.LAB_X; OmegaFamily.THOUGHTBEAM -> CollectiveFamily.THOUGHTBEAM; else -> CollectiveFamily.SCALAR_LAB },
            worldMode = r.worldMode, collectiveMode = r.collectiveMode, labXMode = r.labXMode, classicMode = r.classicMode, scalarMode = r.scalarMode,
            listeningPath = r.listeningPath, requestedSampleRate = h.requestedSampleRate, carrierHz = r.carrierHz, presence = normalizedPresence,
            elfRateHz = r.elfRateHz, elfDepth = r.elfDepth, targetAngleDeg = r.targetAngleDeg, nullAngleDeg = r.nullAngleDeg, spacingMm = r.spacingMm,
            ditherDeg = r.ditherDeg, ditherRateHz = r.ditherRateHz, headWidthCm = r.headWidthCm, listenerDistanceCm = r.listenerDistanceCm
        )
        return StackRenderLayer(r.label, r.family, cfg, r.resonanceMode, r.matrixMode)
    }

    fun selectedModeLabel(s: OmegaUiState = _state.value): String = when (s.family) {
        OmegaFamily.WORLD_BEAM -> s.worldMode.label; OmegaFamily.PERCEPTION_LAB -> s.collectiveMode.label; OmegaFamily.LAB_X -> s.labXMode.label; OmegaFamily.THOUGHTBEAM -> s.classicMode.label; OmegaFamily.SCALAR_LAB -> s.scalarMode.label; OmegaFamily.RESONANCE_LAB -> s.resonanceMode.label; OmegaFamily.MATRIX_LAB -> s.matrixMode.label
    }

    private fun queryName(uri: Uri): String { var c: Cursor? = null; return try { c = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null); if (c != null && c.moveToFirst()) c.getString(0) ?: "Selected audio" else "Selected audio" } catch (_: Throwable) { "Selected audio" } finally { c?.close() } }
    private fun formatDuration(seconds: Double?): String { if (seconds == null || !seconds.isFinite() || seconds < 0) return "stream length unknown"; val total = seconds.roundToInt(); val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60; return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec) }
    private fun launchUi(message: String, block: suspend () -> Unit) { job?.cancel(); _state.value = _state.value.copy(busy = true, status = message); job = scope.launch { try { block() } catch (t: Throwable) { fail(t) } } }
    private fun fail(t: Throwable) { _state.value = _state.value.copy(busy = false, playing = false, stackPlaying = false, exporting = false, backgroundActive = false, status = "Error: ${t.message ?: t.javaClass.simpleName}") }
}

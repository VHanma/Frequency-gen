package com.vaan.ultracarrier

import android.content.Context
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import com.vaan.ultracarrier.audio.AudioFileDecoder
import com.vaan.ultracarrier.audio.AudioHardwareChecker
import com.vaan.ultracarrier.audio.AudioTransmitter
import com.vaan.ultracarrier.audio.GodXAudioTransmitter
import com.vaan.ultracarrier.audio.GodXMode
import com.vaan.ultracarrier.audio.GodXReport
import com.vaan.ultracarrier.audio.HardwareMode
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.audio.PcmAudio
import com.vaan.ultracarrier.audio.ThoughtMode
import com.vaan.ultracarrier.audio.TransmissionReport
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

enum class GodXFamily(val label: String) {
    EXPERIMENTAL("Experimental Lab X"),
    THOUGHTBEAM("ThoughtBeam Classic")
}

data class GodXUiState(
    val text: String = "",
    val loadedName: String? = null,
    val loadedAudio: PcmAudio? = null,
    val hardware: HardwareMode? = null,
    val family: GodXFamily = GodXFamily.EXPERIMENTAL,
    val mode: GodXMode = GodXMode.VOICE_OF_GOD_STACK,
    val classicMode: ThoughtMode = ThoughtMode.CENTER_LOCK,
    val listeningPath: ListeningPath = ListeningPath.PHONE_SPEAKER,
    val presence: Float = 0.36f,
    val modulationHz: Float = 7.83f,
    val modulationDepth: Float = 0.30f,
    val beatHz: Float = 7f,
    val baseHz: Float = 220f,
    val microDelayUs: Float = 180f,
    val motionRateHz: Float = 0.18f,
    val carrierHz: Float = 18_000f,
    val steeringAngleDeg: Float = 0f,
    val transducerSpacingMm: Float = 8.5f,
    val chirpSweepHz: Float = 4_000f,
    val chirpPeriodMs: Float = 20f,
    val clickRateHz: Float = 18f,
    val clickWidthMs: Float = 1.2f,
    val loopEnabled: Boolean = true,
    val isBusy: Boolean = false,
    val isTransmitting: Boolean = false,
    val status: String = "Prepare text or choose a file. Loop is on by default.",
    val experimentReport: GodXReport? = null,
    val classicReport: TransmissionReport? = null
)

class GodXController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decoder = AudioFileDecoder(appContext.contentResolver)
    private val tts = TtsSynthesizer(appContext)
    private val hardwareChecker = AudioHardwareChecker(appContext)
    private val experimentTransmitter = GodXAudioTransmitter()
    private val classicTransmitter = AudioTransmitter()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var transmitJob: Job? = null
    private var sessionCounter = 0L

    private val _state = MutableStateFlow(GodXUiState())
    val state: StateFlow<GodXUiState> = _state.asStateFlow()

    private val _waveform = MutableStateFlow(FloatArray(0))
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    init {
        hardwareChecker.start { hardware ->
            val old = _state.value
            _state.value = old.copy(
                hardware = hardware,
                carrierHz = profileCarrier(old.classicMode, hardware),
                status = if (old.isTransmitting) old.status else routeStatus(old.listeningPath, hardware)
            )
        }
    }

    fun setText(v: String) { _state.value = _state.value.copy(text = v) }
    fun setFamily(v: GodXFamily) { _state.value = _state.value.copy(family = v, status = v.label) }
    fun setMode(v: GodXMode) { _state.value = _state.value.copy(mode = v, status = v.description) }
    fun setPath(v: ListeningPath) {
        val old = _state.value
        _state.value = old.copy(listeningPath = v, status = old.hardware?.let { routeStatus(v, it) } ?: v.description)
    }
    fun setPresence(v: Float) { _state.value = _state.value.copy(presence = v.coerceIn(0.05f, 1f)) }
    fun setModulationHz(v: Float) { _state.value = _state.value.copy(modulationHz = v.coerceIn(1f, 120f)) }
    fun setModulationDepth(v: Float) { _state.value = _state.value.copy(modulationDepth = v.coerceIn(0f, 0.90f)) }
    fun setBeatHz(v: Float) { _state.value = _state.value.copy(beatHz = v.coerceIn(1f, 60f)) }
    fun setBaseHz(v: Float) { _state.value = _state.value.copy(baseHz = v.coerceIn(120f, 900f)) }
    fun setMicroDelay(v: Float) { _state.value = _state.value.copy(microDelayUs = v.coerceIn(0f, 650f)) }
    fun setMotionRate(v: Float) { _state.value = _state.value.copy(motionRateHz = v.coerceIn(0.03f, 4f)) }

    fun setClassicMode(v: ThoughtMode) {
        val old = _state.value
        _state.value = old.copy(
            classicMode = v,
            carrierHz = old.hardware?.let { profileCarrier(v, it) } ?: old.carrierHz,
            status = v.description
        )
    }

    fun setCarrier(v: Float) {
        val h = _state.value.hardware ?: return
        _state.value = _state.value.copy(carrierHz = v.coerceIn(h.carrierMinHz, h.carrierMaxHz))
    }
    fun setSteering(v: Float) { _state.value = _state.value.copy(steeringAngleDeg = v.coerceIn(-60f, 60f)) }
    fun setSpacing(v: Float) { _state.value = _state.value.copy(transducerSpacingMm = v.coerceIn(1f, 50f)) }
    fun setChirpSweep(v: Float) { _state.value = _state.value.copy(chirpSweepHz = v.coerceIn(100f, 12_000f)) }
    fun setChirpPeriod(v: Float) { _state.value = _state.value.copy(chirpPeriodMs = v.coerceIn(2f, 250f)) }
    fun setClickRate(v: Float) { _state.value = _state.value.copy(clickRateHz = v.coerceIn(2f, 40f)) }
    fun setClickWidth(v: Float) { _state.value = _state.value.copy(clickWidthMs = v.coerceIn(0.3f, 4f)) }

    fun setLoopEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(
            loopEnabled = enabled,
            status = if (enabled) "Loop enabled. Playback repeats until Stop." else "Loop disabled. Playback plays once."
        )
    }

    fun loadFile(uri: Uri) {
        scope.launch {
            setBusy("Reading and decoding selected audio…")
            try {
                runCatching {
                    appContext.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val audio = withContext(Dispatchers.IO) { decoder.decodeUri(uri) }
                val name = queryName(uri)
                _waveform.value = preview(audio.samples)
                _state.value = _state.value.copy(
                    loadedAudio = audio,
                    loadedName = name,
                    isBusy = false,
                    status = "Ready: $name${if (_state.value.loopEnabled) " • loops until Stop" else ""}"
                )
            } catch (t: Throwable) { fail(t) }
        }
    }

    fun prepareText() {
        val text = _state.value.text.trim()
        if (text.isBlank()) {
            _state.value = _state.value.copy(status = "Enter text first.")
            return
        }
        scope.launch {
            setBusy("Turning text into speech…")
            try {
                val file = withContext(Dispatchers.IO) { tts.synthesize(text) }
                val audio = withContext(Dispatchers.IO) { decoder.decodeFile(file) }
                file.delete()
                _waveform.value = preview(audio.samples)
                _state.value = _state.value.copy(
                    loadedAudio = audio,
                    loadedName = "Voice: ${text.take(36)}",
                    isBusy = false,
                    status = "Speech ready${if (_state.value.loopEnabled) " • loops until Stop" else ""}"
                )
            } catch (t: Throwable) { fail(t) }
        }
    }

    fun play() {
        val audio = _state.value.loadedAudio ?: run {
            _state.value = _state.value.copy(status = "Prepare text or choose a file first.")
            return
        }
        start(audio)
    }

    fun stop() {
        sessionCounter++
        experimentTransmitter.stop()
        classicTransmitter.stop()
        transmitJob?.cancel()
        transmitJob = null
        _state.value = _state.value.copy(isBusy = false, isTransmitting = false, status = "Playback stopped")
    }

    fun setListeningVolume() {
        val state = _state.value
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val fraction = when (state.listeningPath) {
            ListeningPath.HEADPHONES -> 0.25f
            ListeningPath.BONE_CONDUCTION -> 0.20f
            ListeningPath.PHONE_SPEAKER -> 0.52f
            ListeningPath.EXTERNAL_ARRAY -> 0.35f
        }
        val target = (max * fraction).roundToInt().coerceIn(1, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        _state.value = state.copy(status = "Listening volume set to ${(fraction * 100).roundToInt()}%.")
    }

    private fun start(audio: PcmAudio) {
        val snapshot = _state.value
        val hardware = snapshot.hardware ?: run {
            _state.value = snapshot.copy(status = "Audio hardware is still initializing.")
            return
        }
        if (snapshot.listeningPath != ListeningPath.PHONE_SPEAKER && !hardware.external) {
            _state.value = snapshot.copy(status = "Connect the selected audio output, then try again.")
            return
        }

        stopTransmittersOnly()
        sessionCounter++
        val sessionId = sessionCounter
        val sourceName = snapshot.loadedName ?: "selected audio"
        _state.value = snapshot.copy(
            isBusy = true,
            isTransmitting = true,
            experimentReport = null,
            classicReport = null,
            status = "Starting signal…"
        )

        transmitJob = scope.launch(Dispatchers.IO) {
            try {
                var pass = 0
                do {
                    pass++
                    val current = _state.value
                    if (current.family == GodXFamily.EXPERIMENTAL) {
                        experimentTransmitter.transmit(
                            pcm = audio,
                            requestedSampleRate = hardware.requestedSampleRate,
                            mode = current.mode,
                            listeningPath = current.listeningPath,
                            presence = current.presence,
                            modulationHz = current.modulationHz,
                            modulationDepth = current.modulationDepth,
                            beatHz = current.beatHz,
                            baseHz = current.baseHz,
                            microDelayUs = current.microDelayUs,
                            motionRateHz = current.motionRateHz,
                            preferredDevice = hardware.outputDevice,
                            onStarted = { report ->
                                if (sessionId == sessionCounter) {
                                    _state.value = _state.value.copy(
                                        isBusy = false,
                                        isTransmitting = true,
                                        experimentReport = report,
                                        classicReport = null,
                                        status = "${report.mode.label}${if (_state.value.loopEnabled) " • loop $pass" else ""} • ${current.listeningPath.label}"
                                    )
                                }
                            },
                            onWaveform = { if (sessionId == sessionCounter) _waveform.value = it }
                        )
                    } else {
                        classicTransmitter.transmit(
                            pcm = audio,
                            requestedSampleRate = hardware.requestedSampleRate,
                            requestedCarrierHz = current.carrierHz,
                            depth = current.presence,
                            thoughtMode = current.classicMode,
                            listeningPath = current.listeningPath,
                            steeringAngleDeg = current.steeringAngleDeg,
                            transducerSpacingMm = current.transducerSpacingMm,
                            chirpSweepHz = current.chirpSweepHz,
                            chirpPeriodMs = current.chirpPeriodMs,
                            clickRateHz = current.clickRateHz,
                            clickWidthMs = current.clickWidthMs,
                            preferredDevice = hardware.outputDevice,
                            onStarted = { report ->
                                if (sessionId == sessionCounter) {
                                    _state.value = _state.value.copy(
                                        isBusy = false,
                                        isTransmitting = true,
                                        classicReport = report,
                                        experimentReport = null,
                                        status = "${report.thoughtMode.label}${if (_state.value.loopEnabled) " • loop $pass" else ""} • ${report.listeningPath.label}"
                                    )
                                }
                            },
                            onWaveform = { if (sessionId == sessionCounter) _waveform.value = it }
                        )
                    }
                    if (sessionId != sessionCounter) return@launch
                } while (_state.value.loopEnabled)

                if (sessionId == sessionCounter) {
                    _state.value = _state.value.copy(isBusy = false, isTransmitting = false, status = "Finished playing $sourceName")
                }
            } catch (t: Throwable) {
                if (sessionId == sessionCounter) fail(t)
            }
        }
    }

    private fun stopTransmittersOnly() {
        experimentTransmitter.stop()
        classicTransmitter.stop()
        transmitJob?.cancel()
        transmitJob = null
    }

    private fun profileCarrier(mode: ThoughtMode, hardware: HardwareMode): Float {
        val min = hardware.carrierMinHz
        val max = hardware.carrierMaxHz
        return when (mode) {
            ThoughtMode.INNER_VOICE,
            ThoughtMode.CENTER_LOCK,
            ThoughtMode.FREY_ACOUSTIC_SIM,
            ThoughtMode.MASKED_WHISPER,
            ThoughtMode.BONE_TAP -> 14_500f.coerceIn(min, max)
            ThoughtMode.PATENT_SSB, ThoughtMode.FM_SLOPE -> 14_500f.coerceIn(min, max)
            ThoughtMode.BEAM_WHISPER -> (max - 250f).coerceIn(min, max)
            ThoughtMode.AIR_HETERODYNE, ThoughtMode.ARRAY_STEER, ThoughtMode.CHIRP_CARRIER -> {
                val target = if (max >= 39_500f) 39_000f else max - 500f
                target.coerceIn(min, max)
            }
        }
    }

    private fun routeStatus(path: ListeningPath, hardware: HardwareMode): String = when (path) {
        ListeningPath.HEADPHONES -> if (hardware.external) "Headphone route detected. Stereo experiments are active." else "Connect headphones for stereo-only experiments."
        ListeningPath.BONE_CONDUCTION -> if (hardware.external) "Bone-conduction route detected." else "Connect your bone-conduction headset."
        ListeningPath.PHONE_SPEAKER -> "Phone speaker selected. Stereo cues collapse to mono; modulation and monaural modes remain active. ${hardware.detail}"
        ListeningPath.EXTERNAL_ARRAY -> if (hardware.external) "External array route detected." else "Connect a stereo USB DAC or external array driver."
    }

    private fun preview(samples: FloatArray, target: Int = 512): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        if (samples.size <= target) return samples.copyOf()
        val out = FloatArray(target)
        val step = samples.size.toDouble() / target
        for (i in out.indices) out[i] = samples[(i * step).toInt().coerceAtMost(samples.lastIndex)]
        return out
    }

    private fun setBusy(message: String) { _state.value = _state.value.copy(isBusy = true, status = message) }
    private fun fail(t: Throwable) {
        _state.value = _state.value.copy(isBusy = false, isTransmitting = false, status = "Could not use that source: ${t.message ?: t::class.java.simpleName}")
    }

    private fun queryName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = appContext.contentResolver.query(uri, null, null, null, null)
            val index = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
            if (cursor?.moveToFirst() == true && index >= 0) cursor.getString(index) else "Selected audio"
        } finally { cursor?.close() }
    }

    override fun close() {
        sessionCounter++
        stopTransmittersOnly()
        hardwareChecker.stop()
        tts.close()
        scope.cancel()
    }
}

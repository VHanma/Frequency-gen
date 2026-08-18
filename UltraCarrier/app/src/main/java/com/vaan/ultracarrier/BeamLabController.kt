package com.vaan.ultracarrier

import android.content.Context
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import com.vaan.ultracarrier.audio.AudioFileDecoder
import com.vaan.ultracarrier.audio.AudioHardwareChecker
import com.vaan.ultracarrier.audio.AudioTransmitter
import com.vaan.ultracarrier.audio.BeamLabAudioTransmitter
import com.vaan.ultracarrier.audio.BeamLabMode
import com.vaan.ultracarrier.audio.BeamLabReport
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

enum class BeamFamily(val label: String) {
    BEAM_LAB("Beam Lab"),
    LAB_X("Lab X"),
    THOUGHTBEAM("ThoughtBeam Classic")
}

data class BeamLabUiState(
    val text: String = "",
    val loadedName: String? = null,
    val loadedAudio: PcmAudio? = null,
    val hardware: HardwareMode? = null,
    val family: BeamFamily = BeamFamily.BEAM_LAB,
    val beamMode: BeamLabMode = BeamLabMode.ELF_BEAM,
    val labXMode: GodXMode = GodXMode.VOICE_OF_GOD_STACK,
    val classicMode: ThoughtMode = ThoughtMode.CENTER_LOCK,
    val listeningPath: ListeningPath = ListeningPath.PHONE_SPEAKER,
    val presence: Float = 0.42f,
    val carrierHz: Float = 18_000f,
    val elfRateHz: Float = 7.83f,
    val elfDepth: Float = 0.36f,
    val targetAngleDeg: Float = 0f,
    val nullAngleDeg: Float = 35f,
    val spacingMm: Float = 8.5f,
    val beamDitherDeg: Float = 2f,
    val ditherRateHz: Float = 0.25f,
    val speakerSeparationCm: Float = 18f,
    val listenerDistanceCm: Float = 45f,
    val headWidthCm: Float = 15.5f,
    val modulationHz: Float = 7.83f,
    val modulationDepth: Float = 0.30f,
    val beatHz: Float = 7f,
    val baseHz: Float = 220f,
    val microDelayUs: Float = 180f,
    val motionRateHz: Float = 0.18f,
    val steeringAngleDeg: Float = 0f,
    val transducerSpacingMm: Float = 8.5f,
    val chirpSweepHz: Float = 4_000f,
    val chirpPeriodMs: Float = 20f,
    val clickRateHz: Float = 18f,
    val clickWidthMs: Float = 1.2f,
    val loopEnabled: Boolean = true,
    val isBusy: Boolean = false,
    val isTransmitting: Boolean = false,
    val status: String = "ELF Beam ready. Prepare text or choose a file.",
    val beamReport: BeamLabReport? = null,
    val labXReport: GodXReport? = null,
    val classicReport: TransmissionReport? = null
)

class BeamLabController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decoder = AudioFileDecoder(appContext.contentResolver)
    private val tts = TtsSynthesizer(appContext)
    private val hardwareChecker = AudioHardwareChecker(appContext)
    private val beamTransmitter = BeamLabAudioTransmitter()
    private val labXTransmitter = GodXAudioTransmitter()
    private val classicTransmitter = AudioTransmitter()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var transmitJob: Job? = null
    private var sessionCounter = 0L

    private val _state = MutableStateFlow(BeamLabUiState())
    val state: StateFlow<BeamLabUiState> = _state.asStateFlow()
    private val _waveform = MutableStateFlow(FloatArray(0))
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    init {
        hardwareChecker.start { hardware ->
            val old = _state.value
            _state.value = old.copy(
                hardware = hardware,
                carrierHz = beamCarrier(hardware),
                status = if (old.isTransmitting) old.status else routeStatus(old.listeningPath, hardware)
            )
        }
    }

    fun setText(v: String) { _state.value = _state.value.copy(text = v) }
    fun setFamily(v: BeamFamily) { _state.value = _state.value.copy(family = v, status = v.label) }
    fun setBeamMode(v: BeamLabMode) { _state.value = _state.value.copy(beamMode = v, status = v.description) }
    fun setLabXMode(v: GodXMode) { _state.value = _state.value.copy(labXMode = v, status = v.description) }
    fun setClassicMode(v: ThoughtMode) { _state.value = _state.value.copy(classicMode = v, status = v.description) }
    fun setPath(v: ListeningPath) {
        val old = _state.value
        _state.value = old.copy(listeningPath = v, status = old.hardware?.let { routeStatus(v, it) } ?: v.description)
    }
    fun setPresence(v: Float) { _state.value = _state.value.copy(presence = v.coerceIn(0.05f, 1f)) }
    fun setCarrier(v: Float) {
        val h = _state.value.hardware ?: return
        _state.value = _state.value.copy(carrierHz = v.coerceIn(h.carrierMinHz, h.carrierMaxHz))
    }
    fun setElfRate(v: Float) { _state.value = _state.value.copy(elfRateHz = v.coerceIn(0.5f, 40f)) }
    fun setElfDepth(v: Float) { _state.value = _state.value.copy(elfDepth = v.coerceIn(0f, 0.95f)) }
    fun setTargetAngle(v: Float) {
        val old = _state.value
        var nullAngle = old.nullAngleDeg
        if (kotlin.math.abs(v - nullAngle) < 3f) nullAngle = (v + 15f).coerceIn(-75f, 75f)
        _state.value = old.copy(targetAngleDeg = v.coerceIn(-60f, 60f), nullAngleDeg = nullAngle)
    }
    fun setNullAngle(v: Float) {
        val old = _state.value
        var value = v.coerceIn(-75f, 75f)
        if (kotlin.math.abs(value - old.targetAngleDeg) < 3f) value = (old.targetAngleDeg + 15f).coerceIn(-75f, 75f)
        _state.value = old.copy(nullAngleDeg = value)
    }
    fun setSpacing(v: Float) { _state.value = _state.value.copy(spacingMm = v.coerceIn(1f, 50f)) }
    fun setBeamDither(v: Float) { _state.value = _state.value.copy(beamDitherDeg = v.coerceIn(0f, 12f)) }
    fun setDitherRate(v: Float) { _state.value = _state.value.copy(ditherRateHz = v.coerceIn(0.03f, 3f)) }
    fun setSpeakerSeparation(v: Float) { _state.value = _state.value.copy(speakerSeparationCm = v.coerceIn(4f, 200f)) }
    fun setListenerDistance(v: Float) { _state.value = _state.value.copy(listenerDistanceCm = v.coerceIn(10f, 400f)) }
    fun setHeadWidth(v: Float) { _state.value = _state.value.copy(headWidthCm = v.coerceIn(10f, 24f)) }

    fun setModulationHz(v: Float) { _state.value = _state.value.copy(modulationHz = v.coerceIn(1f, 120f)) }
    fun setModulationDepth(v: Float) { _state.value = _state.value.copy(modulationDepth = v.coerceIn(0f, 0.90f)) }
    fun setBeatHz(v: Float) { _state.value = _state.value.copy(beatHz = v.coerceIn(1f, 60f)) }
    fun setBaseHz(v: Float) { _state.value = _state.value.copy(baseHz = v.coerceIn(120f, 900f)) }
    fun setMicroDelay(v: Float) { _state.value = _state.value.copy(microDelayUs = v.coerceIn(0f, 650f)) }
    fun setMotionRate(v: Float) { _state.value = _state.value.copy(motionRateHz = v.coerceIn(0.03f, 4f)) }
    fun setSteering(v: Float) { _state.value = _state.value.copy(steeringAngleDeg = v.coerceIn(-60f, 60f)) }
    fun setClassicSpacing(v: Float) { _state.value = _state.value.copy(transducerSpacingMm = v.coerceIn(1f, 50f)) }
    fun setChirpSweep(v: Float) { _state.value = _state.value.copy(chirpSweepHz = v.coerceIn(100f, 12_000f)) }
    fun setChirpPeriod(v: Float) { _state.value = _state.value.copy(chirpPeriodMs = v.coerceIn(2f, 250f)) }
    fun setClickRate(v: Float) { _state.value = _state.value.copy(clickRateHz = v.coerceIn(2f, 40f)) }
    fun setClickWidth(v: Float) { _state.value = _state.value.copy(clickWidthMs = v.coerceIn(0.3f, 4f)) }
    fun setLoopEnabled(v: Boolean) { _state.value = _state.value.copy(loopEnabled = v, status = if (v) "Loop enabled until Stop." else "Loop disabled.") }

    fun loadFile(uri: Uri) {
        scope.launch {
            setBusy("Decoding selected audio…")
            try {
                runCatching { appContext.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val audio = withContext(Dispatchers.IO) { decoder.decodeUri(uri) }
                val name = queryName(uri)
                _waveform.value = preview(audio.samples)
                _state.value = _state.value.copy(loadedAudio = audio, loadedName = name, isBusy = false, status = "Ready: $name")
            } catch (t: Throwable) { fail(t) }
        }
    }

    fun prepareText() {
        val text = _state.value.text.trim()
        if (text.isBlank()) { _state.value = _state.value.copy(status = "Enter text first."); return }
        scope.launch {
            setBusy("Turning text into speech…")
            try {
                val file = withContext(Dispatchers.IO) { tts.synthesize(text) }
                val audio = withContext(Dispatchers.IO) { decoder.decodeFile(file) }
                file.delete()
                _waveform.value = preview(audio.samples)
                _state.value = _state.value.copy(loadedAudio = audio, loadedName = "Voice: ${text.take(36)}", isBusy = false, status = "Speech ready")
            } catch (t: Throwable) { fail(t) }
        }
    }

    fun play() {
        val audio = _state.value.loadedAudio ?: run { _state.value = _state.value.copy(status = "Prepare text or choose a file first."); return }
        start(audio)
    }

    fun stop() {
        sessionCounter++
        stopOnly()
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
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * fraction).roundToInt().coerceIn(1, max), AudioManager.FLAG_SHOW_UI)
        _state.value = state.copy(status = "Listening volume set to ${(fraction * 100).roundToInt()}%.")
    }

    private fun start(audio: PcmAudio) {
        val snapshot = _state.value
        val hardware = snapshot.hardware ?: run { _state.value = snapshot.copy(status = "Audio hardware is still initializing."); return }
        if (snapshot.listeningPath != ListeningPath.PHONE_SPEAKER && !hardware.external) {
            _state.value = snapshot.copy(status = "Connect the selected audio output, then try again.")
            return
        }
        stopOnly()
        sessionCounter++
        val session = sessionCounter
        _state.value = snapshot.copy(isBusy = true, isTransmitting = true, beamReport = null, labXReport = null, classicReport = null, status = "Starting ${snapshot.family.label}…")
        transmitJob = scope.launch(Dispatchers.IO) {
            try {
                var pass = 0
                do {
                    pass++
                    val s = _state.value
                    when (s.family) {
                        BeamFamily.BEAM_LAB -> beamTransmitter.transmit(
                            pcm = audio,
                            requestedSampleRate = hardware.requestedSampleRate,
                            requestedCarrierHz = s.carrierHz,
                            mode = s.beamMode,
                            listeningPath = s.listeningPath,
                            presence = s.presence,
                            elfRateHz = s.elfRateHz,
                            elfDepth = s.elfDepth,
                            targetAngleDeg = s.targetAngleDeg,
                            nullAngleDeg = s.nullAngleDeg,
                            spacingMm = s.spacingMm,
                            beamDitherDeg = s.beamDitherDeg,
                            ditherRateHz = s.ditherRateHz,
                            speakerSeparationCm = s.speakerSeparationCm,
                            listenerDistanceCm = s.listenerDistanceCm,
                            headWidthCm = s.headWidthCm,
                            preferredDevice = hardware.outputDevice,
                            onStarted = { r -> if (session == sessionCounter) _state.value = _state.value.copy(isBusy = false, isTransmitting = true, beamReport = r, status = "${r.mode.label}${if (_state.value.loopEnabled) " • loop $pass" else ""}") },
                            onWaveform = { if (session == sessionCounter) _waveform.value = it }
                        )

                        BeamFamily.LAB_X -> labXTransmitter.transmit(
                            pcm = audio,
                            requestedSampleRate = hardware.requestedSampleRate,
                            mode = s.labXMode,
                            listeningPath = s.listeningPath,
                            presence = s.presence,
                            modulationHz = s.modulationHz,
                            modulationDepth = s.modulationDepth,
                            beatHz = s.beatHz,
                            baseHz = s.baseHz,
                            microDelayUs = s.microDelayUs,
                            motionRateHz = s.motionRateHz,
                            preferredDevice = hardware.outputDevice,
                            onStarted = { r -> if (session == sessionCounter) _state.value = _state.value.copy(isBusy = false, isTransmitting = true, labXReport = r, status = "${r.mode.label}${if (_state.value.loopEnabled) " • loop $pass" else ""}") },
                            onWaveform = { if (session == sessionCounter) _waveform.value = it }
                        )

                        BeamFamily.THOUGHTBEAM -> classicTransmitter.transmit(
                            pcm = audio,
                            requestedSampleRate = hardware.requestedSampleRate,
                            requestedCarrierHz = s.carrierHz,
                            depth = s.presence,
                            thoughtMode = s.classicMode,
                            listeningPath = s.listeningPath,
                            steeringAngleDeg = s.steeringAngleDeg,
                            transducerSpacingMm = s.transducerSpacingMm,
                            chirpSweepHz = s.chirpSweepHz,
                            chirpPeriodMs = s.chirpPeriodMs,
                            clickRateHz = s.clickRateHz,
                            clickWidthMs = s.clickWidthMs,
                            preferredDevice = hardware.outputDevice,
                            onStarted = { r -> if (session == sessionCounter) _state.value = _state.value.copy(isBusy = false, isTransmitting = true, classicReport = r, status = "${r.thoughtMode.label}${if (_state.value.loopEnabled) " • loop $pass" else ""}") },
                            onWaveform = { if (session == sessionCounter) _waveform.value = it }
                        )
                    }
                    if (session != sessionCounter) return@launch
                } while (_state.value.loopEnabled)
                if (session == sessionCounter) _state.value = _state.value.copy(isBusy = false, isTransmitting = false, status = "Finished")
            } catch (t: Throwable) { if (session == sessionCounter) fail(t) }
        }
    }

    private fun stopOnly() {
        beamTransmitter.stop(); labXTransmitter.stop(); classicTransmitter.stop(); transmitJob?.cancel(); transmitJob = null
    }

    private fun beamCarrier(h: HardwareMode): Float {
        val target = if (h.carrierMaxHz >= 39_500f) 39_000f else h.carrierMaxHz - 300f
        return target.coerceIn(h.carrierMinHz, h.carrierMaxHz)
    }

    private fun routeStatus(path: ListeningPath, h: HardwareMode): String = when (path) {
        ListeningPath.EXTERNAL_ARRAY -> if (h.external) "External array detected. Beam Lab can produce real directional output." else "Connect an external ultrasonic array / high-rate DAC for real beam modes."
        ListeningPath.PHONE_SPEAKER -> "Phone speaker selected. Beam Lab runs as an encoding preview; a phone speaker cannot form the narrow ultrasonic zone. ${h.detail}"
        ListeningPath.HEADPHONES -> if (h.external) "Headphones detected." else "Connect headphones."
        ListeningPath.BONE_CONDUCTION -> if (h.external) "Bone-conduction route detected." else "Connect bone-conduction audio."
    }

    private fun preview(samples: FloatArray, target: Int = 512): FloatArray {
        if (samples.size <= target) return samples.copyOf()
        val out = FloatArray(target); val step = samples.size.toDouble() / target
        for (i in out.indices) out[i] = samples[(i * step).toInt().coerceAtMost(samples.lastIndex)]
        return out
    }
    private fun setBusy(msg: String) { _state.value = _state.value.copy(isBusy = true, status = msg) }
    private fun fail(t: Throwable) { _state.value = _state.value.copy(isBusy = false, isTransmitting = false, status = "Could not play: ${t.message ?: t::class.java.simpleName}") }
    private fun queryName(uri: Uri): String {
        var c: Cursor? = null
        return try {
            c = appContext.contentResolver.query(uri, null, null, null, null)
            val i = c?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
            if (c?.moveToFirst() == true && i >= 0) c.getString(i) else "Selected audio"
        } finally { c?.close() }
    }

    override fun close() { sessionCounter++; stopOnly(); hardwareChecker.stop(); tts.close(); scope.cancel() }
}

package com.vaan.ultracarrier

import android.content.Context
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import com.vaan.ultracarrier.audio.AudioFileDecoder
import com.vaan.ultracarrier.audio.AudioHardwareChecker
import com.vaan.ultracarrier.audio.AudioTransmitter
import com.vaan.ultracarrier.audio.GodAudioTransmitter
import com.vaan.ultracarrier.audio.GodMode
import com.vaan.ultracarrier.audio.GodTransmissionReport
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

enum class EngineFamily(val label: String) {
    GOD_LAYER("Voice of God Lab"),
    THOUGHTBEAM("ThoughtBeam Classic")
}

data class GodUiState(
    val text: String = "",
    val loadedName: String? = null,
    val loadedAudio: PcmAudio? = null,
    val hardware: HardwareMode? = null,
    val family: EngineFamily = EngineFamily.GOD_LAYER,
    val godMode: GodMode = GodMode.VOICE_OF_GOD_STACK,
    val classicMode: ThoughtMode = ThoughtMode.CENTER_LOCK,
    val listeningPath: ListeningPath = ListeningPath.PHONE_SPEAKER,
    val presence: Float = 0.36f,
    val elfRateHz: Float = 7.83f,
    val elfDepth: Float = 0.28f,
    val binauralBeatHz: Float = 7f,
    val binauralBaseHz: Float = 220f,
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
    val godReport: GodTransmissionReport? = null,
    val classicReport: TransmissionReport? = null
)

class GodController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decoder = AudioFileDecoder(appContext.contentResolver)
    private val tts = TtsSynthesizer(appContext)
    private val hardwareChecker = AudioHardwareChecker(appContext)
    private val classicTransmitter = AudioTransmitter()
    private val godTransmitter = GodAudioTransmitter()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var transmitJob: Job? = null
    private var sessionCounter = 0L

    private val _state = MutableStateFlow(GodUiState())
    val state: StateFlow<GodUiState> = _state.asStateFlow()

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

    fun setText(value: String) { _state.value = _state.value.copy(text = value) }
    fun setFamily(value: EngineFamily) { _state.value = _state.value.copy(family = value, status = value.label) }
    fun setGodMode(value: GodMode) { _state.value = _state.value.copy(godMode = value, status = value.description) }

    fun setClassicMode(value: ThoughtMode) {
        val old = _state.value
        _state.value = old.copy(
            classicMode = value,
            carrierHz = old.hardware?.let { profileCarrier(value, it) } ?: old.carrierHz,
            status = value.description
        )
    }

    fun setListeningPath(value: ListeningPath) {
        val old = _state.value
        _state.value = old.copy(
            listeningPath = value,
            status = old.hardware?.let { routeStatus(value, it) } ?: value.description
        )
    }

    fun setPresence(value: Float) { _state.value = _state.value.copy(presence = value.coerceIn(0.05f, 1f)) }
    fun setElfRate(value: Float) { _state.value = _state.value.copy(elfRateHz = value.coerceIn(1f, 40f)) }
    fun setElfDepth(value: Float) { _state.value = _state.value.copy(elfDepth = value.coerceIn(0f, 0.80f)) }
    fun setBinauralBeat(value: Float) { _state.value = _state.value.copy(binauralBeatHz = value.coerceIn(1f, 40f)) }
    fun setBinauralBase(value: Float) { _state.value = _state.value.copy(binauralBaseHz = value.coerceIn(120f, 900f)) }
    fun setMicroDelay(value: Float) { _state.value = _state.value.copy(microDelayUs = value.coerceIn(0f, 650f)) }
    fun setMotionRate(value: Float) { _state.value = _state.value.copy(motionRateHz = value.coerceIn(0.03f, 2f)) }

    fun setCarrier(value: Float) {
        val hardware = _state.value.hardware ?: return
        _state.value = _state.value.copy(carrierHz = value.coerceIn(hardware.carrierMinHz, hardware.carrierMaxHz))
    }

    fun setSteeringAngle(value: Float) { _state.value = _state.value.copy(steeringAngleDeg = value.coerceIn(-60f, 60f)) }
    fun setTransducerSpacing(value: Float) { _state.value = _state.value.copy(transducerSpacingMm = value.coerceIn(1f, 50f)) }
    fun setChirpSweep(value: Float) { _state.value = _state.value.copy(chirpSweepHz = value.coerceIn(100f, 12_000f)) }
    fun setChirpPeriod(value: Float) { _state.value = _state.value.copy(chirpPeriodMs = value.coerceIn(2f, 250f)) }
    fun setClickRate(value: Float) { _state.value = _state.value.copy(clickRateHz = value.coerceIn(2f, 40f)) }
    fun setClickWidth(value: Float) { _state.value = _state.value.copy(clickWidthMs = value.coerceIn(0.3f, 4f)) }

    fun setLoopEnabled(enabled: Boolean) {
        val old = _state.value
        _state.value = old.copy(
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
                    status = "Ready: $name${if (_state.value.loopEnabled) " • loop until Stop" else ""}"
                )
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    fun synthesizeAndPrepare() {
        val text = _state.value.text.trim()
        if (text.isBlank()) {
            _state.value = _state.value.copy(status = "Enter text first.")
            return
        }
        scope.launch {
            setBusy("Turning your text into speech…")
            try {
                val file = withContext(Dispatchers.IO) { tts.synthesize(text) }
                val audio = withContext(Dispatchers.IO) { decoder.decodeFile(file) }
                file.delete()
                _waveform.value = preview(audio.samples)
                _state.value = _state.value.copy(
                    loadedAudio = audio,
                    loadedName = "Voice: ${text.take(36)}",
                    isBusy = false,
                    status = "Speech ready${if (_state.value.loopEnabled) " • loop until Stop" else ""}"
                )
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    fun play() {
        val audio = _state.value.loadedAudio
        if (audio == null) {
            _state.value = _state.value.copy(status = "Prepare text or choose a file first.")
            return
        }
        startTransmission(audio)
    }

    fun stop() {
        sessionCounter++
        godTransmitter.stop()
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

    private fun startTransmission(audio: PcmAudio) {
        val snapshot = _state.value
        val hardware = snapshot.hardware ?: run {
            _state.value = snapshot.copy(status = "Audio hardware is still initializing.")
            return
        }
        if (snapshot.listeningPath != ListeningPath.PHONE_SPEAKER && !hardware.external) {
            _state.value = snapshot.copy(status = "Connect the selected audio output, then try again.")
            return
        }

        godTransmitter.stop()
        classicTransmitter.stop()
        sessionCounter++
        val sessionId = sessionCounter
        val sourceName = snapshot.loadedName ?: "selected audio"
        _state.value = snapshot.copy(
            isBusy = true,
            isTransmitting = true,
            godReport = null,
            classicReport = null,
            status = "Starting ${if (snapshot.family == EngineFamily.GOD_LAYER) snapshot.godMode.label else snapshot.classicMode.label}…"
        )

        transmitJob = scope.launch(Dispatchers.IO) {
            try {
                var pass = 0
                do {
                    pass++
                    val current = _state.value
                    if (current.family == EngineFamily.GOD_LAYER) {
                        godTransmitter.transmit(
                            pcm = audio,
                            requestedSampleRate = hardware.requestedSampleRate,
                            mode = current.godMode,
                            listeningPath = current.listeningPath,
                            presence = current.presence,
                            elfRateHz = current.elfRateHz,
                            elfDepth = current.elfDepth,
                            binauralBeatHz = current.binauralBeatHz,
                            binauralBaseHz = current.binauralBaseHz,
                            microDelayUs = current.microDelayUs,
                            motionRateHz = current.motionRateHz,
                            preferredDevice = hardware.outputDevice,
                            onStarted = { report ->
                                if (sessionId == sessionCounter) {
                                    _state.value = _state.value.copy(
                                        isBusy = false,
                                        isTransmitting = true,
                                        godReport = report,
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
                                        godReport = null,
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
                    _state.value = _state.value.copy(
                        isBusy = false,
                        isTransmitting = false,
                        status = "Finished playing $sourceName"
                    )
                }
            } catch (error: Throwable) {
                if (sessionId == sessionCounter) fail(error)
            }
        }
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
        ListeningPath.HEADPHONES -> if (hardware.external) {
            "Headphone route detected. Stereo psychoacoustic modes are fully active."
        } else {
            "Connect headphones for Binaural Core, Micro-Motion, and the full Voice of God Stack."
        }
        ListeningPath.BONE_CONDUCTION -> if (hardware.external) {
            "External route detected. Bone-conduction listening is ready."
        } else {
            "Connect your bone-conduction headset."
        }
        ListeningPath.PHONE_SPEAKER -> "Phone speaker selected. Stereo-only cues collapse to mono, but ELF Envelope and the centered voice remain active. ${hardware.detail}"
        ListeningPath.EXTERNAL_ARRAY -> if (hardware.external) {
            "External route detected. ThoughtBeam Air Heterodyne and Array Steer are available."
        } else {
            "Connect the external audio route for array modes."
        }
    }

    private fun preview(samples: FloatArray, target: Int = 512): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        if (samples.size <= target) return samples.copyOf()
        val output = FloatArray(target)
        val step = samples.size.toDouble() / target
        for (i in output.indices) output[i] = samples[(i * step).toInt().coerceAtMost(samples.lastIndex)]
        return output
    }

    private fun setBusy(message: String) { _state.value = _state.value.copy(isBusy = true, status = message) }

    private fun fail(error: Throwable) {
        _state.value = _state.value.copy(
            isBusy = false,
            isTransmitting = false,
            status = "Could not play that source: ${error.message ?: error::class.java.simpleName}"
        )
    }

    private fun queryName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = appContext.contentResolver.query(uri, null, null, null, null)
            val index = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
            if (cursor?.moveToFirst() == true && index >= 0) cursor.getString(index) else "Selected audio"
        } finally {
            cursor?.close()
        }
    }

    override fun close() {
        sessionCounter++
        godTransmitter.stop()
        classicTransmitter.stop()
        hardwareChecker.stop()
        tts.close()
        scope.cancel()
    }
}

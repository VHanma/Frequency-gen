package com.vaan.ultracarrier

import android.content.Context
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import com.vaan.ultracarrier.audio.AudioFileDecoder
import com.vaan.ultracarrier.audio.AudioHardwareChecker
import com.vaan.ultracarrier.audio.AudioTransmitter
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

data class AppUiState(
    val text: String = "",
    val loadedName: String? = null,
    val loadedAudio: PcmAudio? = null,
    val hardware: HardwareMode? = null,
    val carrierHz: Float = 18_000f,
    val depth: Float = 0.42f,
    val thoughtMode: ThoughtMode = ThoughtMode.INNER_VOICE,
    val listeningPath: ListeningPath = ListeningPath.PHONE_SPEAKER,
    val steeringAngleDeg: Float = 0f,
    val transducerSpacingMm: Float = 8.5f,
    val chirpSweepHz: Float = 4_000f,
    val chirpPeriodMs: Float = 20f,
    val status: String = "Prepare text or choose a file, then play through the phone or an external acoustic array.",
    val isBusy: Boolean = false,
    val isTransmitting: Boolean = false,
    val report: TransmissionReport? = null
)

class MainController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decoder = AudioFileDecoder(appContext.contentResolver)
    private val tts = TtsSynthesizer(appContext)
    private val hardwareChecker = AudioHardwareChecker(appContext)
    private val transmitter = AudioTransmitter()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var transmitJob: Job? = null

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val _waveform = MutableStateFlow(FloatArray(0))
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    init {
        hardwareChecker.start { hardware ->
            val old = _uiState.value
            _uiState.value = old.copy(
                hardware = hardware,
                carrierHz = profileCarrier(old.thoughtMode, hardware),
                status = if (old.isTransmitting) old.status else routeStatus(old.listeningPath, hardware)
            )
        }
    }

    fun setText(value: String) {
        _uiState.value = _uiState.value.copy(text = value)
    }

    fun setCarrier(value: Float) {
        val hardware = _uiState.value.hardware ?: return
        _uiState.value = _uiState.value.copy(carrierHz = value.coerceIn(hardware.carrierMinHz, hardware.carrierMaxHz))
    }

    fun setDepth(value: Float) {
        _uiState.value = _uiState.value.copy(depth = value.coerceIn(0.05f, 1f))
    }

    fun setSteeringAngle(value: Float) {
        _uiState.value = _uiState.value.copy(steeringAngleDeg = value.coerceIn(-60f, 60f))
    }

    fun setTransducerSpacing(value: Float) {
        _uiState.value = _uiState.value.copy(transducerSpacingMm = value.coerceIn(1f, 50f))
    }

    fun setChirpSweep(value: Float) {
        _uiState.value = _uiState.value.copy(chirpSweepHz = value.coerceIn(100f, 12_000f))
    }

    fun setChirpPeriod(value: Float) {
        _uiState.value = _uiState.value.copy(chirpPeriodMs = value.coerceIn(2f, 250f))
    }

    fun setThoughtMode(mode: ThoughtMode) {
        val old = _uiState.value
        val carrier = old.hardware?.let { profileCarrier(mode, it) } ?: old.carrierHz
        val depth = when (mode) {
            ThoughtMode.INNER_VOICE -> 0.38f
            ThoughtMode.PATENT_SSB -> 0.42f
            ThoughtMode.FM_SLOPE -> 0.35f
            ThoughtMode.BEAM_WHISPER -> 0.50f
            ThoughtMode.AIR_HETERODYNE -> 0.45f
            ThoughtMode.ARRAY_STEER -> 0.42f
            ThoughtMode.CHIRP_CARRIER -> 0.38f
        }
        _uiState.value = old.copy(
            thoughtMode = mode,
            carrierHz = carrier,
            depth = depth,
            status = mode.description
        )
    }

    fun setListeningPath(path: ListeningPath) {
        val old = _uiState.value
        val depth = when (path) {
            ListeningPath.HEADPHONES -> 0.38f
            ListeningPath.BONE_CONDUCTION -> 0.32f
            ListeningPath.PHONE_SPEAKER -> 0.50f
            ListeningPath.EXTERNAL_ARRAY -> 0.44f
        }
        _uiState.value = old.copy(
            listeningPath = path,
            depth = depth,
            carrierHz = old.hardware?.let { profileCarrier(old.thoughtMode, it) } ?: old.carrierHz,
            status = old.hardware?.let { routeStatus(path, it) } ?: path.description
        )
    }

    fun loadFile(uri: Uri) {
        scope.launch {
            setBusy("Reading and decoding selected audio…")
            try {
                runCatching {
                    appContext.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val audio = withContext(Dispatchers.IO) { decoder.decodeUri(uri) }
                val name = queryName(uri)
                _waveform.value = preview(audio.samples)
                _uiState.value = _uiState.value.copy(
                    loadedAudio = audio,
                    loadedName = name,
                    isBusy = false,
                    status = "Ready: $name. Tap PLAY ACOUSTIC SIGNAL."
                )
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    fun synthesizeAndPrepare() {
        val text = _uiState.value.text.trim()
        if (text.isBlank()) {
            _uiState.value = _uiState.value.copy(status = "Enter text first.")
            return
        }
        scope.launch {
            setBusy("Turning your text into speech…")
            try {
                val file = withContext(Dispatchers.IO) { tts.synthesize(text) }
                val audio = withContext(Dispatchers.IO) { decoder.decodeFile(file) }
                file.delete()
                val name = "Acoustic voice: ${text.take(36)}"
                _waveform.value = preview(audio.samples)
                _uiState.value = _uiState.value.copy(
                    loadedAudio = audio,
                    loadedName = name,
                    isBusy = false,
                    status = "Speech ready. Tap PLAY ACOUSTIC SIGNAL."
                )
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    fun transmitLoaded() {
        val audio = _uiState.value.loadedAudio
        if (audio == null) {
            _uiState.value = _uiState.value.copy(status = "Prepare text or choose a file first.")
            return
        }
        startTransmission(audio)
    }

    fun stopTransmission() {
        transmitter.stop()
        transmitJob?.cancel()
        transmitJob = null
        _uiState.value = _uiState.value.copy(isBusy = false, isTransmitting = false, status = "Playback stopped")
    }

    fun setSafeVolume() {
        val state = _uiState.value
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val fraction = when (state.listeningPath) {
            ListeningPath.HEADPHONES -> 0.25f
            ListeningPath.BONE_CONDUCTION -> 0.20f
            ListeningPath.PHONE_SPEAKER -> 0.52f
            ListeningPath.EXTERNAL_ARRAY -> 0.35f
        }
        val target = (max * fraction).roundToInt().coerceIn(1, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        _uiState.value = state.copy(status = "Acoustic output volume set to ${(fraction * 100).roundToInt()}%.")
    }

    private fun startTransmission(audio: PcmAudio) {
        val snapshot = _uiState.value
        val hardware = snapshot.hardware
        if (hardware == null) {
            _uiState.value = snapshot.copy(status = "Audio hardware is still initializing.")
            return
        }
        if (snapshot.listeningPath != ListeningPath.PHONE_SPEAKER && !hardware.external) {
            _uiState.value = snapshot.copy(status = "Connect the selected external audio device, then try again.")
            return
        }

        transmitter.stop()
        transmitJob?.cancel()
        transmitJob = null
        val sourceName = snapshot.loadedName ?: "selected audio"
        _uiState.value = snapshot.copy(
            isBusy = true,
            isTransmitting = true,
            report = null,
            status = "Rendering ${snapshot.thoughtMode.label}: $sourceName…"
        )
        transmitJob = scope.launch(Dispatchers.IO) {
            try {
                transmitter.transmit(
                    pcm = audio,
                    requestedSampleRate = hardware.requestedSampleRate,
                    requestedCarrierHz = snapshot.carrierHz,
                    depth = snapshot.depth,
                    thoughtMode = snapshot.thoughtMode,
                    listeningPath = snapshot.listeningPath,
                    steeringAngleDeg = snapshot.steeringAngleDeg,
                    transducerSpacingMm = snapshot.transducerSpacingMm,
                    chirpSweepHz = snapshot.chirpSweepHz,
                    chirpPeriodMs = snapshot.chirpPeriodMs,
                    preferredDevice = hardware.outputDevice,
                    onStarted = { report ->
                        _uiState.value = _uiState.value.copy(
                            isBusy = false,
                            isTransmitting = true,
                            report = report,
                            status = "${report.thoughtMode.label} playing through ${report.listeningPath.label}"
                        )
                    },
                    onWaveform = { _waveform.value = it }
                )
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    isTransmitting = false,
                    status = "Finished playing $sourceName"
                )
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun profileCarrier(mode: ThoughtMode, hardware: HardwareMode): Float {
        val min = hardware.carrierMinHz
        val max = hardware.carrierMaxHz
        return when (mode) {
            ThoughtMode.INNER_VOICE -> 14_500f.coerceIn(min, max)
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
            "Headphone route detected. Inner Voice remains available unchanged."
        } else {
            "Connect headphones for the centered listening profiles."
        }
        ListeningPath.BONE_CONDUCTION -> if (hardware.external) {
            "External route detected. Select your bone-conduction headset in Android audio output."
        } else {
            "Connect a bone-conduction headset before playing."
        }
        ListeningPath.PHONE_SPEAKER -> "Phone speaker selected. Inner Voice and Beam Whisper work best on the phone itself. ${hardware.detail}"
        ListeningPath.EXTERNAL_ARRAY -> if (hardware.external) {
            "External route detected. Use a 96 or 192 kHz USB DAC and an ultrasonic transducer driver for Air Heterodyne or Array Steer."
        } else {
            "Connect a stereo USB DAC or external ultrasonic array driver."
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

    private fun setBusy(message: String) {
        _uiState.value = _uiState.value.copy(isBusy = true, status = message)
    }

    private fun fail(error: Throwable) {
        _uiState.value = _uiState.value.copy(
            isBusy = false,
            isTransmitting = false,
            status = "Could not use that source: ${error.message ?: error::class.java.simpleName}"
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
        transmitter.stop()
        hardwareChecker.stop()
        tts.close()
        scope.cancel()
    }
}

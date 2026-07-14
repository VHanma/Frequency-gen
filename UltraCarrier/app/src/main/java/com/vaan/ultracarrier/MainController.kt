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
import com.vaan.ultracarrier.audio.ModulationMode
import com.vaan.ultracarrier.audio.PcmAudio
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
    val depth: Float = 0.75f,
    val modulationMode: ModulationMode = ModulationMode.AM,
    val status: String = "Ready",
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
        hardwareChecker.start { mode ->
            val old = _uiState.value
            val defaultCarrier = if (old.hardware == null) {
                if (mode.external) 30_000f else 18_000f
            } else old.carrierHz
            _uiState.value = old.copy(
                hardware = mode,
                carrierHz = defaultCarrier.coerceIn(mode.carrierMinHz, mode.carrierMaxHz),
                status = if (old.isTransmitting) old.status else mode.detail
            )
        }
    }

    fun setText(value: String) {
        _uiState.value = _uiState.value.copy(text = value)
    }

    fun setCarrier(value: Float) {
        val hardware = _uiState.value.hardware ?: return
        _uiState.value = _uiState.value.copy(
            carrierHz = value.coerceIn(hardware.carrierMinHz, hardware.carrierMaxHz)
        )
    }

    fun setDepth(value: Float) {
        _uiState.value = _uiState.value.copy(depth = value.coerceIn(0.05f, 1f))
    }

    fun setMode(mode: ModulationMode) {
        _uiState.value = _uiState.value.copy(modulationMode = mode)
    }

    fun loadFile(uri: Uri) {
        scope.launch {
            setBusy("Decoding selected audio…")
            try {
                runCatching {
                    appContext.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val audio = withContext(Dispatchers.IO) { decoder.decodeUri(uri) }
                val name = queryName(uri)
                _uiState.value = _uiState.value.copy(
                    loadedAudio = audio,
                    loadedName = name,
                    isBusy = false,
                    status = "Loaded $name, ${"%.1f".format(audio.durationSeconds)} seconds"
                )
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    fun synthesizeAndTransmit() {
        val text = _uiState.value.text
        if (text.isBlank()) {
            _uiState.value = _uiState.value.copy(status = "Enter text first.")
            return
        }
        scope.launch {
            setBusy("Synthesizing speech…")
            try {
                val file = withContext(Dispatchers.IO) { tts.synthesize(text) }
                val audio = withContext(Dispatchers.IO) { decoder.decodeFile(file) }
                file.delete()
                _uiState.value = _uiState.value.copy(
                    loadedAudio = audio,
                    loadedName = "Synthesized speech",
                    isBusy = false
                )
                startTransmission(audio)
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    fun transmitLoaded() {
        val audio = _uiState.value.loadedAudio
        if (audio == null) {
            _uiState.value = _uiState.value.copy(status = "Upload a file or synthesize text first.")
            return
        }
        startTransmission(audio)
    }

    fun stopTransmission() {
        transmitter.stop()
        transmitJob?.cancel()
        transmitJob = null
        _uiState.value = _uiState.value.copy(
            isBusy = false,
            isTransmitting = false,
            status = "Transmission stopped"
        )
    }

    fun setMediaVolumeMaximum() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
        _uiState.value = _uiState.value.copy(status = "Media stream volume set to the device maximum.")
    }

    private fun startTransmission(audio: PcmAudio) {
        val snapshot = _uiState.value
        val hardware = snapshot.hardware
        if (hardware == null) {
            _uiState.value = snapshot.copy(status = "Audio hardware is still initializing.")
            return
        }

        stopTransmission()
        _uiState.value = _uiState.value.copy(
            isBusy = true,
            isTransmitting = true,
            report = null,
            status = "Opening low-latency AudioTrack…"
        )
        transmitJob = scope.launch(Dispatchers.IO) {
            try {
                transmitter.transmit(
                    pcm = audio,
                    requestedSampleRate = hardware.requestedSampleRate,
                    requestedCarrierHz = snapshot.carrierHz,
                    depth = snapshot.depth,
                    mode = snapshot.modulationMode,
                    preferredDevice = hardware.outputDevice,
                    onStarted = { report ->
                        _uiState.value = _uiState.value.copy(
                            isBusy = false,
                            isTransmitting = true,
                            report = report,
                            status = "Transmitting at ${report.actualCarrierHz.roundToInt()} Hz carrier"
                        )
                    },
                    onWaveform = { _waveform.value = it }
                )
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    isTransmitting = false,
                    status = "Transmission complete"
                )
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun setBusy(message: String) {
        _uiState.value = _uiState.value.copy(isBusy = true, status = message)
    }

    private fun fail(error: Throwable) {
        _uiState.value = _uiState.value.copy(
            isBusy = false,
            isTransmitting = false,
            status = error.message ?: error::class.java.simpleName
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
        stopTransmission()
        hardwareChecker.stop()
        tts.close()
        scope.cancel()
    }
}

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
import com.vaan.ultracarrier.audio.PrivacyMode
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
    val carrierHz: Float = 21_000f,
    val depth: Float = 0.55f,
    val modulationMode: ModulationMode = ModulationMode.DSB_SC,
    val privacyMode: PrivacyMode = PrivacyMode.PHONE_BEAM,
    val status: String = "Load text or a file, aim the speaker, then transmit.",
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
            val carrier = profileCarrier(old.privacyMode, hardware)
            _uiState.value = old.copy(
                hardware = hardware,
                carrierHz = carrier,
                status = if (old.isTransmitting) old.status else profileStatus(old.privacyMode, hardware)
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

    fun setMode(mode: ModulationMode) {
        _uiState.value = _uiState.value.copy(modulationMode = mode)
    }

    fun setPrivacyMode(mode: PrivacyMode) {
        val old = _uiState.value
        val hardware = old.hardware
        val carrier = hardware?.let { profileCarrier(mode, it) } ?: old.carrierHz
        val depth = when (mode) {
            PrivacyMode.PHONE_BEAM -> 0.55f
            PrivacyMode.STANDARD -> 0.90f
            PrivacyMode.EXTERNAL_ARRAY -> 0.70f
        }
        val modulation = when (mode) {
            PrivacyMode.PHONE_BEAM -> ModulationMode.DSB_SC
            PrivacyMode.STANDARD -> ModulationMode.AM
            PrivacyMode.EXTERNAL_ARRAY -> ModulationMode.AM
        }
        _uiState.value = old.copy(
            privacyMode = mode,
            carrierHz = carrier,
            depth = depth,
            modulationMode = modulation,
            status = hardware?.let { profileStatus(mode, it) } ?: mode.description
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
                    status = "Ready: $name. Aim the speaker opening, then tap AIM & TRANSMIT."
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
            setBusy("Converting your text into speech audio…")
            try {
                val file = withContext(Dispatchers.IO) { tts.synthesize(text) }
                val audio = withContext(Dispatchers.IO) { decoder.decodeFile(file) }
                file.delete()
                val name = "Speech: ${text.take(36)}"
                _waveform.value = preview(audio.samples)
                _uiState.value = _uiState.value.copy(
                    loadedAudio = audio,
                    loadedName = name,
                    isBusy = false,
                    status = "Speech ready. Aim the speaker opening, then tap AIM & TRANSMIT."
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
        _uiState.value = _uiState.value.copy(isBusy = false, isTransmitting = false, status = "Transmission stopped")
    }

    fun setPrivacyVolume() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * 0.68f).roundToInt().coerceIn(1, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        _uiState.value = _uiState.value.copy(status = "Media volume set near 68% to reduce sideways leakage.")
    }

    private fun startTransmission(audio: PcmAudio) {
        val snapshot = _uiState.value
        val hardware = snapshot.hardware
        if (hardware == null) {
            _uiState.value = snapshot.copy(status = "Audio hardware is still initializing.")
            return
        }
        if (snapshot.privacyMode == PrivacyMode.EXTERNAL_ARRAY && !hardware.external) {
            _uiState.value = snapshot.copy(
                status = "External Array needs a USB audio output and ultrasonic transducer array."
            )
            return
        }

        transmitter.stop()
        transmitJob?.cancel()
        transmitJob = null
        val sourceName = snapshot.loadedName ?: "selected audio"
        _uiState.value = _uiState.value.copy(
            isBusy = true,
            isTransmitting = true,
            report = null,
            status = "Beam encoding $sourceName… Keep the speaker pointed at the target."
        )
        transmitJob = scope.launch(Dispatchers.IO) {
            try {
                transmitter.transmit(
                    pcm = audio,
                    requestedSampleRate = hardware.requestedSampleRate,
                    requestedCarrierHz = snapshot.carrierHz,
                    depth = snapshot.depth,
                    mode = snapshot.modulationMode,
                    privacyMode = snapshot.privacyMode,
                    preferredDevice = hardware.outputDevice,
                    onStarted = { report ->
                        _uiState.value = _uiState.value.copy(
                            isBusy = false,
                            isTransmitting = true,
                            report = report,
                            status = "${report.privacyMode.label}: $sourceName at ${report.actualCarrierHz.roundToInt()} Hz"
                        )
                    },
                    onWaveform = { _waveform.value = it }
                )
                _uiState.value = _uiState.value.copy(
                    isBusy = false,
                    isTransmitting = false,
                    status = "Finished transmitting $sourceName"
                )
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun profileCarrier(mode: PrivacyMode, hardware: HardwareMode): Float = when (mode) {
        PrivacyMode.PHONE_BEAM -> (hardware.carrierMaxHz - 250f).coerceAtLeast(hardware.carrierMinHz)
        PrivacyMode.STANDARD -> (if (hardware.external) 30_000f else 18_000f)
            .coerceIn(hardware.carrierMinHz, hardware.carrierMaxHz)
        PrivacyMode.EXTERNAL_ARRAY -> 38_000f.coerceIn(hardware.carrierMinHz, hardware.carrierMaxHz)
    }

    private fun profileStatus(mode: PrivacyMode, hardware: HardwareMode): String = when (mode) {
        PrivacyMode.PHONE_BEAM ->
            "Phone Beam active. ${hardware.detail} Software lowers leakage, but the phone speaker cannot form a perfectly private beam."
        PrivacyMode.STANDARD -> hardware.detail
        PrivacyMode.EXTERNAL_ARRAY -> if (hardware.external) {
            "External output detected. Connect an ultrasonic array for the narrowest beam."
        } else {
            "External Array selected, but no USB audio output is connected."
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

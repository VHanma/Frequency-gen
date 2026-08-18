package com.vaan.ultracarrier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaan.ultracarrier.audio.DspMath
import com.vaan.ultracarrier.audio.GodXMode
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.audio.ThoughtMode
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class GodXActivity : ComponentActivity() {
    private lateinit var controller: GodXController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = GodXController(applicationContext)
        setContent {
            val state by controller.state.collectAsState()
            val waveform by controller.waveform.collectAsState()
            GodXTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(controller::loadFile)
                }
                GodXScreen(
                    state = state,
                    waveform = waveform,
                    onText = controller::setText,
                    onPick = { picker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onFamily = controller::setFamily,
                    onMode = controller::setMode,
                    onClassicMode = controller::setClassicMode,
                    onPath = controller::setPath,
                    onPresence = controller::setPresence,
                    onModHz = controller::setModulationHz,
                    onModDepth = controller::setModulationDepth,
                    onBeat = controller::setBeatHz,
                    onBase = controller::setBaseHz,
                    onDelay = controller::setMicroDelay,
                    onMotion = controller::setMotionRate,
                    onCarrier = controller::setCarrier,
                    onSteering = controller::setSteering,
                    onSpacing = controller::setSpacing,
                    onChirpSweep = controller::setChirpSweep,
                    onChirpPeriod = controller::setChirpPeriod,
                    onClickRate = controller::setClickRate,
                    onClickWidth = controller::setClickWidth,
                    onLoop = controller::setLoopEnabled,
                    onPrepare = controller::prepareText,
                    onPlay = controller::play,
                    onStop = controller::stop,
                    onVolume = controller::setListeningVolume
                )
            }
        }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}

@Composable
private fun GodXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFFD778),
            secondary = Color(0xFF77E7FF),
            background = Color(0xFF080807),
            surface = Color(0xFF1B1913),
            onBackground = Color(0xFFFFFAEA),
            onSurface = Color(0xFFFFFAEA)
        ),
        content = content
    )
}

@Composable
private fun GodXScreen(
    state: GodXUiState,
    waveform: FloatArray,
    onText: (String) -> Unit,
    onPick: () -> Unit,
    onFamily: (GodXFamily) -> Unit,
    onMode: (GodXMode) -> Unit,
    onClassicMode: (ThoughtMode) -> Unit,
    onPath: (ListeningPath) -> Unit,
    onPresence: (Float) -> Unit,
    onModHz: (Float) -> Unit,
    onModDepth: (Float) -> Unit,
    onBeat: (Float) -> Unit,
    onBase: (Float) -> Unit,
    onDelay: (Float) -> Unit,
    onMotion: (Float) -> Unit,
    onCarrier: (Float) -> Unit,
    onSteering: (Float) -> Unit,
    onSpacing: (Float) -> Unit,
    onChirpSweep: (Float) -> Unit,
    onChirpPeriod: (Float) -> Unit,
    onClickRate: (Float) -> Unit,
    onClickWidth: (Float) -> Unit,
    onLoop: (Boolean) -> Unit,
    onPrepare: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onVolume: () -> Unit
) {
    val hardware = state.hardware
    val minCarrier = hardware?.carrierMinHz ?: 13_500f
    val maxCarrier = hardware?.carrierMaxHz ?: 22_000f
    val sideband = DspMath.safeMessageBandwidth(hardware?.requestedSampleRate ?: 48_000, state.carrierHz)
    val rawPhase = 360.0 * state.carrierHz * (state.transducerSpacingMm / 1000.0) *
        sin(state.steeringAngleDeg * PI / 180.0) / 343.0
    val phase = (((rawPhase + 180.0) % 360.0 + 360.0) % 360.0 - 180.0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ULTRACARRIER VOICE OF GOD LAB X", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("Expanded ThoughtBeam clone with low-frequency envelope, ASSR, beat, timing, phase, and classic acoustic experiments.", color = Color(0xFFD1C5A5))

        InfoCardX(hardware?.label ?: "Detecting audio route…", hardware?.detail ?: "Checking output hardware.")

        ControlCardX("Output path") {
            ListeningPath.entries.forEach { path ->
                FilterChip(selected = state.listeningPath == path, onClick = { onPath(path) }, label = { Text(path.label) })
            }
        }

        ControlCardX("Engine family") {
            GodXFamily.entries.forEach { family ->
                FilterChip(selected = state.family == family, onClick = { onFamily(family) }, label = { Text(family.label) })
            }
        }

        if (state.family == GodXFamily.EXPERIMENTAL) {
            ControlCardX("Experimental engine") {
                GodXMode.entries.forEach { mode ->
                    FilterChip(selected = state.mode == mode, onClick = { onMode(mode) }, label = { Text(mode.label) })
                }
                Text(state.mode.description, color = Color(0xFFD1C5A5))
            }
        } else {
            ControlCardX("ThoughtBeam engine") {
                ThoughtMode.entries.forEach { mode ->
                    FilterChip(selected = state.classicMode == mode, onClick = { onClassicMode(mode) }, label = { Text(mode.label) })
                }
                Text(state.classicMode.description, color = Color(0xFFD1C5A5))
            }
        }

        OutlinedTextField(
            value = state.text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth().height(138.dp),
            label = { Text("Text to encode") },
            placeholder = { Text("Type the phrase or voice…") }
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPrepare, enabled = !state.isBusy, modifier = Modifier.weight(1f)) { Text("Prepare Text") }
            OutlinedButton(onClick = onPick, enabled = !state.isBusy, modifier = Modifier.weight(1f)) { Text("Choose File") }
        }

        Text(state.loadedName?.let { "Ready source: $it" } ?: "Ready source: none", color = Color(0xFFE8DEBE))

        ControlCardX("Loop") {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (state.loopEnabled) "Loop until Stop" else "Play once", fontWeight = FontWeight.Bold)
                Switch(checked = state.loopEnabled, onCheckedChange = onLoop)
            }
        }

        Button(onClick = onPlay, enabled = state.loadedAudio != null && !state.isBusy, modifier = Modifier.fillMaxWidth().height(58.dp)) {
            Text(if (state.loopEnabled) "PLAY & LOOP" else "PLAY SIGNAL", fontWeight = FontWeight.Black)
        }

        ControlCardX("Presence") {
            Text("${(state.presence * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
            Slider(value = state.presence, onValueChange = onPresence, valueRange = 0.05f..1f)
        }

        if (state.family == GodXFamily.EXPERIMENTAL && state.mode in setOf(
                GodXMode.VOICE_OF_GOD_STACK,
                GodXMode.EMF_ENVELOPE,
                GodXMode.CROSS_FREQUENCY_NEST
            )
        ) {
            ControlCardX("Low-frequency / EMF-inspired envelope") {
                Text("Rate ${"%.2f".format(state.modulationHz)} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.modulationHz, onValueChange = onModHz, valueRange = 1f..120f)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7.83f, 10f, 16.67f, 25f).forEach { hz ->
                        FilterChip(selected = kotlin.math.abs(state.modulationHz - hz) < 0.05f, onClick = { onModHz(hz) }, label = { Text(if (hz == 7.83f || hz == 16.67f) "${hz} Hz" else "${hz.roundToInt()} Hz") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(40f, 50f, 60f, 80f).forEach { hz ->
                        FilterChip(selected = kotlin.math.abs(state.modulationHz - hz) < 0.05f, onClick = { onModHz(hz) }, label = { Text("${hz.roundToInt()} Hz") })
                    }
                }
                Text("Depth ${(state.modulationDepth * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
                Slider(value = state.modulationDepth, onValueChange = onModDepth, valueRange = 0f..0.90f)
                Text("These rates are mapped onto the sound envelope. EMF Pattern Scan automatically cycles its own preset bank.", color = Color(0xFFD1C5A5))
            }
        }

        if (state.family == GodXFamily.EXPERIMENTAL && state.mode in setOf(
                GodXMode.VOICE_OF_GOD_STACK,
                GodXMode.BINAURAL_CORE,
                GodXMode.MONAURAL_BEAT
            )
        ) {
            ControlCardX("Beat core") {
                Text("Difference ${"%.1f".format(state.beatHz)} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.beatHz, onValueChange = onBeat, valueRange = 1f..60f)
                Text("Base ${state.baseHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.baseHz, onValueChange = onBase, valueRange = 120f..900f)
            }
        }

        if (state.family == GodXFamily.EXPERIMENTAL && state.mode in setOf(
                GodXMode.VOICE_OF_GOD_STACK,
                GodXMode.MICRO_MOTION,
                GodXMode.PHASE_FLIP,
                GodXMode.COHERENCE_SNAP
            )
        ) {
            ControlCardX("Stereo timing / correlation") {
                Text("Maximum delay ${state.microDelayUs.roundToInt()} µs", fontWeight = FontWeight.Bold)
                Slider(value = state.microDelayUs, onValueChange = onDelay, valueRange = 0f..650f)
                Text("Motion / flip rate ${"%.2f".format(state.motionRateHz)} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.motionRateHz, onValueChange = onMotion, valueRange = 0.03f..4f)
            }
        }

        if (state.family == GodXFamily.THOUGHTBEAM && state.classicMode !in setOf(
                ThoughtMode.INNER_VOICE, ThoughtMode.CENTER_LOCK, ThoughtMode.FREY_ACOUSTIC_SIM, ThoughtMode.MASKED_WHISPER, ThoughtMode.BONE_TAP
            )
        ) {
            ControlCardX("Acoustic carrier") {
                Text("${state.carrierHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.carrierHz.coerceIn(minCarrier, maxCarrier), onValueChange = onCarrier, valueRange = minCarrier..maxCarrier)
                Text("Available message sideband ${sideband.roundToInt()} Hz", color = Color(0xFFD1C5A5))
            }
        }

        if (state.family == GodXFamily.THOUGHTBEAM && state.classicMode == ThoughtMode.ARRAY_STEER) {
            ControlCardX("Array steering") {
                Text("Aim ${state.steeringAngleDeg.roundToInt()}°", fontWeight = FontWeight.Bold)
                Slider(value = state.steeringAngleDeg, onValueChange = onSteering, valueRange = -60f..60f)
                Text("Spacing ${"%.1f".format(state.transducerSpacingMm)} mm", fontWeight = FontWeight.Bold)
                Slider(value = state.transducerSpacingMm, onValueChange = onSpacing, valueRange = 1f..50f)
                Text("Δφ = ${"%.1f".format(phase)}°", color = Color(0xFFD1C5A5))
            }
        }

        if (state.family == GodXFamily.THOUGHTBEAM && state.classicMode == ThoughtMode.CHIRP_CARRIER) {
            ControlCardX("Chirp carrier") {
                Text("Sweep ${state.chirpSweepHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.chirpSweepHz, onValueChange = onChirpSweep, valueRange = 100f..12_000f)
                Text("Period ${state.chirpPeriodMs.roundToInt()} ms", fontWeight = FontWeight.Bold)
                Slider(value = state.chirpPeriodMs, onValueChange = onChirpPeriod, valueRange = 2f..250f)
            }
        }

        if (state.family == GodXFamily.THOUGHTBEAM && state.classicMode in setOf(ThoughtMode.FREY_ACOUSTIC_SIM, ThoughtMode.BONE_TAP)) {
            ControlCardX("Click / tap packets") {
                Text("Rate ${state.clickRateHz.roundToInt()} / s", fontWeight = FontWeight.Bold)
                Slider(value = state.clickRateHz, onValueChange = onClickRate, valueRange = 2f..40f)
                Text("Width ${"%.1f".format(state.clickWidthMs)} ms", fontWeight = FontWeight.Bold)
                Slider(value = state.clickWidthMs, onValueChange = onClickWidth, valueRange = 0.3f..4f)
            }
        }

        ControlCardX("Source waveform") { WaveformX(waveform) }

        state.experimentReport?.let { r ->
            InfoCardX("Active ${r.mode.label}", "${r.sampleRate} Hz • modulation ${"%.2f".format(r.modulationHz)} Hz • beat ${"%.1f".format(r.beatHz)} Hz • delay ${r.microDelayUs.roundToInt()} µs • ${r.routeName}")
        }
        state.classicReport?.let { r ->
            InfoCardX("Active ${r.thoughtMode.label}", "${r.actualSampleRate} Hz • carrier ${r.actualCarrierHz.roundToInt()} Hz • ${r.listeningPath.label} • ${r.routedDeviceName}")
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onVolume, modifier = Modifier.weight(1f)) { Text("Listening Volume") }
            Button(onClick = onStop, enabled = state.isBusy || state.isTransmitting, modifier = Modifier.weight(1f)) { Text("Stop") }
        }

        InfoCardX("Status", state.status)
    }
}

@Composable
private fun InfoCardX(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1913))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFFD1C5A5))
        }
    }
}

@Composable
private fun ControlCardX(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1913))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun WaveformX(samples: FloatArray) {
    Box(modifier = Modifier.fillMaxWidth().height(140.dp).background(Color(0xFF0B0A08), RoundedCornerShape(12.dp)).padding(8.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(Color(0xFF4A4536), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f)
            if (samples.size > 1) {
                val path = Path()
                samples.forEachIndexed { index, sample ->
                    val x = index.toFloat() / (samples.size - 1) * size.width
                    val y = size.height / 2f - sample.coerceIn(-1f, 1f) * size.height * 0.45f
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color(0xFFFFD778), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
            }
        }
    }
}

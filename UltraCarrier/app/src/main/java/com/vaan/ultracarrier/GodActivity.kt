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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.vaan.ultracarrier.audio.GodMode
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.audio.ThoughtMode
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class GodActivity : ComponentActivity() {
    private lateinit var controller: GodController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = GodController(applicationContext)
        setContent {
            val state by controller.state.collectAsState()
            val waveform by controller.waveform.collectAsState()
            GodTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(controller::loadFile)
                }
                GodScreen(
                    state = state,
                    waveform = waveform,
                    onTextChanged = controller::setText,
                    onPickFile = { picker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onFamilyChanged = controller::setFamily,
                    onGodModeChanged = controller::setGodMode,
                    onClassicModeChanged = controller::setClassicMode,
                    onPathChanged = controller::setListeningPath,
                    onPresenceChanged = controller::setPresence,
                    onElfRateChanged = controller::setElfRate,
                    onElfDepthChanged = controller::setElfDepth,
                    onBeatChanged = controller::setBinauralBeat,
                    onBaseChanged = controller::setBinauralBase,
                    onDelayChanged = controller::setMicroDelay,
                    onMotionChanged = controller::setMotionRate,
                    onCarrierChanged = controller::setCarrier,
                    onSteeringChanged = controller::setSteeringAngle,
                    onSpacingChanged = controller::setTransducerSpacing,
                    onChirpSweepChanged = controller::setChirpSweep,
                    onChirpPeriodChanged = controller::setChirpPeriod,
                    onClickRateChanged = controller::setClickRate,
                    onClickWidthChanged = controller::setClickWidth,
                    onLoopChanged = controller::setLoopEnabled,
                    onPrepareText = controller::synthesizeAndPrepare,
                    onPlay = controller::play,
                    onStop = controller::stop,
                    onListeningVolume = controller::setListeningVolume
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
private fun GodTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFFD778),
            secondary = Color(0xFFA8D7FF),
            background = Color(0xFF090806),
            surface = Color(0xFF1B1811),
            onBackground = Color(0xFFFFF8E6),
            onSurface = Color(0xFFFFF8E6)
        ),
        content = content
    )
}

@Composable
private fun GodScreen(
    state: GodUiState,
    waveform: FloatArray,
    onTextChanged: (String) -> Unit,
    onPickFile: () -> Unit,
    onFamilyChanged: (EngineFamily) -> Unit,
    onGodModeChanged: (GodMode) -> Unit,
    onClassicModeChanged: (ThoughtMode) -> Unit,
    onPathChanged: (ListeningPath) -> Unit,
    onPresenceChanged: (Float) -> Unit,
    onElfRateChanged: (Float) -> Unit,
    onElfDepthChanged: (Float) -> Unit,
    onBeatChanged: (Float) -> Unit,
    onBaseChanged: (Float) -> Unit,
    onDelayChanged: (Float) -> Unit,
    onMotionChanged: (Float) -> Unit,
    onCarrierChanged: (Float) -> Unit,
    onSteeringChanged: (Float) -> Unit,
    onSpacingChanged: (Float) -> Unit,
    onChirpSweepChanged: (Float) -> Unit,
    onChirpPeriodChanged: (Float) -> Unit,
    onClickRateChanged: (Float) -> Unit,
    onClickWidthChanged: (Float) -> Unit,
    onLoopChanged: (Boolean) -> Unit,
    onPrepareText: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onListeningVolume: () -> Unit
) {
    val hardware = state.hardware
    val minCarrier = hardware?.carrierMinHz ?: 13_500f
    val maxCarrier = hardware?.carrierMaxHz ?: 22_000f
    val sideband = DspMath.safeMessageBandwidth(hardware?.requestedSampleRate ?: 48_000, state.carrierHz)
    val rawPhaseDegrees = 360.0 * state.carrierHz * (state.transducerSpacingMm / 1000.0) *
        sin(state.steeringAngleDeg * PI / 180.0) / 343.0
    val normalizedPhaseDegrees = (((rawPhaseDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ULTRACARRIER VOICE OF GOD LAB", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(
            "Clone of ThoughtBeam with extra psychoacoustic experiments for internal placement, motion, rhythmic envelopes, and private-direction playback.",
            color = Color(0xFFD1C5A5)
        )

        InfoCard(
            title = hardware?.label ?: "Detecting audio route…",
            body = hardware?.detail ?: "Checking speaker, headset, DAC, or external array."
        )

        ControlCard("Output path") {
            ListeningPath.entries.forEach { path ->
                FilterChip(
                    selected = state.listeningPath == path,
                    onClick = { onPathChanged(path) },
                    label = { Text(path.label) }
                )
            }
            Text(state.listeningPath.description, color = Color(0xFFD1C5A5))
        }

        ControlCard("Engine family") {
            EngineFamily.entries.forEach { family ->
                FilterChip(
                    selected = state.family == family,
                    onClick = { onFamilyChanged(family) },
                    label = { Text(family.label) }
                )
            }
        }

        if (state.family == EngineFamily.GOD_LAYER) {
            ControlCard("Psychoacoustic engine") {
                GodMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.godMode == mode,
                        onClick = { onGodModeChanged(mode) },
                        label = { Text(mode.label) }
                    )
                }
                Text(state.godMode.description, color = Color(0xFFD1C5A5))
            }
        } else {
            ControlCard("ThoughtBeam engine") {
                ThoughtMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.classicMode == mode,
                        onClick = { onClassicModeChanged(mode) },
                        label = { Text(mode.label) }
                    )
                }
                Text(state.classicMode.description, color = Color(0xFFD1C5A5))
            }
        }

        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth().height(140.dp),
            label = { Text("Text to encode") },
            placeholder = { Text("Type the phrase or voice…") }
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPrepareText, enabled = !state.isBusy, modifier = Modifier.weight(1f)) {
                Text("Prepare Text")
            }
            OutlinedButton(onClick = onPickFile, enabled = !state.isBusy, modifier = Modifier.weight(1f)) {
                Text("Choose File")
            }
        }

        Text(
            state.loadedName?.let { "Ready source: $it" } ?: "Ready source: none",
            color = Color(0xFFE7DDBE)
        )

        ControlCard("Continuous experiment") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Loop until Stop", fontWeight = FontWeight.Bold)
                    Text(if (state.loopEnabled) "Repeats continuously." else "Plays once.", color = Color(0xFFD1C5A5))
                }
                Switch(checked = state.loopEnabled, onCheckedChange = onLoopChanged)
            }
        }

        Button(
            onClick = onPlay,
            enabled = state.loadedAudio != null && !state.isBusy,
            modifier = Modifier.fillMaxWidth().height(58.dp)
        ) {
            Text(if (state.loopEnabled) "PLAY & LOOP" else "PLAY SIGNAL", fontWeight = FontWeight.Black)
        }

        ControlCard("Presence") {
            Text("${(state.presence * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
            Slider(value = state.presence, onValueChange = onPresenceChanged, valueRange = 0.05f..1f)
        }

        if (state.family == EngineFamily.GOD_LAYER &&
            (state.godMode == GodMode.VOICE_OF_GOD_STACK || state.godMode == GodMode.ELF_ENVELOPE)
        ) {
            ControlCard("ELF-rate acoustic envelope") {
                Text("Rate ${"%.2f".format(state.elfRateHz)} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.elfRateHz, onValueChange = onElfRateChanged, valueRange = 1f..40f)
                Text("Depth ${(state.elfDepth * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
                Slider(value = state.elfDepth, onValueChange = onElfDepthChanged, valueRange = 0f..0.80f)
                Text("This modulates ordinary sound amplitude at the selected low rate.", color = Color(0xFFD1C5A5))
            }
        }

        if (state.family == EngineFamily.GOD_LAYER &&
            (state.godMode == GodMode.VOICE_OF_GOD_STACK || state.godMode == GodMode.BINAURAL_CORE)
        ) {
            ControlCard("Binaural core") {
                Text("Beat difference ${"%.1f".format(state.binauralBeatHz)} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.binauralBeatHz, onValueChange = onBeatChanged, valueRange = 1f..40f)
                Text("Base tone ${state.binauralBaseHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.binauralBaseHz, onValueChange = onBaseChanged, valueRange = 120f..900f)
                Text("Headphones keep the left and right tones separated so the difference is generated binaurally.", color = Color(0xFFD1C5A5))
            }
        }

        if (state.family == EngineFamily.GOD_LAYER &&
            (state.godMode == GodMode.VOICE_OF_GOD_STACK || state.godMode == GodMode.MICRO_MOTION)
        ) {
            ControlCard("Intracranial micro-motion") {
                Text("Maximum delay ${state.microDelayUs.roundToInt()} µs", fontWeight = FontWeight.Bold)
                Slider(value = state.microDelayUs, onValueChange = onDelayChanged, valueRange = 0f..650f)
                Text("Motion rate ${"%.2f".format(state.motionRateHz)} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.motionRateHz, onValueChange = onMotionChanged, valueRange = 0.03f..2f)
                Text("Uses changing interaural timing rather than left/right volume panning.", color = Color(0xFFD1C5A5))
            }
        }

        if (state.family == EngineFamily.THOUGHTBEAM &&
            state.classicMode !in setOf(
                ThoughtMode.INNER_VOICE,
                ThoughtMode.CENTER_LOCK,
                ThoughtMode.FREY_ACOUSTIC_SIM,
                ThoughtMode.MASKED_WHISPER,
                ThoughtMode.BONE_TAP
            )
        ) {
            ControlCard("Acoustic carrier") {
                Text("${state.carrierHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(
                    value = state.carrierHz.coerceIn(minCarrier, maxCarrier),
                    onValueChange = onCarrierChanged,
                    valueRange = minCarrier..maxCarrier
                )
                Text("Available message sideband ${sideband.roundToInt()} Hz", color = Color(0xFFD1C5A5))
            }
        }

        if (state.family == EngineFamily.THOUGHTBEAM &&
            (state.classicMode == ThoughtMode.FREY_ACOUSTIC_SIM || state.classicMode == ThoughtMode.BONE_TAP)
        ) {
            ControlCard("Click / tap packets") {
                Text("Rate ${state.clickRateHz.roundToInt()} per second", fontWeight = FontWeight.Bold)
                Slider(value = state.clickRateHz, onValueChange = onClickRateChanged, valueRange = 2f..40f)
                Text("Width ${"%.1f".format(state.clickWidthMs)} ms", fontWeight = FontWeight.Bold)
                Slider(value = state.clickWidthMs, onValueChange = onClickWidthChanged, valueRange = 0.3f..4f)
            }
        }

        if (state.family == EngineFamily.THOUGHTBEAM && state.classicMode == ThoughtMode.ARRAY_STEER) {
            ControlCard("Array steering") {
                Text("Aim ${state.steeringAngleDeg.roundToInt()}°", fontWeight = FontWeight.Bold)
                Slider(value = state.steeringAngleDeg, onValueChange = onSteeringChanged, valueRange = -60f..60f)
                Text("Spacing ${"%.1f".format(state.transducerSpacingMm)} mm", fontWeight = FontWeight.Bold)
                Slider(value = state.transducerSpacingMm, onValueChange = onSpacingChanged, valueRange = 1f..50f)
                Text("Δφ = ${"%.1f".format(normalizedPhaseDegrees)}°", color = Color(0xFFD1C5A5))
            }
        }

        if (state.family == EngineFamily.THOUGHTBEAM && state.classicMode == ThoughtMode.CHIRP_CARRIER) {
            ControlCard("Chirp carrier") {
                Text("Sweep ${state.chirpSweepHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.chirpSweepHz, onValueChange = onChirpSweepChanged, valueRange = 100f..12_000f)
                Text("Period ${state.chirpPeriodMs.roundToInt()} ms", fontWeight = FontWeight.Bold)
                Slider(value = state.chirpPeriodMs, onValueChange = onChirpPeriodChanged, valueRange = 2f..250f)
            }
        }

        ControlCard("Source waveform") { WaveformCanvas(waveform) }

        state.godReport?.let { report ->
            InfoCard(
                "Active ${report.mode.label}",
                "${report.sampleRate} Hz • output ${(report.outputGain * 100).roundToInt()}% • ELF ${"%.2f".format(report.elfRateHz)} Hz • beat ${"%.1f".format(report.binauralBeatHz)} Hz • delay ${report.microDelayUs.roundToInt()} µs • ${report.routeName}"
            )
        }

        state.classicReport?.let { report ->
            InfoCard(
                "Active ${report.thoughtMode.label}",
                buildString {
                    append("${report.actualSampleRate} Hz")
                    if (report.actualCarrierHz > 0f) append(" • ${report.actualCarrierHz.roundToInt()} Hz carrier")
                    append(" • ${(report.outputGain * 100).roundToInt()}% output")
                    append(" • ${report.listeningPath.label}")
                }
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onListeningVolume, modifier = Modifier.weight(1f)) { Text("Listening Volume") }
            Button(onClick = onStop, enabled = state.isTransmitting || state.isBusy, modifier = Modifier.weight(1f)) { Text("Stop") }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF292317)), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(state.status)
            }
        }

        Text(
            "Quick experiments: Voice of God Stack + headphones for the full stereo illusion; ELF Envelope for phone-speaker rhythmic modulation; Coherence Snap for sudden center-lock moments; ThoughtBeam Air Heterodyne or Array Steer for the external directional-audio path.",
            color = Color(0xFF9F957C),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1811))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFFD1C5A5))
        }
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1811))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun WaveformCanvas(samples: FloatArray) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(145.dp)
            .background(Color(0xFF0C0A07), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = Color(0xFF4A402C),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 1f
            )
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

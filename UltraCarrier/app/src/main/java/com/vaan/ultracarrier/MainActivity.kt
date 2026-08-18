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
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.audio.ThoughtMode
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private lateinit var controller: MainController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = MainController(applicationContext)
        setContent {
            val state by controller.uiState.collectAsState()
            val waveform by controller.waveform.collectAsState()
            ThoughtBeamTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(controller::loadFile)
                }
                ThoughtBeamScreen(
                    state = state,
                    waveform = waveform,
                    onTextChanged = controller::setText,
                    onPickFile = { picker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onCarrierChanged = controller::setCarrier,
                    onDepthChanged = controller::setDepth,
                    onThoughtModeChanged = controller::setThoughtMode,
                    onListeningPathChanged = controller::setListeningPath,
                    onSteeringAngleChanged = controller::setSteeringAngle,
                    onTransducerSpacingChanged = controller::setTransducerSpacing,
                    onChirpSweepChanged = controller::setChirpSweep,
                    onChirpPeriodChanged = controller::setChirpPeriod,
                    onClickRateChanged = controller::setClickRate,
                    onClickWidthChanged = controller::setClickWidth,
                    onLoopChanged = controller::setLoopEnabled,
                    onPrepareText = controller::synthesizeAndPrepare,
                    onTransmit = controller::transmitLoaded,
                    onStop = controller::stopTransmission,
                    onSafeVolume = controller::setSafeVolume
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
private fun ThoughtBeamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8DF7DF),
            secondary = Color(0xFFBCA8FF),
            background = Color(0xFF050B0D),
            surface = Color(0xFF102023),
            onBackground = Color(0xFFF0FFFB),
            onSurface = Color(0xFFF0FFFB)
        ),
        content = content
    )
}

@Composable
private fun ThoughtBeamScreen(
    state: AppUiState,
    waveform: FloatArray,
    onTextChanged: (String) -> Unit,
    onPickFile: () -> Unit,
    onCarrierChanged: (Float) -> Unit,
    onDepthChanged: (Float) -> Unit,
    onThoughtModeChanged: (ThoughtMode) -> Unit,
    onListeningPathChanged: (ListeningPath) -> Unit,
    onSteeringAngleChanged: (Float) -> Unit,
    onTransducerSpacingChanged: (Float) -> Unit,
    onChirpSweepChanged: (Float) -> Unit,
    onChirpPeriodChanged: (Float) -> Unit,
    onClickRateChanged: (Float) -> Unit,
    onClickWidthChanged: (Float) -> Unit,
    onLoopChanged: (Boolean) -> Unit,
    onPrepareText: () -> Unit,
    onTransmit: () -> Unit,
    onStop: () -> Unit,
    onSafeVolume: () -> Unit
) {
    val hardware = state.hardware
    val minCarrier = hardware?.carrierMinHz ?: 13_500f
    val maxCarrier = hardware?.carrierMaxHz ?: 22_000f
    val predictedBandwidth = DspMath.safeMessageBandwidth(
        hardware?.requestedSampleRate ?: 48_000,
        state.carrierHz
    )
    val rawPhaseDegrees = 360.0 * state.carrierHz * (state.transducerSpacingMm / 1000.0) *
        sin(state.steeringAngleDeg * PI / 180.0) / 343.0
    val normalizedPhaseDegrees = (((rawPhaseDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0)
    val directMode = state.thoughtMode in setOf(
        ThoughtMode.INNER_VOICE,
        ThoughtMode.CENTER_LOCK,
        ThoughtMode.FREY_ACOUSTIC_SIM,
        ThoughtMode.MASKED_WHISPER,
        ThoughtMode.BONE_TAP
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ULTRACARRIER THOUGHTBEAM", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(
            "Self-listening experiment: make your chosen voice feel internally placed while reducing nearby audible spill.",
            color = Color(0xFFA9CBC4)
        )

        InfoCard(
            title = hardware?.label ?: "Detecting audio route…",
            body = hardware?.detail ?: "Checking the phone speaker, headset, DAC, or external array."
        )

        ControlCard(title = "Output path") {
            ListeningPath.entries.forEach { path ->
                FilterChip(
                    selected = state.listeningPath == path,
                    onClick = { onListeningPathChanged(path) },
                    label = { Text(path.label) }
                )
            }
            Text(state.listeningPath.description, color = Color(0xFFA9CBC4))
        }

        ControlCard(title = "Thought placement engine") {
            ThoughtMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.thoughtMode == mode,
                    onClick = { onThoughtModeChanged(mode) },
                    label = { Text(mode.label) }
                )
            }
            Text(state.thoughtMode.description, color = Color(0xFFA9CBC4))
        }

        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth().height(140.dp),
            label = { Text("Text to encode") },
            placeholder = { Text("Type the voice, thought, reflection, or affirmation…") }
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
            color = Color(0xFFD1EAE4)
        )

        ControlCard(title = "Continuous experiment") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Loop until Stop", fontWeight = FontWeight.Bold)
                    Text(
                        if (state.loopEnabled) "The exact prepared text or file repeats continuously." else "The source plays once.",
                        color = Color(0xFFA9CBC4)
                    )
                }
                Switch(checked = state.loopEnabled, onCheckedChange = onLoopChanged)
            }
        }

        Button(
            onClick = onTransmit,
            enabled = state.loadedAudio != null && !state.isBusy,
            modifier = Modifier.fillMaxWidth().height(58.dp)
        ) {
            Text(if (state.loopEnabled) "PLAY & LOOP" else "PLAY SIGNAL", fontWeight = FontWeight.Black)
        }

        ControlCard(title = "Presence") {
            Text("${(state.depth * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
            Slider(value = state.depth, onValueChange = onDepthChanged, valueRange = 0.05f..1f)
            Text(
                "Raise gradually. Lower settings blend more softly; higher settings make the selected texture more obvious.",
                color = Color(0xFFA9CBC4)
            )
        }

        if (state.thoughtMode == ThoughtMode.FREY_ACOUSTIC_SIM || state.thoughtMode == ThoughtMode.BONE_TAP) {
            ControlCard(title = "Internal click texture") {
                Text("Packet rate ${state.clickRateHz.roundToInt()} per second", fontWeight = FontWeight.Bold)
                Slider(value = state.clickRateHz, onValueChange = onClickRateChanged, valueRange = 2f..40f)
                Text("Packet width ${"%.1f".format(state.clickWidthMs)} ms", fontWeight = FontWeight.Bold)
                Slider(value = state.clickWidthMs, onValueChange = onClickWidthChanged, valueRange = 0.3f..4f)
                Text(
                    "These are ordinary zero-DC acoustic packets layered into your source. Shorter widths feel sharper; slower rates feel more separated.",
                    color = Color(0xFFA9CBC4)
                )
            }
        }

        if (!directMode) {
            ControlCard(title = "Acoustic carrier") {
                Text("${state.carrierHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(
                    value = state.carrierHz.coerceIn(minCarrier, maxCarrier),
                    onValueChange = onCarrierChanged,
                    valueRange = minCarrier..maxCarrier
                )
                Text(
                    "Route range ${minCarrier.roundToInt()}–${maxCarrier.roundToInt()} Hz. Available message sideband ${predictedBandwidth.roundToInt()} Hz.",
                    color = Color(0xFFA9CBC4)
                )
            }
        }

        if (state.thoughtMode == ThoughtMode.ARRAY_STEER) {
            ControlCard(title = "Privacy direction") {
                Text("Aim angle ${state.steeringAngleDeg.roundToInt()}°", fontWeight = FontWeight.Bold)
                Slider(value = state.steeringAngleDeg, onValueChange = onSteeringAngleChanged, valueRange = -60f..60f)
                Text("Transducer spacing ${"%.1f".format(state.transducerSpacingMm)} mm", fontWeight = FontWeight.Bold)
                Slider(value = state.transducerSpacingMm, onValueChange = onTransducerSpacingChanged, valueRange = 1f..50f)
                Text(
                    "Δφ = 2π f d sin(θ) / c = ${"%.1f".format(normalizedPhaseDegrees)}°. Left and right channels receive this relative carrier phase.",
                    color = Color(0xFFA9CBC4)
                )
                Text(
                    "Element spacing near or below half a wavelength reduces unwanted side beams.",
                    color = Color(0xFFA9CBC4)
                )
            }
        }

        if (state.thoughtMode == ThoughtMode.CHIRP_CARRIER) {
            ControlCard(title = "Chirp carrier") {
                Text("Sweep ${state.chirpSweepHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.chirpSweepHz, onValueChange = onChirpSweepChanged, valueRange = 100f..12_000f)
                Text("Period ${state.chirpPeriodMs.roundToInt()} ms", fontWeight = FontWeight.Bold)
                Slider(value = state.chirpPeriodMs, onValueChange = onChirpPeriodChanged, valueRange = 2f..250f)
                Text(
                    "The acoustic carrier sweeps around the selected center frequency.",
                    color = Color(0xFFA9CBC4)
                )
            }
        }

        if (state.thoughtMode == ThoughtMode.AIR_HETERODYNE ||
            state.thoughtMode == ThoughtMode.ARRAY_STEER ||
            state.thoughtMode == ThoughtMode.CHIRP_CARRIER
        ) {
            InfoCard(
                title = "External privacy hardware",
                body = "A phone speaker cannot create a true parametric beam. For the narrowest one-person zone, use External Ultrasonic Array with a high-sample-rate USB DAC, appropriate amplifier, and ultrasonic transducers."
            )
        }

        ControlCard(title = "Source waveform") {
            WaveformCanvas(waveform)
        }

        state.report?.let { report ->
            InfoCard(
                title = "Active ${report.thoughtMode.label}",
                body = buildString {
                    append("${report.actualSampleRate} Hz PCM float")
                    if (report.actualCarrierHz > 0f) append(" • ${report.actualCarrierHz.roundToInt()} Hz carrier")
                    append(" • ${report.messageBandwidthHz.roundToInt()} Hz message band")
                    append(" • output ${(report.outputGain * 100).roundToInt()}%")
                    if (report.clickRateHz > 0f) append(" • ${report.clickRateHz.roundToInt()} packets/s")
                    if (report.clickWidthMs > 0f) append(" • ${"%.1f".format(report.clickWidthMs)} ms packet")
                    if (report.thoughtMode == ThoughtMode.ARRAY_STEER) {
                        append(" • phase ${"%.1f".format(report.arrayPhaseDegrees)}°")
                    }
                    if (report.thoughtMode == ThoughtMode.CHIRP_CARRIER) {
                        append(" • sweep ${report.chirpSweepHz.roundToInt()} Hz")
                    }
                    append(" • ${report.listeningPath.label}")
                    append(" • ${report.routedDeviceName}")
                }
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onSafeVolume, modifier = Modifier.weight(1f)) {
                Text("Safe Volume")
            }
            Button(onClick = onStop, enabled = state.isTransmitting || state.isBusy, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF13282A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(state.status)
            }
        }

        Text(
            "Best internal placement: Center Lock or Inner Voice with headphones. Best contact sensation: Bone Tap with bone conduction. Best phone-only experiment: Cranial Click or Masked Whisper. Best nearby privacy: Air Heterodyne or Stereo Array Steer with a real external ultrasonic array. Keep volume comfortable and stop if you notice pressure, ringing, or discomfort.",
            color = Color(0xFF829F99),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF102023))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFFA9CBC4))
        }
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF102023))
    ) {
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
            .height(150.dp)
            .background(Color(0xFF071113), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = Color(0xFF294044),
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
                drawPath(
                    path = path,
                    color = Color(0xFF8DF7DF),
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                )
            }
        }
    }
}

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
            AcousticArrayTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(controller::loadFile)
                }
                AcousticArrayScreen(
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
private fun AcousticArrayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF66F0D2),
            secondary = Color(0xFFFFC86B),
            background = Color(0xFF06100F),
            surface = Color(0xFF10201E),
            onBackground = Color(0xFFECFFF9),
            onSurface = Color(0xFFECFFF9)
        ),
        content = content
    )
}

@Composable
private fun AcousticArrayScreen(
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ULTRACARRIER ACOUSTIC ARRAY", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(
            "A clean clone of the working InnerVoice app, expanded with acoustic heterodyning, stereo phase steering, and chirped carriers.",
            color = Color(0xFFA8C8BF)
        )

        InfoCard(
            title = hardware?.label ?: "Detecting audio route…",
            body = hardware?.detail ?: "Checking the phone speaker, DAC, or external array."
        )

        ControlCard(title = "Output path") {
            ListeningPath.entries.forEach { path ->
                FilterChip(
                    selected = state.listeningPath == path,
                    onClick = { onListeningPathChanged(path) },
                    label = { Text(path.label) }
                )
            }
            Text(state.listeningPath.description, color = Color(0xFFA8C8BF))
        }

        ControlCard(title = "Acoustic engine") {
            ThoughtMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.thoughtMode == mode,
                    onClick = { onThoughtModeChanged(mode) },
                    label = { Text(mode.label) }
                )
            }
            Text(state.thoughtMode.description, color = Color(0xFFA8C8BF))
        }

        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth().height(140.dp),
            label = { Text("Text to encode") },
            placeholder = { Text("Type a phrase, reflection, or affirmation…") }
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
            color = Color(0xFFCFE8E0)
        )

        Button(
            onClick = onTransmit,
            enabled = state.loadedAudio != null && !state.isBusy,
            modifier = Modifier.fillMaxWidth().height(58.dp)
        ) {
            Text("PLAY ACOUSTIC SIGNAL", fontWeight = FontWeight.Black)
        }

        ControlCard(title = "Modulation depth") {
            Text("${(state.depth * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
            Slider(value = state.depth, onValueChange = onDepthChanged, valueRange = 0.05f..1f)
            Text(
                "Raise gradually. External ultrasonic arrays can behave very differently from a phone speaker.",
                color = Color(0xFFA8C8BF)
            )
        }

        if (state.thoughtMode != ThoughtMode.INNER_VOICE) {
            ControlCard(title = "Acoustic carrier") {
                Text("${state.carrierHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(
                    value = state.carrierHz.coerceIn(minCarrier, maxCarrier),
                    onValueChange = onCarrierChanged,
                    valueRange = minCarrier..maxCarrier
                )
                Text(
                    "Route range ${minCarrier.roundToInt()}–${maxCarrier.roundToInt()} Hz. Available message sideband ${predictedBandwidth.roundToInt()} Hz.",
                    color = Color(0xFFA8C8BF)
                )
            }
        }

        if (state.thoughtMode == ThoughtMode.ARRAY_STEER) {
            ControlCard(title = "Stereo array steering") {
                Text("Aim angle ${state.steeringAngleDeg.roundToInt()}°", fontWeight = FontWeight.Bold)
                Slider(
                    value = state.steeringAngleDeg,
                    onValueChange = onSteeringAngleChanged,
                    valueRange = -60f..60f
                )
                Text("Transducer spacing ${"%.1f".format(state.transducerSpacingMm)} mm", fontWeight = FontWeight.Bold)
                Slider(
                    value = state.transducerSpacingMm,
                    onValueChange = onTransducerSpacingChanged,
                    valueRange = 1f..50f
                )
                Text(
                    "Δφ = 2π f d sin(θ) / c = ${"%.1f".format(normalizedPhaseDegrees)}°. Left and right channels receive this relative carrier phase.",
                    color = Color(0xFFA8C8BF)
                )
                Text(
                    "For cleaner steering, element spacing near or below half a wavelength reduces unwanted side beams.",
                    color = Color(0xFFA8C8BF)
                )
            }
        }

        if (state.thoughtMode == ThoughtMode.CHIRP_CARRIER) {
            ControlCard(title = "Chirp carrier") {
                Text("Sweep ${state.chirpSweepHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(
                    value = state.chirpSweepHz,
                    onValueChange = onChirpSweepChanged,
                    valueRange = 100f..12_000f
                )
                Text("Period ${state.chirpPeriodMs.roundToInt()} ms", fontWeight = FontWeight.Bold)
                Slider(
                    value = state.chirpPeriodMs,
                    onValueChange = onChirpPeriodChanged,
                    valueRange = 2f..250f
                )
                Text(
                    "This sweeps the acoustic carrier around the selected center frequency. It is not a microwave pulse and does not target tissue.",
                    color = Color(0xFFA8C8BF)
                )
            }
        }

        if (state.thoughtMode == ThoughtMode.AIR_HETERODYNE ||
            state.thoughtMode == ThoughtMode.ARRAY_STEER ||
            state.thoughtMode == ThoughtMode.CHIRP_CARRIER
        ) {
            InfoCard(
                title = "External array recommended",
                body = "A phone speaker cannot create a true 40 kHz parametric beam. Use External Ultrasonic Array with a 96/192 kHz USB DAC, suitable amplifier, and ultrasonic transducers. The app outputs an acoustic waveform only."
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
            colors = CardDefaults.cardColors(containerColor = Color(0xFF132824)),
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
            "This clone intentionally excludes microwave transmission, GHz carriers, tissue-heating equations, and Frey-effect pulse parameters. It only generates ordinary audio-frequency and ultrasonic electrical waveforms through Android's audio output. Keep ultrasonic hardware away from ears, use measured output levels, and stop if you notice discomfort or ringing.",
            color = Color(0xFF829F97),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10201E))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFFA8C8BF))
        }
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10201E))
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
            .background(Color(0xFF07110F), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = Color(0xFF29403B),
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
                    color = Color(0xFF66F0D2),
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                )
            }
        }
    }
}

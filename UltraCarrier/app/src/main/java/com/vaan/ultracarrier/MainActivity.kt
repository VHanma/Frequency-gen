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
import com.vaan.ultracarrier.audio.ModulationMode
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var controller: MainController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = MainController(applicationContext)
        setContent {
            val state by controller.uiState.collectAsState()
            val waveform by controller.waveform.collectAsState()
            UltraCarrierTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(controller::loadFile)
                }
                UltraCarrierScreen(
                    state = state,
                    waveform = waveform,
                    onTextChanged = controller::setText,
                    onPickFile = { picker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onCarrierChanged = controller::setCarrier,
                    onDepthChanged = controller::setDepth,
                    onModeChanged = controller::setMode,
                    onSynthesize = controller::synthesizeAndTransmit,
                    onTransmitFile = controller::transmitLoaded,
                    onStop = controller::stopTransmission,
                    onMaxVolume = controller::setMediaVolumeMaximum
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
private fun UltraCarrierTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00E5FF),
            secondary = Color(0xFF9C6BFF),
            background = Color(0xFF070B14),
            surface = Color(0xFF111827),
            onBackground = Color(0xFFE8F0FF),
            onSurface = Color(0xFFE8F0FF)
        ),
        content = content
    )
}

@Composable
private fun UltraCarrierScreen(
    state: AppUiState,
    waveform: FloatArray,
    onTextChanged: (String) -> Unit,
    onPickFile: () -> Unit,
    onCarrierChanged: (Float) -> Unit,
    onDepthChanged: (Float) -> Unit,
    onModeChanged: (ModulationMode) -> Unit,
    onSynthesize: () -> Unit,
    onTransmitFile: () -> Unit,
    onStop: () -> Unit,
    onMaxVolume: () -> Unit
) {
    val hardware = state.hardware
    val minCarrier = hardware?.carrierMinHz ?: 15_000f
    val maxCarrier = hardware?.carrierMaxHz ?: 22_000f
    val predictedBandwidth = DspMath.safeMessageBandwidth(
        hardware?.requestedSampleRate ?: 48_000,
        state.carrierHz
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ULTRACARRIER LAB", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(
            "TTS and audio-file modulation through a streaming PCM-float AudioTrack.",
            color = Color(0xFF9DB0D0)
        )

        InfoCard(
            title = hardware?.label ?: "Detecting audio route…",
            body = hardware?.detail ?: "Checking connected outputs and native sample rate."
        )

        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth().height(150.dp),
            label = { Text("Text to synthesize") },
            placeholder = { Text("Type a phrase, paragraph, or test pattern…") }
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSynthesize, enabled = !state.isBusy, modifier = Modifier.weight(1f)) {
                Text("Synthesize & Transmit")
            }
            OutlinedButton(onClick = onPickFile, enabled = !state.isBusy, modifier = Modifier.weight(1f)) {
                Text("Upload File")
            }
        }

        Text(
            state.loadedName?.let { "Loaded: $it" } ?: "Loaded: none",
            color = Color(0xFFB8C7E3)
        )
        Button(
            onClick = onTransmitFile,
            enabled = state.loadedAudio != null && !state.isBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Transmit Loaded Audio")
        }

        ControlCard(title = "Carrier frequency") {
            Text("${state.carrierHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
            Slider(
                value = state.carrierHz.coerceIn(minCarrier, maxCarrier),
                onValueChange = onCarrierChanged,
                valueRange = minCarrier..maxCarrier
            )
            Text(
                "Range ${minCarrier.roundToInt()}–${maxCarrier.roundToInt()} Hz. Estimated message bandwidth ${predictedBandwidth.roundToInt()} Hz.",
                color = Color(0xFF9DB0D0)
            )
        }

        ControlCard(title = "Modulation") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModulationMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.modulationMode == mode,
                        onClick = { onModeChanged(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Depth ${(state.depth * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
            Slider(value = state.depth, onValueChange = onDepthChanged, valueRange = 0.05f..1f)
        }

        ControlCard(title = "Output waveform") {
            WaveformCanvas(waveform)
        }

        state.report?.let { report ->
            InfoCard(
                title = "Active stream",
                body = "${report.actualSampleRate} Hz PCM float • ${report.actualCarrierHz.roundToInt()} Hz carrier • " +
                    "${report.messageBandwidthHz.roundToInt()} Hz message bandwidth • ${report.routedDeviceName}"
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onMaxVolume, modifier = Modifier.weight(1f)) {
                Text("Set Media Volume Max")
            }
            Button(onClick = onStop, enabled = state.isTransmitting || state.isBusy, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101A2B)),
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
            "Ultrasonic output depends on the DAC, amplifier, speaker, route, and Android mixer. Hardware nonlinearities can create audible aliases. The app never raises volume automatically.",
            color = Color(0xFF7F92B5),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFF9DB0D0))
        }
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
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
            .background(Color(0xFF070B14), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = Color(0xFF25324A),
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
                    color = Color(0xFF00E5FF),
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                )
            }
        }
    }
}

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
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var controller: MainController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = MainController(applicationContext)
        setContent {
            val state by controller.uiState.collectAsState()
            val waveform by controller.waveform.collectAsState()
            InnerVoiceTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(controller::loadFile)
                }
                InnerVoiceScreen(
                    state = state,
                    waveform = waveform,
                    onTextChanged = controller::setText,
                    onPickFile = { picker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onCarrierChanged = controller::setCarrier,
                    onDepthChanged = controller::setDepth,
                    onThoughtModeChanged = controller::setThoughtMode,
                    onListeningPathChanged = controller::setListeningPath,
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
private fun InnerVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFFC857),
            secondary = Color(0xFF65E9FF),
            background = Color(0xFF050713),
            surface = Color(0xFF12152A),
            onBackground = Color(0xFFFFF4D6),
            onSurface = Color(0xFFFFF4D6)
        ),
        content = content
    )
}

@Composable
private fun InnerVoiceScreen(
    state: AppUiState,
    waveform: FloatArray,
    onTextChanged: (String) -> Unit,
    onPickFile: () -> Unit,
    onCarrierChanged: (Float) -> Unit,
    onDepthChanged: (Float) -> Unit,
    onThoughtModeChanged: (ThoughtMode) -> Unit,
    onListeningPathChanged: (ListeningPath) -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("INNERVOICE UNLIMITED", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(
            "The same InnerVoice engines with true chunked playback for very large audio files.",
            color = Color(0xFFC9BFA4)
        )

        InfoCard(
            title = hardware?.label ?: "Detecting audio route…",
            body = hardware?.detail ?: "Checking the active speaker or headset."
        )

        ControlCard(title = "Listening path") {
            ListeningPath.entries.forEach { path ->
                FilterChip(
                    selected = state.listeningPath == path,
                    onClick = { onListeningPathChanged(path) },
                    label = { Text(path.label) }
                )
            }
            Text(state.listeningPath.description, color = Color(0xFFC9BFA4))
        }

        ControlCard(title = "Perception engine") {
            ThoughtMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.thoughtMode == mode,
                    onClick = { onThoughtModeChanged(mode) },
                    label = { Text(mode.label) }
                )
            }
            Text(state.thoughtMode.description, color = Color(0xFFC9BFA4))
        }

        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth().height(140.dp),
            label = { Text("Text for your private voice") },
            placeholder = { Text("Type a phrase, reflection, or affirmation…") }
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPrepareText, enabled = !state.isBusy, modifier = Modifier.weight(1f)) {
                Text("Prepare Text")
            }
            OutlinedButton(onClick = onPickFile, enabled = !state.isBusy, modifier = Modifier.weight(1f)) {
                Text("Choose Any-Size File")
            }
        }

        Text(
            "Uploaded files are decoded while they play. The app does not copy the whole song, audiobook, or archive into memory.",
            color = Color(0xFFC9BFA4)
        )

        Text(
            state.loadedName?.let { "Ready source: $it" } ?: "Ready source: none",
            color = Color(0xFFE5D9B8)
        )

        Button(
            onClick = onTransmit,
            enabled = state.loadedSource != null && !state.isBusy,
            modifier = Modifier.fillMaxWidth().height(58.dp)
        ) {
            Text("STREAM TO SELF", fontWeight = FontWeight.Black)
        }

        ControlCard(title = "Presence") {
            Text("${(state.depth * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
            Slider(value = state.depth, onValueChange = onDepthChanged, valueRange = 0.05f..1f)
            Text(
                "Lower settings feel more distant and blend into attention. Raise slowly until comfortable.",
                color = Color(0xFFC9BFA4)
            )
        }

        if (state.thoughtMode != ThoughtMode.INNER_VOICE) {
            ControlCard(title = "Experimental carrier") {
                Text("${state.carrierHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(
                    value = state.carrierHz.coerceIn(minCarrier, maxCarrier),
                    onValueChange = onCarrierChanged,
                    valueRange = minCarrier..maxCarrier
                )
                Text(
                    "Range ${minCarrier.roundToInt()}–${maxCarrier.roundToInt()} Hz. Available sideband width ${predictedBandwidth.roundToInt()} Hz.",
                    color = Color(0xFFC9BFA4)
                )
            }
        }

        ControlCard(title = "Live voice waveform") {
            WaveformCanvas(waveform)
        }

        state.report?.let { report ->
            InfoCard(
                title = "Active ${report.thoughtMode.label}",
                body = buildString {
                    append("${report.actualSampleRate} Hz PCM float")
                    if (report.actualCarrierHz > 0f) append(" • ${report.actualCarrierHz.roundToInt()} Hz carrier")
                    append(" • ${report.messageBandwidthHz.roundToInt()} Hz voice band")
                    append(" • output ${(report.outputGain * 100).roundToInt()}%")
                    append(" • ${report.listeningPath.label}")
                    append(" • ${report.routedDeviceName}")
                }
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onSafeVolume, modifier = Modifier.weight(1f)) {
                Text("Private Volume")
            }
            Button(onClick = onStop, enabled = state.isTransmitting || state.isBusy, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF15182E)),
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
            "There is no app-set file-size or duration cap. The practical limit is the file provider, supported audio format, available storage, battery, and how long Android keeps the app active. WAV and RF64 WAV stream directly; Android handles supported MP3, M4A, OGG, and FLAC files. Keep volume comfortable and stop if you feel pressure, ringing, or discomfort.",
            color = Color(0xFF918970),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12152A))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFFC9BFA4))
        }
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12152A))
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
            .background(Color(0xFF070817), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = Color(0xFF2D304B),
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
                    color = Color(0xFFFFC857),
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                )
            }
        }
    }
}

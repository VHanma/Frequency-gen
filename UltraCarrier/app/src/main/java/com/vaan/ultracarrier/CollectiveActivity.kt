package com.vaan.ultracarrier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaan.ultracarrier.audio.BeamLabMode
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.collective.CollectiveController
import com.vaan.ultracarrier.collective.CollectiveFamily
import com.vaan.ultracarrier.collective.CollectiveMode
import com.vaan.ultracarrier.collective.CollectiveUiState
import com.vaan.ultracarrier.collective.ExportFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class CollectiveActivity : ComponentActivity() {
    private lateinit var controller: CollectiveController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = CollectiveController(applicationContext)
        setContent {
            val state by controller.state.collectAsState()
            val scope by controller.scopeData.collectAsState()
            val scopeRate by controller.scopeRate.collectAsState()
            CollectiveTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::loadFile) }
                val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri -> uri?.let(controller::export) }
                CollectiveScreen(
                    state = state,
                    scope = scope,
                    scopeRate = scopeRate,
                    onText = controller::setText,
                    onPick = { picker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onSave = { saver.launch("Collective-${state.family.name}-${if (state.family == CollectiveFamily.WORLD_BEAM) state.worldMode.name else state.collectiveMode.name}.wav") },
                    onFamily = controller::setFamily,
                    onWorldMode = controller::setWorldMode,
                    onCollectiveMode = controller::setCollectiveMode,
                    onPath = controller::setPath,
                    onPresence = controller::setPresence,
                    onCarrier = controller::setCarrier,
                    onElfRate = controller::setElfRate,
                    onElfDepth = controller::setElfDepth,
                    onTarget = controller::setTarget,
                    onNull = controller::setNull,
                    onSpacing = controller::setSpacing,
                    onDither = controller::setDither,
                    onDitherRate = controller::setDitherRate,
                    onHeadWidth = controller::setHeadWidth,
                    onDistance = controller::setDistance,
                    onLoop = controller::setLoop,
                    onExportFormat = controller::setExportFormat,
                    onPrepare = controller::prepareText,
                    onPlay = controller::play,
                    onStop = controller::stop,
                    onVolume = controller::setListeningVolume,
                    onStartFade = controller::startFadeTrial,
                    onFaded = controller::markFaded,
                    onClearFade = controller::clearFadeTrials
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
private fun CollectiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF70F5D4),
            secondary = Color(0xFFB8A2FF),
            background = Color(0xFF050807),
            surface = Color(0xFF101B18),
            onBackground = Color(0xFFF0FFF9),
            onSurface = Color(0xFFF0FFF9)
        ),
        content = content
    )
}

@Composable
private fun CollectiveScreen(
    state: CollectiveUiState,
    scope: FloatArray,
    scopeRate: Int,
    onText: (String) -> Unit,
    onPick: () -> Unit,
    onSave: () -> Unit,
    onFamily: (CollectiveFamily) -> Unit,
    onWorldMode: (BeamLabMode) -> Unit,
    onCollectiveMode: (CollectiveMode) -> Unit,
    onPath: (ListeningPath) -> Unit,
    onPresence: (Float) -> Unit,
    onCarrier: (Float) -> Unit,
    onElfRate: (Float) -> Unit,
    onElfDepth: (Float) -> Unit,
    onTarget: (Float) -> Unit,
    onNull: (Float) -> Unit,
    onSpacing: (Float) -> Unit,
    onDither: (Float) -> Unit,
    onDitherRate: (Float) -> Unit,
    onHeadWidth: (Float) -> Unit,
    onDistance: (Float) -> Unit,
    onLoop: (Boolean) -> Unit,
    onExportFormat: (ExportFormat) -> Unit,
    onPrepare: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onVolume: () -> Unit,
    onStartFade: () -> Unit,
    onFaded: () -> Unit,
    onClearFade: () -> Unit
) {
    val h = state.hardware
    val minCarrier = h?.carrierMinHz ?: 13_500f
    val maxCarrier = h?.carrierMaxHz ?: 22_000f
    val spectrum = remember(scope, scopeRate) { spectrum(scope, scopeRate, 72) }
    val dominant = spectrum.maxByOrNull { it.second }?.first ?: 0f

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ULTRACARRIER COLLECTIVE BEAM LAB", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("Separate clone • true streaming • live scope • export • world-beam + perception experiments", color = Color(0xFFA9CFC4))
        InfoCard("Status", state.status)

        ControlCard("Output") {
            ChipFlow { ListeningPath.entries.forEach { p -> FilterChip(selected = state.listeningPath == p, onClick = { onPath(p) }, label = { Text(p.label) }) } }
            OutlinedButton(onClick = onVolume) { Text("Set listening volume") }
        }

        ControlCard("Experiment family") {
            ChipFlow { CollectiveFamily.entries.forEach { f -> FilterChip(selected = state.family == f, onClick = { onFamily(f) }, label = { Text(f.label) }) } }
        }

        if (state.family == CollectiveFamily.WORLD_BEAM) {
            ControlCard("World Beam bank") {
                ChipFlow { BeamLabMode.entries.forEach { m -> FilterChip(selected = state.worldMode == m, onClick = { onWorldMode(m) }, label = { Text(m.label) }) } }
                Text(state.worldMode.description, color = Color(0xFFA9CFC4))
            }
        } else {
            ControlCard("Perception Lab bank") {
                ChipFlow { CollectiveMode.entries.forEach { m -> FilterChip(selected = state.collectiveMode == m, onClick = { onCollectiveMode(m) }, label = { Text(m.label) }) } }
                Text(state.collectiveMode.description, color = Color(0xFFA9CFC4))
            }
        }

        OutlinedTextField(value = state.text, onValueChange = onText, modifier = Modifier.fillMaxWidth().height(120.dp), label = { Text("Text to encode") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPrepare, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Prepare Text") }
            OutlinedButton(onClick = onPick, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Choose File") }
        }
        Text(state.sourceName?.let { "Ready: $it" } ?: "Ready: none", color = Color(0xFFD5EAE4))
        Text("Selected files stream from storage. There is no whole-file FloatArray decode step.", color = Color(0xFFA9CFC4))

        ControlCard("Playback") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Loop until Stop", fontWeight = FontWeight.Bold)
                Switch(checked = state.loop, onCheckedChange = onLoop)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, enabled = state.source != null && !state.busy, modifier = Modifier.weight(1f)) { Text(if (state.loop) "PLAY + LOOP" else "PLAY") }
                OutlinedButton(onClick = onStop, enabled = state.playing || state.exporting || state.busy, modifier = Modifier.weight(1f)) { Text("STOP") }
            }
        }

        LiveScope(scope = scope, spectrum = spectrum, sampleRate = scopeRate, dominantHz = dominant)

        ControlCard("Save processed audio") {
            Text("Exact stereo render. WAV automatically upgrades to RF64 if it grows beyond ordinary RIFF size.", color = Color(0xFFA9CFC4))
            ChipFlow { ExportFormat.entries.forEach { f -> FilterChip(selected = state.exportFormat == f, onClick = { onExportFormat(f) }, label = { Text(f.label) }) } }
            Button(onClick = onSave, enabled = state.source != null && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("RENDER & SAVE") }
            if (state.exporting) {
                state.exportProgress?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        ControlCard("Presence") {
            Text("${(state.presence * 100).roundToInt()}%")
            Slider(value = state.presence, onValueChange = onPresence, valueRange = 0.05f..1f)
        }

        if (state.family == CollectiveFamily.WORLD_BEAM) {
            ControlCard("Carrier + field geometry") {
                Text("Carrier ${state.carrierHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                Slider(value = state.carrierHz.coerceIn(minCarrier, maxCarrier), onValueChange = onCarrier, valueRange = minCarrier..maxCarrier)
                Text("ELF / low-rate pattern ${"%.2f".format(state.elfRateHz)} Hz")
                Slider(value = state.elfRateHz, onValueChange = onElfRate, valueRange = 0.25f..80f)
                Text("Pattern depth ${(state.elfDepth * 100).roundToInt()}%")
                Slider(value = state.elfDepth, onValueChange = onElfDepth, valueRange = 0f..0.98f)
                Text("Target ${state.targetAngleDeg.roundToInt()}°")
                Slider(value = state.targetAngleDeg, onValueChange = onTarget, valueRange = -70f..70f)
                Text("Null ${state.nullAngleDeg.roundToInt()}°")
                Slider(value = state.nullAngleDeg, onValueChange = onNull, valueRange = -80f..80f)
                Text("Spacing ${"%.1f".format(state.spacingMm)} mm")
                Slider(value = state.spacingMm, onValueChange = onSpacing, valueRange = 1f..80f)
                Text("Beam dither ±${"%.1f".format(state.ditherDeg)}° at ${"%.2f".format(state.ditherRateHz)} Hz")
                Slider(value = state.ditherDeg, onValueChange = onDither, valueRange = 0f..15f)
                Slider(value = state.ditherRateHz, onValueChange = onDitherRate, valueRange = 0.02f..5f)
                Text("Head width ${"%.1f".format(state.headWidthCm)} cm • distance ${state.listenerDistanceCm.roundToInt()} cm")
                Slider(value = state.headWidthCm, onValueChange = onHeadWidth, valueRange = 10f..24f)
                Slider(value = state.listenerDistanceCm, onValueChange = onDistance, valueRange = 10f..500f)
            }
        }

        FadeLab(state, onStartFade, onFaded, onClearFade)

        InfoCard(
            "Research-grounded mind experiments",
            "Phonemic Restore and Continuity Ghost deliberately remove physical speech segments and test whether perception fills them in. Auditory Afterimage creates a notched-noise induction / quiet cycle. Mind Canvas and Image Seed use pitch-space relationships and music-driven imagery ideas. The Lacerta Filter Test is a self-measured attention/perceptual-fading analogue, not an assumed magic frequency."
        )
    }
}

@Composable
private fun LiveScope(scope: FloatArray, spectrum: List<Pair<Float, Float>>, sampleRate: Int, dominantHz: Float) {
    ControlCard("LIVE SIGNAL") {
        Text("Oscilloscope", fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(120.dp).background(Color(0xFF07110F))) {
            if (scope.size > 1) {
                val path = Path()
                scope.forEachIndexed { i, v ->
                    val x = size.width * i / (scope.size - 1f)
                    val y = size.height * (0.5f - v.coerceIn(-1f, 1f) * 0.44f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color(0xFF70F5D4), style = Stroke(2f))
                drawLine(Color(0x3355FFCC), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
            }
        }
        Text("Frequency spectrum • peak ${dominantHz.roundToInt()} Hz • scope rate $sampleRate Hz", fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF07110F))) {
            if (spectrum.isNotEmpty()) {
                val maxMag = spectrum.maxOf { it.second }.coerceAtLeast(0.000001f)
                val w = size.width / spectrum.size
                spectrum.forEachIndexed { i, pair ->
                    val h = size.height * sqrt((pair.second / maxMag).coerceIn(0f, 1f))
                    drawLine(Color(0xFFB8A2FF), Offset(i * w + w * 0.5f, size.height), Offset(i * w + w * 0.5f, size.height - h), maxOf(1f, w * 0.62f))
                }
            }
        }
    }
}

@Composable
private fun FadeLab(state: CollectiveUiState, onStart: () -> Unit, onFaded: () -> Unit, onClear: () -> Unit) {
    ControlCard("COGNITIVE FILTER / PERCEPTUAL FADE LAB") {
        Text("Fix your eyes on the center cross without chasing the side target. Tap FADED the instant the peripheral target seems to disappear or substantially dissolve.", color = Color(0xFFA9CFC4))
        Canvas(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF111315))) {
            val c = Offset(size.width * 0.5f, size.height * 0.5f)
            drawLine(Color.White, Offset(c.x - 11f, c.y), Offset(c.x + 11f, c.y), 2f)
            drawLine(Color.White, Offset(c.x, c.y - 11f), Offset(c.x, c.y + 11f), 2f)
            val target = Offset(size.width * 0.80f, size.height * 0.48f)
            drawCircle(Color(0x6684FFD2), radius = 27f, center = target)
            drawCircle(Color(0x4470A8FF), radius = 17f, center = target)
            drawCircle(Color(0x88FFD5F2), radius = 8f, center = target)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !state.fadeRunning, modifier = Modifier.weight(1f)) { Text("START") }
            Button(onClick = onFaded, enabled = state.fadeRunning, modifier = Modifier.weight(1f)) { Text("FADED") }
        }
        state.fadeElapsedMs?.let { Text("Last fade: ${"%.2f".format(it / 1000.0)} s", fontWeight = FontWeight.Bold) }
        if (state.fadeTrials.isNotEmpty()) {
            val recent = state.fadeTrials.takeLast(5).reversed()
            recent.forEach { Text("${it.modeLabel}: ${"%.2f".format(it.elapsedMs / 1000.0)} s", color = Color(0xFFD5EAE4)) }
            OutlinedButton(onClick = onClear) { Text("Clear trials") }
        }
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Black)
            content()
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF12251F))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Black)
            Text(body, color = Color(0xFFC9E4DB))
        }
    }
}

@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

private fun spectrum(samples: FloatArray, sampleRate: Int, bars: Int): List<Pair<Float, Float>> {
    if (samples.size < 16 || sampleRate <= 0) return emptyList()
    val n = minOf(256, samples.size)
    val start = samples.size - n
    val maxBin = minOf(n / 2 - 1, bars)
    val out = ArrayList<Pair<Float, Float>>(maxBin)
    for (k in 1..maxBin) {
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1).coerceAtLeast(1))
            val x = samples[start + i] * window
            val a = 2.0 * PI * k * i / n
            re += x * cos(a)
            im -= x * sin(a)
        }
        val mag = sqrt(re * re + im * im).toFloat()
        out += (k * sampleRate.toFloat() / n) to mag
    }
    return out
}

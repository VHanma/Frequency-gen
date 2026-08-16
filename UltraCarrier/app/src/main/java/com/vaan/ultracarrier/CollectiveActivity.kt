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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vaan.ultracarrier.audio.BeamLabMode
import com.vaan.ultracarrier.audio.GodXMode
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.audio.ThoughtMode
import com.vaan.ultracarrier.collective.CollectiveController
import com.vaan.ultracarrier.collective.CollectiveFamily
import com.vaan.ultracarrier.collective.CollectiveMode
import com.vaan.ultracarrier.collective.CollectiveUiState
import com.vaan.ultracarrier.collective.ExportFormat
import com.vaan.ultracarrier.collective.MethodPreset
import com.vaan.ultracarrier.collective.PresetCatalog
import com.vaan.ultracarrier.collective.ScalarMode
import kotlin.math.PI
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
                    onSaveAs = { saver.launch("Collective-${state.family.name}-${selectedModeName(state)}.wav") },
                    onSaveDownloads = controller::saveToDownloads,
                    onFamily = controller::setFamily,
                    onWorldMode = controller::setWorldMode,
                    onCollectiveMode = controller::setCollectiveMode,
                    onLabXMode = controller::setLabXMode,
                    onClassicMode = controller::setClassicMode,
                    onScalarMode = controller::setScalarMode,
                    onResetPreset = controller::resetPreset,
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

private fun selectedModeName(state: CollectiveUiState): String = when (state.family) {
    CollectiveFamily.WORLD_BEAM -> state.worldMode.name
    CollectiveFamily.PERCEPTION_LAB -> state.collectiveMode.name
    CollectiveFamily.LAB_X -> state.labXMode.name
    CollectiveFamily.THOUGHTBEAM -> state.classicMode.name
    CollectiveFamily.SCALAR_LAB -> state.scalarMode.name
}

private fun currentPreset(state: CollectiveUiState): MethodPreset = when (state.family) {
    CollectiveFamily.WORLD_BEAM -> PresetCatalog.world(state.worldMode)
    CollectiveFamily.PERCEPTION_LAB -> PresetCatalog.perception(state.collectiveMode)
    CollectiveFamily.LAB_X -> PresetCatalog.labX(state.labXMode)
    CollectiveFamily.THOUGHTBEAM -> PresetCatalog.classic(state.classicMode)
    CollectiveFamily.SCALAR_LAB -> PresetCatalog.scalar(state.scalarMode)
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
    onSaveAs: () -> Unit,
    onSaveDownloads: () -> Unit,
    onFamily: (CollectiveFamily) -> Unit,
    onWorldMode: (BeamLabMode) -> Unit,
    onCollectiveMode: (CollectiveMode) -> Unit,
    onLabXMode: (GodXMode) -> Unit,
    onClassicMode: (ThoughtMode) -> Unit,
    onScalarMode: (ScalarMode) -> Unit,
    onResetPreset: () -> Unit,
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
    val minCarrier = h?.carrierMinHz ?: 500f
    val maxCarrier = h?.carrierMaxHz ?: 22_000f
    val spectrum = remember(scope, scopeRate) { spectrum(scope, scopeRate, 72) }
    val dominant = spectrum.maxByOrNull { it.second }?.first ?: 0f
    val preset = currentPreset(state)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ULTRACARRIER COLLECTIVE BEAM LAB Ω", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("v15 clone + fixed TTS/save + typed variables + method presets + Scalar Lab", color = Color(0xFFA9CFC4))
        InfoCard("Status", state.status)

        ControlCard("Output") {
            ChipFlow { ListeningPath.entries.forEach { p -> FilterChip(selected = state.listeningPath == p, onClick = { onPath(p) }, label = { Text(p.label) }) } }
            OutlinedButton(onClick = onVolume) { Text("Set listening volume") }
        }

        ControlCard("Experiment family") {
            ChipFlow { CollectiveFamily.entries.forEach { f -> FilterChip(selected = state.family == f, onClick = { onFamily(f) }, label = { Text(f.label) }) } }
        }

        ModeBank(state, onWorldMode, onCollectiveMode, onLabXMode, onClassicMode, onScalarMode)

        PresetCard(preset, onResetPreset)

        OutlinedTextField(
            value = state.text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth().height(120.dp),
            label = { Text("Text to encode with Android TTS") }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPrepare, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("PREPARE TTS") }
            OutlinedButton(onClick = onPick, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("CHOOSE FILE") }
        }
        Text(state.sourceName?.let { "Ready: $it" } ?: "Ready: none", color = Color(0xFFD5EAE4))
        Text("Files stream from storage instead of being loaded into one giant RAM buffer.", color = Color(0xFFA9CFC4))

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

        LiveScope(scope, spectrum, scopeRate, dominant)

        ControlCard("Save processed audio") {
            Text("The selected DSP method is rendered into the saved stereo WAV. Large WAV automatically becomes RF64.", color = Color(0xFFA9CFC4))
            ChipFlow { ExportFormat.entries.forEach { f -> FilterChip(selected = state.exportFormat == f, onClick = { onExportFormat(f) }, label = { Text(f.label) }) } }
            Button(onClick = onSaveDownloads, enabled = state.source != null && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("SAVE TO MUSIC / ULTRACARRIER") }
            OutlinedButton(onClick = onSaveAs, enabled = state.source != null && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("SAVE AS…") }
            if (state.exporting) {
                state.exportProgress?.let { LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        ControlCard("Method variables") {
            Text("Type an exact value or keep using the slider. Suggested values come from the selected method preset.", color = Color(0xFFA9CFC4))
            NumberControl("Presence", state.presence, .05f, 1f, onPresence, preset.depth, 3)
            preset.carrierHz?.let { NumberControl("Carrier Hz", state.carrierHz, minCarrier, maxCarrier, onCarrier, it, 0) }
            preset.rateHz?.let { NumberControl("Rate / modulation Hz", state.elfRateHz, .02f, 120f, onElfRate, it, 2) }
            preset.depth?.let { NumberControl("Depth (0–1)", state.elfDepth, 0f, .98f, onElfDepth, it, 3) }
            preset.targetDeg?.let { NumberControl("Target angle °", state.targetAngleDeg, -80f, 80f, onTarget, it, 1) }
            preset.nullDeg?.let { NumberControl("Null angle °", state.nullAngleDeg, -80f, 80f, onNull, it, 1) }
            preset.spacingMm?.let { NumberControl("Spacing mm", state.spacingMm, 1f, 80f, onSpacing, it, 2) }
            preset.ditherDeg?.let { NumberControl("Dither / spiral amount °", state.ditherDeg, 0f, 15f, onDither, it, 2) }
            preset.ditherRateHz?.let { NumberControl("Motion / dither Hz", state.ditherRateHz, .02f, 5f, onDitherRate, it, 3) }
            preset.headWidthCm?.let { NumberControl("Head width cm", state.headWidthCm, 10f, 24f, onHeadWidth, it, 2) }
            preset.distanceCm?.let { NumberControl("Listener distance cm", state.listenerDistanceCm, 10f, 500f, onDistance, it, 1) }
            Button(onClick = onResetPreset, modifier = Modifier.fillMaxWidth()) { Text("RESTORE METHOD PRESET") }
        }

        FadeLab(state, onStartFade, onFaded, onClearFade)

        if (state.family == CollectiveFamily.SCALAR_LAB) {
            InfoCard(
                "Scalar Lab research basis",
                "These modes turn Bearden/Tesla/phase-conjugation/longitudinal/scalar-interferometer ideas into reproducible stereo acoustic signal structures. The generated phase, carrier, beat, spiral and cancellation patterns are real; extraordinary scalar-EM interpretations remain experimental claims rather than established physics."
            )
        }
    }
}

@Composable
private fun ModeBank(
    state: CollectiveUiState,
    onWorldMode: (BeamLabMode) -> Unit,
    onCollectiveMode: (CollectiveMode) -> Unit,
    onLabXMode: (GodXMode) -> Unit,
    onClassicMode: (ThoughtMode) -> Unit,
    onScalarMode: (ScalarMode) -> Unit
) {
    ControlCard("Techniques by effect") {
        when (state.family) {
            CollectiveFamily.WORLD_BEAM -> {
                val groups = BeamLabMode.entries.groupBy { PresetCatalog.worldCategory(it) }
                groups.forEach { (category, modes) ->
                    CategoryTitle(category)
                    ChipFlow { modes.forEach { m -> FilterChip(selected = state.worldMode == m, onClick = { onWorldMode(m) }, label = { Text(m.label) }) } }
                }
                Text(state.worldMode.description, color = Color(0xFFA9CFC4))
            }
            CollectiveFamily.PERCEPTION_LAB -> {
                val groups = CollectiveMode.entries.groupBy { PresetCatalog.perceptionCategory(it) }
                groups.forEach { (category, modes) ->
                    CategoryTitle(category)
                    ChipFlow { modes.forEach { m -> FilterChip(selected = state.collectiveMode == m, onClick = { onCollectiveMode(m) }, label = { Text(m.label) }) } }
                }
                Text(state.collectiveMode.description, color = Color(0xFFA9CFC4))
            }
            CollectiveFamily.LAB_X -> {
                val groups = GodXMode.entries.groupBy { PresetCatalog.labXCategory(it) }
                groups.forEach { (category, modes) ->
                    CategoryTitle(category)
                    ChipFlow { modes.forEach { m -> FilterChip(selected = state.labXMode == m, onClick = { onLabXMode(m) }, label = { Text(m.label) }) } }
                }
                Text(state.labXMode.description, color = Color(0xFFA9CFC4))
            }
            CollectiveFamily.THOUGHTBEAM -> {
                val groups = ThoughtMode.entries.groupBy { PresetCatalog.classicCategory(it) }
                groups.forEach { (category, modes) ->
                    CategoryTitle(category)
                    ChipFlow { modes.forEach { m -> FilterChip(selected = state.classicMode == m, onClick = { onClassicMode(m) }, label = { Text(m.label) }) } }
                }
                Text(state.classicMode.description, color = Color(0xFFA9CFC4))
            }
            CollectiveFamily.SCALAR_LAB -> {
                ScalarMode.entries.groupBy { it.category }.forEach { (category, modes) ->
                    CategoryTitle(category)
                    ChipFlow { modes.forEach { m -> FilterChip(selected = state.scalarMode == m, onClick = { onScalarMode(m) }, label = { Text(m.label) }) } }
                }
                Text(state.scalarMode.description, color = Color(0xFFA9CFC4))
            }
        }
    }
}

@Composable
private fun PresetCard(preset: MethodPreset, onReset: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF19231E))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("METHOD PRESET • ${preset.category}", fontWeight = FontWeight.Black, color = Color(0xFFFFD98A))
            Text(preset.name, fontWeight = FontWeight.Black)
            Text(preset.note, color = Color(0xFFC9E4DB))
            Text("Suggested: ${preset.variableSummary().ifBlank { "method-defined DSP" }}", color = Color(0xFFB8A2FF))
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("RESTORE PRESET") }
        }
    }
}

@Composable
private fun NumberControl(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
    suggested: Float?,
    decimals: Int
) {
    var text by remember(value) { mutableStateOf(formatNumber(value, decimals)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(buildString {
            append(label)
            append(" • current ")
            append(formatNumber(value, decimals))
            suggested?.let { append(" • preset "); append(formatNumber(it, decimals)) }
        }, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                raw.trim().toFloatOrNull()?.let { onChange(it.coerceIn(min, max)) }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Type $label") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max)
    }
}

private fun formatNumber(value: Float, decimals: Int): String = when (decimals) {
    0 -> value.roundToInt().toString()
    1 -> "%.1f".format(value)
    2 -> "%.2f".format(value)
    else -> "%.3f".format(value)
}

@Composable
private fun CategoryTitle(category: String) {
    Text(category.uppercase(), color = Color(0xFFFFD98A), fontWeight = FontWeight.Black)
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
                    val height = size.height * sqrt((pair.second / maxMag).coerceIn(0f, 1f))
                    drawLine(Color(0xFFB8A2FF), Offset(i * w + w * .5f, size.height), Offset(i * w + w * .5f, size.height - height), maxOf(1f, w * .62f))
                }
            }
        }
    }
}

@Composable
private fun FadeLab(state: CollectiveUiState, onStart: () -> Unit, onFaded: () -> Unit, onClear: () -> Unit) {
    ControlCard("COGNITIVE FILTER / PERCEPTUAL FADE LAB") {
        Text("Fix your eyes on the center cross. Tap FADED when the peripheral target disappears or strongly dissolves.", color = Color(0xFFA9CFC4))
        Canvas(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF111315))) {
            val c = Offset(size.width * .5f, size.height * .5f)
            drawLine(Color.White, Offset(c.x - 11f, c.y), Offset(c.x + 11f, c.y), 2f)
            drawLine(Color.White, Offset(c.x, c.y - 11f), Offset(c.x, c.y + 11f), 2f)
            val target = Offset(size.width * .80f, size.height * .48f)
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
            state.fadeTrials.takeLast(5).reversed().forEach { Text("${it.modeLabel}: ${"%.2f".format(it.elapsedMs / 1000.0)} s", color = Color(0xFFD5EAE4)) }
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
            val window = .5 - .5 * cos(2.0 * PI * i / (n - 1).coerceAtLeast(1))
            val x = samples[start + i] * window
            val a = 2.0 * PI * k * i / n
            re += x * cos(a)
            im -= x * sin(a)
        }
        out += (k * sampleRate.toFloat() / n) to sqrt(re * re + im * im).toFloat()
    }
    return out
}

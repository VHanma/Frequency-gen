package com.vaan.frequencyremapper

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private data class MappingRowV6(
    val id: Long,
    val noteLabel: String,
    val category: AudioCategory,
    val tags: List<String>,
    val sourceText: String,
    val targetText: String,
    val phaseText: String = "0",
    val enabled: Boolean = true,
    val manual: Boolean = false
)

class MainActivityV6 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StreamingLargeAudioExportManager.cleanup(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { FrequencyRemapperV6Screen() }
            }
        }
    }
}

@Composable
private fun FrequencyRemapperV6Screen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val player = remember { MediaPlayer() }
    val liveEngine = remember { StreamingResilientLivePreviewEngine(context.applicationContext) }

    var source by remember { mutableStateOf<StreamAudioSource?>(null) }
    var rows by remember { mutableStateOf<List<MappingRowV6>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Choose audio. v1.5 streams the original file instead of creating a giant PCM cache.") }
    var liveOn by remember { mutableStateOf(false) }
    var shiftHarmonics by remember { mutableStateOf(true) }
    var bandCents by remember { mutableFloatStateOf(48f) }
    var savedUri by remember { mutableStateOf(StreamingLargeAudioExportManager.recoverLastUri(context)) }
    var savedLabel by remember { mutableStateOf(StreamingLargeAudioExportManager.recoverLastLabel(context)) }
    var batchTargets by remember { mutableStateOf<Map<AudioCategory, String>>(emptyMap()) }
    var batchPhases by remember { mutableStateOf<Map<AudioCategory, String>>(emptyMap()) }

    val categoryOrder = remember {
        listOf(
            AudioCategory.VOCAL,
            AudioCategory.LOW_VOCAL,
            AudioCategory.HIDDEN_LOW_VOCAL,
            AudioCategory.HIDDEN_VOCAL,
            AudioCategory.BASS,
            AudioCategory.INSTRUMENT,
            AudioCategory.OTHER,
            AudioCategory.CUSTOM
        )
    }

    fun options(shift: Boolean = shiftHarmonics, cents: Float = bandCents) = PhaseRemapOptions(
        shiftHarmonicFamily = shift,
        bandCents = cents.toDouble()
    )

    fun parseMappings(candidate: List<MappingRowV6>, changedOnly: Boolean): List<PhaseFrequencyMapping> {
        val s = source ?: return emptyList()
        val nyquist = s.sampleRate / 2.0
        return candidate.mapNotNull { row ->
            if (!row.enabled) return@mapNotNull null
            val from = row.sourceText.toDoubleOrNull() ?: return@mapNotNull null
            val to = row.targetText.toDoubleOrNull() ?: return@mapNotNull null
            val phase = row.phaseText.toDoubleOrNull() ?: 0.0
            if (!from.isFinite() || !to.isFinite() || !phase.isFinite()) return@mapNotNull null
            if (from <= 0.0 || to <= 0.0 || from >= nyquist || to >= nyquist) return@mapNotNull null
            val changed = abs(from - to) > 0.0001 || abs(normalizedPhaseV6(phase)) > 0.0001
            if (changedOnly && !changed) return@mapNotNull null
            PhaseFrequencyMapping(from, to, phase, true)
        }
    }

    fun updateLive(candidate: List<MappingRowV6> = rows, shift: Boolean = shiftHarmonics, cents: Float = bandCents) {
        if (liveOn) liveEngine.update(parseMappings(candidate, true), options(shift, cents))
    }

    fun replaceRows(next: List<MappingRowV6>, message: String? = null) {
        rows = next
        updateLive(next)
        if (message != null) status = message
    }

    fun stopPlayer() { runCatching { if (player.isPlaying) player.stop() } }
    fun stopAll() { stopPlayer(); liveEngine.stop(); liveOn = false }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
            liveEngine.close()
        }
    }

    fun playUri(uri: Uri, text: String) {
        liveEngine.stop(); liveOn = false
        runCatching {
            player.reset(); player.setDataSource(context, uri); player.prepare(); player.start(); status = text
        }.onFailure { status = "Playback failed: ${it.message}" }
    }

    fun startLive() {
        val s = source ?: return
        stopPlayer(); liveOn = true
        status = "Starting URI-streaming live preview..."
        liveEngine.start(
            source = s,
            mappings = parseMappings(rows, true),
            options = options(),
            onError = { msg -> mainHandler.post {
                liveOn = false
                status = "LIVE PREVIEW STOPPED: $msg"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } },
            onEnded = { mainHandler.post { liveOn = false } }
        )
        status = "LIVE PREVIEW ON. Original audio is decoded in chunks; transient output failures auto-recover up to 3 times."
    }

    fun applyBatchTarget(category: AudioCategory) {
        val s = source ?: return
        val hz = batchTargets[category].orEmpty().toDoubleOrNull()
        val nyquist = s.sampleRate / 2.0
        if (hz == null || !hz.isFinite() || hz <= 0.0 || hz >= nyquist) {
            Toast.makeText(context, "Enter a target between 0 and ${nyquist.roundToInt()} Hz.", Toast.LENGTH_LONG).show(); return
        }
        val exact = trimNumberV6(hz)
        val count = rows.count { it.category == category }
        replaceRows(rows.map { if (it.category == category) it.copy(targetText = exact) else it },
            "Set $count ${category.title} rows to exactly $exact Hz. Every other category stayed unchanged.")
    }

    fun applyBatchPhase(category: AudioCategory) {
        val value = batchPhases[category].orEmpty().toDoubleOrNull()
        if (value == null || !value.isFinite()) { Toast.makeText(context, "Enter a valid phase.", Toast.LENGTH_LONG).show(); return }
        val exact = trimNumberV6(value)
        val count = rows.count { it.category == category }
        replaceRows(rows.map { if (it.category == category) it.copy(phaseText = exact) else it },
            "Set phase for $count ${category.title} rows to $exact°. Other categories stayed unchanged.")
    }

    fun resetCategory(category: AudioCategory) {
        val count = rows.count { it.category == category }
        replaceRows(rows.map { if (it.category == category) it.copy(targetText = it.sourceText, phaseText = "0") else it },
            "Reset $count ${category.title} rows only.")
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        stopAll()
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        rows = emptyList(); batchTargets = emptyMap(); batchPhases = emptyMap(); progress = 0f; busy = true
        status = "Reading audio metadata. No full-file PCM cache will be created..."
        scope.launch {
            runCatching {
                val probed = withContext(Dispatchers.IO) { StreamingAudioProbe.probe(context, uri) }
                source = probed
                status = "Streaming pass 1/2: finding frequency centers..."
                val detected = withContext(Dispatchers.IO) {
                    StreamingPitchAnalyzer.analyze(context, probed, 128) { p -> mainHandler.post { progress = (p * 0.5f).coerceIn(0f, 0.5f) } }
                }
                status = "Streaming pass 2/2: classifying vocal, hidden vocal, bass, instrument..."
                val labels = withContext(Dispatchers.IO) {
                    StreamingContentAnalyzer.analyze(context, probed, detected) { p -> mainHandler.post { progress = (0.5f + p * 0.5f).coerceIn(0f, 1f) } }
                }
                rows = detected.map { note ->
                    val hz = String.format(Locale.US, "%.3f", note.frequencyHz)
                    val info = labels[note.midi]
                    MappingRowV6(
                        id = note.midi.toLong(), noteLabel = note.label,
                        category = AudioCategoryResolver.resolve(note, info), tags = info?.tags ?: listOf("TONAL"),
                        sourceText = hz, targetText = hz
                    )
                }
                progress = 1f
                status = "Ready: ${rows.size} centers. Analysis used streaming decoder passes, not a giant temporary PCM file."
            }.onFailure { error ->
                source = null; rows = emptyList(); status = "Audio analysis failed: ${error.message}"
            }
            busy = false
        }
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { destination ->
        val from = savedUri
        if (destination == null || from == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; progress = 0f; status = "Streaming saved result to selected destination..."
            runCatching {
                withContext(Dispatchers.IO) {
                    StreamingLargeAudioExportManager.copySavedToDocument(context, from, destination) { p -> mainHandler.post { progress = p } }
                }
            }.onSuccess { status = "COPY SAVED." }.onFailure { status = "Copy failed: ${it.message}" }
            busy = false
        }
    }

    val changedCount = rows.count { row ->
        if (!row.enabled) false else {
            val a = row.sourceText.toDoubleOrNull(); val b = row.targetText.toDoubleOrNull(); val p = row.phaseText.toDoubleOrNull() ?: 0.0
            a != null && b != null && (abs(a - b) > 0.0001 || abs(normalizedPhaseV6(p)) > 0.0001)
        }
    }

    fun renderAndSave() {
        val s = source ?: return
        val mappings = parseMappings(rows, false)
        if (changedCount == 0) { Toast.makeText(context, "Change a target Hz or phase first.", Toast.LENGTH_LONG).show(); return }
        stopAll(); busy = true; progress = 0f
        status = "STREAMING RENDER: original URI → DSP → final public WAV. No decoded source copy..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    StreamingLargeAudioExportManager.renderAndSave(
                        context, s, AudioSaver.defaultOutputName(s.sourceName), mappings, options()
                    ) { p -> mainHandler.post { progress = p.coerceIn(0f, 1f) } }
                }
            }.onSuccess { result ->
                savedUri = result.uri; savedLabel = result.label; progress = 1f
                status = "SAVED: ${result.label} • ${formatBytesV6(result.bytes)} • ${if (result.rf64) "RF64" else "WAV"}. Input PCM cache used: 0 bytes."
                Toast.makeText(context, "Saved", Toast.LENGTH_LONG).show()
            }.onFailure { error -> status = "RENDER/SAVE FAILED: ${error.message}" }
            busy = false
        }
    }

    val present = categoryOrder.filter { cat -> rows.any { it.category == cat } }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("FREQUENCY REMAPPER LIVE v1.5", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("True URI streaming • self-healing live preview • category-isolated batches")
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (source == null) "CHOOSE AUDIO" else "CHOOSE DIFFERENT AUDIO")
                        }
                        source?.let {
                            Text(it.sourceName, fontWeight = FontWeight.Bold)
                            Text("${it.sampleRate} Hz • ${it.channels} ch • ${formatDurationV6(it)}")
                            Text("Estimated final PCM WAV: ${formatBytesV6(StreamingPhaseRenderer.estimatedFileBytes(it))}")
                            Text("Full decoded PCM cache: NONE")
                        }
                        Text(status)
                        if (busy) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (source != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { source?.let { playUri(it.uri, "Playing original audio.") } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("PLAY ORIGINAL") }
                        OutlinedButton(onClick = { stopAll() }, modifier = Modifier.weight(1f)) { Text("STOP") }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("LIVE PREVIEW", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("Streams from the original URI, loops at EOF, and automatically rebuilds transient decoder/audio-output failures.")
                            Button(onClick = {
                                if (liveOn) { liveEngine.stop(); liveOn = false; status = "Live preview stopped." } else startLive()
                            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (liveOn) "STOP LIVE PREVIEW" else "START LIVE PREVIEW") }
                            Text(if (liveOn) "● LIVE • STREAMING • AUTO-RECOVERY" else "Preview off")
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("SHIFT HARMONIC FAMILY", fontWeight = FontWeight.Bold)
                                    Text(if (shiftHarmonics) "Frequency + phase follow harmonics." else "Only local selected bands change.")
                                }
                                Switch(checked = shiftHarmonics, onCheckedChange = { shiftHarmonics = it; updateLive(shift = it) }, enabled = !busy)
                            }
                            Text("Isolation width: ${bandCents.roundToInt()} cents")
                            Slider(value = bandCents, onValueChange = { bandCents = it; updateLive(cents = it) }, valueRange = 18f..90f, enabled = !busy)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("CATEGORY MAP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("$changedCount changed • ${rows.size} rows")
                        }
                        OutlinedButton(onClick = {
                            replaceRows(rows + MappingRowV6(System.nanoTime(), "EXACT", AudioCategory.CUSTOM, listOf("CUSTOM EXACT"), "440.000", "440.000", manual = true))
                        }, enabled = !busy) { Text("+ EXACT") }
                    }
                }
            }

            for (category in present) {
                val group = rows.filter { it.category == category }
                item(key = "batch_${category.name}") {
                    CategoryBatchCardV6(
                        category, group.size,
                        batchTargets[category].orEmpty(), batchPhases[category].orEmpty(), !busy,
                        onTargetChange = { batchTargets = batchTargets + (category to sanitizeHzV6(it)) },
                        onPhaseChange = { batchPhases = batchPhases + (category to sanitizePhaseV6(it)) },
                        onApplyTarget = { applyBatchTarget(category) },
                        onApplyPhase = { applyBatchPhase(category) },
                        onReset = { resetCategory(category) }
                    )
                }
                items(group, key = { it.id }) { row ->
                    MappingCardV6(row, !busy, onChange = { changed -> replaceRows(rows.map { if (it.id == changed.id) changed else it }) },
                        onDelete = if (row.manual) ({ replaceRows(rows.filterNot { it.id == row.id }) }) else null)
                }
            }

            if (source != null) {
                item {
                    HorizontalDivider()
                    Button(onClick = { renderAndSave() }, enabled = !busy && changedCount > 0, modifier = Modifier.fillMaxWidth()) {
                        Text("STREAM RENDER + SAVE ($changedCount CHANGED)")
                    }
                    Text("Original URI is decoded directly into the final file. Input-side full PCM storage = 0 bytes.")
                }
            }

            if (savedUri != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("LAST SAVED RESULT", fontWeight = FontWeight.Black)
                            Text(savedLabel ?: "Saved audio")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { savedUri?.let { playUri(it, "Playing saved result.") } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("PLAY RESULT") }
                                OutlinedButton(onClick = { saveCopyLauncher.launch("frequency-remapped-copy.wav") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("SAVE COPY ANYWHERE") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun CategoryBatchCardV6(
    category: AudioCategory, count: Int, target: String, phase: String, enabled: Boolean,
    onTargetChange: (String) -> Unit, onPhaseChange: (String) -> Unit,
    onApplyTarget: () -> Unit, onApplyPhase: () -> Unit, onReset: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(category.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("$count detected center${if (count == 1) "" else "s"} • ${category.detail}")
            Text("Batch controls affect ONLY ${category.title}.", fontWeight = FontWeight.Bold)
            OutlinedTextField(value = target, onValueChange = onTargetChange, label = { Text("ALL ${category.title} → Hz") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            Button(onClick = onApplyTarget, enabled = enabled && target.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("APPLY Hz TO ${category.title} ONLY") }
            OutlinedTextField(value = phase, onValueChange = onPhaseChange, label = { Text("ALL ${category.title} PHASE °") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            Button(onClick = onApplyPhase, enabled = enabled && phase.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("APPLY PHASE TO ${category.title} ONLY") }
            OutlinedButton(onClick = onReset, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("RESET ${category.title} ONLY") }
        }
    }
}

@Composable
private fun MappingCardV6(row: MappingRowV6, enabled: Boolean, onChange: (MappingRowV6) -> Unit, onDelete: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.noteLabel, fontWeight = FontWeight.Black)
                    Text(row.tags.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = row.enabled, onCheckedChange = { onChange(row.copy(enabled = it)) }, enabled = enabled)
                if (onDelete != null) { Spacer(Modifier.width(6.dp)); OutlinedButton(onClick = onDelete, enabled = enabled) { Text("DELETE") } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = row.sourceText, onValueChange = { if (row.manual) onChange(row.copy(sourceText = sanitizeHzV6(it))) }, label = { Text("FROM Hz") }, readOnly = !row.manual, singleLine = true, enabled = enabled && row.enabled, modifier = Modifier.weight(1f))
                OutlinedTextField(value = row.targetText, onValueChange = { onChange(row.copy(targetText = sanitizeHzV6(it))) }, label = { Text("TO Hz") }, singleLine = true, enabled = enabled && row.enabled, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = row.phaseText, onValueChange = { onChange(row.copy(phaseText = sanitizePhaseV6(it))) }, label = { Text("PHASE °") }, singleLine = true, enabled = enabled && row.enabled, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun sanitizeHzV6(value: String): String {
    var dot = false
    return buildString { for (c in value) if (c.isDigit()) append(c) else if (c == '.' && !dot) { append(c); dot = true } }.take(12)
}

private fun sanitizePhaseV6(value: String): String {
    var dot = false; var sign = true
    return buildString {
        for (c in value) when {
            c.isDigit() -> { append(c); sign = false }
            c == '.' && !dot -> { append(c); dot = true; sign = false }
            (c == '-' || c == '+') && sign -> { append(c); sign = false }
        }
    }.take(12)
}

private fun normalizedPhaseV6(v: Double): Double { var x = v % 360.0; if (x > 180.0) x -= 360.0; if (x <= -180.0) x += 360.0; return x }
private fun trimNumberV6(v: Double): String = if (abs(v - v.toLong()) < 1e-9) v.toLong().toString() else String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
private fun formatDurationV6(s: StreamAudioSource): String { val sec = if (s.durationUs > 0) s.durationUs / 1_000_000 else s.totalFrames / s.sampleRate; return "%d:%02d".format(Locale.US, sec / 60, sec % 60) }
private fun formatBytesV6(bytes: Long): String { val mib = bytes.toDouble() / 1048576.0; return if (mib < 1024.0) String.format(Locale.US, "%.1f MB", mib) else String.format(Locale.US, "%.2f GB", mib / 1024.0) }

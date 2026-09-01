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

private data class MappingRowV5(
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

class MainActivityV5 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LargeAudioExportManager.cleanupAbandonedPending(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { FrequencyRemapperV5Screen() }
            }
        }
    }
}

@Composable
private fun FrequencyRemapperV5Screen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val player = remember { MediaPlayer() }
    val liveEngine = remember { ScalableLivePreviewEngine() }

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourcePcm by remember { mutableStateOf<PcmSource?>(null) }
    var savedUri by remember { mutableStateOf(LargeAudioExportManager.recoverLastSavedUri(context)) }
    var savedLabel by remember { mutableStateOf(LargeAudioExportManager.recoverLastSavedLabel(context)) }
    var rows by remember { mutableStateOf<List<MappingRowV5>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Choose audio. v1.4 uses scalable live DSP and direct large-file saving.") }
    var shiftHarmonics by remember { mutableStateOf(true) }
    var bandCents by remember { mutableFloatStateOf(48f) }
    var livePreviewOn by remember { mutableStateOf(false) }
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

    fun parseMappings(candidateRows: List<MappingRowV5>, changedOnly: Boolean): List<PhaseFrequencyMapping> {
        val source = sourcePcm ?: return emptyList()
        val nyquist = source.sampleRate / 2.0
        return candidateRows.mapNotNull { row ->
            if (!row.enabled) return@mapNotNull null
            val from = row.sourceText.toDoubleOrNull() ?: return@mapNotNull null
            val to = row.targetText.toDoubleOrNull() ?: return@mapNotNull null
            val phase = row.phaseText.toDoubleOrNull() ?: 0.0
            if (!from.isFinite() || !to.isFinite() || !phase.isFinite()) return@mapNotNull null
            if (from <= 0.0 || to <= 0.0 || from >= nyquist || to >= nyquist) return@mapNotNull null
            val changed = abs(from - to) > 0.0001 || abs(normalizedPhaseV5(phase)) > 0.0001
            if (changedOnly && !changed) return@mapNotNull null
            PhaseFrequencyMapping(from, to, phase, true)
        }
    }

    fun updateLive(
        candidateRows: List<MappingRowV5> = rows,
        shift: Boolean = shiftHarmonics,
        cents: Float = bandCents
    ) {
        if (livePreviewOn) {
            liveEngine.update(parseMappings(candidateRows, changedOnly = true), options(shift, cents))
        }
    }

    fun replaceRows(newRows: List<MappingRowV5>, message: String? = null) {
        rows = newRows
        updateLive(newRows)
        if (message != null) status = message
    }

    fun stopPlayer() {
        runCatching { if (player.isPlaying) player.stop() }
    }

    fun stopAll() {
        stopPlayer()
        liveEngine.stop()
        livePreviewOn = false
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
            liveEngine.close()
            sourcePcm?.file?.delete()
        }
    }

    fun playUri(uri: Uri, label: String) {
        liveEngine.stop()
        livePreviewOn = false
        runCatching {
            player.reset()
            if (uri.scheme == "file") player.setDataSource(requireNotNull(uri.path))
            else player.setDataSource(context, uri)
            player.prepare()
            player.start()
            status = label
        }.onFailure { status = "Playback failed: ${it.message}" }
    }

    fun startLive() {
        val source = sourcePcm ?: return
        stopPlayer()
        livePreviewOn = true
        status = "Starting optimized looping live preview..."
        liveEngine.start(
            source = source,
            mappings = parseMappings(rows, changedOnly = true),
            options = options(),
            onError = { message ->
                mainHandler.post {
                    livePreviewOn = false
                    status = "LIVE PREVIEW STOPPED SAFELY: $message"
                    Toast.makeText(context, "Live preview: $message", Toast.LENGTH_LONG).show()
                }
            },
            onEnded = { mainHandler.post { livePreviewOn = false } }
        )
        status = "LIVE PREVIEW ON. It loops at EOF; edits rebuild a compiled DSP map instead of rescanning every frame."
    }

    fun applyBatchTarget(category: AudioCategory) {
        val source = sourcePcm ?: return
        val text = batchTargets[category].orEmpty()
        val hz = text.toDoubleOrNull()
        val nyquist = source.sampleRate / 2.0
        if (hz == null || !hz.isFinite() || hz <= 0.0 || hz >= nyquist) {
            Toast.makeText(context, "Enter a target between 0 and ${nyquist.roundToInt()} Hz.", Toast.LENGTH_LONG).show()
            return
        }
        val exact = trimNumberV5(hz)
        val affected = rows.count { it.category == category }
        val newRows = rows.map { if (it.category == category) it.copy(targetText = exact) else it }
        replaceRows(newRows, "Set $affected ${category.title} rows to exactly $exact Hz. No other category was touched.")
    }

    fun applyBatchPhase(category: AudioCategory) {
        val text = batchPhases[category].orEmpty()
        val phase = text.toDoubleOrNull()
        if (phase == null || !phase.isFinite()) {
            Toast.makeText(context, "Enter a valid phase in degrees.", Toast.LENGTH_LONG).show()
            return
        }
        val exact = trimNumberV5(phase)
        val affected = rows.count { it.category == category }
        val newRows = rows.map { if (it.category == category) it.copy(phaseText = exact) else it }
        replaceRows(newRows, "Set phase for $affected ${category.title} rows to $exact°. Other categories stayed unchanged.")
    }

    fun resetCategory(category: AudioCategory) {
        val affected = rows.count { it.category == category }
        val newRows = rows.map {
            if (it.category == category) it.copy(targetText = it.sourceText, phaseText = "0") else it
        }
        replaceRows(newRows, "Reset $affected ${category.title} rows only.")
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { destination ->
        if (destination == null) return@rememberLauncherForActivityResult
        val from = savedUri
        if (from == null) {
            status = "No saved render is available to copy."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            progress = 0f
            status = "Streaming saved audio to the location you selected..."
            runCatching {
                withContext(Dispatchers.IO) {
                    LargeAudioExportManager.copySavedToDocument(context, from, destination) { p ->
                        mainHandler.post { progress = p.coerceIn(0f, 1f) }
                    }
                }
            }.onSuccess {
                progress = 1f
                status = "COPY SAVED. Large files are streamed instead of loaded into memory."
            }.onFailure { error ->
                status = "Copy failed: ${error.message}. The primary saved render is untouched."
            }
            busy = false
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        stopAll()
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        sourceUri = uri
        rows = emptyList()
        batchTargets = emptyMap()
        batchPhases = emptyMap()
        progress = 0f
        busy = true
        status = "Decoding audio as a streamed PCM source..."

        scope.launch {
            var decoded: PcmSource? = null
            runCatching {
                decoded = withContext(Dispatchers.IO) { AudioFileDecoder.decodeToPcm16(context, uri) }
                sourcePcm?.file?.delete()
                sourcePcm = decoded
                status = "Finding note-frequency centers..."
                val detected = withContext(Dispatchers.IO) {
                    PitchAnalyzer.analyze(decoded!!, maxNotes = 128) { p ->
                        mainHandler.post { progress = (p * 0.50f).coerceIn(0f, 0.50f) }
                    }
                }
                status = "Separating primary groups: vocal, low vocal, hidden vocal, bass, instrument..."
                val content = withContext(Dispatchers.IO) {
                    AudioContentLabelAnalyzer.analyze(decoded!!, detected) { p ->
                        mainHandler.post { progress = (0.50f + p * 0.50f).coerceIn(0f, 1f) }
                    }
                }

                rows = detected.map { note ->
                    val hz = String.format(Locale.US, "%.3f", note.frequencyHz)
                    val info = content[note.midi]
                    MappingRowV5(
                        id = note.midi.toLong(),
                        noteLabel = note.label,
                        category = AudioCategoryResolver.resolve(note, info),
                        tags = info?.tags ?: listOf("TONAL"),
                        sourceText = hz,
                        targetText = hz,
                        phaseText = "0"
                    )
                }
                progress = 1f
                val groups = rows.map { it.category }.distinct().size
                status = if (rows.isEmpty()) {
                    "Decoded. Add a CUSTOM EXACT mapping."
                } else {
                    "Detected ${rows.size} centers in $groups primary groups. Each row has one batch category plus its secondary tags."
                }
            }.onFailure { error ->
                decoded?.file?.takeIf { it != sourcePcm?.file }?.delete()
                sourcePcm = null
                rows = emptyList()
                status = "Audio analysis failed: ${error.message}"
            }
            busy = false
        }
    }

    val changedCount = rows.count { row ->
        if (!row.enabled) false else {
            val a = row.sourceText.toDoubleOrNull()
            val b = row.targetText.toDoubleOrNull()
            val p = row.phaseText.toDoubleOrNull() ?: 0.0
            a != null && b != null && (abs(a - b) > 0.0001 || abs(normalizedPhaseV5(p)) > 0.0001)
        }
    }

    fun renderAndSave() {
        val source = sourcePcm ?: return
        val mappings = parseMappings(rows, changedOnly = false)
        if (changedCount == 0) {
            Toast.makeText(context, "Change a target Hz or phase first.", Toast.LENGTH_LONG).show()
            return
        }
        if (mappings.isEmpty()) {
            Toast.makeText(context, "Check the enabled frequency values.", Toast.LENGTH_LONG).show()
            return
        }

        stopAll()
        busy = true
        progress = 0f
        val predicted = ScalablePhaseRenderer.estimatedFileBytes(source)
        val rf64 = ScalablePhaseRenderer.requiresRf64(ScalablePhaseRenderer.estimatedDataBytes(source))
        status = "Rendering DIRECTLY to the final ${if (rf64) "RF64" else "WAV"} file (${formatBytesV5(predicted)} estimated)..."

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    LargeAudioExportManager.renderAndSave(
                        context = context,
                        source = source,
                        requestedName = AudioSaver.defaultOutputName(source.sourceName),
                        mappings = mappings,
                        options = options()
                    ) { p -> mainHandler.post { progress = p.coerceIn(0f, 1f) } }
                }
            }.onSuccess { result ->
                savedUri = result.uri
                savedLabel = result.label
                progress = 1f
                status = "SAVED: ${result.label} • ${formatBytesV5(result.bytes)} • ${if (result.rf64) "RF64 large WAV" else "standard WAV"}. No temp render copies were created."
                Toast.makeText(context, "Saved", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                status = "RENDER/SAVE FAILED: ${error.message}"
            }
            busy = false
        }
    }

    val presentCategories = categoryOrder.filter { category -> rows.any { it.category == category } }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("FREQUENCY REMAPPER LIVE v1.4", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Large-file direct save • compiled looping live preview • exclusive category batches")
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { audioPicker.launch(arrayOf("audio/*")) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (sourceUri == null) "CHOOSE AUDIO" else "CHOOSE DIFFERENT AUDIO") }
                        sourcePcm?.let { source ->
                            Text(source.sourceName, fontWeight = FontWeight.Bold)
                            Text("${source.sampleRate} Hz • ${source.channels} ch • ${formatDurationV5(source)}")
                            Text("Final PCM WAV estimate: ${formatBytesV5(ScalablePhaseRenderer.estimatedFileBytes(source))}")
                        }
                        Text(status)
                        if (busy) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (sourcePcm != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { sourceUri?.let { playUri(it, "Playing original audio.") } },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("PLAY ORIGINAL") }
                        OutlinedButton(onClick = { stopAll() }, modifier = Modifier.weight(1f)) { Text("STOP") }
                    }
                }

                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("LIVE PREVIEW", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("Compiled DSP plan: edits rebuild once, then playback uses fast bin lookups. Preview loops automatically at EOF.")
                            Button(
                                onClick = {
                                    if (livePreviewOn) {
                                        liveEngine.stop()
                                        livePreviewOn = false
                                        status = "Live preview stopped."
                                    } else startLive()
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (livePreviewOn) "STOP LIVE PREVIEW" else "START LIVE PREVIEW") }
                            Text(if (livePreviewOn) "● LIVE + LOOPING" else "Preview off")
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
                                Switch(
                                    checked = shiftHarmonics,
                                    onCheckedChange = {
                                        shiftHarmonics = it
                                        updateLive(shift = it)
                                    },
                                    enabled = !busy
                                )
                            }
                            Text("Isolation width: ${bandCents.roundToInt()} cents")
                            Slider(
                                value = bandCents,
                                onValueChange = {
                                    bandCents = it
                                    updateLive(cents = it)
                                },
                                valueRange = 18f..90f,
                                enabled = !busy
                            )
                        }
                    }
                }

                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("CATEGORY MAP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("$changedCount changed • ${rows.size} detected rows")
                        }
                        OutlinedButton(
                            onClick = {
                                val newRows = rows + MappingRowV5(
                                    id = System.nanoTime(),
                                    noteLabel = "EXACT",
                                    category = AudioCategory.CUSTOM,
                                    tags = listOf("CUSTOM EXACT"),
                                    sourceText = "440.000",
                                    targetText = "440.000",
                                    phaseText = "0",
                                    enabled = true,
                                    manual = true
                                )
                                replaceRows(newRows)
                            },
                            enabled = !busy
                        ) { Text("+ EXACT") }
                    }
                }
            }

            for (category in presentCategories) {
                val categoryRows = rows.filter { it.category == category }
                item(key = "batch_${category.name}") {
                    CategoryBatchCardV5(
                        category = category,
                        count = categoryRows.size,
                        target = batchTargets[category].orEmpty(),
                        phase = batchPhases[category].orEmpty(),
                        enabled = !busy,
                        onTargetChange = { value ->
                            batchTargets = batchTargets + (category to sanitizeHzV5(value))
                        },
                        onPhaseChange = { value ->
                            batchPhases = batchPhases + (category to sanitizePhaseV5(value))
                        },
                        onApplyTarget = { applyBatchTarget(category) },
                        onApplyPhase = { applyBatchPhase(category) },
                        onReset = { resetCategory(category) }
                    )
                }

                items(categoryRows, key = { it.id }) { row ->
                    MappingCardV5(
                        row = row,
                        enabled = !busy,
                        onChange = { changed ->
                            val newRows = rows.map { if (it.id == changed.id) changed else it }
                            replaceRows(newRows)
                        },
                        onDelete = if (row.manual) {
                            {
                                val newRows = rows.filterNot { it.id == row.id }
                                replaceRows(newRows)
                            }
                        } else null
                    )
                }
            }

            if (sourcePcm != null) {
                item {
                    HorizontalDivider()
                    Button(
                        onClick = { renderAndSave() },
                        enabled = !busy && changedCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("RENDER DIRECT + SAVE ($changedCount CHANGED)") }
                    Text("No cache→master→public copy chain. Files above classic WAV size automatically use RF64.")
                }
            }

            if (savedUri != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("LAST SAVED RESULT", fontWeight = FontWeight.Black)
                            Text(savedLabel ?: "Saved audio")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { savedUri?.let { playUri(it, "Playing saved result.") } },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("PLAY RESULT") }
                                OutlinedButton(
                                    onClick = { saveCopyLauncher.launch("frequency-remapped-copy.wav") },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("SAVE COPY ANYWHERE") }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun CategoryBatchCardV5(
    category: AudioCategory,
    count: Int,
    target: String,
    phase: String,
    enabled: Boolean,
    onTargetChange: (String) -> Unit,
    onPhaseChange: (String) -> Unit,
    onApplyTarget: () -> Unit,
    onApplyPhase: () -> Unit,
    onReset: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(category.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("$count frequency center${if (count == 1) "" else "s"} • ${category.detail}")
            Text("BATCH CONTROLS affect ONLY this primary category.", fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = target,
                onValueChange = onTargetChange,
                label = { Text("SET ALL ${category.title} TO Hz") },
                supportingText = { Text("Example: 510 makes every row in this group target exactly 510 Hz.") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onApplyTarget, enabled = enabled && target.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("APPLY TARGET TO ${category.title} ONLY")
            }

            OutlinedTextField(
                value = phase,
                onValueChange = onPhaseChange,
                label = { Text("SET ALL ${category.title} PHASE °") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = onApplyPhase, enabled = enabled && phase.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("APPLY PHASE TO ${category.title} ONLY")
            }
            OutlinedButton(onClick = onReset, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("RESET ${category.title} ONLY")
            }
        }
    }
}

@Composable
private fun MappingCardV5(
    row: MappingRowV5,
    enabled: Boolean,
    onChange: (MappingRowV5) -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${row.category.title} • ${row.noteLabel}", fontWeight = FontWeight.Black)
                    Text(row.tags.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                }
                Switch(row.enabled, { onChange(row.copy(enabled = it)) }, enabled = enabled)
                if (onDelete != null) {
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = onDelete, enabled = enabled) { Text("DELETE") }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = row.sourceText,
                    onValueChange = { if (row.manual) onChange(row.copy(sourceText = sanitizeHzV5(it))) },
                    label = { Text("FROM Hz") },
                    readOnly = !row.manual,
                    singleLine = true,
                    enabled = enabled && row.enabled,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = row.targetText,
                    onValueChange = { onChange(row.copy(targetText = sanitizeHzV5(it))) },
                    label = { Text("TO Hz") },
                    singleLine = true,
                    enabled = enabled && row.enabled,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = row.phaseText,
                onValueChange = { onChange(row.copy(phaseText = sanitizePhaseV5(it))) },
                label = { Text("CUSTOM PHASE SHIFT °") },
                singleLine = true,
                enabled = enabled && row.enabled,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onChange(row.copy(phaseText = "0")) }, enabled = enabled && row.enabled, modifier = Modifier.weight(1f)) { Text("0°") }
                OutlinedButton(onClick = { onChange(row.copy(phaseText = "90")) }, enabled = enabled && row.enabled, modifier = Modifier.weight(1f)) { Text("+90°") }
                OutlinedButton(onClick = { onChange(row.copy(phaseText = "180")) }, enabled = enabled && row.enabled, modifier = Modifier.weight(1f)) { Text("180°") }
            }
        }
    }
}

private fun sanitizeHzV5(value: String): String {
    val out = buildString {
        var dot = false
        for (c in value) {
            if (c.isDigit()) append(c)
            else if (c == '.' && !dot) {
                append(c)
                dot = true
            }
        }
    }
    return out.take(14)
}

private fun sanitizePhaseV5(value: String): String {
    val out = buildString {
        var dot = false
        var signAllowed = true
        for (c in value) {
            when {
                c.isDigit() -> {
                    append(c)
                    signAllowed = false
                }
                c == '.' && !dot -> {
                    append(c)
                    dot = true
                    signAllowed = false
                }
                (c == '-' || c == '+') && signAllowed -> {
                    append(c)
                    signAllowed = false
                }
            }
        }
    }
    return out.take(14)
}

private fun normalizedPhaseV5(value: Double): Double {
    var x = value % 360.0
    if (x > 180.0) x -= 360.0
    if (x <= -180.0) x += 360.0
    return x
}

private fun trimNumberV5(value: Double): String {
    val rounded = String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
    return if (rounded == "-0") "0" else rounded
}

private fun formatDurationV5(source: PcmSource): String {
    val seconds = if (source.durationUs > 0) source.durationUs / 1_000_000L else source.totalFrames / source.sampleRate
    return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}

private fun formatBytesV5(bytes: Long): String {
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mib < 1024.0) String.format(Locale.US, "%.1f MB", mib)
    else String.format(Locale.US, "%.2f GB", mib / 1024.0)
}

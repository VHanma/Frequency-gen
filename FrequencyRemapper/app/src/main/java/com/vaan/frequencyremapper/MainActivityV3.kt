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
import androidx.compose.foundation.lazy.itemsIndexed
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
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private data class MappingRowV3(
    val id: Long,
    val label: String,
    val sourceText: String,
    val targetText: String,
    val enabled: Boolean = true,
    val manual: Boolean = false
)

class MainActivityV3 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { FrequencyRemapperV3Screen() }
            }
        }
    }
}

@Composable
private fun FrequencyRemapperV3Screen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val player = remember { MediaPlayer() }
    val liveEngine = remember { LivePreviewEngine() }

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourcePcm by remember { mutableStateOf<PcmSource?>(null) }
    var renderedFile by remember { mutableStateOf(PublicExportManager.recoverLastRendered(context)) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    var rows by remember { mutableStateOf<List<MappingRowV3>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Choose audio. Live preview and saving use the rebuilt stable paths.") }
    var shiftHarmonics by remember { mutableStateOf(true) }
    var bandCents by remember { mutableFloatStateOf(48f) }
    var livePreviewOn by remember { mutableStateOf(false) }

    fun options(shift: Boolean = shiftHarmonics, cents: Float = bandCents) = RemapOptions(
        shiftHarmonicFamily = shift,
        bandCents = cents.toDouble()
    )

    fun parseMappings(candidateRows: List<MappingRowV3>, changedOnly: Boolean): List<FrequencyMapping> {
        val source = sourcePcm ?: return emptyList()
        val nyquist = source.sampleRate / 2.0
        return candidateRows.mapNotNull { row ->
            if (!row.enabled) return@mapNotNull null
            val from = row.sourceText.toDoubleOrNull() ?: return@mapNotNull null
            val to = row.targetText.toDoubleOrNull() ?: return@mapNotNull null
            if (from <= 0.0 || to <= 0.0 || from >= nyquist || to >= nyquist) return@mapNotNull null
            if (changedOnly && abs(from - to) <= 0.0001) return@mapNotNull null
            FrequencyMapping(from, to, true)
        }
    }

    fun updateLive(candidateRows: List<MappingRowV3> = rows, shift: Boolean = shiftHarmonics, cents: Float = bandCents) {
        if (livePreviewOn) {
            liveEngine.update(parseMappings(candidateRows, changedOnly = true), options(shift, cents))
        }
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
            // Durable rendered files are intentionally NOT deleted here.
            sourcePcm?.file?.delete()
        }
    }

    fun playOriginal() {
        val uri = sourceUri ?: return
        liveEngine.stop()
        livePreviewOn = false
        runCatching {
            player.reset()
            player.setDataSource(context, uri)
            player.prepare()
            player.start()
            status = "Playing original audio."
        }.onFailure { status = "Original playback failed: ${it.message}" }
    }

    fun playResult() {
        val file = renderedFile ?: PublicExportManager.recoverLastRendered(context) ?: return
        runCatching {
            liveEngine.stop()
            livePreviewOn = false
            player.reset()
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            status = "Playing durable rendered result."
        }.onFailure { status = "Result playback failed: ${it.message}" }
    }

    fun startLive() {
        val source = sourcePcm ?: return
        stopPlayer()
        livePreviewOn = true
        status = "Starting safe live preview..."
        liveEngine.start(
            source = source,
            mappings = parseMappings(rows, changedOnly = true),
            options = options(),
            onError = { message ->
                mainHandler.post {
                    livePreviewOn = false
                    status = "LIVE PREVIEW STOPPED SAFELY: $message"
                    Toast.makeText(context, "Live preview stopped: $message", Toast.LENGTH_LONG).show()
                }
            },
            onEnded = {
                mainHandler.post { livePreviewOn = false }
            }
        )
        status = "LIVE PREVIEW ON. Edit any TO Hz field while audio is playing."
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { destination ->
        if (destination == null) return@rememberLauncherForActivityResult
        val file = renderedFile ?: PublicExportManager.recoverLastRendered(context)
        if (file == null) {
            status = "No rendered WAV is available to save."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            status = "Writing selected copy and verifying every byte..."
            runCatching {
                withContext(Dispatchers.IO) { PublicExportManager.copyToDocument(context, file, destination) }
            }.onSuccess {
                savedUri = destination
                status = "SAVED + VERIFIED to the location you selected."
                Toast.makeText(context, "Saved + verified", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                status = "Selected-location save failed: ${error.message}. The durable rendered master is still kept inside the app."
                Toast.makeText(context, "Save failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        stopAll()
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        sourceUri = uri
        rows = emptyList()
        savedUri = null
        progress = 0f
        busy = true
        status = "Decoding audio..."
        scope.launch {
            var decoded: PcmSource? = null
            runCatching {
                decoded = withContext(Dispatchers.IO) { AudioFileDecoder.decodeToPcm16(context, uri) }
                sourcePcm?.file?.delete()
                sourcePcm = decoded
                status = "Analyzing note-frequency centers..."
                val detected = withContext(Dispatchers.IO) {
                    PitchAnalyzer.analyze(decoded!!) { p -> mainHandler.post { progress = p.coerceIn(0f, 1f) } }
                }
                rows = detected.map { note ->
                    val hz = String.format(Locale.US, "%.3f", note.frequencyHz)
                    MappingRowV3(note.midi.toLong(), note.label, hz, hz)
                }
                progress = 1f
                status = if (rows.isEmpty()) {
                    "Decoded. Add an EXACT mapping below."
                } else {
                    "Detected ${rows.size} frequency centers. Start LIVE PREVIEW and edit TO Hz."
                }
            }.onFailure { error ->
                decoded?.file?.takeIf { it != sourcePcm?.file }?.delete()
                sourcePcm = null
                rows = emptyList()
                status = "Audio load failed: ${error.message}"
            }
            busy = false
        }
    }

    val changedCount = rows.count { row ->
        if (!row.enabled) false else {
            val a = row.sourceText.toDoubleOrNull()
            val b = row.targetText.toDoubleOrNull()
            a != null && b != null && abs(a - b) > 0.0001
        }
    }

    fun renderAndSavePublic() {
        val source = sourcePcm ?: return
        val parsed = parseMappings(rows, changedOnly = false)
        if (changedCount == 0) {
            Toast.makeText(context, "Change at least one TO Hz value first.", Toast.LENGTH_LONG).show()
            return
        }
        if (parsed.isEmpty()) {
            Toast.makeText(context, "Check the enabled frequency values.", Toast.LENGTH_LONG).show()
            return
        }
        stopAll()
        busy = true
        progress = 0f
        status = "Rendering full-quality remapped WAV..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val temp = SpectralRemapper.render(
                        context,
                        source,
                        parsed,
                        options()
                    ) { p -> mainHandler.post { progress = p.coerceIn(0f, 1f) } }
                    val durable = PublicExportManager.persistRendered(context, temp, source.sourceName)
                    val public = PublicExportManager.exportAutomatically(
                        context,
                        durable,
                        durable.name
                    )
                    durable to public
                }
            }.onSuccess { result ->
                renderedFile = result.first
                savedUri = result.second.uri
                progress = 1f
                status = "SAVED + VERIFIED: ${result.second.label}/${result.first.name} (${result.second.bytes} bytes)."
                Toast.makeText(context, "Saved + verified", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                renderedFile = PublicExportManager.recoverLastRendered(context)
                progress = 1f
                status = if (renderedFile != null) {
                    "Render is safely kept, but Android blocked automatic public export: ${error.message}. Tap SAVE COPY ANYWHERE."
                } else {
                    "Render/save failed: ${error.message}"
                }
            }
            busy = false
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("FREQUENCY REMAPPER LIVE v1.2", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Crash-safe live tuning + durable verified saving.")
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
                            Text("${source.sampleRate} Hz • ${source.channels} ch • ${formatDurationV3(source)}")
                        }
                        Text(status)
                        if (busy) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (sourcePcm != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { playOriginal() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("PLAY ORIGINAL") }
                        OutlinedButton(onClick = { stopAll() }, modifier = Modifier.weight(1f)) { Text("STOP") }
                    }
                }

                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("LIVE PREVIEW", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("Start once. While it plays, type new TO Hz values. Only changed mappings are processed live.")
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
                            Text(if (livePreviewOn) "● LIVE" else "Preview off")
                        }
                    }
                }

                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("SHIFT HARMONIC FAMILY", fontWeight = FontWeight.Bold)
                                    Text(if (shiftHarmonics) "Moves the selected frequency and its harmonic family." else "Moves only the local selected band.")
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
                            Text("FREQUENCY MAP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("$changedCount changed • ${rows.size} rows")
                        }
                        OutlinedButton(
                            onClick = {
                                val newRows = rows + MappingRowV3(
                                    System.nanoTime(), "EXACT", "440.000", "440.000", true, true
                                )
                                rows = newRows
                                updateLive(newRows)
                            },
                            enabled = !busy
                        ) { Text("+ EXACT") }
                    }
                }
            }

            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                MappingCardV3(
                    row,
                    enabled = !busy,
                    onChange = { changed ->
                        val newRows = rows.toMutableList().also { it[index] = changed }
                        rows = newRows
                        updateLive(newRows)
                    },
                    onDelete = if (row.manual) {
                        {
                            val newRows = rows.filterNot { it.id == row.id }
                            rows = newRows
                            updateLive(newRows)
                        }
                    } else null
                )
            }

            if (sourcePcm != null) {
                item {
                    HorizontalDivider()
                    Button(
                        onClick = { renderAndSavePublic() },
                        enabled = !busy && changedCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("RENDER + SAVE ($changedCount CHANGED)") }
                    Text("Primary save: Music/FrequencyRemapper. If blocked, Downloads/FrequencyRemapper is tried automatically.")
                }
            }

            if (renderedFile != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("DURABLE RENDERED AUDIO", fontWeight = FontWeight.Black)
                            Text(renderedFile!!.name)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { playResult() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("PLAY RESULT") }
                                OutlinedButton(
                                    onClick = { saveCopyLauncher.launch(renderedFile!!.name) },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("SAVE COPY ANYWHERE") }
                            }
                            savedUri?.let { Text("Verified public URI: $it") }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun MappingCardV3(
    row: MappingRowV3,
    enabled: Boolean,
    onChange: (MappingRowV3) -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Switch(row.enabled, { onChange(row.copy(enabled = it)) }, enabled = enabled)
                if (onDelete != null) {
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = onDelete, enabled = enabled) { Text("DELETE") }
                }
            }
            if (row.manual) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = row.sourceText,
                        onValueChange = { onChange(row.copy(sourceText = sanitizeHzV3(it))) },
                        label = { Text("FROM Hz") },
                        singleLine = true,
                        enabled = enabled && row.enabled,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = row.targetText,
                        onValueChange = { onChange(row.copy(targetText = sanitizeHzV3(it))) },
                        label = { Text("TO Hz") },
                        singleLine = true,
                        enabled = enabled && row.enabled,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("FROM", style = MaterialTheme.typography.labelSmall)
                        Text("${row.sourceText} Hz", fontWeight = FontWeight.Bold)
                    }
                    Text("→")
                    OutlinedTextField(
                        value = row.targetText,
                        onValueChange = { onChange(row.copy(targetText = sanitizeHzV3(it))) },
                        label = { Text("TO Hz") },
                        singleLine = true,
                        enabled = enabled && row.enabled,
                        modifier = Modifier.weight(1.35f)
                    )
                }
            }
        }
    }
}

private fun sanitizeHzV3(value: String): String {
    val out = buildString {
        var dot = false
        value.forEach { c ->
            if (c.isDigit()) append(c)
            else if (c == '.' && !dot) {
                append(c)
                dot = true
            }
        }
    }
    return out.take(12)
}

private fun formatDurationV3(source: PcmSource): String {
    val seconds = if (source.durationUs > 0) source.durationUs / 1_000_000L else source.totalFrames / source.sampleRate
    return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}

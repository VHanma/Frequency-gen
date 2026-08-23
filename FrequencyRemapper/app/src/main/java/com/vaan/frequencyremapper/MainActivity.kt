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
import androidx.compose.material3.ExperimentalMaterial3Api
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

private data class MappingRow(
    val id: Long,
    val label: String,
    val sourceText: String,
    val targetText: String,
    val enabled: Boolean = true,
    val manual: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FrequencyRemapperScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun FrequencyRemapperScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val player = remember { MediaPlayer() }

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourcePcm by remember { mutableStateOf<PcmSource?>(null) }
    var renderedFile by remember { mutableStateOf<File?>(null) }
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }
    var rows by remember { mutableStateOf<List<MappingRow>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Choose any audio file to begin.") }
    var shiftHarmonics by remember { mutableStateOf(true) }
    var bandCents by remember { mutableFloatStateOf(48f) }
    var showAdvanced by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
            sourcePcm?.file?.delete()
            renderedFile?.delete()
        }
    }

    fun playOriginal() {
        val uri = sourceUri ?: return
        runCatching {
            player.reset()
            player.setDataSource(context, uri)
            player.prepare()
            player.start()
        }.onFailure {
            Toast.makeText(context, "Could not play source: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun playRendered() {
        val file = renderedFile ?: return
        runCatching {
            player.reset()
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
        }.onFailure {
            Toast.makeText(context, "Could not play result: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun stopPlayback() {
        runCatching {
            if (player.isPlaying) player.stop()
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { destination ->
        val file = renderedFile
        if (destination != null && file != null) {
            scope.launch {
                busy = true
                status = "Saving another copy..."
                runCatching {
                    withContext(Dispatchers.IO) {
                        AudioSaver.copyToUri(context, file, destination)
                    }
                }.onSuccess {
                    lastSavedUri = destination
                    status = "Saved another copy successfully."
                }.onFailure {
                    status = "Save failed: ${it.message}"
                }
                busy = false
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            sourceUri = uri
            renderedFile?.delete()
            renderedFile = null
            lastSavedUri = null
            rows = emptyList()
            progress = 0f
            status = "Decoding the selected audio..."
            busy = true

            scope.launch {
                var decoded: PcmSource? = null
                runCatching {
                    decoded = withContext(Dispatchers.IO) {
                        AudioFileDecoder.decodeToPcm16(context, uri)
                    }
                    sourcePcm?.file?.delete()
                    sourcePcm = decoded
                    status = "Reading note-frequency centers..."
                    progress = 0f

                    val detected = withContext(Dispatchers.IO) {
                        PitchAnalyzer.analyze(decoded!!) { p ->
                            mainHandler.post { progress = p.coerceIn(0f, 1f) }
                        }
                    }
                    rows = detected.map { note ->
                        val hz = String.format(Locale.US, "%.3f", note.frequencyHz)
                        MappingRow(
                            id = note.midi.toLong(),
                            label = note.label,
                            sourceText = hz,
                            targetText = hz,
                            enabled = true,
                            manual = false
                        )
                    }
                    progress = 1f
                    status = if (rows.isEmpty()) {
                        "Decoded, but no stable note centers were found. Add an exact frequency mapping manually."
                    } else {
                        "Detected ${rows.size} note-frequency centers. Change any target Hz you want."
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
    }

    val changedCount = rows.count { row ->
        if (!row.enabled) return@count false
        val from = row.sourceText.toDoubleOrNull()
        val to = row.targetText.toDoubleOrNull()
        from != null && to != null && abs(from - to) > 0.0001
    }

    fun renderAndSave() {
        val source = sourcePcm ?: return
        val nyquist = source.sampleRate / 2.0
        val parsed = ArrayList<FrequencyMapping>()
        for (row in rows) {
            if (!row.enabled) continue
            val from = row.sourceText.toDoubleOrNull()
            val to = row.targetText.toDoubleOrNull()
            if (from == null || to == null || from <= 0.0 || to <= 0.0) {
                Toast.makeText(context, "Every enabled FROM and TO value must be a positive number.", Toast.LENGTH_LONG).show()
                return
            }
            if (from >= nyquist || to >= nyquist) {
                Toast.makeText(
                    context,
                    "For this file, frequencies must stay below ${nyquist.roundToInt()} Hz.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            parsed += FrequencyMapping(from, to, enabled = true)
        }

        if (parsed.none { abs(it.sourceHz - it.targetHz) > 0.0001 }) {
            Toast.makeText(context, "Change at least one target frequency first.", Toast.LENGTH_LONG).show()
            return
        }

        stopPlayback()
        busy = true
        progress = 0f
        status = "Remapping the actual spectral frequencies..."
        scope.launch {
            runCatching {
                val result = withContext(Dispatchers.IO) {
                    val file = SpectralRemapper.render(
                        context = context,
                        source = source,
                        mappings = parsed,
                        options = RemapOptions(
                            shiftHarmonicFamily = shiftHarmonics,
                            bandCents = bandCents.toDouble()
                        )
                    ) { p -> mainHandler.post { progress = p.coerceIn(0f, 1f) } }
                    val uri = AudioSaver.saveToMusic(
                        context,
                        file,
                        AudioSaver.defaultOutputName(source.sourceName)
                    )
                    file to uri
                }
                renderedFile?.delete()
                renderedFile = result.first
                lastSavedUri = result.second
                progress = 1f
                status = "Rendered and saved in Music/FrequencyRemapper."
            }.onFailure { error ->
                status = "Render failed: ${error.message}"
            }
            busy = false
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "FREQUENCY REMAPPER",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
                Text(
                    "Detect the note-frequency centers inside audio, replace any of them with arbitrary Hz values, then render and save the changed waveform.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { audioPicker.launch(arrayOf("audio/*")) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (sourceUri == null) "CHOOSE AUDIO" else "CHOOSE DIFFERENT AUDIO")
                        }

                        sourcePcm?.let { source ->
                            Text(source.sourceName, fontWeight = FontWeight.Bold)
                            Text(
                                "${source.sampleRate} Hz sample rate  •  ${source.channels} channel${if (source.channels == 1) "" else "s"}  •  ${formatDuration(source)}"
                            )
                        }

                        Text(status)
                        if (busy) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (sourcePcm != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { playOriginal() }, modifier = Modifier.weight(1f), enabled = !busy) {
                            Text("PLAY ORIGINAL")
                        }
                        OutlinedButton(onClick = { stopPlayback() }, modifier = Modifier.weight(1f)) {
                            Text("STOP")
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("SHIFT HARMONIC FAMILY", fontWeight = FontWeight.Bold)
                                    Text(
                                        if (shiftHarmonics) {
                                            "Example: 440→510 also moves 880→1020 and the higher harmonic family."
                                        } else {
                                            "Only the local band around each selected source frequency is moved."
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = shiftHarmonics,
                                    onCheckedChange = { shiftHarmonics = it },
                                    enabled = !busy
                                )
                            }

                            OutlinedButton(
                                onClick = { showAdvanced = !showAdvanced },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (showAdvanced) "HIDE PRECISION CONTROL" else "PRECISION CONTROL")
                            }

                            if (showAdvanced) {
                                Text("Isolation width: ${bandCents.roundToInt()} cents")
                                Slider(
                                    value = bandCents,
                                    onValueChange = { bandCents = it },
                                    valueRange = 18f..90f,
                                    enabled = !busy
                                )
                                Text(
                                    "Narrower isolates a tighter frequency neighborhood. Wider captures more of a drifting or expressive note.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("FREQUENCY MAP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("$changedCount changed • ${rows.size} rows")
                        }
                        OutlinedButton(
                            onClick = {
                                val id = System.nanoTime()
                                rows = rows + MappingRow(
                                    id = id,
                                    label = "EXACT",
                                    sourceText = "440.000",
                                    targetText = "440.000",
                                    enabled = true,
                                    manual = true
                                )
                            },
                            enabled = !busy
                        ) {
                            Text("+ EXACT")
                        }
                    }
                }
            }

            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                FrequencyRow(
                    row = row,
                    enabled = !busy,
                    onChange = { changed ->
                        rows = rows.toMutableList().also { it[index] = changed }
                    },
                    onDelete = if (row.manual) {
                        { rows = rows.filterNot { it.id == row.id } }
                    } else null
                )
            }

            if (sourcePcm != null) {
                item {
                    HorizontalDivider()
                    Button(
                        onClick = { renderAndSave() },
                        enabled = !busy && changedCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("RENDER + SAVE ($changedCount CHANGED)")
                    }
                    Text(
                        "This creates a new WAV in Music/FrequencyRemapper. Your original file stays untouched.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (renderedFile != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("YOUR REMAPPED AUDIO", fontWeight = FontWeight.Black)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { playRendered() },
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy
                                ) {
                                    Text("PLAY RESULT")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val name = sourcePcm?.let { AudioSaver.defaultOutputName(it.sourceName) }
                                            ?: "frequency-remapped.wav"
                                        saveAsLauncher.launch(name)
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy
                                ) {
                                    Text("SAVE AS…")
                                }
                            }
                            lastSavedUri?.let { Text("Saved: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@androidx.compose.runtime.Composable
private fun FrequencyRow(
    row: MappingRow,
    enabled: Boolean,
    onChange: (MappingRow) -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(row.label, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Switch(
                    checked = row.enabled,
                    onCheckedChange = { onChange(row.copy(enabled = it)) },
                    enabled = enabled
                )
                if (onDelete != null) {
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = onDelete, enabled = enabled) { Text("DELETE") }
                }
            }

            if (row.manual) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = row.sourceText,
                        onValueChange = { onChange(row.copy(sourceText = sanitizeHzText(it))) },
                        label = { Text("FROM Hz") },
                        singleLine = true,
                        enabled = enabled && row.enabled,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = row.targetText,
                        onValueChange = { onChange(row.copy(targetText = sanitizeHzText(it))) },
                        label = { Text("TO Hz") },
                        singleLine = true,
                        enabled = enabled && row.enabled,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("FROM", style = MaterialTheme.typography.labelSmall)
                        Text("${row.sourceText} Hz", fontWeight = FontWeight.Bold)
                    }
                    Text("→", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = row.targetText,
                        onValueChange = { onChange(row.copy(targetText = sanitizeHzText(it))) },
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

private fun sanitizeHzText(value: String): String {
    val filtered = buildString {
        var dotSeen = false
        for (c in value) {
            when {
                c.isDigit() -> append(c)
                c == '.' && !dotSeen -> {
                    append(c)
                    dotSeen = true
                }
            }
        }
    }
    return filtered.take(12)
}

private fun formatDuration(source: PcmSource): String {
    val seconds = if (source.durationUs > 0L) {
        source.durationUs / 1_000_000L
    } else {
        source.totalFrames / source.sampleRate
    }
    val minutes = seconds / 60
    val remainder = seconds % 60
    return "%d:%02d".format(Locale.US, minutes, remainder)
}

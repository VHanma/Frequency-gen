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

private data class MappingRowV2(
    val id: Long,
    val label: String,
    val sourceText: String,
    val targetText: String,
    val enabled: Boolean = true,
    val manual: Boolean = false
)

class MainActivityV2 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FrequencyRemapperV2Screen()
                }
            }
        }
    }
}

@Composable
private fun FrequencyRemapperV2Screen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val player = remember { MediaPlayer() }
    val liveEngine = remember { LivePreviewEngine() }

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourcePcm by remember { mutableStateOf<PcmSource?>(null) }
    var renderedFile by remember { mutableStateOf<File?>(null) }
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }
    var rows by remember { mutableStateOf<List<MappingRowV2>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Choose any audio file to begin.") }
    var shiftHarmonics by remember { mutableStateOf(true) }
    var bandCents by remember { mutableFloatStateOf(48f) }
    var showAdvanced by remember { mutableStateOf(false) }
    var livePreviewOn by remember { mutableStateOf(false) }

    fun options(shift: Boolean = shiftHarmonics, cents: Float = bandCents) = RemapOptions(
        shiftHarmonicFamily = shift,
        bandCents = cents.toDouble()
    )

    fun parsedForLive(candidateRows: List<MappingRowV2>): List<FrequencyMapping> {
        val source = sourcePcm ?: return emptyList()
        val nyquist = source.sampleRate / 2.0
        return candidateRows.mapNotNull { row ->
            if (!row.enabled) return@mapNotNull null
            val from = row.sourceText.toDoubleOrNull() ?: return@mapNotNull null
            val to = row.targetText.toDoubleOrNull() ?: return@mapNotNull null
            if (from <= 0.0 || to <= 0.0 || from >= nyquist || to >= nyquist) return@mapNotNull null
            FrequencyMapping(from, to, enabled = true)
        }
    }

    fun updateLive(
        candidateRows: List<MappingRowV2> = rows,
        shift: Boolean = shiftHarmonics,
        cents: Float = bandCents
    ) {
        if (livePreviewOn) {
            liveEngine.update(parsedForLive(candidateRows), options(shift, cents))
        }
    }

    fun stopMediaPlayer() {
        runCatching { if (player.isPlaying) player.stop() }
    }

    fun stopEverything() {
        stopMediaPlayer()
        liveEngine.stop()
        livePreviewOn = false
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
            liveEngine.close()
            sourcePcm?.file?.delete()
            renderedFile?.delete()
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
            status = "Playing the original audio."
        }.onFailure {
            Toast.makeText(context, "Could not play source: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun playRendered() {
        val file = renderedFile ?: return
        liveEngine.stop()
        livePreviewOn = false
        runCatching {
            player.reset()
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            status = "Playing the fully rendered remapped audio."
        }.onFailure {
            Toast.makeText(context, "Could not play result: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun startLivePreview() {
        val source = sourcePcm ?: return
        stopMediaPlayer()
        runCatching {
            liveEngine.start(source, parsedForLive(rows), options())
            livePreviewOn = true
            status = "LIVE PREVIEW ON — edit any TO Hz value while this is playing. Changes are streamed into the audio."
        }.onFailure {
            livePreviewOn = false
            status = "Live preview failed: ${it.message}"
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { destination ->
        val file = renderedFile
        if (destination == null) {
            if (file != null) status = "Save canceled. Your rendered audio is still available below; tap SAVE AS when ready."
        } else if (file != null) {
            scope.launch {
                busy = true
                status = "Writing and verifying the saved WAV..."
                runCatching {
                    withContext(Dispatchers.IO) {
                        ReliableAudioSaver.copyToUri(context, file, destination)
                    }
                }.onSuccess {
                    lastSavedUri = destination
                    status = "SAVED + VERIFIED. Android reopened the destination and confirmed the entire WAV matches."
                    Toast.makeText(context, "Saved and verified", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    status = "SAVE FAILED: ${error.message}. The rendered audio is still available; choose SAVE AS and try another folder/provider."
                    Toast.makeText(context, "Save failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
                busy = false
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            stopEverything()
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
                    decoded = withContext(Dispatchers.IO) { AudioFileDecoder.decodeToPcm16(context, uri) }
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
                        MappingRowV2(
                            id = note.midi.toLong(),
                            label = note.label,
                            sourceText = hz,
                            targetText = hz
                        )
                    }
                    progress = 1f
                    status = if (rows.isEmpty()) {
                        "Decoded. No stable note centers were found automatically; add an EXACT mapping."
                    } else {
                        "Detected ${rows.size} note-frequency centers. Start LIVE PREVIEW, then change any TO Hz value."
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

    fun renderThenChooseSaveLocation() {
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
                Toast.makeText(context, "Frequencies must stay below ${nyquist.roundToInt()} Hz for this audio.", Toast.LENGTH_LONG).show()
                return
            }
            parsed += FrequencyMapping(from, to, enabled = true)
        }
        if (parsed.none { abs(it.sourceHz - it.targetHz) > 0.0001 }) {
            Toast.makeText(context, "Change at least one target frequency first.", Toast.LENGTH_LONG).show()
            return
        }

        stopEverything()
        busy = true
        progress = 0f
        status = "Rendering the complete remapped WAV..."
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    SpectralRemapper.render(
                        context = context,
                        source = source,
                        mappings = parsed,
                        options = options()
                    ) { p -> mainHandler.post { progress = p.coerceIn(0f, 1f) } }
                }
            }.onSuccess { file ->
                renderedFile?.delete()
                renderedFile = file
                lastSavedUri = null
                progress = 1f
                busy = false
                status = "Render complete. Choose the exact location for the WAV in Android's Save screen."
                saveAsLauncher.launch(AudioSaver.defaultOutputName(source.sourceName))
            }.onFailure { error ->
                status = "Render failed: ${error.message}"
                busy = false
            }
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("FREQUENCY REMAPPER LIVE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Change the actual spectral frequencies, audition the changes while audio is playing, then render and save a verified WAV.")
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { audioPicker.launch(arrayOf("audio/*")) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (sourceUri == null) "CHOOSE AUDIO" else "CHOOSE DIFFERENT AUDIO") }
                        sourcePcm?.let { source ->
                            Text(source.sourceName, fontWeight = FontWeight.Bold)
                            Text("${source.sampleRate} Hz • ${source.channels} channel${if (source.channels == 1) "" else "s"} • ${formatDurationV2(source)}")
                        }
                        Text(status)
                        if (busy) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (sourcePcm != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { playOriginal() }, modifier = Modifier.weight(1f), enabled = !busy) { Text("PLAY ORIGINAL") }
                        OutlinedButton(onClick = { stopEverything() }, modifier = Modifier.weight(1f)) { Text("STOP") }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("LIVE PREVIEW", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("Start this once, then edit TO Hz values while the song is playing. Valid changes feed into the spectral remapper continuously without waiting for a full render.")
                            Button(
                                onClick = {
                                    if (livePreviewOn) {
                                        liveEngine.stop()
                                        livePreviewOn = false
                                        status = "Live preview stopped."
                                    } else startLivePreview()
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (livePreviewOn) "STOP LIVE PREVIEW" else "START LIVE PREVIEW") }
                            Text(if (livePreviewOn) "● LIVE — edits are audible now" else "Preview is off")
                        }
                    }
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("SHIFT HARMONIC FAMILY", fontWeight = FontWeight.Bold)
                                    Text(if (shiftHarmonics) "440→510 also moves 880→1020 and higher harmonics." else "Only the local band around each source frequency is moved.")
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
                            OutlinedButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (showAdvanced) "HIDE PRECISION CONTROL" else "PRECISION CONTROL")
                            }
                            if (showAdvanced) {
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
                }

                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("FREQUENCY MAP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("$changedCount changed • ${rows.size} rows")
                        }
                        OutlinedButton(
                            onClick = {
                                val newRows = rows + MappingRowV2(
                                    id = System.nanoTime(),
                                    label = "EXACT",
                                    sourceText = "440.000",
                                    targetText = "440.000",
                                    manual = true
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
                FrequencyRowV2(
                    row = row,
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
                        onClick = { renderThenChooseSaveLocation() },
                        enabled = !busy && changedCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("RENDER + CHOOSE SAVE LOCATION ($changedCount)") }
                    Text("After rendering, Android's own Save screen opens. Pick the exact folder/file destination. The app reads the saved WAV back and verifies its full byte count and SHA-256 before saying success.")
                }
            }

            if (renderedFile != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("YOUR REMAPPED AUDIO", fontWeight = FontWeight.Black)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { playRendered() }, modifier = Modifier.weight(1f), enabled = !busy) { Text("PLAY RESULT") }
                                OutlinedButton(
                                    onClick = {
                                        val name = sourcePcm?.let { AudioSaver.defaultOutputName(it.sourceName) } ?: "frequency-remapped.wav"
                                        saveAsLauncher.launch(name)
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy
                                ) { Text("SAVE AS") }
                            }
                            lastSavedUri?.let { Text("Verified destination: $it") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FrequencyRowV2(
    row: MappingRowV2,
    enabled: Boolean,
    onChange: (MappingRowV2) -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = row.sourceText,
                        onValueChange = { onChange(row.copy(sourceText = sanitizeHzTextV2(it))) },
                        label = { Text("FROM Hz") }, singleLine = true,
                        enabled = enabled && row.enabled, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = row.targetText,
                        onValueChange = { onChange(row.copy(targetText = sanitizeHzTextV2(it))) },
                        label = { Text("TO Hz") }, singleLine = true,
                        enabled = enabled && row.enabled, modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("FROM", style = MaterialTheme.typography.labelSmall)
                        Text("${row.sourceText} Hz", fontWeight = FontWeight.Bold)
                    }
                    Text("→", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = row.targetText,
                        onValueChange = { onChange(row.copy(targetText = sanitizeHzTextV2(it))) },
                        label = { Text("TO Hz") }, singleLine = true,
                        enabled = enabled && row.enabled, modifier = Modifier.weight(1.35f)
                    )
                }
            }
        }
    }
}

private fun sanitizeHzTextV2(value: String): String {
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

private fun formatDurationV2(source: PcmSource): String {
    val seconds = if (source.durationUs > 0L) source.durationUs / 1_000_000L else source.totalFrames / source.sampleRate
    return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}

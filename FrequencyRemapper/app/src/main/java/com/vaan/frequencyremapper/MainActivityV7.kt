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
import kotlin.math.max
import kotlin.math.roundToInt

private data class RuleDraftV7(
    val mode: CategoryFrequencyMode = CategoryFrequencyMode.EXACT,
    val valueText: String = "",
    val phaseText: String = "0",
    val threshold: Float = 0.45f,
    val enabled: Boolean = false
)

private data class RowDraftV7(
    val sourceText: String,
    val targetText: String,
    val phaseText: String = "0",
    val enabled: Boolean = true
)

class MainActivityV7 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SourceAwareExportManager.cleanup(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { FrequencyRemapperV7Screen() }
            }
        }
    }
}

@Composable
private fun FrequencyRemapperV7Screen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val player = remember { MediaPlayer() }
    val liveEngine = remember { SourceAwareLivePreviewEngine(context.applicationContext) }

    var source by remember { mutableStateOf<StreamAudioSource?>(null) }
    var objects by remember { mutableStateOf<List<SpectralObject>>(emptyList()) }
    var rowDrafts by remember { mutableStateOf<Map<Long, RowDraftV7>>(emptyMap()) }
    var ruleDrafts by remember { mutableStateOf<Map<AudioCategory, RuleDraftV7>>(emptyMap()) }
    var manualRegions by remember { mutableStateOf<List<ManualMaskRegion>>(emptyList()) }
    var soloCategory by remember { mutableStateOf<AudioCategory?>(null) }
    var soloConfidence by remember { mutableFloatStateOf(0.45f) }
    var shiftHarmonics by remember { mutableStateOf(true) }
    var bandCents by remember { mutableFloatStateOf(48f) }
    var liveOn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember {
        mutableStateOf("Choose audio. v1.6 builds frame-local source masks for Spectral Solo and category-isolated editing.")
    }
    var savedUri by remember { mutableStateOf(SourceAwareExportManager.recoverLastUri(context)) }
    var savedLabel by remember { mutableStateOf(SourceAwareExportManager.recoverLastLabel(context)) }

    var regionStart by remember { mutableStateOf("0") }
    var regionEnd by remember { mutableStateOf("5") }
    var regionLow by remember { mutableStateOf("80") }
    var regionHigh by remember { mutableStateOf("1200") }
    var regionCategory by remember { mutableStateOf(AudioCategory.VOCAL) }

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

    fun parseRules(
        drafts: Map<AudioCategory, RuleDraftV7> = ruleDrafts,
        src: StreamAudioSource? = source
    ): List<CategoryTransformRule> {
        val nyquist = (src?.sampleRate ?: 48000) / 2.0
        return categoryOrder.mapNotNull { category ->
            val draft = drafts[category] ?: return@mapNotNull null
            if (!draft.enabled) return@mapNotNull null
            val raw = draft.valueText.toDoubleOrNull()?.takeIf { it.isFinite() }
            val value = when (draft.mode) {
                CategoryFrequencyMode.EXACT -> raw?.takeIf { it > 0.0 && it < nyquist }
                CategoryFrequencyMode.RATIO -> raw?.takeIf { it > 0.0 }
                CategoryFrequencyMode.OFFSET_HZ,
                CategoryFrequencyMode.SEMITONES -> raw
            }
            val phase = draft.phaseText.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
            if (value == null && abs(normalizedPhaseV7(phase)) <= 0.0001) return@mapNotNull null
            CategoryTransformRule(
                category = category,
                mode = draft.mode,
                value = value,
                phaseDegrees = phase,
                confidenceThreshold = draft.threshold.toDouble(),
                enabled = true
            )
        }
    }

    fun parseEdits(
        candidateObjects: List<SpectralObject> = objects,
        drafts: Map<Long, RowDraftV7> = rowDrafts,
        src: StreamAudioSource? = source
    ): List<SpectralFrequencyEdit> {
        val nyquist = (src?.sampleRate ?: 48000) / 2.0
        return candidateObjects.mapNotNull { obj ->
            val draft = drafts[obj.id] ?: return@mapNotNull null
            if (!draft.enabled) return@mapNotNull null
            val from = draft.sourceText.toDoubleOrNull() ?: return@mapNotNull null
            val to = draft.targetText.toDoubleOrNull() ?: return@mapNotNull null
            val phase = draft.phaseText.toDoubleOrNull() ?: 0.0
            if (!from.isFinite() || !to.isFinite() || !phase.isFinite()) return@mapNotNull null
            if (from <= 0.0 || to <= 0.0 || from >= nyquist || to >= nyquist) return@mapNotNull null
            if (abs(from - to) <= 0.0001 && abs(normalizedPhaseV7(phase)) <= 0.0001) return@mapNotNull null
            SpectralFrequencyEdit(
                objectId = obj.id,
                sourceHz = from,
                targetHz = to,
                phaseDegrees = phase,
                category = obj.primaryCategory,
                enabled = true
            )
        }
    }

    fun updateLive(
        candidateObjects: List<SpectralObject> = objects,
        candidateRows: Map<Long, RowDraftV7> = rowDrafts,
        candidateRules: Map<AudioCategory, RuleDraftV7> = ruleDrafts,
        candidateSolo: AudioCategory? = soloCategory,
        candidateSoloConfidence: Float = soloConfidence,
        candidateRegions: List<ManualMaskRegion> = manualRegions,
        shift: Boolean = shiftHarmonics,
        cents: Float = bandCents
    ) {
        if (!liveOn) return
        liveEngine.update(
            objects = candidateObjects,
            edits = parseEdits(candidateObjects, candidateRows),
            rules = parseRules(candidateRules),
            options = options(shift, cents),
            soloCategory = candidateSolo,
            soloConfidence = candidateSoloConfidence.toDouble(),
            manualRegions = candidateRegions
        )
    }

    fun stopPlayer() {
        runCatching { if (player.isPlaying) player.stop() }
    }

    fun stopAll() {
        stopPlayer()
        liveEngine.stop()
        liveOn = false
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
            liveEngine.close()
        }
    }

    fun playUri(uri: Uri, message: String) {
        liveEngine.stop()
        liveOn = false
        runCatching {
            player.reset()
            player.setDataSource(context, uri)
            player.prepare()
            player.start()
            status = message
        }.onFailure { status = "Playback failed: ${it.message}" }
    }

    fun startLive() {
        val src = source ?: return
        stopPlayer()
        liveOn = true
        status = "Starting source-aware live preview..."
        liveEngine.start(
            source = src,
            objects = objects,
            edits = parseEdits(),
            rules = parseRules(),
            options = options(),
            soloCategory = soloCategory,
            soloConfidence = soloConfidence.toDouble(),
            manualRegions = manualRegions,
            onError = { message ->
                mainHandler.post {
                    liveOn = false
                    status = "LIVE PREVIEW STOPPED: $message"
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
            onEnded = { mainHandler.post { liveOn = false } }
        )
        status = if (soloCategory == null) {
            "LIVE SOURCE-AWARE PREVIEW ON. Category rules are evaluated frame-by-frame."
        } else {
            "SPECTRAL SOLO: ${soloCategory!!.title}. You are hearing the dry source mask before category remapping."
        }
    }

    fun setSolo(category: AudioCategory?) {
        soloCategory = category
        updateLive(candidateSolo = category)
        status = if (category == null) "Spectral Solo cleared." else "Spectral Solo armed: ${category.title}. Start Live Preview to audition it."
    }

    fun updateRule(category: AudioCategory, draft: RuleDraftV7) {
        val next = ruleDrafts + (category to draft)
        ruleDrafts = next
        updateLive(candidateRules = next)
    }

    fun updateRow(id: Long, draft: RowDraftV7) {
        val next = rowDrafts + (id to draft)
        rowDrafts = next
        updateLive(candidateRows = next)
    }

    fun overrideCategory(obj: SpectralObject) {
        val nextObject = obj.withNextCategory()
        val next = objects.map { if (it.id == obj.id) nextObject else it }
        objects = next
        updateLive(candidateObjects = next)
        status = "Manual category override: ${obj.noteLabel} → ${nextObject.primaryCategory.title}."
    }

    fun updateCustomSource(obj: SpectralObject, raw: String) {
        val clean = sanitizeHzV7(raw)
        val currentDraft = rowDrafts[obj.id] ?: RowDraftV7(formatHzV7(obj.sourceHz), formatHzV7(obj.sourceHz))
        val nextDrafts = rowDrafts + (obj.id to currentDraft.copy(sourceText = clean))
        val parsed = clean.toDoubleOrNull()
        val nextObjects = if (parsed != null && parsed.isFinite() && parsed > 0.0 && parsed < (source?.sampleRate ?: 48000) / 2.0) {
            objects.map { if (it.id == obj.id) it.copy(sourceHz = parsed) else it }
        } else objects
        rowDrafts = nextDrafts
        objects = nextObjects
        updateLive(candidateObjects = nextObjects, candidateRows = nextDrafts)
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        stopAll()
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        objects = emptyList()
        rowDrafts = emptyMap()
        ruleDrafts = emptyMap()
        manualRegions = emptyList()
        soloCategory = null
        progress = 0f
        busy = true
        status = "Reading metadata. No full-file PCM cache will be created..."
        scope.launch {
            runCatching {
                val probed = withContext(Dispatchers.IO) { StreamingAudioProbe.probe(context, uri) }
                source = probed
                status = "Streaming analysis 1/2: frequency centers..."
                val detected = withContext(Dispatchers.IO) {
                    StreamingPitchAnalyzer.analyze(context, probed, 128) { p ->
                        mainHandler.post { progress = (p * 0.50f).coerceIn(0f, 0.50f) }
                    }
                }
                status = "Streaming analysis 2/2: source priors for vocal/hidden vocal/bass/instrument..."
                val content = withContext(Dispatchers.IO) {
                    StreamingContentAnalyzer.analyze(context, probed, detected) { p ->
                        mainHandler.post { progress = (0.50f + p * 0.50f).coerceIn(0f, 1f) }
                    }
                }
                val maxEnergy = detected.maxOfOrNull { it.energy }?.coerceAtLeast(1e-20) ?: 1.0
                val created = detected.map { note ->
                    SpectralObjectFactory.create(note, content[note.midi], note.energy / maxEnergy)
                }
                objects = created
                rowDrafts = created.associate { obj ->
                    obj.id to RowDraftV7(
                        sourceText = formatHzV7(obj.sourceHz),
                        targetText = formatHzV7(obj.sourceHz),
                        phaseText = "0",
                        enabled = true
                    )
                }
                progress = 1f
                status = "Ready: ${created.size} spectral objects. Whole-track labels are priors; ownership is re-decided on every STFT frame during Solo/preview/render."
            }.onFailure { error ->
                source = null
                objects = emptyList()
                rowDrafts = emptyMap()
                status = "Audio analysis failed: ${error.message}"
            }
            busy = false
        }
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { destination ->
        val from = savedUri
        if (destination == null || from == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            progress = 0f
            status = "Streaming saved result to selected destination..."
            runCatching {
                withContext(Dispatchers.IO) {
                    SourceAwareExportManager.copySavedToDocument(context, from, destination) { p ->
                        mainHandler.post { progress = p.coerceIn(0f, 1f) }
                    }
                }
            }.onSuccess {
                progress = 1f
                status = "COPY SAVED."
            }.onFailure {
                status = "Copy failed: ${it.message}"
            }
            busy = false
        }
    }

    fun renderAndSave(renderSolo: AudioCategory?) {
        val src = source ?: return
        val edits = parseEdits()
        val rules = parseRules()
        if (renderSolo == null && edits.isEmpty() && rules.isEmpty()) {
            Toast.makeText(context, "Enable a category rule or change an individual frequency/phase first.", Toast.LENGTH_LONG).show()
            return
        }
        stopAll()
        busy = true
        progress = 0f
        status = if (renderSolo == null) {
            "Rendering source-aware edit graph directly to final WAV..."
        } else {
            "Rendering ${renderSolo.title} Spectral Solo directly to final WAV..."
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val baseName = AudioSaver.defaultOutputName(src.sourceName)
                    val name = if (renderSolo == null) baseName else {
                        baseName.substringBeforeLast('.') + "_${renderSolo.name.lowercase(Locale.US)}_solo.wav"
                    }
                    SourceAwareExportManager.renderAndSave(
                        context = context,
                        source = src,
                        requestedName = name,
                        objects = objects,
                        frequencyEdits = edits,
                        categoryRules = rules,
                        options = options(),
                        manualRegions = manualRegions,
                        soloCategory = renderSolo,
                        soloConfidence = soloConfidence.toDouble()
                    ) { p -> mainHandler.post { progress = p.coerceIn(0f, 1f) } }
                }
            }.onSuccess { result ->
                savedUri = result.uri
                savedLabel = result.label
                progress = 1f
                status = "SAVED: ${result.label} • ${formatBytesV7(result.bytes)} • ${if (result.rf64) "RF64" else "WAV"}. Source mask was evaluated frame-by-frame."
                Toast.makeText(context, "Saved", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                status = "RENDER/SAVE FAILED: ${error.message}"
            }
            busy = false
        }
    }

    fun addCustomExact() {
        val id = System.nanoTime()
        val obj = SpectralObject(
            id = id,
            noteLabel = "EXACT",
            sourceHz = 440.0,
            primaryCategory = AudioCategory.CUSTOM,
            tags = listOf("CUSTOM EXACT"),
            vocalScore = 0.0,
            shiftedVocalScore = 0.0,
            bassScore = 0.0,
            instrumentScore = 0.0,
            relativeEnergy = 1.0
        )
        val nextObjects = objects + obj
        val nextRows = rowDrafts + (id to RowDraftV7("440.000", "440.000"))
        objects = nextObjects
        rowDrafts = nextRows
        updateLive(candidateObjects = nextObjects, candidateRows = nextRows)
    }

    fun addManualRegion() {
        val start = regionStart.toDoubleOrNull()
        val end = regionEnd.toDoubleOrNull()
        val low = regionLow.toDoubleOrNull()
        val high = regionHigh.toDoubleOrNull()
        val src = source
        if (start == null || end == null || low == null || high == null || src == null ||
            !start.isFinite() || !end.isFinite() || !low.isFinite() || !high.isFinite() ||
            start < 0.0 || end < 0.0 || low <= 0.0 || high <= 0.0 || low >= src.sampleRate / 2.0 || high >= src.sampleRate / 2.0
        ) {
            Toast.makeText(context, "Check the time and Hz bounds for the manual mask region.", Toast.LENGTH_LONG).show()
            return
        }
        val region = ManualMaskRegion(System.nanoTime(), start, end, low, high, regionCategory)
        val next = manualRegions + region
        manualRegions = next
        updateLive(candidateRegions = next)
        status = "Manual time-frequency mask added: ${regionCategory.title} ${minOf(low, high)}-${maxOf(low, high)} Hz, ${minOf(start, end)}-${maxOf(start, end)} sec."
    }

    val graphRuleCount = parseRules().size
    val individualEditCount = parseEdits().size

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("FREQUENCY REMAPPER SPECTRAL v1.6", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Time-frequency source masks • Spectral Solo • Category Matrix • non-destructive edit graph")
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(
                            onClick = { audioPicker.launch(arrayOf("audio/*")) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (source == null) "CHOOSE AUDIO" else "CHOOSE DIFFERENT AUDIO") }
                        source?.let {
                            Text(it.sourceName, fontWeight = FontWeight.Bold)
                            Text("${it.sampleRate} Hz • ${it.channels} ch • ${formatDurationV7(it)}")
                            Text("Estimated final PCM WAV: ${formatBytesV7(SourceAwareStreamingRenderer.estimatedFileBytes(it))}")
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
                        OutlinedButton(
                            onClick = { source?.let { playUri(it.uri, "Playing original audio.") } },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("PLAY ORIGINAL") }
                        OutlinedButton(onClick = { stopAll() }, modifier = Modifier.weight(1f)) { Text("STOP") }
                    }
                }

                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("SOURCE-AWARE LIVE PREVIEW", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("Each STFT frame re-decides vocal / hidden vocal / bass / instrument ownership before applying the edit graph.")
                            Button(
                                onClick = {
                                    if (liveOn) {
                                        liveEngine.stop()
                                        liveOn = false
                                        status = "Live preview stopped."
                                    } else startLive()
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (liveOn) "STOP LIVE PREVIEW" else "START LIVE PREVIEW") }
                            Text(if (liveOn) "● LIVE • MASKED • STREAMING • AUTO-RECOVERY" else "Preview off")
                        }
                    }
                }

                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("SPECTRAL SOLO", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("Solo is a DRY mask audition: it lets you hear what the classifier currently thinks belongs to that source before remapping.")
                            Text("Solo confidence: ${(soloConfidence * 100).roundToInt()}%")
                            Slider(
                                value = soloConfidence,
                                onValueChange = {
                                    soloConfidence = it
                                    updateLive(candidateSoloConfidence = it)
                                },
                                valueRange = 0.20f..0.90f,
                                enabled = !busy
                            )
                            if (soloCategory != null) {
                                Text("ACTIVE: ${soloCategory!!.title}", fontWeight = FontWeight.Black)
                                OutlinedButton(onClick = { setSolo(null) }, modifier = Modifier.fillMaxWidth()) { Text("CLEAR SOLO") }
                            }
                        }
                    }
                }

                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("INDIVIDUAL HARMONIC FAMILY", fontWeight = FontWeight.Bold)
                                    Text(if (shiftHarmonics) "Individual row edits follow their harmonics." else "Individual row edits stay local to the selected band.")
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
                            Text("Individual isolation width: ${bandCents.roundToInt()} cents")
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
                            Text("CATEGORY MATRIX", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("$graphRuleCount active category rules • $individualEditCount individual edits")
                        }
                        OutlinedButton(onClick = { addCustomExact() }, enabled = !busy) { Text("+ EXACT") }
                    }
                }

                for (category in categoryOrder) {
                    val count = objects.count { it.primaryCategory == category }
                    item(key = "matrix_${category.name}") {
                        CategoryMatrixCardV7(
                            category = category,
                            count = count,
                            draft = ruleDrafts[category] ?: RuleDraftV7(),
                            solo = soloCategory == category,
                            enabled = !busy,
                            onDraftChange = { updateRule(category, it) },
                            onSolo = { setSolo(if (soloCategory == category) null else category) }
                        )
                    }
                }

                item {
                    HorizontalDivider()
                    Text("MANUAL SPECTRAL MASK REGIONS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("Precision override for time-frequency regions. A manual region outranks automatic classification during preview/render.")
                }

                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(regionStart, { regionStart = sanitizeSignedV7(it) }, label = { Text("START sec") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(regionEnd, { regionEnd = sanitizeSignedV7(it) }, label = { Text("END sec") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(regionLow, { regionLow = sanitizeHzV7(it) }, label = { Text("LOW Hz") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(regionHigh, { regionHigh = sanitizeHzV7(it) }, label = { Text("HIGH Hz") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            OutlinedButton(
                                onClick = {
                                    val editable = categoryOrder.filter { it != AudioCategory.CUSTOM }
                                    val i = editable.indexOf(regionCategory)
                                    regionCategory = editable[if (i < 0) 0 else (i + 1) % editable.size]
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("REGION CATEGORY: ${regionCategory.title}") }
                            Button(onClick = { addManualRegion() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("ADD MANUAL MASK REGION")
                            }
                        }
                    }
                }

                items(manualRegions, key = { "region_${it.id}" }) { region ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(region.category.title, fontWeight = FontWeight.Black)
                            Text("${minOf(region.startSeconds, region.endSeconds)}-${maxOf(region.startSeconds, region.endSeconds)} sec • ${minOf(region.lowHz, region.highHz)}-${maxOf(region.lowHz, region.highHz)} Hz")
                            OutlinedButton(
                                onClick = {
                                    val next = manualRegions.filterNot { it.id == region.id }
                                    manualRegions = next
                                    updateLive(candidateRegions = next)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("DELETE REGION") }
                        }
                    }
                }

                item {
                    HorizontalDivider()
                    Text("SPECTRAL OBJECTS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("Whole-track scores seed the mask. Use MOVE CATEGORY when a detector prior is wrong; live/render still re-check every frame.")
                }

                for (category in categoryOrder) {
                    val group = objects.filter { it.primaryCategory == category }
                    if (group.isNotEmpty()) {
                        item(key = "group_${category.name}") {
                            Text("${category.title} • ${group.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        }
                        items(group, key = { "obj_${it.id}" }) { obj ->
                            val draft = rowDrafts[obj.id] ?: RowDraftV7(formatHzV7(obj.sourceHz), formatHzV7(obj.sourceHz))
                            SpectralObjectCardV7(
                                obj = obj,
                                draft = draft,
                                enabled = !busy,
                                onDraftChange = { updateRow(obj.id, it) },
                                onCategoryOverride = { overrideCategory(obj) },
                                onCustomSourceChange = if (obj.tags.any { it.contains("CUSTOM EXACT", true) }) {
                                    { value -> updateCustomSource(obj, value) }
                                } else null,
                                onDelete = if (obj.tags.any { it.contains("CUSTOM EXACT", true) }) {
                                    {
                                        val nextObjects = objects.filterNot { it.id == obj.id }
                                        val nextRows = rowDrafts - obj.id
                                        objects = nextObjects
                                        rowDrafts = nextRows
                                        updateLive(candidateObjects = nextObjects, candidateRows = nextRows)
                                    }
                                } else null
                            )
                        }
                    }
                }

                item {
                    HorizontalDivider()
                    Button(
                        onClick = { renderAndSave(null) },
                        enabled = !busy && (graphRuleCount > 0 || individualEditCount > 0),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("RENDER SOURCE-AWARE FULL MIX") }
                    if (soloCategory != null) {
                        Button(
                            onClick = { renderAndSave(soloCategory) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("RENDER ${soloCategory!!.title} SOLO") }
                    }
                    Text("Category edits are applied to frame-local masks. The same Hz may be treated differently at different moments.")
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
                                    onClick = { saveCopyLauncher.launch("frequency-remapper-v1.6-copy.wav") },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("SAVE COPY") }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(36.dp)) }
        }
    }
}

@Composable
private fun CategoryMatrixCardV7(
    category: AudioCategory,
    count: Int,
    draft: RuleDraftV7,
    solo: Boolean,
    enabled: Boolean,
    onDraftChange: (RuleDraftV7) -> Unit,
    onSolo: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(category.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("$count spectral object${if (count == 1) "" else "s"} • ${category.detail}")
                }
                Switch(draft.enabled, { onDraftChange(draft.copy(enabled = it)) }, enabled = enabled)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onDraftChange(draft.copy(mode = draft.mode.next())) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text(draft.mode.title) }
                OutlinedButton(
                    onClick = onSolo,
                    enabled = enabled && category != AudioCategory.CUSTOM,
                    modifier = Modifier.weight(1f)
                ) { Text(if (solo) "STOP SOLO" else "SOLO") }
            }

            OutlinedTextField(
                value = draft.valueText,
                onValueChange = { onDraftChange(draft.copy(valueText = sanitizeSignedV7(it))) },
                label = { Text(draft.mode.valueLabel) },
                supportingText = {
                    Text(
                        when (draft.mode) {
                            CategoryFrequencyMode.EXACT -> "Every accepted bin in this category targets this exact frequency."
                            CategoryFrequencyMode.OFFSET_HZ -> "Preserves spacing by adding/subtracting Hz."
                            CategoryFrequencyMode.RATIO -> "Preserves spacing by multiplying every accepted frequency."
                            CategoryFrequencyMode.SEMITONES -> "Preserves musical spacing by pitch interval."
                        }
                    )
                },
                singleLine = true,
                enabled = enabled && draft.enabled,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = draft.phaseText,
                onValueChange = { onDraftChange(draft.copy(phaseText = sanitizeSignedV7(it))) },
                label = { Text("CATEGORY PHASE SHIFT °") },
                singleLine = true,
                enabled = enabled && draft.enabled,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Apply only when mask confidence ≥ ${(draft.threshold * 100).roundToInt()}%")
            Slider(
                value = draft.threshold,
                onValueChange = { onDraftChange(draft.copy(threshold = it)) },
                valueRange = 0.20f..0.95f,
                enabled = enabled && draft.enabled
            )

            OutlinedButton(
                onClick = { onDraftChange(RuleDraftV7()) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("RESET ${category.title} RULE") }
        }
    }
}

@Composable
private fun SpectralObjectCardV7(
    obj: SpectralObject,
    draft: RowDraftV7,
    enabled: Boolean,
    onDraftChange: (RowDraftV7) -> Unit,
    onCategoryOverride: () -> Unit,
    onCustomSourceChange: ((String) -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${obj.noteLabel} • ${formatHzV7(obj.sourceHz)} Hz", fontWeight = FontWeight.Black)
                    Text(obj.primaryCategory.title, fontWeight = FontWeight.Bold)
                    Text(obj.tags.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "V ${scoreV7(obj.vocalScore)} • HV ${scoreV7(obj.shiftedVocalScore)} • B ${scoreV7(obj.bassScore)} • I ${scoreV7(obj.instrumentScore)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(draft.enabled, { onDraftChange(draft.copy(enabled = it)) }, enabled = enabled)
            }

            OutlinedButton(onClick = onCategoryOverride, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("MOVE CATEGORY / MANUAL OVERRIDE")
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.sourceText,
                    onValueChange = { value -> if (onCustomSourceChange != null) onCustomSourceChange(value) },
                    label = { Text("FROM Hz") },
                    readOnly = onCustomSourceChange == null,
                    singleLine = true,
                    enabled = enabled && draft.enabled,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = draft.targetText,
                    onValueChange = { onDraftChange(draft.copy(targetText = sanitizeHzV7(it))) },
                    label = { Text("INDIVIDUAL TO Hz") },
                    singleLine = true,
                    enabled = enabled && draft.enabled,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = draft.phaseText,
                onValueChange = { onDraftChange(draft.copy(phaseText = sanitizeSignedV7(it))) },
                label = { Text("INDIVIDUAL PHASE °") },
                singleLine = true,
                enabled = enabled && draft.enabled,
                modifier = Modifier.fillMaxWidth()
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onDraftChange(draft.copy(targetText = draft.sourceText, phaseText = "0")) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text("RESET") }
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete, enabled = enabled, modifier = Modifier.weight(1f)) { Text("DELETE") }
                }
            }
        }
    }
}

private fun sanitizeHzV7(value: String): String {
    val out = buildString {
        var dot = false
        for (c in value) {
            when {
                c.isDigit() -> append(c)
                c == '.' && !dot -> { append(c); dot = true }
            }
        }
    }
    return out.take(14)
}

private fun sanitizeSignedV7(value: String): String {
    val out = buildString {
        var dot = false
        var signAllowed = true
        for (c in value) {
            when {
                c.isDigit() -> { append(c); signAllowed = false }
                c == '.' && !dot -> { append(c); dot = true; signAllowed = false }
                (c == '-' || c == '+') && signAllowed -> { append(c); signAllowed = false }
            }
        }
    }
    return out.take(14)
}

private fun normalizedPhaseV7(value: Double): Double {
    var x = value % 360.0
    if (x > 180.0) x -= 360.0
    if (x <= -180.0) x += 360.0
    return x
}

private fun formatHzV7(value: Double): String = String.format(Locale.US, "%.3f", value)
private fun scoreV7(value: Double): String = String.format(Locale.US, "%.2f", value.coerceIn(0.0, 1.0))

private fun formatDurationV7(source: StreamAudioSource): String {
    val seconds = if (source.durationUs > 0L) source.durationUs / 1_000_000L
    else source.totalFrames / max(1, source.sampleRate)
    return "%d:%02d".format(Locale.US, seconds / 60L, seconds % 60L)
}

private fun formatBytesV7(bytes: Long): String {
    if (bytes < 0L) return "unknown"
    val mib = bytes.toDouble() / 1048576.0
    return if (mib < 1024.0) String.format(Locale.US, "%.1f MB", mib)
    else String.format(Locale.US, "%.2f GB", mib / 1024.0)
}

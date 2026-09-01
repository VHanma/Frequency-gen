from pathlib import Path

ROOT = Path(__file__).resolve().parent
JAVA = ROOT / "app/src/main/java/com/vaan/frequencyremapper"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Atlas patch anchor mismatch in {path}: expected 1 occurrence, found {count}\nANCHOR:\n{old}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# ---------------------------------------------------------------------------
# Renderer: insert the atlas after the source mask and Vocal Front gain. User
# individual/category edits retain frequency-target precedence; the atlas can
# still layer its sub-audio modulation envelope underneath them.
# ---------------------------------------------------------------------------
renderer = JAVA / "SourceAwareStreamingRenderer.kt"
replace_once(
    renderer,
    "        vocalFront: VocalFrontConfig = VocalFrontConfig(),\n        onProgress: (Float) -> Unit = {}\n",
    "        vocalFront: VocalFrontConfig = VocalFrontConfig(),\n        combatAtlas: CombatAtlasConfig = CombatAtlasConfig(),\n        onProgress: (Float) -> Unit = {}\n"
)
replace_once(
    renderer,
    "        if (activeEdits.isEmpty() && activeRules.isEmpty() && soloCategory == null && !vocalFront.enabled) {\n",
    "        if (activeEdits.isEmpty() && activeRules.isEmpty() && soloCategory == null && !vocalFront.enabled && !combatAtlas.enabled) {\n"
)
replace_once(
    renderer,
    "                        soloConfidence = soloConfidence,\n                        vocalFront = vocalFront\n",
    "                        soloConfidence = soloConfidence,\n                        vocalFront = vocalFront,\n                        combatAtlas = combatAtlas,\n                        timeSeconds = timeSeconds\n"
)
replace_once(
    renderer,
    "        soloConfidence: Double,\n        vocalFront: VocalFrontConfig\n    ) {\n",
    "        soloConfidence: Double,\n        vocalFront: VocalFrontConfig,\n        combatAtlas: CombatAtlasConfig,\n        timeSeconds: Double\n    ) {\n"
)
replace_once(
    renderer,
    "            val frontGain = vocalFront.gainFor(categoryOrdinal, confidence)\n            val baseRe = re * frontGain\n            val baseIm = im * frontGain\n\n            var transformRatio = 1.0\n",
    "            val atlasDecision = if (combatAtlas.enabled && confidence >= combatAtlas.confidenceThreshold) {\n                CombatFrequencyAtlas.decision(\n                    config = combatAtlas,\n                    categoryOrdinal = categoryOrdinal,\n                    sourceHz = k * binHz,\n                    timeSeconds = timeSeconds,\n                    sampleRate = sampleRate,\n                    fftSize = fftSize\n                )\n            } else null\n            val frontGain = vocalFront.gainFor(categoryOrdinal, confidence)\n            val atlasGain = atlasDecision?.gain ?: 1.0\n            val baseRe = re * frontGain * atlasGain\n            val baseIm = im * frontGain * atlasGain\n\n            var transformRatio = 1.0\n"
)
replace_once(
    renderer,
    "            if (transformWeight <= 0.0001) {\n                outReal[k] += baseRe.toFloat()\n",
    "            if (transformWeight <= 0.0001) {\n                val atlasTarget = atlasDecision?.targetHz\n                val frequency = k * binHz\n                if (atlasTarget != null && atlasTarget.isFinite() && atlasTarget > 0.0 && atlasTarget < sampleRate / 2.0) {\n                    transformRatio = atlasTarget / frequency\n                    transformWeight = (0.45 + 0.55 * combatAtlas.intensity.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)\n                }\n            }\n\n            if (transformWeight <= 0.0001) {\n                outReal[k] += baseRe.toFloat()\n"
)


# ---------------------------------------------------------------------------
# Live engine: same atlas logic, with the playback timeline passed to warp so
# ROTATE is genuinely time-varying and loops with the source.
# ---------------------------------------------------------------------------
live = JAVA / "SourceAwareLivePreviewEngine.kt"
replace_once(
    live,
    "        val vocalFront: VocalFrontConfig,\n        val version: Long\n",
    "        val vocalFront: VocalFrontConfig,\n        val combatAtlas: CombatAtlasConfig,\n        val version: Long\n"
)
replace_once(
    live,
    "        val soloConfidence: Double,\n        val vocalFront: VocalFrontConfig\n    )\n",
    "        val soloConfidence: Double,\n        val vocalFront: VocalFrontConfig,\n        val combatAtlas: CombatAtlasConfig\n    )\n"
)
replace_once(
    live,
    "    @Volatile private var config = Config(emptyList(), emptyList(), emptyList(), emptyList(), PhaseRemapOptions(), null, 0.45, VocalFrontConfig(), 0L)\n",
    "    @Volatile private var config = Config(emptyList(), emptyList(), emptyList(), emptyList(), PhaseRemapOptions(), null, 0.45, VocalFrontConfig(), CombatAtlasConfig(), 0L)\n"
)
replace_once(
    live,
    "        vocalFront: VocalFrontConfig = VocalFrontConfig(),\n        manualRegions: List<ManualMaskRegion> = emptyList(),\n",
    "        vocalFront: VocalFrontConfig = VocalFrontConfig(),\n        combatAtlas: CombatAtlasConfig = CombatAtlasConfig(),\n        manualRegions: List<ManualMaskRegion> = emptyList(),\n"
)
# start() and update() both construct Config. The Vocal Front patch has already
# changed both constructor lines to include vocalFront.
text = live.read_text(encoding="utf-8")
old_ctor = "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), vocalFront, versionCounter\n"
new_ctor = "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), vocalFront, combatAtlas, versionCounter\n"
count = text.count(old_ctor)
if count != 2:
    raise SystemExit(f"Atlas patch expected 2 live Config constructors, found {count}")
live.write_text(text.replace(old_ctor, new_ctor), encoding="utf-8")
replace_once(
    live,
    "            hasProcessing = cfg.vocalFront.enabled || cfg.soloCategory != null || activeRules > 0 || cfg.edits.any {\n",
    "            hasProcessing = cfg.vocalFront.enabled || cfg.combatAtlas.enabled || cfg.soloCategory != null || activeRules > 0 || cfg.edits.any {\n"
)
replace_once(
    live,
    "            soloConfidence = cfg.soloConfidence,\n            vocalFront = cfg.vocalFront\n",
    "            soloConfidence = cfg.soloConfidence,\n            vocalFront = cfg.vocalFront,\n            combatAtlas = cfg.combatAtlas\n"
)
replace_once(
    live,
    "                                fftSize, hop, source.sampleRate, mask, plan\n",
    "                                fftSize, hop, source.sampleRate, mask, plan, timeSeconds\n"
)
replace_once(
    live,
    "        mask: FrameCategoryMask,\n        plan: RuntimePlan\n    ) {\n",
    "        mask: FrameCategoryMask,\n        plan: RuntimePlan,\n        timeSeconds: Double\n    ) {\n"
)
replace_once(
    live,
    "            val frontGain = plan.vocalFront.gainFor(cat, confidence)\n            val baseRe = re * frontGain\n            val baseIm = im * frontGain\n\n            var ratio = 1.0\n",
    "            val atlasDecision = if (plan.combatAtlas.enabled && confidence >= plan.combatAtlas.confidenceThreshold) {\n                CombatFrequencyAtlas.decision(\n                    config = plan.combatAtlas,\n                    categoryOrdinal = cat,\n                    sourceHz = k * binHz,\n                    timeSeconds = timeSeconds,\n                    sampleRate = sampleRate,\n                    fftSize = fftSize\n                )\n            } else null\n            val frontGain = plan.vocalFront.gainFor(cat, confidence)\n            val atlasGain = atlasDecision?.gain ?: 1.0\n            val baseRe = re * frontGain * atlasGain\n            val baseIm = im * frontGain * atlasGain\n\n            var ratio = 1.0\n"
)
replace_once(
    live,
    "            if (weight <= 0.0001) {\n                outReal[k] += baseRe.toFloat()\n",
    "            if (weight <= 0.0001) {\n                val atlasTarget = atlasDecision?.targetHz\n                val frequency = k * binHz\n                if (atlasTarget != null && atlasTarget.isFinite() && atlasTarget > 0.0 && atlasTarget < sampleRate / 2.0) {\n                    ratio = atlasTarget / frequency\n                    weight = (0.45 + 0.55 * plan.combatAtlas.intensity.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)\n                }\n            }\n\n            if (weight <= 0.0001) {\n                outReal[k] += baseRe.toFloat()\n"
)


# ---------------------------------------------------------------------------
# Export plumbing.
# ---------------------------------------------------------------------------
export = JAVA / "SourceAwareExportManager.kt"
replace_once(
    export,
    "        vocalFront: VocalFrontConfig = VocalFrontConfig(),\n        onProgress: (Float) -> Unit = {}\n",
    "        vocalFront: VocalFrontConfig = VocalFrontConfig(),\n        combatAtlas: CombatAtlasConfig = CombatAtlasConfig(),\n        onProgress: (Float) -> Unit = {}\n"
)
replace_once(
    export,
    "                        vocalFront = vocalFront,\n                        onProgress = onProgress\n",
    "                        vocalFront = vocalFront,\n                        combatAtlas = combatAtlas,\n                        onProgress = onProgress\n"
)


# ---------------------------------------------------------------------------
# Main UI: Full Combat Atlas command center and all 22 experimental labels.
# ---------------------------------------------------------------------------
ui = JAVA / "MainActivityV7.kt"
replace_once(
    ui,
    "    var vocalsFront by remember { mutableStateOf(false) }\n",
    "    var vocalsFront by remember { mutableStateOf(false) }\n    var atlasEnabled by remember { mutableStateOf(false) }\n    var atlasProgram by remember { mutableStateOf(CombatAtlasProgram.FULL) }\n    var atlasMode by remember { mutableStateOf(CombatAtlasMode.ROTATE) }\n    var atlasRepresentation by remember { mutableStateOf(CombatAtlasRepresentation.AUTO) }\n    var atlasRotationSeconds by remember { mutableFloatStateOf(4f) }\n    var atlasIntensity by remember { mutableFloatStateOf(0.38f) }\n    var showAtlasEntries by remember { mutableStateOf(false) }\n"
)
replace_once(
    ui,
    "    fun options(shift: Boolean = shiftHarmonics, cents: Float = bandCents) = PhaseRemapOptions(\n",
    '''    fun makeAtlasConfig(\n        enabled: Boolean = atlasEnabled,\n        program: CombatAtlasProgram = atlasProgram,\n        mode: CombatAtlasMode = atlasMode,\n        representation: CombatAtlasRepresentation = atlasRepresentation,\n        rotationSeconds: Float = atlasRotationSeconds,\n        intensity: Float = atlasIntensity\n    ) = CombatAtlasConfig(\n        enabled = enabled,\n        program = program,\n        mode = mode,\n        representation = representation,\n        rotationSeconds = rotationSeconds.toDouble(),\n        intensity = intensity.toDouble(),\n        confidenceThreshold = 0.25\n    )\n\n    fun options(shift: Boolean = shiftHarmonics, cents: Float = bandCents) = PhaseRemapOptions(\n'''
)
replace_once(
    ui,
    "        candidateVocalsFront: Boolean = vocalsFront,\n        candidateRegions: List<ManualMaskRegion> = manualRegions,\n",
    "        candidateVocalsFront: Boolean = vocalsFront,\n        candidateCombatAtlas: CombatAtlasConfig = makeAtlasConfig(),\n        candidateRegions: List<ManualMaskRegion> = manualRegions,\n"
)
replace_once(
    ui,
    "            vocalFront = VocalFrontConfig(enabled = candidateVocalsFront),\n            manualRegions = candidateRegions\n",
    "            vocalFront = VocalFrontConfig(enabled = candidateVocalsFront),\n            combatAtlas = candidateCombatAtlas,\n            manualRegions = candidateRegions\n"
)
replace_once(
    ui,
    "            vocalFront = VocalFrontConfig(enabled = vocalsFront),\n            manualRegions = manualRegions,\n            onError = { message ->\n",
    "            vocalFront = VocalFrontConfig(enabled = vocalsFront),\n            combatAtlas = makeAtlasConfig(),\n            manualRegions = manualRegions,\n            onError = { message ->\n"
)
replace_once(
    ui,
    "        ruleDrafts = emptyMap()\n        manualRegions = emptyList()\n",
    "        ruleDrafts = emptyMap()\n        atlasEnabled = false\n        manualRegions = emptyList()\n"
)
replace_once(
    ui,
    "        if (renderSolo == null && edits.isEmpty() && rules.isEmpty() && !vocalsFront) {\n",
    "        if (renderSolo == null && edits.isEmpty() && rules.isEmpty() && !vocalsFront && !atlasEnabled) {\n"
)
replace_once(
    ui,
    "                        vocalFront = VocalFrontConfig(enabled = vocalsFront)\n",
    "                        vocalFront = VocalFrontConfig(enabled = vocalsFront),\n                        combatAtlas = makeAtlasConfig()\n"
)
replace_once(
    ui,
    "    fun applyCombatBodyMap() {\n        val src = source ?: return\n",
    '''    fun applyCombatAtlas(program: CombatAtlasProgram = atlasProgram) {\n        if (source == null) return\n        atlasProgram = program\n        atlasEnabled = true\n        vocalsFront = true\n        soloCategory = null\n        ruleDrafts = emptyMap()\n        val config = makeAtlasConfig(enabled = true, program = program)\n        updateLive(\n            candidateRules = emptyMap(),\n            candidateSolo = null,\n            candidateVocalsFront = true,\n            candidateCombatAtlas = config\n        )\n        status = "${program.title} ON • ${atlasMode.title} • ${atlasRepresentation.title}. VOCALS FRONT is ON. Every atlas label is Experimental."\n    }\n\n    fun disableCombatAtlas() {\n        atlasEnabled = false\n        updateLive(candidateCombatAtlas = makeAtlasConfig(enabled = false))\n        status = "Combat Frequency Atlas disabled. VOCALS FRONT and your individual edits remain unchanged."\n    }\n\n    fun applyCombatBodyMap() {\n        val src = source ?: return\n        atlasEnabled = false\n'''
)
# Combat Body Map should explicitly disable the Atlas in the live config.
replace_once(
    ui,
    "            candidateSolo = null,\n            candidateVocalsFront = true\n        )\n        status = \"COMBAT BODY MAP ON.",
",
    "            candidateSolo = null,\n            candidateVocalsFront = true,\n            candidateCombatAtlas = makeAtlasConfig(enabled = false)\n        )\n        status = \"COMBAT BODY MAP ON.",
"
) if False else None
# The previous replacement above is intentionally guarded because the exact
# status string contains more text. Patch the shorter unique call instead.
replace_once(
    ui,
    "            candidateRules = nextRules,\n            candidateSolo = null,\n            candidateVocalsFront = true\n        )\n",
    "            candidateRules = nextRules,\n            candidateSolo = null,\n            candidateVocalsFront = true,\n            candidateCombatAtlas = makeAtlasConfig(enabled = false)\n        )\n"
)
replace_once(
    ui,
    "                Text(\"FREQUENCY REMAPPER SPECTRAL v1.6.2\", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)\n",
    "                Text(\"FREQUENCY REMAPPER SPECTRAL v1.7.0\", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)\n"
)
replace_once(
    ui,
    "                Text(\"Time-frequency source masks • Spectral Solo • Category Matrix • non-destructive edit graph\")\n",
    "                Text(\"Full Combat Atlas • 22 experimental document frequencies • source-aware masks • streaming DSP\")\n"
)

combat_card = '''                item {\n                    Card(Modifier.fillMaxWidth()) {\n                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {\n                            Text("COMBAT BODY MAP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)\n'''
atlas_card = '''                item {\n                    Card(Modifier.fillMaxWidth()) {\n                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {\n                            Text("FULL COMBAT FREQUENCY ATLAS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)\n                            Text("All 22 frequencies from your selected combat/body table. Every entry is labeled Experimental: [document benefit].")\n                            Text(if (atlasEnabled) "● ATLAS ACTIVE • ${atlasProgram.title}" else "Atlas off", fontWeight = FontWeight.Bold)\n                            Text("MODE: ${atlasMode.title} • REPRESENTATION: ${atlasRepresentation.title}")\n                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                                OutlinedButton(\n                                    onClick = {\n                                        val next = atlasMode.next()\n                                        atlasMode = next\n                                        updateLive(candidateCombatAtlas = makeAtlasConfig(mode = next))\n                                    },\n                                    modifier = Modifier.weight(1f),\n                                    enabled = !busy\n                                ) { Text("MODE") }\n                                OutlinedButton(\n                                    onClick = {\n                                        val next = atlasRepresentation.next()\n                                        atlasRepresentation = next\n                                        updateLive(candidateCombatAtlas = makeAtlasConfig(representation = next))\n                                    },\n                                    modifier = Modifier.weight(1f),\n                                    enabled = !busy\n                                ) { Text("REPRESENTATION") }\n                            }\n                            Text("Rotation: ${String.format(Locale.US, \"%.1f\", atlasRotationSeconds)} sec")\n                            Slider(\n                                value = atlasRotationSeconds,\n                                onValueChange = {\n                                    atlasRotationSeconds = it\n                                    updateLive(candidateCombatAtlas = makeAtlasConfig(rotationSeconds = it))\n                                },\n                                valueRange = 0.5f..10f,\n                                enabled = !busy\n                            )\n                            Text("Atlas strength: ${(atlasIntensity * 100).roundToInt()}%")\n                            Slider(\n                                value = atlasIntensity,\n                                onValueChange = {\n                                    atlasIntensity = it\n                                    updateLive(candidateCombatAtlas = makeAtlasConfig(intensity = it))\n                                },\n                                valueRange = 0.10f..1.0f,\n                                enabled = !busy\n                            )\n                            Button(\n                                onClick = { applyCombatAtlas(CombatAtlasProgram.FULL) },\n                                enabled = !busy,\n                                modifier = Modifier.fillMaxWidth()\n                            ) { Text("APPLY ALL 22 + VOCALS FRONT") }\n                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                                OutlinedButton(onClick = { applyCombatAtlas(CombatAtlasProgram.EXPLOSIVE) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("EXPLOSIVE") }\n                                OutlinedButton(onClick = { applyCombatAtlas(CombatAtlasProgram.IRON_FRAME) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("IRON FRAME") }\n                            }\n                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                                OutlinedButton(onClick = { applyCombatAtlas(CombatAtlasProgram.ENDURANCE) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("ENDLESS GAS") }\n                                OutlinedButton(onClick = { applyCombatAtlas(CombatAtlasProgram.NEURAL) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("NEURAL") }\n                            }\n                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                                OutlinedButton(onClick = { applyCombatAtlas(CombatAtlasProgram.RECOVERY) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("RECOVERY") }\n                                OutlinedButton(onClick = { disableCombatAtlas() }, enabled = !busy && atlasEnabled, modifier = Modifier.weight(1f)) { Text("ATLAS OFF") }\n                            }\n                            OutlinedButton(\n                                onClick = { showAtlasEntries = !showAtlasEntries },\n                                modifier = Modifier.fillMaxWidth()\n                            ) { Text(if (showAtlasEntries) "HIDE ALL 22" else "SHOW ALL 22") }\n                            if (showAtlasEntries) {\n                                val sr = source?.sampleRate ?: 48000\n                                CombatFrequencyAtlas.entries.forEach { entry ->\n                                    HorizontalDivider()\n                                    Text("${entry.displayHz} • ${entry.documentTarget}", fontWeight = FontWeight.Bold)\n                                    Text(entry.experimentalLabel)\n                                    Text("AUTO on this file: ${CombatFrequencyAtlas.resolvedPreview(entry, sr, AudioCategory.VOCAL, atlasRepresentation)}")\n                                }\n                            }\n                            Text("1 / 2 / 15 Hz entries use sub-audio modulation. Values above the file limit are octave represented in AUTO/FOLD mode; the original document value always stays visible.")\n                        }\n                    }\n                }\n\n''' + combat_card
replace_once(ui, combat_card, atlas_card)
replace_once(
    ui,
    "                        enabled = !busy && (vocalsFront || graphRuleCount > 0 || individualEditCount > 0),\n",
    "                        enabled = !busy && (atlasEnabled || vocalsFront || graphRuleCount > 0 || individualEditCount > 0),\n"
)
replace_once(
    ui,
    'saveCopyLauncher.launch("frequency-remapper-v1.6.2-copy.wav")',
    'saveCopyLauncher.launch("frequency-remapper-v1.7.0-copy.wav")'
)

print("Applied Frequency Remapper v1.7.0 FULL COMBAT ATLAS patch successfully.")

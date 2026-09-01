from pathlib import Path

ROOT = Path(__file__).resolve().parent
JAVA = ROOT / "app/src/main/java/com/vaan/frequencyremapper"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Patch anchor mismatch in {path}: expected 1 occurrence, found {count}\nANCHOR:\n{old}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# ---------------------------------------------------------------------------
# Shared model: vocal-family foreground gain policy.
# ---------------------------------------------------------------------------
model = JAVA / "SourceAwareSpectralModel.kt"
replace_once(
    model,
    "data class ManualMaskRegion(\n",
    '''data class VocalFrontConfig(\n    val enabled: Boolean = false,\n    val nonVocalGain: Double = 0.08\n) {\n    fun gainFor(categoryOrdinal: Int, confidence: Double): Double {\n        if (!enabled) return 1.0\n        val category = AudioCategory.entries.getOrNull(categoryOrdinal) ?: AudioCategory.OTHER\n        if (category.isVocalFamily()) return 1.0\n\n        // Strongly push non-vocal material behind the vocal family. Uncertain\n        // bins get a slightly gentler cut to avoid harsh musical gating.\n        val certainty = confidence.coerceIn(0.0, 1.0)\n        return (nonVocalGain.coerceIn(0.02, 0.35) + (1.0 - certainty) * 0.05)\n            .coerceIn(0.02, 0.40)\n    }\n}\n\nfun AudioCategory.isVocalFamily(): Boolean = when (this) {\n    AudioCategory.VOCAL,\n    AudioCategory.LOW_VOCAL,\n    AudioCategory.HIDDEN_VOCAL,\n    AudioCategory.HIDDEN_LOW_VOCAL -> true\n    else -> false\n}\n\ndata class ManualMaskRegion(\n'''
)


# ---------------------------------------------------------------------------
# Full-quality renderer: apply foreground/background gain before remapping.
# ---------------------------------------------------------------------------
renderer = JAVA / "SourceAwareStreamingRenderer.kt"
replace_once(
    renderer,
    "        soloConfidence: Double = 0.45,\n        onProgress: (Float) -> Unit = {}\n",
    "        soloConfidence: Double = 0.45,\n        vocalFront: VocalFrontConfig = VocalFrontConfig(),\n        onProgress: (Float) -> Unit = {}\n"
)
replace_once(
    renderer,
    "        if (activeEdits.isEmpty() && activeRules.isEmpty() && soloCategory == null) {\n",
    "        if (activeEdits.isEmpty() && activeRules.isEmpty() && soloCategory == null && !vocalFront.enabled) {\n"
)
replace_once(
    renderer,
    "                        soloCategory = soloCategory,\n                        soloConfidence = soloConfidence\n",
    "                        soloCategory = soloCategory,\n                        soloConfidence = soloConfidence,\n                        vocalFront = vocalFront\n"
)
replace_once(
    renderer,
    "        soloCategory: AudioCategory?,\n        soloConfidence: Double\n    ) {\n",
    "        soloCategory: AudioCategory?,\n        soloConfidence: Double,\n        vocalFront: VocalFrontConfig\n    ) {\n"
)
replace_once(
    renderer,
    "        outReal[0] = if (soloCategory == null) real[0] else 0f\n        outImag[0] = 0f\n        outReal[half] = if (soloCategory == null) real[half] else 0f\n",
    "        val edgeGain = if (vocalFront.enabled) vocalFront.nonVocalGain.toFloat() else 1f\n        outReal[0] = if (soloCategory == null) real[0] * edgeGain else 0f\n        outImag[0] = 0f\n        outReal[half] = if (soloCategory == null) real[half] * edgeGain else 0f\n"
)
replace_once(
    renderer,
    "            var transformRatio = 1.0\n",
    "            val frontGain = vocalFront.gainFor(categoryOrdinal, confidence)\n            val baseRe = re * frontGain\n            val baseIm = im * frontGain\n\n            var transformRatio = 1.0\n"
)
replace_once(
    renderer,
    "                outReal[k] += real[k]\n                outImag[k] += imag[k]\n                prevPhase[k] = sourcePhase.toFloat()\n",
    "                outReal[k] += baseRe.toFloat()\n                outImag[k] += baseIm.toFloat()\n                prevPhase[k] = sourcePhase.toFloat()\n"
)
replace_once(renderer, "            val magnitude = hypot(re, im)\n", "            val magnitude = hypot(baseRe, baseIm)\n")
replace_once(
    renderer,
    "            outReal[k] += (re * keep).toFloat()\n            outImag[k] += (im * keep).toFloat()\n",
    "            outReal[k] += (baseRe * keep).toFloat()\n            outImag[k] += (baseIm * keep).toFloat()\n"
)
replace_once(
    renderer,
    "                outReal[k] += (re * transformWeight).toFloat()\n                outImag[k] += (im * transformWeight).toFloat()\n",
    "                outReal[k] += (baseRe * transformWeight).toFloat()\n                outImag[k] += (baseIm * transformWeight).toFloat()\n"
)


# ---------------------------------------------------------------------------
# Live engine: make the same gain policy hot-updatable during playback.
# ---------------------------------------------------------------------------
live = JAVA / "SourceAwareLivePreviewEngine.kt"
replace_once(
    live,
    "        val soloCategory: AudioCategory?,\n        val soloConfidence: Double,\n        val version: Long\n",
    "        val soloCategory: AudioCategory?,\n        val soloConfidence: Double,\n        val vocalFront: VocalFrontConfig,\n        val version: Long\n"
)
replace_once(
    live,
    "        val soloCategory: AudioCategory?,\n        val soloConfidence: Double\n    )\n",
    "        val soloCategory: AudioCategory?,\n        val soloConfidence: Double,\n        val vocalFront: VocalFrontConfig\n    )\n"
)
replace_once(
    live,
    "    @Volatile private var config = Config(emptyList(), emptyList(), emptyList(), emptyList(), PhaseRemapOptions(), null, 0.45, 0L)\n",
    "    @Volatile private var config = Config(emptyList(), emptyList(), emptyList(), emptyList(), PhaseRemapOptions(), null, 0.45, VocalFrontConfig(), 0L)\n"
)
replace_once(
    live,
    "        soloConfidence: Double = 0.45,\n        manualRegions: List<ManualMaskRegion> = emptyList(),\n",
    "        soloConfidence: Double = 0.45,\n        vocalFront: VocalFrontConfig = VocalFrontConfig(),\n        manualRegions: List<ManualMaskRegion> = emptyList(),\n"
)
replace_once(
    live,
    "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), versionCounter\n",
    "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), vocalFront, versionCounter\n"
)
replace_once(
    live,
    "        soloCategory: AudioCategory?,\n        soloConfidence: Double,\n        manualRegions: List<ManualMaskRegion> = emptyList()\n",
    "        soloCategory: AudioCategory?,\n        soloConfidence: Double,\n        vocalFront: VocalFrontConfig = VocalFrontConfig(),\n        manualRegions: List<ManualMaskRegion> = emptyList()\n"
)
# second Config constructor occurrence in update()
text = live.read_text(encoding="utf-8")
old = "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), versionCounter\n"
new = "            options, soloCategory, soloConfidence.coerceIn(0.05, 0.99), vocalFront, versionCounter\n"
if old in text:
    if text.count(old) != 1:
        raise SystemExit(f"Unexpected remaining Config constructor count: {text.count(old)}")
    live.write_text(text.replace(old, new, 1), encoding="utf-8")

replace_once(
    live,
    "            hasProcessing = cfg.soloCategory != null || activeRules > 0 || cfg.edits.any {\n",
    "            hasProcessing = cfg.vocalFront.enabled || cfg.soloCategory != null || activeRules > 0 || cfg.edits.any {\n"
)
replace_once(
    live,
    "            soloCategory = cfg.soloCategory,\n            soloConfidence = cfg.soloConfidence\n",
    "            soloCategory = cfg.soloCategory,\n            soloConfidence = cfg.soloConfidence,\n            vocalFront = cfg.vocalFront\n"
)
replace_once(
    live,
    "        outReal[0] = if (plan.soloCategory == null) real[0] else 0f\n        outReal[half] = if (plan.soloCategory == null) real[half] else 0f\n",
    "        val edgeGain = if (plan.vocalFront.enabled) plan.vocalFront.nonVocalGain.toFloat() else 1f\n        outReal[0] = if (plan.soloCategory == null) real[0] * edgeGain else 0f\n        outReal[half] = if (plan.soloCategory == null) real[half] * edgeGain else 0f\n"
)
replace_once(
    live,
    "            var ratio = 1.0\n",
    "            val frontGain = plan.vocalFront.gainFor(cat, confidence)\n            val baseRe = re * frontGain\n            val baseIm = im * frontGain\n\n            var ratio = 1.0\n"
)
replace_once(
    live,
    "                outReal[k] += real[k]\n                outImag[k] += imag[k]\n                prevPhase[k] = sourcePhase.toFloat()\n",
    "                outReal[k] += baseRe.toFloat()\n                outImag[k] += baseIm.toFloat()\n                prevPhase[k] = sourcePhase.toFloat()\n"
)
replace_once(live, "            val magnitude = hypot(re, im)\n", "            val magnitude = hypot(baseRe, baseIm)\n")
replace_once(
    live,
    "            outReal[k] += (re * keep).toFloat()\n            outImag[k] += (im * keep).toFloat()\n",
    "            outReal[k] += (baseRe * keep).toFloat()\n            outImag[k] += (baseIm * keep).toFloat()\n"
)
replace_once(
    live,
    "                outReal[k] += (re * weight).toFloat()\n                outImag[k] += (im * weight).toFloat()\n",
    "                outReal[k] += (baseRe * weight).toFloat()\n                outImag[k] += (baseIm * weight).toFloat()\n"
)


# ---------------------------------------------------------------------------
# Export plumbing.
# ---------------------------------------------------------------------------
export = JAVA / "SourceAwareExportManager.kt"
replace_once(
    export,
    "        soloConfidence: Double = 0.45,\n        onProgress: (Float) -> Unit = {}\n",
    "        soloConfidence: Double = 0.45,\n        vocalFront: VocalFrontConfig = VocalFrontConfig(),\n        onProgress: (Float) -> Unit = {}\n"
)
replace_once(
    export,
    "                        soloCategory = soloCategory,\n                        soloConfidence = soloConfidence,\n                        onProgress = onProgress\n",
    "                        soloCategory = soloCategory,\n                        soloConfidence = soloConfidence,\n                        vocalFront = vocalFront,\n                        onProgress = onProgress\n"
)


# ---------------------------------------------------------------------------
# Main workstation UI: a single switch, hot-updatable, renderable by itself.
# ---------------------------------------------------------------------------
ui = JAVA / "MainActivityV7.kt"
replace_once(
    ui,
    "    var soloConfidence by remember { mutableFloatStateOf(0.45f) }\n",
    "    var soloConfidence by remember { mutableFloatStateOf(0.45f) }\n    var vocalsFront by remember { mutableStateOf(false) }\n"
)
replace_once(
    ui,
    "        candidateSoloConfidence: Float = soloConfidence,\n        candidateRegions: List<ManualMaskRegion> = manualRegions,\n",
    "        candidateSoloConfidence: Float = soloConfidence,\n        candidateVocalsFront: Boolean = vocalsFront,\n        candidateRegions: List<ManualMaskRegion> = manualRegions,\n"
)
replace_once(
    ui,
    "            soloConfidence = candidateSoloConfidence.toDouble(),\n            manualRegions = candidateRegions\n",
    "            soloConfidence = candidateSoloConfidence.toDouble(),\n            vocalFront = VocalFrontConfig(enabled = candidateVocalsFront),\n            manualRegions = candidateRegions\n"
)
replace_once(
    ui,
    "            soloConfidence = soloConfidence.toDouble(),\n            manualRegions = manualRegions,\n            onError = { message ->\n",
    "            soloConfidence = soloConfidence.toDouble(),\n            vocalFront = VocalFrontConfig(enabled = vocalsFront),\n            manualRegions = manualRegions,\n            onError = { message ->\n"
)
replace_once(
    ui,
    "        if (renderSolo == null && edits.isEmpty() && rules.isEmpty()) {\n            Toast.makeText(context, \"Enable a category rule or change an individual frequency/phase first.\", Toast.LENGTH_LONG).show()\n",
    "        if (renderSolo == null && edits.isEmpty() && rules.isEmpty() && !vocalsFront) {\n            Toast.makeText(context, \"Enable VOCALS FRONT, a category rule, or change an individual frequency/phase first.\", Toast.LENGTH_LONG).show()\n"
)
replace_once(
    ui,
    "                        soloCategory = renderSolo,\n                        soloConfidence = soloConfidence.toDouble()\n",
    "                        soloCategory = renderSolo,\n                        soloConfidence = soloConfidence.toDouble(),\n                        vocalFront = VocalFrontConfig(enabled = vocalsFront)\n"
)
replace_once(
    ui,
    "                Text(\"FREQUENCY REMAPPER SPECTRAL v1.6\", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)\n",
    "                Text(\"FREQUENCY REMAPPER SPECTRAL v1.6.1\", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)\n"
)
replace_once(
    ui,
    '''                item {\n                    Card(Modifier.fillMaxWidth()) {\n                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {\n                            Text("SPECTRAL SOLO", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)\n''',
    '''                item {\n                    Card(Modifier.fillMaxWidth()) {\n                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {\n                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                                Column(Modifier.weight(1f)) {\n                                    Text("VOCALS FRONT", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)\n                                    Text("Keeps VOCAL + LOW VOCAL + HIDDEN VOCAL families forward and pushes bass/instruments/other about 18-22 dB into the background.")\n                                }\n                                Switch(\n                                    checked = vocalsFront,\n                                    onCheckedChange = { enabled ->\n                                        vocalsFront = enabled\n                                        updateLive(candidateVocalsFront = enabled)\n                                        status = if (enabled) {\n                                            "VOCALS FRONT ON. All vocal families stay forward; non-vocal material is strongly reduced."\n                                        } else {\n                                            "VOCALS FRONT OFF. Full source balance restored."\n                                        }\n                                    },\n                                    enabled = !busy\n                                )\n                            }\n                            Text(if (vocalsFront) "● VOCAL FOREGROUND ACTIVE" else "Normal full-mix level balance")\n                        }\n                    }\n                }\n\n                item {\n                    Card(Modifier.fillMaxWidth()) {\n                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {\n                            Text("SPECTRAL SOLO", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)\n'''
)
replace_once(
    ui,
    "                        enabled = !busy && (graphRuleCount > 0 || individualEditCount > 0),\n",
    "                        enabled = !busy && (vocalsFront || graphRuleCount > 0 || individualEditCount > 0),\n"
)
replace_once(
    ui,
    "                                    onClick = { saveCopyLauncher.launch(\"frequency-remapper-v1.6-copy.wav\") },\n",
    "                                    onClick = { saveCopyLauncher.launch(\"frequency-remapper-v1.6.1-copy.wav\") },\n"
)

print("Applied Frequency Remapper v1.6.1 VOCALS FRONT patch successfully.")

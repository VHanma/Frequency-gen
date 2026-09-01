from pathlib import Path

ROOT = Path(__file__).resolve().parent
UI = ROOT / "app/src/main/java/com/vaan/frequencyremapper/MainActivityV7.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Patch anchor mismatch in {path}: expected 1 occurrence, found {count}\nANCHOR:\n{old}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Display version.
replace_once(
    UI,
    'Text("FREQUENCY REMAPPER SPECTRAL v1.6.1", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)',
    'Text("FREQUENCY REMAPPER SPECTRAL v1.6.2", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)'
)

# One-tap combat/body frequency graph. It uses exact document frequencies for
# detected categories and simultaneously enables VOCALS FRONT.
anchor = '''    fun updateRule(category: AudioCategory, draft: RuleDraftV7) {
        val next = ruleDrafts + (category to draft)
        ruleDrafts = next
        updateLive(candidateRules = next)
    }
'''
replacement = anchor + '''
    fun applyCombatBodyMap() {
        val src = source ?: return
        val supported = CombatBodyMapPreset.supportedTargets(src.sampleRate)
        val nextRules = supported.associate { target ->
            target.category to RuleDraftV7(
                mode = CategoryFrequencyMode.EXACT,
                valueText = formatHzV7(target.hz),
                phaseText = "0",
                threshold = 0.25f,
                enabled = true
            )
        }
        ruleDrafts = nextRules
        vocalsFront = true
        soloCategory = null
        updateLive(
            candidateRules = nextRules,
            candidateSolo = null,
            candidateVocalsFront = true
        )
        status = "COMBAT BODY MAP ON. Vocal families use the higher combat/body document frequencies; bass/instrument/other use lower combat entries. VOCALS FRONT is also ON."
    }

    fun clearCombatBodyMap() {
        ruleDrafts = emptyMap()
        vocalsFront = false
        updateLive(candidateRules = emptyMap(), candidateVocalsFront = false)
        status = "Combat Body Map cleared. Individual edits and manual mask regions were left untouched."
    }
'''
replace_once(UI, anchor, replacement)

# Insert the preset command card immediately before the existing VOCALS FRONT card.
vocals_card = '''                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("VOCALS FRONT", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
'''
combat_plus_vocals = '''                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("COMBAT BODY MAP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                            Text("One tap maps every detected source category onto combat/body frequencies from your frequency documents and turns VOCALS FRONT on.")
                            Text("HIGH VOCAL RAIL", fontWeight = FontWeight.Bold)
                            Text("VOCAL 4000 Hz • LOW VOCAL 3300 Hz • HIDDEN VOCAL 2500 Hz • HIDDEN LOW VOCAL 2400 Hz")
                            Text("LOWER MIX RAIL", fontWeight = FontWeight.Bold)
                            Text("BASS 174 Hz • INSTRUMENT 528 Hz • OTHER 852 Hz")
                            Text("Custom exact rows stay untouched. Every generated category rule remains editable in the Category Matrix below.")
                            Button(
                                onClick = { applyCombatBodyMap() },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("APPLY COMBAT BODY MAP + VOCALS FRONT") }
                            OutlinedButton(
                                onClick = { clearCombatBodyMap() },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("CLEAR COMBAT BODY MAP") }
                        }
                    }
                }

''' + vocals_card
replace_once(UI, vocals_card, combat_plus_vocals)

replace_once(
    UI,
    'saveCopyLauncher.launch("frequency-remapper-v1.6.1-copy.wav")',
    'saveCopyLauncher.launch("frequency-remapper-v1.6.2-copy.wav")'
)

print("Applied Frequency Remapper v1.6.2 COMBAT BODY MAP patch successfully.")

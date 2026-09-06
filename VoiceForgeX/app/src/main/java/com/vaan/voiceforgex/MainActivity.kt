package com.vaan.voiceforgex

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var text: EditText
    private lateinit var modeSpinner: Spinner
    private lateinit var liveButton: Button
    private val recorder by lazy { VoiceRecorder(this) }
    private var live: LiveVoiceOver? = null
    private var pendingName = ""

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) beginRecordingDialog() }
    private val liveMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startLiveVoiceover() }
    private val importMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importCloneMedia(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CloneRepository.init(this)
        VoiceGenome.init(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28,28,28,50) }
        scroll.addView(root); setContentView(scroll)

        root.addView(TextView(this).apply { text = "VOICEFORGE Ω"; textSize = 30f; gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(TextView(this).apply { text = "Voice Genome • media distillation • fusion • live mic • offline system TTS"; textSize = 14f; gravity = Gravity.CENTER_HORIZONTAL })
        status = TextView(this).apply { textSize = 15f; setPadding(0,22,0,18) }; root.addView(status)

        root.addView(Button(this).apply { text = "REPAIR / DEEP VERIFY VOICE ENGINE"; setOnClickListener { ensureModel() } })
        root.addView(Button(this).apply { text = "＋ RECORD NEW VOICE GENOME"; setOnClickListener { askNameThenRecord() } })
        root.addView(Button(this).apply { text = "IMPORT AUDIO / VIDEO"; setOnClickListener { importMedia.launch(arrayOf("audio/*", "video/*")) } })
        root.addView(TextView(this).apply {
            text = "Ω extracts a speech-shaped reference, scores it, and can either create a new voice or strengthen the selected Voice Genome. A consistency gate warns before unrelated audio contaminates an existing clone."
            textSize = 12f
        })

        root.addView(TextView(this).apply { text = "VOICE VAULT"; textSize = 20f; setPadding(0,22,0,8) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(list)
        root.addView(Button(this).apply { text = "Ω FUSE SELECTED VOICE WITH ANOTHER"; setOnClickListener { showFusionDialog() } })
        root.addView(Button(this).apply { text = "VOICE LAB: INSPECT SELECTED GENOME"; setOnClickListener { showGenomeLab() } })

        root.addView(TextView(this).apply { text = "SYNTHESIS"; textSize = 20f; setPadding(0,22,0,6) })
        modeSpinner = Spinner(this)
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("FAST", "BALANCED", "OMEGA MAX LIKENESS"))
        modeSpinner.setSelection(1); root.addView(modeSpinner)
        root.addView(TextView(this).apply { text = "Ω mode renders three candidates and keeps the closest acoustic fit. FAST is used automatically for live voiceover latency."; textSize = 12f })

        text = EditText(this).apply { hint = "Type anything for the selected clone to say"; minLines = 3; gravity = Gravity.TOP }; root.addView(text)
        root.addView(Button(this).apply { text = "▶ SPEAK SELECTED VOICE"; setOnClickListener { speakNow() } })

        liveButton = Button(this).apply { text = "🎙 START LIVE MIC → CLONED VOICE"; setOnClickListener { toggleLiveVoiceover() } }
        root.addView(liveButton)
        root.addView(TextView(this).apply {
            text = "Live mode listens for a phrase, pauses recognition, speaks that phrase in the selected clone, then resumes. It prefers the phone's on-device speech recognizer when available."
            textSize = 12f
        })

        root.addView(Button(this).apply { text = "USE CLONES OUTSIDE THIS APP (ANDROID TTS)"; setOnClickListener { openTtsSettings() } })
        root.addView(Button(this).apply { text = "FLOATING CALL / APP OVERLAY"; setOnClickListener { toggleOverlay() } })
        root.addView(TextView(this).apply {
            text = "OMEGA NOTE: system TTS and the overlay work outside VoiceForge. True digital replacement of another app's private call-mic stream still needs control of that app's audio pipeline or a privileged/root audio route."
            textSize = 12f; setPadding(0,16,0,0)
        })

        refresh(); ensureModel()
    }

    override fun onDestroy() { live?.stop(); live = null; super.onDestroy() }

    private fun refresh() {
        status.text = if (ModelManager.isReady(this)) "Voice engine files present • deep verification available" else "Voice engine needs download/repair"
        list.removeAllViews()
        val all = CloneRepository.all()
        if (all.isEmpty()) list.addView(TextView(this).apply { text = "No voices yet. Record or import one." })
        all.forEach { p ->
            VoiceGenome.ensureLegacy(p)
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,4,0,8) }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val rb = RadioButton(this).apply {
                text = p.name; isChecked = CloneRepository.selected()?.id == p.id
                setOnClickListener { CloneRepository.select(p.id); refresh() }
            }
            val del = Button(this).apply { text = "DELETE"; setOnClickListener { CloneRepository.delete(p.id); refresh() } }
            top.addView(rb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); top.addView(del)
            row.addView(top)
            row.addView(TextView(this).apply { text = VoiceGenome.summary(p); textSize = 12f; setPadding(48,0,0,0) })
            list.addView(row)
        }
    }

    private fun selectedMode(): SynthesisMode = when (modeSpinner.selectedItemPosition) { 0 -> SynthesisMode.FAST; 2 -> SynthesisMode.OMEGA; else -> SynthesisMode.BALANCED }

    private fun ensureModel() {
        status.text = "Deep-checking voice engine…"
        lifecycleScope.launch {
            runCatching { ModelManager.ensure(this@MainActivity, forceDeepCheck = true) { p, msg -> runOnUiThread { status.text = "$msg  $p%" } } }
                .onSuccess { status.text = "✓ Voice engine verified offline" }
                .onFailure { status.text = "Automatic repair failed: ${it.message}" }
        }
    }

    private fun askNameThenRecord() {
        val input = EditText(this).apply { hint = "Voice name" }
        AlertDialog.Builder(this).setTitle("New Voice Genome").setView(input).setPositiveButton("Record") { _, _ ->
            pendingName = input.text.toString().ifBlank { "Clone ${CloneRepository.all().size + 1}" }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecordingDialog() else mic.launch(Manifest.permission.RECORD_AUDIO)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun beginRecordingDialog() {
        recorder.start { result -> result.onSuccess { wav ->
            val p = CloneRepository.addFromWav(pendingName, wav); val q = VoiceGenome.quality(VoiceGenome.bestReference(p))
            wav.delete(); refresh(); status.text = "✓ ${p.name} saved • ${q.summary()}"
        }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() } }
        AlertDialog.Builder(this).setTitle("Recording $pendingName").setMessage("Speak naturally for 8–15 seconds. Vary vowels, pace and pitch. Ω scores the sample after capture.")
            .setPositiveButton("Stop & save") { _, _ -> recorder.stop() }.setCancelable(false).show()
    }

    private fun importCloneMedia(uri: Uri) {
        status.text = "Ω distilling voice from media…"
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { MediaVoiceImporter.import(this@MainActivity, uri) } }
            result.onSuccess { media ->
                val quality = VoiceGenome.quality(media.wav); val selected = CloneRepository.selected()
                val match = selected?.let { VoiceGenome.compatibility(it, media.wav) }
                val choices = if (selected != null) arrayOf("Create new Voice Genome", "Add to ${selected.name}") else arrayOf("Create new Voice Genome")
                val matchText = if (match != null) "\nAcoustic consistency with ${selected!!.name}: $match%" else ""
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Ω extracted ${"%.1f".format(media.seconds)} seconds")
                    .setMessage("${quality.summary()}$matchText\nSource: ${media.sourceMime}\n\nChoose where this sample belongs.")
                    .setItems(choices) { _, which ->
                        if (which == 1 && selected != null) {
                            if ((match ?: 100) < 45) {
                                AlertDialog.Builder(this@MainActivity).setTitle("Genome contamination warning")
                                    .setMessage("This sample is only ${match}% acoustically consistent with ${selected.name}. That can mean another speaker, heavy music/noise, or a very different recording. Add it anyway?")
                                    .setPositiveButton("ADD ANYWAY") { _, _ -> addMediaToGenome(selected, media) }
                                    .setNegativeButton("CANCEL") { _, _ -> media.wav.delete() }.show()
                            } else addMediaToGenome(selected, media)
                        } else askImportedName(media.wav)
                    }
                    .setOnCancelListener { media.wav.delete() }.show()
            }.onFailure { status.text = "Media import failed: ${it.message}" }
        }
    }

    private fun addMediaToGenome(selected: CloneProfile, media: MediaVoiceImporter.Result) {
        val added = CloneRepository.addGenomeSample(selected.id, media.wav, "media:${media.sourceMime}")
        media.wav.delete(); refresh(); status.text = "✓ ${selected.name} strengthened • ${added.quality.summary()}"
    }

    private fun askImportedName(wav: java.io.File) {
        val input = EditText(this).apply { hint = "Voice name" }
        AlertDialog.Builder(this).setTitle("Name this Voice Genome").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val p = CloneRepository.addFromWav(input.text.toString(), wav); val q = VoiceGenome.quality(VoiceGenome.bestReference(p))
                wav.delete(); refresh(); status.text = "✓ ${p.name} saved • ${q.summary()}"
            }.setNegativeButton("Cancel") { _, _ -> wav.delete() }.show()
    }

    private fun showGenomeLab() {
        val p = CloneRepository.selected() ?: return Toast.makeText(this, "Select a voice first", Toast.LENGTH_SHORT).show()
        VoiceGenome.ensureLegacy(p); val s = VoiceGenome.samples(p.id)
        val body = buildString {
            append("VOICE: ${p.name}\nSAMPLES: ${s.size}\nBEST REFERENCE: ${VoiceGenome.bestReference(p).name}\n\n")
            s.forEachIndexed { i, x -> append("${i + 1}. ${x.quality.summary()}\n${x.source}\n\n") }
        }
        AlertDialog.Builder(this).setTitle("Ω Voice Lab").setMessage(body).setPositiveButton("Close", null).show()
    }

    private fun showFusionDialog() {
        val a = CloneRepository.selected() ?: return Toast.makeText(this, "Select a voice first", Toast.LENGTH_SHORT).show()
        val others = CloneRepository.all().filter { it.id != a.id }
        if (others.isEmpty()) return Toast.makeText(this, "Create at least two voices first", Toast.LENGTH_SHORT).show()
        AlertDialog.Builder(this).setTitle("Fuse ${a.name} with…").setItems(others.map { it.name }.toTypedArray()) { _, index ->
            val b = others[index]; val opts = arrayOf("70% ${a.name} / 30% ${b.name}", "50% / 50%", "30% ${a.name} / 70% ${b.name}")
            AlertDialog.Builder(this).setTitle("Voice Matrix ratio").setItems(opts) { _, r ->
                val ratio = when (r) { 0 -> .70f; 2 -> .30f; else -> .50f }; val input = EditText(this).apply { setText("${a.name} × ${b.name}") }
                AlertDialog.Builder(this).setTitle("Fusion name").setView(input).setPositiveButton("CREATE") { _, _ ->
                    lifecycleScope.launch { status.text = "Forging fusion voice…"
                        runCatching { withContext(Dispatchers.IO) { VoiceFusion.create(this@MainActivity, a, b, ratio, input.text.toString()) } }
                            .onSuccess { refresh(); status.text = "✓ Fusion created: ${it.name}" }.onFailure { status.text = "Fusion failed: ${it.message}" }
                    }
                }.setNegativeButton("Cancel", null).show()
            }.show()
        }.show()
    }

    private fun speakNow() {
        val p = CloneRepository.selected() ?: return Toast.makeText(this, "Create a clone first", Toast.LENGTH_SHORT).show()
        val t = text.text.toString().trim(); if (t.isEmpty()) return
        val mode = selectedMode(); lifecycleScope.launch {
            status.text = "Synthesizing ${p.name} • ${mode.name}…"
            val first = runCatching { withContext(Dispatchers.IO) { ModelManager.ensure(this@MainActivity, forceDeepCheck = false); CloneEngine.play(this@MainActivity, p, t, mode) } }
            if (first.isSuccess) { status.text = "✓ ${p.name} • ${mode.name}"; return@launch }
            status.text = "Engine fault detected • self-healing…"
            runCatching { withContext(Dispatchers.IO) { CloneEngine.invalidate(); ModelManager.ensure(this@MainActivity, forceDeepCheck = true) { pct,msg -> runOnUiThread { status.text="$msg  $pct%" } }; CloneEngine.play(this@MainActivity,p,t,mode) } }
                .onSuccess { status.text = "✓ Repaired • clone ready" }.onFailure { status.text = "Synthesis failed after repair: ${it.message}" }
        }
    }

    private fun toggleLiveVoiceover() {
        if (live != null) { live?.stop(); live = null; liveButton.text = "🎙 START LIVE MIC → CLONED VOICE"; return }
        if (CloneRepository.selected() == null) return Toast.makeText(this, "Create/select a clone first", Toast.LENGTH_SHORT).show()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startLiveVoiceover() else liveMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startLiveVoiceover() {
        if (live != null) return
        live = LiveVoiceOver(this, onStatus = { status.text = it }, onPhrase = { phrase ->
            val p = CloneRepository.selected() ?: return@LiveVoiceOver
            live?.pause(); status.text = "Ω LIVE • $phrase"
            lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { ModelManager.ensure(this@MainActivity, false); CloneEngine.play(this@MainActivity, p, phrase, SynthesisMode.FAST) } }
                    .onFailure { status.text = "Live synthesis failed: ${it.message}" }
                live?.resume(500)
            }
        })
        runCatching { live!!.start() }.onSuccess { liveButton.text = "■ STOP LIVE VOICEOVER" }
            .onFailure { status.text = "Live voiceover unavailable: ${it.message}"; live = null }
    }

    private fun openTtsSettings() { runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java)); Toast.makeText(this, "Ω overlay started", Toast.LENGTH_SHORT).show()
    }
}

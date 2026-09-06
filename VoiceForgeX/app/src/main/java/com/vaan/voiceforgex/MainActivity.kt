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
    private val recorder by lazy { VoiceRecorder(this) }
    private var pendingName = ""

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) beginRecordingDialog() }
    private val importMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importCloneMedia(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); CloneRepository.init(this)
        val scroll = ScrollView(this); val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28,28,28,50) }; scroll.addView(root); setContentView(scroll)
        root.addView(TextView(this).apply { text = "VOICEFORGE X"; textSize = 28f; gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(TextView(this).apply { text = "Offline zero-shot clone vault • system TTS • floating call overlay"; textSize = 14f; gravity = Gravity.CENTER_HORIZONTAL })
        status = TextView(this).apply { textSize = 16f; setPadding(0,24,0,18) }; root.addView(status)
        val model = Button(this).apply { text = "REPAIR / VERIFY VOICE ENGINE AUTOMATICALLY"; setOnClickListener { ensureModel() } }; root.addView(model)
        val create = Button(this).apply { text = "＋ RECORD NEW VOICE CLONE"; setOnClickListener { askNameThenRecord() } }; root.addView(create)
        val imp = Button(this).apply {
            text = "IMPORT AUDIO / VIDEO VOICE CLONE"
            setOnClickListener { importMedia.launch(arrayOf("audio/*", "video/*")) }
        }; root.addView(imp)
        root.addView(TextView(this).apply { text = "Supports downloaded WAV, MP3, M4A/AAC and videos such as MP4, MKV and WebM when Android has a decoder for their audio track. VoiceForge extracts and prepares the clone sample automatically."; textSize = 12f })
        root.addView(TextView(this).apply { text = "Saved voices"; textSize = 20f; setPadding(0,22,0,8) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(list)
        text = EditText(this).apply { hint = "Type anything for the selected clone to say"; minLines = 3; gravity = Gravity.TOP }; root.addView(text)
        val speak = Button(this).apply { text = "▶ SPEAK CLONE"; setOnClickListener { speakNow() } }; root.addView(speak)
        val outside = Button(this).apply { text = "USE CLONES OUTSIDE THIS APP (ANDROID TTS SETTINGS)"; setOnClickListener { openTtsSettings() } }; root.addView(outside)
        val overlay = Button(this).apply { text = "FLOATING CALL / APP OVERLAY"; setOnClickListener { toggleOverlay() } }; root.addView(overlay)
        root.addView(TextView(this).apply {
            text = "CALL MODE: the overlay can speak typed lines in the selected clone while another app is open. Android keeps another app's private call microphone path separate, so the standard no-root route is system TTS plus overlay/acoustic relay."
            textSize = 13f; setPadding(0,16,0,0)
        })
        refresh()
        // Always deep-verify on launch. A same-sized but corrupted ONNX file must never be trusted.
        ensureModel()
    }

    private fun refresh() {
        status.text = if (ModelManager.isReady(this)) "Voice engine files present • verification runs automatically" else "Voice engine needs repair/download"
        list.removeAllViews()
        val all = CloneRepository.all()
        if (all.isEmpty()) list.addView(TextView(this).apply { text = "No clones yet. Record clean speech or import audio/video." })
        all.forEach { p ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val rb = RadioButton(this).apply { text = p.name; isChecked = CloneRepository.selected()?.id == p.id; setOnClickListener { CloneRepository.select(p.id); refresh() } }
            val del = Button(this).apply { text = "Delete"; setOnClickListener { CloneRepository.delete(p.id); refresh() } }
            row.addView(rb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); row.addView(del); list.addView(row)
        }
    }

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
        AlertDialog.Builder(this).setTitle("New clone").setView(input).setPositiveButton("Record") { _, _ ->
            pendingName = input.text.toString().ifBlank { "Clone ${CloneRepository.all().size + 1}" }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecordingDialog() else mic.launch(Manifest.permission.RECORD_AUDIO)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun beginRecordingDialog() {
        recorder.start { result -> result.onSuccess { wav -> CloneRepository.addFromWav(pendingName, wav); refresh() }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() } }
        AlertDialog.Builder(this).setTitle("Recording $pendingName").setMessage("Speak naturally for about 8–12 seconds. Different pitch, pace and vowels give the clone a richer reference.")
            .setPositiveButton("Stop & save") { _, _ -> recorder.stop() }.setCancelable(false).show()
    }

    private fun importCloneMedia(uri: Uri) {
        status.text = "Extracting voice from selected media…"
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { MediaVoiceImporter.import(this@MainActivity, uri) } }
            result.onSuccess { media ->
                status.text = "✓ Extracted ${"%.1f".format(media.seconds)} s of clone audio"
                val input = EditText(this@MainActivity).apply { hint = "Voice name" }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Name this voice clone")
                    .setMessage("VoiceForge extracted a speech-dense reference from ${media.sourceMime}.")
                    .setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        CloneRepository.addFromWav(input.text.toString(), media.wav)
                        media.wav.delete()
                        refresh()
                        status.text = "✓ Voice clone saved"
                    }
                    .setNegativeButton("Cancel") { _, _ -> media.wav.delete() }
                    .show()
            }.onFailure {
                status.text = "Media import failed: ${it.message}"
                Toast.makeText(this@MainActivity, "Could not extract clone audio: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun speakNow() {
        val p = CloneRepository.selected() ?: return Toast.makeText(this, "Create a clone first", Toast.LENGTH_SHORT).show()
        val t = text.text.toString().trim(); if (t.isEmpty()) return
        lifecycleScope.launch {
            status.text = "Synthesizing ${p.name}…"
            val first = runCatching {
                withContext(Dispatchers.IO) {
                    ModelManager.ensure(this@MainActivity, forceDeepCheck = false)
                    CloneEngine.play(this@MainActivity, p, t)
                }
            }
            if (first.isSuccess) {
                status.text = "✓ Ready"
                return@launch
            }

            // One automatic self-heal/retry. Covers protobuf/model corruption without asking the user to diagnose it.
            status.text = "Voice engine fault detected • repairing automatically…"
            val retry = runCatching {
                withContext(Dispatchers.IO) {
                    CloneEngine.invalidate()
                    ModelManager.ensure(this@MainActivity, forceDeepCheck = true) { pct, msg -> runOnUiThread { status.text = "$msg  $pct%" } }
                    CloneEngine.play(this@MainActivity, p, t)
                }
            }
            retry.onSuccess { status.text = "✓ Repaired • clone ready" }
                .onFailure { status.text = "Synthesis failed after automatic repair: ${it.message}" }
        }
    }

    private fun openTtsSettings() {
        runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java)); Toast.makeText(this, "Overlay started. Use × on the bubble to close it.", Toast.LENGTH_SHORT).show()
    }
}

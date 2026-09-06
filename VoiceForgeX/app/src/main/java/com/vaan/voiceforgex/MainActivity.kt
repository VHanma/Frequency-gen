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
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var text: EditText
    private val recorder by lazy { VoiceRecorder(this) }
    private var pendingName = ""

    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) beginRecordingDialog() }
    private val importWav = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importClone(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); CloneRepository.init(this)
        val scroll = ScrollView(this); val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28,28,28,50) }; scroll.addView(root); setContentView(scroll)
        root.addView(TextView(this).apply { text = "VOICEFORGE X"; textSize = 28f; gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(TextView(this).apply { text = "Offline zero-shot clone vault • system TTS • floating call overlay"; textSize = 14f; gravity = Gravity.CENTER_HORIZONTAL })
        status = TextView(this).apply { textSize = 16f; setPadding(0,24,0,18) }; root.addView(status)
        val model = Button(this).apply { text = "Install / verify voice engine automatically"; setOnClickListener { ensureModel() } }; root.addView(model)
        val create = Button(this).apply { text = "＋ Record new voice clone"; setOnClickListener { askNameThenRecord() } }; root.addView(create)
        val imp = Button(this).apply { text = "Import PCM WAV clone sample"; setOnClickListener { importWav.launch(arrayOf("audio/wav","audio/x-wav","audio/*")) } }; root.addView(imp)
        root.addView(TextView(this).apply { text = "Saved voices"; textSize = 20f; setPadding(0,22,0,8) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(list)
        text = EditText(this).apply { hint = "Type anything for the selected clone to say"; minLines = 3; gravity = Gravity.TOP }; root.addView(text)
        val speak = Button(this).apply { text = "▶ Speak clone"; setOnClickListener { speakNow() } }; root.addView(speak)
        val outside = Button(this).apply { text = "Use clones OUTSIDE this app (Android TTS settings)"; setOnClickListener { openTtsSettings() } }; root.addView(outside)
        val overlay = Button(this).apply { text = "Floating call / app overlay"; setOnClickListener { toggleOverlay() } }; root.addView(overlay)
        root.addView(TextView(this).apply {
            text = "CALL MODE: the overlay can speak typed lines in the selected clone while another app is open. Android 16 keeps the actual phone-call microphone path private to the call app, so normal APKs cannot digitally replace that mic stream; the no-root path here is system TTS + overlay/acoustic relay."
            textSize = 13f; setPadding(0,16,0,0)
        })
        refresh(); if (!ModelManager.isReady(this)) ensureModel()
    }

    private fun refresh() {
        status.text = if (ModelManager.isReady(this)) "✓ Voice engine ready offline" else "Voice engine needs its one-time model download"
        list.removeAllViews()
        val all = CloneRepository.all()
        if (all.isEmpty()) list.addView(TextView(this).apply { text = "No clones yet. Record 5–15 seconds of clean speech." })
        all.forEach { p ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val rb = RadioButton(this).apply { text = p.name; isChecked = CloneRepository.selected()?.id == p.id; setOnClickListener { CloneRepository.select(p.id); refresh() } }
            val del = Button(this).apply { text = "Delete"; setOnClickListener { CloneRepository.delete(p.id); refresh() } }
            row.addView(rb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); row.addView(del); list.addView(row)
        }
    }

    private fun ensureModel() {
        status.text = "Preparing voice engine…"
        lifecycleScope.launch {
            runCatching { ModelManager.ensure(this@MainActivity) { p, msg -> runOnUiThread { status.text = "$msg  $p%" } } }
                .onSuccess { status.text = "✓ Voice engine ready offline" }
                .onFailure { status.text = "Model setup failed: ${it.message}" }
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
        AlertDialog.Builder(this).setTitle("Recording $pendingName").setMessage("Speak naturally for about 8–12 seconds. Different pitch, pace and vowels help the clone lock onto your voice.")
            .setPositiveButton("Stop & save") { _, _ -> recorder.stop() }.setCancelable(false).show()
    }

    private fun importClone(uri: Uri) {
        val tmp = File(cacheDir, "import_${System.currentTimeMillis()}.wav")
        runCatching { contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }; WavUtils.readPcm16Mono(tmp) }
            .onSuccess {
                val input = EditText(this).apply { hint = "Voice name" }
                AlertDialog.Builder(this).setTitle("Name this clone").setView(input).setPositiveButton("Save") {_,_-> CloneRepository.addFromWav(input.text.toString(), tmp); refresh() }.show()
            }.onFailure { Toast.makeText(this, "Import needs a 16-bit PCM WAV: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun speakNow() {
        val p = CloneRepository.selected() ?: return Toast.makeText(this, "Create a clone first", Toast.LENGTH_SHORT).show()
        val t = text.text.toString().trim(); if (t.isEmpty()) return
        lifecycleScope.launch {
            status.text = "Synthesizing ${p.name}…"
            runCatching { withContext(Dispatchers.IO) { CloneEngine.play(this@MainActivity, p, t) } }
                .onSuccess { status.text = "✓ Ready" }.onFailure { status.text = "Synthesis failed: ${it.message}" }
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

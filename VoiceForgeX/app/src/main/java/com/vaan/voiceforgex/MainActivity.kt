package com.vaan.voiceforgex

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.withTimeout

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var voices: LinearLayout
    private lateinit var ttsText: EditText
    private lateinit var speakButton: Button
    private val recorder by lazy { VoiceRecorder(this) }
    private var pendingLabel = ""
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startRecording() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); CloneRepository.init(this); VoiceGenome.init(this)
        val scroll=ScrollView(this); val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(30,30,30,50) }; scroll.addView(root); setContentView(scroll)
        root.addView(TextView(this).apply { text="VOICE CLONER"; textSize=30f; gravity=Gravity.CENTER_HORIZONTAL })
        root.addView(TextView(this).apply { text="Record → label → select → use"; textSize=15f; gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,4,0,22) })
        status=TextView(this).apply { textSize=14f; setPadding(0,0,0,16) }; root.addView(status)
        root.addView(Button(this).apply { text="1. 🎙 RECORD VOICE TO CLONE"; textSize=17f; setOnClickListener { askLabel() } })
        root.addView(TextView(this).apply { text="Speak naturally for about 8–15 seconds."; textSize=12f; setPadding(8,0,0,18) })
        root.addView(TextView(this).apply { text="2. SELECT VOICE TO USE"; textSize=19f; setPadding(0,8,0,8) })
        voices=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; root.addView(voices)
        root.addView(TextView(this).apply { text="3. TEXT TO SPEECH"; textSize=19f; setPadding(0,22,0,6) })
        ttsText=EditText(this).apply { hint="Type what you want the selected voice to say"; minLines=3; gravity=Gravity.TOP }; root.addView(ttsText)
        speakButton=Button(this).apply { text="▶ SPEAK"; textSize=17f; setOnClickListener { speak() } }; root.addView(speakButton)
        root.addView(Button(this).apply { text="4. USE THIS VOICE OUTSIDE OF APP"; textSize=17f; setOnClickListener { openTtsSettings() } })
        root.addView(TextView(this).apply { text="Choose VoiceForge in Android Text-to-Speech settings. VoiceForge uses the voice selected above."; textSize=12f; setPadding(8,4,0,0) })
        refresh(); ensureEngine()
    }

    private fun refresh() {
        status.text=if(ModelManager.isReady(this)) "✓ Voice engine ready" else "Preparing voice engine…"; voices.removeAllViews(); val all=CloneRepository.all()
        if(all.isEmpty()) voices.addView(TextView(this).apply { text="No cloned voices yet."; setPadding(8,8,0,8) })
        all.forEach { p -> VoiceGenome.ensureLegacy(p); val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            val radio=RadioButton(this).apply { text=p.name; textSize=17f; isChecked=CloneRepository.selected()?.id==p.id; setOnClickListener { CloneRepository.select(p.id); refresh(); status.text="✓ Using ${p.name}" } }
            val del=Button(this).apply { text="DELETE"; setOnClickListener { CloneRepository.delete(p.id); refresh() } }; row.addView(radio,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)); row.addView(del); voices.addView(row) }
    }

    private fun askLabel() { val input=EditText(this).apply { hint="Example: My voice, Sarah, Deep voice" }; AlertDialog.Builder(this).setTitle("Label this sample voice").setView(input).setPositiveButton("START RECORDING") { _,_-> pendingLabel=input.text.toString().trim().ifBlank { "Voice ${CloneRepository.all().size+1}" }; if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) startRecording() else mic.launch(Manifest.permission.RECORD_AUDIO) }.setNegativeButton("Cancel",null).show() }
    private fun startRecording() { recorder.start { result -> result.onSuccess { wav -> val p=CloneRepository.addFromWav(pendingLabel,wav); wav.delete(); CloneRepository.select(p.id); refresh(); status.text="✓ ${p.name} cloned and selected" }.onFailure { status.text="Recording failed: ${it.message}" } }; AlertDialog.Builder(this).setTitle("Recording: $pendingLabel").setMessage("Speak naturally. When finished, tap SAVE VOICE.").setPositiveButton("SAVE VOICE") { _,_-> recorder.stop() }.setCancelable(false).show() }

    private fun speak() {
        val p=CloneRepository.selected() ?: return Toast.makeText(this,"Record or select a voice first",Toast.LENGTH_SHORT).show(); val words=ttsText.text.toString().trim(); if(words.isEmpty()) return
        speakButton.isEnabled=false
        lifecycleScope.launch {
            status.text="Generating ${p.name}… first speech may take a little longer"
            val result=runCatching { withTimeout(90_000) { withContext(Dispatchers.IO) { ModelManager.ensure(this@MainActivity,false); CloneEngine.play(this@MainActivity,p,words,SynthesisMode.FAST) } } }
            result.onSuccess { status.text="✓ Using ${p.name}" }.onFailure { e ->
                CloneEngine.invalidate()
                status.text=if(e is kotlinx.coroutines.TimeoutCancellationException) "Generation timed out. Engine reset. Tap SPEAK again." else "Voice failed: ${e.message}"
            }
            speakButton.isEnabled=true
        }
    }

    private fun ensureEngine() { lifecycleScope.launch { status.text="Checking voice engine…"; runCatching { withTimeout(45_000) { ModelManager.ensure(this@MainActivity,true) } }.onSuccess { status.text="✓ Voice engine ready" }.onFailure { status.text=if(ModelManager.isReady(this@MainActivity)) "✓ Voice engine ready" else "Voice engine setup failed: ${it.message}" } } }
    private fun openTtsSettings() { runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}

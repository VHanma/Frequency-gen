package com.vhanma.lightcode.photophone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import kotlin.concurrent.thread

class TorchLoopActivity : Activity() {
    private lateinit var fileLabel: TextView
    private lateinit var processingSpinner: Spinner
    private lateinit var rateSpinner: Spinner
    private lateinit var gainLabel: TextView
    private lateinit var gainSeek: SeekBar
    private lateinit var forceSilent: CheckBox
    private lateinit var startButton: Button
    private lateinit var statusText: TextView

    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var busy = false
    private var running = false
    private var pendingProgram: OpticalProgram? = null
    private var engine: TorchLoopEngine? = null

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
    }

    private fun buildView(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(34))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(label("PHOTOPHONE LOOP TORCH", 28f, Color.WHITE, true))
        root.addView(label(
            "Choose audio once. Its waveform loops through the phone flashlight until you press Stop.",
            15f,
            0xFFBDBDBD.toInt()
        ))

        root.addView(actionButton("CHOOSE AUDIO") { chooseAudio() })
        fileLabel = label("No audio selected", 13f, 0xFFFFCC80.toInt())
        root.addView(fileLabel)

        root.addView(section("AUDIO PROCESSING"))
        processingSpinner = spinner(listOf(
            "Direct PCM",
            "Clarity optical EQ",
            "Compressed optical PCM"
        ))
        root.addView(processingSpinner)

        root.addView(section("TORCH UPDATE RATE"))
        rateSpinner = spinner(listOf(
            "10 updates/s",
            "20 updates/s",
            "30 updates/s",
            "40 updates/s"
        ))
        rateSpinner.setSelection(2)
        root.addView(rateSpinner)

        gainLabel = label("Torch modulation gain: 100%", 14f, Color.WHITE, true)
        root.addView(gainLabel)
        gainSeek = SeekBar(this).apply {
            max = 175
            progress = 95
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    gainLabel.text = "Torch modulation gain: ${progress + 5}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        root.addView(gainSeek)

        forceSilent = CheckBox(this).apply {
            text = "Force Android media volume to zero"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        root.addView(forceSilent)

        startButton = actionButton("START LOOPING TORCHLIGHT") {
            if (running || busy) stopLoop("Stopped by user.") else prepareLoop()
        }.apply { setBackgroundColor(0xFFEF6C00.toInt()) }
        root.addView(startButton)

        statusText = label(
            "Ready. Looping works until Stop, Back, or closing the app.",
            13f,
            0xFF80CBC4.toInt()
        )
        root.addView(statusText)

        root.addView(label(
            "Phones expose flashlight control at limited update rates. On Android 13+ devices with multiple torch strength levels, this mode maps waveform amplitude across those levels; other phones use binary on/off pulses.",
            12f,
            0xFF8E8E8E.toInt()
        ))
        return scroll
    }

    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_AUDIO)
    }

    @Deprecated("Retained for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedUri = uri
        selectedName = displayName(uri)
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        fileLabel.text = "Loaded: $selectedName · loops until Stop"
    }

    private fun prepareLoop() {
        val uri = selectedUri ?: return status("Choose an audio file first.")
        busy = true
        startButton.text = "STOP"
        status("Decoding the audio into a looping light waveform…")
        thread(name = "TorchLoopAudioDecode") {
            runCatching {
                val decoded = AudioDecoder.decode(this, uri, selectedName.ifBlank { "Audio" })
                val processing = when (processingSpinner.selectedItemPosition) {
                    1 -> MusicProcessing.CLARITY
                    2 -> MusicProcessing.COMPRESSED
                    else -> MusicProcessing.DIRECT
                }
                SignalCore.process(decoded, processing).copy(loop = true)
            }.onSuccess { program ->
                runOnUiThread {
                    pendingProgram = program
                    requestPermissionOrStart(program)
                }
            }.onFailure { error ->
                runOnUiThread { fail("Audio decode failed: ${error.message}") }
            }
        }
    }

    private fun requestPermissionOrStart(program: OpticalProgram) {
        if (
            Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            status("Camera permission is required to control the phone flashlight.")
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            startLoop(program)
        }
    }

    private fun startLoop(program: OpticalProgram) {
        if (forceSilent.isChecked) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
        }
        val rate = when (rateSpinner.selectedItemPosition) {
            0 -> 10
            1 -> 20
            3 -> 40
            else -> 30
        }
        runCatching {
            TorchLoopEngine(
                context = this,
                program = program.copy(loop = true),
                updateRateHz = rate,
                modulationGain = (gainSeek.progress + 5) / 100f,
                onStatus = { message -> runOnUiThread { status(message) } },
                onFinished = { runOnUiThread { stopLoop("Torchlight transmission ended.") } }
            ).also {
                engine = it
                busy = false
                running = true
                startButton.text = "STOP LOOPING TORCHLIGHT"
                it.start()
            }
        }.onFailure { error ->
            fail("Torchlight could not start: ${error.message}")
        }
    }

    private fun stopLoop(message: String) {
        engine?.stop()
        engine = null
        running = false
        busy = false
        pendingProgram = null
        startButton.text = "START LOOPING TORCHLIGHT"
        status(message)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        val program = pendingProgram
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && program != null) {
            startLoop(program)
        } else {
            fail("Camera permission was denied, so the flashlight cannot be controlled.")
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "selected-audio"
    }

    private fun fail(message: String) {
        running = false
        busy = false
        pendingProgram = null
        startButton.text = "START LOOPING TORCHLIGHT"
        status(message)
    }

    private fun status(message: String) {
        statusText.text = message
    }

    private fun section(text: String): TextView = label(text, 13f, 0xFFFF9800.toInt(), true)

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(8))
    }

    private fun actionButton(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF333333.toInt())
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply {
            setMargins(0, dp(8), 0, dp(4))
        }
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@TorchLoopActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setBackgroundColor(0xFF202020.toInt())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Android framework back callback")
    override fun onBackPressed() {
        if (running || busy) stopLoop("Stopped.") else super.onBackPressed()
    }

    override fun onDestroy() {
        engine?.stop()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_AUDIO = 5101
        private const val REQUEST_CAMERA = 5102
    }
}

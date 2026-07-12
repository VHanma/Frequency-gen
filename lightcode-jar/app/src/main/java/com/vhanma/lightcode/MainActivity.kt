package com.vhanma.lightcode

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var controlView: View
    private lateinit var sourceSpinner: Spinner
    private lateinit var outputSpinner: Spinner
    private lateinit var textInput: EditText
    private lateinit var fileButton: Button
    private lateinit var fileLabel: TextView
    private lateinit var frequencySeek: SeekBar
    private lateinit var frequencyLabel: TextView
    private lateinit var gainSeek: SeekBar
    private lateinit var gainLabel: TextView
    private lateinit var reverseRows: CheckBox
    private lateinit var startButton: Button
    private lateinit var statusText: TextView

    private var selectedUri: Uri? = null
    private var selectedName: String = ""
    private var running = false
    private var busy = false
    private var lightView: LightSurfaceView? = null
    private var torchEngine: TorchEngine? = null
    private var usbEngine: UsbLedEngine? = null

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var pendingTtsId: String? = null
    private var pendingTtsFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controlView = buildControlView()
        setContentView(controlView)
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale.US
            tts.setSpeechRate(0.92f)
            tts.setPitch(1.0f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) {
                    if (utteranceId == pendingTtsId) {
                        runOnUiThread { fail("Text synthesis failed.") }
                    }
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != pendingTtsId) return
                    val file = pendingTtsFile ?: return
                    thread(name = "DecodeTts") {
                        runCatching {
                            AudioDecoder.decode(this@MainActivity, Uri.fromFile(file), "Spoken text")
                        }.onSuccess { program ->
                            runOnUiThread {
                                busy = false
                                startPreparedProgram(program)
                            }
                        }.onFailure { error ->
                            runOnUiThread { fail("TTS decode failed: ${error.message}") }
                        }
                    }
                }
            })
        }
        runOnUiThread {
            statusText.text = if (ttsReady) "Ready. Phone speaker output stays unused." else "TTS engine unavailable; file and tone modes still work."
        }
    }

    private fun buildControlView(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(30))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(label("LIGHTCODE JAR", 30f, Color.WHITE, true))
        root.addView(label("Words, audio and arbitrary files encoded into changing light. No Morse. No phone-speaker playback.", 15f, 0xFFBDBDBD.toInt()))
        root.addView(spacer(14))

        root.addView(label("SOURCE", 13f, 0xFFFF6666.toInt(), true))
        sourceSpinner = spinner(listOf("Tone", "Speak typed words", "Audio file", "Exact file as AFSK data", "Resonance sweep"))
        root.addView(sourceSpinner)

        textInput = EditText(this).apply {
            setText("I move with calm precision. My timing is sharp. My body coils and releases with complete power.")
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            setBackgroundColor(0xFF151515.toInt())
            minLines = 4
            gravity = Gravity.TOP
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(textInput, matchWrap())

        fileButton = actionButton("CHOOSE FILE") { chooseFile() }
        root.addView(fileButton)
        fileLabel = label("No file selected", 13f, 0xFF8E8E8E.toInt())
        root.addView(fileLabel)

        frequencyLabel = label("Tone frequency: 440 Hz", 14f, Color.WHITE, true)
        root.addView(frequencyLabel)
        frequencySeek = SeekBar(this).apply {
            max = 3980
            progress = 420
            setOnSeekBarChangeListener(simpleSeek { value ->
                frequencyLabel.text = "Tone frequency: ${value + 20} Hz"
            })
        }
        root.addView(frequencySeek)

        root.addView(spacer(12))
        root.addView(label("LIGHT OUTPUT", 13f, 0xFFFF6666.toInt(), true))
        outputSpinner = spinner(listOf(
            "Raster DAC screen (fast experimental)",
            "Whole-screen brightness (refresh-limited)",
            "Camera torch pulse/strength",
            "USB-C LED DAC 48 kHz"
        ))
        root.addView(outputSpinner)

        gainLabel = label("Optical modulation gain: 100%", 14f, Color.WHITE, true)
        root.addView(gainLabel)
        gainSeek = SeekBar(this).apply {
            max = 145
            progress = 95
            setOnSeekBarChangeListener(simpleSeek { value ->
                gainLabel.text = "Optical modulation gain: ${value + 5}%"
            })
        }
        root.addView(gainSeek)

        reverseRows = CheckBox(this).apply {
            text = "Reverse raster scan direction"
            setTextColor(Color.WHITE)
        }
        root.addView(reverseRows)

        startButton = actionButton("START LIGHT TRANSMISSION") {
            if (running) stopAll() else prepareAndStart()
        }
        startButton.setBackgroundColor(0xFF0B8F38.toInt())
        root.addView(startButton)

        root.addView(actionButton("PHONE HARDWARE DIAGNOSTICS") {
            AlertDialog.Builder(this)
                .setTitle("Optical hardware report")
                .setMessage(HardwareDiagnostics.report(this, display))
                .setPositiveButton("CLOSE", null)
                .show()
        })

        statusText = label("Initializing…", 13f, 0xFF80CBC4.toInt())
        root.addView(statusText)

        root.addView(spacer(10))
        root.addView(label(
            "Raster DAC packs waveform slices into horizontal rows so a row-scanned panel may expose far more temporal detail than one brightness value per frame. Double-tap the light screen to stop.",
            12f,
            0xFF8E8E8E.toInt()
        ))

        sourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateSourceControls(position)
            }
        }
        updateSourceControls(0)
        return scroll
    }

    private fun updateSourceControls(position: Int) {
        textInput.visibility = if (position == 1) View.VISIBLE else View.GONE
        fileButton.visibility = if (position == 2 || position == 3) View.VISIBLE else View.GONE
        fileLabel.visibility = fileButton.visibility
        frequencyLabel.visibility = if (position == 0) View.VISIBLE else View.GONE
        frequencySeek.visibility = frequencyLabel.visibility
        fileButton.text = if (position == 2) "CHOOSE AUDIO FILE" else "CHOOSE ANY FILE"
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (sourceSpinner.selectedItemPosition == 2) "audio/*" else "*/*"
        }
        startActivityForResult(intent, REQUEST_FILE)
    }

    @Deprecated("Deprecated in Android framework, retained for broad device compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedUri = uri
        selectedName = displayName(uri)
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        fileLabel.text = "Loaded: $selectedName"
    }

    private fun prepareAndStart() {
        if (busy) return
        busy = true
        status("Preparing optical program…")
        startButton.isEnabled = false

        when (sourceSpinner.selectedItemPosition) {
            0 -> {
                val frequency = frequencySeek.progress + 20
                busy = false
                startButton.isEnabled = true
                startPreparedProgram(SignalFactory.tone(frequency.toDouble()))
            }
            1 -> synthesizeText()
            2 -> decodeSelectedAudio()
            3 -> encodeSelectedFile()
            4 -> {
                busy = false
                startButton.isEnabled = true
                startPreparedProgram(SignalFactory.logarithmicSweep())
            }
        }
    }

    private fun synthesizeText() {
        val text = textInput.text.toString().trim()
        if (text.isEmpty()) return fail("Enter words first.")
        if (!ttsReady) return fail("Android text-to-speech is not ready.")

        val file = File(cacheDir, "lightcode_tts_${System.currentTimeMillis()}.wav")
        val id = UUID.randomUUID().toString()
        pendingTtsFile = file
        pendingTtsId = id
        val result = tts.synthesizeToFile(text, Bundle(), file, id)
        if (result != TextToSpeech.SUCCESS) fail("Android refused the text synthesis request.")
        else status("Synthesizing words into a silent internal waveform…")
    }

    private fun decodeSelectedAudio() {
        val uri = selectedUri ?: return fail("Choose an audio file first.")
        thread(name = "DecodeAudio") {
            runCatching { AudioDecoder.decode(this, uri, selectedName.ifBlank { "Audio file" }) }
                .onSuccess { program -> runOnUiThread {
                    busy = false
                    startPreparedProgram(program)
                } }
                .onFailure { error -> runOnUiThread { fail("Audio decode failed: ${error.message}") } }
        }
    }

    private fun encodeSelectedFile() {
        val uri = selectedUri ?: return fail("Choose a file first.")
        thread(name = "EncodeFile") {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to read file.")
                SignalFactory.afskFile(bytes, selectedName.ifBlank { "payload.bin" })
            }.onSuccess { program -> runOnUiThread {
                busy = false
                startPreparedProgram(program)
            } }.onFailure { error -> runOnUiThread { fail("File encoding failed: ${error.message}") } }
        }
    }

    private fun startPreparedProgram(program: OpticalProgram) {
        busy = false
        startButton.isEnabled = true
        val gain = (gainSeek.progress + 5) / 100f
        when (outputSpinner.selectedItemPosition) {
            0 -> startScreen(program, ScreenOutputMode.RASTER_DAC, gain)
            1 -> startScreen(program, ScreenOutputMode.FULL_FRAME, gain)
            2 -> startTorch(program)
            3 -> startUsb(program)
        }
    }

    private fun startScreen(program: OpticalProgram, mode: ScreenOutputMode, gain: Float) {
        running = true
        startButton.text = "STOP"
        enterOpticalFullscreen()
        val view = LightSurfaceView(
            this,
            program,
            mode,
            gain,
            reverseRows.isChecked
        ) { runOnUiThread { stopAll() } }
        lightView = view
        setContentView(view)
        status("${program.label} through ${if (mode == ScreenOutputMode.RASTER_DAC) "Raster DAC" else "whole-screen mode"}")
    }

    private fun startTorch(program: OpticalProgram) {
        runCatching {
            TorchEngine(this, program, requestedRateHz = 30, ::status) {
                runOnUiThread { stopAll() }
            }.also {
                torchEngine = it
                running = true
                startButton.text = "STOP"
                it.start()
            }
        }.onFailure { fail("Torch mode unavailable: ${it.message}") }
    }

    private fun startUsb(program: OpticalProgram) {
        runCatching {
            UsbLedEngine(this, program, ::status) {
                runOnUiThread { stopAll() }
            }.also {
                usbEngine = it
                running = true
                startButton.text = "STOP"
                it.start()
            }
        }.onFailure { fail("USB LED DAC unavailable: ${it.message}") }
    }

    private fun stopAll() {
        lightView?.stop()
        lightView = null
        torchEngine?.stop()
        torchEngine = null
        usbEngine?.stop()
        usbEngine = null
        running = false
        busy = false
        exitOpticalFullscreen()
        setContentView(controlView)
        startButton.text = "START LIGHT TRANSMISSION"
        startButton.isEnabled = true
        status("Stopped. Ready for the next light program.")
    }

    private fun enterOpticalFullscreen() {
        window.attributes = window.attributes.apply {
            screenBrightness = 1f
            val fastest = display?.supportedModes?.maxByOrNull { it.refreshRate }
            if (fastest != null) preferredDisplayModeId = fastest.modeId
            preferredRefreshRate = fastest?.refreshRate ?: 0f
        }
        if (Build.VERSION.SDK_INT >= 24) runCatching { window.setSustainedPerformanceMode(true) }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun exitOpticalFullscreen() {
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            preferredRefreshRate = 0f
            preferredDisplayModeId = 0
        }
        if (Build.VERSION.SDK_INT >= 24) runCatching { window.setSustainedPerformanceMode(false) }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    @Deprecated("Android framework back callback")
    override fun onBackPressed() {
        if (running || busy) stopAll() else super.onBackPressed()
    }

    override fun onDestroy() {
        lightView?.stop()
        torchEngine?.stop()
        usbEngine?.stop()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "selected-file"
    }

    private fun fail(message: String) {
        busy = false
        startButton.isEnabled = true
        status(message)
    }

    private fun status(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(7))
    }

    private fun actionButton(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF333333.toInt())
        setOnClickListener { action() }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        params.setMargins(0, dp(8), 0, dp(4))
        layoutParams = params
    }

    private fun spinner(items: List<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setBackgroundColor(0xFF202020.toInt())
    }

    private fun simpleSeek(action: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = action(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun spacer(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_FILE = 1001
    }
}

package com.vhanma.lightcode.photophone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.media.AudioManager
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
    private lateinit var processingSpinner: Spinner
    private lateinit var outputSpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var rowsSpinner: Spinner
    private lateinit var geometrySpinner: Spinner
    private lateinit var textInput: EditText
    private lateinit var chooseFileButton: Button
    private lateinit var fileLabel: TextView
    private lateinit var toneLabel: TextView
    private lateinit var toneSeek: SeekBar
    private lateinit var gainLabel: TextView
    private lateinit var gainSeek: SeekBar
    private lateinit var reverseRows: CheckBox
    private lateinit var forceSilent: CheckBox
    private lateinit var proofMode: CheckBox
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView

    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var busy = false
    private var running = false
    private var finishing = false
    private var pendingProgram: OpticalProgram? = null

    private var musicView: MusicLightView? = null
    private var usbEngine: UsbBulkPcmEngine? = null
    private var proofRecorder: ProofRecorder? = null

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var pendingTtsId: String? = null
    private var pendingTtsFile: File? = null

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val program = pendingProgram
            if (granted && program != null) {
                status("USB permission granted. Starting volume-independent light PCM…")
                beginOutput(program)
            } else {
                fail("USB permission was not granted.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        registerUsbReceiver()
        controlView = buildControlView()
        setContentView(controlView)
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale.US
            tts.setSpeechRate(0.94f)
            tts.setPitch(1.0f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onError(utteranceId: String?) {
                    if (utteranceId == pendingTtsId) runOnUiThread { fail("Silent text synthesis failed.") }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != pendingTtsId) return
                    val file = pendingTtsFile ?: return
                    thread(name = "PhotophoneTtsDecode") {
                        runCatching {
                            AudioDecoder.decode(this@MainActivity, Uri.fromFile(file), "Typed speech")
                        }.onSuccess { decoded ->
                            runOnUiThread { prepareDecodedProgram(decoded) }
                        }.onFailure { error ->
                            runOnUiThread { fail("Synthesized speech decode failed: ${error.message}") }
                        }
                    }
                }
            })
        }
        runOnUiThread {
            statusText.text = if (ttsReady) {
                "Ready. Songs and words are decoded internally; the phone speaker is not used."
            } else {
                "TTS unavailable. Song-file, tone and sweep modes remain ready."
            }
        }
    }

    private fun buildControlView(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(34))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(label("LIGHTCODE PHOTOPHONE", 29f, Color.WHITE, true))
        root.addView(label(
            "Literal music carried by changing light. The selected song waveform becomes the brightness waveform; the black jar is the intended acoustic source.",
            14f,
            0xFFBDBDBD.toInt()
        ))
        root.addView(spacer(12))

        root.addView(section("SOURCE"))
        sourceSpinner = spinner(listOf(
            "Song or audio file",
            "Typed words as literal speech-light",
            "Calibration tone",
            "Jar sweep"
        ))
        root.addView(sourceSpinner)

        textInput = EditText(this).apply {
            setText("The song is traveling through light, and the jar is speaking.")
            minLines = 4
            gravity = Gravity.TOP
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            setBackgroundColor(0xFF151515.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(textInput, matchWrap())

        chooseFileButton = actionButton("CHOOSE SONG") { chooseFile() }
        root.addView(chooseFileButton)
        fileLabel = label("No song selected", 13f, 0xFF8E8E8E.toInt())
        root.addView(fileLabel)

        toneLabel = label("Tone frequency: 440 Hz", 14f, Color.WHITE, true)
        root.addView(toneLabel)
        toneSeek = SeekBar(this).apply {
            max = 4_480
            progress = 420
            setOnSeekBarChangeListener(simpleSeek { value ->
                toneLabel.text = "Tone frequency: ${value + 20} Hz"
            })
        }
        root.addView(toneSeek)

        root.addView(section("MUSIC PROCESSING"))
        processingSpinner = spinner(listOf(
            "Direct PCM",
            "Clarity optical EQ",
            "Compressed optical PCM"
        ))
        root.addView(processingSpinner)

        root.addView(section("LIGHT OUTPUT"))
        outputSpinner = spinner(listOf(
            "Scanline PCM screen",
            "Whole-screen low-band fallback",
            "USB bulk LED controller 48 kHz"
        ))
        root.addView(outputSpinner)

        root.addView(section("SCREEN COLOR"))
        colorSpinner = spinner(listOf("White", "Red", "Green", "Blue", "Amber", "Cyan", "Magenta"))
        root.addView(colorSpinner)

        root.addView(section("BEAM GEOMETRY"))
        geometrySpinner = spinner(listOf("Full aperture", "Hollow beam", "Central shaft", "Twin beam"))
        root.addView(geometrySpinner)

        root.addView(section("SCANLINE DENSITY"))
        rowsSpinner = spinner(listOf("Automatic", "192 rows", "256 rows", "384 rows", "512 rows", "640 rows", "768 rows"))
        root.addView(rowsSpinner)

        gainLabel = label("Optical modulation gain: 100%", 14f, Color.WHITE, true)
        root.addView(gainLabel)
        gainSeek = SeekBar(this).apply {
            max = 175
            progress = 95
            setOnSeekBarChangeListener(simpleSeek { value ->
                gainLabel.text = "Optical modulation gain: ${value + 5}%"
            })
        }
        root.addView(gainSeek)

        reverseRows = CheckBox(this).apply {
            text = "Reverse scanline direction"
            setTextColor(Color.WHITE)
        }
        root.addView(reverseRows)

        forceSilent = CheckBox(this).apply {
            text = "Force Android media volume to zero before transmission"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        root.addView(forceSilent)

        proofMode = CheckBox(this).apply {
            text = "Proof Mode: record the jar and save a WAV"
            setTextColor(Color.WHITE)
            isChecked = false
        }
        root.addView(proofMode)

        startButton = actionButton("START LITERAL MUSIC-LIGHT") {
            if (busy || running) stopAll("Stopped by user.") else prepareSource()
        }.apply { setBackgroundColor(0xFF00838F.toInt()) }
        root.addView(startButton)

        root.addView(actionButton("PHOTOPHONE HARDWARE REPORT") {
            AlertDialog.Builder(this)
                .setTitle("Photophone hardware report")
                .setMessage(hardwareReport())
                .setPositiveButton("CLOSE", null)
                .show()
        })

        progressText = label("No transmission running.", 13f, 0xFFFFCC80.toInt())
        root.addView(progressText)
        statusText = label("Initializing…", 13f, 0xFF80CBC4.toInt())
        root.addView(statusText)

        root.addView(spacer(8))
        root.addView(label(
            "Screen mode is a device-dependent experiment that exploits row scanout. USB bulk mode is the volume-independent full-rate route and requires the matching LED-controller firmware included in the repository.",
            12f,
            0xFF8E8E8E.toInt()
        ))

        sourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateSourceControls(position)
            }
        }
        outputSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val screenVisible = position != 2
                colorSpinner.isEnabled = screenVisible
                geometrySpinner.isEnabled = screenVisible
                rowsSpinner.isEnabled = position == 0
                reverseRows.isEnabled = position == 0
            }
        }
        updateSourceControls(0)
        return scroll
    }

    private fun updateSourceControls(position: Int) {
        chooseFileButton.visibility = if (position == 0) View.VISIBLE else View.GONE
        fileLabel.visibility = chooseFileButton.visibility
        textInput.visibility = if (position == 1) View.VISIBLE else View.GONE
        toneLabel.visibility = if (position == 2) View.VISIBLE else View.GONE
        toneSeek.visibility = toneLabel.visibility
        processingSpinner.isEnabled = position == 0 || position == 1
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_AUDIO_FILE)
    }

    @Deprecated("Retained for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedUri = uri
        selectedName = displayName(uri)
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        fileLabel.text = "Loaded song: $selectedName"
    }

    private fun prepareSource() {
        if (busy || running) return
        busy = true
        startButton.text = "STOP"
        status("Preparing literal PCM-to-light transmission…")

        when (sourceSpinner.selectedItemPosition) {
            0 -> decodeSong()
            1 -> synthesizeWords()
            2 -> prepareDecodedProgram(SignalCore.tone((toneSeek.progress + 20).toDouble()))
            3 -> prepareDecodedProgram(SignalCore.sweep())
        }
    }

    private fun decodeSong() {
        val uri = selectedUri ?: return fail("Choose a song or audio file first.")
        thread(name = "PhotophoneSongDecode") {
            runCatching {
                AudioDecoder.decode(this, uri, selectedName.ifBlank { "Song" })
            }.onSuccess { decoded ->
                runOnUiThread { prepareDecodedProgram(decoded) }
            }.onFailure { error ->
                runOnUiThread { fail("Song decode failed: ${error.message}") }
            }
        }
    }

    private fun synthesizeWords() {
        val text = textInput.text.toString().trim()
        if (text.isEmpty()) return fail("Enter words first.")
        if (!ttsReady) return fail("Android text-to-speech is not ready.")

        val file = File(cacheDir, "photophone_tts_${System.currentTimeMillis()}.wav")
        val utteranceId = UUID.randomUUID().toString()
        pendingTtsFile = file
        pendingTtsId = utteranceId
        val result = tts.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (result != TextToSpeech.SUCCESS) fail("Android refused silent text synthesis.")
        else status("Building speech PCM internally without playing it…")
    }

    private fun prepareDecodedProgram(decoded: OpticalProgram) {
        val processing = when (processingSpinner.selectedItemPosition) {
            1 -> MusicProcessing.CLARITY
            2 -> MusicProcessing.COMPRESSED
            else -> MusicProcessing.DIRECT
        }
        val processed = if (sourceSpinner.selectedItemPosition <= 1) {
            SignalCore.process(decoded, processing)
        } else {
            decoded
        }
        pendingProgram = processed
        if (proofMode.isChecked && !hasMicrophonePermission()) {
            status("Proof Mode needs microphone permission to record the jar.")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION)
            return
        }
        beginOutput(processed)
    }

    private fun beginOutput(program: OpticalProgram) {
        pendingProgram = program
        if (forceSilent.isChecked) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
        }

        when (outputSpinner.selectedItemPosition) {
            0 -> {
                startProofIfRequested(program)
                startScreen(program, ScreenPhotophoneMode.SCANLINE_PCM)
            }
            1 -> {
                startProofIfRequested(program)
                startScreen(program, ScreenPhotophoneMode.WHOLE_FRAME_FALLBACK)
            }
            2 -> startUsb(program)
        }
    }

    private fun startScreen(program: OpticalProgram, mode: ScreenPhotophoneMode) {
        busy = false
        running = true
        enterOpticalFullscreen()
        val view = MusicLightView(
            context = this,
            program = program,
            mode = mode,
            modulationGain = (gainSeek.progress + 5) / 100f,
            reverseRows = reverseRows.isChecked,
            colorMode = selectedColor(),
            requestedRows = selectedRows(),
            geometry = selectedGeometry(),
            onProgress = { elapsed ->
                runOnUiThread {
                    progressText.text = "${formatTime(elapsed)} / ${formatTime(program.durationSeconds)} · light only"
                }
            },
            onFinished = { runOnUiThread { finishOutput("Transmission completed.") } }
        )
        musicView = view
        setContentView(view)
        status(
            if (mode == ScreenPhotophoneMode.SCANLINE_PCM) {
                "Song PCM is controlling scanline light. Double-tap the light to stop."
            } else {
                "Whole-screen fallback active. This preserves only low-frequency envelope detail."
            }
        )
    }

    private fun startUsb(program: OpticalProgram) {
        val engine = UsbBulkPcmEngine(
            this,
            program,
            onStatus = { message -> runOnUiThread { status(message) } },
            onFinished = { runOnUiThread { finishOutput("USB photophone transmission completed.") } }
        )
        val target = engine.findTarget()
            ?: return fail("No USB device with a bulk OUT endpoint was detected.")

        if (!engine.hasPermission(target)) {
            usbEngine = engine
            status("Requesting permission for ${target.description}…")
            engine.requestPermission(target, usbPermissionIntent())
            return
        }

        startProofIfRequested(program)
        usbEngine = engine
        busy = false
        running = true
        progressText.text = "USB bulk PCM at 48,000 samples/s"
        runCatching { engine.start(target) }
            .onFailure { error -> fail("USB light controller failed: ${error.message}") }
    }

    private fun startProofIfRequested(program: OpticalProgram) {
        if (!proofMode.isChecked) return
        val recorder = ProofRecorder(
            context = this,
            sourceProgram = program,
            onStatus = { message -> runOnUiThread { status(message) } },
            onComplete = { result ->
                runOnUiThread {
                    proofRecorder = null
                    AlertDialog.Builder(this)
                        .setTitle("Photophone Proof")
                        .setMessage(result.report)
                        .setPositiveButton("CLOSE", null)
                        .show()
                }
            },
            onError = { message -> runOnUiThread {
                proofRecorder = null
                status("Proof Mode: $message")
            } }
        )
        proofRecorder = recorder
        recorder.start()
    }

    private fun finishOutput(message: String) {
        if (finishing) return
        finishing = true
        musicView?.stop()
        musicView = null
        usbEngine?.stop()
        usbEngine = null
        proofRecorder?.stop()
        running = false
        busy = false
        pendingProgram = null
        exitOpticalFullscreen()
        setContentView(controlView)
        startButton.text = "START LITERAL MUSIC-LIGHT"
        progressText.text = "No transmission running."
        status(message)
        finishing = false
    }

    private fun stopAll(message: String) {
        pendingProgram = null
        finishOutput(message)
    }

    private fun fail(message: String) {
        busy = false
        running = false
        pendingProgram = null
        startButton.text = "START LITERAL MUSIC-LIGHT"
        status(message)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC_PERMISSION) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            val program = pendingProgram
            if (granted && program != null) beginOutput(program)
            else fail("Microphone permission was denied, so Proof Mode could not start.")
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun usbPermissionIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        return PendingIntent.getBroadcast(this, 0, intent, flags)
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    private fun hardwareReport(): String {
        val modes = display?.supportedModes
            ?.sortedByDescending { it.refreshRate }
            ?.joinToString("\n") {
                "${it.physicalWidth}×${it.physicalHeight} @ ${"%.2f".format(it.refreshRate)} Hz"
            }
            ?: "Unknown"
        val target = UsbBulkPcmEngine(
            this,
            SignalCore.tone(440.0, seconds = 1.0),
            {},
            {}
        ).findTarget()
        val mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return buildString {
            append("DEVICE: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("ANDROID: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
            append("CURRENT REFRESH: ${display?.refreshRate ?: 0f} Hz\n\n")
            append("DISPLAY MODES:\n$modes\n\n")
            append("MEDIA VOLUME: $mediaVolume\n")
            append("PHONE AUDIO PLAYBACK OBJECTS: none created by Photophone\n\n")
            append("USB BULK TARGET: ${target?.description ?: "none connected"}\n")
            append("USB PCM RATE: 48,000 mono samples/s\n")
            append("SCREEN METHOD: differential row luminance synchronized to native frames")
        }
    }

    private fun selectedColor(): LightColorMode = when (colorSpinner.selectedItemPosition) {
        1 -> LightColorMode.RED
        2 -> LightColorMode.GREEN
        3 -> LightColorMode.BLUE
        4 -> LightColorMode.AMBER
        5 -> LightColorMode.CYAN
        6 -> LightColorMode.MAGENTA
        else -> LightColorMode.WHITE
    }

    private fun selectedGeometry(): BeamGeometry = when (geometrySpinner.selectedItemPosition) {
        1 -> BeamGeometry.HOLLOW_BEAM
        2 -> BeamGeometry.CENTRAL_SHAFT
        3 -> BeamGeometry.TWIN_BEAM
        else -> BeamGeometry.FULL_APERTURE
    }

    private fun selectedRows(): Int = when (rowsSpinner.selectedItemPosition) {
        1 -> 192
        2 -> 256
        3 -> 384
        4 -> 512
        5 -> 640
        6 -> 768
        else -> 0
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

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "selected-song"
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun status(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun section(text: String): TextView = label(text, 13f, 0xFF00E5FF.toInt(), true)

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
        val parameters = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        parameters.setMargins(0, dp(8), 0, dp(4))
        layoutParams = parameters
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

    @Deprecated("Android framework back callback")
    override fun onBackPressed() {
        if (running || busy) stopAll("Stopped.") else super.onBackPressed()
    }

    override fun onDestroy() {
        musicView?.stop()
        usbEngine?.stop()
        proofRecorder?.stop()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_AUDIO_FILE = 3001
        private const val REQUEST_MIC_PERMISSION = 3002
        private const val ACTION_USB_PERMISSION = "com.vhanma.lightcode.photophone.USB_PERMISSION"
    }
}

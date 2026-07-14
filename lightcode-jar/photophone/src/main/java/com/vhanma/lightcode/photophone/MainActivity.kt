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
import android.widget.FrameLayout
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
    private lateinit var showHud: CheckBox
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var telemetryText: TextView

    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var busy = false
    private var running = false
    private var finishing = false
    private var pendingProgram: OpticalProgram? = null
    private var musicView: MusicLightView? = null
    private var usbEngine: UsbBulkPcmEngine? = null
    private var proofRecorder: ProofRecorder? = null
    private var liveHud: TextView? = null
    private var lastElapsed = 0.0
    private var lastScreenStats: EfficiencySnapshot? = null
    private var lastUsbStats: UsbEfficiencySnapshot? = null

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
            if (granted && program != null) beginOutput(program)
            else fail("USB permission was not granted.")
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
            tts.setPitch(1f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) {
                    if (utteranceId == pendingTtsId) runOnUiThread { fail("Silent text synthesis failed.") }
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != pendingTtsId) return
                    val file = pendingTtsFile ?: return
                    thread(name = "EfficiencyTtsDecode") {
                        runCatching {
                            AudioDecoder.decode(this@MainActivity, Uri.fromFile(file), "Typed speech")
                        }.onSuccess { decoded ->
                            runOnUiThread { prepareDecodedProgram(decoded) }
                        }.onFailure { error ->
                            runOnUiThread { fail("Speech decode failed: ${error.message}") }
                        }
                    }
                }
            })
        }
        runOnUiThread {
            statusText.text = if (ttsReady) {
                "Efficiency edition ready. Every stage remains visible."
            } else {
                "TTS unavailable. Song, tone and sweep modes remain ready."
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

        root.addView(label("PHOTOPHONE EFFICIENCY LAB", 28f, Color.WHITE, true))
        root.addView(label(
            "Literal PCM-to-light with compact memory, hardware raster rendering, asynchronous USB packets and visible bit-by-bit telemetry.",
            14f,
            0xFFBDBDBD.toInt()
        ))

        root.addView(section("SOURCE"))
        sourceSpinner = spinner(listOf("Song or audio file", "Typed speech-light", "Calibration tone", "Jar sweep"))
        root.addView(sourceSpinner)

        textInput = EditText(this).apply {
            setText("Every sample becomes light, and every stage stays visible.")
            minLines = 3
            gravity = Gravity.TOP
            setTextColor(Color.WHITE)
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
            setOnSeekBarChangeListener(simpleSeek { toneLabel.text = "Tone frequency: ${it + 20} Hz" })
        }
        root.addView(toneSeek)

        root.addView(section("PCM PROCESSING"))
        processingSpinner = spinner(listOf("Direct PCM", "Clarity optical EQ", "Compressed optical PCM"))
        root.addView(processingSpinner)

        root.addView(section("OUTPUT ENGINE"))
        outputSpinner = spinner(listOf(
            "Hardware scanline PCM",
            "Whole-screen low-band fallback",
            "Asynchronous USB LED controller 48 kHz"
        ))
        root.addView(outputSpinner)

        root.addView(section("SCREEN PARAMETERS"))
        colorSpinner = spinner(listOf("White", "Red", "Green", "Blue", "Amber", "Cyan", "Magenta"))
        root.addView(colorSpinner)
        geometrySpinner = spinner(listOf("Full aperture", "Hollow beam", "Central shaft", "Twin beam"))
        root.addView(geometrySpinner)
        rowsSpinner = spinner(listOf("Automatic", "192 rows", "256 rows", "384 rows", "512 rows", "640 rows", "768 rows"))
        root.addView(rowsSpinner)

        gainLabel = label("Optical modulation gain: 100%", 14f, Color.WHITE, true)
        root.addView(gainLabel)
        gainSeek = SeekBar(this).apply {
            max = 175
            progress = 95
            setOnSeekBarChangeListener(simpleSeek { gainLabel.text = "Optical modulation gain: ${it + 5}%" })
        }
        root.addView(gainSeek)

        reverseRows = check("Reverse scanline direction", false)
        forceSilent = check("Force Android media volume to zero", true)
        proofMode = check("Proof Mode: record jar and save WAV", false)
        showHud = check("Show live bit-by-bit HUD over the light", true)
        root.addView(reverseRows)
        root.addView(forceSilent)
        root.addView(proofMode)
        root.addView(showHud)

        startButton = actionButton("START EFFICIENT MUSIC-LIGHT") {
            if (busy || running) stopAll("Stopped by user.") else prepareSource()
        }.apply { setBackgroundColor(0xFF00695C.toInt()) }
        root.addView(startButton)

        root.addView(actionButton("FULL BREAKDOWN REPORT") {
            AlertDialog.Builder(this)
                .setTitle("Photophone breakdown")
                .setMessage(hardwareReport())
                .setPositiveButton("CLOSE", null)
                .show()
        })

        progressText = label("No transmission running.", 13f, 0xFFFFCC80.toInt())
        telemetryText = label("No PCM loaded.", 12f, 0xFF9FA8DA.toInt())
        statusText = label("Initializing…", 13f, 0xFF80CBC4.toInt())
        root.addView(progressText)
        root.addView(telemetryText)
        root.addView(statusText)

        sourceSpinner.onItemSelectedListener = selectionListener { updateSourceControls(it) }
        outputSpinner.onItemSelectedListener = selectionListener { position ->
            val screen = position != 2
            colorSpinner.isEnabled = screen
            geometrySpinner.isEnabled = screen
            rowsSpinner.isEnabled = position == 0
            reverseRows.isEnabled = position == 0
            showHud.isEnabled = screen
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
        processingSpinner.isEnabled = position <= 1
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_AUDIO_FILE)
    }

    @Deprecated("Retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedUri = uri
        selectedName = displayName(uri)
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        fileLabel.text = "Loaded: $selectedName"
    }

    private fun prepareSource() {
        busy = true
        startButton.text = "STOP"
        status("Decoding each sample into compact 16-bit mono PCM…")
        when (sourceSpinner.selectedItemPosition) {
            0 -> decodeSong()
            1 -> synthesizeWords()
            2 -> prepareDecodedProgram(SignalCore.tone((toneSeek.progress + 20).toDouble()))
            3 -> prepareDecodedProgram(SignalCore.sweep())
        }
    }

    private fun decodeSong() {
        val uri = selectedUri ?: return fail("Choose a song first.")
        thread(name = "EfficiencySongDecode") {
            runCatching { AudioDecoder.decode(this, uri, selectedName.ifBlank { "Song" }) }
                .onSuccess { runOnUiThread { prepareDecodedProgram(it) } }
                .onFailure { runOnUiThread { fail("Song decode failed: ${it.message}") } }
        }
    }

    private fun synthesizeWords() {
        val text = textInput.text.toString().trim()
        if (text.isEmpty()) return fail("Enter words first.")
        if (!ttsReady) return fail("Android text-to-speech is not ready.")
        val file = File(cacheDir, "efficiency_tts_${System.currentTimeMillis()}.wav")
        val id = UUID.randomUUID().toString()
        pendingTtsFile = file
        pendingTtsId = id
        if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
            fail("Android refused silent text synthesis.")
        }
    }

    private fun prepareDecodedProgram(decoded: OpticalProgram) {
        val mode = when (processingSpinner.selectedItemPosition) {
            1 -> MusicProcessing.CLARITY
            2 -> MusicProcessing.COMPRESSED
            else -> MusicProcessing.DIRECT
        }
        val program = if (sourceSpinner.selectedItemPosition <= 1) SignalCore.process(decoded, mode) else decoded
        pendingProgram = program
        telemetryText.text = programBreakdown(program)
        if (proofMode.isChecked && !hasMicrophonePermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION)
        } else {
            beginOutput(program)
        }
    }

    private fun beginOutput(program: OpticalProgram) {
        pendingProgram = program
        if (forceSilent.isChecked) runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
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
                lastElapsed = elapsed
                runOnUiThread { updateLiveHud(program) }
            },
            onEfficiency = { stats ->
                lastScreenStats = stats
                runOnUiThread { updateLiveHud(program) }
            },
            onFinished = { runOnUiThread { finishOutput("Transmission completed.") } }
        )
        musicView = view

        val frame = FrameLayout(this)
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        if (showHud.isChecked) {
            val hud = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 11f
                setBackgroundColor(0xB0000000.toInt())
                setPadding(dp(8), dp(6), dp(8), dp(6))
                typeface = android.graphics.Typeface.MONOSPACE
            }
            liveHud = hud
            frame.addView(hud, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            updateLiveHud(program)
        }
        setContentView(frame)
        status("Hardware raster active. Double-tap the light to stop.")
    }

    private fun startUsb(program: OpticalProgram) {
        val engine = UsbBulkPcmEngine(
            this,
            program,
            onStatus = { runOnUiThread { message -> status(message) } },
            onFinished = { runOnUiThread { finishOutput("USB transmission completed.") } },
            onEfficiency = { stats ->
                lastUsbStats = stats
                runOnUiThread { telemetryText.text = usbBreakdown(program, stats) }
            }
        )
        val target = engine.findTarget() ?: return fail("No USB bulk OUT device detected.")
        if (!engine.hasPermission(target)) {
            usbEngine = engine
            status("Requesting USB permission for ${target.description}…")
            engine.requestPermission(target, usbPermissionIntent())
            return
        }
        startProofIfRequested(program)
        usbEngine = engine
        busy = false
        running = true
        progressText.text = "Asynchronous USB PCM: 48,000 samples/s"
        runCatching { engine.start(target) }
            .onFailure { fail("USB light controller failed: ${it.message}") }
    }

    private fun startProofIfRequested(program: OpticalProgram) {
        if (!proofMode.isChecked) return
        proofRecorder = ProofRecorder(
            this,
            program,
            onStatus = { runOnUiThread { message -> status(message) } },
            onComplete = { result -> runOnUiThread {
                proofRecorder = null
                AlertDialog.Builder(this)
                    .setTitle("Photophone Proof")
                    .setMessage(result.report)
                    .setPositiveButton("CLOSE", null)
                    .show()
            } },
            onError = { message -> runOnUiThread { status("Proof Mode: $message") } }
        ).also { it.start() }
    }

    private fun updateLiveHud(program: OpticalProgram) {
        val stats = lastScreenStats
        val thermal = stats?.thermalHeadroom?.let { if (it.isNaN()) "warming" else "%.2f".format(it) } ?: "warming"
        val text = buildString {
            append("PCM ").append(program.sampleRate).append(" Hz · 16-bit mono · ")
            append(program.samples.size).append(" samples · ")
            append(formatBytes(program.memoryBytes)).append('\n')
            append("TIME ").append(formatTime(lastElapsed)).append(" / ").append(formatTime(program.durationSeconds)).append('\n')
            if (stats != null) {
                append("RASTER ").append(stats.activeRows).append('/').append(stats.configuredRows)
                append(" rows · ").append(stats.effectiveRowRateHz.toLong()).append(" row-updates/s\n")
                append("DISPLAY ").append("%.2f".format(stats.refreshRateHz)).append(" Hz · render ")
                append(stats.renderMicros).append(" µs · dropped ").append(stats.droppedFrames).append('\n')
                append("THERMAL headroom-use ").append(thermal).append(" · frame ").append(stats.frameNumber)
            }
        }
        liveHud?.text = text
        telemetryText.text = text
    }

    private fun usbBreakdown(program: OpticalProgram, stats: UsbEfficiencySnapshot): String = buildString {
        append(programBreakdown(program)).append('\n')
        append("USB ASYNC: ").append(stats.asynchronous).append('\n')
        append("PACKETS COMPLETE: ").append(stats.packetsCompleted).append('\n')
        append("SEQUENCE: ").append(stats.sequence).append(" · QUEUE DEPTH: ").append(stats.queueDepth).append('\n')
        append("SAMPLES QUEUED: ").append(stats.samplesQueued).append('\n')
        append("THROUGHPUT: ").append(stats.throughputBytesPerSecond).append(" bytes/s")
    }

    private fun programBreakdown(program: OpticalProgram): String = buildString {
        append("SOURCE: ").append(program.label).append('\n')
        append("FORMAT: 16-bit signed mono PCM\n")
        append("SAMPLE RATE: ").append(program.sampleRate).append(" Hz\n")
        append("SAMPLE COUNT: ").append(program.samples.size).append('\n')
        append("DURATION: ").append(formatTime(program.durationSeconds)).append('\n')
        append("PCM MEMORY: ").append(formatBytes(program.memoryBytes)).append("\n")
        append("PHONE SPEAKER OBJECT: none")
    }

    private fun finishOutput(message: String) {
        if (finishing) return
        finishing = true
        musicView?.stop()
        musicView = null
        usbEngine?.stop()
        usbEngine = null
        proofRecorder?.stop()
        proofRecorder = null
        liveHud = null
        running = false
        busy = false
        pendingProgram = null
        exitOpticalFullscreen()
        setContentView(controlView)
        startButton.text = "START EFFICIENT MUSIC-LIGHT"
        progressText.text = "No transmission running."
        status(message)
        finishing = false
    }

    private fun stopAll(message: String) = finishOutput(message)

    private fun fail(message: String) {
        busy = false
        running = false
        pendingProgram = null
        startButton.text = "START EFFICIENT MUSIC-LIGHT"
        status(message)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC_PERMISSION) {
            val program = pendingProgram
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && program != null) beginOutput(program)
            else fail("Microphone permission was denied.")
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun usbPermissionIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            flags
        )
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    private fun hardwareReport(): String {
        val program = pendingProgram
        val modes = display?.supportedModes?.sortedByDescending { it.refreshRate }?.joinToString("\n") {
            "${it.physicalWidth}×${it.physicalHeight} @ ${"%.2f".format(it.refreshRate)} Hz"
        } ?: "Unknown"
        return buildString {
            append("DEVICE: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("ANDROID: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
            append("PACKAGE: $packageName\n")
            append("CURRENT REFRESH: ${display?.refreshRate ?: 0f} Hz\n\n")
            append("DISPLAY MODES:\n$modes\n\n")
            append("AUDIO STORAGE: 16-bit ShortArray, 2 bytes/sample\n")
            append("RASTER DRAW: one 1-pixel-wide hardware bitmap per frame\n")
            append("USB: two asynchronous UsbRequest buffers\n")
            append("THERMAL: adaptive row reduction near severe throttling\n")
            append("MEDIA VOLUME: ${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}\n")
            append("ANDROID SONG PLAYBACK OBJECT: none\n")
            if (program != null) append("\n").append(programBreakdown(program))
            lastScreenStats?.let { append("\n\nSCREEN LAST:\n").append(screenStatsText(it)) }
            lastUsbStats?.let { append("\n\nUSB LAST:\n").append(usbBreakdown(program ?: SignalCore.tone(440.0, 1.0), it)) }
        }
    }

    private fun screenStatsText(stats: EfficiencySnapshot): String =
        "${stats.activeRows}/${stats.configuredRows} rows, ${stats.effectiveRowRateHz.toLong()} row-updates/s, " +
            "${stats.renderMicros} µs render, ${stats.droppedFrames} dropped, thermal ${stats.thermalHeadroom}"

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
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
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
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: "selected-song"
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576L -> "%.2f MiB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KiB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }

    private fun status(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun section(text: String) = label(text, 13f, 0xFF00E5FF.toInt(), true)

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(7))
    }

    private fun check(text: String, checked: Boolean) = CheckBox(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        isChecked = checked
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF333333.toInt())
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { setMargins(0, dp(8), 0, dp(4)) }
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setBackgroundColor(0xFF202020.toInt())
    }

    private fun selectionListener(action: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
    }

    private fun simpleSeek(action: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = action(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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
        private const val REQUEST_AUDIO_FILE = 4001
        private const val REQUEST_MIC_PERMISSION = 4002
        private const val ACTION_USB_PERMISSION = "com.vhanma.lightcode.photophone.efficient.USB_PERMISSION"
    }
}

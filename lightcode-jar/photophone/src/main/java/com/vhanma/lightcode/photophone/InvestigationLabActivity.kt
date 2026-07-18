package com.vhanma.lightcode.photophone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
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
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InvestigationLabActivity : Activity() {
    private lateinit var controlView: View
    private lateinit var protocolSpinner: Spinner
    private lateinit var outputSpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var geometrySpinner: Spinner
    private lateinit var rowsSpinner: Spinner
    private lateinit var textInput: EditText
    private lateinit var seedInput: EditText
    private lateinit var chooseFileButton: Button
    private lateinit var fileLabel: TextView
    private lateinit var descriptionText: TextView
    private lateinit var gainSeek: SeekBar
    private lateinit var gainLabel: TextView
    private lateinit var loopCheck: CheckBox
    private lateinit var proofCheck: CheckBox
    private lateinit var enclosedEmitterCheck: CheckBox
    private lateinit var forceSilentCheck: CheckBox
    private lateinit var reverseRowsCheck: CheckBox
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView

    private var selectedFileUri: Uri? = null
    private var selectedFileName = ""
    private var selectedFileBytes: ByteArray? = null
    private var currentProtocol: InvestigationProtocol? = null
    private var pendingProgram: OpticalProgram? = null
    private var running = false
    private var busy = false
    private var sessionStartedAt = 0L

    private var screenView: MusicLightView? = null
    private var torchEngine: TorchLoopEngine? = null
    private var usbEngine: UsbBulkPcmEngine? = null
    private var proofRecorder: ProofRecorder? = null

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val program = pendingProgram
            if (granted && program != null) {
                startUsb(program)
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
        updateProtocolDescription(0)
    }

    private fun buildControlView(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(36))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(label("LIGHTCODE INVESTIGATION LAB", 28f, Color.WHITE, true))
        root.addView(label(
            "Declassified protocol reconstruction, established optical coding, frontier channel experiments, and separately labeled fringe-inspired patterns. Every live run is meant for an enclosed jar, photodiode, camera, or other instrument receiver.",
            14f,
            0xFFBDBDBD.toInt()
        ))

        root.addView(section("PROTOCOL"))
        protocolSpinner = spinner(InvestigationSignalFactory.protocolNames())
        root.addView(protocolSpinner)
        descriptionText = label("", 13f, 0xFFFFCC80.toInt())
        root.addView(descriptionText)

        textInput = EditText(this).apply {
            setText("Controlled optical laboratory message")
            minLines = 3
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            setBackgroundColor(0xFF151515.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(textInput, matchWrap())

        chooseFileButton = actionButton("CHOOSE EXACT FILE PAYLOAD") { chooseFile() }
        root.addView(chooseFileButton)
        fileLabel = label("No file selected", 13f, 0xFF8E8E8E.toInt())
        root.addView(fileLabel)

        seedInput = EditText(this).apply {
            hint = "Randomization seed, blank = current time"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            setBackgroundColor(0xFF151515.toInt())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(seedInput, matchWrap())

        root.addView(section("OUTPUT"))
        outputSpinner = spinner(listOf(
            "Scanline screen into enclosed receiver",
            "Whole-screen low-band output",
            "Phone torch envelope output",
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
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    gainLabel.text = "Optical modulation gain: ${progress + 5}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        root.addView(gainSeek)

        loopCheck = CheckBox(this).apply {
            text = "Loop protocol until Stop"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        root.addView(loopCheck)

        reverseRowsCheck = CheckBox(this).apply {
            text = "Reverse scanline direction"
            setTextColor(Color.WHITE)
        }
        root.addView(reverseRowsCheck)

        proofCheck = CheckBox(this).apply {
            text = "Record receiver microphone WAV and correlation envelope"
            setTextColor(Color.WHITE)
        }
        root.addView(proofCheck)

        forceSilentCheck = CheckBox(this).apply {
            text = "Force Android media volume to zero"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        root.addView(forceSilentCheck)

        enclosedEmitterCheck = CheckBox(this).apply {
            text = "Emitter is enclosed or aimed only at a jar/sensor, not a person"
            setTextColor(Color.WHITE)
        }
        root.addView(enclosedEmitterCheck)

        startButton = actionButton("START CONTROLLED LAB RUN") {
            if (running || busy) stopAll("Run stopped by user.") else prepareProtocol()
        }.apply { setBackgroundColor(0xFF4527A0.toInt()) }
        root.addView(startButton)

        root.addView(actionButton("SHOW RESEARCH MAP") {
            AlertDialog.Builder(this)
                .setTitle("Evidence map")
                .setMessage(researchMap())
                .setPositiveButton("CLOSE", null)
                .show()
        })

        progressText = label("No protocol running.", 13f, 0xFFFFCC80.toInt())
        root.addView(progressText)
        statusText = label("Ready.", 13f, 0xFF80CBC4.toInt())
        root.addView(statusText)

        protocolSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateProtocolDescription(position)
            }
        }
        outputSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val screen = position <= 1
                colorSpinner.isEnabled = screen
                geometrySpinner.isEnabled = screen
                rowsSpinner.isEnabled = position == 0
                reverseRowsCheck.isEnabled = position == 0
            }
        }

        return scroll
    }

    private fun updateProtocolDescription(position: Int) {
        val needsText = position == 4 || position == 6
        val needsFile = position == 5
        val needsSeed = position == 9
        textInput.visibility = if (needsText) View.VISIBLE else View.GONE
        chooseFileButton.visibility = if (needsFile) View.VISIBLE else View.GONE
        fileLabel.visibility = chooseFileButton.visibility
        seedInput.visibility = if (needsSeed) View.VISIBLE else View.GONE

        val preview = runCatching {
            InvestigationSignalFactory.create(
                index = position,
                text = textInput.text?.toString().orEmpty(),
                fileBytes = if (needsFile) selectedFileBytes ?: ByteArray(1) else null,
                fileName = selectedFileName.ifBlank { "preview.bin" },
                loop = true,
                seed = 1L
            )
        }.getOrNull()

        descriptionText.text = if (preview == null) {
            "Select the required input, then start the protocol."
        } else {
            "${preview.evidenceLayer.name.replace('_', ' ')}\n${preview.description}"
        }
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_FILE)
    }

    @Deprecated("Retained for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedFileUri = uri
        selectedFileName = displayName(uri)
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        busy = true
        status("Reading file into the controlled packet buffer…")
        Thread {
            runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to read the selected file.")
            }.onSuccess { bytes ->
                runOnUiThread {
                    selectedFileBytes = bytes
                    busy = false
                    fileLabel.text = "Loaded: $selectedFileName · ${bytes.size} bytes"
                    status("File payload ready.")
                }
            }.onFailure { error ->
                runOnUiThread { fail("File read failed: ${error.message}") }
            }
        }.start()
    }

    private fun prepareProtocol() {
        if (busy || running) return
        val outputIndex = outputSpinner.selectedItemPosition
        if (outputIndex <= 2 && !enclosedEmitterCheck.isChecked) {
            status("Confirm that the emitter is enclosed or aimed only at the jar/sensor.")
            return
        }

        val seed = seedInput.text.toString().toLongOrNull() ?: System.currentTimeMillis()
        val protocol = runCatching {
            InvestigationSignalFactory.create(
                index = protocolSpinner.selectedItemPosition,
                text = textInput.text.toString(),
                fileBytes = selectedFileBytes,
                fileName = selectedFileName,
                loop = loopCheck.isChecked,
                seed = seed
            )
        }.getOrElse { error ->
            fail(error.message ?: "Protocol creation failed.")
            return
        }
        currentProtocol = protocol

        val schedule = protocol.eventSchedule
        if (schedule != null) {
            val location = saveText(
                fileName = "${schedule.name}_${schedule.seed}.csv",
                mimeType = "text/csv",
                text = schedule.toCsv()
            )
            AlertDialog.Builder(this)
                .setTitle("Declassified trial schedule exported")
                .setMessage(
                    "Saved to:\n$location\n\n${protocol.description}\n\n" +
                        "This mode exports the randomized 36-trial design without producing a human-facing strobe."
                )
                .setPositiveButton("CLOSE", null)
                .show()
            logSession(protocol, "schedule-export", "saved=$location")
            return
        }

        if (protocol.instrumentOnly && outputIndex != 3) {
            status("This protocol is instrument-only and requires the enclosed USB LED controller output.")
            return
        }

        val program = protocol.program ?: return fail("Protocol contains no signal program.")
        pendingProgram = program
        if (proofCheck.isChecked && !hasMicrophonePermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        startProgram(program)
    }

    private fun startProgram(program: OpticalProgram) {
        if (forceSilentCheck.isChecked) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
        }
        sessionStartedAt = System.currentTimeMillis()
        startProofIfRequested(program)
        when (outputSpinner.selectedItemPosition) {
            0 -> startScreen(program, ScreenPhotophoneMode.SCANLINE_PCM)
            1 -> startScreen(program, ScreenPhotophoneMode.WHOLE_FRAME_FALLBACK)
            2 -> startTorch(program)
            3 -> startUsb(program)
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
            reverseRows = reverseRowsCheck.isChecked,
            colorMode = selectedColor(),
            requestedRows = selectedRows(),
            geometry = selectedGeometry(),
            onProgress = { elapsed ->
                runOnUiThread {
                    val loops = if (program.loop && program.durationSeconds > 0.0) {
                        (elapsed / program.durationSeconds).toLong()
                    } else 0L
                    progressText.text = "${formatTime(elapsed)} · loop $loops · ${currentProtocol?.name.orEmpty()}"
                }
            },
            onFinished = { runOnUiThread { stopAll("Protocol completed.") } }
        )
        screenView = view
        setContentView(view)
        status("${currentProtocol?.name} is transmitting into the enclosed receiver. Double-tap to stop.")
    }

    private fun startTorch(program: OpticalProgram) {
        val protocol = currentProtocol
        if ((protocol?.maximumUsefulOutputHz ?: 0) > 20) {
            status("Torch mode preserves only the slow envelope of this protocol; use scanline or USB for the full waveform.")
        }
        runCatching {
            TorchLoopEngine(
                context = this,
                program = program,
                updateRateHz = 40,
                modulationGain = (gainSeek.progress + 5) / 100f,
                onStatus = { message -> runOnUiThread { status(message) } },
                onFinished = { runOnUiThread { stopAll("Torch protocol ended.") } }
            ).also {
                torchEngine = it
                busy = false
                running = true
                it.start()
            }
        }.onFailure { error -> fail("Torch protocol failed: ${error.message}") }
    }

    private fun startUsb(program: OpticalProgram) {
        val engine = UsbBulkPcmEngine(
            this,
            program,
            onStatus = { message -> runOnUiThread { status(message) } },
            onFinished = { runOnUiThread { stopAll("USB protocol ended.") } }
        )
        val target = engine.findTarget()
            ?: return fail("No USB device with a bulk OUT endpoint was detected.")
        if (!engine.hasPermission(target)) {
            usbEngine = engine
            status("Requesting permission for ${target.description}…")
            engine.requestPermission(target, usbPermissionIntent())
            return
        }
        usbEngine = engine
        busy = false
        running = true
        runCatching { engine.start(target) }
            .onFailure { error -> fail("USB protocol failed: ${error.message}") }
    }

    private fun startProofIfRequested(program: OpticalProgram) {
        if (!proofCheck.isChecked) return
        val recorder = ProofRecorder(
            context = this,
            sourceProgram = program,
            onStatus = { message -> runOnUiThread { status(message) } },
            onComplete = { result ->
                runOnUiThread {
                    proofRecorder = null
                    AlertDialog.Builder(this)
                        .setTitle("Receiver recording")
                        .setMessage(result.report)
                        .setPositiveButton("CLOSE", null)
                        .show()
                }
            },
            onError = { message -> runOnUiThread {
                proofRecorder = null
                status("Receiver recorder: $message")
            } }
        )
        proofRecorder = recorder
        recorder.start()
    }

    private fun stopAll(message: String) {
        screenView?.stop()
        screenView = null
        torchEngine?.stop()
        torchEngine = null
        usbEngine?.stop()
        usbEngine = null
        proofRecorder?.stop()
        proofRecorder = null
        val protocol = currentProtocol
        val elapsed = if (sessionStartedAt == 0L) 0L else System.currentTimeMillis() - sessionStartedAt
        if (protocol != null && sessionStartedAt != 0L) {
            logSession(protocol, outputSpinner.selectedItem.toString(), "elapsed_ms=$elapsed")
        }
        running = false
        busy = false
        pendingProgram = null
        sessionStartedAt = 0L
        exitOpticalFullscreen()
        setContentView(controlView)
        progressText.text = "No protocol running."
        startButton.text = "START CONTROLLED LAB RUN"
        status(message)
    }

    private fun logSession(protocol: InvestigationProtocol, output: String, note: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        val line = buildString {
            append("timestamp,protocol,evidence_layer,output,loop,note\n")
            append(timestamp).append(',')
                .append(csv(protocol.name)).append(',')
                .append(protocol.evidenceLayer.name).append(',')
                .append(csv(output)).append(',')
                .append(loopCheck.isChecked).append(',')
                .append(csv(note)).append('\n')
        }
        saveText(
            fileName = "LightCode_Lab_Session_${System.currentTimeMillis()}.csv",
            mimeType = "text/csv",
            text = line
        )
    }

    private fun saveText(fileName: String, mimeType: String, text: String): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LightCode-Investigation-Lab")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Android could not create the lab file.")
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: error("Android could not write the lab file.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            "Download/LightCode-Investigation-Lab/$fileName"
        } else {
            val folder = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "LightCode-Investigation-Lab"
            )
            folder.mkdirs()
            val file = File(folder, fileName)
            FileOutputStream(file).use { it.write(text.toByteArray()) }
            file.absolutePath
        }
    }

    private fun researchMap(): String = buildString {
        append("DECLASSIFIED\n")
        append("• MKULTRA records establish a broad program involving drugs, hypnosis and behavior research, but the surviving files located in this search did not reveal a documented MKULTRA optical-data transmitter.\n")
        append("• A later CIA/SRI program documented randomized 0, 6 and 16 Hz remote-strobe trials with EEG monitoring. The app preserves the design as a schedule export only.\n\n")
        append("ESTABLISHED ENGINEERING\n")
        append("• Manchester coding, PPM, PRBS, Barker codes, Gold-like sequences, chirps, optical camera communication and rolling-shutter channels.\n")
        append("• Photoacoustic music and data are supported by modulated-light experiments using dark absorbers.\n\n")
        append("FRONTIER\n")
        append("• Multicarrier phase fingerprints, correlation-assisted focusing and adaptive channel probing.\n\n")
        append("REPORTED / FRINGE\n")
        append("• Declassified CIA holdings include reports on Kirlian photography and Soviet psychoenergetic devices. The Kirlian mode here copies only pulse timing into light; it is not a high-voltage corona generator.\n")
        append("• Witness reports of hollow beams, source-less luminous interiors and structured colored fields are treated as geometry inspiration, not as verified hardware specifications.\n\n")
        append("The clone never hides the active protocol and does not include covert human-targeting functions.")
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

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

    private fun usbPermissionIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            flags
        )
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

    private fun hasMicrophonePermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            val program = pendingProgram
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && program != null) {
                startProgram(program)
            } else {
                fail("Microphone permission was denied, so receiver recording could not start.")
            }
        }
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
        return uri.lastPathSegment ?: "payload.bin"
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0L)
        return "%d:%02d".format(total / 60L, total % 60L)
    }

    private fun fail(message: String) {
        busy = false
        running = false
        pendingProgram = null
        status(message)
    }

    private fun status(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun section(text: String): TextView = label(text, 13f, 0xFFB388FF.toInt(), true)

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
        adapter = ArrayAdapter(this@InvestigationLabActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setBackgroundColor(0xFF202020.toInt())
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Android framework back callback")
    override fun onBackPressed() {
        if (running || busy) stopAll("Run stopped.") else super.onBackPressed()
    }

    override fun onDestroy() {
        screenView?.stop()
        torchEngine?.stop()
        usbEngine?.stop()
        proofRecorder?.stop()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_FILE = 5101
        private const val REQUEST_MIC = 5102
        private const val ACTION_USB_PERMISSION = "com.vhanma.lightcode.investigation.USB_PERMISSION"
    }
}

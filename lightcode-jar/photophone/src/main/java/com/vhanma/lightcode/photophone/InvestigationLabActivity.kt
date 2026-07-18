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
import kotlin.concurrent.thread

class InvestigationLabActivity : Activity() {
    private lateinit var controlView: View
    private lateinit var payloadSourceSpinner: Spinner
    private lateinit var carrierSpinner: Spinner
    private lateinit var outputSpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var geometrySpinner: Spinner
    private lateinit var rowsSpinner: Spinner
    private lateinit var textInput: EditText
    private lateinit var chooseFileButton: Button
    private lateinit var fileLabel: TextView
    private lateinit var carrierDescription: TextView
    private lateinit var seedInput: EditText
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
    private var selectedFileSize: Long? = null
    private var currentSignal: OpticalSignal? = null
    private var pendingSignal: OpticalSignal? = null
    private var currentPrepared: PreparedPayload? = null
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
            val signal = pendingSignal
            if (granted && signal != null) {
                startUsb(signal, permissionAlreadyGranted = true)
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
        updatePayloadControls(0)
        updateCarrierDescription(0)
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
            "Choose exactly what you want encoded, then choose how its bytes ride through light. Text and uploaded files use the same recoverable block container.",
            14f,
            0xFFBDBDBD.toInt()
        ))

        root.addView(section("1. WHAT DO YOU WANT TO ENCODE?"))
        payloadSourceSpinner = spinner(listOf(
            "TYPE TEXT",
            "UPLOAD ANY FILE"
        ))
        root.addView(payloadSourceSpinner)

        textInput = EditText(this).apply {
            hint = "Type the complete message to encode through light"
            setText("Controlled optical laboratory message")
            minLines = 5
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            setBackgroundColor(0xFF151515.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(textInput, matchWrap())

        chooseFileButton = actionButton("UPLOAD ANY FILE TO ENCODE") { chooseFile() }
            .apply { setBackgroundColor(0xFF00695C.toInt()) }
        root.addView(chooseFileButton)
        fileLabel = label(
            "No file selected. Photos, songs, video, PDF, ZIP, APK, documents and arbitrary binary files are accepted.",
            13f,
            0xFFFFCC80.toInt()
        )
        root.addView(fileLabel)

        root.addView(section("2. CHOOSE THE LIGHT-DATA CARRIER"))
        carrierSpinner = spinner(
            UniversalPayloadEncoder.carrierNames() +
                listOf("SRI 0/6/16 Hz historical trial schedule export")
        )
        root.addView(carrierSpinner)
        carrierDescription = label("", 13f, 0xFFFFCC80.toInt())
        root.addView(carrierDescription)

        seedInput = EditText(this).apply {
            hint = "Schedule randomization seed, blank = current time"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            setBackgroundColor(0xFF151515.toInt())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(seedInput, matchWrap())

        root.addView(section("3. CHOOSE THE LIGHT OUTPUT"))
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
            text = "Loop the entire encoded payload until Stop"
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
            text = "Record receiver microphone WAV and source correlation"
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
            text = "Emitter is enclosed or aimed only at a jar, photodiode, camera or sensor"
            setTextColor(Color.WHITE)
        }
        root.addView(enclosedEmitterCheck)

        startButton = actionButton("ENCODE MY PAYLOAD AND START LIGHT") {
            if (running || busy) stopAll("Run stopped by user.") else prepareSelectedPayload()
        }.apply { setBackgroundColor(0xFF4527A0.toInt()) }
        root.addView(startButton)

        root.addView(actionButton("SHOW PAYLOAD FORMAT + RESEARCH MAP") {
            AlertDialog.Builder(this)
                .setTitle("Universal payload architecture")
                .setMessage(researchMap())
                .setPositiveButton("CLOSE", null)
                .show()
        })

        progressText = label("No payload running.", 13f, 0xFFFFCC80.toInt())
        root.addView(progressText)
        statusText = label("Ready to type or upload what you want encoded.", 13f, 0xFF80CBC4.toInt())
        root.addView(statusText)

        root.addView(label(
            "No app-defined payload size ceiling is used. Uploaded content is copied and framed on disk in 64 KiB blocks, with CRC32 per block and SHA-256 for the complete original file. Available storage and transmission time are the practical limits.",
            12f,
            0xFF8E8E8E.toInt()
        ))

        payloadSourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePayloadControls(position)
            }
        }
        carrierSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCarrierDescription(position)
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

    private fun updatePayloadControls(position: Int) {
        val typed = position == 0
        textInput.visibility = if (typed) View.VISIBLE else View.GONE
        chooseFileButton.visibility = if (typed) View.GONE else View.VISIBLE
        fileLabel.visibility = chooseFileButton.visibility
    }

    private fun updateCarrierDescription(position: Int) {
        val scheduleIndex = UniversalCarrier.entries.size
        seedInput.visibility = if (position == scheduleIndex) View.VISIBLE else View.GONE
        carrierDescription.text = when (position) {
            0 -> "ESTABLISHED ENGINEERING: self-clocking Manchester transitions. About 300 payload bits/s before framing."
            1 -> "ESTABLISHED ENGINEERING: four audio/light frequencies carry two bits per symbol. About 1,200 payload bits/s before framing."
            2 -> "ESTABLISHED + ANCIENT-TIMING INSPIRATION: each four-bit value selects one of sixteen pulse positions."
            3 -> "ESTABLISHED ENGINEERING: every payload bit is spread by a PRBS-127 correlation code for weak-channel recovery."
            4 -> "ESTABLISHED ENGINEERING: a Gold-like code spreads each payload bit and gives the stream a repeatable correlation identity."
            5 -> "FRONTIER CHANNEL METHOD: payload bits select upward or downward logarithmic-style chirps for multipath and resonance experiments."
            6 -> "REPORTED-FRINGE-INSPIRED TIMING: exact 4-FSK payload is gated by a 37 Hz pulse pattern inspired by declassified Kirlian-device descriptions."
            else -> "DECLASSIFIED HISTORICAL RECONSTRUCTION: exports the randomized twelve null, twelve 6 Hz and twelve 16 Hz trial schedule. It does not encode your payload and does not flash the screen."
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
        selectedFileSize = displaySize(uri)
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        fileLabel.text = buildString {
            append("READY TO ENCODE: ").append(selectedFileName)
            selectedFileSize?.let { append(" · ").append(formatBytes(it)) }
            append("\nThe file will be streamed into a disk-backed block container when Start is pressed.")
        }
        status("Uploaded payload selected.")
    }

    private fun prepareSelectedPayload() {
        if (busy || running) return
        val scheduleIndex = UniversalCarrier.entries.size
        if (carrierSpinner.selectedItemPosition == scheduleIndex) {
            exportHistoricalSchedule()
            return
        }

        if (outputSpinner.selectedItemPosition <= 2 && !enclosedEmitterCheck.isChecked) {
            status("Confirm that the emitter is enclosed or aimed only at the jar or sensor.")
            return
        }

        val typed = payloadSourceSpinner.selectedItemPosition == 0
        if (typed && textInput.text.toString().isEmpty()) {
            status("Type the text you want encoded.")
            return
        }
        if (!typed && selectedFileUri == null) {
            status("Tap UPLOAD ANY FILE TO ENCODE first.")
            return
        }

        busy = true
        startButton.text = "STOP"
        status(
            if (typed) "Building the typed-text payload container…"
            else "Streaming the selected file into recoverable 64 KiB blocks…"
        )

        val carrier = UniversalCarrier.entries[carrierSpinner.selectedItemPosition]
        thread(name = "InvestigationPayloadPrepare") {
            runCatching {
                val prepared = if (typed) {
                    UniversalPayloadEncoder.prepareText(this, textInput.text.toString())
                } else {
                    UniversalPayloadEncoder.prepareUri(
                        this,
                        selectedFileUri ?: error("Selected file was lost."),
                        selectedFileName.ifBlank { "payload.bin" }
                    )
                }
                prepared to UniversalPayloadSignal(prepared, carrier, loopCheck.isChecked)
            }.onSuccess { (prepared, signal) ->
                runOnUiThread {
                    currentPrepared = prepared
                    pendingSignal = signal
                    status(
                        "Payload ready: ${prepared.originalName} · ${formatBytes(prepared.originalLength)} · " +
                            "${prepared.blockCount} block(s) · SHA-256 ${prepared.sha256Hex.take(16)}…"
                    )
                    ensurePermissionsAndStart(signal)
                }
            }.onFailure { error ->
                runOnUiThread { fail("Payload preparation failed: ${error.message}") }
            }
        }
    }

    private fun ensurePermissionsAndStart(signal: OpticalSignal) {
        val needed = mutableListOf<String>()
        if (proofCheck.isChecked && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (outputSpinner.selectedItemPosition == 2 && !hasPermission(Manifest.permission.CAMERA)) {
            needed += Manifest.permission.CAMERA
        }
        if (needed.isNotEmpty()) {
            pendingSignal = signal
            requestPermissions(needed.toTypedArray(), REQUEST_RUNTIME_PERMISSIONS)
        } else {
            startSignal(signal)
        }
    }

    private fun startSignal(signal: OpticalSignal) {
        currentSignal = signal
        pendingSignal = signal
        if (forceSilentCheck.isChecked) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
        }
        sessionStartedAt = System.currentTimeMillis()
        startProofIfRequested(signal)
        when (outputSpinner.selectedItemPosition) {
            0 -> startScreen(signal, ScreenPhotophoneMode.SCANLINE_PCM)
            1 -> startScreen(signal, ScreenPhotophoneMode.WHOLE_FRAME_FALLBACK)
            2 -> startTorch(signal)
            3 -> startUsb(signal, permissionAlreadyGranted = false)
        }
    }

    private fun startScreen(signal: OpticalSignal, mode: ScreenPhotophoneMode) {
        busy = false
        running = true
        enterOpticalFullscreen()
        val view = MusicLightView(
            context = this,
            program = signal,
            mode = mode,
            modulationGain = (gainSeek.progress + 5) / 100f,
            reverseRows = reverseRowsCheck.isChecked,
            colorMode = selectedColor(),
            requestedRows = selectedRows(),
            geometry = selectedGeometry(),
            onProgress = { elapsed ->
                runOnUiThread {
                    val loopNumber = if (signal.loop && signal.durationSeconds > 0.0) {
                        (elapsed / signal.durationSeconds).toLong() + 1L
                    } else 1L
                    val position = if (signal.loop && signal.durationSeconds > 0.0) {
                        elapsed % signal.durationSeconds
                    } else elapsed
                    progressText.text =
                        "${formatTime(position)} / ${formatTime(signal.durationSeconds)} · payload loop $loopNumber"
                }
            },
            onFinished = { runOnUiThread { stopAll("Payload transmission completed.") } }
        )
        screenView = view
        setContentView(view)
        status("${signal.label} is transmitting through light. Double-tap to stop.")
    }

    private fun startTorch(signal: OpticalSignal) {
        status(
            "Torch output is limited to roughly 40 updates/s, so it carries a slow sampled envelope. " +
                "Use scanline or USB for the complete high-rate carrier."
        )
        runCatching {
            TorchLoopEngine(
                context = this,
                program = signal,
                updateRateHz = 40,
                modulationGain = (gainSeek.progress + 5) / 100f,
                onStatus = { message -> runOnUiThread { status(message) } },
                onFinished = { runOnUiThread { stopAll("Torch payload ended.") } }
            ).also {
                torchEngine = it
                busy = false
                running = true
                it.start()
            }
        }.onFailure { error -> fail("Torch output failed: ${error.message}") }
    }

    private fun startUsb(signal: OpticalSignal, permissionAlreadyGranted: Boolean) {
        val engine = usbEngine ?: UsbBulkPcmEngine(
            this,
            signal,
            onStatus = { message -> runOnUiThread { status(message) } },
            onFinished = { runOnUiThread { stopAll("USB payload ended.") } }
        )
        val target = engine.findTarget()
            ?: return fail("No USB device with a bulk OUT endpoint was detected.")
        if (!permissionAlreadyGranted && !engine.hasPermission(target)) {
            usbEngine = engine
            pendingSignal = signal
            status("Requesting permission for ${target.description}…")
            engine.requestPermission(target, usbPermissionIntent())
            return
        }
        usbEngine = engine
        busy = false
        running = true
        progressText.text = "USB optical waveform: 48,000 samples/s · ${signal.label}"
        runCatching { engine.start(target) }
            .onFailure { error -> fail("USB payload output failed: ${error.message}") }
    }

    private fun startProofIfRequested(signal: OpticalSignal) {
        if (!proofCheck.isChecked) return
        val recorder = ProofRecorder(
            context = this,
            sourceProgram = signal,
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

    private fun exportHistoricalSchedule() {
        val seed = seedInput.text.toString().toLongOrNull() ?: System.currentTimeMillis()
        val protocol = InvestigationSignalFactory.create(
            index = 9,
            text = "",
            fileBytes = null,
            fileName = null,
            loop = false,
            seed = seed
        )
        val schedule = protocol.eventSchedule ?: return fail("Schedule creation failed.")
        val location = saveText(
            fileName = "${schedule.name}_${schedule.seed}.csv",
            mimeType = "text/csv",
            text = schedule.toCsv()
        )
        AlertDialog.Builder(this)
            .setTitle("Historical SRI schedule exported")
            .setMessage(
                "Saved to:\n$location\n\n" +
                    "This declassified protocol did not encode arbitrary text or files. The randomized condition itself was the experimental variable: null, 6 Hz or 16 Hz. Use any of the seven carriers above to encode your chosen payload."
            )
            .setPositiveButton("CLOSE", null)
            .show()
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

        val signal = currentSignal ?: pendingSignal
        val prepared = currentPrepared
        val elapsed = if (sessionStartedAt == 0L) 0L else System.currentTimeMillis() - sessionStartedAt
        if (signal != null && sessionStartedAt != 0L) {
            logSession(signal, prepared, outputSpinner.selectedItem.toString(), elapsed)
        }

        currentSignal = null
        pendingSignal = null
        currentPrepared = null
        signal?.close()
        running = false
        busy = false
        sessionStartedAt = 0L
        exitOpticalFullscreen()
        setContentView(controlView)
        progressText.text = "No payload running."
        startButton.text = "ENCODE MY PAYLOAD AND START LIGHT"
        status(message)
    }

    private fun logSession(
        signal: OpticalSignal,
        prepared: PreparedPayload?,
        output: String,
        elapsedMs: Long
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        val line = buildString {
            append("timestamp,payload_name,payload_bytes,sha256,carrier,output,loop,duration_seconds,elapsed_ms\n")
            append(csv(timestamp)).append(',')
                .append(csv(prepared?.originalName ?: "typed-or-generated")).append(',')
                .append(prepared?.originalLength ?: 0L).append(',')
                .append(csv(prepared?.sha256Hex ?: "")).append(',')
                .append(csv(signal.label)).append(',')
                .append(csv(output)).append(',')
                .append(signal.loop).append(',')
                .append(signal.durationSeconds).append(',')
                .append(elapsedMs).append('\n')
        }
        saveText(
            fileName = "LightCode_Payload_Session_${System.currentTimeMillis()}.csv",
            mimeType = "text/csv",
            text = line
        )
    }

    private fun researchMap(): String = buildString {
        append("WHAT IS ACTUALLY ENCODED\n\n")
        append("The payload is exactly the UTF-8 text you type or the raw bytes of the file you upload. The historical or experimental method is only the carrier.\n\n")
        append("DISK-BACKED CONTAINER\n\n")
        append("Magic + version + UTF-8 filename + original 64-bit length + whole-file SHA-256 + 64 KiB block size + block count. Every block contains its index, length, raw payload bytes and CRC32. No app-defined total-size ceiling is imposed.\n\n")
        append("AVAILABLE CARRIERS\n\n")
        append("• Manchester: self-clocking exact data\n")
        append("• 4-FSK: two payload bits per symbol\n")
        append("• 16-position PPM: four payload bits per timed pulse window\n")
        append("• PRBS-127 spread: correlation-assisted weak-signal payload\n")
        append("• Gold-code spread: deterministic code identity\n")
        append("• Chirp spread: up/down chirps represent payload bits\n")
        append("• Kirlian-timing 4-FSK: exact payload with a separately labeled fringe-inspired 37 Hz gate\n\n")
        append("DECLASSIFIED SRI STROBE\n\n")
        append("The recovered SRI design randomized null, 6 Hz and 16 Hz trial conditions. It did not carry arbitrary messages. The lab preserves it as a CSV schedule export rather than pretending those frequencies were a secret file format.\n\n")
        append("RECONSTRUCTION\n\n")
        append("The transmitter now produces recoverable framed data. A matching microphone/camera decoder is still required to reconstruct the original file after optical transmission.")
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

    private fun hasPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RUNTIME_PERMISSIONS) return
        val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val signal = pendingSignal
        if (allGranted && signal != null) startSignal(signal)
        else fail("A required microphone or camera permission was denied.")
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

    private fun displaySize(uri: Uri): Long? {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return null
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1_024L) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1_024.0 && unit < units.lastIndex) {
            value /= 1_024.0
            unit++
        }
        return "%.2f %s".format(value, units[unit.coerceAtLeast(0)])
    }

    private fun formatTime(seconds: Double): String {
        if (!seconds.isFinite()) return "unknown"
        val total = seconds.toLong().coerceAtLeast(0L)
        val hours = total / 3_600L
        val minutes = (total % 3_600L) / 60L
        val secs = total % 60L
        return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, secs)
        else "%d:%02d".format(minutes, secs)
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun fail(message: String) {
        busy = false
        running = false
        pendingSignal?.close()
        pendingSignal = null
        currentSignal = null
        currentPrepared = null
        startButton.text = "ENCODE MY PAYLOAD AND START LIGHT"
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
        val parameters = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
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
        currentSignal?.close()
        pendingSignal?.close()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_FILE = 5101
        private const val REQUEST_RUNTIME_PERMISSIONS = 5102
        private const val ACTION_USB_PERMISSION = "com.vhanma.lightcode.investigation.USB_PERMISSION"
    }
}

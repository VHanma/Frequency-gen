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
import android.os.Handler
import android.os.Looper
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

class V2Activity : Activity(), TextToSpeech.OnInitListener {
    private enum class RunPurpose { NORMAL, CALIBRATION }

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
    private lateinit var useProfile: CheckBox
    private lateinit var startButton: Button
    private lateinit var gradeButton: Button
    private lateinit var showGradeButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var gradeText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var busy = false
    private var running = false
    private var finishing = false
    private var currentPurpose = RunPurpose.NORMAL
    private var pendingProgram: OpticalProgram? = null
    private var pendingPurpose = RunPurpose.NORMAL
    private var pendingCalibrationPlan: CalibrationPlan? = null
    private var pendingUsbEngine: UsbBulkPcmEngine? = null
    private var pendingUsbTarget: UsbLightTarget? = null

    private var pdmView: PdmMusicLightView? = null
    private var analogView: MusicLightView? = null
    private var usbEngine: UsbBulkPcmEngine? = null
    private var proofRecorder: ProofRecorder? = null
    private var calibrationRecorder: RigCalibrationRecorder? = null
    private var lastProfile: OpticalProfile? = null

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var pendingTtsId: String? = null
    private var pendingTtsFile: File? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (!granted) {
                fail("USB permission was not granted.")
                clearPendingUsb()
                return
            }

            val program = pendingProgram
            val purpose = pendingPurpose
            clearPendingUsb(keepProgram = true)
            if (program == null) {
                fail("The pending optical program was lost.")
                return
            }

            if (purpose == RunPurpose.CALIBRATION) {
                val plan = pendingCalibrationPlan
                if (plan == null) {
                    fail("Calibration plan was lost.")
                    return
                }
                launchCalibration(plan)
            } else {
                beginOutput(program, RunPurpose.NORMAL)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        registerUsbReceiver()
        lastProfile = OpticalProfileStore.load(this)
        controlView = buildControlView()
        setContentView(controlView)
        updateGradeSummary()
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
                    if (utteranceId == pendingTtsId) {
                        runOnUiThread { fail("Silent text synthesis failed.") }
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != pendingTtsId) return
                    val file = pendingTtsFile ?: return
                    thread(name = "PhotophoneForgeTts") {
                        runCatching {
                            AudioDecoder.decode(this@V2Activity, Uri.fromFile(file), "Typed speech")
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
                "Forge ready. First run AUTO-GRADE so the app can measure the real light-to-jar path."
            } else {
                "TTS is unavailable. Song, tone, sweep and rig grading remain available."
            }
        }
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

        root.addView(label("LIGHTCODE PHOTOPHONE FORGE", 28f, Color.WHITE, true))
        root.addView(label(
            "V2 grades the physical rig, uses binary pulse-density light, and builds inverse EQ from the jar's measured response.",
            14f,
            0xFFBDBDBD.toInt()
        ))
        root.addView(spacer(10))

        root.addView(section("SOURCE"))
        sourceSpinner = spinner(listOf(
            "Song or audio file",
            "Typed words as speech-light",
            "Calibration tone",
            "Manual logarithmic sweep"
        ))
        root.addView(sourceSpinner)

        textInput = EditText(this).apply {
            setText("The complete sentence is traveling through modulated light, and the jar is the acoustic source.")
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

        toneLabel = label("Tone frequency: 1,000 Hz", 14f, Color.WHITE, true)
        root.addView(toneLabel)
        toneSeek = SeekBar(this).apply {
            max = 12_480
            progress = 980
            setOnSeekBarChangeListener(simpleSeek { value ->
                toneLabel.text = "Tone frequency: ${value + 20} Hz"
            })
        }
        root.addView(toneSeek)

        root.addView(section("AUDIO PREPARATION"))
        processingSpinner = spinner(listOf(
            "Direct PCM",
            "Speech/detail clarity",
            "High-density optical compression"
        ))
        root.addView(processingSpinner)

        useProfile = CheckBox(this).apply {
            text = "Apply measured jar inverse EQ"
            setTextColor(Color.WHITE)
            isChecked = lastProfile != null
            isEnabled = lastProfile != null
        }
        root.addView(useProfile)

        root.addView(section("LIGHT ENGINE"))
        outputSpinner = spinner(listOf(
            "Binary PDM scanline screen",
            "Analog grayscale scanline",
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
            text = "Reverse scan direction"
            setTextColor(Color.WHITE)
        }
        root.addView(reverseRows)

        forceSilent = CheckBox(this).apply {
            text = "Force Android media volume to zero"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        root.addView(forceSilent)

        proofMode = CheckBox(this).apply {
            text = "Proof Mode: save a microphone WAV of the jar"
            setTextColor(Color.WHITE)
        }
        root.addView(proofMode)

        gradeButton = actionButton("AUTO-GRADE RIG + BUILD INVERSE EQ") {
            requestOrStartRigGrade()
        }.apply { setBackgroundColor(0xFF6A1B9A.toInt()) }
        root.addView(gradeButton)

        showGradeButton = actionButton("SHOW LAST FULL RIG REPORT") {
            val profile = lastProfile
            if (profile == null) {
                status("No rig profile exists yet.")
            } else {
                showReport(profile)
            }
        }
        showGradeButton.isEnabled = lastProfile != null
        root.addView(showGradeButton)

        startButton = actionButton("START CALIBRATED MUSIC-LIGHT") {
            if (busy || running) stopAll("Stopped by user.", analyzeCalibration = false)
            else prepareSource()
        }.apply { setBackgroundColor(0xFF00838F.toInt()) }
        root.addView(startButton)

        root.addView(actionButton("WHY THE CURRENT RIG ONLY MAKES NOISE") {
            AlertDialog.Builder(this)
                .setTitle("Physical bottleneck map")
                .setMessage(receiverGuide())
                .setPositiveButton("CLOSE", null)
                .show()
        })

        gradeText = label("No measured rig grade yet.", 14f, 0xFFFFCC80.toInt(), true)
        root.addView(gradeText)
        progressText = label("No transmission running.", 13f, 0xFFFFCC80.toInt())
        root.addView(progressText)
        statusText = label("Initializing…", 13f, 0xFF80CBC4.toInt())
        root.addView(statusText)

        root.addView(spacer(8))
        root.addView(label(
            "A full song needs usable bandwidth, not merely visible rings. Grade F means software has detected that the physical light/absorber/cavity path must be upgraded before intelligible audio is realistic.",
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
                val screen = position != 2
                colorSpinner.isEnabled = screen
                geometrySpinner.isEnabled = screen
                rowsSpinner.isEnabled = screen
                reverseRows.isEnabled = screen
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
        fileLabel.text = "Loaded: $selectedName"
    }

    private fun prepareSource() {
        if (busy || running) return
        busy = true
        startButton.text = "STOP"
        status("Preparing the literal PCM light waveform…")
        when (sourceSpinner.selectedItemPosition) {
            0 -> decodeSong()
            1 -> synthesizeWords()
            2 -> prepareDecodedProgram(SignalCore.tone((toneSeek.progress + 20).toDouble()))
            3 -> prepareDecodedProgram(SignalCore.sweep())
        }
    }

    private fun decodeSong() {
        val uri = selectedUri ?: return fail("Choose a song first.")
        thread(name = "PhotophoneForgeSong") {
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
        val file = File(cacheDir, "photophone_forge_tts_${System.currentTimeMillis()}.wav")
        val utteranceId = UUID.randomUUID().toString()
        pendingTtsFile = file
        pendingTtsId = utteranceId
        val result = tts.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (result != TextToSpeech.SUCCESS) fail("Android refused silent text synthesis.")
        else status("Synthesizing speech internally without playing it…")
    }

    private fun prepareDecodedProgram(decoded: OpticalProgram) {
        val processing = when (processingSpinner.selectedItemPosition) {
            1 -> MusicProcessing.CLARITY
            2 -> MusicProcessing.COMPRESSED
            else -> MusicProcessing.DIRECT
        }
        var prepared = if (sourceSpinner.selectedItemPosition <= 1) {
            SignalCore.process(decoded, processing)
        } else decoded

        val profile = lastProfile
        if (useProfile.isChecked && profile != null) {
            status("Applying the jar's measured inverse EQ…")
            thread(name = "PhotophoneForgeEq") {
                runCatching { AdaptiveOpticalEqualizer.apply(prepared, profile) }
                    .onSuccess { equalized -> runOnUiThread { beginNormalOutput(equalized) } }
                    .onFailure { error -> runOnUiThread { fail("Measured EQ failed: ${error.message}") } }
            }
        } else {
            beginNormalOutput(prepared)
        }
    }

    private fun beginNormalOutput(program: OpticalProgram) {
        pendingProgram = program
        pendingPurpose = RunPurpose.NORMAL
        if (proofMode.isChecked && !hasMicrophonePermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_NORMAL)
            return
        }
        beginOutput(program, RunPurpose.NORMAL)
    }

    private fun requestOrStartRigGrade() {
        if (busy || running) {
            status("Stop the current transmission before grading the rig.")
            return
        }
        if (!hasMicrophonePermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_CALIBRATION)
            return
        }
        startRigGrade()
    }

    private fun startRigGrade() {
        val plan = CalibrationSignalFactory.create()
        pendingCalibrationPlan = plan
        pendingProgram = plan.program
        pendingPurpose = RunPurpose.CALIBRATION
        busy = true
        gradeButton.isEnabled = false
        startButton.text = "STOP"
        status("Preparing the multiband rig-grade sequence…")

        if (outputSpinner.selectedItemPosition == 2) {
            val probe = newUsbEngine(plan.program, RunPurpose.CALIBRATION)
            val target = probe.findTarget()
                ?: return fail("No USB bulk LED controller was detected for calibration.")
            if (!probe.hasPermission(target)) {
                pendingUsbEngine = probe
                pendingUsbTarget = target
                status("Grant USB permission; the microphone test begins afterward.")
                probe.requestPermission(target, usbPermissionIntent())
                return
            }
        }
        launchCalibration(plan)
    }

    private fun launchCalibration(plan: CalibrationPlan) {
        currentPurpose = RunPurpose.CALIBRATION
        val recorder = RigCalibrationRecorder(
            plan,
            onStatus = { message -> runOnUiThread { status(message) } },
            onComplete = { profile ->
                runOnUiThread {
                    calibrationRecorder = null
                    lastProfile = profile
                    OpticalProfileStore.save(this, profile)
                    useProfile.isEnabled = true
                    useProfile.isChecked = true
                    showGradeButton.isEnabled = true
                    gradeButton.isEnabled = true
                    busy = false
                    updateGradeSummary()
                    status("Rig grade ${profile.grade} saved. Inverse EQ is now active.")
                    showReport(profile)
                }
            },
            onError = { message ->
                runOnUiThread {
                    calibrationRecorder = null
                    gradeButton.isEnabled = true
                    fail("Rig grading failed: $message")
                }
            }
        )
        calibrationRecorder = recorder
        recorder.start()
        mainHandler.postDelayed({
            if (calibrationRecorder === recorder) {
                beginOutput(plan.program, RunPurpose.CALIBRATION)
            }
        }, 220L)
    }

    private fun beginOutput(program: OpticalProgram, purpose: RunPurpose) {
        pendingProgram = program
        pendingPurpose = purpose
        currentPurpose = purpose
        if (forceSilent.isChecked) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
        }

        when (outputSpinner.selectedItemPosition) {
            0 -> {
                if (purpose == RunPurpose.NORMAL) startProofIfRequested(program)
                startPdmScreen(program, purpose)
            }
            1 -> {
                if (purpose == RunPurpose.NORMAL) startProofIfRequested(program)
                startAnalogScreen(program, purpose)
            }
            2 -> startUsb(program, purpose)
        }
    }

    private fun startPdmScreen(program: OpticalProgram, purpose: RunPurpose) {
        busy = false
        running = true
        enterOpticalFullscreen()
        val view = PdmMusicLightView(
            context = this,
            program = program,
            modulationGain = (gainSeek.progress + 5) / 100f,
            reverseRows = reverseRows.isChecked,
            colorMode = selectedColor(),
            requestedRows = selectedRows(),
            geometry = selectedGeometry(),
            onProgress = { elapsed -> runOnUiThread { updateProgress(elapsed, program, "binary PDM") } },
            onFinished = { runOnUiThread { outputFinished(purpose) } }
        )
        pdmView = view
        setContentView(view)
        status("Binary PDM light is carrying the waveform. Double-tap to stop.")
    }

    private fun startAnalogScreen(program: OpticalProgram, purpose: RunPurpose) {
        busy = false
        running = true
        enterOpticalFullscreen()
        val view = MusicLightView(
            context = this,
            program = program,
            mode = ScreenPhotophoneMode.SCANLINE_PCM,
            modulationGain = (gainSeek.progress + 5) / 100f,
            reverseRows = reverseRows.isChecked,
            colorMode = selectedColor(),
            requestedRows = selectedRows(),
            geometry = selectedGeometry(),
            onProgress = { elapsed -> runOnUiThread { updateProgress(elapsed, program, "analog rows") } },
            onFinished = { runOnUiThread { outputFinished(purpose) } }
        )
        analogView = view
        setContentView(view)
        status("Legacy grayscale scanline light is active. Double-tap to stop.")
    }

    private fun startUsb(program: OpticalProgram, purpose: RunPurpose) {
        val engine = newUsbEngine(program, purpose)
        val target = engine.findTarget()
            ?: return fail("No USB device with a bulk OUT endpoint was detected.")
        if (!engine.hasPermission(target)) {
            pendingUsbEngine = engine
            pendingUsbTarget = target
            pendingProgram = program
            pendingPurpose = purpose
            status("Requesting permission for ${target.description}…")
            engine.requestPermission(target, usbPermissionIntent())
            return
        }

        if (purpose == RunPurpose.NORMAL) startProofIfRequested(program)
        usbEngine = engine
        busy = false
        running = true
        progressText.text = "USB PCM: 48,000 samples/s"
        runCatching { engine.start(target) }
            .onFailure { error -> fail("USB light controller failed: ${error.message}") }
    }

    private fun newUsbEngine(program: OpticalProgram, purpose: RunPurpose): UsbBulkPcmEngine =
        UsbBulkPcmEngine(
            this,
            program,
            onStatus = { message -> runOnUiThread { status(message) } },
            onFinished = { runOnUiThread { outputFinished(purpose) } }
        )

    private fun outputFinished(purpose: RunPurpose) {
        if (purpose == RunPurpose.CALIBRATION) {
            stopOutputSurfaces()
            running = false
            busy = true
            restoreControls()
            status("Light test completed. Analyzing the microphone recording…")
            calibrationRecorder?.stopAndAnalyze()
        } else {
            finishNormalOutput("Transmission completed.")
        }
    }

    private fun finishNormalOutput(message: String) {
        if (finishing) return
        finishing = true
        stopOutputSurfaces()
        proofRecorder?.stop()
        proofRecorder = null
        running = false
        busy = false
        pendingProgram = null
        restoreControls()
        startButton.text = "START CALIBRATED MUSIC-LIGHT"
        progressText.text = "No transmission running."
        status(message)
        finishing = false
    }

    private fun stopAll(message: String, analyzeCalibration: Boolean) {
        val wasCalibration = currentPurpose == RunPurpose.CALIBRATION
        stopOutputSurfaces()
        proofRecorder?.stop()
        proofRecorder = null
        if (wasCalibration) {
            if (analyzeCalibration) calibrationRecorder?.stopAndAnalyze()
            else calibrationRecorder?.cancel()
            calibrationRecorder = null
        }
        running = false
        busy = false
        pendingProgram = null
        pendingCalibrationPlan = null
        clearPendingUsb()
        restoreControls()
        startButton.text = "START CALIBRATED MUSIC-LIGHT"
        gradeButton.isEnabled = true
        progressText.text = "No transmission running."
        status(message)
    }

    private fun stopOutputSurfaces() {
        pdmView?.stop()
        pdmView = null
        analogView?.stop()
        analogView = null
        usbEngine?.stop()
        usbEngine = null
    }

    private fun restoreControls() {
        exitOpticalFullscreen()
        setContentView(controlView)
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

    private fun updateProgress(elapsed: Double, program: OpticalProgram, engine: String) {
        progressText.text = "${formatTime(elapsed)} / ${formatTime(program.durationSeconds)} · $engine · light only"
    }

    private fun showReport(profile: OpticalProfile) {
        AlertDialog.Builder(this)
            .setTitle("Rig grade ${profile.grade}")
            .setMessage(profile.report)
            .setPositiveButton("USE THIS EQ") { _, _ ->
                useProfile.isEnabled = true
                useProfile.isChecked = true
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun updateGradeSummary() {
        if (!::gradeText.isInitialized) return
        val profile = lastProfile
        gradeText.text = if (profile == null) {
            "No measured rig grade yet."
        } else {
            "Rig ${profile.grade} · power ${profile.powerScore} · speech ${profile.speechScore} · music ${profile.musicScore} · ${profile.usableLowHz}–${profile.usableHighHz} Hz"
        }
    }

    private fun receiverGuide(): String = buildString {
        append("SELF-GRADE OF V1\n\n")
        append("Whole-screen music: 1/10. A 90 Hz panel cannot directly render full audio as one brightness value per frame.\n\n")
        append("Grayscale scanline experiment: 3/10. It creates visible structure and some noise, but display scanout and grayscale PWM are not controlled tightly enough for faithful PCM.\n\n")
        append("Painted glass receiver: 2/10 for speech. Thick glass and paint store heat, which smears rapid temperature changes.\n\n")
        append("USB PCM architecture: 7/10 before physical testing. It bypasses media volume and can drive a real high-speed LED stage.\n\n")
        append("THE REQUIRED PHYSICAL UPGRADE\n\n")
        append("Keep the painted jar, but add a removable, extremely low-mass black absorber near the light entrance. A porous carbon or graphene-like foam is the broadband target. A sealed soot-coated ultrathin insert is a lower-cost experiment; avoid loose soot or inhalation.\n\n")
        append("Focus a high-speed LED onto that insert, seal unwanted air leaks, and use a short ear tube or horn from the jar opening. Run AUTO-GRADE after every physical change.\n\n")
        append("Binary PDM and inverse EQ can recover fidelity only after the physical path produces measurable speech-band energy. Software cannot manufacture optical power or remove the thermal mass of glass.")
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

    private fun hasMicrophonePermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQUEST_MIC_NORMAL -> {
                val program = pendingProgram
                if (granted && program != null) beginOutput(program, RunPurpose.NORMAL)
                else fail("Microphone permission was denied, so Proof Mode could not start.")
            }
            REQUEST_MIC_CALIBRATION -> {
                if (granted) startRigGrade()
                else fail("Microphone permission is required to grade the physical jar.")
            }
        }
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

    private fun clearPendingUsb(keepProgram: Boolean = false) {
        pendingUsbEngine = null
        pendingUsbTarget = null
        if (!keepProgram) pendingProgram = null
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

    private fun fail(message: String) {
        busy = false
        running = false
        pendingProgram = null
        gradeButton.isEnabled = true
        startButton.text = "START CALIBRATED MUSIC-LIGHT"
        status(message)
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
        adapter = ArrayAdapter(this@V2Activity, android.R.layout.simple_spinner_dropdown_item, items)
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

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Android framework back callback")
    override fun onBackPressed() {
        if (running || busy) stopAll("Stopped.", analyzeCalibration = false)
        else super.onBackPressed()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        pdmView?.stop()
        analogView?.stop()
        usbEngine?.stop()
        proofRecorder?.stop()
        calibrationRecorder?.cancel()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_AUDIO_FILE = 4001
        private const val REQUEST_MIC_NORMAL = 4002
        private const val REQUEST_MIC_CALIBRATION = 4003
        private const val ACTION_USB_PERMISSION = "com.vhanma.lightcode.photophone.v2.USB_PERMISSION"
    }
}

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
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.concurrent.thread

class UniversalPayloadActivity : Activity() {
    private lateinit var controlView: View
    private lateinit var sourceSpinner: Spinner
    private lateinit var carrierSpinner: Spinner
    private lateinit var outputSpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var geometrySpinner: Spinner
    private lateinit var rowsSpinner: Spinner
    private lateinit var textInput: EditText
    private lateinit var chooseFileButton: Button
    private lateinit var fileLabel: TextView
    private lateinit var carrierDescription: TextView
    private lateinit var loopCheck: CheckBox
    private lateinit var fecCheck: CheckBox
    private lateinit var enclosedCheck: CheckBox
    private lateinit var forceSilentCheck: CheckBox
    private lateinit var reverseRowsCheck: CheckBox
    private lateinit var gainSeek: SeekBar
    private lateinit var gainLabel: TextView
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private var selectedUri: Uri? = null
    private var selectedName = ""
    private var selectedSize = -1L
    private var descriptor: UniversalPayloadDescriptor? = null
    private var manifest: UniversalPayloadManifest? = null
    private var pendingFactory: UniversalStreamFactory? = null
    private var pendingUsbEngine: UniversalPayloadUsbEngine? = null
    private var pendingUsbTarget: UsbLightTarget? = null
    private var pendingTorchStart = false
    private var running = false
    private var busy = false

    private var lightView: UniversalPayloadLightView? = null
    private var torchEngine: UniversalPayloadTorchEngine? = null
    private var usbEngine: UniversalPayloadUsbEngine? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val engine = pendingUsbEngine
            val target = pendingUsbTarget
            if (granted && engine != null && target != null) {
                pendingUsbEngine = null
                pendingUsbTarget = null
                usbEngine = engine
                busy = false
                running = true
                engine.start(target)
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
        updateCarrierDescription()
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

        root.addView(label("UNIVERSAL LIGHT PAYLOAD", 28f, Color.WHITE, true))
        root.addView(label(
            "Choose any text or any file type. The app streams it in 4,096-byte blocks with a 64-bit length, per-block CRC32 and whole-file SHA-256. It does not impose a payload-size ceiling.",
            14f,
            0xFFBDBDBD.toInt()
        ))

        root.addView(section("PAYLOAD"))
        sourceSpinner = spinner(listOf("Any file", "Typed UTF-8 text"))
        root.addView(sourceSpinner)

        chooseFileButton = actionButton("CHOOSE ANY FILE") { chooseFile() }
        root.addView(chooseFileButton)
        fileLabel = label("No file selected", 13f, 0xFFFFCC80.toInt())
        root.addView(fileLabel)

        textInput = EditText(this).apply {
            setText("Whatever I decide to transmit through light")
            minLines = 4
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            setBackgroundColor(0xFF151515.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(textInput, matchWrap())

        root.addView(section("CARRIER"))
        carrierSpinner = spinner(UniversalCarrier.entries.map { it.displayName })
        root.addView(carrierSpinner)
        carrierDescription = label("", 13f, 0xFFFFCC80.toInt())
        root.addView(carrierDescription)

        fecCheck = CheckBox(this).apply {
            text = "Extended Hamming error correction"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        root.addView(fecCheck)

        loopCheck = CheckBox(this).apply {
            text = "Loop the complete payload until Stop"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        root.addView(loopCheck)

        root.addView(section("OUTPUT"))
        outputSpinner = spinner(listOf(
            "Scanline screen into enclosed receiver",
            "Whole-screen slow output",
            "Phone torch slow output",
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

        reverseRowsCheck = CheckBox(this).apply {
            text = "Reverse scanline direction"
            setTextColor(Color.WHITE)
        }
        root.addView(reverseRowsCheck)

        forceSilentCheck = CheckBox(this).apply {
            text = "Force Android media volume to zero"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        root.addView(forceSilentCheck)

        enclosedCheck = CheckBox(this).apply {
            text = "Emitter is enclosed or aimed only at a jar, camera or sensor"
            setTextColor(Color.WHITE)
        }
        root.addView(enclosedCheck)

        startButton = actionButton("HASH + TRANSMIT UNIVERSAL PAYLOAD") {
            if (running || busy) stopAll("Transmission stopped.") else preparePayload()
        }.apply { setBackgroundColor(0xFF00695C.toInt()) }
        root.addView(startButton)

        root.addView(actionButton("SHOW LAST PAYLOAD MANIFEST") {
            val current = manifest
            if (current == null) status("No payload manifest exists yet.")
            else AlertDialog.Builder(this)
                .setTitle("Universal payload manifest")
                .setMessage(current.asText())
                .setPositiveButton("CLOSE", null)
                .show()
        })

        progressText = label("No universal payload running.", 13f, 0xFFFFCC80.toInt())
        root.addView(progressText)
        statusText = label("Ready.", 13f, 0xFF80CBC4.toInt())
        root.addView(statusText)

        root.addView(label(
            "Large files are streamed. They are never expanded into a giant in-memory waveform. Transmission time can still be long: at five torch bits per second, one megabyte takes many days; Fast 4-FSK and USB are the practical large-payload routes.",
            12f,
            0xFF8E8E8E.toInt()
        ))

        sourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val file = position == 0
                chooseFileButton.visibility = if (file) View.VISIBLE else View.GONE
                fileLabel.visibility = chooseFileButton.visibility
                textInput.visibility = if (file) View.GONE else View.VISIBLE
                descriptor = null
            }
        }
        carrierSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCarrierDescription()
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
                if (position == 1 || position == 2) {
                    carrierSpinner.setSelection(UniversalCarrier.TORCH_SLOW_OOK.ordinal)
                }
            }
        }

        return scroll
    }

    private fun updateCarrierDescription() {
        if (!::carrierDescription.isInitialized) return
        val carrier = UniversalCarrier.entries[carrierSpinner.selectedItemPosition]
        carrierDescription.text = "${carrier.description}\nNominal payload rate before framing/FEC: ${"%.2f".format(carrier.estimatedBitsPerSecond)} bits/s"
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
        selectedUri = uri
        selectedName = displayName(uri)
        selectedSize = displaySize(uri)
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        descriptor = null
        fileLabel.text = "Loaded: $selectedName · ${formatBytes(selectedSize)}"
    }

    private fun preparePayload() {
        if (!enclosedCheck.isChecked) {
            status("Confirm that the emitter is enclosed or aimed only at the jar/camera/sensor.")
            return
        }
        busy = true
        startButton.text = "STOP"
        val carrier = UniversalCarrier.entries[carrierSpinner.selectedItemPosition]
        val output = outputSpinner.selectedItemPosition
        if ((output == 1 || output == 2) && carrier != UniversalCarrier.TORCH_SLOW_OOK) {
            busy = false
            startButton.text = "HASH + TRANSMIT UNIVERSAL PAYLOAD"
            status("Whole-screen and phone-torch outputs require Torch-safe slow OOK.")
            return
        }

        if (sourceSpinner.selectedItemPosition == 1) {
            val bytes = textInput.text.toString().toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) return fail("Enter text first.")
            status("Hashing typed payload…")
            thread(name = "UniversalTextHash") {
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                val built = byteArrayDescriptor("typed-message.txt", bytes, sha)
                runOnUiThread { descriptorReady(built) }
            }
        } else {
            val uri = selectedUri ?: return fail("Choose any file first.")
            status("Streaming through the file once to calculate SHA-256…")
            thread(name = "UniversalFileHash") {
                runCatching { hashFile(uri) }
                    .onSuccess { built -> runOnUiThread { descriptorReady(built) } }
                    .onFailure { error -> runOnUiThread { fail("File hashing failed: ${error.message}") } }
            }
        }
    }

    private fun hashFile(uri: Uri): UniversalPayloadDescriptor {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val buffer = ByteArray(256 * 1_024)
        contentResolver.openInputStream(uri)?.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                count += read.toLong()
                if (count % (16L * 1_024L * 1_024L) < read.toLong()) {
                    runOnUiThread { status("Hashed ${formatBytes(count)}…") }
                }
            }
        } ?: error("Android could not open the selected file.")
        val name = selectedName.ifBlank { displayName(uri) }
        return UniversalPayloadDescriptor(
            fileName = name,
            payloadBytes = count,
            sha256 = digest.digest(),
            openInput = {
                contentResolver.openInputStream(uri)
                    ?: error("Android could not reopen the payload stream.")
            }
        )
    }

    private fun descriptorReady(built: UniversalPayloadDescriptor) {
        descriptor = built
        val carrier = UniversalCarrier.entries[carrierSpinner.selectedItemPosition]
        val currentManifest = UniversalPayloadFactory.manifest(
            descriptor = built,
            carrier = carrier,
            fecEnabled = fecCheck.isChecked,
            loop = loopCheck.isChecked
        )
        manifest = currentManifest
        saveManifest(currentManifest)
        val hours = currentManifest.estimatedSecondsPerPass / 3_600.0
        status(
            "Payload ready: ${formatBytes(built.payloadBytes)}. Estimated pass: " +
                if (hours >= 1.0) "${"%.2f".format(hours)} hours." else "${"%.1f".format(currentManifest.estimatedSecondsPerPass)} seconds."
        )
        startTransmission(built, carrier)
    }

    private fun startTransmission(descriptor: UniversalPayloadDescriptor, carrier: UniversalCarrier) {
        if (forceSilentCheck.isChecked) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
        }
        val factory = UniversalStreamFactory(
            descriptor = descriptor,
            carrier = carrier,
            fecEnabled = fecCheck.isChecked,
            loop = loopCheck.isChecked
        )
        pendingFactory = factory
        when (outputSpinner.selectedItemPosition) {
            0 -> startScreen(factory, wholeFrame = false)
            1 -> startScreen(factory, wholeFrame = true)
            2 -> requestOrStartTorch(factory)
            3 -> startUsb(factory)
        }
    }

    private fun startScreen(factory: UniversalStreamFactory, wholeFrame: Boolean) {
        val sourceRate = if (wholeFrame) 60 else 24_000
        val source = factory.create(sourceRate)
        val view = UniversalPayloadLightView(
            context = this,
            source = source,
            wholeFrame = wholeFrame,
            modulationGain = (gainSeek.progress + 5) / 100f,
            reverseRows = reverseRowsCheck.isChecked,
            colorMode = selectedColor(),
            requestedRows = selectedRows(),
            geometry = selectedGeometry(),
            onProgress = { seconds, fraction -> runOnUiThread { updateProgress(seconds, fraction) } },
            onFinished = { runOnUiThread { stopAll("Universal payload completed.") } }
        )
        lightView = view
        busy = false
        running = true
        enterOpticalFullscreen()
        setContentView(view)
        status("Universal payload is streaming through light. Double-tap to stop.")
    }

    private fun requestOrStartTorch(factory: UniversalStreamFactory) {
        pendingTorchStart = true
        pendingFactory = factory
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            startTorch(factory)
        }
    }

    private fun startTorch(factory: UniversalStreamFactory) {
        val source = factory.create(40)
        runCatching {
            UniversalPayloadTorchEngine(
                context = this,
                source = source,
                updateRateHz = 40,
                modulationGain = (gainSeek.progress + 5) / 100f,
                onStatus = { message -> runOnUiThread { status(message) } },
                onFinished = { runOnUiThread { stopAll("Universal torch payload ended.") } }
            ).also {
                torchEngine = it
                busy = false
                running = true
                it.start()
            }
        }.onFailure { error -> fail("Torch payload failed: ${error.message}") }
    }

    private fun startUsb(factory: UniversalStreamFactory) {
        val source = factory.create(48_000)
        val engine = UniversalPayloadUsbEngine(
            context = this,
            source = source,
            onStatus = { message -> runOnUiThread { status(message) } },
            onProgress = { seconds, fraction -> runOnUiThread { updateProgress(seconds, fraction) } },
            onFinished = { runOnUiThread { stopAll("Universal USB payload ended.") } }
        )
        val target = engine.findTarget()
            ?: return fail("No USB bulk LED controller was detected.")
        if (!engine.hasPermission(target)) {
            pendingUsbEngine = engine
            pendingUsbTarget = target
            engine.requestPermission(target, usbPermissionIntent())
            status("Grant USB permission to begin the universal payload.")
            return
        }
        usbEngine = engine
        busy = false
        running = true
        engine.start(target)
    }

    private fun updateProgress(seconds: Double, fraction: Double) {
        val percentage = (fraction * 100.0).coerceIn(0.0, 100.0)
        progressText.text = "${formatTime(seconds)} · current pass ${"%.2f".format(percentage)}%"
    }

    private fun stopAll(message: String) {
        lightView?.stop()
        lightView = null
        torchEngine?.stop()
        torchEngine = null
        usbEngine?.stop()
        usbEngine = null
        pendingUsbEngine = null
        pendingUsbTarget = null
        pendingFactory = null
        running = false
        busy = false
        exitOpticalFullscreen()
        setContentView(controlView)
        startButton.text = "HASH + TRANSMIT UNIVERSAL PAYLOAD"
        progressText.text = "No universal payload running."
        status(message)
    }

    private fun saveManifest(current: UniversalPayloadManifest) {
        val fileName = "ULP3_${System.currentTimeMillis()}_manifest.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LightCode-Investigation-Lab")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        runCatching {
            val uri = contentResolver.insert(collection, values) ?: return@runCatching
            contentResolver.openOutputStream(uri)?.use { it.write(current.asText().toByteArray()) }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && pendingTorchStart) {
            pendingTorchStart = false
            val factory = pendingFactory
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && factory != null) {
                startTorch(factory)
            } else {
                fail("Camera permission was denied, so the flashlight cannot be controlled.")
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

    private fun displaySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return -1L
    }

    private fun formatBytes(value: Long): String {
        if (value < 0L) return "size will be measured while hashing"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var number = value.toDouble()
        var unit = 0
        while (number >= 1024.0 && unit < units.lastIndex) {
            number /= 1024.0
            unit++
        }
        return "%.2f %s".format(number, units[unit])
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0L)
        val hours = total / 3_600L
        val minutes = (total % 3_600L) / 60L
        val secs = total % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
        else "%d:%02d".format(minutes, secs)
    }

    private fun fail(message: String) {
        busy = false
        running = false
        startButton.text = "HASH + TRANSMIT UNIVERSAL PAYLOAD"
        status(message)
    }

    private fun status(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun section(text: String): TextView = label(text, 13f, 0xFF80CBC4.toInt(), true)

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
        adapter = ArrayAdapter(this@UniversalPayloadActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setBackgroundColor(0xFF202020.toInt())
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Android framework back callback")
    override fun onBackPressed() {
        if (running || busy) stopAll("Transmission stopped.") else super.onBackPressed()
    }

    override fun onDestroy() {
        lightView?.stop()
        torchEngine?.stop()
        usbEngine?.stop()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_FILE = 5201
        private const val REQUEST_CAMERA = 5202
        private const val ACTION_USB_PERMISSION = "com.vhanma.lightcode.investigation.UNIVERSAL_USB_PERMISSION"
    }
}

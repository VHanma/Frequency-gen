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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import kotlin.concurrent.thread

class UniversalPayloadEncoderActivity : Activity() {
    private lateinit var controlView: View
    private lateinit var urlInput: EditText
    private lateinit var textInput: EditText
    private lateinit var payloadLabel: TextView
    private lateinit var carrierSpinner: Spinner
    private lateinit var outputSpinner: Spinner
    private lateinit var colorSpinner: Spinner
    private lateinit var geometrySpinner: Spinner
    private lateinit var rowsSpinner: Spinner
    private lateinit var gainSeek: SeekBar
    private lateinit var gainLabel: TextView
    private lateinit var loopCheck: CheckBox
    private lateinit var reverseCheck: CheckBox
    private lateinit var enclosedCheck: CheckBox
    private lateinit var startButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView

    private var descriptor: UniversalPayloadDescriptor? = null
    private var pendingStream: UniversalCarrierSampleStream? = null
    private var pendingUsbTarget: StreamingUsbTarget? = null
    private var running = false
    private var busy = false

    private var screenEngine: StreamingLightView? = null
    private var torchEngine: StreamingTorchEngine? = null
    private var usbEngine: StreamingUsbEngine? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val stream = pendingStream
            val target = pendingUsbTarget
            if (granted && stream != null && target != null) {
                launchUsb(stream, target)
            } else {
                stream?.close()
                pendingStream = null
                pendingUsbTarget = null
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

        root.addView(label("UNIVERSAL PAYLOAD ENCODER", 28f, Color.WHITE, true))
        root.addView(label(
            "Download or choose anything, then stream it through light in CRC-protected blocks. File size is not loaded into RAM and has no fixed app ceiling.",
            14f,
            0xFFBDBDBD.toInt()
        ))

        root.addView(section("ONE-TAP URL DOWNLOAD"))
        urlInput = EditText(this).apply {
            hint = "Paste a direct https:// file link"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            setBackgroundColor(0xFF151515.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(urlInput, matchWrap())
        root.addView(actionButton("DOWNLOAD + LOAD PAYLOAD") { downloadAndLoad() }.apply {
            setBackgroundColor(0xFF1565C0.toInt())
        })

        root.addView(section("LOCAL PAYLOAD"))
        root.addView(actionButton("CHOOSE ANY FILE") { chooseLocalFile() })

        root.addView(section("TEXT PAYLOAD"))
        textInput = EditText(this).apply {
            hint = "Type any text to package as UTF-8"
            minLines = 4
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF777777.toInt())
            setBackgroundColor(0xFF151515.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(textInput, matchWrap())
        root.addView(actionButton("LOAD TYPED TEXT") { loadTypedText() })

        payloadLabel = label("No payload loaded.", 13f, 0xFFFFCC80.toInt(), true)
        root.addView(payloadLabel)

        root.addView(section("CARRIER"))
        carrierSpinner = spinner(UniversalCarrierMode.entries.map { it.displayName })
        root.addView(carrierSpinner)

        root.addView(section("LIGHT OUTPUT"))
        outputSpinner = spinner(listOf(
            "Scanline screen",
            "Whole-screen low-band",
            "Phone torch",
            "USB bulk LED controller"
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
            text = "Loop complete payload until Stop"
            setTextColor(Color.WHITE)
        }
        root.addView(loopCheck)

        reverseCheck = CheckBox(this).apply {
            text = "Reverse screen scan direction"
            setTextColor(Color.WHITE)
        }
        root.addView(reverseCheck)

        enclosedCheck = CheckBox(this).apply {
            text = "Light is aimed into a jar/sensor or enclosed receiver"
            setTextColor(Color.WHITE)
        }
        root.addView(enclosedCheck)

        startButton = actionButton("START UNIVERSAL LIGHT ENCODING") {
            if (running || busy) stopAll("Stopped by user.") else startEncoding()
        }.apply { setBackgroundColor(0xFF6A1B9A.toInt()) }
        root.addView(startButton)

        progressText = label("No transmission running.", 13f, 0xFFFFCC80.toInt())
        root.addView(progressText)
        statusText = label("Ready for a URL, local file, or typed text.", 13f, 0xFF80CBC4.toInt())
        root.addView(statusText)

        root.addView(label(
            "Each payload carries its filename, 64-bit length, SHA-256, block index, block length and block CRC32. A compatible receiver can validate and reconstruct the exact original bytes.",
            12f,
            0xFF8E8E8E.toInt()
        ))

        outputSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val screen = position <= 1
                colorSpinner.isEnabled = screen
                geometrySpinner.isEnabled = screen
                rowsSpinner.isEnabled = position == 0
                reverseCheck.isEnabled = position == 0
                if (position == 2) carrierSpinner.setSelection(UniversalCarrierMode.TORCH_OOK.ordinal)
            }
        }

        return scroll
    }

    private fun downloadAndLoad() {
        val raw = urlInput.text.toString().trim()
        if (!raw.startsWith("https://") && !raw.startsWith("http://")) {
            status("Paste a complete http:// or https:// direct file link.")
            return
        }
        busy = true
        startButton.text = "STOP"
        status("Downloading payload…")
        thread(name = "UniversalPayloadDownload") {
            runCatching { downloadToPublicPayloadFolder(raw) }
                .onSuccess { downloaded ->
                    inspectAndLoad(downloaded.uri, downloaded.name)
                }
                .onFailure { error -> runOnUiThread { fail("Download failed: ${error.message}") } }
        }
    }

    private data class DownloadedPayload(val uri: Uri, val name: String)

    private fun downloadToPublicPayloadFolder(rawUrl: String): DownloadedPayload {
        var connection = URL(rawUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 45_000
        connection.setRequestProperty("User-Agent", "LightCode-Investigation-Lab/1.0")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            error("Server returned HTTP ${connection.responseCode}.")
        }
        val name = resolveDownloadName(rawUrl, connection)
        val total = connection.contentLengthLong

        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, connection.contentType ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/LightCode-Investigation-Lab/Payloads")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Android could not create the download file.")
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    connection.inputStream.use { input ->
                        copyWithProgress(input, output, total, "Downloading")
                    }
                } ?: error("Android could not open the download destination.")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                DownloadedPayload(uri, name)
            } catch (error: Throwable) {
                contentResolver.delete(uri, null, null)
                throw error
            } finally {
                connection.disconnect()
            }
        } else {
            val folder = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LightCode-Investigation-Lab/Payloads"
            )
            folder.mkdirs()
            val file = uniqueFile(folder, name)
            try {
                FileOutputStream(file).use { output ->
                    connection.inputStream.use { input ->
                        copyWithProgress(input, output, total, "Downloading")
                    }
                }
                DownloadedPayload(Uri.fromFile(file), file.name)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        verb: String
    ) {
        val buffer = ByteArray(256 * 1024)
        var copied = 0L
        var lastUpdate = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            copied += read.toLong()
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 400L) {
                lastUpdate = now
                runOnUiThread {
                    status("$verb: ${formatBytes(copied)}${if (total > 0) " / ${formatBytes(total)}" else ""}")
                }
            }
        }
    }

    private fun resolveDownloadName(rawUrl: String, connection: HttpURLConnection): String {
        val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
        val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)
        val plain = Regex("filename=\"?([^\";]+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)
        val candidate = encoded?.let { URLDecoder.decode(it, "UTF-8") }
            ?: plain
            ?: Uri.parse(rawUrl).lastPathSegment
            ?: "download-${System.currentTimeMillis()}.bin"
        return candidate.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180).ifBlank {
            "download-${System.currentTimeMillis()}.bin"
        }
    }

    private fun uniqueFile(folder: File, requested: String): File {
        var file = File(folder, requested)
        if (!file.exists()) return file
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 1
        while (file.exists()) {
            file = File(folder, "$base ($index)$extension")
            index++
        }
        return file
    }

    private fun chooseLocalFile() {
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
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        inspectAndLoad(uri, displayName(uri))
    }

    private fun loadTypedText() {
        val text = textInput.text.toString()
        if (text.isEmpty()) {
            status("Type text first.")
            return
        }
        val file = File(cacheDir, "typed-payload-${System.currentTimeMillis()}.txt")
        file.writeText(text, Charsets.UTF_8)
        inspectAndLoad(Uri.fromFile(file), "typed-message.txt")
    }

    private fun inspectAndLoad(uri: Uri, name: String) {
        busy = true
        startButton.text = "STOP"
        runOnUiThread { status("Hashing payload for exact reconstruction…") }
        thread(name = "UniversalPayloadHash") {
            runCatching {
                UniversalPayloadInspector.inspect(this, uri, name) { bytes ->
                    runOnUiThread { status("Hashing: ${formatBytes(bytes)}") }
                }
            }.onSuccess { inspected ->
                runOnUiThread {
                    descriptor = inspected
                    busy = false
                    startButton.text = "START UNIVERSAL LIGHT ENCODING"
                    payloadLabel.text = buildString {
                        append(inspected.displayName).append('\n')
                        append(formatBytes(inspected.sizeBytes)).append('\n')
                        append("SHA-256: ").append(inspected.sha256Hex)
                    }
                    status("Payload loaded. Choose a carrier and output.")
                }
            }.onFailure { error -> runOnUiThread { fail("Payload inspection failed: ${error.message}") } }
        }
    }

    private fun startEncoding() {
        val payload = descriptor ?: return status("Download, choose, or type a payload first.")
        if (outputSpinner.selectedItemPosition <= 2 && !enclosedCheck.isChecked) {
            status("Confirm the light is aimed into an enclosed jar or instrument receiver.")
            return
        }
        val mode = UniversalCarrierMode.entries[carrierSpinner.selectedItemPosition]
        if (outputSpinner.selectedItemPosition == 2 && mode != UniversalCarrierMode.TORCH_OOK) {
            status("Phone torch output requires the Torch OOK carrier.")
            return
        }

        val stream = UniversalCarrierSampleStream(this, payload, mode, loopCheck.isChecked)
        pendingStream = stream
        startButton.text = "STOP"
        when (outputSpinner.selectedItemPosition) {
            0 -> startScreen(stream, false)
            1 -> startScreen(stream, true)
            2 -> requestCameraOrStartTorch(stream)
            3 -> startUsb(stream)
        }
    }

    private fun startScreen(stream: UniversalCarrierSampleStream, fullFrame: Boolean) {
        running = true
        enterFullscreen()
        val view = StreamingLightView(
            context = this,
            stream = stream,
            fullFrame = fullFrame,
            modulationGain = (gainSeek.progress + 5) / 100f,
            reverseRows = reverseCheck.isChecked,
            colorMode = selectedColor(),
            requestedRows = selectedRows(),
            geometry = selectedGeometry(),
            onProgress = ::updateProgress,
            onFinished = { runOnUiThread { stopAll("Payload transmission completed.") } }
        )
        screenEngine = view
        setContentView(view)
        status("Streaming ${descriptor?.displayName} through ${stream.mode.displayName}. Double-tap to stop.")
    }

    private fun requestCameraOrStartTorch(stream: UniversalCarrierSampleStream) {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            startTorch(stream)
        }
    }

    private fun startTorch(stream: UniversalCarrierSampleStream) {
        runCatching {
            StreamingTorchEngine(
                context = this,
                stream = stream,
                updateRateHz = 40,
                gain = (gainSeek.progress + 5) / 100f,
                onProgress = ::updateProgress,
                onStatus = { message -> runOnUiThread { status(message) } },
                onFinished = { runOnUiThread { stopAll("Torch payload transmission completed.") } }
            ).also {
                torchEngine = it
                running = true
                it.start()
            }
        }.onFailure { error -> fail("Torch stream failed: ${error.message}") }
    }

    private fun startUsb(stream: UniversalCarrierSampleStream) {
        val engine = StreamingUsbEngine(
            context = this,
            stream = stream,
            onProgress = ::updateProgress,
            onStatus = { message -> runOnUiThread { status(message) } },
            onFinished = { runOnUiThread { stopAll("USB payload transmission completed.") } }
        )
        val target = engine.findTarget() ?: return fail("No USB bulk LED controller was detected.")
        usbEngine = engine
        if (!engine.hasPermission(target)) {
            pendingUsbTarget = target
            engine.requestPermission(target, usbPermissionIntent())
        } else {
            launchUsb(stream, target)
        }
    }

    private fun launchUsb(stream: UniversalCarrierSampleStream, target: StreamingUsbTarget) {
        val engine = usbEngine ?: StreamingUsbEngine(
            this,
            stream,
            ::updateProgress,
            { message -> runOnUiThread { status(message) } },
            { runOnUiThread { stopAll("USB payload transmission completed.") } }
        ).also { usbEngine = it }
        pendingUsbTarget = null
        running = true
        runCatching { engine.start(target) }
            .onFailure { error -> fail("USB stream failed: ${error.message}") }
    }

    private fun updateProgress(bytes: Long, total: Long, passes: Long) {
        runOnUiThread {
            val percent = if (total > 0) (bytes * 100L / total).coerceIn(0L, 100L) else 0L
            progressText.text = "${formatBytes(bytes)} / ${formatBytes(total)} · $percent% · completed loops $passes"
        }
    }

    private fun stopAll(message: String) {
        screenEngine?.stop()
        screenEngine = null
        torchEngine?.stop()
        torchEngine = null
        usbEngine?.stop()
        usbEngine = null
        pendingStream?.close()
        pendingStream = null
        pendingUsbTarget = null
        running = false
        busy = false
        exitFullscreen()
        setContentView(controlView)
        startButton.text = "START UNIVERSAL LIGHT ENCODING"
        progressText.text = "No transmission running."
        status(message)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        val stream = pendingStream
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && stream != null) {
            startTorch(stream)
        } else {
            stream?.close()
            fail("Camera permission was denied, so the phone torch cannot be controlled.")
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

    private fun enterFullscreen() {
        window.attributes = window.attributes.apply {
            screenBrightness = 1f
            val fastest = display?.supportedModes?.maxByOrNull { it.refreshRate }
            if (fastest != null) preferredDisplayModeId = fastest.modeId
            preferredRefreshRate = fastest?.refreshRate ?: 0f
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    private fun exitFullscreen() {
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            preferredRefreshRate = 0f
            preferredDisplayModeId = 0
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "payload.bin"
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

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1_024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1_024.0 && unit < units.lastIndex) {
            value /= 1_024.0
            unit++
        }
        return "%.2f %s".format(Locale.US, value, units[unit])
    }

    private fun fail(message: String) {
        busy = false
        running = false
        pendingStream?.close()
        pendingStream = null
        startButton.text = "START UNIVERSAL LIGHT ENCODING"
        status(message)
    }

    private fun status(message: String) {
        if (::statusText.isInitialized) statusText.text = message
    }

    private fun section(text: String): TextView = label(text, 13f, 0xFF64B5F6.toInt(), true)

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
        adapter = ArrayAdapter(this@UniversalPayloadEncoderActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setBackgroundColor(0xFF202020.toInt())
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Android framework back callback")
    override fun onBackPressed() {
        if (running || busy) stopAll("Stopped.") else super.onBackPressed()
    }

    override fun onDestroy() {
        screenEngine?.stop()
        torchEngine?.stop()
        usbEngine?.stop()
        pendingStream?.close()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_FILE = 5201
        private const val REQUEST_CAMERA = 5202
        private const val ACTION_USB_PERMISSION = "com.vhanma.lightcode.investigation.UNIVERSAL_USB_PERMISSION"
    }
}

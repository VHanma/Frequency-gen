package com.vhanma.a432matrix

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private data class AppRow(val label: String, val packageName: String, val uid: Int) {
        override fun toString() = "$label\n$packageName"
    }

    private val projectionRequest = 44032
    private lateinit var appList: ListView
    private lateinit var statusBox: LinearLayout
    private lateinit var startButton: Button
    private var apps: List<AppRow> = emptyList()
    private val statusViews = HashMap<Int, TextView>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CaptureService.ACTION_UPDATE) return
            val uid = intent.getIntExtra("uid", -1)
            val label = intent.getStringExtra("label") ?: "Source"
            val hz = intent.getDoubleExtra("hz", 0.0)
            val confidence = intent.getDoubleExtra("confidence", 0.0)
            val state = intent.getStringExtra("state") ?: "Analyzing"
            val retune = intent.getBooleanExtra("retune", false)
            val view = statusViews.getOrPut(uid) {
                TextView(this@MainActivity).also {
                    it.setPadding(dp(14), dp(12), dp(14), dp(12))
                    it.setTextColor(Color.WHITE)
                    it.textSize = 15f
                    it.setBackgroundColor(Color.rgb(22, 26, 34))
                    statusBox.addView(it, LinearLayout.LayoutParams(-1, -2).apply {
                        bottomMargin = dp(8)
                    })
                }
            }
            val tuning = if (hz > 0.0) "A ≈ ${String.format(Locale.US, "%.2f", hz)} Hz" else "No stable tuning yet"
            val action = if (retune) "TARGET: 440 → 432  (-31.76665 cents)" else "BYPASS: leave untouched"
            view.text = "$label\n$tuning   •   ${String.format(Locale.US, "%.0f", confidence * 100)}% confidence\n$state\n$action"
            view.setTextColor(if (retune) Color.rgb(180, 255, 110) else Color.WHITE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 11, 16)
        buildUi()
        loadApps()
        val filter = IntentFilter(CaptureService.ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 991)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            setBackgroundColor(Color.rgb(9, 11, 16))
        }
        val title = TextView(this).apply {
            text = "A432 MATRIX"
            textSize = 28f
            setTextColor(Color.rgb(184, 255, 105))
            setTypeface(typeface, 1)
        }
        val subtitle = TextView(this).apply {
            text = "Selective tuning intelligence • Scout v0.1\nEach checked app is analyzed on its own audio stream. A440 is flagged for 432 conversion. A432, 528 material, speech, and uncertain signals are bypassed."
            textSize = 14f
            setTextColor(Color.rgb(200, 205, 215))
            setPadding(0, dp(5), 0, dp(12))
        }
        root.addView(title)
        root.addView(subtitle)

        val selectLabel = TextView(this).apply {
            text = "SELECT APPS TO WATCH INDEPENDENTLY"
            textSize = 12f
            setTextColor(Color.rgb(145, 150, 165))
            setPadding(0, 0, 0, dp(5))
        }
        root.addView(selectLabel)

        appList = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            dividerHeight = 1
            setBackgroundColor(Color.rgb(14, 17, 23))
        }
        root.addView(appList, LinearLayout.LayoutParams(-1, 0, 1.0f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(8))
        }
        startButton = Button(this).apply {
            text = "START SMART SCAN"
            setOnClickListener { requestProjection() }
        }
        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                statusViews.clear()
                statusBox.removeAllViews()
            }
        }
        actions.addView(startButton, LinearLayout.LayoutParams(0, dp(52), 2f).apply { marginEnd = dp(8) })
        actions.addView(stop, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(actions)

        val statusLabel = TextView(this).apply {
            text = "LIVE SOURCE MATRIX"
            textSize = 12f
            setTextColor(Color.rgb(145, 150, 165))
            setPadding(0, dp(2), 0, dp(5))
        }
        root.addView(statusLabel)
        val scroller = ScrollView(this)
        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(statusBox)
        root.addView(scroller, LinearLayout.LayoutParams(-1, dp(210)))
        setContentView(root)
    }

    private fun loadApps() {
        @Suppress("DEPRECATION")
        val installed = packageManager.getInstalledApplications(0)
        apps = installed.asSequence()
            .filter { it.packageName != packageName }
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { AppRow(packageManager.getApplicationLabel(it).toString(), it.packageName, it.uid) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
        appList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, apps)

        // Pre-check YouTube when present. Nothing else is assumed.
        apps.forEachIndexed { i, a ->
            if (a.packageName == "com.google.android.youtube") appList.setItemChecked(i, true)
        }
    }

    private fun selectedApps(): List<AppRow> {
        val checked = appList.checkedItemPositions
        return apps.filterIndexed { index, _ -> checked.get(index, false) }
    }

    private fun requestProjection() {
        val selected = selectedApps()
        if (selected.isEmpty()) {
            Toast.makeText(this, "Check at least one media app first", Toast.LENGTH_SHORT).show()
            return
        }
        // Android requires explicit MediaProjection consent for playback capture.
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), projectionRequest)
    }

    @Deprecated("Deprecated in Android framework; retained for minSdk 29 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != projectionRequest || resultCode != Activity.RESULT_OK || data == null) return
        val selected = selectedApps()
        statusViews.clear()
        statusBox.removeAllViews()
        val service = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_UIDS, selected.map { it.uid }.toIntArray())
            putExtra(CaptureService.EXTRA_LABELS, selected.map { it.label }.toTypedArray())
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_PROJECTION_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Throwable) {}
        super.onDestroy()
    }
}

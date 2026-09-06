package com.vaan.contactomega

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionStore(private val context: Context) {
    var sessionId: String = ""
        private set
    var dir: File? = null
        private set
    private var startMs: Long = 0L

    fun start(mode: String, config: Map<String, Any?> = emptyMap()): File {
        startMs = System.currentTimeMillis()
        sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startMs))
        val root = File(context.getExternalFilesDir(null), "OMEGA-Contact-Lab").apply { mkdirs() }
        val folder = File(root, "omega-$sessionId").apply { mkdirs() }
        dir = folder
        val meta = JSONObject().apply {
            put("sessionId", sessionId)
            put("startEpochMs", startMs)
            put("mode", mode)
            put("appVersion", "2.0.1")
            put("config", JSONObject(config))
        }
        File(folder, "session.json").writeText(meta.toString(2))
        event("SESSION_START", mapOf("mode" to mode))
        return folder
    }

    fun ensure(mode: String = "MANUAL"): File = dir ?: start(mode)
    fun elapsedMs(): Long = if (startMs == 0L) 0L else System.currentTimeMillis() - startMs

    @Synchronized fun event(type: String, fields: Map<String, Any?> = emptyMap()) {
        val folder = ensure()
        val o = JSONObject().apply {
            put("tMs", elapsedMs())
            put("epochMs", System.currentTimeMillis())
            put("type", type)
            fields.forEach { (k, v) -> put(k, v) }
        }
        File(folder, "events.jsonl").appendText(o.toString() + "\n")
    }

    @Synchronized fun transcript(text: String, partial: Boolean, confidence: Double?, sourceOverlap: Boolean) {
        val folder = ensure()
        val o = JSONObject().apply {
            put("tMs", elapsedMs())
            put("text", text)
            put("partial", partial)
            if (confidence != null) put("confidence", confidence)
            put("sourceOverlap", sourceOverlap)
        }
        File(folder, "transcripts.jsonl").appendText(o.toString() + "\n")
    }

    @Synchronized fun sensorCsv(header: String, row: String) {
        val f = File(ensure(), "sensors.csv")
        if (!f.exists()) f.writeText(header + "\n")
        f.appendText(row + "\n")
    }

    fun root(): File = File(context.getExternalFilesDir(null), "OMEGA-Contact-Lab").apply { mkdirs() }
    fun recentSessions(): List<File> = root().listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

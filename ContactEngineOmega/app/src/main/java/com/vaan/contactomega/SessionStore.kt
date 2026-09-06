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
    private var lastTxMs: Long = -1L
    private val channelCounts = linkedMapOf<String, Int>()
    private val delayBins = linkedMapOf<String, Int>()
    private val prefs = context.getSharedPreferences("omega_r3_profile", Context.MODE_PRIVATE)

    fun start(mode: String, config: Map<String, Any?> = emptyMap()): File {
        finalizeCurrent("NEXT_SESSION")
        startMs = System.currentTimeMillis()
        lastTxMs = -1L
        channelCounts.clear()
        delayBins.clear()
        sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startMs))
        val root = File(context.getExternalFilesDir(null), "OMEGA-Contact-Lab").apply { mkdirs() }
        val folder = File(root, "omega-$sessionId").apply { mkdirs() }
        dir = folder
        val meta = JSONObject().apply {
            put("sessionId", sessionId)
            put("startEpochMs", startMs)
            put("mode", mode)
            put("appVersion", "2.1.0-r3")
            put("config", JSONObject(config))
            put("r3AdaptiveProfile", persistentProfileJson())
        }
        File(folder, "session.json").writeText(meta.toString(2))
        event("SESSION_START", mapOf("mode" to mode))
        return folder
    }

    fun ensure(mode: String = "MANUAL"): File = dir ?: start(mode)
    fun elapsedMs(): Long = if (startMs == 0L) 0L else System.currentTimeMillis() - startMs

    @Synchronized fun event(type: String, fields: Map<String, Any?> = emptyMap()) {
        val folder = ensure()
        val now = elapsedMs()
        if (type == "BEACON_TX_START") lastTxMs = now
        val channel = classify(type)
        if (channel != null) recordChannel(channel, now)
        val o = JSONObject().apply {
            put("tMs", now)
            put("epochMs", System.currentTimeMillis())
            put("type", type)
            if (lastTxMs >= 0L && type != "BEACON_TX_START") put("sinceLastTxMs", (now - lastTxMs).coerceAtLeast(0L))
            if (channel != null) put("r3Channel", channel)
            fields.forEach { (k, v) -> put(k, v) }
        }
        File(folder, "events.jsonl").appendText(o.toString() + "\n")
        if (channel != null || type == "MULTIMODAL_COINCIDENCE") writeFingerprint()
    }

    @Synchronized fun transcript(text: String, partial: Boolean, confidence: Double?, sourceOverlap: Boolean) {
        val folder = ensure()
        val now = elapsedMs()
        if (!partial) recordChannel(if (sourceOverlap) "EVP_OVERLAP" else "EVP_CLEAN", now)
        val o = JSONObject().apply {
            put("tMs", now)
            put("text", text)
            put("partial", partial)
            if (confidence != null) put("confidence", confidence)
            put("sourceOverlap", sourceOverlap)
            if (lastTxMs >= 0L) put("sinceLastTxMs", (now - lastTxMs).coerceAtLeast(0L))
        }
        File(folder, "transcripts.jsonl").appendText(o.toString() + "\n")
        if (!partial) writeFingerprint()
    }

    @Synchronized fun sensorCsv(header: String, row: String) {
        val f = File(ensure(), "sensors.csv")
        if (!f.exists()) f.writeText(header + "\n")
        f.appendText(row + "\n")
    }

    fun adaptiveChallenge(): String {
        val combined = linkedMapOf<String, Int>()
        listOf("EVP_CLEAN", "FIELD", "VISUAL", "RNG", "COINCIDENCE").forEach { ch ->
            combined[ch] = channelCounts[ch] ?: prefs.getInt("channel_$ch", 0)
        }
        return when (combined.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key) {
            "EVP_CLEAN" -> "AUDIO CHANNEL NOTED. RESPOND TWICE DURING THE SILENT WINDOW."
            "FIELD" -> "FIELD CHANNEL NOTED. REPEAT TWO MATCHING FIELD CHANGES."
            "VISUAL" -> "OPTICAL CHANNEL NOTED. REPEAT TWO MATCHING LIGHT CHANGES."
            "RNG" -> "ENTROPY CHANNEL NOTED. REPEAT THE SAME BIAS PATTERN."
            "COINCIDENCE" -> "MULTIPLE CHANNELS NOTED. REPEAT THE SAME CHANNEL COMBINATION."
            else -> "IDENTIFY ONE CHANNEL. REPEAT THE SAME RESPONSE TWICE."
        }
    }

    fun fingerprintSummary(): String {
        if (channelCounts.isEmpty() && delayBins.isEmpty()) return "No response fingerprint yet"
        val channels = channelCounts.entries.sortedByDescending { it.value }.take(5).joinToString(" · ") { "${it.key}×${it.value}" }
        val delays = delayBins.entries.sortedByDescending { it.value }.take(5).joinToString(" · ") { "${it.key}×${it.value}" }
        return listOf(channels, delays).filter { it.isNotBlank() }.joinToString(" | ")
    }

    @Synchronized fun finalizeCurrent(reason: String = "END") {
        val folder = dir ?: return
        writeFingerprint()
        val o = JSONObject().apply {
            put("reason", reason)
            put("endEpochMs", System.currentTimeMillis())
            put("durationMs", elapsedMs())
            put("summary", fingerprintSummary())
        }
        File(folder, "r3-session-end.json").writeText(o.toString(2))
        persistProfile()
    }

    private fun recordChannel(channel: String, now: Long) {
        channelCounts[channel] = (channelCounts[channel] ?: 0) + 1
        if (lastTxMs >= 0L && now >= lastTxMs) {
            val delay = now - lastTxMs
            if (delay <= 180_000L) {
                val bucketStart = (delay / 2000L) * 2L
                val key = "$channel@${bucketStart}-${bucketStart + 2}s"
                delayBins[key] = (delayBins[key] ?: 0) + 1
            }
        }
    }

    private fun classify(type: String): String? = when {
        type == "MULTIMODAL_COINCIDENCE" -> "COINCIDENCE"
        type.startsWith("VISUAL_") || type.contains("FRAME") -> "VISUAL"
        type == "ENV_WORD" || type.startsWith("FIELD_") -> "FIELD"
        type == "ENTROPY_TRIAL" -> "RNG"
        else -> null
    }

    private fun writeFingerprint() {
        val folder = dir ?: return
        val o = JSONObject().apply {
            val c = JSONObject(); channelCounts.forEach { (k, v) -> c.put(k, v) }; put("channels", c)
            val d = JSONObject(); delayBins.forEach { (k, v) -> d.put(k, v) }; put("postTxDelayBins", d)
            put("adaptiveNextChallenge", adaptiveChallenge())
            put("summary", fingerprintSummary())
        }
        File(folder, "r3-response-fingerprint.json").writeText(o.toString(2))
    }

    private fun persistProfile() {
        val e = prefs.edit()
        channelCounts.forEach { (k, v) -> e.putInt("channel_$k", prefs.getInt("channel_$k", 0) + v) }
        e.apply()
    }

    private fun persistentProfileJson(): JSONObject {
        val o = JSONObject()
        listOf("EVP_CLEAN", "FIELD", "VISUAL", "RNG", "COINCIDENCE").forEach { ch -> o.put(ch, prefs.getInt("channel_$ch", 0)) }
        return o
    }

    fun root(): File = File(context.getExternalFilesDir(null), "OMEGA-Contact-Lab").apply { mkdirs() }
    fun recentSessions(): List<File> = root().listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

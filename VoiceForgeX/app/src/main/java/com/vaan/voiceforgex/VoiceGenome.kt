package com.vaan.voiceforgex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

data class VoiceQuality(
    val score: Int,
    val seconds: Float,
    val rms: Float,
    val silencePercent: Int,
    val clippingPercent: Int,
    val dynamicRange: Float,
) {
    fun summary(): String = "Q$score • ${"%.1f".format(seconds)}s • silence $silencePercent% • clip $clippingPercent%"
}

data class GenomeSample(
    val id: String,
    val path: String,
    val source: String,
    val quality: VoiceQuality,
    val createdAt: Long,
)

object VoiceGenome {
    private lateinit var app: Context
    private const val PREF = "voiceforge_genomes"

    fun init(context: Context) { app = context.applicationContext }
    private fun prefs() = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun key(profileId: String) = "g:$profileId"

    fun quality(file: File): VoiceQuality {
        val w = WavUtils.readPcm16Mono(file)
        val s = w.samples
        var sumSq = 0.0
        var silence = 0
        var clipping = 0
        var peak = 0f
        var lowEnergy = 1f
        for (v in s) {
            val a = abs(v)
            sumSq += (v * v).toDouble()
            if (a < 0.008f) silence++
            if (a > 0.985f) clipping++
            if (a > peak) peak = a
            if (a > 0.01f && a < lowEnergy) lowEnergy = a
        }
        val rms = sqrt(sumSq / s.size).toFloat()
        val silencePct = (silence * 100 / s.size).coerceIn(0, 100)
        val clipPct = (clipping * 100 / s.size).coerceIn(0, 100)
        val seconds = s.size.toFloat() / w.sampleRate
        val dynamic = if (lowEnergy < 1f) peak / lowEnergy.coerceAtLeast(0.001f) else peak
        var score = 100
        if (seconds < 3f) score -= 35 else if (seconds < 6f) score -= 15
        if (seconds > 18f) score -= 5
        score -= (silencePct * 0.35f).toInt()
        score -= (clipPct * 3).coerceAtMost(35)
        if (rms < 0.015f) score -= 25 else if (rms < 0.035f) score -= 10
        if (peak < 0.08f) score -= 12
        return VoiceQuality(score.coerceIn(0, 100), seconds, rms, silencePct, clipPct, dynamic)
    }

    fun ensureLegacy(profile: CloneProfile) {
        if (samples(profile.id).isNotEmpty()) return
        val f = File(profile.wavPath)
        if (!f.exists()) return
        addSample(profile.id, f, "original", copy = false)
    }

    fun addSample(profileId: String, source: File, sourceLabel: String, copy: Boolean = true): GenomeSample {
        val dst = if (copy) {
            val dir = File(app.filesDir, "genomes/$profileId").apply { mkdirs() }
            File(dir, "${System.currentTimeMillis()}_${UUID.randomUUID()}.wav").also { source.copyTo(it, overwrite = true) }
        } else source
        val q = quality(dst)
        val item = GenomeSample(UUID.randomUUID().toString(), dst.absolutePath, sourceLabel, q, System.currentTimeMillis())
        val list = samples(profileId).toMutableList().apply { add(item) }.sortedByDescending { it.quality.score }
        save(profileId, list)
        return item
    }

    fun samples(profileId: String): List<GenomeSample> {
        val arr = runCatching { JSONArray(prefs().getString(key(profileId), "[]")) }.getOrElse { JSONArray() }
        val out = mutableListOf<GenomeSample>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = o.optString("path")
            if (!File(p).exists()) continue
            val q = VoiceQuality(
                o.optInt("score", 0), o.optDouble("seconds", 0.0).toFloat(), o.optDouble("rms", 0.0).toFloat(),
                o.optInt("silence", 0), o.optInt("clip", 0), o.optDouble("dynamic", 0.0).toFloat()
            )
            out += GenomeSample(o.optString("id"), p, o.optString("source"), q, o.optLong("createdAt"))
        }
        return out.sortedByDescending { it.quality.score }
    }

    fun bestReference(profile: CloneProfile): File {
        ensureLegacy(profile)
        return samples(profile.id).firstOrNull()?.let { File(it.path) } ?: File(profile.wavPath)
    }

    fun summary(profile: CloneProfile): String {
        ensureLegacy(profile)
        val s = samples(profile.id)
        val best = s.firstOrNull()?.quality
        return if (best == null) "1 reference" else "${s.size} sample${if (s.size == 1) "" else "s"} • ${best.summary()}"
    }

    fun delete(profileId: String) {
        samples(profileId).forEach { sample ->
            val f = File(sample.path)
            if (f.path.contains("/genomes/")) runCatching { f.delete() }
        }
        File(app.filesDir, "genomes/$profileId").deleteRecursively()
        prefs().edit().remove(key(profileId)).apply()
    }

    private fun save(profileId: String, list: List<GenomeSample>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().apply {
                put("id", s.id); put("path", s.path); put("source", s.source); put("createdAt", s.createdAt)
                put("score", s.quality.score); put("seconds", s.quality.seconds); put("rms", s.quality.rms)
                put("silence", s.quality.silencePercent); put("clip", s.quality.clippingPercent); put("dynamic", s.quality.dynamicRange)
            })
        }
        prefs().edit().putString(key(profileId), a.toString()).apply()
    }
}

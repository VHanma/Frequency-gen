package com.vaan.voiceforgex

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object CloneRepository {
    private lateinit var app: Context
    private const val PREF = "voiceforge_clones"
    private const val KEY = "profiles"
    private const val SELECTED = "selected"

    fun init(context: Context) {
        app = context.applicationContext
        VoiceGenome.init(app)
    }

    private fun prefs() = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(): List<CloneProfile> {
        val raw = prefs().getString(KEY, "[]") ?: "[]"
        val a = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val p = CloneProfile(o.optString("id"), o.optString("name"), o.optString("wavPath"), o.optLong("createdAt"))
                if (p.id.isNotBlank() && File(p.wavPath).exists()) add(p)
            }
        }.sortedByDescending { it.createdAt }
    }

    fun addFromWav(name: String, source: File): CloneProfile {
        val dir = File(app.filesDir, "voices").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val dst = File(dir, "$id.wav")
        source.copyTo(dst, overwrite = true)
        val p = CloneProfile(id, name.ifBlank { "Clone ${all().size + 1}" }, dst.absolutePath, System.currentTimeMillis())
        save(all() + p)
        VoiceGenome.addSample(id, dst, "primary", copy = false)
        select(p.id)
        return p
    }

    fun addGenomeSample(profileId: String, source: File, sourceLabel: String): GenomeSample {
        require(all().any { it.id == profileId }) { "Clone no longer exists" }
        return VoiceGenome.addSample(profileId, source, sourceLabel, copy = true)
    }

    fun delete(id: String) {
        all().firstOrNull { it.id == id }?.let { runCatching { File(it.wavPath).delete() } }
        VoiceGenome.delete(id)
        val remaining = all().filterNot { it.id == id }
        save(remaining)
        if (selectedId() == id) select(remaining.firstOrNull()?.id)
    }

    fun selectedId(): String? = prefs().getString(SELECTED, null)
    fun selected(): CloneProfile? = all().firstOrNull { it.id == selectedId() } ?: all().firstOrNull()
    fun select(id: String?) { prefs().edit().putString(SELECTED, id).apply() }
    fun byVoiceName(voiceName: String?): CloneProfile? {
        val id = voiceName?.removePrefix("vfx:") ?: return selected()
        return all().firstOrNull { it.id == id } ?: selected()
    }

    private fun save(list: List<CloneProfile>) {
        val a = JSONArray()
        list.forEach { p ->
            a.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("wavPath", p.wavPath); put("createdAt", p.createdAt)
            })
        }
        prefs().edit().putString(KEY, a.toString()).apply()
    }
}

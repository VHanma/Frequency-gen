package com.vaan.voiceforgex

import android.content.Context
import java.io.File
import kotlin.math.abs

object VoiceFusion {
    fun create(context: Context, a: CloneProfile, b: CloneProfile, ratioA: Float, name: String): CloneProfile {
        val wa = WavUtils.readPcm16Mono(VoiceGenome.bestReference(a))
        val wb = WavUtils.readPcm16Mono(VoiceGenome.bestReference(b))
        val rate = 16000
        val sa = resample(wa.samples, wa.sampleRate, rate)
        val sb = resample(wb.samples, wb.sampleRate, rate)
        val max = minOf(rate * 15, maxOf(sa.size, sb.size))
        val ra = ratioA.coerceIn(0f, 1f)
        val rb = 1f - ra
        val out = FloatArray(max)
        for (i in 0 until max) {
            val va = if (sa.isNotEmpty()) sa[i % sa.size] else 0f
            val vb = if (sb.isNotEmpty()) sb[i % sb.size] else 0f
            out[i] = (va * ra + vb * rb).coerceIn(-0.98f, 0.98f)
        }
        val peak = out.maxOfOrNull { abs(it) } ?: 0f
        if (peak > 0.001f) {
            val g = (0.88f / peak).coerceAtMost(2.5f)
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-0.98f, 0.98f)
        }
        val tmp = File(context.cacheDir, "fusion_${System.currentTimeMillis()}.wav")
        WavUtils.writeFloatPcm16Wav(out, tmp, rate)
        val p = CloneRepository.addFromWav(name.ifBlank { "${a.name} × ${b.name}" }, tmp)
        VoiceGenome.addSample(p.id, File(p.wavPath), "fusion ${a.name} ${(ra * 100).toInt()}% + ${b.name} ${(rb * 100).toInt()}%", copy = false)
        tmp.delete()
        return p
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val n = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        val scale = from.toDouble() / to
        for (i in out.indices) {
            val pos = i * scale
            val p0 = pos.toInt().coerceIn(0, input.lastIndex)
            val p1 = (p0 + 1).coerceAtMost(input.lastIndex)
            val f = (pos - p0).toFloat()
            out[i] = input[p0] * (1f - f) + input[p1] * f
        }
        return out
    }
}

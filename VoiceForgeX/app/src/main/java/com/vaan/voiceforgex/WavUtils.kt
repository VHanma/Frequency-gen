package com.vaan.voiceforgex

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtils {
    data class Wav(val samples: FloatArray, val sampleRate: Int)

    fun writePcm16Wav(raw: File, wav: File, sampleRate: Int, channels: Int = 1) {
        val dataLen = raw.length()
        FileOutputStream(wav).use { out ->
            val totalLen = dataLen + 36
            val byteRate = sampleRate * channels * 2
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(totalLen.toInt()); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort())
            h.putInt(sampleRate); h.putInt(byteRate); h.putShort((channels * 2).toShort()); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(dataLen.toInt()); out.write(h.array())
            FileInputStream(raw).use { it.copyTo(out) }
        }
    }

    fun readPcm16Mono(file: File): Wav {
        val bytes = file.readBytes()
        require(bytes.size >= 44 && String(bytes, 0, 4) == "RIFF") { "Only PCM WAV is supported for clone references" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sampleRate = 16000
        var channels = 1
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val size = bb.getInt(p + 4)
            if (id == "fmt " && size >= 16) {
                channels = bb.getShort(p + 10).toInt(); sampleRate = bb.getInt(p + 12); bits = bb.getShort(p + 22).toInt()
            } else if (id == "data") { dataOffset = p + 8; dataSize = minOf(size, bytes.size - dataOffset); break }
            p += 8 + size + (size and 1)
        }
        require(dataOffset >= 0 && bits == 16) { "Reference must be 16-bit PCM WAV" }
        val frames = dataSize / 2 / channels
        val out = FloatArray(frames)
        var q = dataOffset
        for (i in 0 until frames) {
            var sum = 0f
            repeat(channels) { sum += bb.getShort(q).toFloat() / 32768f; q += 2 }
            out[i] = (sum / channels).coerceIn(-1f, 1f)
        }
        return Wav(out, sampleRate)
    }

    fun floatToPcm16(samples: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { s -> bb.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        return bb.array()
    }
}

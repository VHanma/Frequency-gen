package com.vaan.contactomega

import java.io.File
import java.io.RandomAccessFile

class WavWriter(private val file: File, private val sampleRate: Int, private val channels: Int = 1) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    init { writeHeader(0) }

    @Synchronized fun write(samples: ShortArray, count: Int) {
        val bytes = ByteArray(count * 2)
        var j = 0
        for (i in 0 until count) {
            val v = samples[i].toInt()
            bytes[j++] = (v and 0xff).toByte()
            bytes[j++] = ((v ushr 8) and 0xff).toByte()
        }
        raf.seek(44 + dataBytes)
        raf.write(bytes)
        dataBytes += bytes.size
    }

    @Synchronized fun close() {
        raf.seek(0)
        writeHeader(dataBytes)
        raf.close()
    }

    private fun writeHeader(dataLen: Long) {
        val byteRate = sampleRate * channels * 2
        val total = dataLen + 36
        fun str(s: String) = raf.write(s.toByteArray(Charsets.US_ASCII))
        fun le16(v: Int) { raf.write(v and 0xff); raf.write((v ushr 8) and 0xff) }
        fun le32(v: Long) { raf.write((v and 0xff).toInt()); raf.write(((v ushr 8) and 0xff).toInt()); raf.write(((v ushr 16) and 0xff).toInt()); raf.write(((v ushr 24) and 0xff).toInt()) }
        str("RIFF"); le32(total); str("WAVEfmt "); le32(16); le16(1); le16(channels); le32(sampleRate.toLong()); le32(byteRate.toLong()); le16(channels * 2); le16(16); str("data"); le32(dataLen)
    }
}

package com.vaan.contactomega

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object SignalMath {
    fun spectrum32(samples: ShortArray, nRead: Int): FloatArray {
        val n = 1024
        if (nRead < n) return FloatArray(32)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val offset = nRead - n
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            re[i] = samples[offset + i] / 32768.0 * w
        }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenR = cos(ang); val wlenI = sin(ang)
            var i = 0
            while (i < n) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val uR = re[i+k]; val uI = im[i+k]
                    val vR = re[i+k+len/2] * wr - im[i+k+len/2] * wi
                    val vI = re[i+k+len/2] * wi + im[i+k+len/2] * wr
                    re[i+k] = uR + vR; im[i+k] = uI + vI
                    re[i+k+len/2] = uR - vR; im[i+k+len/2] = uI - vI
                    val nwr = wr*wlenR - wi*wlenI; wi = wr*wlenI + wi*wlenR; wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
        val out = FloatArray(32)
        for (b in 0 until 32) {
            val start = 1 + b * 8
            var e = 0.0
            for (k in start until start + 8) e += sqrt(re[k]*re[k] + im[k]*im[k])
            out[b] = (e / 8.0).toFloat().coerceAtMost(1f)
        }
        return out
    }
}

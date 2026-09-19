package com.vaan.absoluterandom

import android.os.SystemClock
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/** Cryptographically strong, with exact-uniform mapping to any positive Long-sized pool. */
class EntropyEngine {
    private val rng: SecureRandom = try { SecureRandom.getInstanceStrong() } catch (_: Exception) { SecureRandom() }
    private val counter = AtomicLong()

    init {
        ByteArray(64).also {
            rng.nextBytes(it)
            rng.setSeed(MessageDigest.getInstance("SHA-512").digest(it))
        }
    }

    fun pick(bound: Long, tapNanos: Long, poolFingerprint: String): Long {
        require(bound > 0)
        val fresh = ByteArray(64).also(rng::nextBytes)
        val timing = ByteBuffer.allocate(40)
            .putLong(System.nanoTime())
            .putLong(SystemClock.elapsedRealtimeNanos())
            .putLong(tapNanos)
            .putLong(counter.incrementAndGet())
            .putLong(Runtime.getRuntime().freeMemory())
            .array()
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(fresh)
        digest.update(timing)
        digest.update(poolFingerprint.toByteArray(StandardCharsets.UTF_8))
        rng.setSeed(digest.digest())
        return unbiased(bound)
    }

    private fun unbiased(bound: Long): Long {
        if (bound == 1L) return 0L
        val modulus = BigInteger.valueOf(bound)
        val domain = BigInteger.ONE.shiftLeft(63)
        val limit = domain.subtract(domain.mod(modulus))
        val bytes = ByteArray(8)
        while (true) {
            rng.nextBytes(bytes)
            bytes[0] = (bytes[0].toInt() and 0x7F).toByte()
            val x = BigInteger(1, bytes)
            if (x < limit) return x.mod(modulus).toLong()
        }
    }
}

package com.vaan.absoluterandom

import android.os.SystemClock
import org.json.JSONObject
import java.io.FileInputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Rival 3 HyperEntropy engine.
 *
 * Every draw is conditioned from fresh Android CSPRNG output, direct Linux kernel entropy,
 * high-resolution scheduler/touch timing and, when available, a separately fetched ANU
 * quantum-random buffer. SHA-512 conditions the sources and HMAC-SHA-512 expands the
 * conditioned state. Final pool mapping uses rejection sampling, so there is no modulo bias.
 *
 * Extra sources are diversification only. The engine never assumes they are independent or
 * credits them with a fixed number of entropy bits. If QRNG/network access fails, local
 * cryptographic randomness continues normally.
 */
class EntropyEngine {
    private val rng: SecureRandom = try {
        SecureRandom.getInstanceStrong()
    } catch (_: Exception) {
        SecureRandom()
    }

    private val drawCounter = AtomicLong()
    private val quantumRefreshing = AtomicBoolean(false)
    private val quantumLock = Any()
    private var quantumBuffer = ByteArray(0)
    private var quantumOffset = 0

    @Volatile private var lastQuantumSuccessMs = 0L
    @Volatile private var lastQuantumError: String? = null
    @Volatile private var lastSourceSummary = "LOCAL MAX"

    private val qrngExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "R3-Quantum-Entropy").apply { isDaemon = true }
    }

    init {
        // Force the provider to initialize before the first user draw.
        val boot = ByteArray(64)
        rng.nextBytes(boot)
        rng.setSeed(MessageDigest.getInstance("SHA-512").digest(boot))
        refreshQuantumAsync()
    }

    fun pick(bound: Long, tapNanos: Long, poolFingerprint: String): Long {
        require(bound > 0) { "bound must be positive" }

        val secure = ByteArray(64).also(rng::nextBytes)
        val kernel = readKernelEntropy(64)
        val jitter = collectSchedulerJitter()
        val quantum = takeQuantumEntropy(64)
        val counter = drawCounter.incrementAndGet()

        val timing = ByteBuffer.allocate(72)
            .putLong(System.nanoTime())
            .putLong(SystemClock.elapsedRealtimeNanos())
            .putLong(SystemClock.uptimeMillis())
            .putLong(tapNanos)
            .putLong(counter)
            .putLong(Runtime.getRuntime().freeMemory())
            .putLong(Runtime.getRuntime().totalMemory())
            .putLong(Thread.currentThread().id)
            .putLong(System.identityHashCode(this).toLong())
            .array()

        val digest = MessageDigest.getInstance("SHA-512")
        digest.update("ABSOLUTE-RANDOM-R3-HYPERENTROPY-v1".toByteArray(StandardCharsets.UTF_8))
        digest.update(intPrefix(secure.size)); digest.update(secure)
        digest.update(intPrefix(kernel.size)); digest.update(kernel)
        digest.update(intPrefix(jitter.size)); digest.update(jitter)
        digest.update(intPrefix(quantum.size)); digest.update(quantum)
        digest.update(timing)
        digest.update(poolFingerprint.toByteArray(StandardCharsets.UTF_8))
        val master = digest.digest()

        // Supplement the Android provider too. HMAC mapping below does not depend on this reseed.
        rng.setSeed(master)
        lastSourceSummary = if (quantum.isNotEmpty()) {
            "CSPRNG + KERNEL + JITTER + QUANTUM"
        } else {
            "CSPRNG + KERNEL + JITTER"
        }

        return unbiased(bound, master, counter)
    }

    /** Human-readable status for a future diagnostics screen. */
    fun sourceSummary(): String = lastSourceSummary

    fun quantumReady(): Boolean = synchronized(quantumLock) {
        quantumBuffer.size - quantumOffset >= 64
    }

    fun lastQuantumSuccess(): Long = lastQuantumSuccessMs
    fun lastQuantumFailure(): String? = lastQuantumError

    private fun unbiased(bound: Long, master: ByteArray, drawId: Long): Long {
        if (bound == 1L) return 0L

        val modulus = BigInteger.valueOf(bound)
        val domain = BigInteger.ONE.shiftLeft(63)
        val limit = domain.subtract(domain.mod(modulus))
        var blockCounter = 0L

        while (true) {
            val message = ByteBuffer.allocate(32)
                .putLong(drawId)
                .putLong(blockCounter++)
                .putLong(System.nanoTime())
                .putLong(bound)
                .array()
            val block = hmacSha512(master, message)

            var offset = 0
            while (offset + 8 <= block.size) {
                val candidate = block.copyOfRange(offset, offset + 8)
                candidate[0] = (candidate[0].toInt() and 0x7F).toByte()
                val x = BigInteger(1, candidate)
                if (x < limit) return x.mod(modulus).toLong()
                offset += 8
            }
        }
    }

    private fun hmacSha512(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(message)
    }

    private fun readKernelEntropy(size: Int): ByteArray {
        return try {
            FileInputStream("/dev/urandom").use { input ->
                val out = ByteArray(size)
                var offset = 0
                while (offset < out.size) {
                    val n = input.read(out, offset, out.size - offset)
                    if (n <= 0) break
                    offset += n
                }
                if (offset == out.size) out else out.copyOf(offset)
            }
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun collectSchedulerJitter(): ByteArray {
        val out = ByteBuffer.allocate(32 * 8)
        var last = System.nanoTime()
        var state = last xor SystemClock.elapsedRealtimeNanos()
        repeat(32) { i ->
            if ((i and 3) == 0) Thread.yield()
            val now = System.nanoTime()
            val delta = now - last
            state = java.lang.Long.rotateLeft(state xor delta xor (i.toLong() shl 32), 17) + now
            out.putLong(delta xor state)
            last = now
        }
        return out.array()
    }

    private fun intPrefix(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private fun takeQuantumEntropy(maxBytes: Int): ByteArray {
        var out = ByteArray(0)
        var shouldRefresh = false
        synchronized(quantumLock) {
            val remaining = quantumBuffer.size - quantumOffset
            if (remaining > 0) {
                val count = minOf(maxBytes, remaining)
                out = quantumBuffer.copyOfRange(quantumOffset, quantumOffset + count)
                quantumOffset += count
            }
            if (quantumBuffer.size - quantumOffset < 128) shouldRefresh = true
        }
        if (shouldRefresh) refreshQuantumAsync()
        return out
    }

    private fun refreshQuantumAsync() {
        if (!quantumRefreshing.compareAndSet(false, true)) return
        qrngExecutor.execute {
            try {
                val fresh = fetchAnuQuantumBytes()
                if (fresh.isNotEmpty()) {
                    synchronized(quantumLock) {
                        val remaining = if (quantumOffset < quantumBuffer.size) {
                            quantumBuffer.copyOfRange(quantumOffset, quantumBuffer.size)
                        } else {
                            ByteArray(0)
                        }
                        quantumBuffer = remaining + fresh
                        quantumOffset = 0
                    }
                    lastQuantumSuccessMs = System.currentTimeMillis()
                    lastQuantumError = null
                }
            } catch (e: Exception) {
                lastQuantumError = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            } finally {
                quantumRefreshing.set(false)
            }
        }
    }

    private fun fetchAnuQuantumBytes(): ByteArray {
        val connection = URL(
            "https://qrng.anu.edu.au/API/jsonI.php?length=1024&type=uint8"
        ).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "AbsoluteRandomTarot-R3/1.0")

        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("QRNG HTTP $code")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val data = json.optJSONArray("data") ?: throw IllegalStateException("QRNG data missing")
            if (data.length() <= 0) throw IllegalStateException("QRNG data empty")

            val out = ByteArray(data.length())
            for (i in 0 until data.length()) out[i] = (data.getInt(i) and 0xFF).toByte()
            out
        } finally {
            connection.disconnect()
        }
    }
}

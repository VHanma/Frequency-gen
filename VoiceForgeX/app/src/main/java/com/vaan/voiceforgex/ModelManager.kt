package com.vaan.voiceforgex

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {
    private const val ARCHIVE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2"
    private const val HF_BASE = "https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main/"

    // Conservative lower bounds. A zero-byte/truncated/HTML/error-response model can never pass these.
    private val requiredMinBytes = linkedMapOf(
        "lm_flow.int8.onnx" to 8_000_000L,
        "lm_main.int8.onnx" to 70_000_000L,
        "encoder.onnx" to 68_000_000L,
        "decoder.int8.onnx" to 20_000_000L,
        "text_conditioner.onnx" to 15_000_000L,
        "vocab.json" to 50_000L,
        "token_scores.json" to 100_000L,
    )

    fun dir(context: Context) = File(context.filesDir, "models/pocket")

    /** Fast disk sanity check. Deep validation actually parses every ONNX model. */
    fun isReady(context: Context): Boolean = filesSane(dir(context))

    fun filesSane(d: File): Boolean = requiredMinBytes.all { (name, min) ->
        File(d, name).let { it.isFile && it.length() >= min }
    }

    /** Forces sherpa-onnx to parse/open the model set. This catches protobuf corruption. */
    fun deepValidate(d: File) {
        check(filesSane(d)) { "PocketTTS files are missing or truncated" }
        val p = OfflineTtsPocketModelConfig(
            lmFlow = File(d, "lm_flow.int8.onnx").absolutePath,
            lmMain = File(d, "lm_main.int8.onnx").absolutePath,
            encoder = File(d, "encoder.onnx").absolutePath,
            decoder = File(d, "decoder.int8.onnx").absolutePath,
            textConditioner = File(d, "text_conditioner.onnx").absolutePath,
            vocabJson = File(d, "vocab.json").absolutePath,
            tokenScoresJson = File(d, "token_scores.json").absolutePath,
            voiceEmbeddingCacheCapacity = 8,
        )
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                pocket = p,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        OfflineTts(config = cfg) // Constructor parsing is the integrity test.
    }

    suspend fun ensure(
        context: Context,
        forceDeepCheck: Boolean = true,
        progress: (Int, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val live = dir(context)

        if (filesSane(live)) {
            if (!forceDeepCheck) return@withContext
            progress(2, "Verifying installed voice engine")
            val healthy = runCatching { deepValidate(live) }.isSuccess
            if (healthy) {
                progress(100, "Voice engine verified")
                return@withContext
            }
            progress(3, "Damaged model found — rebuilding automatically")
            quarantine(live, context)
            CloneEngine.invalidate()
        } else if (live.exists()) {
            quarantine(live, context)
            CloneEngine.invalidate()
        }

        val models = File(context.filesDir, "models").apply { mkdirs() }
        val stage = File(models, "pocket_stage").apply { deleteRecursively(); mkdirs() }
        val archivePart = File(context.cacheDir, "pocket.tar.bz2.part").apply { delete() }

        try {
            progress(4, "Downloading verified PocketTTS engine")
            val archiveOk = runCatching {
                download(ARCHIVE_URL, archivePart) { p -> progress(4 + (p * 72 / 100), "Downloading model") }
                require(archivePart.length() > 90_000_000L) { "Model archive was unexpectedly small (${archivePart.length()} bytes)" }
                progress(78, "Unpacking model safely")
                extract(archivePart, stage)
                val found = findModelDir(stage) ?: error("Archive did not contain the expected PocketTTS files")
                if (found != stage) copyModelFiles(found, stage)
                require(filesSane(stage)) { "Extracted model failed size verification" }
                true
            }.getOrElse {
                stage.deleteRecursively(); stage.mkdirs()
                progress(10, "Primary download failed — using backup mirror")
                false
            }

            if (!archiveOk) {
                downloadIndividualFiles(stage) { done, total, name ->
                    progress(10 + (done * 68 / total), "Backup download: $name")
                }
            }

            progress(82, "Parsing ONNX files for corruption")
            deepValidate(stage)

            progress(96, "Activating verified engine")
            val old = File(models, "pocket_old").apply { deleteRecursively() }
            if (live.exists() && !live.renameTo(old)) live.deleteRecursively()
            if (!stage.renameTo(live)) {
                live.mkdirs()
                copyModelFiles(stage, live)
                stage.deleteRecursively()
            }
            old.deleteRecursively()
            CloneEngine.invalidate()
            require(filesSane(live)) { "Activation verification failed" }
            deepValidate(live)
            progress(100, "Voice engine repaired and verified")
        } catch (t: Throwable) {
            stage.deleteRecursively()
            throw IOException("Automatic model repair failed: ${t.message}", t)
        } finally {
            archivePart.delete()
        }
    }

    private fun quarantine(live: File, context: Context) {
        if (!live.exists()) return
        val bad = File(context.filesDir, "models/pocket_bad_${System.currentTimeMillis()}")
        if (!live.renameTo(bad)) live.deleteRecursively()
        // Keep no poisoned model around. Quarantine is only useful during this operation.
        bad.deleteRecursively()
    }

    private fun findModelDir(root: File): File? = root.walkTopDown().firstOrNull { it.isDirectory && filesSane(it) }

    private fun copyModelFiles(from: File, to: File) {
        to.mkdirs()
        requiredMinBytes.keys.forEach { name ->
            val src = File(from, name)
            if (src.isFile) src.copyTo(File(to, name), overwrite = true)
        }
    }

    private fun downloadIndividualFiles(out: File, progress: (Int, Int, String) -> Unit) {
        val names = requiredMinBytes.keys.toList()
        names.forEachIndexed { index, name ->
            val dst = File(out, "$name.part").apply { delete() }
            download(HF_BASE + name, dst) { }
            val min = requiredMinBytes.getValue(name)
            require(dst.length() >= min) { "$name backup download was truncated" }
            val final = File(out, name)
            if (final.exists()) final.delete()
            require(dst.renameTo(final)) { "Could not activate $name" }
            progress(index + 1, names.size, name)
        }
    }

    private fun download(url: String, dst: File, progress: (Int) -> Unit) {
        dst.parentFile?.mkdirs()
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 120_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "VoiceForgeX/0.2")
        c.setRequestProperty("Accept", "application/octet-stream,*/*")
        c.connect()
        if (c.responseCode !in 200..299) throw IOException("Download failed HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        FileOutputStream(dst, false).use { out ->
            c.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress(((done * 100L) / total).toInt().coerceIn(0, 100))
                }
                out.fd.sync()
                if (total > 0 && done != total) throw EOFException("Download ended early: $done/$total bytes")
            }
        }
    }

    private fun extract(archive: File, outDir: File) {
        val root = outDir.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val out = File(root, e.name).canonicalFile
                require(out.path == root.path || out.path.startsWith(root.path + File.separator)) { "Unsafe archive path" }
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                }
            }
        }
    }
}

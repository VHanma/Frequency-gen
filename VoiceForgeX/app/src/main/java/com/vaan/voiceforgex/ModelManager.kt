package com.vaan.voiceforgex

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {
    private const val URL_MODEL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2"
    private val required = listOf("lm_flow.int8.onnx", "lm_main.int8.onnx", "encoder.onnx", "decoder.int8.onnx", "text_conditioner.onnx", "vocab.json", "token_scores.json")

    fun dir(context: Context) = File(context.filesDir, "models/pocket")
    fun isReady(context: Context) = required.all { File(dir(context), it).exists() }

    suspend fun ensure(context: Context, progress: (Int, String) -> Unit = {_,_->}) = withContext(Dispatchers.IO) {
        if (isReady(context)) return@withContext
        val models = File(context.filesDir, "models").apply { mkdirs() }
        val archive = File(context.cacheDir, "pocket.tar.bz2")
        progress(0, "Downloading PocketTTS voice-clone engine")
        download(URL_MODEL, archive) { p -> progress(p.coerceIn(0, 95), "Downloading model") }
        val temp = File(models, "pocket_extract").apply { deleteRecursively(); mkdirs() }
        progress(96, "Unpacking model")
        extract(archive, temp)
        val found = temp.walkTopDown().firstOrNull { it.isDirectory && required.all { n -> File(it, n).exists() } }
            ?: throw IOException("Model archive did not contain expected PocketTTS files")
        val dst = dir(context); dst.deleteRecursively(); dst.mkdirs()
        found.listFiles()?.forEach { src -> src.copyRecursively(File(dst, src.name), overwrite = true) }
        temp.deleteRecursively(); archive.delete()
        if (!isReady(context)) throw IOException("Model verification failed")
        progress(100, "Voice engine ready")
    }

    private fun download(url: String, dst: File, progress: (Int) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "VoiceForgeX/0.1")
        c.connect()
        if (c.responseCode !in 200..299) throw IOException("Download failed HTTP ${c.responseCode}")
        val total = c.contentLengthLong
        c.inputStream.use { input -> FileOutputStream(dst).use { out ->
            val buf = ByteArray(1024 * 128); var done = 0L; var n: Int
            while (input.read(buf).also { n = it } > 0) {
                out.write(buf, 0, n); done += n
                if (total > 0) progress(((done * 95) / total).toInt())
            }
        }}
    }

    private fun extract(archive: File, outDir: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            while (true) {
                val e = tar.nextTarEntry ?: break
                val out = File(outDir, e.name).canonicalFile
                require(out.path.startsWith(outDir.canonicalPath)) { "Unsafe archive path" }
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs(); FileOutputStream(out).use { tar.copyTo(it) }
                }
            }
        }
    }
}

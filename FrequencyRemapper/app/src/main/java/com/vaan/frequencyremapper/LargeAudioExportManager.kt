package com.vaan.frequencyremapper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Large-file export path. On Android 10+ the DSP renderer writes directly into
 * the final pending MediaStore item, eliminating the old temp -> durable ->
 * public multi-copy chain. RF64 is handled by ScalablePhaseRenderer.
 */
object LargeAudioExportManager {
    data class Result(
        val uri: Uri,
        val label: String,
        val bytes: Long,
        val rf64: Boolean
    )

    private const val PREFS = "frequency_remapper_large"
    private const val PENDING_URI = "pending_uri"
    private const val LAST_URI = "last_uri"
    private const val LAST_LABEL = "last_label"

    fun cleanupAbandonedPending(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(PENDING_URI, null) ?: return
        runCatching { context.contentResolver.delete(Uri.parse(text), null, null) }
        prefs.edit().remove(PENDING_URI).apply()
    }

    fun recoverLastSavedUri(context: Context): Uri? {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_URI, null) ?: return null
        return runCatching { Uri.parse(text) }.getOrNull()
    }

    fun recoverLastSavedLabel(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_LABEL, null)

    fun renderAndSave(
        context: Context,
        source: PcmSource,
        requestedName: String,
        mappings: List<PhaseFrequencyMapping>,
        options: PhaseRemapOptions,
        onProgress: (Float) -> Unit = {}
    ): Result {
        val expectedBytes = ScalablePhaseRenderer.estimatedFileBytes(source)
        val freeBytes = availableExternalBytes(context)
        val safety = 32L * 1024L * 1024L
        require(freeBytes <= 0L || expectedBytes + safety < freeBytes) {
            "Not enough free storage for the final audio. Need about ${formatBytes(expectedBytes + safety)}, free ${formatBytes(freeBytes)}. The new path uses one output copy only."
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return renderLegacy(context, source, requestedName, mappings, options, onProgress)
        }

        cleanupAbandonedPending(context)
        val resolver = context.contentResolver
        val target = createWritableTarget(context, cleanName(requestedName))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(PENDING_URI, target.uri.toString()).apply()

        try {
            val info = target.pfd.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { stream ->
                    ScalablePhaseRenderer.renderToChannel(
                        source = source,
                        channel = stream.channel,
                        mappings = mappings,
                        options = options,
                        onProgress = onProgress
                    )
                }
            }

            verifyLocalMediaStoreOutput(context, target.uri, info.bytesWritten, info.rf64)
            resolver.update(
                target.uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            prefs.edit()
                .remove(PENDING_URI)
                .putString(LAST_URI, target.uri.toString())
                .putString(LAST_LABEL, target.label)
                .apply()
            return Result(target.uri, target.label, info.bytesWritten, info.rf64)
        } catch (t: Throwable) {
            runCatching { target.pfd.close() }
            runCatching { resolver.delete(target.uri, null, null) }
            prefs.edit().remove(PENDING_URI).apply()
            throw t
        }
    }

    fun copySavedToDocument(
        context: Context,
        sourceUri: Uri,
        destination: Uri,
        onProgress: (Float) -> Unit = {}
    ) {
        val resolver = context.contentResolver
        val sourceLength = uriLength(context, sourceUri)
        val input = if (sourceUri.scheme == "file") {
            FileInputStream(requireNotNull(sourceUri.path))
        } else {
            resolver.openInputStream(sourceUri)
                ?: error("Could not open the saved audio for copying.")
        }

        val pfd = runCatching { resolver.openFileDescriptor(destination, "rwt") }.getOrNull()
            ?: runCatching { resolver.openFileDescriptor(destination, "rw") }.getOrNull()
            ?: error("Android could not open the selected destination.")

        var copied = 0L
        input.use { src ->
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = src.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    copied += read
                    if (sourceLength > 0L) onProgress((copied.toDouble() / sourceLength).toFloat().coerceIn(0f, 1f))
                }
                out.flush()
                runCatching { out.fd.sync() }
            }
        }

        val destinationLength = uriLength(context, destination)
        if (sourceLength > 0L && destinationLength >= 0L) {
            require(destinationLength == sourceLength) {
                "Copied file has $destinationLength of $sourceLength bytes."
            }
        }
        onProgress(1f)
    }

    private data class Target(
        val uri: Uri,
        val label: String,
        val pfd: ParcelFileDescriptor
    )

    private fun createWritableTarget(context: Context, name: String): Target {
        val resolver = context.contentResolver
        val attempts = listOf(
            Triple(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                Environment.DIRECTORY_MUSIC + "/FrequencyRemapper",
                "Music/FrequencyRemapper"
            ),
            Triple(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                Environment.DIRECTORY_DOWNLOADS + "/FrequencyRemapper",
                "Downloads/FrequencyRemapper"
            )
        )
        val errors = ArrayList<String>()

        for ((collection, path, label) in attempts) {
            var uri: Uri? = null
            try {
                uri = resolver.insert(
                    collection,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                ) ?: error("Android refused to create a file.")
                val pfd = resolver.openFileDescriptor(uri, "rw")
                    ?: error("Android created the file but did not allow writing.")
                return Target(uri, label, pfd)
            } catch (t: Throwable) {
                if (uri != null) runCatching { resolver.delete(uri, null, null) }
                errors += "$label: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        error(errors.joinToString(" | "))
    }

    private fun verifyLocalMediaStoreOutput(context: Context, uri: Uri, expectedBytes: Long, rf64: Boolean) {
        val actual = uriLength(context, uri)
        require(actual < 0L || actual == expectedBytes) {
            "Final file size is $actual bytes; expected $expectedBytes."
        }

        val header = ByteArray(12)
        context.contentResolver.openInputStream(uri)?.use { input ->
            var readTotal = 0
            while (readTotal < header.size) {
                val n = input.read(header, readTotal, header.size - readTotal)
                if (n < 0) break
                readTotal += n
            }
            require(readTotal == 12) { "Final audio header could not be read back." }
        } ?: error("Final audio could not be reopened after writing.")

        val id = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        require(wave == "WAVE") { "Final file did not contain a WAVE header." }
        require(if (rf64) id == "RF64" else id == "RIFF") {
            "Final file header type did not match the rendered size."
        }
    }

    private fun renderLegacy(
        context: Context,
        source: PcmSource,
        requestedName: String,
        mappings: List<PhaseFrequencyMapping>,
        options: PhaseRemapOptions,
        onProgress: (Float) -> Unit
    ): Result {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val folder = File(root, "FrequencyRemapper").apply { mkdirs() }
        val file = uniqueFile(folder, cleanName(requestedName))
        val info = RandomAccessFile(file, "rw").use { raf ->
            ScalablePhaseRenderer.renderToChannel(source, raf.channel, mappings, options, onProgress)
        }
        val uri = Uri.fromFile(file)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LAST_URI, uri.toString())
            .putString(LAST_LABEL, file.parent ?: "App audio folder")
            .apply()
        return Result(uri, file.parent ?: "App audio folder", info.bytesWritten, info.rf64)
    }

    private fun availableExternalBytes(context: Context): Long {
        val root = context.getExternalFilesDir(null) ?: return runCatching {
            StatFs(context.filesDir.absolutePath).availableBytes
        }.getOrDefault(-1L)
        return runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(-1L)
    }

    private fun uriLength(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: -1L
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun cleanName(requested: String): String {
        val base = requested.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "frequency-remapped.wav" }
        return if (base.endsWith(".wav", true)) base else "$base.wav"
    }

    private fun uniqueFile(folder: File, name: String): File {
        var f = File(folder, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "wav")
        var i = 2
        while (f.exists()) {
            f = File(folder, "${stem}_$i.$ext")
            i++
        }
        return f
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "unknown"
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mib < 1024.0) String.format(java.util.Locale.US, "%.1f MB", mib)
        else String.format(java.util.Locale.US, "%.2f GB", mib / 1024.0)
    }
}

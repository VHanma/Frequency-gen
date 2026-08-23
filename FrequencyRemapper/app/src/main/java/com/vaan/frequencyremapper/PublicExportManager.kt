package com.vaan.frequencyremapper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports a rendered WAV to a public Android collection and verifies the copy
 * by reading it back. The source is expected to be a durable app-owned file,
 * not a cache file.
 */
object PublicExportManager {
    data class Result(
        val uri: Uri,
        val label: String,
        val bytes: Long
    )

    fun persistRendered(context: Context, renderedTemp: File, sourceName: String): File {
        require(renderedTemp.exists() && renderedTemp.length() > 44L) { "Rendered WAV is missing or empty." }
        val dir = File(context.filesDir, "frequency_remapper_exports").apply {
            if (!exists() && !mkdirs()) error("Could not create durable export storage.")
        }
        val safeStem = sourceName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "audio" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "${safeStem}_remapped_$stamp.wav")
        renderedTemp.copyTo(out, overwrite = true)
        renderedTemp.delete()
        require(out.length() > 44L) { "Durable rendered WAV was not written." }
        context.getSharedPreferences("frequency_remapper", Context.MODE_PRIVATE)
            .edit().putString("last_rendered_path", out.absolutePath).apply()
        return out
    }

    fun recoverLastRendered(context: Context): File? {
        val path = context.getSharedPreferences("frequency_remapper", Context.MODE_PRIVATE)
            .getString("last_rendered_path", null) ?: return null
        return File(path).takeIf { it.exists() && it.length() > 44L }
    }

    fun exportAutomatically(context: Context, source: File, requestedName: String): Result {
        require(source.exists() && source.length() > 44L) { "Rendered WAV is missing or empty." }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val errors = ArrayList<String>()
            runCatching {
                return exportToCollection(
                    context,
                    source,
                    requestedName,
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    Environment.DIRECTORY_MUSIC + "/FrequencyRemapper",
                    "Music/FrequencyRemapper"
                )
            }.onFailure { errors += "Music: ${it.message}" }

            runCatching {
                return exportToCollection(
                    context,
                    source,
                    requestedName,
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    Environment.DIRECTORY_DOWNLOADS + "/FrequencyRemapper",
                    "Downloads/FrequencyRemapper"
                )
            }.onFailure { errors += "Downloads: ${it.message}" }

            error(errors.joinToString(" | "))
        }

        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val folder = File(root, "FrequencyRemapper").apply { mkdirs() }
        val dest = uniqueFile(folder, cleanName(requestedName))
        source.copyTo(dest, overwrite = false)
        require(dest.length() == source.length() && sha256(dest).contentEquals(sha256(source))) {
            "Fallback export failed verification."
        }
        return Result(Uri.fromFile(dest), dest.absolutePath, dest.length())
    }

    fun copyToDocument(context: Context, source: File, destination: Uri) {
        require(source.exists() && source.length() > 44L) { "Rendered WAV is missing or empty." }
        writeAndVerify(context, source, destination)
    }

    private fun exportToCollection(
        context: Context,
        source: File,
        requestedName: String,
        collection: Uri,
        relativePath: String,
        label: String
    ): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName(requestedName))
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Android refused to create the destination file.")
        try {
            writeAndVerify(context, source, uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return Result(uri, label, source.length())
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun writeAndVerify(context: Context, source: File, destination: Uri) {
        val resolver = context.contentResolver
        val pfd = resolver.openFileDescriptor(destination, "w")
            ?: error("Android could not open the destination for writing.")
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
            FileInputStream(source).use { input -> input.copyTo(out, 64 * 1024) }
            out.flush()
            runCatching { out.fd.sync() }
        }

        val expectedLength = source.length()
        val expectedHash = sha256(source)
        val actualDigest = MessageDigest.getInstance("SHA-256")
        var actualLength = 0L
        resolver.openInputStream(destination)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                actualLength += read
                actualDigest.update(buffer, 0, read)
            }
        } ?: error("Saved file could not be reopened for verification.")

        require(actualLength == expectedLength) {
            "Saved file has $actualLength of $expectedLength bytes."
        }
        require(actualDigest.digest().contentEquals(expectedHash)) {
            "Saved file hash does not match the rendered WAV."
        }
    }

    private fun cleanName(requestedName: String): String {
        val base = requestedName.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "frequency-remapped.wav" }
        return if (base.endsWith(".wav", true)) base else "$base.wav"
    }

    private fun uniqueFile(folder: File, name: String): File {
        var f = File(folder, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "wav")
        var i = 2
        while (f.exists()) {
            f = File(folder, "${stem}_$i.$ext")
            i++
        }
        return f
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}

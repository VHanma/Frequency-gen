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

/**
 * A save path that does not report success until the rendered WAV has actually
 * been written to storage. Android 10+ uses the public Downloads collection so
 * the result is easy to find in Files/Downloads/FrequencyRemapper.
 */
object ReliableAudioSaver {
    fun saveToDownloads(context: Context, wavFile: File, requestedName: String): Uri {
        require(wavFile.exists() && wavFile.length() > 44L) {
            "The rendered WAV is empty or missing."
        }

        val cleanName = cleanWavName(requestedName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/FrequencyRemapper"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = resolver.insert(collection, values)
                ?: error("Android could not create the output file in Downloads.")

            try {
                writeAndSync(context, wavFile, uri)
                verifySavedSize(context, wavFile, uri)

                val finished = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, finished, null, null)
                return uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }

        // Android 8/9 fallback. The app still retains a durable exported copy.
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val folder = File(root, "FrequencyRemapper").apply {
            if (!exists() && !mkdirs()) error("Could not create the export folder.")
        }
        val destination = uniqueFile(folder, cleanName)
        wavFile.copyTo(destination, overwrite = false)
        if (!destination.exists() || destination.length() != wavFile.length()) {
            destination.delete()
            error("The exported audio did not finish writing.")
        }
        return Uri.fromFile(destination)
    }

    fun copyToUri(context: Context, wavFile: File, destination: Uri) {
        require(wavFile.exists() && wavFile.length() > 44L) {
            "The rendered WAV is empty or missing."
        }
        writeAndSync(context, wavFile, destination)
        verifySavedSize(context, wavFile, destination)
    }

    private fun writeAndSync(context: Context, source: File, destination: Uri) {
        val resolver = context.contentResolver
        val pfd = resolver.openFileDescriptor(destination, "w")
            ?: error("Could not open the selected save location.")

        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
            FileInputStream(source).use { input ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
            output.flush()
            runCatching { output.fd.sync() }
        }
    }

    private fun verifySavedSize(context: Context, source: File, destination: Uri) {
        val actual = runCatching {
            context.contentResolver.openFileDescriptor(destination, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L

        // Some document providers legitimately return -1 for statSize. When a
        // concrete size is available, require an exact byte-for-byte copy.
        if (actual >= 0L && actual != source.length()) {
            error("Save verification failed: wrote $actual of ${source.length()} bytes.")
        }
    }

    private fun cleanWavName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "frequency-remapped" }
        return if (cleaned.endsWith(".wav", ignoreCase = true)) cleaned else "$cleaned.wav"
    }

    private fun uniqueFile(folder: File, name: String): File {
        var candidate = File(folder, name)
        if (!candidate.exists()) return candidate

        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "wav")
        var index = 2
        while (candidate.exists()) {
            candidate = File(folder, "${stem}_$index.$ext")
            index++
        }
        return candidate
    }
}

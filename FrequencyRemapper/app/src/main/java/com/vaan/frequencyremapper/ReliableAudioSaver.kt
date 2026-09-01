package com.vaan.frequencyremapper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Reliable WAV export. Success is reported only after the destination can be
 * reopened and its byte count + SHA-256 match the rendered cache file.
 */
object ReliableAudioSaver {
    fun saveToDownloads(context: Context, wavFile: File, requestedName: String): Uri {
        requireValidSource(wavFile)
        val cleanName = cleanWavName(requestedName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, cleanName)
                put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FrequencyRemapper")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: error("Android could not create the output file in Downloads.")
            try {
                copyAndVerify(context, wavFile, uri)
                val finished = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, finished, null, null)
                return uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }

        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val folder = File(root, "FrequencyRemapper").apply {
            if (!exists() && !mkdirs()) error("Could not create the export folder.")
        }
        val destination = uniqueFile(folder, cleanName)
        wavFile.copyTo(destination, overwrite = false)
        if (destination.length() != wavFile.length() || sha256File(destination) != sha256File(wavFile)) {
            destination.delete()
            error("The exported audio failed verification.")
        }
        return Uri.fromFile(destination)
    }

    fun copyToUri(context: Context, wavFile: File, destination: Uri) {
        requireValidSource(wavFile)
        copyAndVerify(context, wavFile, destination)
    }

    private fun copyAndVerify(context: Context, source: File, destination: Uri) {
        val resolver = context.contentResolver
        val expectedLength = source.length()
        val expectedDigest = MessageDigest.getInstance("SHA-256")

        val output = resolver.openOutputStream(destination, "wt")
            ?: resolver.openOutputStream(destination, "w")
            ?: error("Android could not open the selected save location for writing.")

        output.use { out ->
            FileInputStream(source).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    expectedDigest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
            out.flush()
        }

        val expectedHash = expectedDigest.digest()
        val actualDigest = MessageDigest.getInstance("SHA-256")
        var actualLength = 0L
        val input = resolver.openInputStream(destination)
            ?: error("The saved file could not be reopened for verification.")
        input.use { saved ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = saved.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                actualDigest.update(buffer, 0, read)
                actualLength += read
            }
        }

        if (actualLength != expectedLength) {
            error("Save verification failed: destination has $actualLength of $expectedLength bytes.")
        }
        if (!actualDigest.digest().contentEquals(expectedHash)) {
            error("Save verification failed: destination audio does not match the rendered WAV.")
        }
    }

    private fun requireValidSource(wavFile: File) {
        require(wavFile.exists() && wavFile.length() > 44L) {
            "The rendered WAV is empty or missing."
        }
    }

    private fun sha256File(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun cleanWavName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "frequency-remapped" }
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

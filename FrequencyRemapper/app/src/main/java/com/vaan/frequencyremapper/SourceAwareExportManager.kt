package com.vaan.frequencyremapper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import java.io.FileOutputStream

/** Direct final-file saver for the v1.6 source-aware renderer. */
object SourceAwareExportManager {
    data class Result(val uri: Uri, val label: String, val bytes: Long, val rf64: Boolean)

    private const val PREFS = "frequency_remapper_source_aware"
    private const val PENDING = "pending_uri"
    private const val LAST = "last_uri"
    private const val LAST_LABEL = "last_label"

    fun cleanup(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = prefs.getString(PENDING, null)?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        runCatching { context.contentResolver.delete(uri, null, null) }
        prefs.edit().remove(PENDING).apply()
    }

    fun recoverLastUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun recoverLastLabel(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_LABEL, null)

    fun renderAndSave(
        context: Context,
        source: StreamAudioSource,
        requestedName: String,
        objects: List<SpectralObject>,
        frequencyEdits: List<SpectralFrequencyEdit>,
        categoryRules: List<CategoryTransformRule>,
        options: PhaseRemapOptions,
        manualRegions: List<ManualMaskRegion> = emptyList(),
        soloCategory: AudioCategory? = null,
        soloConfidence: Double = 0.45,
        onProgress: (Float) -> Unit = {}
    ): Result {
        cleanup(context)
        val estimate = SourceAwareStreamingRenderer.estimatedFileBytes(source)
        val free = availableBytes(context)
        val safety = 24L * 1024L * 1024L
        require(free <= 0L || estimate + safety < free) {
            "Not enough free storage for the final audio. Need about ${formatBytes(estimate + safety)}, free ${formatBytes(free)}. Input decoding remains streaming."
        }

        val resolver = context.contentResolver
        val target = createTarget(context, cleanName(requestedName))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(PENDING, target.uri.toString()).apply()

        try {
            val info = target.pfd.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { output ->
                    SourceAwareStreamingRenderer.renderToChannel(
                        context = context,
                        source = source,
                        channel = output.channel,
                        objects = objects,
                        frequencyEdits = frequencyEdits,
                        categoryRules = categoryRules,
                        options = options,
                        manualRegions = manualRegions,
                        soloCategory = soloCategory,
                        soloConfidence = soloConfidence,
                        onProgress = onProgress
                    )
                }
            }
            verify(context, target.uri, info.bytesWritten, info.rf64)
            resolver.update(
                target.uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            prefs.edit()
                .remove(PENDING)
                .putString(LAST, target.uri.toString())
                .putString(LAST_LABEL, target.label)
                .apply()
            return Result(target.uri, target.label, info.bytesWritten, info.rf64)
        } catch (t: Throwable) {
            runCatching { resolver.delete(target.uri, null, null) }
            prefs.edit().remove(PENDING).apply()
            throw t
        }
    }

    fun copySavedToDocument(context: Context, sourceUri: Uri, destination: Uri, onProgress: (Float) -> Unit = {}) {
        StreamingLargeAudioExportManager.copySavedToDocument(context, sourceUri, destination, onProgress)
    }

    private data class Target(val uri: Uri, val label: String, val pfd: ParcelFileDescriptor)

    private fun createTarget(context: Context, name: String): Target {
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
                ) ?: error("Android refused to create a destination.")
                val pfd = runCatching { resolver.openFileDescriptor(uri, "rwt") }.getOrNull()
                    ?: runCatching { resolver.openFileDescriptor(uri, "rw") }.getOrNull()
                    ?: error("Android created the destination but would not open it for writing.")
                return Target(uri, label, pfd)
            } catch (t: Throwable) {
                if (uri != null) runCatching { resolver.delete(uri, null, null) }
                errors += "$label: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        error(errors.joinToString(" | "))
    }

    private fun verify(context: Context, uri: Uri, expected: Long, rf64: Boolean) {
        val length = uriLength(context, uri)
        require(length < 0L || length == expected) {
            "Final file has $length bytes; renderer wrote $expected."
        }
        val header = ByteArray(12)
        context.contentResolver.openInputStream(uri)?.use { input ->
            var got = 0
            while (got < header.size) {
                val n = input.read(header, got, header.size - got)
                if (n < 0) break
                got += n
            }
            require(got == header.size) { "Saved audio header could not be read back." }
        } ?: error("Saved audio could not be reopened.")
        val id = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        require(wave == "WAVE") { "Saved file is not WAVE audio." }
        require(if (rf64) id == "RF64" else id == "RIFF") {
            "Saved header type does not match rendered audio."
        }
    }

    private fun availableBytes(context: Context): Long = runCatching {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        StatFs(root.absolutePath).availableBytes
    }.getOrDefault(-1L)

    private fun uriLength(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }.getOrDefault(-1L)

    private fun cleanName(name: String): String {
        val clean = name.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "frequency-remapped.wav" }
        return if (clean.endsWith(".wav", true)) clean else "$clean.wav"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "unknown"
        val mib = bytes.toDouble() / 1048576.0
        return if (mib < 1024.0) String.format(java.util.Locale.US, "%.1f MB", mib)
        else String.format(java.util.Locale.US, "%.2f GB", mib / 1024.0)
    }
}

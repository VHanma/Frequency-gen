package com.vhanma.a432matrix

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class CaptureService : Service() {
    companion object {
        const val ACTION_UPDATE = "com.vhanma.a432matrix.UPDATE"
        const val ACTION_STOP = "com.vhanma.a432matrix.STOP"
        const val EXTRA_UIDS = "uids"
        const val EXTRA_LABELS = "labels"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_PROJECTION_DATA = "projectionData"
        private const val CHANNEL = "a432_matrix_scan"
        private const val NOTIFICATION_ID = 432
    }

    private var projection: MediaProjection? = null
    private val running = AtomicBoolean(false)
    private val records = ConcurrentHashMap<Int, AudioRecord>()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "A432 Matrix scanner", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.getAndSet(true)) return START_STICKY

        val uids = intent?.getIntArrayExtra(EXTRA_UIDS) ?: intArrayOf()
        val labels = intent?.getStringArrayExtra(EXTRA_LABELS) ?: arrayOf()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)

        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("A432 Matrix active")
            .setContentText("Analyzing ${uids.size} audio source${if (uids.size == 1) "" else "s"} independently")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)

        if (resultCode != Activity.RESULT_OK || data == null || uids.isEmpty()) {
            broadcast(-1, "Matrix", 0.0, 0.0, "Capture permission/source missing", false)
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                shutdown()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        uids.forEachIndexed { index, uid ->
            val label = labels.getOrNull(index) ?: "UID $uid"
            startSource(uid, label)
        }
        return START_STICKY
    }

    private fun startSource(uid: Int, label: String) {
        val mp = projection ?: return
        try {
            val capture = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUid(uid)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBytes = AudioRecord.getMinBufferSize(
                48_000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(16_384)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBytes * 2)
                .setAudioPlaybackCaptureConfig(capture)
                .build()
            records[uid] = record
            record.startRecording()

            thread(name = "A432-$uid", isDaemon = true) {
                val detector = TuningDetector(48_000)
                val buffer = ShortArray(8192) // 4096 stereo frames
                var lastBroadcast = 0L
                try {
                    while (running.get() && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        var got = 0
                        while (got < buffer.size && running.get()) {
                            val n = record.read(buffer, got, buffer.size - got, AudioRecord.READ_BLOCKING)
                            if (n <= 0) break
                            got += n
                        }
                        if (got < buffer.size) continue
                        val result = detector.analyzeStereoPcm(buffer, got) ?: continue
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastBroadcast >= 450) {
                            broadcast(uid, label, result.referenceHz, result.confidence,
                                result.classification, result.shouldRetune)
                            lastBroadcast = now
                        }
                    }
                } catch (t: Throwable) {
                    broadcast(uid, label, 0.0, 0.0, "Capture blocked: ${t.javaClass.simpleName}", false)
                } finally {
                    try { record.stop() } catch (_: Throwable) {}
                    record.release()
                    records.remove(uid)
                }
            }
        } catch (t: Throwable) {
            broadcast(uid, label, 0.0, 0.0, "Unavailable: ${t.javaClass.simpleName}", false)
        }
    }

    private fun broadcast(uid: Int, label: String, hz: Double, confidence: Double, state: String, retune: Boolean) {
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName).apply {
            putExtra("uid", uid)
            putExtra("label", label)
            putExtra("hz", hz)
            putExtra("confidence", confidence)
            putExtra("state", state)
            putExtra("retune", retune)
        })
    }

    private fun shutdown() {
        running.set(false)
        records.values.forEach { try { it.stop() } catch (_: Throwable) {} }
        records.clear()
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}

package com.vaan.ultracarrier.collective

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.vaan.ultracarrier.OmegaActivity

class OmegaPlaybackService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP_AUDIO -> {
                OmegaRuntime.get(applicationContext).stopFromService()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "UltraCarrier playback" }
                startForeground(NOTIFICATION_ID, notification(label))
                acquireWakeLock()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the foreground audio service alive if the UI task is swiped away.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UltraCarrierOmega:Playback").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "UltraCarrier background audio", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps experimental audio playback running with the screen off or another app open."
                setSound(null, null)
            }
        )
    }

    private fun notification(label: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, OmegaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OmegaPlaybackService::class.java).setAction(ACTION_STOP_AUDIO),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("UltraCarrier Ω running")
            .setContentText(label)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "STOP", stopIntent).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ultracarrier_omega_playback"
        private const val NOTIFICATION_ID = 17017
        private const val ACTION_START = "com.vaan.ultracarrier.omega.START"
        private const val ACTION_STOP_AUDIO = "com.vaan.ultracarrier.omega.STOP_AUDIO"
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, label: String) {
            val intent = Intent(context, OmegaPlaybackService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LABEL, label)
            context.startForegroundService(intent)
        }

        fun shutdown(context: Context) {
            context.stopService(Intent(context, OmegaPlaybackService::class.java))
        }
    }
}

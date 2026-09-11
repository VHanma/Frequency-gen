package com.vaanhanma.doccopy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class ReaderService extends Service implements TextToSpeech.OnInitListener {
    public static final String ACTION_PLAY = "com.vaanhanma.doccopy.reader.PLAY";
    public static final String ACTION_PAUSE = "com.vaanhanma.doccopy.reader.PAUSE";
    public static final String ACTION_STOP = "com.vaanhanma.doccopy.reader.STOP";
    public static final String ACTION_BACK = "com.vaanhanma.doccopy.reader.BACK";
    public static final String ACTION_FORWARD = "com.vaanhanma.doccopy.reader.FORWARD";
    public static final String ACTION_RESTART = "com.vaanhanma.doccopy.reader.RESTART";
    public static final String EXTRA_SPEED = "speed";

    private static final String CHANNEL_ID = "doccopy_reader";
    private static final int NOTIFICATION_ID = 4402;
    private static final int JUMP_CHARS = 700;

    private TextToSpeech tts;
    private SharedPreferences prefs;
    private File textFile;
    private boolean ttsReady = false;
    private boolean playing = false;
    private boolean paused = false;
    private boolean pendingPlay = false;
    private long currentOffset = 0;
    private long totalChars = 0;
    private float speechRate = 1.0f;
    private long activeChunkStart = 0;
    private int activeChunkLength = 0;
    private String docName = "document";

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("omega", MODE_PRIVATE);
        textFile = new File(getFilesDir(), "doccopy_omega_last.txt");
        createNotificationChannel();
        tts = new TextToSpeech(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_PLAY : intent.getAction();
        if (action == null) action = ACTION_PLAY;
        speechRate = clampRate(intent == null ? 1.0f : intent.getFloatExtra(EXTRA_SPEED, speechRate));
        loadBookState();

        switch (action) {
            case ACTION_PAUSE:
                pauseReading();
                break;
            case ACTION_STOP:
                stopReading(true);
                break;
            case ACTION_BACK:
                jumpBy(-JUMP_CHARS);
                break;
            case ACTION_FORWARD:
                jumpBy(JUMP_CHARS);
                break;
            case ACTION_RESTART:
                currentOffset = 0;
                savePosition();
                startReading();
                break;
            case ACTION_PLAY:
            default:
                startReading();
                break;
        }
        return START_NOT_STICKY;
    }

    private void loadBookState() {
        totalChars = prefs.getLong("chars", 0);
        docName = prefs.getString("name", "document");
        String readerDoc = prefs.getString("reader_doc", "");
        if (!docName.equals(readerDoc)) {
            currentOffset = 0;
            prefs.edit().putString("reader_doc", docName).putLong("reader_position", 0).apply();
        } else {
            currentOffset = prefs.getLong("reader_position", 0);
        }
        if (currentOffset < 0) currentOffset = 0;
        if (totalChars > 0 && currentOffset > totalChars) currentOffset = totalChars;
    }

    private void startReading() {
        if (!textFile.exists() || totalChars <= 0) {
            stopSelf();
            return;
        }
        playing = true;
        paused = false;
        pendingPlay = !ttsReady;
        startForegroundCompat(buildNotification("Getting the reader ready…"));
        if (ttsReady) {
            tts.setSpeechRate(speechRate);
            speakNextChunk();
        }
    }

    private void pauseReading() {
        paused = true;
        playing = false;
        pendingPlay = false;
        if (tts != null) tts.stop();
        savePosition();
        updateNotification("Paused • " + percentText());
    }

    private void stopReading(boolean removeService) {
        paused = false;
        playing = false;
        pendingPlay = false;
        if (tts != null) tts.stop();
        savePosition();
        if (removeService) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void jumpBy(int amount) {
        currentOffset = Math.max(0, Math.min(totalChars, currentOffset + amount));
        savePosition();
        if (tts != null) tts.stop();
        if (playing || paused) {
            playing = true;
            paused = false;
            startForegroundCompat(buildNotification("Moving to " + percentText() + "…"));
            if (ttsReady) speakNextChunk();
            else pendingPlay = true;
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            ttsReady = false;
            updateNotification("Your phone's reading voice could not start.");
            return;
        }
        ttsReady = true;
        tts.setLanguage(Locale.getDefault());
        tts.setSpeechRate(speechRate);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                updateNotification("Reading • " + percentText());
            }

            @Override
            public void onDone(String utteranceId) {
                currentOffset = Math.min(totalChars, activeChunkStart + activeChunkLength);
                savePosition();
                if (playing && !paused) speakNextChunk();
            }

            @Override
            public void onError(String utteranceId) {
                updateNotification("The reader hit a problem. Tap Read to try again.");
                playing = false;
            }

            @Override
            public void onError(String utteranceId, int errorCode) {
                onError(utteranceId);
            }

            @Override
            public void onRangeStart(String utteranceId, int start, int end, int frame) {
                currentOffset = Math.min(totalChars, activeChunkStart + Math.max(0, start));
                savePosition();
            }
        });
        if (pendingPlay) {
            pendingPlay = false;
            speakNextChunk();
        }
    }

    private void speakNextChunk() {
        if (!playing || paused || !ttsReady) return;
        if (currentOffset >= totalChars) {
            currentOffset = totalChars;
            savePosition();
            updateNotification("Finished • 100%");
            playing = false;
            return;
        }
        try {
            int max = Math.max(1200, Math.min(3200, TextToSpeech.getMaxSpeechInputLength() - 200));
            String chunk = readChunkAt(currentOffset, max);
            if (chunk.trim().isEmpty()) {
                currentOffset = totalChars;
                savePosition();
                updateNotification("Finished • 100%");
                playing = false;
                return;
            }
            chunk = trimAtNaturalBreak(chunk, 900);
            activeChunkStart = currentOffset;
            activeChunkLength = chunk.length();
            tts.setSpeechRate(speechRate);
            Bundle params = new Bundle();
            int result = tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, params,
                    "omega_" + activeChunkStart + "_" + System.nanoTime());
            if (result == TextToSpeech.ERROR) {
                updateNotification("The phone's reading voice rejected this part. Tap Read to retry.");
                playing = false;
            }
        } catch (Exception e) {
            updateNotification("Reader problem: " + shortError(e));
            playing = false;
        }
    }

    private String readChunkAt(long charOffset, int maxChars) throws Exception {
        try (Reader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(textFile), StandardCharsets.UTF_8), 64 * 1024)) {
            long left = charOffset;
            while (left > 0) {
                long skipped = r.skip(left);
                if (skipped <= 0) {
                    if (r.read() == -1) return "";
                    skipped = 1;
                }
                left -= skipped;
            }
            char[] buf = new char[maxChars];
            int total = 0;
            while (total < maxChars) {
                int n = r.read(buf, total, maxChars - total);
                if (n == -1) break;
                total += n;
            }
            return new String(buf, 0, total);
        }
    }

    private String trimAtNaturalBreak(String s, int minimum) {
        if (s.length() <= minimum) return s;
        int best = -1;
        for (int i = s.length() - 1; i >= minimum; i--) {
            char c = s.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                best = i + 1;
                break;
            }
        }
        if (best < 0) {
            for (int i = s.length() - 1; i >= minimum; i--) {
                if (Character.isWhitespace(s.charAt(i))) {
                    best = i;
                    break;
                }
            }
        }
        return best > 0 ? s.substring(0, best) : s;
    }

    private void savePosition() {
        prefs.edit()
                .putString("reader_doc", docName)
                .putLong("reader_position", Math.max(0, currentOffset))
                .putFloat("reader_speed", speechRate)
                .apply();
    }

    private String percentText() {
        if (totalChars <= 0) return "0%";
        long pct = Math.max(0, Math.min(100, Math.round(currentOffset * 100.0 / totalChars)));
        return pct + "%";
    }

    private float clampRate(float r) {
        if (r < 0.5f) return 0.5f;
        if (r > 2.0f) return 2.0f;
        return r;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Document reader", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps your document reading while the screen is off.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String line) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent play = servicePendingIntent(ACTION_PLAY, 2);
        PendingIntent pause = servicePendingIntent(ACTION_PAUSE, 3);
        PendingIntent stop = servicePendingIntent(ACTION_STOP, 4);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(docName)
                .setContentText(line)
                .setContentIntent(content)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_play, "Read", play)
                .addAction(android.R.drawable.ic_media_pause, "Pause", pause)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop);
        if (totalChars > 0) {
            int p = (int) Math.max(0, Math.min(100, Math.round(currentOffset * 100.0 / totalChars)));
            b.setProgress(100, p, false);
        }
        return b.build();
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent i = new Intent(this, ReaderService.class).setAction(action).putExtra(EXTRA_SPEED, speechRate);
        return PendingIntent.getService(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String line) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(line));
    }

    private String shortError(Throwable t) {
        String s = t.getMessage();
        if (s == null || s.trim().isEmpty()) s = t.getClass().getSimpleName();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    @Override
    public void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

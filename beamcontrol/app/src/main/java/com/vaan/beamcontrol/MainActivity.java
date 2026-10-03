package com.vaan.beamcontrol;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 42;

    private MediaPlayer player;
    private AudioTrack toneTrack;
    private Thread toneThread;
    private volatile boolean toneRunning;
    private float gain = 1.0f;

    private TextView fileText;
    private TextView routeText;
    private Button playPause;
    private Button toneButton;
    private Button routeLockButton;

    private Uri selectedUri;
    private AudioDeviceInfo preferredDevice;
    private boolean routeLock = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(buildUi());
        refreshRoute();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(10, 10, 10));
        scroll.addView(root);

        TextView title = text("BEAM CONTROL", 28, true);
        root.addView(title);

        TextView sub = text("Android → USB-C DAC → 3.5 mm → ultrasonic driver board", 14, false);
        sub.setTextColor(Color.LTGRAY);
        root.addView(sub, lp(-1, -2, 0, 0, 0, 18));

        routeText = text("Output: checking…", 16, true);
        root.addView(routeText, lp(-1, -2, 0, 0, 0, 8));

        Button refresh = button("REFRESH OUTPUT");
        refresh.setOnClickListener(v -> refreshRoute());
        root.addView(refresh);

        routeLockButton = button("USB/WIRED LOCK: ON");
        routeLockButton.setOnClickListener(v -> {
            routeLock = !routeLock;
            routeLockButton.setText(routeLock ? "USB/WIRED LOCK: ON" : "USB/WIRED LOCK: OFF");
            refreshRoute();
            applyPreferredRoute();
        });
        root.addView(routeLockButton, lp(-1, dp(52), 0, 8, 0, 8));

        Button audioPanel = button("OPEN ANDROID AUDIO SETTINGS");
        audioPanel.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Open Sound settings manually", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(audioPanel, lp(-1, dp(52), 0, 0, 0, 18));

        fileText = text("No audio selected", 15, false);
        fileText.setTextColor(Color.LTGRAY);
        root.addView(fileText, lp(-1, -2, 0, 0, 0, 8));

        Button pick = button("CHOOSE AUDIO FILE");
        pick.setOnClickListener(v -> pickAudio());
        root.addView(pick);

        playPause = button("PLAY");
        playPause.setEnabled(false);
        playPause.setOnClickListener(v -> togglePlayback());
        root.addView(playPause, lp(-1, dp(56), 0, 8, 0, 8));

        Button stop = button("STOP");
        stop.setOnClickListener(v -> stopPlayback());
        root.addView(stop, lp(-1, dp(52), 0, 0, 0, 20));

        root.addView(text("Signal level", 15, true));

        SeekBar level = new SeekBar(this);
        level.setMax(100);
        level.setProgress(100);
        level.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                gain = p / 100f;
                if (player != null) player.setVolume(gain, gain);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(level, lp(-1, -2, 0, 0, 0, 20));

        root.addView(text("Board test signal", 15, true));

        toneButton = button("START 1 kHz TEST TONE");
        toneButton.setOnClickListener(v -> {
            if (toneRunning) stopTone();
            else startTone(1000.0);
        });
        root.addView(toneButton);

        Button lowTone = button("START 444 Hz TEST TONE");
        lowTone.setOnClickListener(v -> {
            stopTone();
            startTone(444.0);
        });
        root.addView(lowTone, lp(-1, dp(52), 0, 8, 0, 18));

        TextView note = text(
                "Connection: phone → USB-C DAC/headphone adapter → 3.5 mm cable → board audio input. " +
                "Keep the board on its separate DC supply. USB/Wired Lock asks Android to route this app directly to the detected external audio device.",
                13, false);
        note.setTextColor(Color.GRAY);
        root.addView(note);

        return scroll;
    }

    private void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, PICK_AUDIO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_AUDIO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            fileText.setText("Selected: " + selectedUri.getLastPathSegment());
            playPause.setEnabled(true);
            preparePlayer();
        }
    }

    private void preparePlayer() {
        releasePlayer();
        if (selectedUri == null) return;

        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(this, selectedUri);
            if (routeLock && preferredDevice != null) {
                player.setPreferredDevice(preferredDevice);
            }
            player.setVolume(gain, gain);
            player.setOnCompletionListener(mp -> playPause.setText("PLAY"));
            player.prepare();
        } catch (Exception e) {
            Toast.makeText(this, "Could not open audio: " + e.getMessage(), Toast.LENGTH_LONG).show();
            releasePlayer();
        }
    }

    private void togglePlayback() {
        stopTone();
        if (player == null) preparePlayer();
        if (player == null) return;

        if (player.isPlaying()) {
            player.pause();
            playPause.setText("PLAY");
        } else {
            player.start();
            playPause.setText("PAUSE");
        }
    }

    private void stopPlayback() {
        stopTone();
        if (player != null) {
            try {
                player.pause();
                player.seekTo(0);
            } catch (Exception ignored) {}
        }
        playPause.setText("PLAY");
    }

    private void startTone(double hz) {
        stopPlaybackOnly();
        stopTone();

        final int sampleRate = 48000;
        int min = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int buffer = Math.max(min, 4096);

        toneTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(buffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        if (routeLock && preferredDevice != null) {
            toneTrack.setPreferredDevice(preferredDevice);
        }

        toneRunning = true;
        toneTrack.play();
        toneButton.setText(String.format(Locale.US, "STOP %.0f Hz TEST TONE", hz));

        toneThread = new Thread(() -> {
            short[] samples = new short[2048];
            double phase = 0.0;
            double inc = 2.0 * Math.PI * hz / sampleRate;

            while (toneRunning) {
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = (short) (Math.sin(phase) * 12000 * gain);
                    phase += inc;
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                }

                try {
                    AudioTrack t = toneTrack;
                    if (t == null) break;
                    t.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
                } catch (Exception ignored) {
                    break;
                }
            }
        }, "BeamTone");
        toneThread.start();
    }

    private void stopPlaybackOnly() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.pause();
            } catch (Exception ignored) {}
            playPause.setText("PLAY");
        }
    }

    private void stopTone() {
        toneRunning = false;

        AudioTrack t = toneTrack;
        toneTrack = null;

        if (t != null) {
            try { t.pause(); } catch (Exception ignored) {}
            try { t.flush(); } catch (Exception ignored) {}
            try { t.stop(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }

        if (toneButton != null) toneButton.setText("START 1 kHz TEST TONE");
    }

    private void refreshRoute() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

        StringBuilder found = new StringBuilder();
        AudioDeviceInfo best = null;
        int bestScore = -1;

        for (AudioDeviceInfo d : devices) {
            int score = routeScore(d.getType());
            if (score < 0) continue;

            if (found.length() > 0) found.append(" • ");
            CharSequence n = d.getProductName();
            found.append(n == null || n.length() == 0 ? typeName(d.getType()) : n);

            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }

        preferredDevice = best;

        if (preferredDevice != null) {
            routeText.setText("Output: " + found + (routeLock ? "  • APP LOCKED" : ""));
            routeText.setTextColor(Color.rgb(90, 240, 150));
        } else {
            routeText.setText("External wired/USB output not detected");
            routeText.setTextColor(Color.rgb(255, 180, 70));
        }

        applyPreferredRoute();
    }

    private int routeScore(int t) {
        if (t == AudioDeviceInfo.TYPE_USB_HEADSET) return 100;
        if (t == AudioDeviceInfo.TYPE_USB_DEVICE) return 95;
        if (t == AudioDeviceInfo.TYPE_USB_ACCESSORY) return 90;
        if (t == AudioDeviceInfo.TYPE_LINE_ANALOG) return 80;
        if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) return 70;
        if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET) return 65;
        return -1;
    }

    private void applyPreferredRoute() {
        AudioDeviceInfo target = routeLock ? preferredDevice : null;

        if (player != null) {
            try { player.setPreferredDevice(target); } catch (Exception ignored) {}
        }
        if (toneTrack != null) {
            try { toneTrack.setPreferredDevice(target); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (routeText != null) refreshRoute();
    }

    private String typeName(int t) {
        if (t == AudioDeviceInfo.TYPE_USB_HEADSET) return "USB headset/DAC";
        if (t == AudioDeviceInfo.TYPE_USB_DEVICE) return "USB audio";
        if (t == AudioDeviceInfo.TYPE_USB_ACCESSORY) return "USB accessory audio";
        if (t == AudioDeviceInfo.TYPE_LINE_ANALOG) return "analog line output";
        if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) return "3.5 mm headphones";
        if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET) return "3.5 mm headset";
        return "audio device";
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int top, int r, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(top), dp(r), dp(bottom));
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopTone();
        releasePlayer();
        super.onDestroy();
    }
}

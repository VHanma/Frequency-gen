package com.vaan.pulseluxsync;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int CAMERA_REQUEST = 44;
    private static final int BG = Color.rgb(9, 11, 16);
    private static final int CARD = Color.rgb(21, 24, 34);
    private static final int TEXT = Color.rgb(244, 246, 255);
    private static final int MUTED = Color.rgb(167, 174, 197);
    private static final int ACCENT = Color.rgb(124, 77, 255);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(4);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private LinearLayout root;
    private Switch syncSwitch;
    private EditText masterHz;
    private SeekBar masterDuty;
    private TextView masterDutyLabel;
    private Button startStop;
    private TextView status;

    private Channel torch;
    private Channel screen;
    private Channel vibration;

    private CameraManager cameraManager;
    private String torchCameraId;
    private int torchMaxStrength = 1;
    private Vibrator vibrator;

    private ScheduledFuture<?> syncFuture;
    private ScheduledFuture<?> torchFuture;
    private ScheduledFuture<?> screenFuture;
    private ScheduledFuture<?> vibrationFuture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        findTorch();
        vibrator = getDeviceVibrator();
        buildUi();
        updateSyncUi();
    }

    private Vibrator getDeviceVibrator() {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm.getDefaultVibrator();
        }
        return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }

    private void findTorch() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchCameraId = id;
                    if (Build.VERSION.SDK_INT >= 33) {
                        Integer max = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                        if (max != null) torchMaxStrength = Math.max(1, max);
                    }
                    return;
                }
            }
        } catch (Exception ignored) { }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        root.setBackgroundColor(BG);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("PULSELUX SYNC", 28, TEXT, true);
        title.setLetterSpacing(0.08f);
        root.addView(title);
        TextView sub = text("Torch • Screen • Vibration frequency controller", 14, MUTED, false);
        sub.setPadding(0, dp(5), 0, dp(18));
        root.addView(sub);

        LinearLayout masterCard = card();
        syncSwitch = new Switch(this);
        syncSwitch.setText("SYNC CHANNELS");
        syncSwitch.setTextColor(TEXT);
        syncSwitch.setTextSize(16);
        syncSwitch.setChecked(true);
        syncSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (running.get()) stopAll();
            updateSyncUi();
        });
        masterCard.addView(syncSwitch);

        masterHz = input("12.0");
        addLabeled(masterCard, "MASTER FREQUENCY (Hz)", masterHz);
        masterDutyLabel = text("DUTY CYCLE 50%", 13, MUTED, true);
        masterDuty = slider(1, 99, 50, v -> masterDutyLabel.setText("DUTY CYCLE " + v + "%"));
        masterCard.addView(masterDutyLabel);
        masterCard.addView(masterDuty);
        root.addView(masterCard);

        torch = addChannel("TORCH", "Rear flashlight pulses", 12.0, 100, true);
        screen = addChannel("SCREEN", "Full-screen light pulses", 12.0, 100, true);
        vibration = addChannel("VIBRATION", "Haptic pulses per second", 12.0, 100, true);

        startStop = new Button(this);
        startStop.setText("START OUTPUT");
        startStop.setTextSize(17);
        startStop.setTypeface(Typeface.DEFAULT_BOLD);
        startStop.setTextColor(Color.WHITE);
        startStop.setBackgroundColor(ACCENT);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(58));
        bp.setMargins(0, dp(16), 0, dp(10));
        root.addView(startStop, bp);
        startStop.setOnClickListener(v -> {
            if (running.get()) stopAll(); else requestAndStart();
        });

        status = text("Ready", 13, MUTED, false);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(status);

        TextView note = text("0 Hz = steady ON. Decimal frequencies are accepted. Hardware timing and visible flash rate can top out below the requested value depending on the phone.", 12, MUTED, false);
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private Channel addChannel(String name, String desc, double hz, int intensity, boolean enabled) {
        LinearLayout c = card();
        Switch sw = new Switch(this);
        sw.setText(name);
        sw.setTextColor(TEXT);
        sw.setTextSize(18);
        sw.setTypeface(Typeface.DEFAULT_BOLD);
        sw.setChecked(enabled);
        c.addView(sw);
        TextView d = text(desc, 13, MUTED, false);
        d.setPadding(0, 0, 0, dp(8));
        c.addView(d);

        EditText hzInput = input(String.format(Locale.US, "%.1f", hz));
        addLabeled(c, "FREQUENCY (Hz)", hzInput);

        TextView intensityLabel = text("INTENSITY " + intensity + "%", 13, MUTED, true);
        SeekBar intensityBar = slider(1, 100, intensity, v -> intensityLabel.setText("INTENSITY " + v + "%"));
        c.addView(intensityLabel);
        c.addView(intensityBar);

        TextView dutyLabel = text("DUTY CYCLE 50%", 13, MUTED, true);
        SeekBar dutyBar = slider(1, 99, 50, v -> dutyLabel.setText("DUTY CYCLE " + v + "%"));
        c.addView(dutyLabel);
        c.addView(dutyBar);

        root.addView(c);
        return new Channel(sw, hzInput, intensityBar, dutyBar, dutyLabel);
    }

    private void updateSyncUi() {
        boolean synced = syncSwitch != null && syncSwitch.isChecked();
        if (masterHz != null) masterHz.setEnabled(synced);
        if (masterDuty != null) masterDuty.setEnabled(synced);
        if (torch != null) torch.setIndependentEnabled(!synced);
        if (screen != null) screen.setIndependentEnabled(!synced);
        if (vibration != null) vibration.setIndependentEnabled(!synced);
    }

    private void requestAndStart() {
        if (torch.enabled.isChecked() && torchCameraId != null && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            return;
        }
        startAll();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startAll();
            else {
                torch.enabled.setChecked(false);
                Toast.makeText(this, "Torch permission denied. Screen and vibration can still run.", Toast.LENGTH_LONG).show();
                startAll();
            }
        }
    }

    private void startAll() {
        stopAllInternal(false);
        running.set(true);
        startStop.setText("STOP OUTPUT");
        startStop.setBackgroundColor(Color.rgb(205, 62, 82));

        if (syncSwitch.isChecked()) {
            double hz = parseHz(masterHz, 12.0);
            int duty = masterDuty.getProgress();
            if (hz == 0) {
                applySyncedState(true);
                status.setText("Synced • steady output");
            } else {
                scheduleSynced(hz, duty);
                status.setText(String.format(Locale.US, "Synced • %.3f Hz • %d%% duty", hz, duty));
            }
        } else {
            scheduleIndependent(torch, Output.TORCH);
            scheduleIndependent(screen, Output.SCREEN);
            scheduleIndependent(vibration, Output.VIBRATION);
            status.setText("Independent channel timing active");
        }
    }

    private void scheduleSynced(double hz, int dutyPercent) {
        final long cycleNs = cycleNanos(hz);
        final long onNs = Math.max(1_000_000L, Math.round(cycleNs * (dutyPercent / 100.0)));
        final long offNs = Math.max(1_000_000L, cycleNs - onNs);
        final AtomicBoolean on = new AtomicBoolean(false);
        final Runnable[] pulse = new Runnable[1];
        pulse[0] = () -> {
            if (!running.get()) return;
            boolean next = !on.get();
            on.set(next);
            applySyncedState(next);
            long delay = next ? onNs : offNs;
            syncFuture = scheduler.schedule(pulse[0], delay, TimeUnit.NANOSECONDS);
        };
        syncFuture = scheduler.schedule(pulse[0], 0, TimeUnit.NANOSECONDS);
    }

    private void applySyncedState(boolean on) {
        if (torch.enabled.isChecked()) setTorch(on, torch.intensity.getProgress());
        if (screen.enabled.isChecked()) setScreen(on, screen.intensity.getProgress());
        if (vibration.enabled.isChecked()) {
            if (on) pulseVibratorOneShot(estimateSyncedOnMs(), vibration.intensity.getProgress());
            else cancelVibration();
        }
    }

    private long estimateSyncedOnMs() {
        double hz = parseHz(masterHz, 12.0);
        if (hz <= 0) return 60_000;
        double cycleMs = 1000.0 / hz;
        return Math.max(1, Math.round(cycleMs * masterDuty.getProgress() / 100.0));
    }

    private void scheduleIndependent(Channel channel, Output output) {
        if (!channel.enabled.isChecked()) return;
        double hz = parseHz(channel.hz, 12.0);
        int duty = channel.duty.getProgress();
        int intensity = channel.intensity.getProgress();
        if (hz == 0) {
            setOutput(output, true, intensity, 60_000);
            return;
        }

        final long cycleNs = cycleNanos(hz);
        final long onNs = Math.max(1_000_000L, Math.round(cycleNs * (duty / 100.0)));
        final long offNs = Math.max(1_000_000L, cycleNs - onNs);
        final AtomicBoolean on = new AtomicBoolean(false);
        final Runnable[] pulse = new Runnable[1];
        pulse[0] = () -> {
            if (!running.get()) return;
            boolean next = !on.get();
            on.set(next);
            setOutput(output, next, intensity, Math.max(1, TimeUnit.NANOSECONDS.toMillis(onNs)));
            long delay = next ? onNs : offNs;
            ScheduledFuture<?> f = scheduler.schedule(pulse[0], delay, TimeUnit.NANOSECONDS);
            assignFuture(output, f);
        };
        ScheduledFuture<?> f = scheduler.schedule(pulse[0], 0, TimeUnit.NANOSECONDS);
        assignFuture(output, f);
    }

    private void assignFuture(Output output, ScheduledFuture<?> f) {
        if (output == Output.TORCH) torchFuture = f;
        else if (output == Output.SCREEN) screenFuture = f;
        else vibrationFuture = f;
    }

    private void setOutput(Output output, boolean on, int intensity, long onMs) {
        switch (output) {
            case TORCH: setTorch(on, intensity); break;
            case SCREEN: setScreen(on, intensity); break;
            case VIBRATION:
                if (on) pulseVibratorOneShot(onMs, intensity); else cancelVibration();
                break;
        }
    }

    private long cycleNanos(double hz) {
        double bounded = Math.max(0.001, Math.min(hz, 10000.0));
        return Math.max(2_000_000L, Math.round(1_000_000_000.0 / bounded));
    }

    private double parseHz(EditText field, double fallback) {
        try {
            double v = Double.parseDouble(field.getText().toString().trim());
            if (!Double.isFinite(v)) return fallback;
            v = Math.max(0.0, Math.min(10000.0, v));
            field.setText(trimHz(v));
            field.setSelection(field.length());
            return v;
        } catch (Exception e) {
            field.setText(trimHz(fallback));
            return fallback;
        }
    }

    private String trimHz(double v) {
        if (Math.rint(v) == v) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void setTorch(boolean on, int intensityPercent) {
        if (torchCameraId == null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (on) {
                if (Build.VERSION.SDK_INT >= 33 && torchMaxStrength > 1) {
                    int strength = Math.max(1, Math.round((intensityPercent / 100f) * torchMaxStrength));
                    cameraManager.turnOnTorchWithStrengthLevel(torchCameraId, strength);
                } else {
                    cameraManager.setTorchMode(torchCameraId, true);
                }
            } else {
                cameraManager.setTorchMode(torchCameraId, false);
            }
        } catch (CameraAccessException | IllegalArgumentException | SecurityException ignored) { }
    }

    private void setScreen(boolean on, int intensityPercent) {
        main.post(() -> {
            int level = Math.max(0, Math.min(255, Math.round(255 * intensityPercent / 100f)));
            root.setBackgroundColor(on ? Color.rgb(level, level, level) : BG);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = on ? Math.max(0.01f, intensityPercent / 100f) : 0.01f;
            getWindow().setAttributes(lp);
        });
    }

    private void pulseVibratorOneShot(long ms, int intensityPercent) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        int amp = Math.max(1, Math.min(255, Math.round(255 * intensityPercent / 100f)));
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(Math.max(1, ms), amp));
        } catch (Exception ignored) { }
    }

    private void cancelVibration() {
        if (vibrator != null) vibrator.cancel();
    }

    private void stopAll() {
        stopAllInternal(true);
    }

    private void stopAllInternal(boolean updateUi) {
        running.set(false);
        cancel(syncFuture); cancel(torchFuture); cancel(screenFuture); cancel(vibrationFuture);
        syncFuture = torchFuture = screenFuture = vibrationFuture = null;
        setTorch(false, 1);
        cancelVibration();
        main.post(() -> {
            if (root != null) root.setBackgroundColor(BG);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
            if (updateUi && startStop != null) {
                startStop.setText("START OUTPUT");
                startStop.setBackgroundColor(ACCENT);
                status.setText("Stopped");
            }
        });
    }

    private void cancel(ScheduledFuture<?> f) {
        if (f != null) f.cancel(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (running.get()) stopAll();
    }

    @Override
    protected void onDestroy() {
        stopAllInternal(false);
        scheduler.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        l.setBackgroundColor(CARD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(12));
        l.setLayoutParams(p);
        return l;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText input(String initial) {
        EditText e = new EditText(this);
        e.setText(initial);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(18);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setPadding(dp(12), dp(7), dp(12), dp(7));
        e.setBackgroundColor(Color.rgb(33, 37, 51));
        return e;
    }

    private void addLabeled(LinearLayout parent, String label, View field) {
        TextView l = text(label, 12, MUTED, true);
        l.setPadding(0, dp(10), 0, dp(5));
        parent.addView(l);
        parent.addView(field, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private SeekBar slider(int min, int max, int progress, Progress listener) {
        SeekBar s = new SeekBar(this);
        if (Build.VERSION.SDK_INT >= 26) s.setMin(min);
        s.setMax(max);
        s.setProgress(progress);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                if (p < min) { seekBar.setProgress(min); p = min; }
                listener.onValue(p);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        return s;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private enum Output { TORCH, SCREEN, VIBRATION }
    private interface Progress { void onValue(int value); }

    private static class Channel {
        final Switch enabled;
        final EditText hz;
        final SeekBar intensity;
        final SeekBar duty;
        final TextView dutyLabel;
        Channel(Switch enabled, EditText hz, SeekBar intensity, SeekBar duty, TextView dutyLabel) {
            this.enabled = enabled;
            this.hz = hz;
            this.intensity = intensity;
            this.duty = duty;
            this.dutyLabel = dutyLabel;
        }
        void setIndependentEnabled(boolean enabledState) {
            hz.setEnabled(enabledState);
            duty.setEnabled(enabledState);
            dutyLabel.setAlpha(enabledState ? 1f : 0.4f);
            hz.setAlpha(enabledState ? 1f : 0.4f);
            duty.setAlpha(enabledState ? 1f : 0.4f);
        }
    }
}

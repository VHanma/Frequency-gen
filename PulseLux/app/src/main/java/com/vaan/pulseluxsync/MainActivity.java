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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;
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
    private static final long MAX_DELAY_NS = Long.MAX_VALUE / 4L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(4);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private LinearLayout root;
    private Switch syncSwitch;
    private EditText masterHz;
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
        TextView sub = text("Frequency-only torch • screen • vibration controller", 14, MUTED, false);
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

        masterHz = input("12");
        addLabeled(masterCard, "MASTER Hz", masterHz);
        TextView masterHelp = text("One Hz value drives every enabled channel together.", 12, MUTED, false);
        masterHelp.setPadding(0, dp(8), 0, 0);
        masterCard.addView(masterHelp);
        root.addView(masterCard);

        torch = addChannel("TORCH", "Rear flashlight strobe", "12", true);
        screen = addChannel("SCREEN", "Screen light strobe", "12", true);
        vibration = addChannel("VIBRATION", "Vibration pulses per second", "12", true);

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

        TextView note = text("Type the Hz number you want. Decimals are accepted. 0 Hz = steady ON. Pulse output uses equal ON and OFF halves automatically, with no percentage controls and no 10,000 Hz software cap.", 12, MUTED, false);
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private Channel addChannel(String name, String desc, String hz, boolean enabled) {
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

        EditText hzInput = input(hz);
        addLabeled(c, "Hz", hzInput);

        root.addView(c);
        return new Channel(sw, hzInput);
    }

    private void updateSyncUi() {
        boolean synced = syncSwitch != null && syncSwitch.isChecked();
        if (masterHz != null) {
            masterHz.setEnabled(synced);
            masterHz.setAlpha(synced ? 1f : 0.4f);
        }
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
            HzValue hz = parseHz(masterHz, "12");
            if (hz.isZero()) {
                applySyncedState(true, 60_000L);
                status.setText("Synced • steady output");
            } else {
                scheduleSynced(hz);
                status.setText("Synced • " + hz.display + " Hz");
            }
        } else {
            scheduleIndependent(torch, Output.TORCH);
            scheduleIndependent(screen, Output.SCREEN);
            scheduleIndependent(vibration, Output.VIBRATION);
            status.setText("Independent Hz timing active");
        }
    }

    private void scheduleSynced(HzValue hz) {
        final long halfNs = halfCycleNanos(hz.value);
        final AtomicBoolean on = new AtomicBoolean(false);
        final Runnable[] pulse = new Runnable[1];
        pulse[0] = () -> {
            if (!running.get()) return;
            boolean next = !on.get();
            on.set(next);
            applySyncedState(next, nanosToPulseMillis(halfNs));
            syncFuture = scheduler.schedule(pulse[0], halfNs, TimeUnit.NANOSECONDS);
        };
        syncFuture = scheduler.schedule(pulse[0], 0, TimeUnit.NANOSECONDS);
    }

    private void applySyncedState(boolean on, long pulseMs) {
        if (torch.enabled.isChecked()) setTorch(on);
        if (screen.enabled.isChecked()) setScreen(on);
        if (vibration.enabled.isChecked()) {
            if (on) pulseVibratorOneShot(pulseMs);
            else cancelVibration();
        }
    }

    private void scheduleIndependent(Channel channel, Output output) {
        if (!channel.enabled.isChecked()) return;
        HzValue hz = parseHz(channel.hz, "12");
        if (hz.isZero()) {
            setOutput(output, true, 60_000L);
            return;
        }

        final long halfNs = halfCycleNanos(hz.value);
        final AtomicBoolean on = new AtomicBoolean(false);
        final Runnable[] pulse = new Runnable[1];
        pulse[0] = () -> {
            if (!running.get()) return;
            boolean next = !on.get();
            on.set(next);
            setOutput(output, next, nanosToPulseMillis(halfNs));
            ScheduledFuture<?> f = scheduler.schedule(pulse[0], halfNs, TimeUnit.NANOSECONDS);
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

    private void setOutput(Output output, boolean on, long pulseMs) {
        switch (output) {
            case TORCH:
                setTorch(on);
                break;
            case SCREEN:
                setScreen(on);
                break;
            case VIBRATION:
                if (on) pulseVibratorOneShot(pulseMs);
                else cancelVibration();
                break;
        }
    }

    private long halfCycleNanos(double hz) {
        if (!(hz > 0.0) || Double.isNaN(hz)) return MAX_DELAY_NS;
        if (Double.isInfinite(hz)) return 1L;
        double half = 500_000_000.0 / hz;
        if (half >= MAX_DELAY_NS) return MAX_DELAY_NS;
        if (half <= 1.0) return 1L;
        return Math.max(1L, Math.round(half));
    }

    private long nanosToPulseMillis(long ns) {
        if (ns >= TimeUnit.MILLISECONDS.toNanos(60_000L)) return 60_000L;
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(ns));
    }

    private HzValue parseHz(EditText field, String fallback) {
        String raw = field.getText().toString().trim();
        try {
            if (raw.isEmpty()) throw new NumberFormatException();
            BigDecimal bd = new BigDecimal(raw);
            if (bd.signum() < 0) {
                Toast.makeText(this, "Hz must be 0 or greater.", Toast.LENGTH_SHORT).show();
                return parseFallback(field, fallback);
            }
            double value = bd.doubleValue();
            if (value == 0.0 && bd.signum() > 0) value = Double.MIN_VALUE;
            if (Double.isInfinite(value)) value = Double.POSITIVE_INFINITY;
            return new HzValue(value, raw);
        } catch (Exception e) {
            Toast.makeText(this, "Enter a number for Hz.", Toast.LENGTH_SHORT).show();
            return parseFallback(field, fallback);
        }
    }

    private HzValue parseFallback(EditText field, String fallback) {
        field.setText(fallback);
        field.setSelection(field.length());
        return new HzValue(Double.parseDouble(fallback), fallback);
    }

    private void setTorch(boolean on) {
        if (torchCameraId == null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (on) {
                if (Build.VERSION.SDK_INT >= 33 && torchMaxStrength > 1) {
                    cameraManager.turnOnTorchWithStrengthLevel(torchCameraId, torchMaxStrength);
                } else {
                    cameraManager.setTorchMode(torchCameraId, true);
                }
            } else {
                cameraManager.setTorchMode(torchCameraId, false);
            }
        } catch (CameraAccessException | IllegalArgumentException | SecurityException ignored) { }
    }

    private void setScreen(boolean on) {
        main.post(() -> {
            root.setBackgroundColor(on ? Color.WHITE : BG);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = on ? 1.0f : 0.01f;
            getWindow().setAttributes(lp);
        });
    }

    private void pulseVibratorOneShot(long ms) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(Math.max(1L, ms), 255));
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
        cancel(syncFuture);
        cancel(torchFuture);
        cancel(screenFuture);
        cancel(vibrationFuture);
        syncFuture = torchFuture = screenFuture = vibrationFuture = null;
        setTorch(false);
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private enum Output { TORCH, SCREEN, VIBRATION }

    private static class HzValue {
        final double value;
        final String display;

        HzValue(double value, String display) {
            this.value = value;
            this.display = display;
        }

        boolean isZero() {
            return value == 0.0;
        }
    }

    private static class Channel {
        final Switch enabled;
        final EditText hz;

        Channel(Switch enabled, EditText hz) {
            this.enabled = enabled;
            this.hz = hz;
        }

        void setIndependentEnabled(boolean enabledState) {
            hz.setEnabled(enabledState);
            hz.setAlpha(enabledState ? 1f : 0.4f);
        }
    }
}

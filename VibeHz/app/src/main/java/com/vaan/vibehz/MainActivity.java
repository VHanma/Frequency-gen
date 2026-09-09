package com.vaan.vibehz;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.vibrator.VibratorFrequencyProfile;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(5, 7, 11);
    private static final int SURFACE = Color.rgb(16, 21, 31);
    private static final int SURFACE_2 = Color.rgb(24, 31, 44);
    private static final int TEXT = Color.rgb(247, 251, 255);
    private static final int MUTED = Color.rgb(153, 169, 190);
    private static final int CYAN = Color.rgb(88, 225, 255);
    private static final int VIOLET = Color.rgb(161, 110, 255);
    private static final int GREEN = Color.rgb(91, 238, 158);
    private static final int RED = Color.rgb(255, 92, 115);

    private Vibrator vibrator;
    private VibratorFrequencyProfile frequencyProfile;
    private boolean trueHzAvailable = false;
    private float minTrueHz = 0f;
    private float maxTrueHz = 0f;

    private EditText hzInput;
    private SeekBar hzSeek;
    private TextView hzBig;
    private TextView modeValue;
    private TextView rangeValue;
    private TextView outputValue;
    private TextView strengthValue;
    private TextView durationValue;
    private TextView stateValue;
    private SeekBar strengthSeek;
    private SeekBar durationSeek;
    private Button startButton;

    private boolean continuous = true;
    private float selectedHz = 120f;
    private int strengthPercent = 80;
    private long burstDurationMs = 5000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        initVibrator();
        buildUi();
        refreshCapabilityUi();
        setSelectedHz(defaultHz());
    }

    private void initVibrator() {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = manager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (Build.VERSION.SDK_INT >= 36 && vibrator != null && vibrator.hasVibrator()) {
            frequencyProfile = vibrator.getFrequencyProfile();
            if (frequencyProfile != null) {
                minTrueHz = frequencyProfile.getMinFrequencyHz();
                maxTrueHz = frequencyProfile.getMaxFrequencyHz();
                trueHzAvailable = minTrueHz > 0 && maxTrueHz >= minTrueHz;
            }
        }
    }

    private float defaultHz() {
        if (trueHzAvailable) return Math.max(minTrueHz, Math.min(maxTrueHz, 120f));
        return 40f;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView eyebrow = text("VIBRATION SYNTH", 12, CYAN, true);
        eyebrow.setLetterSpacing(0.18f);
        root.addView(eyebrow);
        TextView title = text("VibeHz", 38, TEXT, true);
        title.setPadding(0, dp(3), 0, 0);
        root.addView(title);
        TextView subtitle = text("Dial the motor by frequency.", 16, MUTED, false);
        subtitle.setPadding(0, 0, 0, dp(20));
        root.addView(subtitle);

        LinearLayout capability = card();
        root.addView(capability, lpMatchWrap(dp(12)));
        capability.addView(label("HARDWARE MODE"));
        modeValue = text("Checking…", 18, TEXT, true);
        capability.addView(modeValue);
        rangeValue = text("", 13, MUTED, false);
        rangeValue.setPadding(0, dp(6), 0, 0);
        capability.addView(rangeValue);

        LinearLayout dial = card();
        root.addView(dial, lpMatchWrap(dp(12)));
        dial.addView(label("FREQUENCY"));
        hzBig = text("120.00 Hz", 34, TEXT, true);
        hzBig.setGravity(Gravity.CENTER_HORIZONTAL);
        hzBig.setPadding(0, dp(8), 0, dp(8));
        dial.addView(hzBig);

        hzSeek = new SeekBar(this);
        hzSeek.setMax(1000);
        hzSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
        hzSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(CYAN));
        dial.addView(hzSeek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        hzSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float min = sliderMin();
                float max = sliderMax();
                float hz = min + (max - min) * (progress / 1000f);
                setSelectedHz(hz, false);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        LinearLayout entryRow = new LinearLayout(this);
        entryRow.setOrientation(LinearLayout.HORIZONTAL);
        entryRow.setGravity(Gravity.CENTER_VERTICAL);
        entryRow.setPadding(0, dp(10), 0, 0);
        dial.addView(entryRow);
        hzInput = new EditText(this);
        hzInput.setTextColor(TEXT);
        hzInput.setHintTextColor(MUTED);
        hzInput.setTextSize(17);
        hzInput.setSingleLine(true);
        hzInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        hzInput.setHint("Enter Hz");
        hzInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        hzInput.setBackground(roundRect(SURFACE_2, 12, Color.rgb(50, 65, 87), 1));
        entryRow.addView(hzInput, new LinearLayout.LayoutParams(0, dp(52), 1f));
        Button apply = button("APPLY", CYAN, BG);
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(dp(96), dp(52));
        applyLp.setMargins(dp(10), 0, 0, 0);
        entryRow.addView(apply, applyLp);
        apply.setOnClickListener(v -> applyTypedHz());
        hzInput.setOnEditorActionListener((v, actionId, event) -> { applyTypedHz(); return true; });
        outputValue = text("", 13, MUTED, false);
        outputValue.setPadding(0, dp(10), 0, 0);
        dial.addView(outputValue);

        root.addView(labelOutside("PRESETS"));
        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presetScroll.addView(presets);
        root.addView(presetScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        double[] presetValues = {7.83, 10, 20, 40, 60, 80, 100, 120, 160, 200, 240};
        for (double p : presetValues) {
            Button b = chip(formatHz((float) p));
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            pLp.setMargins(0, 0, dp(8), 0);
            presets.addView(b, pLp);
            b.setOnClickListener(v -> setSelectedHz((float) p));
        }

        LinearLayout strengthCard = card();
        root.addView(strengthCard, lpMatchWrap(dp(12)));
        LinearLayout strengthHeader = headerRow("STRENGTH");
        strengthValue = text(strengthPercent + "%", 16, CYAN, true);
        strengthHeader.addView(strengthValue);
        strengthCard.addView(strengthHeader);
        strengthSeek = new SeekBar(this);
        strengthSeek.setMax(99);
        strengthSeek.setProgress(strengthPercent - 1);
        strengthSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(VIOLET));
        strengthSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(VIOLET));
        strengthCard.addView(strengthSeek);
        strengthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                strengthPercent = progress + 1;
                strengthValue.setText(strengthPercent + "%");
                refreshOutputEstimate();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        LinearLayout durationCard = card();
        root.addView(durationCard, lpMatchWrap(dp(12)));
        LinearLayout durationHeader = headerRow("DURATION");
        durationValue = text("Continuous", 16, CYAN, true);
        durationHeader.addView(durationValue);
        durationCard.addView(durationHeader);
        durationSeek = new SeekBar(this);
        durationSeek.setMax(4);
        durationSeek.setProgress(4);
        durationSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
        durationSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(CYAN));
        durationCard.addView(durationSeek);
        TextView ticks = text("0.5 s        1 s        3 s        5 s        ∞", 12, MUTED, false);
        ticks.setGravity(Gravity.CENTER_HORIZONTAL);
        durationCard.addView(ticks);
        durationSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                long[] values = {500, 1000, 3000, 5000, -1};
                burstDurationMs = values[progress];
                continuous = burstDurationMs < 0;
                durationValue.setText(continuous ? "Continuous" : (burstDurationMs / 1000f) + " s");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        LinearLayout stateCard = card();
        root.addView(stateCard, lpMatchWrap(dp(14)));
        stateValue = text("READY", 14, GREEN, true);
        stateValue.setLetterSpacing(0.12f);
        stateCard.addView(stateValue);
        TextView stateHint = text("Direct Hz is used whenever your motor exposes that exact frequency range. Otherwise VibeHz switches to pulse-rate mode.", 13, MUTED, false);
        stateHint.setPadding(0, dp(7), 0, 0);
        stateCard.addView(stateHint);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        startButton = button("START", CYAN, BG);
        Button stopButton = button("STOP", SURFACE_2, TEXT);
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(0, dp(58), 1f);
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(58), 0.45f);
        stopLp.setMargins(dp(10), 0, 0, 0);
        controls.addView(startButton, startLp);
        controls.addView(stopButton, stopLp);
        startButton.setOnClickListener(v -> startVibration());
        stopButton.setOnClickListener(v -> stopVibration());
    }

    private void refreshCapabilityUi() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            modeValue.setText("No vibrator detected");
            modeValue.setTextColor(RED);
            rangeValue.setText("This device reports no vibration motor.");
            startButton.setEnabled(false);
            return;
        }
        if (trueHzAvailable) {
            modeValue.setText("TRUE Hz CONTROL  •  Android 16");
            modeValue.setTextColor(GREEN);
            rangeValue.setText(String.format(Locale.US, "Motor range: %.1f–%.1f Hz", minTrueHz, maxTrueHz));
        } else {
            modeValue.setText("PULSE-RATE FALLBACK");
            modeValue.setTextColor(CYAN);
            rangeValue.setText("Independent motor-frequency control is not exposed by this device.");
        }
    }

    private float sliderMin() { return trueHzAvailable ? minTrueHz : 1f; }
    private float sliderMax() { return trueHzAvailable ? maxTrueHz : 250f; }

    private void applyTypedHz() {
        try {
            float hz = Float.parseFloat(hzInput.getText().toString().trim());
            if (hz <= 0f || Float.isInfinite(hz) || Float.isNaN(hz)) throw new NumberFormatException();
            setSelectedHz(hz);
            hzInput.clearFocus();
        } catch (Exception e) {
            Toast.makeText(this, "Enter a frequency above 0 Hz", Toast.LENGTH_SHORT).show();
        }
    }

    private void setSelectedHz(float hz) { setSelectedHz(hz, true); }
    private void setSelectedHz(float hz, boolean moveSlider) {
        selectedHz = Math.max(0.01f, hz);
        hzBig.setText(formatHz(selectedHz) + " Hz");
        hzInput.setText(trimFloat(selectedHz));
        if (moveSlider) {
            float min = sliderMin();
            float max = sliderMax();
            float clamped = Math.max(min, Math.min(max, selectedHz));
            int progress = Math.round(((clamped - min) / Math.max(0.0001f, max - min)) * 1000f);
            hzSeek.setProgress(Math.max(0, Math.min(1000, progress)));
        }
        refreshOutputEstimate();
    }

    private void refreshOutputEstimate() {
        if (outputValue == null) return;
        if (isDirectHz(selectedHz)) {
            float maxGs = frequencyProfile.getOutputAccelerationGs(selectedHz);
            float scaledGs = maxGs * (strengthPercent / 100f);
            outputValue.setText(String.format(Locale.US, "Direct actuator frequency • %.2f G max at %s Hz • %.0f%% ≈ %.2f G", maxGs, formatHz(selectedHz), (float) strengthPercent, scaledGs));
            outputValue.setTextColor(GREEN);
        } else if (trueHzAvailable) {
            outputValue.setText(String.format(Locale.US, "%s Hz is outside this motor's %.1f–%.1f Hz direct range • pulse-rate fallback", formatHz(selectedHz), minTrueHz, maxTrueHz));
            outputValue.setTextColor(CYAN);
        } else {
            outputValue.setText("Pulse-rate fallback • selected Hz controls the on/off pulse rate, not the actuator's internal resonance frequency");
            outputValue.setTextColor(MUTED);
        }
    }

    private boolean isDirectHz(float hz) {
        return Build.VERSION.SDK_INT >= 36 && trueHzAvailable && hz >= minTrueHz && hz <= maxTrueHz;
    }

    private void startVibration() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        stopVibration(false);
        try {
            if (isDirectHz(selectedHz)) {
                playDirectFrequency();
                stateValue.setText("RUNNING  •  TRUE " + formatHz(selectedHz) + " Hz");
                stateValue.setTextColor(GREEN);
            } else {
                playPulseFallback();
                stateValue.setText("RUNNING  •  " + formatHz(selectedHz) + " Hz PULSE RATE");
                stateValue.setTextColor(CYAN);
            }
        } catch (Throwable t) {
            stateValue.setText("VIBRATION ERROR");
            stateValue.setTextColor(RED);
            Toast.makeText(this, t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void playDirectFrequency() {
        if (Build.VERSION.SDK_INT < 36) return;
        float amp = strengthPercent / 100f;
        long total = continuous ? 10000L : Math.max(100L, burstDurationMs);
        long ramp = Math.min(20L, Math.max(1L, total / 10L));
        long hold = Math.max(1L, total - ramp * 2L);
        VibrationEffect segment = new VibrationEffect.WaveformEnvelopeBuilder()
                .setInitialFrequencyHz(selectedHz)
                .addControlPoint(amp, selectedHz, ramp)
                .addControlPoint(amp, selectedHz, hold)
                .addControlPoint(0f, selectedHz, ramp)
                .build();
        vibrator.vibrate(continuous ? VibrationEffect.createRepeatingEffect(segment) : segment);
    }

    private void playPulseFallback() {
        int amplitude = Math.max(1, Math.min(255, Math.round(255f * strengthPercent / 100f)));
        long periodMs = Math.max(2L, Math.round(1000.0 / Math.max(0.01, selectedHz)));
        long onMs = Math.max(1L, periodMs / 2L);
        long offMs = Math.max(1L, periodMs - onMs);
        if (continuous) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, onMs, offMs}, new int[]{0, amplitude, 0}, 1));
        } else {
            int pulses = (int) Math.max(1, Math.min(2000, Math.ceil(burstDurationMs / (double) periodMs)));
            long[] timings = new long[1 + pulses * 2];
            int[] amplitudes = new int[timings.length];
            for (int i = 0; i < pulses; i++) {
                int idx = 1 + i * 2;
                timings[idx] = onMs;
                amplitudes[idx] = amplitude;
                timings[idx + 1] = offMs;
            }
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
        }
    }

    private void stopVibration() { stopVibration(true); }
    private void stopVibration(boolean updateUi) {
        if (vibrator != null) vibrator.cancel();
        if (updateUi && stateValue != null) {
            stateValue.setText("STOPPED");
            stateValue.setTextColor(MUTED);
        }
    }

    @Override protected void onDestroy() {
        stopVibration(false);
        super.onDestroy();
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(15), dp(16), dp(15));
        l.setBackground(roundRect(SURFACE, 18, Color.rgb(37, 48, 65), 1));
        return l;
    }

    private LinearLayout headerRow(String labelText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = label(labelText);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, MUTED, true);
        t.setLetterSpacing(0.12f);
        return t;
    }

    private TextView labelOutside(String s) {
        TextView t = label(s);
        t.setPadding(dp(4), dp(7), 0, dp(8));
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private Button button(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(14);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackground(roundRect(bg, 15, bg, 0));
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private Button chip(String s) {
        Button b = button(s + " Hz", SURFACE_2, TEXT);
        b.setTextSize(13);
        b.setBackground(roundRect(SURFACE_2, 21, Color.rgb(50, 65, 87), 1));
        b.setPadding(dp(14), 0, dp(14), 0);
        return b;
    }

    private GradientDrawable roundRect(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private LinearLayout.LayoutParams lpMatchWrap(int bottomMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, bottomMarginDp);
        return lp;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private String formatHz(float hz) {
        if (Math.abs(hz - Math.round(hz)) < 0.005f) return String.format(Locale.US, "%.0f", hz);
        if (hz < 10f) return String.format(Locale.US, "%.2f", hz);
        return String.format(Locale.US, "%.1f", hz);
    }

    private String trimFloat(float value) {
        String s = String.format(Locale.US, "%.3f", value);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            if (s.endsWith(".")) { s = s.substring(0, s.length() - 1); break; }
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}

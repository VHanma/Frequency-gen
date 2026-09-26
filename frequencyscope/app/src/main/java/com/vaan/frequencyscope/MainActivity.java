package com.vaan.frequencyscope;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements SensorEventListener {
    static final int REQ_AUDIO = 9001;
    static final int REQ_SAVE = 9002;
    static final int FFT_N = 32768;
    static final int HOP = 8192;
    static final int SENSOR_N = 512;
    static final int TOP_N = 8;

    final double[] audioRanges = {500, 2000, 5000, 20000, Double.POSITIVE_INFINITY};
    int rangeIndex = 3;

    TextView meta, topList, status;
    FrequencyGauge gauge;
    SpectrumView spectrum;
    Spinner mode;
    Button start, freeze, range, save;

    volatile boolean frozen = false;
    AudioRecord rec;
    Thread audioThread;
    final AtomicBoolean running = new AtomicBoolean(false);
    int audioRate = 48000;
    int audioSource = MediaRecorder.AudioSource.DEFAULT;

    AcousticEchoCanceler aec;
    NoiseSuppressor ns;
    AutomaticGainControl agc;

    final double[] prevPhase = new double[FFT_N / 2];
    boolean phaseReady = false;
    double smoothedDominant = 0;
    double lastRawDominant = 0;
    int stableFrames = 0;
    int zeroInputFrames = 0;

    SensorManager sm;
    Sensor sensor;
    final ArrayList<Float> sensorValues = new ArrayList<>();
    final ArrayList<Long> sensorTimes = new ArrayList<>();

    final List<String> history = Collections.synchronizedList(new ArrayList<>());

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(3, 7, 10));
        build();
    }

    TextView tv(String s, int sp, int col) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(col);
        v.setGravity(17);
        v.setPadding(10, 10, 10, 10);
        return v;
    }

    Button bt(String s) {
        Button b = new Button(this);
        b.setText(s);
        return b;
    }

    void build() {
        int bg = Color.rgb(5, 8, 12);
        int fg = Color.rgb(230, 244, 248);
        int ac = Color.rgb(0, 255, 200);
        int mut = Color.rgb(140, 175, 185);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 16, 16, 24);
        sc.addView(root);

        root.addView(tv("FREQUENCY SCOPE v2.0", 25, ac));
        root.addView(tv("LIVE MULTI-FREQUENCY ANALYZER", 12, mut));

        mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"AUDIO Hz", "VIBRATION Hz", "MAG FIELD Hz", "GYRO Hz"}));
        root.addView(mode, new LinearLayout.LayoutParams(-1, 58));

        gauge = new FrequencyGauge(this);
        gauge.setReading(0, 20000, true, "AUDIO");
        root.addView(gauge, new LinearLayout.LayoutParams(-1, 560));

        meta = tv("Ready • tap START", 14, mut);
        root.addView(meta);

        LinearLayout row1 = new LinearLayout(this);
        start = bt("START");
        freeze = bt("FREEZE");
        range = bt("RANGE 20k");
        row1.addView(start, new LinearLayout.LayoutParams(0, 60, 1));
        row1.addView(freeze, new LinearLayout.LayoutParams(0, 60, 1));
        row1.addView(range, new LinearLayout.LayoutParams(0, 60, 1));
        root.addView(row1);

        spectrum = new SpectrumView(this);
        spectrum.setSpectrum(new float[0], 48000, 20000, true);
        root.addView(spectrum, new LinearLayout.LayoutParams(-1, 330));

        topList = tv(emptyTopList(), 18, fg);
        root.addView(topList);

        save = bt("SAVE SESSION CSV");
        root.addView(save, new LinearLayout.LayoutParams(-1, 58));

        status = tv("Idle • measured values are displayed in Hz", 12, mut);
        root.addView(status);

        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                if (running.get() || sensor != null || rec != null) return;
                setIdleScale(pos);
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        start.setOnClickListener(v -> {
            status.setText("Opening selected input…");
            meta.setText("Starting…");
            if (running.get() || sensor != null || rec != null) stop("Stopped");
            else begin();
        });

        freeze.setOnClickListener(v -> {
            frozen = !frozen;
            freeze.setText(frozen ? "UNFREEZE" : "FREEZE");
            meta.setText(frozen ? "Display frozen • capture continues" : "Live display resumed");
        });

        range.setOnClickListener(v -> {
            if (mode.getSelectedItemPosition() != 0) {
                meta.setText("Range follows the sensor sample rate");
                return;
            }
            rangeIndex = (rangeIndex + 1) % audioRanges.length;
            updateRangeButton();
            double max = selectedAudioMax(audioRate);
            if (!running.get()) {
                gauge.setReading(0, max, true, "AUDIO");
                spectrum.setSpectrum(new float[0], audioRate, max, true);
            }
        });

        save.setOnClickListener(v -> saveSession());

        setContentView(sc);
    }

    void setIdleScale(int pos) {
        if (pos == 0) {
            updateRangeButton();
            double max = selectedAudioMax(48000);
            gauge.setReading(0, max, true, "AUDIO");
            spectrum.setSpectrum(new float[0], 48000, max, true);
        } else {
            range.setText("AUTO RANGE");
            String label = sensorLabelForMode(pos);
            gauge.setReading(0, 100, false, label);
            spectrum.setSpectrum(new float[0], 200, 100, false);
        }
    }

    String emptyTopList() {
        StringBuilder b = new StringBuilder("TOP 8 FREQUENCIES\n\n");
        for (int i = 1; i <= TOP_N; i++) {
            b.append(i).append(". — Hz");
            if (i < TOP_N) b.append('\n');
        }
        return b.toString();
    }

    void updateRangeButton() {
        double r = audioRanges[rangeIndex];
        if (Double.isInfinite(r)) range.setText("RANGE MAX");
        else if (r >= 1000) range.setText(String.format(Locale.US, "RANGE %.0fk", r / 1000.0));
        else range.setText(String.format(Locale.US, "RANGE %.0f", r));
    }

    double selectedAudioMax(double rate) {
        double nyquist = Math.max(100, rate / 2.0 - rate / FFT_N);
        double selected = audioRanges[rangeIndex];
        return Double.isInfinite(selected) ? nyquist : Math.min(selected, nyquist);
    }

    void begin() {
        history.clear();
        smoothedDominant = 0;
        lastRawDominant = 0;
        stableFrames = 0;
        if (mode.getSelectedItemPosition() == 0) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                status.setText("Microphone permission required • requesting…");
                meta.setText("Grant microphone permission");
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
                return;
            }
            startAudio();
        } else {
            int p = mode.getSelectedItemPosition();
            int type = p == 1 ? Sensor.TYPE_ACCELEROMETER : p == 2 ? Sensor.TYPE_MAGNETIC_FIELD : Sensor.TYPE_GYROSCOPE;
            startSensor(type);
        }
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r != REQ_AUDIO) return;
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            status.setText("Microphone granted • opening input…");
            startAudio();
        } else {
            status.setText("MIC PERMISSION DENIED • allow Microphone in App info → Permissions");
            meta.setText("Audio permission blocked");
            start.setText("START");
        }
    }

    void startAudio() {
        stopAudio();
        int[] rates = {96000, 48000, 44100, 32000};
        int[] sources = {
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
        };
        Exception last = null;

        for (int rate : rates) {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) continue;
            for (int source : sources) {
                AudioRecord a = null;
                try {
                    a = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, FFT_N * 2));
                    if (a.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("input not initialized");
                    a.startRecording();
                    if (a.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("input did not start");
                    rec = a;
                    audioRate = rate;
                    audioSource = source;
                    disableInputEffects(a.getAudioSessionId());
                    launchAudio(a, rate, source);
                    return;
                } catch (Exception e) {
                    last = e;
                    if (a != null) try { a.release(); } catch (Exception ignored) {}
                }
            }
        }

        String why = last == null ? "no compatible audio input" : String.valueOf(last.getMessage());
        status.setText("MIC OPEN FAILED • " + why);
        meta.setText("Try a sensor mode to verify the analyzer");
        start.setText("START");
    }

    void disableInputEffects(int sessionId) {
        releaseEffects();
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId);
                if (agc != null) agc.setEnabled(false);
            }
        } catch (Exception ignored) {}
        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId);
                if (ns != null) ns.setEnabled(false);
            }
        } catch (Exception ignored) {}
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId);
                if (aec != null) aec.setEnabled(false);
            }
        } catch (Exception ignored) {}
    }

    void launchAudio(final AudioRecord localRec, final int rate, final int source) {
        running.set(true);
        phaseReady = false;
        zeroInputFrames = 0;
        Arrays.fill(prevPhase, 0);
        start.setText("STOP");
        updateRangeButton();
        double max = selectedAudioMax(rate);
        gauge.setReading(0, max, true, "AUDIO");
        spectrum.setSpectrum(new float[0], rate, max, true);
        status.setText("LIVE AUDIO • " + sourceName(source) + " • input " + rate + " Hz • FFT " + FFT_N);
        meta.setText("Collecting first frequency frame…");

        audioThread = new Thread(() -> {
            short[] frame = new short[FFT_N];
            if (!readFully(localRec, frame, 0, FFT_N)) return;
            analyzeAudio(frame, rate);

            short[] hop = new short[HOP];
            while (running.get()) {
                if (!readFully(localRec, hop, 0, HOP)) return;
                System.arraycopy(frame, HOP, frame, 0, FFT_N - HOP);
                System.arraycopy(hop, 0, frame, FFT_N - HOP, HOP);
                analyzeAudio(frame, rate);
            }
        }, "FrequencyScope-Audio");
        audioThread.start();
    }

    boolean readFully(AudioRecord localRec, short[] dst, int off, int len) {
        int got = 0;
        while (got < len && running.get()) {
            int x;
            try {
                x = localRec.read(dst, off + got, len - got, AudioRecord.READ_BLOCKING);
            } catch (Exception e) {
                if (running.get()) fail("Audio read failed: " + e.getMessage());
                return false;
            }
            if (x > 0) got += x;
            else if (x < 0) {
                if (running.get()) fail("Audio read error " + x);
                return false;
            }
        }
        return got == len && running.get();
    }

    String sourceName(int s) {
        if (s == MediaRecorder.AudioSource.UNPROCESSED) return "UNPROCESSED";
        if (s == MediaRecorder.AudioSource.CAMCORDER) return "CAMCORDER";
        if (s == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE RECOGNITION";
        if (s == MediaRecorder.AudioSource.MIC) return "MIC";
        return "DEFAULT";
    }

    void fail(String s) {
        runOnUiThread(() -> {
            if (!running.get()) return;
            status.setText(s);
            meta.setText("Scanner stopped");
            running.set(false);
            start.setText("START");
        });
    }

    void analyzeAudio(short[] s, int rate) {
        double[] r = new double[FFT_N];
        double[] im = new double[FFT_N];
        double mean = 0;
        long abs = 0;
        for (short x : s) {
            mean += x;
            abs += Math.abs((int)x);
        }
        mean /= s.length;
        double avgAbs = abs / (double)s.length;
        zeroInputFrames = avgAbs < 1.0 ? zeroInputFrames + 1 : 0;

        for (int i = 0; i < FFT_N; i++) {
            double win = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_N - 1));
            r[i] = ((s[i] - mean) / 32768.0) * win;
        }
        FFT.run(r, im);

        float[] score = new float[FFT_N / 2];
        double[] phase = new double[FFT_N / 2];
        for (int i = 1; i < score.length; i++) {
            score[i] = (float)Math.log10(Math.hypot(r[i], im[i]) + 1e-15);
            phase[i] = Math.atan2(im[i], r[i]);
        }

        double maxHz = selectedAudioMax(rate);
        if (zeroInputFrames >= 3) {
            runOnUiThread(() -> {
                if (!frozen) {
                    meta.setText("No live microphone samples detected");
                    topList.setText(emptyTopList());
                    gauge.setReading(0, maxHz, true, "AUDIO");
                    spectrum.setSpectrum(score, rate, maxHz, true);
                }
                status.setText("AUDIO INPUT IS ZERO • check Android microphone access or another recording app");
            });
            System.arraycopy(phase, 0, prevPhase, 0, phase.length);
            phaseReady = true;
            return;
        }

        ArrayList<Integer> chosen = choosePeaks(score, rate, 3.0, maxHz, TOP_N);
        if (chosen.isEmpty()) {
            runOnUiThread(() -> {
                if (!frozen) {
                    meta.setText("LIVE • waiting for a stable spectral peak…");
                    spectrum.setSpectrum(score, rate, maxHz, true);
                }
            });
            System.arraycopy(phase, 0, prevPhase, 0, phase.length);
            phaseReady = true;
            return;
        }

        double[] freqs = new double[TOP_N];
        for (int i = 0; i < chosen.size(); i++) {
            freqs[i] = refinedAudioFrequency(score, phase, chosen.get(i), rate);
        }

        System.arraycopy(phase, 0, prevPhase, 0, phase.length);
        phaseReady = true;
        publishResults(score, rate, maxHz, "AUDIO", true, freqs, chosen.size());
    }

    ArrayList<Integer> choosePeaks(float[] score, double rate, double minHz, double maxHz, int limit) {
        int n = score.length * 2;
        int lo = Math.max(2, (int)Math.floor(minHz * n / rate));
        int hi = Math.min(score.length - 3, (int)Math.ceil(maxHz * n / rate));
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i = lo; i <= hi; i++) {
            if (score[i] > score[i - 1] && score[i] >= score[i + 1]) candidates.add(i);
        }
        candidates.sort((a, b) -> Float.compare(score[b], score[a]));

        ArrayList<Integer> chosen = new ArrayList<>();
        for (int q : candidates) {
            boolean separate = true;
            for (int old : chosen) {
                if (Math.abs(q - old) < 2) {
                    separate = false;
                    break;
                }
            }
            if (separate) chosen.add(q);
            if (chosen.size() == limit) break;
        }
        return chosen;
    }

    double refinedAudioFrequency(float[] score, double[] phase, int k, double rate) {
        double parabolic = parabolicFrequency(score, k, rate);
        if (!phaseReady || k <= 0 || k >= phase.length) return parabolic;

        double expected = 2.0 * Math.PI * k * HOP / FFT_N;
        double residual = wrapPi(phase[k] - prevPhase[k] - expected);
        double trueBin = k + residual * FFT_N / (2.0 * Math.PI * HOP);
        double phaseHz = trueBin * rate / FFT_N;
        double binHz = rate / (double)FFT_N;
        if (Math.abs(phaseHz - parabolic) <= Math.max(2.5 * binHz, 0.5)) {
            return phaseHz * 0.80 + parabolic * 0.20;
        }
        return parabolic;
    }

    double parabolicFrequency(float[] score, int k, double rate) {
        if (k <= 0 || k >= score.length - 1) return k * rate / (score.length * 2.0);
        double y1 = score[k - 1], y2 = score[k], y3 = score[k + 1];
        double den = y1 - 2 * y2 + y3;
        double delta = Math.abs(den) < 1e-12 ? 0 : 0.5 * (y1 - y3) / den;
        delta = Math.max(-0.5, Math.min(0.5, delta));
        return (k + delta) * rate / (score.length * 2.0);
    }

    double wrapPi(double x) {
        while (x > Math.PI) x -= 2.0 * Math.PI;
        while (x < -Math.PI) x += 2.0 * Math.PI;
        return x;
    }

    void publishResults(float[] score, double rate, double maxHz, String label, boolean logScale,
                        double[] freqs, int count) {
        if (count <= 0) return;
        double dominant = freqs[0];
        double tolerance = Math.max(rate / FFT_N * 2.0, Math.max(0.15, dominant * 0.0015));
        if (lastRawDominant > 0 && Math.abs(dominant - lastRawDominant) <= tolerance) stableFrames++;
        else stableFrames = 0;
        lastRawDominant = dominant;

        if (smoothedDominant <= 0 || Math.abs(dominant - smoothedDominant) > Math.max(5.0, dominant * 0.03)) {
            smoothedDominant = dominant;
        } else {
            smoothedDominant = smoothedDominant * 0.65 + dominant * 0.35;
        }

        final double displayDominant = stableFrames >= 2 ? smoothedDominant : dominant;
        final String list = formatTopList(freqs, count);
        appendHistory(label, rate, maxHz, freqs, count);

        runOnUiThread(() -> {
            if (frozen) return;
            gauge.setReading(displayDominant, maxHz, logScale, label);
            topList.setText(list);
            spectrum.setSpectrum(score, rate, maxHz, logScale);
            meta.setText(String.format(Locale.US, "DOMINANT %s • %s",
                    formatHz(displayDominant), stableFrames >= 2 ? "LOCKED" : "TRACKING"));
        });
    }

    String formatTopList(double[] freqs, int count) {
        StringBuilder b = new StringBuilder("TOP 8 FREQUENCIES\n\n");
        for (int i = 0; i < TOP_N; i++) {
            b.append(i + 1).append(". ");
            if (i < count) b.append(formatHz(freqs[i]));
            else b.append("— Hz");
            if (i < TOP_N - 1) b.append('\n');
        }
        return b.toString();
    }

    String formatHz(double f) {
        if (f < 1000) return String.format(Locale.US, "%.3f Hz", f);
        return String.format(Locale.US, "%.2f Hz", f);
    }

    void appendHistory(String label, double rate, double maxHz, double[] freqs, int count) {
        StringBuilder b = new StringBuilder();
        b.append(System.currentTimeMillis()).append(',').append(label).append(',');
        b.append(String.format(Locale.US, "%.6f", rate)).append(',');
        b.append(String.format(Locale.US, "%.6f", maxHz));
        for (int i = 0; i < TOP_N; i++) {
            b.append(',');
            if (i < count) b.append(String.format(Locale.US, "%.6f", freqs[i]));
        }
        history.add(b.toString());
        if (history.size() > 20000) history.remove(0);
    }

    void startSensor(int type) {
        sm = (SensorManager)getSystemService(SENSOR_SERVICE);
        sensor = sm.getDefaultSensor(type);
        if (sensor == null) {
            status.setText("Selected sensor is not present on this phone.");
            meta.setText("Choose another mode");
            sensor = null;
            return;
        }
        sensorValues.clear();
        sensorTimes.clear();
        boolean ok;
        try {
            ok = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        } catch (SecurityException e) {
            status.setText("High-rate sensor start blocked: " + e.getMessage());
            sensor = null;
            return;
        }
        if (!ok) {
            status.setText("Android did not start the selected sensor.");
            sensor = null;
            return;
        }
        start.setText("STOP");
        String label = sensorLabel(type);
        range.setText("AUTO RANGE");
        gauge.setReading(0, 100, false, label);
        spectrum.setSpectrum(new float[0], 200, 100, false);
        status.setText("LIVE " + label + " • using sensor event timestamps");
        meta.setText("Collecting 0 / " + SENSOR_N + " samples…");
    }

    @Override public void onSensorChanged(SensorEvent e) {
        if (sensor == null || e.sensor.getType() != sensor.getType()) return;
        float v = (float)Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        sensorValues.add(v);
        sensorTimes.add(e.timestamp);

        if (sensorValues.size() % 64 == 0 && sensorValues.size() < SENSOR_N) {
            final int count = sensorValues.size();
            runOnUiThread(() -> meta.setText("Collecting " + count + " / " + SENSOR_N + " samples…"));
        }

        if (sensorValues.size() >= SENSOR_N) analyzeSensorWindow(e.sensor.getType());
    }

    void analyzeSensorWindow(int type) {
        if (sensorTimes.size() < SENSOR_N) return;
        long t0 = sensorTimes.get(0);
        long t1 = sensorTimes.get(SENSOR_N - 1);
        if (t1 <= t0) {
            sensorValues.clear();
            sensorTimes.clear();
            return;
        }

        double rate = (SENSOR_N - 1) * 1e9 / (double)(t1 - t0);
        double[] uniform = resampleSensor(t0, t1);
        double mean = 0;
        for (double x : uniform) mean += x;
        mean /= uniform.length;

        double[] r = new double[SENSOR_N];
        double[] im = new double[SENSOR_N];
        for (int i = 0; i < SENSOR_N; i++) {
            double win = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (SENSOR_N - 1));
            r[i] = (uniform[i] - mean) * win;
        }
        FFT.run(r, im);

        float[] score = new float[SENSOR_N / 2];
        for (int i = 1; i < score.length; i++) score[i] = (float)Math.log10(Math.hypot(r[i], im[i]) + 1e-15);

        double maxHz = Math.max(1, rate / 2.0);
        ArrayList<Integer> chosen = choosePeaks(score, rate, Math.max(0.05, rate / SENSOR_N), maxHz, TOP_N);
        double[] freqs = new double[TOP_N];
        for (int i = 0; i < chosen.size(); i++) freqs[i] = parabolicFrequency(score, chosen.get(i), rate);

        String label = sensorLabel(type);
        sensorValues.clear();
        sensorTimes.clear();

        if (chosen.isEmpty()) {
            runOnUiThread(() -> {
                if (!frozen) {
                    meta.setText("LIVE • waiting for a stable sensor frequency…");
                    spectrum.setSpectrum(score, rate, maxHz, false);
                }
            });
            return;
        }

        statusOnUi("LIVE " + label + " • measured sample rate " + String.format(Locale.US, "%.2f Hz", rate));
        publishResults(score, rate, maxHz, label, false, freqs, chosen.size());
    }

    double[] resampleSensor(long t0, long t1) {
        double[] out = new double[SENSOR_N];
        int j = 0;
        for (int i = 0; i < SENSOR_N; i++) {
            double target = t0 + (t1 - t0) * i / (double)(SENSOR_N - 1);
            while (j + 1 < SENSOR_N && sensorTimes.get(j + 1) < target) j++;
            if (j + 1 >= SENSOR_N) {
                out[i] = sensorValues.get(SENSOR_N - 1);
            } else {
                long ta = sensorTimes.get(j);
                long tb = sensorTimes.get(j + 1);
                double a = sensorValues.get(j);
                double b = sensorValues.get(j + 1);
                double p = tb == ta ? 0 : (target - ta) / (double)(tb - ta);
                p = Math.max(0, Math.min(1, p));
                out[i] = a + (b - a) * p;
            }
        }
        return out;
    }

    void statusOnUi(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    String sensorLabelForMode(int pos) {
        if (pos == 1) return "VIBRATION";
        if (pos == 2) return "MAG FIELD";
        return "GYRO";
    }

    String sensorLabel(int type) {
        if (type == Sensor.TYPE_ACCELEROMETER) return "VIBRATION";
        if (type == Sensor.TYPE_MAGNETIC_FIELD) return "MAG FIELD";
        return "GYRO";
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    void saveSession() {
        if (history.isEmpty()) {
            meta.setText("No captured frequency frames to save yet");
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "FrequencyScope_" + stamp + ".csv");
        startActivityForResult(i, REQ_SAVE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("No output stream");
            String header = "time_ms,mode,sample_rate_hz,range_max_hz,f1_hz,f2_hz,f3_hz,f4_hz,f5_hz,f6_hz,f7_hz,f8_hz\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            synchronized (history) {
                for (String line : history) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                }
            }
            meta.setText("Session CSV saved");
        } catch (Exception e) {
            meta.setText("Save failed: " + e.getMessage());
        }
    }

    void releaseEffects() {
        try { if (aec != null) aec.release(); } catch (Exception ignored) {}
        try { if (ns != null) ns.release(); } catch (Exception ignored) {}
        try { if (agc != null) agc.release(); } catch (Exception ignored) {}
        aec = null;
        ns = null;
        agc = null;
    }

    void stopAudio() {
        running.set(false);
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) {}
            try { rec.release(); } catch (Exception ignored) {}
            rec = null;
        }
        releaseEffects();
        phaseReady = false;
    }

    void stop(String why) {
        stopAudio();
        if (sm != null) sm.unregisterListener(this);
        sensor = null;
        sensorValues.clear();
        sensorTimes.clear();
        start.setText("START");
        status.setText(why + " • " + history.size() + " captured frames available for CSV");
        meta.setText("Ready");
    }

    @Override protected void onDestroy() {
        stop("Stopped");
        super.onDestroy();
    }
}

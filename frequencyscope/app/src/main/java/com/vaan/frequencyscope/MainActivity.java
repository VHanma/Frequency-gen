package com.vaan.frequencyscope;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements SensorEventListener {
    static final int REQ = 9001;
    static final int N = 16384;
    static final int SN = 256;

    TextView meta, top3, status;
    FrequencyGauge gauge;
    Spinner mode;
    Button start, freeze;

    volatile boolean frozen = false;
    AudioRecord rec;
    Thread audioThread;
    final AtomicBoolean running = new AtomicBoolean(false);

    SensorManager sm;
    Sensor sensor;
    final ArrayList<Float> sensorValues = new ArrayList<>();
    long sensorT0;

    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(3,7,10));
        build();
    }

    TextView tv(String s, int sp, int col) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(col);
        v.setGravity(17);
        v.setPadding(10,10,10,10);
        return v;
    }

    Button bt(String s) {
        Button b = new Button(this);
        b.setText(s);
        return b;
    }

    void build() {
        int bg = Color.rgb(5,8,12);
        int fg = Color.rgb(230,244,248);
        int ac = Color.rgb(0,255,200);
        int mut = Color.rgb(140,175,185);

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16,16,16,24);
        sc.addView(root);

        root.addView(tv("FREQUENCY SCOPE v1.2",25,ac));
        root.addView(tv("DOMINANT FREQUENCY SPEEDOMETER",12,mut));

        mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"AUDIO Hz","VIBRATION Hz","MAG FIELD Hz"}));
        root.addView(mode,new LinearLayout.LayoutParams(-1,58));

        gauge = new FrequencyGauge(this);
        gauge.setReading(0,20000,true,"AUDIO");
        root.addView(gauge,new LinearLayout.LayoutParams(-1,620));

        meta = tv("Ready • tap START",14,mut);
        root.addView(meta);

        LinearLayout row = new LinearLayout(this);
        start = bt("START");
        freeze = bt("FREEZE");
        row.addView(start,new LinearLayout.LayoutParams(0,60,1));
        row.addView(freeze,new LinearLayout.LayoutParams(0,60,1));
        root.addView(row);

        top3 = tv("TOP 3 DOMINANT FREQUENCIES\n\n1. — Hz\n2. — Hz\n3. — Hz",20,fg);
        root.addView(top3);

        status = tv("Idle • all displayed measurements are Hz",12,mut);
        root.addView(status);

        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id){
                if (running.get() || sensor != null || rec != null) return;
                if (pos == 0) gauge.setReading(0,20000,true,"AUDIO");
                else gauge.setReading(0,100,false,pos == 1 ? "VIBRATION" : "MAG FIELD");
            }
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });

        start.setOnClickListener(v -> {
            status.setText("START pressed • opening selected input…");
            meta.setText("Starting…");
            if (running.get() || sensor != null || rec != null) stop("Stopped");
            else begin();
        });

        freeze.setOnClickListener(v -> {
            frozen = !frozen;
            freeze.setText(frozen ? "UNFREEZE" : "FREEZE");
            meta.setText(frozen ? "Reading frozen" : "Live reading resumed");
        });

        setContentView(sc);
    }

    void begin() {
        int m = mode.getSelectedItemPosition();
        if (m == 0) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                status.setText("Microphone permission required • requesting…");
                meta.setText("Grant microphone permission");
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ);
                return;
            }
            startAudio();
        } else {
            startSensor(m == 1 ? Sensor.TYPE_ACCELEROMETER : Sensor.TYPE_MAGNETIC_FIELD);
        }
    }

    public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r != REQ) return;
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            status.setText("Microphone granted • opening input…");
            startAudio();
        } else {
            status.setText("MIC PERMISSION DENIED • allow Microphone in App info → Permissions, or use another mode.");
            meta.setText("Audio permission blocked");
            start.setText("START");
        }
    }

    void startAudio() {
        stopAudio();
        int[] rates = {48000,44100,32000};
        int[] sources = {
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
        };
        Exception last = null;

        for (int rate : rates) {
            int min = AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) continue;
            for (int source : sources) {
                AudioRecord a = null;
                try {
                    a = new AudioRecord(source,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,N*2));
                    if (a.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("input not initialized");
                    a.startRecording();
                    if (a.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("input did not start");
                    rec = a;
                    launchAudio(rate,source);
                    return;
                } catch (Exception e) {
                    last = e;
                    if (a != null) try { a.release(); } catch (Exception ignored) {}
                }
            }
        }

        String why = last == null ? "no compatible audio input" : String.valueOf(last.getMessage());
        status.setText("MIC OPEN FAILED • " + why);
        meta.setText("Try VIBRATION Hz to test scanner");
        start.setText("START");
    }

    void launchAudio(final int rate, final int source) {
        running.set(true);
        start.setText("STOP");
        gauge.setReading(0,Math.min(20000,rate/2.0),true,"AUDIO");
        status.setText("LIVE AUDIO • " + sourceName(source) + " • finding dominant Hz");
        meta.setText("Collecting first frequency frame…");

        audioThread = new Thread(() -> {
            short[] s = new short[N];
            while (running.get()) {
                int got = 0;
                while (got < N && running.get()) {
                    int x;
                    try { x = rec.read(s,got,N-got,AudioRecord.READ_BLOCKING); }
                    catch (Exception e) { fail("Audio read failed: " + e.getMessage()); return; }
                    if (x > 0) got += x;
                    else if (x < 0) { fail("Audio read error " + x); return; }
                }
                if (got == N) analyzeAudio(s,rate);
            }
        },"FrequencyScope-Audio");
        audioThread.start();
    }

    String sourceName(int s) {
        if (s == MediaRecorder.AudioSource.UNPROCESSED) return "UNPROCESSED";
        if (s == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE RECOGNITION";
        if (s == MediaRecorder.AudioSource.MIC) return "MIC";
        return "DEFAULT";
    }

    void fail(String s) {
        runOnUiThread(() -> {
            status.setText(s);
            meta.setText("Scanner stopped");
            running.set(false);
            start.setText("START");
        });
    }

    void analyzeAudio(short[] s, int rate) {
        double[] r = new double[N], im = new double[N];
        double mean = 0;
        for (short x : s) mean += x;
        mean /= s.length;
        for (int i=0;i<N;i++) {
            double win = .5 - .5*Math.cos(2*Math.PI*i/(N-1));
            r[i] = ((s[i]-mean)/32768.0)*win;
        }
        FFT.run(r,im);
        float[] score = new float[N/2];
        for (int i=1;i<score.length;i++) score[i] = (float)Math.log10(Math.hypot(r[i],im[i]) + 1e-15);
        showDominants(score,rate,Math.min(20000,rate/2.0),"AUDIO",true);
    }

    void showDominants(float[] score, double rate, double maxHz, String label, boolean logGauge) {
        int lo = Math.max(1,(int)(15*score.length*2/rate));
        int hi = Math.min(score.length-2,(int)(maxHz*score.length*2/rate));
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i=lo;i<=hi;i++) if (score[i] > score[i-1] && score[i] >= score[i+1]) candidates.add(i);
        candidates.sort((a,b) -> Float.compare(score[b],score[a]));

        ArrayList<Integer> chosen = new ArrayList<>();
        int minGapBins = rate > 1000 ? 5 : 2;
        for (int q : candidates) {
            boolean separate = true;
            for (int old : chosen) if (Math.abs(q-old) < minGapBins) { separate = false; break; }
            if (separate) chosen.add(q);
            if (chosen.size() == 3) break;
        }

        if (chosen.isEmpty()) {
            runOnUiThread(() -> meta.setText("LIVE • waiting for a stable frequency…"));
            return;
        }

        double[] freqs = new double[3];
        for (int i=0;i<chosen.size();i++) freqs[i] = refinedFrequency(score,chosen.get(i),rate);
        double dominant = freqs[0];

        StringBuilder list = new StringBuilder("TOP 3 DOMINANT FREQUENCIES\n\n");
        for (int i=0;i<3;i++) {
            if (i < chosen.size()) list.append(String.format(Locale.US,"%d. %.2f Hz",i+1,freqs[i]));
            else list.append((i+1)+". — Hz");
            if (i < 2) list.append("\n");
        }

        runOnUiThread(() -> {
            if (frozen) return;
            gauge.setReading(dominant,maxHz,logGauge,label);
            top3.setText(list.toString());
            meta.setText(String.format(Locale.US,"DOMINANT %.2f Hz • live",dominant));
        });
    }

    double refinedFrequency(float[] score, int k, double rate) {
        double y1 = score[k-1], y2 = score[k], y3 = score[k+1];
        double den = y1 - 2*y2 + y3;
        double delta = Math.abs(den) < 1e-12 ? 0 : .5*(y1-y3)/den;
        delta = Math.max(-.5,Math.min(.5,delta));
        return (k+delta)*rate/(score.length*2.0);
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
        sensorT0 = System.nanoTime();
        boolean ok = sm.registerListener(this,sensor,SensorManager.SENSOR_DELAY_FASTEST);
        if (!ok) {
            status.setText("Android did not start the selected sensor.");
            sensor = null;
            return;
        }
        start.setText("STOP");
        String label = type == Sensor.TYPE_ACCELEROMETER ? "VIBRATION" : "MAG FIELD";
        gauge.setReading(0,100,false,label);
        status.setText("LIVE " + label + " • finding dominant Hz");
        meta.setText("Collecting 0 / " + SN + " samples…");
    }

    public void onSensorChanged(SensorEvent e) {
        float v = (float)Math.sqrt(e.values[0]*e.values[0] + e.values[1]*e.values[1] + e.values[2]*e.values[2]);
        sensorValues.add(v);
        if (sensorValues.size()%32 == 0 && sensorValues.size() < SN) {
            final int count = sensorValues.size();
            runOnUiThread(() -> meta.setText("Collecting " + count + " / " + SN + " samples…"));
        }
        if (sensorValues.size() >= SN) {
            long now = System.nanoTime();
            double rate = sensorValues.size()/((now-sensorT0)/1e9);
            double[] r = new double[SN], im = new double[SN];
            double mean = 0;
            for (float x : sensorValues) mean += x;
            mean /= sensorValues.size();
            for (int i=0;i<SN;i++) r[i] = (sensorValues.get(i)-mean)*(.5-.5*Math.cos(2*Math.PI*i/(SN-1)));
            FFT.run(r,im);
            float[] score = new float[SN/2];
            for (int i=1;i<score.length;i++) score[i] = (float)Math.log10(Math.hypot(r[i],im[i]) + 1e-15);
            sensorValues.clear();
            sensorT0 = now;
            String label = e.sensor.getType() == Sensor.TYPE_ACCELEROMETER ? "VIBRATION" : "MAG FIELD";
            showDominants(score,rate,rate/2,label,false);
        }
    }

    public void onAccuracyChanged(Sensor s, int a) {}

    void stopAudio() {
        running.set(false);
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) {}
            try { rec.release(); } catch (Exception ignored) {}
            rec = null;
        }
    }

    void stop(String why) {
        stopAudio();
        if (sm != null) sm.unregisterListener(this);
        sensor = null;
        sensorValues.clear();
        start.setText("START");
        status.setText(why);
        meta.setText("Ready");
    }

    protected void onDestroy() {
        stop("Stopped");
        super.onDestroy();
    }
}

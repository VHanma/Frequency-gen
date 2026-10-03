package com.vhanma.ethership;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int TARGET_SR = 96000;
    private static final double TWO_PI = Math.PI * 2.0;

    private final List<Preset> presets = new ArrayList<>();
    private final List<AudioDeviceInfo> outputDevices = new ArrayList<>();

    private Spinner presetSpinner;
    private Spinner outputSpinner;
    private TextView presetInfo;
    private TextView status;
    private TextView octaveReadout;
    private TextView gainReadout;
    private TextView modReadout;
    private EditText customFreqs;

    private SeekBar gainBar;
    private SeekBar masterModBar;
    private SeekBar octaveBar;
    private CheckBox manualToneCheck;

    private volatile Preset activePreset;
    private volatile boolean running = false;
    private volatile float masterGain = 0.18f;
    private volatile float masterModRate = 0.0f;
    private volatile double manualFreq = 444.0;
    private volatile boolean manualToneEnabled = false;

    private AudioTrack audioTrack;
    private Thread audioThread;
    private AudioDeviceInfo selectedDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildPresets();
        buildUi();
        refreshAudioDevices();
        selectPreset(0);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("ETHER SHIP");
        title.setTextSize(30f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Magnetic Mind Generator • xenolinguistic frequency console");
        sub.setGravity(Gravity.CENTER);
        sub.setTextSize(14f);
        root.addView(sub);

        spacer(root, 14);

        label(root, "PRESET BANK");
        presetSpinner = new Spinner(this);
        String[] names = new String[presets.size()];
        for (int i = 0; i < presets.size(); i++) names[i] = presets.get(i).name;
        presetSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        root.addView(presetSpinner);
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectPreset(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        presetInfo = new TextView(this);
        presetInfo.setTextSize(14f);
        presetInfo.setPadding(0, dp(8), 0, dp(8));
        root.addView(presetInfo);

        label(root, "OUTPUT DEVICE");
        outputSpinner = new Spinner(this);
        root.addView(outputSpinner);
        outputSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < outputDevices.size()) selectedDevice = outputDevices.get(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        Button refresh = new Button(this);
        refresh.setText("REFRESH OUTPUTS");
        refresh.setOnClickListener(v -> refreshAudioDevices());
        root.addView(refresh);

        spacer(root, 10);
        label(root, "MASTER GAIN");
        gainReadout = new TextView(this);
        root.addView(gainReadout);
        gainBar = new SeekBar(this);
        gainBar.setMax(100);
        gainBar.setProgress(18);
        root.addView(gainBar);
        gainBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                masterGain = progress / 100f;
                gainReadout.setText(String.format(Locale.US, "%.0f%%", masterGain * 100f));
            }
        });
        gainReadout.setText("18%");

        label(root, "MASTER AM / BRAINWAVE MODULATION");
        modReadout = new TextView(this);
        root.addView(modReadout);
        masterModBar = new SeekBar(this);
        masterModBar.setMax(400);
        masterModBar.setProgress(0);
        root.addView(masterModBar);
        masterModBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                masterModRate = progress / 10f;
                modReadout.setText(String.format(Locale.US, "%.1f Hz", masterModRate));
            }
        });
        modReadout.setText("0.0 Hz");

        spacer(root, 10);
        label(root, "20-OCTAVE CONTROL");
        octaveReadout = new TextView(this);
        root.addView(octaveReadout);
        octaveBar = new SeekBar(this);
        octaveBar.setMax(2000);
        octaveBar.setProgress(1344);
        root.addView(octaveBar);
        octaveBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                double octaves = progress / 100.0;
                manualFreq = 0.04 * Math.pow(2.0, octaves);
                octaveReadout.setText(String.format(Locale.US, "%.2f oct • %.3f Hz", octaves, manualFreq));
            }
        });
        manualToneCheck = new CheckBox(this);
        manualToneCheck.setText("Mix 20-octave control tone into output");
        manualToneCheck.setOnCheckedChangeListener((buttonView, isChecked) -> manualToneEnabled = isChecked);
        root.addView(manualToneCheck);

        spacer(root, 10);
        label(root, "CUSTOM FREQUENCY MIX");
        customFreqs = new EditText(this);
        customFreqs.setHint("Example: 444, 888, 1776, 19200");
        customFreqs.setSingleLine(false);
        root.addView(customFreqs);

        Button applyCustom = new Button(this);
        applyCustom.setText("LOAD CUSTOM MIX");
        applyCustom.setOnClickListener(v -> loadCustomMix());
        root.addView(applyCustom);

        spacer(root, 14);
        Button startStop = new Button(this);
        startStop.setText("START TRANSMISSION");
        startStop.setTextSize(18f);
        root.addView(startStop);
        startStop.setOnClickListener(v -> {
            if (running) {
                stopAudio();
                startStop.setText("START TRANSMISSION");
            } else {
                startAudio();
                startStop.setText("STOP TRANSMISSION");
            }
        });

        status = new TextView(this);
        status.setPadding(0, dp(12), 0, 0);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        spacer(root, 16);
        TextView notes = new TextView(this);
        notes.setText(
                "ARCHIVE MODE\n" +
                "The historically documented Ether Ship chain included a Synthi AKS, Hammond organ, " +
                "brain-wave analyzer, color-linked sound control, magnetic/laser thought-tunnel concepts, " +
                "a 24-channel equalizer, and a reported 20-octave control system. Exact Hz tables for named " +
                "ESP/telekinesis functions are not present in the surviving public descriptions, so presets marked " +
                "RECONSTRUCTION preserve the architecture and use editable frequency sets rather than claiming an archival setting."
        );
        notes.setTextSize(12f);
        root.addView(notes);

        setContentView(scroll);
    }

    private void buildPresets() {
        presets.add(new Preset(
                "Ether Ship Core • DOCUMENTED ARCHITECTURE",
                "Harmonic ladder + slow amplitude movement. Recreates the Synthi/Hammond-style layered control concept.",
                new Osc[]{
                        s(55, .18, -0.5), s(110, .16, 0.5), s(220, .13, -0.2),
                        s(440, .12, 0.2), s(880, .08, -0.7), s(1760, .06, 0.7)
                }, 0.33f));

        presets.add(new Preset(
                "Alien Communication • RECONSTRUCTION",
                "444-derived harmonic beacon with sub-audio AM and ultrasonic extensions.",
                new Osc[]{
                        am(444, .16, -0.5, 7.83, .45), am(888, .12, 0.5, 7.83, .40),
                        am(1776, .09, -0.2, 3.915, .35), am(3552, .07, 0.2, 1.9575, .30),
                        am(7111, .05, -0.7, 7.83, .25), am(14222, .035, 0.7, 7.83, .20),
                        am(28444, .02, 0.0, 3.915, .20)
                }, 0.22f));

        presets.add(new Preset(
                "ESP / Telepathy • RECONSTRUCTION",
                "Stacked carriers modulated in the 4–12 Hz band.",
                new Osc[]{
                        am(222, .13, -0.8, 7.0, .55), am(444, .14, 0.8, 7.0, .50),
                        am(666, .10, -0.4, 10.0, .40), am(888, .09, 0.4, 10.0, .40),
                        am(1776, .06, 0.0, 4.0, .35)
                }, 0.26f));

        presets.add(new Preset(
                "Telekinesis • RECONSTRUCTION",
                "Dense octave/power-of-two stack with phase-separated stereo movement.",
                new Osc[]{
                        am(33, .15, -1.0, 2.0, .35), am(66, .14, 1.0, 2.0, .35),
                        am(132, .12, -0.6, 4.0, .40), am(264, .11, 0.6, 4.0, .40),
                        am(528, .10, -0.3, 8.0, .30), am(1056, .08, 0.3, 8.0, .30),
                        am(2112, .05, 0.0, 16.0, .25)
                }, 0.23f));

        presets.add(new Preset(
                "Remote Viewing • RECONSTRUCTION",
                "Low harmonic bed, 7 Hz and 10 Hz amplitude modulation, wide stereo image.",
                new Osc[]{
                        am(110, .16, -0.9, 7.0, .50), am(220, .14, 0.9, 7.0, .50),
                        am(330, .10, -0.5, 10.0, .35), am(660, .08, 0.5, 10.0, .35),
                        am(1320, .05, 0.0, 5.0, .30)
                }, 0.25f));

        presets.add(new Preset(
                "Xenolinguistic Microtonal • RECONSTRUCTION",
                "Non-12TET ratio lattice built around 444 Hz.",
                new Osc[]{
                        s(444, .13, -0.8), s(499.5, .11, 0.8), s(555, .10, -0.6),
                        s(592, .09, 0.6), s(666, .08, -0.4), s(777, .07, 0.4),
                        s(888, .06, -0.2), s(999, .05, 0.2), s(1110, .04, 0.0)
                }, 0.18f));

        presets.add(new Preset(
                "Magnetic Mind Generator • RECONSTRUCTION",
                "Slow FM + AM cross-coupling intended to feel like a continuously moving analog patch.",
                new Osc[]{
                        fm(74, .14, -0.9, .17, 8), fm(148, .13, 0.9, .23, 12),
                        fm(296, .11, -0.5, .31, 16), fm(592, .09, 0.5, .41, 24),
                        fm(1184, .07, -0.2, .53, 32), fm(2368, .05, 0.2, .67, 48)
                }, 0.20f));

        presets.add(new Preset(
                "Star Net ETI Transmission • RECONSTRUCTION",
                "Stepped communication-style band from 400–1000 Hz with upper harmonic mirrors.",
                new Osc[]{
                        am(400, .10, -0.8, 1.0, .50), am(500, .10, 0.8, 1.0, .50),
                        am(600, .09, -0.6, 2.0, .45), am(700, .09, 0.6, 2.0, .45),
                        am(800, .08, -0.4, 4.0, .40), am(900, .08, 0.4, 4.0, .40),
                        am(1000, .07, 0.0, 8.0, .35), s(8000, .03, -0.2), s(16000, .02, 0.2)
                }, 0.18f));

        presets.add(new Preset(
                "Ultrasonic Contact • EXPERIMENTAL",
                "High-frequency carrier bank for external 96 kHz-capable DAC/transducer hardware.",
                new Osc[]{
                        am(18000, .04, -0.9, 7.83, .40), am(19200, .035, 0.9, 7.83, .40),
                        am(20000, .03, -0.6, 4.0, .30), am(22100, .027, 0.6, 4.0, .30),
                        am(24000, .024, -0.3, 10.0, .25), am(28000, .020, 0.3, 10.0, .25),
                        am(32000, .016, 0.0, 2.0, .20)
                }, 0.12f));

        presets.add(new Preset(
                "444 Calibration",
                "Single clean 444 Hz reference with its first four octaves.",
                new Osc[]{
                        s(444, .20, 0.0), s(888, .10, -0.4), s(1776, .06, 0.4), s(3552, .035, 0.0)
                }, 0.20f));
    }

    private static Osc s(double f, double a, double p) {
        return new Osc(f, a, p, 0, 0, 0, 0);
    }

    private static Osc am(double f, double a, double p, double rate, double depth) {
        return new Osc(f, a, p, rate, depth, 0, 0);
    }

    private static Osc fm(double f, double a, double p, double rate, double depthHz) {
        return new Osc(f, a, p, 0, 0, rate, depthHz);
    }

    private void selectPreset(int index) {
        if (index < 0 || index >= presets.size()) return;
        activePreset = presets.get(index);
        masterGain = activePreset.recommendedGain;
        if (gainBar != null) gainBar.setProgress(Math.round(masterGain * 100f));
        if (presetInfo != null) {
            StringBuilder b = new StringBuilder();
            b.append(activePreset.description).append("\n\nHz: ");
            for (int i = 0; i < activePreset.oscs.length; i++) {
                if (i > 0) b.append(", ");
                b.append(trim(activePreset.oscs[i].freq));
            }
            presetInfo.setText(b.toString());
        }
    }

    private void loadCustomMix() {
        String raw = customFreqs.getText().toString().trim();
        if (raw.isEmpty()) return;
        String[] parts = raw.split("[,;\\s]+");
        List<Osc> list = new ArrayList<>();
        for (int i = 0; i < parts.length && i < 24; i++) {
            try {
                double f = Double.parseDouble(parts[i]);
                if (f > 0) {
                    double pan = parts.length <= 1 ? 0 : -1.0 + (2.0 * i / (parts.length - 1.0));
                    list.add(s(f, Math.max(0.03, 0.22 / Math.max(1, parts.length)), pan));
                }
            } catch (Exception ignored) { }
        }
        if (list.isEmpty()) return;
        activePreset = new Preset("Custom Mix", "Live custom frequency bank.", list.toArray(new Osc[0]), masterGain);
        StringBuilder b = new StringBuilder("Custom frequencies loaded:\n");
        for (Osc o : activePreset.oscs) b.append(trim(o.freq)).append(" Hz  ");
        presetInfo.setText(b.toString());
    }

    private void refreshAudioDevices() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        outputDevices.clear();
        List<String> labels = new ArrayList<>();

        for (AudioDeviceInfo d : devices) {
            outputDevices.add(d);
            labels.add(deviceName(d));
        }
        if (labels.isEmpty()) {
            labels.add("System default output");
            outputDevices.add(null);
        }
        if (outputSpinner != null) {
            outputSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            int preferred = 0;
            for (int i = 0; i < outputDevices.size(); i++) {
                AudioDeviceInfo d = outputDevices.get(i);
                if (d != null && (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        d.getType() == AudioDeviceInfo.TYPE_LINE_ANALOG)) {
                    preferred = i;
                    break;
                }
            }
            outputSpinner.setSelection(preferred);
            selectedDevice = outputDevices.get(preferred);
        }
    }

    private String deviceName(AudioDeviceInfo d) {
        if (d == null) return "System default output";
        String product = String.valueOf(d.getProductName());
        String type;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_USB_DEVICE: type = "USB DAC"; break;
            case AudioDeviceInfo.TYPE_USB_HEADSET: type = "USB headset"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: type = "Wired headphones"; break;
            case AudioDeviceInfo.TYPE_LINE_ANALOG: type = "Analog line"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: type = "Bluetooth A2DP"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: type = "Built-in speaker"; break;
            default: type = "Output type " + d.getType();
        }
        return product + " • " + type;
    }

    private void startAudio() {
        stopAudio();

        int sr = chooseSampleRate();
        int min = AudioTrack.getMinBufferSize(sr,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = 8192;
        int bufferBytes = Math.max(min * 4, 16384);

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        try {
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            if (selectedDevice != null) audioTrack.setPreferredDevice(selectedDevice);
            audioTrack.play();
            running = true;
            final int finalSr = sr;
            audioThread = new Thread(() -> renderLoop(finalSr), "EtherShipAudio");
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.start();
            status.setText("TRANSMITTING • " + sr + " Hz PCM • stereo");
        } catch (Exception e) {
            status.setText("Audio start error: " + e.getMessage());
            stopAudio();
        }
    }

    private int chooseSampleRate() {
        int test96 = AudioTrack.getMinBufferSize(TARGET_SR,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        return test96 > 0 ? TARGET_SR : 48000;
    }

    private void renderLoop(int sr) {
        final int frames = 1024;
        short[] pcm = new short[frames * 2];
        double masterPhase = 0.0;
        double manualPhase = 0.0;

        while (running && audioTrack != null) {
            Preset p = activePreset;
            if (p == null) continue;

            for (int frame = 0; frame < frames; frame++) {
                double left = 0.0;
                double right = 0.0;

                double masterEnv = 1.0;
                if (masterModRate > 0.01f) {
                    masterPhase += TWO_PI * masterModRate / sr;
                    if (masterPhase > TWO_PI) masterPhase -= TWO_PI;
                    masterEnv = 0.62 + 0.38 * Math.sin(masterPhase);
                }

                for (Osc o : p.oscs) {
                    double f = Math.min(Math.max(0.01, o.freq), sr * 0.45);
                    if (o.fmRate > 0 && o.fmDepthHz > 0) {
                        o.fmPhase += TWO_PI * o.fmRate / sr;
                        if (o.fmPhase > TWO_PI) o.fmPhase -= TWO_PI;
                        f += Math.sin(o.fmPhase) * o.fmDepthHz;
                        f = Math.min(Math.max(0.01, f), sr * 0.45);
                    }

                    o.phase += TWO_PI * f / sr;
                    if (o.phase > TWO_PI) o.phase -= TWO_PI;

                    double amp = o.amp;
                    if (o.amRate > 0 && o.amDepth > 0) {
                        o.amPhase += TWO_PI * o.amRate / sr;
                        if (o.amPhase > TWO_PI) o.amPhase -= TWO_PI;
                        amp *= (1.0 - o.amDepth) + o.amDepth * (0.5 + 0.5 * Math.sin(o.amPhase));
                    }

                    double s = Math.sin(o.phase) * amp;
                    double lGain = Math.sqrt((1.0 - o.pan) * 0.5);
                    double rGain = Math.sqrt((1.0 + o.pan) * 0.5);
                    left += s * lGain;
                    right += s * rGain;
                }

                if (manualToneEnabled) {
                    double f = Math.min(Math.max(0.01, manualFreq), sr * 0.45);
                    manualPhase += TWO_PI * f / sr;
                    if (manualPhase > TWO_PI) manualPhase -= TWO_PI;
                    double m = Math.sin(manualPhase) * 0.08;
                    left += m;
                    right += m;
                }

                left = softClip(left * masterGain * masterEnv);
                right = softClip(right * masterGain * masterEnv);
                pcm[frame * 2] = (short) Math.round(left * 32767.0);
                pcm[frame * 2 + 1] = (short) Math.round(right * 32767.0);
            }

            int written = audioTrack.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
            if (written < 0) break;
        }
    }

    private static double softClip(double x) {
        return Math.tanh(x);
    }

    private void stopAudio() {
        running = false;
        Thread t = audioThread;
        audioThread = null;
        if (t != null) {
            try { t.join(250); } catch (InterruptedException ignored) { }
        }
        if (audioTrack != null) {
            try { audioTrack.pause(); } catch (Exception ignored) { }
            try { audioTrack.flush(); } catch (Exception ignored) { }
            try { audioTrack.stop(); } catch (Exception ignored) { }
            try { audioTrack.release(); } catch (Exception ignored) { }
            audioTrack = null;
        }
        if (status != null) status.setText("IDLE");
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopAudio();
    }

    private void label(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setPadding(0, dp(10), 0, dp(4));
        root.addView(t);
    }

    private void spacer(LinearLayout root, int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        root.addView(v);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private static String trim(double n) {
        if (Math.abs(n - Math.rint(n)) < 0.0001) return String.format(Locale.US, "%.0f", n);
        return String.format(Locale.US, "%.3f", n);
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) { }
    }

    private static class Preset {
        final String name;
        final String description;
        final Osc[] oscs;
        final float recommendedGain;

        Preset(String name, String description, Osc[] oscs, float recommendedGain) {
            this.name = name;
            this.description = description;
            this.oscs = oscs;
            this.recommendedGain = recommendedGain;
        }
    }

    private static class Osc {
        final double freq;
        final double amp;
        final double pan;
        final double amRate;
        final double amDepth;
        final double fmRate;
        final double fmDepthHz;
        double phase;
        double amPhase;
        double fmPhase;

        Osc(double freq, double amp, double pan,
            double amRate, double amDepth, double fmRate, double fmDepthHz) {
            this.freq = freq;
            this.amp = amp;
            this.pan = Math.max(-1.0, Math.min(1.0, pan));
            this.amRate = amRate;
            this.amDepth = amDepth;
            this.fmRate = fmRate;
            this.fmDepthHz = fmDepthHz;
        }
    }
}

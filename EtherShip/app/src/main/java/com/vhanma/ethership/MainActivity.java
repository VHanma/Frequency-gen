package com.vhanma.ethership;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Build;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final double TWO_PI = Math.PI * 2.0;
    private static final int REQUEST_AUDIO_FILE = 4401;
    private static final int REQUEST_RECORD_AUDIO = 4402;
    private static final String CAFL_URL =
            "https://gist.githubusercontent.com/tmiland/a3ce588bcd65738d91b4/raw/acc20b9e600d6fb92915f758a379fbd4139b98ca/CAFL.txt";

    private final List<Preset> etherPresets = new ArrayList<>();
    private final List<Preset> bentovPresets = new ArrayList<>();
    private final List<Preset> ninePresets = new ArrayList<>();
    private final List<Preset> orgonePresets = new ArrayList<>();
    private final List<Preset> rifePresets = new ArrayList<>();
    private final List<AudioDeviceInfo> outputDevices = new ArrayList<>();
    private final List<RifeEntry> rifeEntries = new ArrayList<>();
    private final List<RifeEntry> rifeSearchResults = new ArrayList<>();

    private Spinner sectionSpinner;
    private Spinner presetSpinner;
    private Spinner outputSpinner;
    private Spinner audioModeSpinner;
    private Spinner rifeResultsSpinner;

    private TextView presetInfo;
    private TextView status;
    private TextView octaveReadout;
    private TextView gainReadout;
    private TextView modReadout;
    private TextView audioFileReadout;
    private TextView deviceReadout;
    private TextView rifeDbReadout;
    private TextView rifeSelectedReadout;
    private TextView wholeTargetReadout;

    private EditText customFreqs;
    private EditText rifeSearch;
    private EditText wholeTargetInput;
    private SeekBar gainBar;
    private SeekBar masterModBar;
    private SeekBar octaveBar;
    private CheckBox manualToneCheck;
    private CheckBox wholeTargetModeCheck;

    private volatile Preset activePreset;
    private volatile boolean running = false;
    private volatile float masterGain = 0.18f;
    private volatile float masterModRate = 0.0f;
    private volatile double manualFreq = 444.0;
    private volatile boolean manualToneEnabled = false;
    private volatile float externalAudioEnvelope = 1.0f;
    private volatile int audioFileMode = 0; // 0 mix, 1 envelope-modulate carriers
    private volatile double wholeHardwareTargetHz = 0.0;
    private volatile boolean wholeTargetMode = true;

    private AudioTrack audioTrack;
    private Thread audioThread;
    private AudioDeviceInfo selectedDevice;

    private Uri selectedAudioUri;
    private MediaPlayer mediaPlayer;
    private Visualizer visualizer;

    private int activeSection = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildPresets();
        buildUi();
        refreshAudioDevices();
        loadCachedCafl();
        selectSection(0);
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
        sub.setText("Choose a system, choose what you want, choose your emitter, then press START");
        sub.setGravity(Gravity.CENTER);
        sub.setTextSize(14f);
        root.addView(sub);

        spacer(root, 14);
        label(root, "1. CHOOSE MACHINE");
        sectionSpinner = new Spinner(this);
        sectionSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"ETHER SHIP", "BENTOV", "THE NINE", "ORGONE", "RIFE"}));
        root.addView(sectionSpinner);
        sectionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectSection(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        label(root, "2. CHOOSE PURPOSE");
        presetSpinner = new Spinner(this);
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

        spacer(root, 10);
        label(root, "3. CHOOSE OUTPUT DEVICE");
        outputSpinner = new Spinner(this);
        root.addView(outputSpinner);
        outputSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < outputDevices.size()) {
                    selectedDevice = outputDevices.get(position);
                    updateDeviceReadout();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        Button connect = new Button(this);
        connect.setText("FIND MY ULTRASONIC EMITTER");
        connect.setOnClickListener(v -> {
            refreshAudioDevices();
            scanUsbDevices();
        });
        root.addView(connect);

        deviceReadout = new TextView(this);
        deviceReadout.setTextSize(12f);
        deviceReadout.setPadding(0, dp(6), 0, dp(6));
        root.addView(deviceReadout);

        spacer(root, 10);
        label(root, "EXACT HIGH FREQUENCY FOR YOUR EMITTER");
        wholeTargetModeCheck = new CheckBox(this);
        wholeTargetModeCheck.setText("Use the exact listed high frequency");
        wholeTargetModeCheck.setChecked(true);
        wholeTargetModeCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            wholeTargetMode = isChecked;
            updateWholeTargetReadout();
        });
        root.addView(wholeTargetModeCheck);

        wholeTargetInput = new EditText(this);
        wholeTargetInput.setHint("Frequency in Hz, example: 98600000");
        wholeTargetInput.setSingleLine(true);
        root.addView(wholeTargetInput);

        Button loadWholeTarget = new Button(this);
        loadWholeTarget.setText("USE THIS EXACT FREQUENCY");
        loadWholeTarget.setOnClickListener(v -> {
            try {
                double f = Double.parseDouble(wholeTargetInput.getText().toString().trim());
                if (f > 0) {
                    wholeHardwareTargetHz = f;
                    updateWholeTargetReadout();
                }
            } catch (Exception e) {
                wholeTargetReadout.setText("Enter a numeric frequency in Hz");
            }
        });
        root.addView(loadWholeTarget);

        wholeTargetReadout = new TextView(this);
        wholeTargetReadout.setTextSize(13f);
        wholeTargetReadout.setPadding(0, dp(6), 0, dp(8));
        root.addView(wholeTargetReadout);

        spacer(root, 10);
        label(root, "4. ADD AN AUDIO FILE (OPTIONAL)");
        audioFileReadout = new TextView(this);
        audioFileReadout.setText("No audio file selected");
        root.addView(audioFileReadout);

        Button pickAudio = new Button(this);
        pickAudio.setText("PICK AUDIO FILE");
        pickAudio.setOnClickListener(v -> chooseAudioFile());
        root.addView(pickAudio);

        audioModeSpinner = new Spinner(this);
        audioModeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{
                        "Play my audio together with the frequencies",
                        "Make my audio control the frequency strength"
                }));
        root.addView(audioModeSpinner);
        audioModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                audioFileMode = position;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        Button playAudio = new Button(this);
        playAudio.setText("PLAY MY AUDIO");
        playAudio.setOnClickListener(v -> playSelectedAudio());
        root.addView(playAudio);

        Button stopAudioFile = new Button(this);
        stopAudioFile.setText("STOP MY AUDIO");
        stopAudioFile.setOnClickListener(v -> stopMediaPlayer());
        root.addView(stopAudioFile);

        spacer(root, 10);
        Button advancedButton = new Button(this);
        advancedButton.setText("SHOW ADVANCED OPTIONS");
        root.addView(advancedButton);

        LinearLayout advancedPanel = new LinearLayout(this);
        advancedPanel.setOrientation(LinearLayout.VERTICAL);
        advancedPanel.setVisibility(View.GONE);
        root.addView(advancedPanel);

        advancedButton.setOnClickListener(v -> {
            boolean show = advancedPanel.getVisibility() != View.VISIBLE;
            advancedPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedButton.setText(show ? "HIDE ADVANCED OPTIONS" : "SHOW ADVANCED OPTIONS");
        });

        spacer(advancedPanel, 10);
        label(advancedPanel, "OUTPUT STRENGTH");
        gainReadout = new TextView(this);
        advancedPanel.addView(gainReadout);
        gainBar = new SeekBar(this);
        gainBar.setMax(100);
        gainBar.setProgress(18);
        advancedPanel.addView(gainBar);
        gainBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                masterGain = progress / 100f;
                gainReadout.setText(String.format(Locale.US, "%.0f%%", masterGain * 100f));
            }
        });
        gainReadout.setText("18%");

        label(advancedPanel, "PULSE SPEED (Hz)");
        modReadout = new TextView(this);
        advancedPanel.addView(modReadout);
        masterModBar = new SeekBar(this);
        masterModBar.setMax(400);
        masterModBar.setProgress(0);
        advancedPanel.addView(masterModBar);
        masterModBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                masterModRate = progress / 10f;
                modReadout.setText(String.format(Locale.US, "%.1f Hz", masterModRate));
            }
        });
        modReadout.setText("0.0 Hz");

        spacer(advancedPanel, 10);
        label(advancedPanel, "MANUAL FREQUENCY SLIDER");
        octaveReadout = new TextView(this);
        advancedPanel.addView(octaveReadout);
        octaveBar = new SeekBar(this);
        octaveBar.setMax(2000);
        octaveBar.setProgress(1344);
        advancedPanel.addView(octaveBar);
        octaveBar.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                double octaves = progress / 100.0;
                manualFreq = 0.04 * Math.pow(2.0, octaves);
                octaveReadout.setText(String.format(Locale.US, "%.2f oct • %.3f Hz", octaves, manualFreq));
            }
        });
        manualToneCheck = new CheckBox(this);
        manualToneCheck.setText("Add the manual frequency to the output");
        manualToneCheck.setOnCheckedChangeListener((buttonView, isChecked) -> manualToneEnabled = isChecked);
        advancedPanel.addView(manualToneCheck);

        spacer(advancedPanel, 10);
        label(advancedPanel, "TYPE YOUR OWN FREQUENCIES");
        customFreqs = new EditText(this);
        customFreqs.setHint("Example: 444, 888, 1776, 19200");
        customFreqs.setSingleLine(false);
        advancedPanel.addView(customFreqs);

        Button applyCustom = new Button(this);
        applyCustom.setText("USE THESE FREQUENCIES");
        applyCustom.setOnClickListener(v -> loadCustomMix());
        advancedPanel.addView(applyCustom);

        spacer(advancedPanel, 14);
        label(advancedPanel, "RIFE FREQUENCY LIBRARY");
        rifeDbReadout = new TextView(this);
        rifeDbReadout.setText("CAFL database not loaded yet");
        advancedPanel.addView(rifeDbReadout);

        Button syncRife = new Button(this);
        syncRife.setText("DOWNLOAD RIFE LIBRARY");
        syncRife.setOnClickListener(v -> syncCafl());
        advancedPanel.addView(syncRife);

        rifeSearch = new EditText(this);
        rifeSearch.setHint("Search the Rife library");
        advancedPanel.addView(rifeSearch);

        Button searchRife = new Button(this);
        searchRife.setText("SEARCH RIFE LIBRARY");
        searchRife.setOnClickListener(v -> searchRife());
        advancedPanel.addView(searchRife);

        rifeResultsSpinner = new Spinner(this);
        advancedPanel.addView(rifeResultsSpinner);
        rifeResultsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showRifeResult(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        rifeSelectedReadout = new TextView(this);
        rifeSelectedReadout.setTextSize(12f);
        advancedPanel.addView(rifeSelectedReadout);

        Button loadRife = new Button(this);
        loadRife.setText("USE SELECTED RIFE FREQUENCIES");
        loadRife.setOnClickListener(v -> loadSelectedRifeSet());
        advancedPanel.addView(loadRife);

        TextView rifeRfNote = new TextView(this);
        rifeRfNote.setText(
                "Historical Rife RF reference: surviving reconstructions place original high-RF work roughly " +
                "from 139,200 Hz up to about 1,607,450 Hz, with later Beam Ray systems using RF carriers and sidebands. " +
                "Those MHz-class values are kept as exact reference data. Phone PCM can only generate frequencies below " +
                "half the active sample rate."
        );
        rifeRfNote.setTextSize(12f);
        rifeRfNote.setPadding(0, dp(8), 0, dp(8));
        advancedPanel.addView(rifeRfNote);

        spacer(root, 14);
        Button startStop = new Button(this);
        startStop.setText("START OUTPUT");
        startStop.setTextSize(18f);
        root.addView(startStop);
        startStop.setOnClickListener(v -> {
            if (running) {
                stopSynth();
                startStop.setText("START OUTPUT");
            } else {
                startSynth();
                startStop.setText("STOP OUTPUT");
            }
        });

        status = new TextView(this);
        status.setPadding(0, dp(12), 0, 0);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        spacer(root, 16);
        TextView notes = new TextView(this);
        notes.setText(
                "SOURCE LABELS\n" +
                "ETHER SHIP: documented hardware architecture is separated from later/reconstructed frequency ideas. " +
                "The surviving public Ether Ship descriptions document the Synthi AKS, Hammond organ, brain-wave analyzer, " +
                "color-linked sound control, magnetic and laser thought tunnels, crystal projection, 24-channel EQ and a " +
                "20-octave control system, but do not publish a fixed archival Hz table for telepathy or ET contact.\n\n" +
                "BENTOV: primary-text values include body micromotion at 6.8–7.5 Hz, about 7.5 Hz for the Earth-ionosphere cavity in Bentov's model, about 7 Hz for whole-body resonance, 264/396/528 Hz as a harmonic example, and explicit beat examples of 50+60 and 10+12 Hz.\n\n" +
                "THE NINE: the published transcript gives 98.6 megacycles (98.6 MHz) as the tonal range of their language. That value is preserved whole as a primary external-hardware target. No octave-downconversion is used as a substitute. The 18-minute synchronized meditation and sound/color preparation come from the same published contact material. Puharich's later source values 6 Hz, 6.66 Hz, 7.83 Hz, 8 Hz, 10.80 Hz and 11 Hz are kept as a separate ELF/psi layer rather than mislabeled as the 98.6 MHz carrier itself.\n\n" +
                "ORGONE: Reich's original accumulator was a passive accumulator, not a fixed-Hz tone generator. " +
                "The Orgone presets here reproduce frequencies published by later pulsed-orgone generator traditions.\n\n" +
                "RIFE: historical RF references, CAFL labels and later frequency lists are preserved as experimental/archive data. " +
                "The app does not claim that a listed frequency diagnoses or treats a medical condition."
        );
        notes.setTextSize(12f);
        root.addView(notes);

        setContentView(scroll);
    }

    private void buildPresets() {
        etherPresets.add(new Preset(
                "Ether Ship Core • DOCUMENTED ARCHITECTURE",
                "Harmonic ladder representing the layered Synthi/Hammond control concept. " +
                        "No surviving public source gives one fixed archival Hz table for the 1970s machine.",
                new Osc[]{
                        s(55, .18, -0.5), s(110, .16, 0.5), s(220, .13, -0.2),
                        s(444, .12, 0.2), s(888, .08, -0.7), s(1776, .06, 0.7)
                }, 0.30f));

        etherPresets.add(new Preset(
                "Magnetic Mind Generator • RECONSTRUCTION",
                "Slow FM + AM cross-coupling inspired by Van De Bogart's psychotronic description.",
                new Osc[]{
                        fm(74, .14, -0.9, .17, 8), fm(148, .13, 0.9, .23, 12),
                        fm(296, .11, -0.5, .31, 16), fm(592, .09, 0.5, .41, 24),
                        fm(1184, .07, -0.2, .53, 32), fm(2368, .05, 0.2, .67, 48)
                }, 0.20f));

        etherPresets.add(new Preset(
                "Schumann-linked Xenolinguistics • LATER METHOD",
                "Later Van De Bogart writing explicitly links xenolinguistic work with Earth's Schumann resonance. " +
                        "This preset uses the widely cited 7.83 Hz fundamental as AM over a 444-derived carrier lattice.",
                new Osc[]{
                        am(444, .16, -0.6, 7.83, .50),
                        am(888, .13, 0.6, 7.83, .45),
                        am(1776, .10, -0.3, 7.83, .40),
                        am(3552, .07, 0.3, 7.83, .35),
                        am(7111, .04, 0.0, 7.83, .30)
                }, 0.22f));

        etherPresets.add(new Preset(
                "Xenolinguistic Microtonal • RECONSTRUCTION",
                "Non-12TET ratio lattice centered on 444 Hz for experimental symbolic/sonic language work.",
                new Osc[]{
                        s(444, .13, -0.8), s(499.5, .11, 0.8), s(555, .10, -0.6),
                        s(592, .09, 0.6), s(666, .08, -0.4), s(777, .07, 0.4),
                        s(888, .06, -0.2), s(999, .05, 0.2), s(1110, .04, 0.0)
                }, 0.18f));

        etherPresets.add(new Preset(
                "Ultrasonic Contact • EXPERIMENTAL",
                "High-frequency carrier bank for external high-sample-rate DAC/transducer hardware.",
                new Osc[]{
                        am(18000, .04, -0.9, 7.83, .40),
                        am(19200, .035, 0.9, 7.83, .40),
                        am(20000, .03, -0.6, 4.0, .30),
                        am(22100, .027, 0.6, 4.0, .30),
                        am(24000, .024, -0.3, 10.0, .25),
                        am(28000, .020, 0.3, 10.0, .25),
                        am(32000, .016, 0.0, 2.0, .20)
                }, 0.12f));

        etherPresets.add(new Preset(
                "444 Calibration",
                "Clean 444 Hz reference and octaves.",
                new Osc[]{
                        s(444, .20, 0.0), s(888, .10, -0.4),
                        s(1776, .06, 0.4), s(3552, .035, 0.0)
                }, 0.20f));


        bentovPresets.add(new Preset(
                "Heart-Aorta Resonance • PRIMARY TEXT",
                "Bentov gives body micromotion at about 6.8–7.5 Hz during deep meditation, with whole-body coherence centered near 7 Hz.",
                new Osc[]{
                        s(6.8, .12, -0.7), s(7.0, .14, 0.0), s(7.5, .12, 0.7)
                }, 0.16f));

        bentovPresets.add(new Preset(
                "Earth-Ionosphere Coupling • PRIMARY + ENGINE",
                "Bentov states an Earth-ionosphere resonance of about 7.5 cycles/second and proposes coupling to the body's 6.8–7.5 Hz micromotion. The carrier tones are an app rendering of that model.",
                new Osc[]{
                        s(7.5, .08, 0.0),
                        am(264, .12, -0.6, 7.5, .65),
                        am(396, .10, 0.0, 7.5, .60),
                        am(528, .12, 0.6, 7.5, .65)
                }, 0.18f));

        bentovPresets.add(new Preset(
                "Telepathic Coupling • 7 Hz MODEL",
                "Bentov calls the approximately 7 Hz planetary/body resonance an ideal medium for a telepathic signal. This preset keeps 7 Hz exact and uses harmonically related carriers for output.",
                new Osc[]{
                        s(7.0, .08, 0.0),
                        am(188.064575, .10, -0.8, 7.0, .70),
                        am(376.129150, .10, -0.4, 7.0, .65),
                        am(752.258301, .10, 0.0, 7.0, .60),
                        am(1504.516602, .08, 0.4, 7.0, .55),
                        am(3009.033203, .06, 0.8, 7.0, .50)
                }, 0.18f));

        bentovPresets.add(new Preset(
                "Harmonic Bodies • PRIMARY EXAMPLE",
                "Bentov's explicit piano example uses middle C at 264 Hz, G at 396 Hz and the octave at 528 Hz to illustrate resonance and higher harmonics.",
                new Osc[]{
                        s(264, .15, -0.6), s(396, .12, 0.0), s(528, .15, 0.6)
                }, 0.22f));

        bentovPresets.add(new Preset(
                "Beat Demo • 50 + 60 = 10 Hz",
                "Bentov's explicit beat-frequency example: 50 Hz and 60 Hz combine to produce a 10 Hz amplitude beat.",
                new Osc[]{
                        s(50, .18, -0.5), s(60, .18, 0.5)
                }, 0.22f));

        bentovPresets.add(new Preset(
                "Beat Demo • 10 + 12 = 2 Hz",
                "Bentov's second explicit beat-frequency example: 10 Hz and 12 Hz produce a 2 Hz beat.",
                new Osc[]{
                        s(10, .18, -0.5), s(12, .18, 0.5)
                }, 0.22f));

        bentovPresets.add(new Preset(
                "4 / 7 Hz Magnetic Field Reference • PRIMARY TEXT",
                "Bentov discusses prolonged exposure to pulsating magnetic fields at about 4 or 7 Hz in his physio-kundalini model. Preserved here as an historical frequency reference.",
                new Osc[]{
                        s(4.0, .12, -0.5), s(7.0, .12, 0.5)
                }, 0.14f));

        ninePresets.add(new Preset(
                "The Nine Tonal Language • 98.6 MHz PRIMARY TRANSCRIPT",
                "In The Only Planet of Choice, Puharich asks whether the tonal range of The Nine's language is 98.6 megacycles; Tom answers yes. 98.6 megacycles = 98.6 MHz. This is stored exactly as an RF reference and is above phone PCM range.",
                new Osc[]{
                        s(98600000.0, .0, 0.0)
                }, 0.10f));

        ninePresets.add(new Preset(
                "Puharich PSI / ELF Exact Set • SOURCE VALUES",
                "Puharich's published ELF material repeatedly names 6 Hz, 6.66 Hz, 7.83 Hz, 8 Hz, 10.80 Hz and 11 Hz. This preset preserves those values exactly as a source bank.",
                new Osc[]{
                        s(6.0, .08, -0.9),
                        s(6.66, .08, -0.6),
                        s(7.83, .10, -0.3),
                        s(8.0, .12, 0.0),
                        s(10.80, .08, 0.4),
                        s(11.0, .08, 0.8)
                }, 0.15f));

        ninePresets.add(new Preset(
                "Puharich ELF Bridge • 8 Hz",
                "Puharich later described 8 Hz as a central ELF magnetic frequency associated in his experiments with psychics, healers and crystals. This is a Puharich bridge preset, not a direct statement from The Nine transcript.",
                new Osc[]{
                        s(8.0, .08, 0.0),
                        am(444, .10, -0.7, 8.0, .65),
                        am(888, .10, -0.3, 8.0, .60),
                        am(1776, .10, 0.3, 8.0, .55),
                        am(3552, .08, 0.7, 8.0, .50)
                }, 0.17f));

        ninePresets.add(new Preset(
                "Schumann Contact Bridge • 7.83 Hz PUHARICH LATER",
                "Puharich's later ELF material singles out 7.83 Hz as a beneficial Schumann-linked rate. Preserved separately from the 98.6 MHz Nine transcript value.",
                new Osc[]{
                        s(7.83, .08, 0.0),
                        am(188.064575, .10, -0.8, 7.83, .70),
                        am(376.129150, .10, -0.4, 7.83, .65),
                        am(752.258301, .10, 0.0, 7.83, .60),
                        am(1504.516602, .08, 0.4, 7.83, .55),
                        am(3009.033203, .06, 0.8, 7.83, .50)
                }, 0.17f));

        ninePresets.add(new Preset(
                "The Nine Whole Contact • 98.6 MHz PRIMARY",
                "Keeps the reported 98.6 MHz tonal-language value whole as the PRIMARY HARDWARE TARGET. 8 Hz and 7.83 Hz are support/modulation layers only and do not replace or downconvert the primary frequency. The published meditation protocol calls for an 18-minute synchronized session and allows sound before meditation.",
                new Osc[]{
                        s(98600000.0, .0, 0.0),
                        s(8.0, .06, -0.5),
                        s(7.83, .06, 0.5)
                }, 0.14f));

        orgonePresets.add(new Preset(
                "Orgone Pulse • 3.5 Hz",
                "Later pulsed-orgone tradition preset: 3.5 Hz modulation.",
                new Osc[]{am(444, .16, 0, 3.5, .75), am(888, .08, 0, 3.5, .70)}, 0.18f));
        orgonePresets.add(new Preset(
                "Orgone Pulse • 6.3 Hz",
                "Later pulsed-orgone tradition preset: 6.3 Hz modulation.",
                new Osc[]{am(444, .16, 0, 6.3, .75), am(888, .08, 0, 6.3, .70)}, 0.18f));
        orgonePresets.add(new Preset(
                "Orgone Pulse • 7.0 Hz",
                "Later pulsed-orgone tradition preset: 7.0 Hz modulation.",
                new Osc[]{am(444, .16, 0, 7.0, .75), am(888, .08, 0, 7.0, .70)}, 0.18f));
        orgonePresets.add(new Preset(
                "Orgone Pulse • 7.83 Hz",
                "Later pulsed-orgone tradition preset associated with the Schumann fundamental.",
                new Osc[]{am(444, .16, 0, 7.83, .75), am(888, .08, 0, 7.83, .70)}, 0.18f));
        orgonePresets.add(new Preset(
                "Orgone Pulse • 10.0 Hz",
                "Later pulsed-orgone tradition preset: 10.0 Hz modulation.",
                new Osc[]{am(444, .16, 0, 10.0, .75), am(888, .08, 0, 10.0, .70)}, 0.18f));
        orgonePresets.add(new Preset(
                "Orgone Pulse • 14.1 Hz",
                "Later pulsed-orgone tradition preset: 14.1 Hz modulation.",
                new Osc[]{am(444, .16, 0, 14.1, .75), am(888, .08, 0, 14.1, .70)}, 0.18f));

        rifePresets.add(new Preset(
                "Rife Audio Core • LATER LIST",
                "Common later Rife audio-frequency group preserved as experimental/archive data.",
                new Osc[]{
                        s(20, .10, -0.8), s(464, .10, -0.5), s(727, .10, -0.2),
                        s(728, .10, 0.0), s(784, .10, 0.2), s(787, .10, 0.4),
                        s(800, .08, 0.6), s(880, .08, 0.8), s(5000, .04, 0)
                }, 0.16f));

        rifePresets.add(new Preset(
                "Rife Beam Ray Audio Sideband Drivers • HISTORICAL RECONSTRUCTION",
                "Low/audio generator values in later Beam Ray reconstructions are sideband drivers, not the original RF MOR itself.",
                new Osc[]{
                        s(21275, .04, -0.7), s(20080, .04, 0.7),
                        s(803, .08, -0.4), s(727, .08, 0.4),
                        s(690, .08, 0.0)
                }, 0.12f));

        rifePresets.add(new Preset(
                "Rife Sweep • 300–800 Hz EXPERIMENTAL",
                "Dense research sweep-style bank across the 300–800 Hz region.",
                makeRange(300, 800, 25), 0.10f));

        rifePresets.add(new Preset(
                "Rife Sweep • 2000–2600 Hz EXPERIMENTAL",
                "Dense research sweep-style bank across the 2000–2600 Hz region.",
                makeRange(2000, 2600, 25), 0.10f));
    }

    private Osc[] makeRange(int start, int end, int step) {
        List<Osc> list = new ArrayList<>();
        int count = Math.max(1, ((end - start) / step) + 1);
        for (int f = start, i = 0; f <= end; f += step, i++) {
            double pan = count <= 1 ? 0 : -1.0 + (2.0 * i / (count - 1.0));
            list.add(s(f, 0.12 / Math.sqrt(count), pan));
        }
        return list.toArray(new Osc[0]);
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

    private List<Preset> currentPresets() {
        if (activeSection == 1) return bentovPresets;
        if (activeSection == 2) return ninePresets;
        if (activeSection == 3) return orgonePresets;
        if (activeSection == 4) return rifePresets;
        return etherPresets;
    }

    private void selectSection(int section) {
        activeSection = Math.max(0, Math.min(4, section));
        List<Preset> list = currentPresets();
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).name;
        presetSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        if (!list.isEmpty()) {
            presetSpinner.setSelection(0);
            selectPreset(0);
        }
    }

    private void selectPreset(int index) {
        List<Preset> list = currentPresets();
        if (index < 0 || index >= list.size()) return;
        activePreset = list.get(index);
        masterGain = activePreset.recommendedGain;
        if (gainBar != null) gainBar.setProgress(Math.round(masterGain * 100f));

        double primary = 0.0;
        for (Osc o : activePreset.oscs) {
            if (o.freq >= 96000.0 && o.freq > primary) primary = o.freq;
        }
        if (primary > 0.0) {
            wholeHardwareTargetHz = primary;
            if (wholeTargetInput != null) wholeTargetInput.setText(trim(primary));
        }
        updateWholeTargetReadout();
        showPresetInfo(activePreset);
    }

    private void showPresetInfo(Preset p) {
        if (p == null || presetInfo == null) return;
        StringBuilder b = new StringBuilder();
        b.append(p.description);

        List<Double> hardwareTargets = new ArrayList<>();
        List<Double> pcmTargets = new ArrayList<>();
        for (Osc o : p.oscs) {
            if (o.freq >= 96000.0) hardwareTargets.add(o.freq);
            else pcmTargets.add(o.freq);
        }

        if (!hardwareTargets.isEmpty()) {
            b.append("\n\nEXACT HIGH FREQUENCY FOR EMITTER");
            for (double f : hardwareTargets) {
                b.append("\n• ").append(formatFrequency(f)).append(" • WHOLE / NO DOWNCONVERSION");
            }
        }

        if (!pcmTargets.isEmpty()) {
            b.append("\n\nOTHER FREQUENCIES USED: ");
            for (int i = 0; i < pcmTargets.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(formatFrequency(pcmTargets.get(i)));
            }
        }

        presetInfo.setText(b.toString());
    }

    private void updateWholeTargetReadout() {
        if (wholeTargetReadout == null) return;
        if (wholeHardwareTargetHz <= 0.0) {
            wholeTargetReadout.setText("No external RF target loaded");
            return;
        }
        String mode = wholeTargetMode
                ? "WHOLE • exact value preserved • no octave conversion"
                : "reference only";
        wholeTargetReadout.setText("PRIMARY EXTERNAL TARGET: " +
                formatFrequency(wholeHardwareTargetHz) + "\n" + mode);
    }

    private void loadCustomMix() {
        String raw = customFreqs.getText().toString().trim();
        if (raw.isEmpty()) return;
        String[] parts = raw.split("[,;\\s]+");
        List<Osc> list = new ArrayList<>();
        for (int i = 0; i < parts.length && i < 64; i++) {
            try {
                double f = Double.parseDouble(parts[i]);
                if (f > 0) {
                    double pan = parts.length <= 1 ? 0 : -1.0 + (2.0 * i / (parts.length - 1.0));
                    list.add(s(f, Math.max(0.015, 0.20 / Math.sqrt(Math.max(1, parts.length))), pan));
                }
            } catch (Exception ignored) { }
        }
        if (list.isEmpty()) return;
        activePreset = new Preset(
                "Custom Mix",
                "Live custom frequency bank.",
                list.toArray(new Osc[0]),
                masterGain);
        showPresetInfo(activePreset);
    }

    private void chooseAudioFile() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, REQUEST_AUDIO_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_AUDIO_FILE && resultCode == RESULT_OK && data != null) {
            selectedAudioUri = data.getData();
            if (selectedAudioUri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            selectedAudioUri,
                            data.getFlags() & (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                } catch (Exception ignored) { }
                audioFileReadout.setText("Selected: " + selectedAudioUri.getLastPathSegment());
            }
        }
    }

    private void playSelectedAudio() {
        if (selectedAudioUri == null) {
            audioFileReadout.setText("Choose an audio file first");
            return;
        }
        if (audioFileMode == 1 && Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            audioFileReadout.setText("Grant microphone/audio-capture permission, then tap PLAY AUDIO FILE again");
            return;
        }

        stopMediaPlayer();
        try {
            mediaPlayer = new MediaPlayer();
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            mediaPlayer.setAudioAttributes(attrs);
            if (selectedDevice != null) {
                try { mediaPlayer.setPreferredDevice(selectedDevice); } catch (Exception ignored) { }
            }
            mediaPlayer.setDataSource(this, selectedAudioUri);
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                setupVisualizerIfNeeded();
                audioFileReadout.setText("PLAYING • " + selectedAudioUri.getLastPathSegment());
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                externalAudioEnvelope = 1.0f;
                disableVisualizer();
                audioFileReadout.setText("Audio file finished");
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            audioFileReadout.setText("Audio file error: " + e.getMessage());
            stopMediaPlayer();
        }
    }

    private void setupVisualizerIfNeeded() {
        externalAudioEnvelope = 1.0f;
        if (audioFileMode != 1 || mediaPlayer == null) return;
        try {
            visualizer = new Visualizer(mediaPlayer.getAudioSessionId());
            int[] range = Visualizer.getCaptureSizeRange();
            int size = Math.min(1024, range[1]);
            size = Math.max(range[0], size);
            visualizer.setCaptureSize(size);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    if (waveform == null || waveform.length == 0) return;
                    double sum = 0;
                    for (byte b : waveform) {
                        double v = ((b & 0xFF) - 128) / 128.0;
                        sum += v * v;
                    }
                    double rms = Math.sqrt(sum / waveform.length);
                    externalAudioEnvelope = (float) Math.max(0.04, Math.min(1.0, rms * 3.5));
                }
                @Override public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) { }
            }, Visualizer.getMaxCaptureRate() / 2, true, false);
            visualizer.setEnabled(true);
        } catch (Exception e) {
            externalAudioEnvelope = 1.0f;
            audioFileReadout.setText("Audio playing • envelope capture unavailable: " + e.getMessage());
        }
    }

    private void disableVisualizer() {
        if (visualizer != null) {
            try { visualizer.setEnabled(false); } catch (Exception ignored) { }
            try { visualizer.release(); } catch (Exception ignored) { }
            visualizer = null;
        }
    }

    private void stopMediaPlayer() {
        disableVisualizer();
        externalAudioEnvelope = 1.0f;
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) { }
            try { mediaPlayer.release(); } catch (Exception ignored) { }
            mediaPlayer = null;
        }
        if (audioFileReadout != null && selectedAudioUri != null) {
            audioFileReadout.setText("Selected: " + selectedAudioUri.getLastPathSegment());
        }
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
            outputSpinner.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, labels));
            int preferred = 0;
            for (int i = 0; i < outputDevices.size(); i++) {
                AudioDeviceInfo d = outputDevices.get(i);
                if (d != null && isUltrasonicEmitterType(d)) {
                    preferred = i;
                    break;
                }
            }
            outputSpinner.setSelection(preferred);
            selectedDevice = outputDevices.get(preferred);
            updateDeviceReadout();
        }
    }

    private void scanUsbDevices() {
        UsbManager usbManager = (UsbManager) getSystemService(USB_SERVICE);
        Map<String, UsbDevice> map = usbManager.getDeviceList();
        StringBuilder b = new StringBuilder();
        if (map.isEmpty()) {
            b.append("No generic USB device detected. Android audio outputs above are still available.");
        } else {
            b.append("USB detected: ");
            boolean first = true;
            for (UsbDevice d : map.values()) {
                if (!first) b.append(" | ");
                first = false;
                b.append(d.getProductName() == null ? d.getDeviceName() : d.getProductName());
                b.append(" VID ").append(d.getVendorId()).append(" PID ").append(d.getProductId());
            }
            b.append("\nIf Android reports your emitter as a headset, EtherShip will still label it ULTRASONIC EMITTER.");
        }
        deviceReadout.setText(b.toString());
    }

    private void updateDeviceReadout() {
        if (deviceReadout == null) return;
        if (selectedDevice == null) {
            deviceReadout.setText("Connected to: PHONE DEFAULT OUTPUT");
            return;
        }
        StringBuilder b = new StringBuilder("Connected to: ").append(deviceName(selectedDevice));
        int[] rates = selectedDevice.getSampleRates();
        if (rates != null && rates.length > 0) {
            b.append("\nDevice sample rates: ");
            for (int i = 0; i < rates.length; i++) {
                if (i > 0) b.append(", ");
                b.append(rates[i]);
            }
        }
        deviceReadout.setText(b.toString());
    }

    private boolean isUltrasonicEmitterType(AudioDeviceInfo d) {
        if (d == null) return false;
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_USB_DEVICE ||
                t == AudioDeviceInfo.TYPE_USB_HEADSET ||
                t == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                t == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                t == AudioDeviceInfo.TYPE_LINE_ANALOG;
    }

    private String deviceName(AudioDeviceInfo d) {
        if (d == null) return "PHONE DEFAULT OUTPUT";
        String product = String.valueOf(d.getProductName());

        if (isUltrasonicEmitterType(d)) {
            return "ULTRASONIC EMITTER • " + product;
        }

        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "PHONE SPEAKER";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "BLUETOOTH AUDIO • " + product;
            default:
                return "OTHER PHONE OUTPUT • " + product;
        }
    }

    private void loadCachedCafl() {
        File f = new File(getFilesDir(), "cafl.txt");
        if (!f.exists()) return;
        new Thread(() -> {
            try {
                StringBuilder b = new StringBuilder();
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        new java.io.FileInputStream(f), StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) b.append(line).append('\n');
                r.close();
                parseCafl(b.toString());
                runOnUiThread(() -> rifeDbReadout.setText(
                        "CAFL cache loaded • " + rifeEntries.size() + " entries"));
            } catch (Exception ignored) { }
        }).start();
    }

    private void syncCafl() {
        rifeDbReadout.setText("Downloading full CAFL bank…");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(CAFL_URL).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "EtherShip-Android");
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder b = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) b.append(line).append('\n');
                r.close();
                String text = b.toString();
                FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "cafl.txt"));
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.close();
                parseCafl(text);
                runOnUiThread(() -> rifeDbReadout.setText(
                        "FULL CAFL LOADED • " + rifeEntries.size() + " entries • cached offline"));
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> rifeDbReadout.setText("CAFL sync error: " + msg));
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private synchronized void parseCafl(String text) {
        rifeEntries.clear();
        if (text == null) return;
        String[] lines = text.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;
            String[] parts = line.split(";");
            if (parts.length < 2) continue;

            StringBuilder name = new StringBuilder();
            List<Double> freqs = new ArrayList<>();
            boolean frequencyStarted = false;

            for (int i = 1; i < parts.length; i++) {
                String token = parts[i].trim();
                if (token.isEmpty()) continue;
                Double f = parseFrequencyToken(token);
                if (f != null) {
                    frequencyStarted = true;
                    if (f > 0) freqs.add(f);
                } else if (!frequencyStarted) {
                    if (name.length() > 0) name.append("; ");
                    name.append(token);
                }
            }

            if (name.length() == 0) name.append("CAFL ").append(parts[0].trim());
            rifeEntries.add(new RifeEntry(name.toString(), freqs));
        }
    }

    private Double parseFrequencyToken(String token) {
        try {
            String t = token.trim();
            int eq = t.indexOf('=');
            if (eq > 0) t = t.substring(0, eq).trim();
            if (!t.matches("[-+]?\\d+(\\.\\d+)?")) return null;
            return Double.parseDouble(t);
        } catch (Exception e) {
            return null;
        }
    }

    private void searchRife() {
        String q = rifeSearch.getText().toString().trim().toLowerCase(Locale.US);
        rifeSearchResults.clear();
        List<String> names = new ArrayList<>();
        synchronized (this) {
            for (RifeEntry e : rifeEntries) {
                if (q.isEmpty() || e.name.toLowerCase(Locale.US).contains(q)) {
                    rifeSearchResults.add(e);
                    names.add(e.name);
                    if (names.size() >= 100) break;
                }
            }
        }
        if (names.isEmpty()) names.add("No matches");
        rifeResultsSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names));
        rifeResultsSpinner.setSelection(0);
        showRifeResult(0);
    }

    private void showRifeResult(int position) {
        if (rifeSearchResults.isEmpty() || position < 0 || position >= rifeSearchResults.size()) {
            rifeSelectedReadout.setText("");
            return;
        }
        RifeEntry e = rifeSearchResults.get(position);
        StringBuilder b = new StringBuilder(e.name).append("\nHz: ");
        for (int i = 0; i < e.freqs.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(formatFrequency(e.freqs.get(i)));
        }
        rifeSelectedReadout.setText(b.toString());
    }

    private void loadSelectedRifeSet() {
        int pos = rifeResultsSpinner.getSelectedItemPosition();
        if (rifeSearchResults.isEmpty() || pos < 0 || pos >= rifeSearchResults.size()) return;
        RifeEntry e = rifeSearchResults.get(pos);

        List<Osc> oscs = new ArrayList<>();
        int playable = 0;
        int referenceOnly = 0;
        for (int i = 0; i < e.freqs.size(); i++) {
            double f = e.freqs.get(i);
            if (f <= 86000 && playable < 64) {
                double pan = e.freqs.size() <= 1 ? 0 : -1.0 + (2.0 * i / Math.max(1.0, e.freqs.size() - 1.0));
                oscs.add(s(f, Math.max(0.012, 0.16 / Math.sqrt(Math.max(1, e.freqs.size()))), pan));
                playable++;
            } else {
                referenceOnly++;
            }
        }

        if (oscs.isEmpty()) {
            presetInfo.setText(e.name + "\nNo frequencies in this set fit the app's PCM generation range. " +
                    "The exact values remain visible as reference data.");
            return;
        }

        activePreset = new Preset(
                "CAFL • " + e.name,
                "Loaded from the full CAFL archive. " + playable + " frequencies loaded for PCM output" +
                        (referenceOnly > 0 ? "; " + referenceOnly + " higher values kept as reference only." : "."),
                oscs.toArray(new Osc[0]),
                0.12f);
        masterGain = 0.12f;
        gainBar.setProgress(12);
        showPresetInfo(activePreset);
    }

    private void startSynth() {
        stopSynth();

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

            String route = selectedDevice == null ? "system default" : deviceName(selectedDevice);
            if (wholeTargetMode && wholeHardwareTargetHz > sr * 0.49) {
                status.setText("MODULATION ACTIVE • " + sr + " Hz PCM • " + route +
                        "\nWHOLE EXTERNAL TARGET: " + formatFrequency(wholeHardwareTargetHz) +
                        " • NOT OCTAVE-DIVIDED");
            } else {
                status.setText("TRANSMITTING • " + sr + " Hz PCM • stereo • " + route);
            }
        } catch (Exception e) {
            status.setText("Audio start error: " + e.getMessage());
            stopSynth();
        }
    }

    private int chooseSampleRate() {
        int[] candidates = new int[]{192000, 96000, 48000, 44100};
        for (int sr : candidates) {
            int test = AudioTrack.getMinBufferSize(sr,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (test > 0) return sr;
        }
        return 48000;
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

                double fileEnv = (audioFileMode == 1 && mediaPlayer != null && mediaPlayer.isPlaying())
                        ? externalAudioEnvelope : 1.0;

                for (Osc o : p.oscs) {
                    if (o.freq >= sr * 0.49) continue;
                    double f = Math.max(0.01, o.freq);
                    if (o.fmRate > 0 && o.fmDepthHz > 0) {
                        o.fmPhase += TWO_PI * o.fmRate / sr;
                        if (o.fmPhase > TWO_PI) o.fmPhase -= TWO_PI;
                        f += Math.sin(o.fmPhase) * o.fmDepthHz;
                        f = Math.min(Math.max(0.01, f), sr * 0.48);
                    }

                    o.phase += TWO_PI * f / sr;
                    if (o.phase > TWO_PI) o.phase -= TWO_PI;

                    double amp = o.amp;
                    if (o.amRate > 0 && o.amDepth > 0) {
                        o.amPhase += TWO_PI * o.amRate / sr;
                        if (o.amPhase > TWO_PI) o.amPhase -= TWO_PI;
                        amp *= (1.0 - o.amDepth) + o.amDepth *
                                (0.5 + 0.5 * Math.sin(o.amPhase));
                    }

                    amp *= fileEnv;
                    double sample = Math.sin(o.phase) * amp;
                    double lGain = Math.sqrt((1.0 - o.pan) * 0.5);
                    double rGain = Math.sqrt((1.0 + o.pan) * 0.5);
                    left += sample * lGain;
                    right += sample * rGain;
                }

                if (manualToneEnabled && manualFreq < sr * 0.49) {
                    double f = Math.max(0.01, manualFreq);
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

    private void stopSynth() {
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
        stopSynth();
        stopMediaPlayer();
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

    private static String formatFrequency(double n) {
        if (n >= 1000000.0) return String.format(Locale.US, "%.6f MHz", n / 1000000.0);
        if (n >= 1000.0) return trim(n) + " Hz";
        return trim(n) + " Hz";
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

    private static class RifeEntry {
        final String name;
        final List<Double> freqs;

        RifeEntry(String name, List<Double> freqs) {
            this.name = name;
            this.freqs = freqs;
        }
    }
}

package com.vaan.infobeam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

public final class ThoughtActivity extends Activity {
    private static final int REQ_MIC = 72;

    private static final int BG = Color.rgb(4, 8, 10);
    private static final int CARD = Color.rgb(13, 24, 28);
    private static final int TEXT = Color.rgb(236, 255, 250);
    private static final int MUTED = Color.rgb(164, 188, 183);
    private static final int AQUA = Color.rgb(93, 246, 211);
    private static final int GOLD = Color.rgb(255, 208, 104);
    private static final int VIOLET = Color.rgb(187, 142, 255);
    private static final int RED = Color.rgb(255, 108, 115);

    private enum PendingKind { NONE, THOUGHT, BEAM }

    private final UltrasonicFsk fsk = new UltrasonicFsk();
    private final ParametricAudio parametric = new ParametricAudio();
    private final ThoughtAudio thought = new ThoughtAudio();

    private AudioManager audioManager;
    private TextToSpeech tts;
    private volatile boolean ttsReady;

    private EditText messageInput;
    private Spinner profileSpinner;
    private TextView statusText;
    private TextView decodedText;
    private TextView hardwareText;
    private TextView thoughtStrengthText;
    private TextView carrierText;
    private TextView angleText;
    private TextView spacingText;
    private SeekBar thoughtStrength;
    private SeekBar carrierSeek;
    private SeekBar angleSeek;
    private SeekBar spacingSeek;
    private CheckBox autoThought;

    private volatile String lastDecoded = "";
    private volatile PendingKind pendingKind = PendingKind.NONE;
    private volatile String pendingId;
    private volatile File pendingFile;
    private volatile boolean pendingBeamExternal;
    private volatile int pendingBeamRate;
    private volatile double pendingBeamCarrier;
    private volatile double pendingBeamAngle;
    private volatile double pendingBeamSpacing;
    private volatile AudioDeviceInfo pendingBeamDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        configureWindow();
        setContentView(buildUi());
        initTts();
        scanHardware();
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("INFO BEAM Ω • THOUGHT", 26, AQUA, true);
        title.setLetterSpacing(0.05f);
        root.addView(title);
        TextView sub = text("Ultrasonic information link + private centered thought-voice renderer", 13, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        statusText = text("ENGINE STARTING", 14, GOLD, true);
        statusText.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusText.setBackground(rounded(Color.rgb(28, 39, 32), dp(12), GOLD, 1));
        root.addView(statusText, matchWrap());

        LinearLayout payload = card(root, "INFORMATION / THOUGHT", "Type anything here. Thought Preview lets you hear the exact private rendering without transmitting first.");
        messageInput = new EditText(this);
        messageInput.setTextColor(TEXT);
        messageInput.setHintTextColor(Color.rgb(105, 130, 128));
        messageInput.setHint("Example: Move now. Stay calm. Look left.");
        messageInput.setTextSize(17f);
        messageInput.setGravity(Gravity.TOP | Gravity.START);
        messageInput.setMinLines(4);
        messageInput.setMaxLines(8);
        messageInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        messageInput.setBackground(rounded(Color.rgb(6, 14, 17), dp(10), Color.rgb(55, 84, 83), 1));
        payload.addView(messageInput, matchWrap());

        thoughtStrengthText = text("Thought intensity: 75%", 14, VIOLET, true);
        thoughtStrengthText.setPadding(0, dp(10), 0, 0);
        payload.addView(thoughtStrengthText);
        thoughtStrength = new SeekBar(this);
        thoughtStrength.setMax(100);
        thoughtStrength.setProgress(75);
        thoughtStrength.setProgressTintList(ColorStateList.valueOf(VIOLET));
        thoughtStrength.setThumbTintList(ColorStateList.valueOf(VIOLET));
        thoughtStrength.setOnSeekBarChangeListener(new SimpleSeekListener(p -> thoughtStrengthText.setText("Thought intensity: " + p + "%")));
        payload.addView(thoughtStrength, matchWrap());

        Button preview = button("THOUGHT PREVIEW", VIOLET, Color.rgb(30, 14, 48));
        preview.setOnClickListener(v -> {
            String s = messageInput.getText().toString().trim();
            if (s.isEmpty()) setStatus("Type a thought first.", RED);
            else synthesizeThought(s, true);
        });
        payload.addView(preview, matchWrapTop(6));

        LinearLayout data = card(root, "ULTRASONIC INFO BEAM", "The transmitter sends coded information. The receiver verifies it, then automatically converts the recovered text into Thought Voice.");
        profileSpinner = new Spinner(this);
        ArrayAdapter<UltrasonicFsk.Profile> adapter = new ArrayAdapter<UltrasonicFsk.Profile>(this, android.R.layout.simple_spinner_dropdown_item, UltrasonicFsk.Profile.values()) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(TEXT);
                v.setTextSize(15f);
                v.setPadding(dp(10), dp(10), dp(10), dp(10));
                return v;
            }
        };
        profileSpinner.setAdapter(adapter);
        data.addView(profileSpinner, matchWrap());

        Button tx = button("TRANSMIT INFORMATION", AQUA, Color.rgb(1, 32, 29));
        tx.setOnClickListener(v -> transmitText());
        data.addView(tx, matchWrapTop(6));

        Button rx = button("RECEIVE + HEAR AS THOUGHT", GOLD, Color.rgb(36, 28, 4));
        rx.setOnClickListener(v -> startListening());
        data.addView(rx, matchWrapTop(8));

        autoThought = new CheckBox(this);
        autoThought.setText("Auto-play every verified message as Thought Voice");
        autoThought.setTextColor(TEXT);
        autoThought.setTextSize(13f);
        autoThought.setChecked(true);
        autoThought.setButtonTintList(ColorStateList.valueOf(VIOLET));
        data.addView(autoThought, matchWrapTop(8));

        Button stop = button("STOP ALL", RED, Color.rgb(40, 10, 13));
        stop.setOnClickListener(v -> stopAll("Stopped."));
        data.addView(stop, matchWrapTop(8));

        LinearLayout receive = card(root, "RECEIVED", "When CRC verification passes, the message appears here. Thought Voice uses headphones if connected, otherwise the phone earpiece.");
        decodedText = text("No verified information received yet.", 17, TEXT, false);
        decodedText.setPadding(dp(12), dp(12), dp(12), dp(12));
        decodedText.setBackground(rounded(Color.rgb(7, 15, 17), dp(10), Color.rgb(48, 76, 75), 1));
        receive.addView(decodedText, matchWrap());

        Button replay = button("REPLAY LAST AS THOUGHT", VIOLET, Color.rgb(30, 14, 48));
        replay.setOnClickListener(v -> {
            String s = lastDecoded;
            if (s == null || s.trim().isEmpty()) setStatus("Receive a message first.", RED);
            else synthesizeThought(s, true);
        });
        receive.addView(replay, matchWrapTop(8));

        LinearLayout direct = card(root, "DIRECT PARAMETRIC VOICE BEAM", "This is the direct audible-spot path. External ultrasonic hardware gets the full high-carrier engine; Phone Carrier stays available as the built-in-speaker experiment.");

        carrierText = text("External carrier: 40.0 kHz", 14, GOLD, true);
        direct.addView(carrierText);
        carrierSeek = new SeekBar(this);
        carrierSeek.setMax(240);
        carrierSeek.setProgress(220);
        carrierSeek.setProgressTintList(ColorStateList.valueOf(AQUA));
        carrierSeek.setThumbTintList(ColorStateList.valueOf(AQUA));
        carrierSeek.setOnSeekBarChangeListener(new SimpleSeekListener(p -> {
            double hz = 18_000 + p * 100.0;
            carrierText.setText(String.format(Locale.US, "External carrier: %.1f kHz", hz / 1000.0));
        }));
        direct.addView(carrierSeek, matchWrap());

        angleText = text("Target angle: 0°", 14, TEXT, true);
        angleText.setPadding(0, dp(6), 0, 0);
        direct.addView(angleText);
        angleSeek = new SeekBar(this);
        angleSeek.setMax(120);
        angleSeek.setProgress(60);
        angleSeek.setProgressTintList(ColorStateList.valueOf(GOLD));
        angleSeek.setThumbTintList(ColorStateList.valueOf(GOLD));
        angleSeek.setOnSeekBarChangeListener(new SimpleSeekListener(p -> angleText.setText("Target angle: " + (p - 60) + "°")));
        direct.addView(angleSeek, matchWrap());

        spacingText = text("Array spacing: 10 mm", 14, TEXT, true);
        spacingText.setPadding(0, dp(6), 0, 0);
        direct.addView(spacingText);
        spacingSeek = new SeekBar(this);
        spacingSeek.setMax(59);
        spacingSeek.setProgress(9);
        spacingSeek.setProgressTintList(ColorStateList.valueOf(AQUA));
        spacingSeek.setThumbTintList(ColorStateList.valueOf(AQUA));
        spacingSeek.setOnSeekBarChangeListener(new SimpleSeekListener(p -> spacingText.setText("Array spacing: " + (p + 1) + " mm")));
        direct.addView(spacingSeek, matchWrap());

        Button external = button("PARAMETRIC THOUGHT BEAM", AQUA, Color.rgb(2, 31, 28));
        external.setOnClickListener(v -> startVoiceBeam(true));
        direct.addView(external, matchWrapTop(8));

        Button phone = button("PHONE CARRIER EXPERIMENT", GOLD, Color.rgb(36, 28, 4));
        phone.setOnClickListener(v -> startVoiceBeam(false));
        direct.addView(phone, matchWrapTop(8));

        LinearLayout hw = card(root, "PRIVATE ROUTES / HARDWARE", "Thought Voice prefers a connected headset, then falls back to the top phone earpiece. Parametric mode looks for high-rate USB/digital output.");
        hardwareText = text("Scanning…", 13, MUTED, false);
        hw.addView(hardwareText, matchWrap());
        Button scan = button("RESCAN", TEXT, Color.rgb(25, 38, 41));
        scan.setOnClickListener(v -> scanHardware());
        hw.addView(scan, matchWrapTop(8));

        TextView note = text("THOUGHT ENGINE: dry dual-mono center image on headphones; tightly band-limited/compressed private speech; explicit AudioTrack routing instead of depending on TTS routing. On the earpiece route, hold the top speaker near the ear. The ultrasonic data carrier itself is intentionally not the audible thought.", 12, MUTED, false);
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);

        return scroll;
    }

    private void initTts() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                int r = tts.setLanguage(Locale.US);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(0.96f);
                tts.setPitch(0.92f);
                runOnUiThread(() -> setStatus("READY • press THOUGHT PREVIEW to hear the new renderer", AQUA));
            } else {
                runOnUiThread(() -> setStatus("TTS unavailable • ultrasonic data still works", RED));
            }
        });

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}

            @Override public void onDone(String utteranceId) {
                if (utteranceId == null || !utteranceId.equals(pendingId)) return;
                File file = pendingFile;
                PendingKind kind = pendingKind;
                pendingId = null;
                pendingFile = null;
                pendingKind = PendingKind.NONE;
                if (file != null) launchSynthesized(file, kind);
            }

            @Override @Deprecated public void onError(String utteranceId) {
                onError(utteranceId, TextToSpeech.ERROR);
            }

            @Override public void onError(String utteranceId, int errorCode) {
                if (utteranceId != null && utteranceId.equals(pendingId)) {
                    pendingId = null;
                    pendingKind = PendingKind.NONE;
                    File f = pendingFile;
                    pendingFile = null;
                    if (f != null) f.delete();
                    runOnUiThread(() -> setStatus("Speech synthesis failed: " + errorCode, RED));
                }
            }
        });
    }

    private void synthesizeThought(String text, boolean stopCurrent) {
        if (!ttsReady || tts == null) {
            setStatus("Thought voice is still initializing.", RED);
            return;
        }
        if (stopCurrent) {
            fsk.stop();
            parametric.stop();
            thought.stop();
            try { tts.stop(); } catch (Throwable ignored) {}
        }
        tts.setSpeechRate(0.96f);
        tts.setPitch(0.92f);
        File out = new File(getCacheDir(), "thought_" + System.nanoTime() + ".wav");
        if (out.exists()) out.delete();
        pendingKind = PendingKind.THOUGHT;
        pendingFile = out;
        pendingId = "thought-" + System.nanoTime();
        setStatus("Forming Thought Voice…", VIOLET);
        int result = tts.synthesizeToFile(text, new Bundle(), out, pendingId);
        if (result != TextToSpeech.SUCCESS) setStatus("TTS rejected Thought Voice synthesis.", RED);
    }

    private void transmitText() {
        String s = messageInput.getText().toString().trim();
        if (s.isEmpty()) {
            setStatus("Enter information first.", RED);
            return;
        }
        stopAll(null);
        UltrasonicFsk.Profile p = (UltrasonicFsk.Profile) profileSpinner.getSelectedItem();
        fsk.transmitText(s, p, fskListener);
    }

    private void startListening() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        stopAll(null);
        fsk.listen(this, fskListener);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening();
            else setStatus("Receiver mode needs microphone permission.", RED);
        }
    }

    private final UltrasonicFsk.Listener fskListener = new UltrasonicFsk.Listener() {
        @Override public void onStatus(String text) {
            runOnUiThread(() -> setStatus(text, text.startsWith("Receive error") || text.startsWith("Transmit error") ? RED : GOLD));
        }

        @Override public void onDecoded(String text, UltrasonicFsk.Profile profile) {
            lastDecoded = text;
            fsk.stop();
            runOnUiThread(() -> {
                decodedText.setText(text + "\n\n✓ CRC VERIFIED • " + profile.label);
                decodedText.setTextColor(AQUA);
                setStatus("VERIFIED • converting beam data into Thought Voice", VIOLET);
                if (autoThought.isChecked()) synthesizeThought(text, false);
            });
        }

        @Override public void onStopped() {}
    };

    private void startVoiceBeam(boolean external) {
        String s = messageInput.getText().toString().trim();
        if (s.isEmpty()) {
            setStatus("Enter information first.", RED);
            return;
        }
        if (!ttsReady || tts == null) {
            setStatus("Speech engine is still initializing.", RED);
            return;
        }

        AudioDeviceInfo device = external ? findBestExternalOutput() : findBuiltInSpeaker();
        if (external && device == null) {
            setStatus("No external high-rate route detected. Connect USB/digital ultrasonic output or use Phone Carrier Experiment.", RED);
            return;
        }

        stopAll(null);
        tts.setSpeechRate(0.96f);
        tts.setPitch(0.92f);
        pendingBeamExternal = external;
        pendingBeamDevice = device;
        pendingBeamRate = external ? bestSampleRate(device) : 48_000;
        pendingBeamCarrier = external ? 18_000 + carrierSeek.getProgress() * 100.0 : 19_200.0;
        pendingBeamAngle = angleSeek.getProgress() - 60.0;
        pendingBeamSpacing = spacingSeek.getProgress() + 1.0;

        File out = new File(getCacheDir(), "beam_thought_" + System.nanoTime() + ".wav");
        if (out.exists()) out.delete();
        pendingKind = PendingKind.BEAM;
        pendingFile = out;
        pendingId = "beam-thought-" + System.nanoTime();
        setStatus("Synthesizing Thought Voice for ultrasonic carrier…", GOLD);
        int result = tts.synthesizeToFile(s, new Bundle(), out, pendingId);
        if (result != TextToSpeech.SUCCESS) setStatus("TTS rejected beam synthesis.", RED);
    }

    private void launchSynthesized(File file, PendingKind kind) {
        new Thread(() -> {
            try {
                if (!file.exists() || file.length() <= 44) throw new IllegalStateException("TTS returned an empty audio file.");
                PcmWav.Data pcm = PcmWav.read(file);
                if (kind == PendingKind.THOUGHT) {
                    float amount = thoughtStrength == null ? 0.75f : thoughtStrength.getProgress() / 100f;
                    thought.play(getApplicationContext(), pcm, amount, thoughtListener);
                } else if (kind == PendingKind.BEAM) {
                    parametric.play(pcm, pendingBeamRate, pendingBeamCarrier, pendingBeamAngle, pendingBeamSpacing,
                            pendingBeamDevice, pendingBeamExternal, parametricListener);
                }
            } catch (Throwable t) {
                runOnUiThread(() -> setStatus("Audio prep error: " + safeMessage(t), RED));
            } finally {
                file.delete();
            }
        }, "InfoBeam-Synth-Decode").start();
    }

    private final ThoughtAudio.Listener thoughtListener = new ThoughtAudio.Listener() {
        @Override public void onStatus(String text) {
            runOnUiThread(() -> setStatus(text, text.startsWith("Thought playback error") ? RED : VIOLET));
        }
        @Override public void onStopped() {}
    };

    private final ParametricAudio.Listener parametricListener = new ParametricAudio.Listener() {
        @Override public void onStatus(String text) {
            runOnUiThread(() -> setStatus(text, text.startsWith("Beam error") ? RED : GOLD));
        }
        @Override public void onStopped() {}
    };

    private void scanHardware() {
        StringBuilder sb = new StringBuilder();
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : devices) {
            sb.append("• ").append(typeName(d.getType())).append(" • ").append(d.getProductName()).append('\n');
            int[] rates = d.getSampleRates();
            if (rates != null && rates.length > 0) sb.append("  ").append(Arrays.toString(rates)).append(" Hz\n");
        }
        AudioDeviceInfo privateRoute = findPrivateRoute();
        if (privateRoute != null) sb.append("\nTHOUGHT ROUTE: ").append(typeName(privateRoute.getType())).append(" • ").append(privateRoute.getProductName());
        AudioDeviceInfo external = findBestExternalOutput();
        if (external != null) sb.append("\nPARAMETRIC ROUTE: ").append(external.getProductName()).append(" • request ").append(bestSampleRate(external)).append(" Hz");
        else sb.append("\nPARAMETRIC ROUTE: no external high-rate output detected");
        hardwareText.setText(sb.toString());
    }

    private AudioDeviceInfo findPrivateRoute() {
        AudioDeviceInfo ear = null, wired = null, usb = null, bt = null;
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET: if (wired == null) wired = d; break;
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE: if (usb == null) usb = d; break;
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: if (bt == null) bt = d; break;
                case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: if (ear == null) ear = d; break;
                default: break;
            }
        }
        if (wired != null) return wired;
        if (usb != null) return usb;
        if (bt != null) return bt;
        return ear;
    }

    private AudioDeviceInfo findBuiltInSpeaker() {
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return d;
        return null;
    }

    private AudioDeviceInfo findBestExternalOutput() {
        AudioDeviceInfo best = null;
        int score = 0;
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int s = outputScore(d);
            if (s > score) { score = s; best = d; }
        }
        return best;
    }

    private int bestSampleRate(AudioDeviceInfo d) {
        if (d == null) return 48_000;
        int best = 0;
        int[] rates = d.getSampleRates();
        if (rates != null) for (int r : rates) if (r <= 192_000 && r > best) best = r;
        if (best >= 96_000) return best;
        if (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) return 96_000;
        return best > 0 ? best : 48_000;
    }

    private int outputScore(AudioDeviceInfo d) {
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_USB_DEVICE: return 120;
            case AudioDeviceInfo.TYPE_USB_HEADSET: return 115;
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return 100;
            case AudioDeviceInfo.TYPE_HDMI: return 90;
            case AudioDeviceInfo.TYPE_HDMI_ARC: return 88;
            case AudioDeviceInfo.TYPE_HDMI_EARC: return 87;
            default: return 0;
        }
    }

    private String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "Earpiece";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB audio";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired headphones";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired headset";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "Bluetooth LE";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth headset";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "Digital line";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            case AudioDeviceInfo.TYPE_HDMI_ARC: return "HDMI ARC";
            case AudioDeviceInfo.TYPE_HDMI_EARC: return "HDMI eARC";
            default: return "Audio type " + type;
        }
    }

    private void stopAll(String status) {
        fsk.stop();
        thought.stop();
        parametric.stop();
        if (tts != null) try { tts.stop(); } catch (Throwable ignored) {}
        pendingKind = PendingKind.NONE;
        pendingId = null;
        File f = pendingFile;
        pendingFile = null;
        if (f != null) f.delete();
        if (status != null) setStatus(status, GOLD);
    }

    @Override
    protected void onDestroy() {
        stopAll(null);
        if (tts != null) try { tts.shutdown(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private void setStatus(String value, int color) {
        if (statusText == null) return;
        statusText.setText(value);
        statusText.setTextColor(color);
    }

    private LinearLayout card(LinearLayout root, String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setBackground(rounded(CARD, dp(14), Color.rgb(37, 61, 62), 1));
        root.addView(box, matchWrapTop(14));
        box.addView(text(title, 16, AQUA, true));
        TextView s = text(subtitle, 12, MUTED, false);
        s.setPadding(0, dp(4), 0, dp(10));
        box.addView(s);
        return box;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setLineSpacing(0f, 1.08f);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String label, int textColor, int bgColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(textColor);
        b.setTextSize(14f);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setBackgroundTintList(ColorStateList.valueOf(bgColor));
        return b;
    }

    private GradientDrawable rounded(int fill, int radiusPx, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(dp(strokePx), stroke);
        return g;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapTop(int topDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(topDp);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private interface IntConsumer { void accept(int value); }
    private static final class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        private final IntConsumer c;
        SimpleSeekListener(IntConsumer c) { this.c = c; }
        @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { c.accept(progress); }
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}

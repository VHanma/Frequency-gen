package com.vaan.infobeam;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_MIC = 71;

    private static final int BG = Color.rgb(4, 8, 10);
    private static final int CARD = Color.rgb(13, 24, 28);
    private static final int TEXT = Color.rgb(236, 255, 250);
    private static final int MUTED = Color.rgb(164, 188, 183);
    private static final int AQUA = Color.rgb(93, 246, 211);
    private static final int GOLD = Color.rgb(255, 208, 104);
    private static final int RED = Color.rgb(255, 108, 115);

    private final UltrasonicFsk fsk = new UltrasonicFsk();
    private final ParametricAudio parametric = new ParametricAudio();

    private AudioManager audioManager;
    private TextToSpeech tts;
    private volatile boolean ttsReady;

    private EditText messageInput;
    private Spinner profileSpinner;
    private TextView profileHint;
    private TextView statusText;
    private TextView decodedText;
    private TextView hardwareText;
    private TextView carrierText;
    private TextView angleText;
    private TextView spacingText;
    private SeekBar carrierSeek;
    private SeekBar angleSeek;
    private SeekBar spacingSeek;

    private volatile String lastDecoded = "";
    private volatile String pendingBeamId;
    private volatile File pendingBeamFile;
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
        if (Build.VERSION.SDK_INT >= 26) w.getDecorView().setSystemUiVisibility(0);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("INFO BEAM Ω", 28, AQUA, true);
        title.setLetterSpacing(0.08f);
        root.addView(title);
        TextView sub = text("Directed information link • ultrasonic data + parametric voice", 14, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        statusText = text("ENGINE IDLE", 14, GOLD, true);
        statusText.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusText.setBackground(rounded(Color.rgb(28, 39, 32), dp(12), GOLD, 1));
        root.addView(statusText, matchWrap());

        LinearLayout messageCard = card(root, "INFORMATION PAYLOAD", "Type the information you want to transmit. Ultrasonic data mode sends the bytes, not audible speech.");
        messageInput = new EditText(this);
        messageInput.setTextColor(TEXT);
        messageInput.setHintTextColor(Color.rgb(110, 135, 132));
        messageInput.setHint("Example: Meet me at the north entrance at 9:30.");
        messageInput.setTextSize(17f);
        messageInput.setGravity(Gravity.TOP | Gravity.START);
        messageInput.setMinLines(4);
        messageInput.setMaxLines(8);
        messageInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        messageInput.setBackground(rounded(Color.rgb(6, 14, 17), dp(10), Color.rgb(55, 84, 83), 1));
        messageCard.addView(messageInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout dataCard = card(root, "ULTRASONIC DATA BEAM", "One InfoBeam phone transmits an ultrasonic FSK packet. A receiver running InfoBeam verifies the CRC, reconstructs the text, then can play it through its earpiece.");

        profileSpinner = new Spinner(this);
        ArrayAdapter<UltrasonicFsk.Profile> adapter = new ArrayAdapter<UltrasonicFsk.Profile>(this, android.R.layout.simple_spinner_dropdown_item, UltrasonicFsk.Profile.values()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(TEXT);
                v.setTextSize(15f);
                v.setPadding(dp(10), dp(10), dp(10), dp(10));
                v.setBackgroundColor(Color.TRANSPARENT);
                return v;
            }
        };
        profileSpinner.setAdapter(adapter);
        profileSpinner.setSelection(0);
        dataCard.addView(profileSpinner, matchWrap());

        profileHint = text(UltrasonicFsk.Profile.BALANCED.note, 12, MUTED, false);
        profileHint.setPadding(0, dp(4), 0, dp(8));
        dataCard.addView(profileHint);
        profileSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            UltrasonicFsk.Profile p = UltrasonicFsk.Profile.values()[position];
            profileHint.setText(p.note + " • " + Math.round(p.zeroHz) + " / " + Math.round(p.oneHz) + " Hz");
        }));

        Button tx = button("TRANSMIT TEXT BEAM", AQUA, Color.rgb(1, 32, 29));
        tx.setOnClickListener(v -> transmitText());
        dataCard.addView(tx, matchWrapTop(6));

        Button rx = button("LISTEN FOR BEAM", GOLD, Color.rgb(36, 28, 4));
        rx.setOnClickListener(v -> startListening());
        dataCard.addView(rx, matchWrapTop(8));

        Button stop = button("STOP ALL", RED, Color.rgb(40, 10, 13));
        stop.setOnClickListener(v -> stopAll("Stopped."));
        dataCard.addView(stop, matchWrapTop(8));

        LinearLayout receivedCard = card(root, "RECEIVER", "A valid packet only appears after the sync pattern and CRC both pass.");
        decodedText = text("No verified beam received yet.", 17, TEXT, false);
        decodedText.setPadding(dp(12), dp(12), dp(12), dp(12));
        decodedText.setBackground(rounded(Color.rgb(7, 15, 17), dp(10), Color.rgb(48, 76, 75), 1));
        receivedCard.addView(decodedText, matchWrap());

        Button privatePlay = button("PLAY LAST THROUGH EARPIECE", AQUA, Color.rgb(2, 30, 28));
        privatePlay.setOnClickListener(v -> playLastPrivately());
        receivedCard.addView(privatePlay, matchWrapTop(8));

        Button copy = button("COPY LAST MESSAGE", TEXT, Color.rgb(26, 38, 41));
        copy.setOnClickListener(v -> copyLast());
        receivedCard.addView(copy, matchWrapTop(8));

        LinearLayout voiceCard = card(root, "DIRECT VOICE BEAM", "TTS speech is encoded onto an ultrasonic carrier. The 40 kHz path is intended for an external ultrasonic transducer/parametric array. The phone-only path is an experiment because phone speakers are not true parametric arrays.");

        carrierText = text("External carrier: 40.0 kHz", 14, GOLD, true);
        voiceCard.addView(carrierText);
        carrierSeek = new SeekBar(this);
        carrierSeek.setMax(240);
        carrierSeek.setProgress(220);
        carrierSeek.setProgressTintList(ColorStateList.valueOf(AQUA));
        carrierSeek.setThumbTintList(ColorStateList.valueOf(AQUA));
        carrierSeek.setOnSeekBarChangeListener(new SimpleSeekListener(progress -> {
            double hz = 18_000 + progress * 100.0;
            carrierText.setText(String.format(Locale.US, "External carrier: %.1f kHz", hz / 1000.0));
        }));
        voiceCard.addView(carrierSeek, matchWrap());

        angleText = text("Target angle: 0°", 14, TEXT, true);
        angleText.setPadding(0, dp(6), 0, 0);
        voiceCard.addView(angleText);
        angleSeek = new SeekBar(this);
        angleSeek.setMax(120);
        angleSeek.setProgress(60);
        angleSeek.setProgressTintList(ColorStateList.valueOf(GOLD));
        angleSeek.setThumbTintList(ColorStateList.valueOf(GOLD));
        angleSeek.setOnSeekBarChangeListener(new SimpleSeekListener(progress -> angleText.setText("Target angle: " + (progress - 60) + "°")));
        voiceCard.addView(angleSeek, matchWrap());

        spacingText = text("Array spacing: 10 mm", 14, TEXT, true);
        spacingText.setPadding(0, dp(6), 0, 0);
        voiceCard.addView(spacingText);
        spacingSeek = new SeekBar(this);
        spacingSeek.setMax(59);
        spacingSeek.setProgress(9);
        spacingSeek.setProgressTintList(ColorStateList.valueOf(AQUA));
        spacingSeek.setThumbTintList(ColorStateList.valueOf(AQUA));
        spacingSeek.setOnSeekBarChangeListener(new SimpleSeekListener(progress -> spacingText.setText("Array spacing: " + (progress + 1) + " mm")));
        voiceCard.addView(spacingSeek, matchWrap());

        Button externalBeam = button("40 kHz PARAMETRIC BEAM", AQUA, Color.rgb(2, 31, 28));
        externalBeam.setOnClickListener(v -> startVoiceBeam(true));
        voiceCard.addView(externalBeam, matchWrapTop(8));

        Button phoneBeam = button("PHONE CARRIER EXPERIMENT", GOLD, Color.rgb(36, 28, 4));
        phoneBeam.setOnClickListener(v -> startVoiceBeam(false));
        voiceCard.addView(phoneBeam, matchWrapTop(8));

        LinearLayout hardwareCard = card(root, "HARDWARE SCAN", "InfoBeam checks Android's reported output routes and sample rates. A USB high-rate DAC/driver is the cleanest path to a 40 kHz transducer array.");
        hardwareText = text("Scanning…", 13, MUTED, false);
        hardwareCard.addView(hardwareText);
        Button rescan = button("RESCAN AUDIO OUTPUTS", TEXT, Color.rgb(25, 38, 41));
        rescan.setOnClickListener(v -> scanHardware());
        hardwareCard.addView(rescan, matchWrapTop(8));

        LinearLayout noteCard = card(root, "BEAM LOGIC", "The APK contains three different paths so one weak link does not sink the whole concept.");
        TextView note = text(
                "1 • Data Beam: ultrasonic FSK text packet with CRC verification.\n\n" +
                "2 • Private Receive: decoded text can stay on-screen or be spoken through the receiver earpiece.\n\n" +
                "3 • Parametric Voice: square-root preconditioned AM plus stereo phase steering for an external ultrasonic array.\n\n" +
                "Phone Carrier Experiment keeps the same modulation engine near 19.2 kHz, but its directionality depends heavily on the phone speaker hardware.",
                13, MUTED, false);
        noteCard.addView(note);

        return scroll;
    }

    private void initTts() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                int result = tts.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(1.0f);
                tts.setPitch(1.0f);
                runOnUiThread(() -> setStatus("ENGINE READY • type information or listen for a beam", AQUA));
            } else {
                runOnUiThread(() -> setStatus("TTS unavailable • data beam still works", RED));
            }
        });

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}

            @Override public void onDone(String utteranceId) {
                if (utteranceId != null && utteranceId.equals(pendingBeamId)) {
                    File file = pendingBeamFile;
                    if (file != null) launchSynthesizedBeam(file);
                    return;
                }
                if (utteranceId != null && utteranceId.startsWith("private-")) releasePrivateRoute();
            }

            @Override @Deprecated public void onError(String utteranceId) {
                onError(utteranceId, TextToSpeech.ERROR);
            }

            @Override public void onError(String utteranceId, int errorCode) {
                if (utteranceId != null && utteranceId.equals(pendingBeamId)) runOnUiThread(() -> setStatus("TTS synthesis failed: " + errorCode, RED));
                if (utteranceId != null && utteranceId.startsWith("private-")) releasePrivateRoute();
            }
        });
    }

    private void transmitText() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) {
            setStatus("Enter information first.", RED);
            return;
        }
        stopAll(null);
        UltrasonicFsk.Profile profile = (UltrasonicFsk.Profile) profileSpinner.getSelectedItem();
        fsk.transmitText(text, profile, fskListener);
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
            runOnUiThread(() -> {
                decodedText.setText(text + "\n\n✓ CRC VERIFIED • " + profile.label);
                decodedText.setTextColor(AQUA);
            });
        }

        @Override public void onStopped() {}
    };

    private void startVoiceBeam(boolean external) {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) {
            setStatus("Enter information first.", RED);
            return;
        }
        if (!ttsReady || tts == null) {
            setStatus("Android TTS is still initializing.", RED);
            return;
        }

        AudioDeviceInfo device = external ? findBestExternalOutput() : findBuiltInSpeaker();
        if (external && device == null) {
            setStatus("No external high-rate audio route detected. Connect a USB audio/ultrasonic output or use Phone Carrier Experiment.", RED);
            return;
        }

        stopAll(null);
        int rate = external ? bestSampleRate(device) : 48_000;
        double carrier = external ? 18_000 + carrierSeek.getProgress() * 100.0 : 19_200.0;
        double angle = angleSeek.getProgress() - 60.0;
        double spacing = spacingSeek.getProgress() + 1.0;

        File out = new File(getCacheDir(), "infobeam_tts_" + System.nanoTime() + ".wav");
        if (out.exists()) out.delete();
        String id = "beam-" + System.nanoTime();
        pendingBeamId = id;
        pendingBeamFile = out;
        pendingBeamExternal = external;
        pendingBeamRate = rate;
        pendingBeamCarrier = carrier;
        pendingBeamAngle = angle;
        pendingBeamSpacing = spacing;
        pendingBeamDevice = device;

        setStatus("Synthesizing speech for beam…", GOLD);
        Bundle params = new Bundle();
        int result = tts.synthesizeToFile(text, params, out, id);
        if (result != TextToSpeech.SUCCESS) setStatus("TTS engine rejected the synthesis request.", RED);
    }

    private void launchSynthesizedBeam(File file) {
        new Thread(() -> {
            try {
                if (!file.exists() || file.length() <= 44) throw new IllegalStateException("TTS returned an empty audio file.");
                PcmWav.Data pcm = PcmWav.read(file);
                parametric.play(pcm, pendingBeamRate, pendingBeamCarrier, pendingBeamAngle, pendingBeamSpacing,
                        pendingBeamDevice, pendingBeamExternal, parametricListener);
            } catch (Throwable t) {
                runOnUiThread(() -> setStatus("Voice beam prep error: " + safeMessage(t), RED));
            } finally {
                file.delete();
            }
        }, "InfoBeam-TTS-Decode").start();
    }

    private final ParametricAudio.Listener parametricListener = new ParametricAudio.Listener() {
        @Override public void onStatus(String text) {
            runOnUiThread(() -> setStatus(text, text.startsWith("Beam error") ? RED : GOLD));
        }
        @Override public void onStopped() {}
    };

    private void playLastPrivately() {
        String text = lastDecoded;
        if (text == null || text.trim().isEmpty()) {
            setStatus("Receive a verified message first.", RED);
            return;
        }
        if (!ttsReady || tts == null) {
            setStatus("TTS is unavailable.", RED);
            return;
        }
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            tts.setAudioAttributes(attrs);
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo ear = null;
                for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) { ear = d; break; }
                }
                if (ear != null) audioManager.setCommunicationDevice(ear);
            } else {
                audioManager.setSpeakerphoneOn(false);
            }

            String id = "private-" + System.nanoTime();
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
            if (result == TextToSpeech.SUCCESS) setStatus("Playing decoded information through the earpiece route.", AQUA);
            else {
                releasePrivateRoute();
                setStatus("Private TTS playback failed.", RED);
            }
        } catch (Throwable t) {
            releasePrivateRoute();
            setStatus("Earpiece route error: " + safeMessage(t), RED);
        }
    }

    private void releasePrivateRoute() {
        try {
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice();
        } catch (Throwable ignored) {}
        try { audioManager.setMode(AudioManager.MODE_NORMAL); } catch (Throwable ignored) {}
    }

    private void copyLast() {
        String text = lastDecoded;
        if (text == null || text.isEmpty()) {
            setStatus("Nothing to copy yet.", RED);
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("InfoBeam", text));
        setStatus("Decoded message copied.", AQUA);
    }

    private void scanHardware() {
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        StringBuilder sb = new StringBuilder();
        int nativeRate = 48_000;
        try {
            String prop = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            if (prop != null) nativeRate = Integer.parseInt(prop);
        } catch (Throwable ignored) {}
        sb.append("Native output property: ").append(nativeRate).append(" Hz\n\n");

        for (AudioDeviceInfo d : devices) {
            sb.append("• ").append(typeName(d.getType())).append(" • ").append(d.getProductName()).append('\n');
            int[] rates = d.getSampleRates();
            if (rates != null && rates.length > 0) sb.append("  reported rates: ").append(Arrays.toString(rates)).append('\n');
            else sb.append("  reported rates: unspecified\n");
        }
        AudioDeviceInfo best = findBestExternalOutput();
        if (best != null) {
            sb.append("\nEXTERNAL BEAM ROUTE: ").append(best.getProductName())
                    .append(" • request ").append(bestSampleRate(best)).append(" Hz");
        } else {
            sb.append("\nNo external high-rate route detected. Data Beam and Phone Carrier Experiment remain available.");
        }
        if (hardwareText != null) hardwareText.setText(sb.toString());
    }

    private AudioDeviceInfo findBestExternalOutput() {
        AudioDeviceInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int score = outputScore(d);
            if (score > bestScore) {
                bestScore = score;
                best = score > 0 ? d : best;
            }
        }
        return best;
    }

    private AudioDeviceInfo findBuiltInSpeaker() {
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return d;
        }
        return null;
    }

    private int bestSampleRate(AudioDeviceInfo d) {
        if (d == null) return 48_000;
        int[] rates = d.getSampleRates();
        int best = 0;
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
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return 70;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return 65;
            default: return 0;
        }
    }

    private String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Built-in speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "Earpiece";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB audio";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired headphones";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "Bluetooth LE";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "Digital line";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            case AudioDeviceInfo.TYPE_HDMI_ARC: return "HDMI ARC";
            case AudioDeviceInfo.TYPE_HDMI_EARC: return "HDMI eARC";
            default: return "Audio type " + type;
        }
    }

    private void stopAll(String status) {
        fsk.stop();
        parametric.stop();
        if (tts != null) {
            try { tts.stop(); } catch (Throwable ignored) {}
        }
        releasePrivateRoute();
        if (status != null) setStatus(status, GOLD);
    }

    private void setStatus(String text, int color) {
        if (statusText == null) return;
        statusText.setText(text);
        statusText.setTextColor(color);
    }

    @Override
    protected void onDestroy() {
        stopAll(null);
        if (tts != null) {
            try { tts.shutdown(); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    private LinearLayout card(LinearLayout root, String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setBackground(rounded(CARD, dp(14), Color.rgb(37, 61, 62), 1));
        LinearLayout.LayoutParams lp = matchWrapTop(14);
        root.addView(box, lp);

        TextView t = text(title, 16, AQUA, true);
        box.addView(t);
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
        private final IntConsumer consumer;
        SimpleSeekListener(IntConsumer consumer) { this.consumer = consumer; }
        @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { consumer.accept(progress); }
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }

    private interface PositionConsumer { void accept(int position); }

    private static final class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final PositionConsumer consumer;
        SimpleItemSelectedListener(PositionConsumer consumer) { this.consumer = consumer; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { consumer.accept(position); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}

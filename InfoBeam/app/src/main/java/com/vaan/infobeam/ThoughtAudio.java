package com.vaan.infobeam;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v2 InnerSpeech renderer.
 *
 * The old engine started with ordinary voiced TTS and then colored it. This engine
 * changes the source itself. A multi-band analysis/synthesis vocoder extracts the
 * moving speech/formant envelopes, discards most of the original pitch/harmonic
 * identity, and rebuilds speech from noise-band excitation. A small low-frequency
 * body path and a tiny dry path are mixed back only where a profile asks for them.
 *
 * Result: much less "person speaking through headphones" and much more compact,
 * pitch-poor, subvocal/whisper-like speech. Playback remains diotic on headphones
 * to minimize external left/right location cues.
 */
public final class ThoughtAudio {
    public enum Profile {
        INNER_VOICE("Inner Voice"),
        SUBVOCAL("Subvocal"),
        SKULL_VOICE("Skull Voice"),
        WHISPER_CORTEX("Whisper Cortex"),
        HYBRID("Hybrid Neural"),
        CORTEX_LOCK_EXTREME("Cortex Lock EXTREME"),
        PURE_INNER_SPEECH("PURE INNER SPEECH");

        public final String label;
        Profile(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public static final class Settings {
        public final Profile profile;
        public final float intensity;
        public final float bone;
        public final float whisper;
        public final float depth;
        public final float impact;

        public Settings(Profile profile, float intensity, float bone, float whisper, float depth) {
            this(profile, intensity, bone, whisper, depth, 0.72f);
        }

        public Settings(Profile profile, float intensity, float bone, float whisper, float depth, float impact) {
            this.profile = profile == null ? Profile.PURE_INNER_SPEECH : profile;
            this.intensity = clamp01(intensity);
            this.bone = clamp01(bone);
            this.whisper = clamp01(whisper);
            this.depth = clamp01(depth);
            this.impact = clamp01(impact);
        }

        public static Settings defaultHybrid(float intensity) {
            return new Settings(Profile.PURE_INNER_SPEECH, intensity, 0.34f, 0.62f, 0.92f, 0.88f);
        }
    }

    public interface Listener {
        void onStatus(String text);
        void onStopped();
    }

    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private volatile AudioTrack activeTrack;
    private volatile AudioManager activeManager;
    private volatile boolean communicationRoute;

    public void stop() {
        stopped.set(true);
        AudioTrack t = activeTrack;
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) {}
            try { t.flush(); } catch (Throwable ignored) {}
            try { t.stop(); } catch (Throwable ignored) {}
            try { t.release(); } catch (Throwable ignored) {}
            activeTrack = null;
        }
        releaseRoute();
    }

    public void play(Context context, PcmWav.Data pcm, float intensity, Listener listener) {
        play(context, pcm, Settings.defaultHybrid(intensity), listener);
    }

    public void play(Context context, PcmWav.Data pcm, Settings settings, Listener listener) {
        stop();
        stopped.set(false);
        new Thread(() -> {
            try {
                listener.onStatus("RESYNTHESIZING • removing normal voice pitch…");
                PcmWav.Data inner = resynthesize(pcm, settings);
                render(context.getApplicationContext(), inner, settings, listener);
            } catch (Throwable t) {
                listener.onStatus("Thought playback error: " + safeMessage(t));
                stopped.set(true);
                listener.onStopped();
            }
        }, "InfoBeam-InnerSpeech-v2").start();
    }

    /**
     * Public so the direct parametric beam can carry the same pitch-stripped source
     * instead of ordinary TTS.
     */
    public static PcmWav.Data resynthesize(PcmWav.Data pcm, Settings cfg) {
        if (pcm == null || pcm.samples == null || pcm.samples.length == 0) {
            throw new IllegalArgumentException("Speech source is empty.");
        }

        final int rate = pcm.sampleRate;
        final float[] src = pcm.samples;
        final float[] out = new float[src.length];

        final double[] wantedCenters = {180, 290, 450, 680, 980, 1380, 1950, 2750, 3850, 5100};
        int usable = 0;
        for (double f : wantedCenters) if (f < rate * 0.43) usable++;
        if (usable < 5) usable = Math.min(5, wantedCenters.length);

        Biquad[] analysis = new Biquad[usable];
        Biquad[] synthesis = new Biquad[usable];
        OnePoleLowPass[] envelopes = new OnePoleLowPass[usable];
        float[] weights = new float[usable];

        for (int b = 0; b < usable; b++) {
            double center = Math.min(wantedCenters[b], rate * 0.41);
            double q = b < 2 ? 0.72 : (b < 6 ? 0.90 : 1.05);
            analysis[b] = Biquad.bandPass(rate, center, q);
            synthesis[b] = Biquad.bandPass(rate, center, q);
            envelopes[b] = new OnePoleLowPass(rate, 38.0 + b * 1.2);
            weights[b] = bandWeight(b, usable);
        }

        OnePoleHighPass dcHp = new OnePoleHighPass(rate, 72.0);
        OnePoleLowPass boneLp = new OnePoleLowPass(rate, 940.0);
        OnePoleLowPass envSlow = new OnePoleLowPass(rate, 14.0);
        OnePoleLowPass envFast = new OnePoleLowPass(rate, 48.0);
        OnePoleLowPass noiseBody = new OnePoleLowPass(rate, 1050.0);
        OnePoleHighPass airHp = new OnePoleHighPass(rate, 2100.0);

        int rng = 0x73A91F2D;
        float previousDry = 0f;
        float profileDry = dryMix(cfg.profile);
        float profileVoc = vocoderMix(cfg.profile);
        float profileBone = boneMix(cfg.profile);
        float profileAir = airMix(cfg.profile);

        for (int i = 0; i < src.length; i++) {
            float dry = dcHp.process(src[i]);
            float overall = envSlow.process(Math.abs(dry));
            float fast = envFast.process(Math.abs(dry));

            rng = rng * 1664525 + 1013904223;
            float white = (((rng >>> 8) & 0x00ffffff) / 8388607.5f) - 1f;

            float voc = 0f;
            for (int b = 0; b < usable; b++) {
                float analyzed = analysis[b].process(dry);
                float env = envelopes[b].process(Math.abs(analyzed));

                // Nonlinear envelope expansion raises quiet consonants while keeping vowels compact.
                float shapedEnv = (float) Math.pow(Math.max(0f, env), 0.72);
                float excited = synthesis[b].process(white);
                voc += excited * shapedEnv * weights[b];
            }

            // Normalize channel-vocoder energy and drive it by speech presence.
            voc *= 3.2f / Math.max(5, usable);
            voc *= Math.min(1.35f, 0.28f + fast * 6.6f);

            float bone = boneLp.process(dry);
            float breath = airHp.process(white - noiseBody.process(white));
            breath *= Math.min(1f, fast * 7.5f);

            float transientEdge = dry - previousDry;
            previousDry = dry;

            float mix = voc * profileVoc
                    + dry * profileDry
                    + bone * profileBone * cfg.bone
                    + breath * profileAir * cfg.whisper;

            // Depth is implemented as a compact low/body bias, not a room/reverb effect.
            mix += bone * cfg.depth * 0.10f;

            // Quiet-phoneme lift. This is intentionally based on the source envelope,
            // so speech remains legible even after pitch stripping.
            float target = 0.19f + cfg.impact * 0.08f;
            float upward = target / (0.018f + overall);
            upward = clampRange(upward, 0.90f, 2.1f + cfg.impact * 2.8f);
            mix *= 0.72f + upward * (0.28f + cfg.impact * 0.16f);

            // Consonant edge survives the noise resynthesis without reintroducing a voiced carrier.
            mix += transientEdge * (0.025f + cfg.impact * 0.045f);

            double drive = 1.65 + cfg.intensity * 1.85 + cfg.impact * 0.95;
            if (cfg.profile == Profile.PURE_INNER_SPEECH) drive += 0.35;
            if (cfg.profile == Profile.CORTEX_LOCK_EXTREME) drive += 0.55;

            out[i] = (float) Math.tanh(mix * drive);
        }

        normalizeForSpeech(out, 0.86f);
        return new PcmWav.Data(out, rate);
    }

    private void render(Context context, PcmWav.Data pcm, Settings cfg, Listener listener) {
        AudioTrack track = null;
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            activeManager = manager;
            AudioDeviceInfo route = choosePrivateRoute(manager);
            if (route == null) throw new IllegalStateException("No private audio route was found.");

            boolean stereo = isHeadphoneLike(route.getType());
            boolean earpiece = route.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
            int outRate = chooseRate(route);
            int channelMask = stereo ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int channels = stereo ? 2 : 1;
            int min = AudioTrack.getMinBufferSize(outRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            int buffer = Math.max(min > 0 ? min : 0, 24_576);

            if (earpiece) {
                communicationRoute = true;
                try { manager.setMode(AudioManager.MODE_IN_COMMUNICATION); } catch (Throwable ignored) {}
                if (Build.VERSION.SDK_INT >= 31) {
                    try { manager.setCommunicationDevice(route); } catch (Throwable ignored) {}
                } else {
                    try { manager.setSpeakerphoneOn(false); } catch (Throwable ignored) {}
                }
            }

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(earpiece ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(outRate)
                            .setChannelMask(channelMask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(buffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            activeTrack = track;
            try { track.setPreferredDevice(route); } catch (Throwable ignored) {}
            try { track.setVolume(1.0f); } catch (Throwable ignored) {}

            double step = pcm.sampleRate / (double) outRate;
            long totalFrames = Math.max(1L, (long) Math.ceil(pcm.samples.length / step));
            short[] block = new short[1024 * channels];
            int fadeFrames = Math.max(1, (int) (outRate * 0.010));
            double srcPos = 0.0;
            long frame = 0;

            float routeGain = earpiece ? 0.88f : 0.84f;
            track.play();
            listener.onStatus("INNER SPEECH • " + cfg.profile.label + " • " + routeName(route));

            while (frame < totalFrames && !stopped.get()) {
                int frames = (int) Math.min(1024, totalFrames - frame);
                int cursor = 0;

                for (int i = 0; i < frames; i++) {
                    float s = interpolate(pcm.samples, srcPos);
                    srcPos += step;

                    // Final compacting stage after resampling. It raises density, not the device volume ceiling.
                    float compact = (float) Math.tanh(s * (1.05 + cfg.impact * 0.72));
                    float fi = Math.min(1f, (frame + i) / (float) fadeFrames);
                    float fo = Math.min(1f, (totalFrames - 1 - (frame + i)) / (float) fadeFrames);
                    float sample = hardLimit(compact * routeGain * Math.max(0f, Math.min(fi, fo)), 0.91f);
                    short q = (short) Math.round(sample * 32767.0);

                    if (stereo) {
                        block[cursor++] = q;
                        block[cursor++] = q;
                    } else {
                        block[cursor++] = q;
                    }
                }

                int count = frames * channels;
                int offset = 0;
                while (offset < count && !stopped.get()) {
                    int wrote = track.write(block, offset, count - offset, AudioTrack.WRITE_BLOCKING);
                    if (wrote < 0) throw new IllegalStateException("InnerSpeech AudioTrack write failed: " + wrote);
                    offset += wrote;
                }
                frame += frames;
            }

            if (!stopped.get()) listener.onStatus("Inner Speech playback complete.");
        } catch (Throwable t) {
            listener.onStatus("Thought playback error: " + safeMessage(t));
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
            activeTrack = null;
            stopped.set(true);
            releaseRoute();
            listener.onStopped();
        }
    }

    private static float dryMix(Profile p) {
        switch (p) {
            case PURE_INNER_SPEECH: return 0.025f;
            case WHISPER_CORTEX: return 0.035f;
            case SUBVOCAL: return 0.075f;
            case SKULL_VOICE: return 0.13f;
            case INNER_VOICE: return 0.17f;
            case CORTEX_LOCK_EXTREME: return 0.10f;
            default: return 0.11f;
        }
    }

    private static float vocoderMix(Profile p) {
        switch (p) {
            case PURE_INNER_SPEECH: return 1.28f;
            case WHISPER_CORTEX: return 1.20f;
            case SUBVOCAL: return 1.02f;
            case SKULL_VOICE: return 0.62f;
            case INNER_VOICE: return 0.76f;
            case CORTEX_LOCK_EXTREME: return 1.02f;
            default: return 0.91f;
        }
    }

    private static float boneMix(Profile p) {
        switch (p) {
            case PURE_INNER_SPEECH: return 0.24f;
            case WHISPER_CORTEX: return 0.10f;
            case SUBVOCAL: return 0.35f;
            case SKULL_VOICE: return 0.62f;
            case INNER_VOICE: return 0.28f;
            case CORTEX_LOCK_EXTREME: return 0.46f;
            default: return 0.34f;
        }
    }

    private static float airMix(Profile p) {
        switch (p) {
            case PURE_INNER_SPEECH: return 0.34f;
            case WHISPER_CORTEX: return 0.52f;
            case SUBVOCAL: return 0.18f;
            case SKULL_VOICE: return 0.08f;
            case INNER_VOICE: return 0.11f;
            case CORTEX_LOCK_EXTREME: return 0.18f;
            default: return 0.16f;
        }
    }

    private static float bandWeight(int band, int count) {
        float t = count <= 1 ? 0f : band / (float) (count - 1);
        // Slightly favor the speech-intelligibility middle bands over very low/high bands.
        return 0.72f + (float) Math.sin(Math.PI * t) * 0.58f;
    }

    private static void normalizeForSpeech(float[] data, float targetPeak) {
        float peak = 0f;
        double sum2 = 0.0;
        for (float v : data) {
            peak = Math.max(peak, Math.abs(v));
            sum2 += v * (double) v;
        }
        if (peak < 1e-6f) return;
        double rms = Math.sqrt(sum2 / Math.max(1, data.length));
        float peakGain = targetPeak / peak;
        float rmsGain = rms > 1e-5 ? (float) (0.23 / rms) : peakGain;
        float gain = Math.min(peakGain, Math.min(2.2f, rmsGain));
        if (gain < 0.45f) gain = 0.45f;
        for (int i = 0; i < data.length; i++) data[i] = hardLimit(data[i] * gain, 0.90f);
    }

    private AudioDeviceInfo choosePrivateRoute(AudioManager manager) {
        AudioDeviceInfo earpiece = null, wired = null, usb = null, bluetooth = null;
        for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET: if (wired == null) wired = d; break;
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE: if (usb == null) usb = d; break;
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: if (bluetooth == null) bluetooth = d; break;
                case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: if (earpiece == null) earpiece = d; break;
                default: break;
            }
        }
        if (wired != null) return wired;
        if (usb != null) return usb;
        if (bluetooth != null) return bluetooth;
        return earpiece;
    }

    private int chooseRate(AudioDeviceInfo route) {
        int[] rates = route.getSampleRates();
        int best = 0;
        if (rates != null) {
            for (int r : rates) {
                if (r >= 16_000 && r <= 96_000 && (best == 0 || Math.abs(r - 48_000) < Math.abs(best - 48_000))) best = r;
            }
        }
        return best > 0 ? best : 48_000;
    }

    private boolean isHeadphoneLike(int type) {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
    }

    private String routeName(AudioDeviceInfo route) {
        String p = route.getProductName() == null ? "" : route.getProductName().toString();
        if (!p.trim().isEmpty()) return p;
        return route.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ? "phone earpiece" : "private output";
    }

    private void releaseRoute() {
        AudioManager m = activeManager;
        if (m == null) return;
        if (communicationRoute) {
            try { if (Build.VERSION.SDK_INT >= 31) m.clearCommunicationDevice(); } catch (Throwable ignored) {}
            try { m.setMode(AudioManager.MODE_NORMAL); } catch (Throwable ignored) {}
        }
        communicationRoute = false;
        activeManager = null;
    }

    private static float interpolate(float[] data, double pos) {
        if (data.length == 0) return 0f;
        int i = (int) pos;
        if (i <= 0) return data[0];
        if (i >= data.length - 1) return data[data.length - 1];
        double f = pos - i;
        return (float) (data[i] + (data[i + 1] - data[i]) * f);
    }

    private static float hardLimit(float v, float ceiling) {
        return Math.max(-ceiling, Math.min(ceiling, v));
    }

    private static float clampRange(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double z1, z2;

        private Biquad(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2;
        }

        static Biquad bandPass(int rate, double hz, double q) {
            double w0 = 2.0 * Math.PI * Math.max(40.0, Math.min(hz, rate * 0.45)) / rate;
            double cos = Math.cos(w0);
            double sin = Math.sin(w0);
            double alpha = sin / (2.0 * Math.max(0.25, q));
            double a0 = 1.0 + alpha;
            return new Biquad(alpha / a0, 0.0, -alpha / a0,
                    (-2.0 * cos) / a0, (1.0 - alpha) / a0);
        }

        float process(float x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return (float) y;
        }
    }

    private static final class OnePoleLowPass {
        private final double a;
        private float y;
        OnePoleLowPass(int rate, double hz) {
            double dt = 1.0 / rate;
            double rc = 1.0 / (2.0 * Math.PI * hz);
            a = dt / (rc + dt);
        }
        float process(float x) {
            y += (float) (a * (x - y));
            return y;
        }
    }

    private static final class OnePoleHighPass {
        private final double a;
        private float y, lastX;
        OnePoleHighPass(int rate, double hz) {
            double dt = 1.0 / rate;
            double rc = 1.0 / (2.0 * Math.PI * hz);
            a = rc / (rc + dt);
        }
        float process(float x) {
            y = (float) (a * (y + x - lastX));
            lastX = x;
            return y;
        }
    }
}

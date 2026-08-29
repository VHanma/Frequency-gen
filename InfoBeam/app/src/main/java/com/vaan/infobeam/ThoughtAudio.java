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
 * Head-locked private speech renderer.
 * v1.3 uses parallel low/body/formant bands, dual micro-delay cranial coloration,
 * envelope-driven breath texture, and dense soft-limited compression. The final
 * output remains hard limited below full scale and Android's normal volume control
 * still governs acoustic level.
 */
public final class ThoughtAudio {
    public enum Profile {
        INNER_VOICE("Inner Voice"),
        SUBVOCAL("Subvocal"),
        SKULL_VOICE("Skull Voice"),
        WHISPER_CORTEX("Whisper Cortex"),
        HYBRID("Hybrid Neural"),
        CORTEX_LOCK_EXTREME("Cortex Lock EXTREME");

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
            this.profile = profile == null ? Profile.HYBRID : profile;
            this.intensity = clamp01(intensity);
            this.bone = clamp01(bone);
            this.whisper = clamp01(whisper);
            this.depth = clamp01(depth);
            this.impact = clamp01(impact);
        }

        public static Settings defaultHybrid(float intensity) {
            return new Settings(Profile.HYBRID, intensity, 0.68f, 0.20f, 0.84f, 0.78f);
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
        new Thread(() -> render(context.getApplicationContext(), pcm, settings, listener), "InfoBeam-CortexLock").start();
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
            int fadeFrames = Math.max(1, (int) (outRate * 0.012));

            OnePoleHighPass hp = new OnePoleHighPass(outRate, 58.0);
            OnePoleLowPass topLp = new OnePoleLowPass(outRate, profileTopHz(cfg.profile));
            OnePoleLowPass bassLp = new OnePoleLowPass(outRate, 520.0);
            OnePoleLowPass bodyLp = new OnePoleLowPass(outRate, 1120.0);
            OnePoleLowPass formantLp = new OnePoleLowPass(outRate, 2450.0);
            OnePoleLowPass envFast = new OnePoleLowPass(outRate, 30.0);
            OnePoleLowPass envSlow = new OnePoleLowPass(outRate, 7.0);
            OnePoleLowPass noiseLow = new OnePoleLowPass(outRate, 1150.0);
            DelayLine microA = new DelayLine(Math.max(3, (int) (outRate * 0.00018)));
            DelayLine microB = new DelayLine(Math.max(5, (int) (outRate * 0.00062)));

            double srcPos = 0.0;
            long frame = 0;
            int rng = 0x6F2A4B19;
            float previous = 0f;

            float routeGain = earpiece ? 0.98f : 0.94f;
            if (cfg.profile != Profile.CORTEX_LOCK_EXTREME) routeGain -= 0.05f * (1f - cfg.intensity);

            track.play();
            listener.onStatus("CORTEX LOCK • " + cfg.profile.label + " • " + routeName(route));

            while (frame < totalFrames && !stopped.get()) {
                int frames = (int) Math.min(1024, totalFrames - frame);
                int cursor = 0;

                for (int i = 0; i < frames; i++) {
                    float raw = interpolate(pcm.samples, srcPos);
                    srcPos += step;

                    float clean = topLp.process(hp.process(raw));
                    float bass = bassLp.process(clean);
                    float bodyBase = bodyLp.process(clean);
                    float formantBase = formantLp.process(clean);
                    float lowMid = bodyBase - bass;
                    float formant = formantBase - bodyBase;
                    float presence = clean - formantBase;

                    float delayedA = microA.process(clean);
                    float delayedB = microB.process(clean);
                    float fastEnv = envFast.process(Math.abs(clean));
                    float slowEnv = envSlow.process(Math.abs(clean));

                    // Parallel skull/body path. This is deliberately much stronger than v1.2.
                    float skull = bass * (0.72f + cfg.bone * 0.72f)
                            + lowMid * (0.28f + cfg.bone * 0.34f);

                    // Very short same-channel delays create a compact cranial coloration without room reverb.
                    float cranial = clean
                            + (delayedA - clean) * (0.10f + cfg.depth * 0.16f)
                            + (delayedB - clean) * (0.04f + cfg.depth * 0.10f);

                    // Speech-gated breath texture. Noise only appears where speech energy exists.
                    rng = rng * 1664525 + 1013904223;
                    float white = (((rng >>> 8) & 0x00ffffff) / 8388607.5f) - 1f;
                    float airy = white - noiseLow.process(white);
                    float breath = airy * Math.min(1f, fastEnv * 5.8f) * cfg.whisper;

                    float mix;
                    switch (cfg.profile) {
                        case SUBVOCAL:
                            mix = cranial * 0.68f + skull * 0.35f + formant * 0.18f + presence * 0.05f + breath * 0.06f;
                            break;
                        case SKULL_VOICE:
                            mix = cranial * 0.56f + skull * 0.66f + formant * 0.13f + delayedB * 0.08f + breath * 0.035f;
                            break;
                        case WHISPER_CORTEX:
                            mix = cranial * 0.48f + skull * 0.22f + formant * 0.31f + presence * 0.24f + breath * 0.34f;
                            break;
                        case INNER_VOICE:
                            mix = cranial * 0.88f + skull * 0.23f + formant * 0.16f + presence * 0.08f + breath * 0.045f;
                            break;
                        case CORTEX_LOCK_EXTREME:
                            mix = cranial * 0.86f + skull * 0.58f + formant * 0.31f + presence * 0.16f + breath * 0.11f;
                            break;
                        default:
                            mix = cranial * 0.76f + skull * 0.42f + formant * 0.24f + presence * 0.12f + breath * 0.09f;
                            break;
                    }

                    // Envelope-dependent upward compression. Quiet phonemes are pulled forward instead of disappearing.
                    float target = 0.30f + cfg.impact * 0.12f;
                    float comp = target / (0.045f + slowEnv);
                    comp = clampRange(comp, 0.92f, 1.55f + cfg.impact * 1.20f);
                    mix *= (0.82f + comp * (0.18f + cfg.impact * 0.24f));

                    // Presence/transient reinforcement keeps consonants inside the dense low/body mix.
                    float transientEdge = clean - previous;
                    previous = clean;
                    mix += presence * (0.07f + cfg.impact * 0.12f);
                    mix += transientEdge * (0.018f + cfg.impact * 0.025f);
                    mix += bass * cfg.depth * 0.13f;

                    double drive = 2.45 + cfg.intensity * 2.35 + cfg.impact * 1.55;
                    if (cfg.profile == Profile.CORTEX_LOCK_EXTREME) drive += 1.15;
                    float dense = (float) Math.tanh(mix * drive);

                    // Parallel pre-limiter signal adds body while tanh supplies a brick-wall-like soft ceiling.
                    dense = (float) Math.tanh(dense * (1.18 + cfg.impact * 0.42) + mix * 0.16);

                    float fi = Math.min(1f, (frame + i) / (float) fadeFrames);
                    float fo = Math.min(1f, (totalFrames - 1 - (frame + i)) / (float) fadeFrames);
                    float sample = hardLimit(dense * routeGain * Math.max(0f, Math.min(fi, fo)), 0.975f);
                    short q = (short) Math.round(sample * 32767.0);

                    if (stereo) {
                        // Exact diotic output minimizes interaural location cues and maximizes the in-head center image.
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
                    if (wrote < 0) throw new IllegalStateException("Thought AudioTrack write failed: " + wrote);
                    offset += wrote;
                }
                frame += frames;
            }

            if (!stopped.get()) listener.onStatus("Cortex Lock playback complete.");
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

    private static double profileTopHz(Profile p) {
        switch (p) {
            case SUBVOCAL: return 3200.0;
            case SKULL_VOICE: return 3650.0;
            case WHISPER_CORTEX: return 5600.0;
            case INNER_VOICE: return 4400.0;
            case CORTEX_LOCK_EXTREME: return 5200.0;
            default: return 4800.0;
        }
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

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static final class DelayLine {
        private final float[] data;
        private int pos;
        DelayLine(int samples) { data = new float[Math.max(2, samples)]; }
        float process(float x) {
            float y = data[pos];
            data[pos] = x;
            pos++;
            if (pos >= data.length) pos = 0;
            return y;
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

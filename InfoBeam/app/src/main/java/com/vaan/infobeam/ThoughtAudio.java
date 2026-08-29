package com.vaan.infobeam;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;

/** Head-locked private speech renderer. No room/reverb cues are added. */
public final class ThoughtAudio {
    public enum Profile {
        INNER_VOICE("Inner Voice"),
        SUBVOCAL("Subvocal"),
        SKULL_VOICE("Skull Voice"),
        WHISPER_CORTEX("Whisper Cortex"),
        HYBRID("Hybrid Neural");
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
        public Settings(Profile profile, float intensity, float bone, float whisper, float depth) {
            this.profile = profile == null ? Profile.HYBRID : profile;
            this.intensity = clamp01(intensity);
            this.bone = clamp01(bone);
            this.whisper = clamp01(whisper);
            this.depth = clamp01(depth);
        }
        public static Settings defaultHybrid(float intensity) {
            return new Settings(Profile.HYBRID, intensity, 0.62f, 0.24f, 0.78f);
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
        new Thread(() -> render(context.getApplicationContext(), pcm, settings, listener), "InfoBeam-NeuroThought").start();
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
            int buffer = Math.max(min > 0 ? min : 0, 24576);

            if (earpiece) {
                communicationRoute = true;
                try { manager.setMode(AudioManager.MODE_IN_COMMUNICATION); } catch (Throwable ignored) {}
                if (Build.VERSION.SDK_INT >= 31) {
                    try { manager.setCommunicationDevice(route); } catch (Throwable ignored) {}
                } else {
                    try { manager.setSpeakerphoneOn(false); } catch (Throwable ignored) {}
                }
            }

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
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

            double step = pcm.sampleRate / (double) outRate;
            long totalFrames = Math.max(1L, (long) Math.ceil(pcm.samples.length / step));
            short[] block = new short[1024 * channels];
            int fadeFrames = Math.max(1, (int) (outRate * 0.018));

            OnePoleHighPass hp = new OnePoleHighPass(outRate, 85.0);
            OnePoleLowPass lp = new OnePoleLowPass(outRate, profileTopHz(cfg.profile));
            OnePoleLowPass boneLp = new OnePoleLowPass(outRate, 720.0);
            OnePoleLowPass lowMidLp = new OnePoleLowPass(outRate, 1450.0);
            OnePoleLowPass presenceLp = new OnePoleLowPass(outRate, 3150.0);
            OnePoleLowPass envLp = new OnePoleLowPass(outRate, 22.0);
            DelayLine micro = new DelayLine(Math.max(4, (int) (outRate * 0.00042)));

            double srcPos = 0.0;
            long frame = 0;
            int rng = 0x5EED1234;
            float peakGain = earpiece ? 0.86f : (0.54f + cfg.intensity * 0.26f);

            track.play();
            listener.onStatus("NEUROTHOUGHT • " + cfg.profile.label + " • " + routeName(route));

            while (frame < totalFrames && !stopped.get()) {
                int frames = (int) Math.min(1024, totalFrames - frame);
                int cursor = 0;
                for (int i = 0; i < frames; i++) {
                    float raw = interpolate(pcm.samples, srcPos);
                    srcPos += step;

                    float clean = lp.process(hp.process(raw));
                    float low = boneLp.process(clean);
                    float lowMid = lowMidLp.process(clean);
                    float upper = presenceLp.process(clean) - lowMid;
                    float delayed = micro.process(clean);
                    float env = envLp.process(Math.abs(clean));

                    rng = rng * 1664525 + 1013904223;
                    float noise = (((rng >>> 8) & 0x00ffffff) / 8388607.5f) - 1f;
                    float breath = noise * Math.min(1f, env * 5.2f);

                    float body;
                    switch (cfg.profile) {
                        case SUBVOCAL:
                            body = clean * 0.54f + low * (0.30f + cfg.bone * 0.26f) + upper * 0.10f;
                            breath *= 0.16f;
                            break;
                        case SKULL_VOICE:
                            body = clean * 0.43f + low * (0.42f + cfg.bone * 0.34f) + delayed * 0.10f;
                            breath *= 0.10f;
                            break;
                        case WHISPER_CORTEX:
                            body = clean * 0.31f + upper * 0.26f + low * 0.13f;
                            breath *= (0.44f + cfg.whisper * 0.46f);
                            break;
                        case INNER_VOICE:
                            body = clean * 0.70f + low * (0.12f + cfg.bone * 0.16f) + upper * 0.07f;
                            breath *= 0.10f + cfg.whisper * 0.14f;
                            break;
                        default:
                            body = clean * 0.54f + low * (0.22f + cfg.bone * 0.27f) + upper * 0.12f + delayed * 0.055f;
                            breath *= 0.16f + cfg.whisper * 0.34f;
                            break;
                    }

                    float neural = (float) Math.tanh(body * (2.0 + cfg.intensity * 1.45));
                    neural += breath * cfg.whisper * 0.16f;
                    neural += low * cfg.depth * 0.08f;

                    float fi = Math.min(1f, (frame + i) / (float) fadeFrames);
                    float fo = Math.min(1f, (totalFrames - 1 - (frame + i)) / (float) fadeFrames);
                    float s = clamp(neural * peakGain * Math.max(0f, Math.min(fi, fo)));
                    short q = (short) Math.round(s * 32767.0);

                    if (stereo) {
                        // Diotic output deliberately removes interaural location cues and locks the image to the head center.
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
            if (!stopped.get()) listener.onStatus("Thought playback complete.");
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
            case SUBVOCAL: return 2850.0;
            case SKULL_VOICE: return 3200.0;
            case WHISPER_CORTEX: return 4100.0;
            case INNER_VOICE: return 3500.0;
            default: return 3800.0;
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
        if (rates != null) for (int r : rates) if (r >= 16000 && r <= 96000 && (best == 0 || Math.abs(r - 48000) < Math.abs(best - 48000))) best = r;
        return best > 0 ? best : 48000;
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

    private static float clamp(float v) { return Math.max(-0.98f, Math.min(0.98f, v)); }
    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static String safeMessage(Throwable t) { String m = t.getMessage(); return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m; }

    private static final class DelayLine {
        private final float[] data; private int pos;
        DelayLine(int samples) { data = new float[Math.max(2, samples)]; }
        float process(float x) { float y = data[pos]; data[pos] = x; pos++; if (pos >= data.length) pos = 0; return y; }
    }

    private static final class OnePoleLowPass {
        private final double a; private float y;
        OnePoleLowPass(int rate, double hz) { double dt = 1.0 / rate, rc = 1.0 / (2.0 * Math.PI * hz); a = dt / (rc + dt); }
        float process(float x) { y += (float) (a * (x - y)); return y; }
    }

    private static final class OnePoleHighPass {
        private final double a; private float y, lastX;
        OnePoleHighPass(int rate, double hz) { double dt = 1.0 / rate, rc = 1.0 / (2.0 * Math.PI * hz); a = rc / (rc + dt); }
        float process(float x) { y = (float) (a * (y + x - lastX)); lastX = x; return y; }
    }
}

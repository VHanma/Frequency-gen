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
 * Private, dry, center-image speech renderer used after a verified InfoBeam packet.
 * It owns the AudioTrack route directly instead of trusting the TTS engine to honor
 * the earpiece route. Headphones are preferred when connected; otherwise the phone
 * earpiece is used. The DSP deliberately removes room-like cues and narrows the
 * speech band so the result feels closer to an internal/centered voice.
 */
public final class ThoughtAudio {
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
        stop();
        stopped.set(false);
        new Thread(() -> render(context.getApplicationContext(), pcm, intensity, listener), "InfoBeam-Thought").start();
    }

    private void render(Context context, PcmWav.Data pcm, float intensity, Listener listener) {
        AudioTrack track = null;
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            activeManager = manager;
            AudioDeviceInfo route = choosePrivateRoute(manager);
            if (route == null) throw new IllegalStateException("No private audio route was found.");

            boolean stereo = isHeadphoneLike(route.getType());
            int outRate = chooseRate(route);
            int channelMask = stereo ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int channelCount = stereo ? 2 : 1;
            int min = AudioTrack.getMinBufferSize(outRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            int buffer = Math.max(min > 0 ? min : 0, 16_384);

            boolean earpiece = route.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
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

            float strength = Math.max(0f, Math.min(1f, intensity));
            float[] src = pcm.samples;
            double step = pcm.sampleRate / (double) outRate;
            long totalFrames = Math.max(1L, (long) Math.ceil(src.length / step));
            int blockFrames = 1024;
            short[] block = new short[blockFrames * channelCount];

            OnePoleLowPass low = new OnePoleLowPass(outRate, 3450.0);
            OnePoleLowPass body = new OnePoleLowPass(outRate, 900.0);
            OnePoleHighPass high = new OnePoleHighPass(outRate, 135.0);
            OnePoleLowPass soft = new OnePoleLowPass(outRate, 1900.0);

            double sourcePos = 0.0;
            long frame = 0;
            int fadeFrames = Math.max(1, (int) (outRate * 0.030));
            float peakGain = 0.42f + strength * 0.32f;

            track.play();
            listener.onStatus("THOUGHT VOICE • " + routeName(route) + " • dry center image");

            while (frame < totalFrames && !stopped.get()) {
                int frames = (int) Math.min(blockFrames, totalFrames - frame);
                int cursor = 0;
                for (int i = 0; i < frames; i++) {
                    float raw = interpolate(src, sourcePos);
                    sourcePos += step;

                    float v = high.process(low.process(raw));
                    float warm = body.process(v);
                    float inner = soft.process(v);

                    // Dense center voice: compressed main signal + a small warm component.
                    double compressed = Math.tanh(v * (1.85 + 0.85 * strength));
                    float shaped = (float) compressed * (0.88f + 0.08f * strength)
                            + warm * (0.08f + 0.05f * strength)
                            + inner * 0.035f;

                    // Very short edge emphasis makes consonants intelligible at low private volume
                    // without adding reverb or obvious spatial cues.
                    float edge = v - inner;
                    shaped += edge * (0.05f + 0.07f * strength);

                    float fadeIn = Math.min(1f, (frame + i) / (float) fadeFrames);
                    float fadeOut = Math.min(1f, (totalFrames - 1 - (frame + i)) / (float) fadeFrames);
                    float fade = Math.max(0f, Math.min(fadeIn, fadeOut));
                    float sample = clamp(shaped * peakGain * fade);
                    short s = (short) Math.round(sample * 32767.0);

                    if (stereo) {
                        // Exact dual-mono is intentional: minimum interaural timing/level cues,
                        // which produces the strongest centered/in-head headphone image.
                        block[cursor++] = s;
                        block[cursor++] = s;
                    } else {
                        block[cursor++] = s;
                    }
                }

                int samples = frames * channelCount;
                int offset = 0;
                while (offset < samples && !stopped.get()) {
                    int wrote = track.write(block, offset, samples - offset, AudioTrack.WRITE_BLOCKING);
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

    private AudioDeviceInfo choosePrivateRoute(AudioManager manager) {
        AudioDeviceInfo earpiece = null;
        AudioDeviceInfo wired = null;
        AudioDeviceInfo usb = null;
        AudioDeviceInfo bluetooth = null;
        for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                    if (wired == null) wired = d;
                    break;
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                    if (usb == null) usb = d;
                    break;
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                    if (bluetooth == null) bluetooth = d;
                    break;
                case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                    if (earpiece == null) earpiece = d;
                    break;
                default:
                    break;
            }
        }
        if (wired != null) return wired;
        if (usb != null) return usb;
        if (bluetooth != null) return bluetooth;
        return earpiece;
    }

    private int chooseRate(AudioDeviceInfo route) {
        int best = 0;
        int[] rates = route.getSampleRates();
        if (rates != null) {
            for (int r : rates) {
                if (r >= 16_000 && r <= 96_000 && Math.abs(r - 48_000) < Math.abs(best - 48_000)) best = r;
            }
        }
        return best > 0 ? best : 48_000;
    }

    private boolean isHeadphoneLike(int type) {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
    }

    private String routeName(AudioDeviceInfo route) {
        String p = route.getProductName() == null ? "" : route.getProductName().toString();
        if (!p.trim().isEmpty()) return p;
        return route.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ? "phone earpiece" : "private output";
    }

    private void releaseRoute() {
        AudioManager manager = activeManager;
        if (manager == null) return;
        if (communicationRoute) {
            try {
                if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice();
            } catch (Throwable ignored) {}
            try { manager.setMode(AudioManager.MODE_NORMAL); } catch (Throwable ignored) {}
        }
        communicationRoute = false;
        activeManager = null;
    }

    private static float interpolate(float[] data, double pos) {
        if (data.length == 0) return 0f;
        int i = (int) pos;
        if (i <= 0) return data[0];
        if (i >= data.length - 1) return data[data.length - 1];
        double frac = pos - i;
        return (float) (data[i] + (data[i + 1] - data[i]) * frac);
    }

    private static float clamp(float v) {
        return Math.max(-0.98f, Math.min(0.98f, v));
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
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
        private float y;
        private float lastX;
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

package com.vaan.infobeam;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ParametricAudio {
    public interface Listener { void onStatus(String text); void onStopped(); }
    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private volatile AudioTrack activeTrack;

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
    }

    public void play(PcmWav.Data pcm, int requestedRate, double requestedCarrierHz, double targetAngleDeg,
                     double spacingMm, AudioDeviceInfo preferredDevice, boolean externalMode, Listener listener) {
        stop();
        stopped.set(false);
        new Thread(() -> runPlayback(pcm, requestedRate, requestedCarrierHz, targetAngleDeg, spacingMm,
                preferredDevice, externalMode, listener), "InfoBeam-Parametric-v12").start();
    }

    private void runPlayback(PcmWav.Data pcm, int requestedRate, double requestedCarrierHz, double targetAngleDeg,
                             double spacingMm, AudioDeviceInfo preferredDevice, boolean externalMode, Listener listener) {
        AudioTrack track = null;
        try {
            TrackChoice choice = createBestTrack(requestedRate, preferredDevice);
            track = choice.track;
            activeTrack = track;
            final int sampleRate = choice.sampleRate;
            final double nyquistGuard = sampleRate * 0.445;
            final double baseCarrier = externalMode
                    ? clamp(requestedCarrierHz, 18000.0, nyquistGuard)
                    : clamp(requestedCarrierHz, 14500.0, Math.min(19200.0, nyquistGuard));
            final double angle = Math.toRadians(clamp(targetAngleDeg, -60.0, 60.0));
            final double spacingM = clamp(spacingMm, 1.0, 160.0) / 1000.0;
            final double sourceStep = pcm.sampleRate / (double) sampleRate;
            final long totalFrames = Math.max(1L, (long) Math.ceil(pcm.samples.length / sourceStep));
            final int fadeFrames = Math.max(1, (int) (sampleRate * 0.045));

            OnePoleHighPass hp = new OnePoleHighPass(sampleRate, externalMode ? 120.0 : 260.0);
            OnePoleLowPass lp = new OnePoleLowPass(sampleRate, externalMode ? 4200.0 : 2850.0);
            OnePoleLowPass integDc = new OnePoleLowPass(sampleRate, 24.0);

            double srcPos = 0.0;
            double phase = 0.0;
            double integ1 = 0.0, integ2 = 0.0;
            long outFrame = 0L;
            short[] block = new short[2048 * 2];

            track.play();
            String route = track.getRoutedDevice() != null ? track.getRoutedDevice().getProductName().toString()
                    : preferredDevice != null ? preferredDevice.getProductName().toString() : "system output";
            listener.onStatus((externalMode ? "PARAMETRIC THOUGHT BEAM" : "ADAPTIVE PHONE BEAM")
                    + " • " + Math.round(baseCarrier) + " Hz • " + route);

            while (!stopped.get() && outFrame < totalFrames) {
                int frames = (int) Math.min(2048, totalFrames - outFrame);
                for (int i = 0; i < frames; i++) {
                    long n = outFrame + i;
                    float raw = interpolate(pcm.samples, srcPos);
                    srcPos += sourceStep;
                    double voice = Math.tanh(lp.process(hp.process(raw)) * 2.35);

                    // Leaky double integration approximates Berktay-oriented preprocessing while preventing drift.
                    if (externalMode) {
                        integ1 = integ1 * 0.99965 + voice * 0.00035;
                        integ2 = integ2 * 0.99965 + integ1 * 0.00035;
                        double dc = integDc.process((float) integ2);
                        double pre = clamp((integ2 - dc) * 42.0 + voice * 0.72, -1.0, 1.0);
                        voice = pre;
                    }

                    // Phone hardware varies wildly above ~18 kHz. Sweep a narrow window so the beam searches speaker resonances.
                    double carrier = baseCarrier;
                    if (!externalMode) {
                        double sweep = 1050.0 * Math.sin(2.0 * Math.PI * 0.72 * n / sampleRate);
                        carrier = clamp(baseCarrier + sweep, 14200.0, Math.min(19500.0, nyquistGuard));
                    }

                    double envelope = Math.sqrt(clamp(1.0 + (externalMode ? 0.88 : 0.74) * voice, 0.035, 1.965));
                    double phaseOffset = 2.0 * Math.PI * carrier * spacingM * Math.sin(angle) / 343.0;
                    double fi = Math.min(1.0, n / (double) fadeFrames);
                    double fo = Math.min(1.0, (totalFrames - 1 - n) / (double) fadeFrames);
                    double fade = Math.max(0.0, Math.min(fi, fo));
                    double gain = externalMode ? 0.29 : 0.30;

                    double left = Math.sin(phase) * envelope * gain * fade;
                    double right = Math.sin(phase + phaseOffset) * envelope * gain * fade;
                    block[i * 2] = toShort(left);
                    block[i * 2 + 1] = toShort(right);

                    phase += 2.0 * Math.PI * carrier / sampleRate;
                    while (phase > Math.PI * 2.0) phase -= Math.PI * 2.0;
                }

                int count = frames * 2, offset = 0;
                while (offset < count && !stopped.get()) {
                    int wrote = track.write(block, offset, count - offset, AudioTrack.WRITE_BLOCKING);
                    if (wrote < 0) throw new IllegalStateException("AudioTrack write failed: " + wrote);
                    offset += wrote;
                }
                outFrame += frames;
            }
            if (!stopped.get()) listener.onStatus("Beam playback complete.");
        } catch (Throwable t) {
            listener.onStatus("Beam error: " + safeMessage(t));
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
            activeTrack = null;
            stopped.set(true);
            listener.onStopped();
        }
    }

    private static TrackChoice createBestTrack(int requestedRate, AudioDeviceInfo preferredDevice) {
        int[] candidates = uniqueRates(requestedRate, 192000, 96000, 48000);
        Throwable last = null;
        for (int rate : candidates) {
            if (rate < 44100 || rate > 192000) continue;
            try {
                int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(Math.max(min * 2, 32768)).setTransferMode(AudioTrack.MODE_STREAM).build();
                if (preferredDevice != null) track.setPreferredDevice(preferredDevice);
                if (track.getState() != AudioTrack.STATE_INITIALIZED) { track.release(); continue; }
                return new TrackChoice(track, rate);
            } catch (Throwable t) { last = t; }
        }
        throw new IllegalStateException("No usable high-rate AudioTrack route.", last);
    }

    private static int[] uniqueRates(int... input) {
        int[] out = new int[input.length]; int n = 0;
        for (int value : input) { boolean seen = false; for (int i = 0; i < n; i++) if (out[i] == value) { seen = true; break; } if (!seen) out[n++] = value; }
        int[] trimmed = new int[n]; System.arraycopy(out, 0, trimmed, 0, n); return trimmed;
    }

    private static float interpolate(float[] s, double pos) {
        if (s.length == 0) return 0f; int i = (int) pos; if (i <= 0) return s[0]; if (i >= s.length - 1) return s[s.length - 1];
        double f = pos - i; return (float) (s[i] + (s[i + 1] - s[i]) * f);
    }
    private static short toShort(double v) { return (short) Math.round(clamp(v, -0.98, 0.98) * Short.MAX_VALUE); }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String safeMessage(Throwable t) { String m = t.getMessage(); return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m; }

    private static final class TrackChoice { final AudioTrack track; final int sampleRate; TrackChoice(AudioTrack t, int r) { track = t; sampleRate = r; } }
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

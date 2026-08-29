package com.vaan.infobeam;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ParametricAudio {
    public interface Listener {
        void onStatus(String text);
        void onStopped();
    }

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

    public void play(
            PcmWav.Data pcm,
            int requestedRate,
            double requestedCarrierHz,
            double targetAngleDeg,
            double spacingMm,
            AudioDeviceInfo preferredDevice,
            boolean externalMode,
            Listener listener
    ) {
        stop();
        stopped.set(false);
        new Thread(() -> runPlayback(pcm, requestedRate, requestedCarrierHz, targetAngleDeg, spacingMm,
                preferredDevice, externalMode, listener), "InfoBeam-Parametric").start();
    }

    private void runPlayback(
            PcmWav.Data pcm,
            int requestedRate,
            double requestedCarrierHz,
            double targetAngleDeg,
            double spacingMm,
            AudioDeviceInfo preferredDevice,
            boolean externalMode,
            Listener listener
    ) {
        AudioTrack track = null;
        try {
            TrackChoice choice = createBestTrack(requestedRate, preferredDevice);
            track = choice.track;
            activeTrack = track;
            final int sampleRate = choice.sampleRate;
            final double nyquistGuard = sampleRate * 0.445;
            final double carrier = clamp(requestedCarrierHz, 12_000.0, nyquistGuard);
            final double angle = Math.toRadians(clamp(targetAngleDeg, -60.0, 60.0));
            final double spacingM = clamp(spacingMm, 1.0, 60.0) / 1000.0;
            final double phaseOffset = 2.0 * Math.PI * carrier * spacingM * Math.sin(angle) / 343.0;
            final double sourceStep = pcm.sampleRate / (double) sampleRate;
            final long totalFrames = Math.max(1L, (long) Math.ceil(pcm.samples.length / sourceStep));
            final int fadeFrames = Math.max(1, (int) (sampleRate * 0.06));

            final double dt = 1.0 / sampleRate;
            final double hpRc = 1.0 / (2.0 * Math.PI * 160.0);
            final double hpA = hpRc / (hpRc + dt);
            final double lpRc = 1.0 / (2.0 * Math.PI * 4_600.0);
            final double lpA = dt / (lpRc + dt);

            double srcPos = 0.0;
            double phase = 0.0;
            double prevX = 0.0;
            double hpY = 0.0;
            double lpY = 0.0;
            long outFrame = 0L;
            short[] block = new short[2048 * 2];

            track.play();
            String route = track.getRoutedDevice() != null
                    ? track.getRoutedDevice().getProductName().toString()
                    : preferredDevice != null ? preferredDevice.getProductName().toString() : "system output";
            listener.onStatus((externalMode ? "PARAMETRIC BEAM" : "PHONE CARRIER EXPERIMENT")
                    + " • " + Math.round(carrier) + " Hz • " + (sampleRate / 1000.0) + " kHz • " + route);

            while (!stopped.get() && outFrame < totalFrames) {
                int frames = (int) Math.min(2048, totalFrames - outFrame);
                for (int i = 0; i < frames; i++) {
                    float raw = interpolate(pcm.samples, srcPos);
                    double hp = hpA * (hpY + raw - prevX);
                    prevX = raw;
                    hpY = hp;
                    lpY += lpA * (hp - lpY);
                    double voice = Math.tanh(lpY * 2.15);

                    double envelope = Math.sqrt(clamp(1.0 + 0.82 * voice, 0.08, 1.92));
                    double fadeIn = Math.min(1.0, (outFrame + i) / (double) fadeFrames);
                    double fadeOut = Math.min(1.0, (totalFrames - 1 - (outFrame + i)) / (double) fadeFrames);
                    double fade = Math.max(0.0, Math.min(fadeIn, fadeOut));
                    double gain = externalMode ? 0.24 : 0.18;

                    double left = Math.sin(phase) * envelope * gain * fade;
                    double right = Math.sin(phase + phaseOffset) * envelope * gain * fade;
                    block[i * 2] = toShort(left);
                    block[i * 2 + 1] = toShort(right);

                    phase += 2.0 * Math.PI * carrier / sampleRate;
                    if (phase > Math.PI * 2.0) phase -= Math.PI * 2.0;
                    srcPos += sourceStep;
                }

                int shorts = frames * 2;
                int offset = 0;
                while (offset < shorts && !stopped.get()) {
                    int wrote = track.write(block, offset, shorts - offset, AudioTrack.WRITE_BLOCKING);
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
        int[] candidates = uniqueRates(requestedRate, 192_000, 96_000, 48_000);
        Throwable last = null;
        for (int rate : candidates) {
            if (rate < 44_100 || rate > 192_000) continue;
            try {
                int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(Math.max(min * 2, 32_768))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
                if (preferredDevice != null) track.setPreferredDevice(preferredDevice);
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    track.release();
                    continue;
                }
                return new TrackChoice(track, rate);
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new IllegalStateException("No usable high-rate AudioTrack route.", last);
    }

    private static int[] uniqueRates(int... input) {
        int[] out = new int[input.length];
        int n = 0;
        for (int value : input) {
            boolean seen = false;
            for (int i = 0; i < n; i++) if (out[i] == value) { seen = true; break; }
            if (!seen) out[n++] = value;
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    private static float interpolate(float[] samples, double pos) {
        if (samples.length == 0) return 0f;
        int i = (int) pos;
        if (i <= 0) return samples[0];
        if (i >= samples.length - 1) return samples[samples.length - 1];
        double frac = pos - i;
        return (float) (samples[i] + (samples[i + 1] - samples[i]) * frac);
    }

    private static short toShort(double v) {
        double c = clamp(v, -0.98, 0.98);
        return (short) Math.round(c * Short.MAX_VALUE);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static final class TrackChoice {
        final AudioTrack track;
        final int sampleRate;
        TrackChoice(AudioTrack track, int sampleRate) {
            this.track = track;
            this.sampleRate = sampleRate;
        }
    }
}

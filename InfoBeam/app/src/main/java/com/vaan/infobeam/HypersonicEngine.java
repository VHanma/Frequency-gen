package com.vaan.infobeam;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-rate line-level generator for external parametric / ultrasonic hardware.
 * All carriers and ELF components are generated from one AudioTrack sample clock,
 * so their starts and phase evolution are sample-synchronous.
 */
public final class HypersonicEngine {
    public interface Listener { void onStatus(String text); void onStopped(); }

    public static final class SingleConfig {
        public final double carrierHz;
        public final double elfHz;
        public final double modulationDepth;
        public final double beamGain;
        public final double elfGain;
        public final boolean splitStereo;
        public final int requestedRate;
        public final AudioDeviceInfo preferredDevice;

        public SingleConfig(double carrierHz, double elfHz, double modulationDepth,
                            double beamGain, double elfGain, boolean splitStereo,
                            int requestedRate, AudioDeviceInfo preferredDevice) {
            this.carrierHz = carrierHz;
            this.elfHz = elfHz;
            this.modulationDepth = clamp(modulationDepth, 0.0, 0.98);
            this.beamGain = clamp(beamGain, 0.0, 0.48);
            this.elfGain = clamp(elfGain, 0.0, 0.35);
            this.splitStereo = splitStereo;
            this.requestedRate = requestedRate;
            this.preferredDevice = preferredDevice;
        }
    }

    public static final class DualConfig {
        public final double carrierAHz, carrierBHz;
        public final double elfAHz, elfBHz;
        public final double modulationDepth;
        public final double beamGain;
        public final double elfGain;
        public final int requestedRate;
        public final AudioDeviceInfo preferredDevice;

        public DualConfig(double carrierAHz, double carrierBHz, double elfAHz, double elfBHz,
                          double modulationDepth, double beamGain, double elfGain,
                          int requestedRate, AudioDeviceInfo preferredDevice) {
            this.carrierAHz = carrierAHz;
            this.carrierBHz = carrierBHz;
            this.elfAHz = elfAHz;
            this.elfBHz = elfBHz;
            this.modulationDepth = clamp(modulationDepth, 0.0, 0.98);
            this.beamGain = clamp(beamGain, 0.0, 0.44);
            this.elfGain = clamp(elfGain, 0.0, 0.28);
            this.requestedRate = requestedRate;
            this.preferredDevice = preferredDevice;
        }
    }

    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private volatile AudioTrack active;

    public void stop() {
        stopped.set(true);
        AudioTrack t = active;
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) {}
            try { t.flush(); } catch (Throwable ignored) {}
            try { t.stop(); } catch (Throwable ignored) {}
            try { t.release(); } catch (Throwable ignored) {}
        }
        active = null;
    }

    public void playSingle(PcmWav.Data source, SingleConfig cfg, Listener listener) {
        stop();
        stopped.set(false);
        new Thread(() -> runSingle(source, cfg, listener), "InfoBeam-Hypersonic-Single").start();
    }

    public void playDual(PcmWav.Data source, DualConfig cfg, Listener listener) {
        stop();
        stopped.set(false);
        new Thread(() -> runDual(source, cfg, listener), "InfoBeam-Hypersonic-Dual").start();
    }

    private void runSingle(PcmWav.Data source, SingleConfig cfg, Listener listener) {
        AudioTrack track = null;
        try {
            TrackChoice choice = createTrack(cfg.requestedRate, cfg.preferredDevice);
            track = choice.track;
            active = track;
            validateCarrier(cfg.carrierHz, choice.rate);
            validateElf(cfg.elfHz);

            double srcStep = source.sampleRate / (double) choice.rate;
            long totalFrames = Math.max(1L, (long) Math.ceil(source.samples.length / srcStep));
            int fade = Math.max(1, (int) (choice.rate * 0.025));
            short[] block = new short[2048 * 2];

            OnePoleHighPass hp = new OnePoleHighPass(choice.rate, 90.0);
            OnePoleLowPass lp = new OnePoleLowPass(choice.rate, Math.min(6500.0, cfg.carrierHz * 0.24));
            double srcPos = 0.0, carrierPhase = 0.0, elfPhase = 0.0;
            long frame = 0;

            track.play();
            listener.onStatus("HYPERSONIC + ELF • " + Math.round(cfg.carrierHz) + " Hz + "
                    + fmt(cfg.elfHz) + " Hz • " + choice.rate + " Hz clock"
                    + (cfg.splitStereo ? " • L beam / R ELF" : " • combined stereo"));

            while (frame < totalFrames && !stopped.get()) {
                int frames = (int) Math.min(2048, totalFrames - frame);
                for (int i = 0; i < frames; i++) {
                    long n = frame + i;
                    float raw = interpolate(source.samples, srcPos);
                    srcPos += srcStep;
                    double voice = Math.tanh(lp.process(hp.process(raw)) * 2.25);

                    // Square-root envelope precompensation is useful for parametric-array demodulation.
                    double env = Math.sqrt(clamp(1.0 + cfg.modulationDepth * voice, 0.025, 1.975));
                    double beam = Math.sin(carrierPhase) * env * cfg.beamGain;
                    double elf = Math.sin(elfPhase) * cfg.elfGain;

                    double fi = Math.min(1.0, n / (double) fade);
                    double fo = Math.min(1.0, (totalFrames - 1 - n) / (double) fade);
                    double f = Math.max(0.0, Math.min(fi, fo));
                    beam *= f;
                    elf *= f;

                    double left, right;
                    if (cfg.splitStereo) {
                        left = beam;
                        right = elf;
                    } else {
                        double mix = (beam + elf) * 0.86;
                        left = right = mix;
                    }
                    block[i * 2] = toShort(left);
                    block[i * 2 + 1] = toShort(right);

                    carrierPhase = wrap(carrierPhase + 2.0 * Math.PI * cfg.carrierHz / choice.rate);
                    elfPhase = wrap(elfPhase + 2.0 * Math.PI * cfg.elfHz / choice.rate);
                }
                write(track, block, frames * 2);
                frame += frames;
            }
            if (!stopped.get()) listener.onStatus("Hypersonic + ELF playback complete.");
        } catch (Throwable t) {
            listener.onStatus("Hypersonic engine error: " + safe(t));
        } finally {
            release(track, listener);
        }
    }

    private void runDual(PcmWav.Data source, DualConfig cfg, Listener listener) {
        AudioTrack track = null;
        try {
            TrackChoice choice = createTrack(cfg.requestedRate, cfg.preferredDevice);
            track = choice.track;
            active = track;
            validateCarrier(cfg.carrierAHz, choice.rate);
            validateCarrier(cfg.carrierBHz, choice.rate);
            validateElf(cfg.elfAHz);
            validateElf(cfg.elfBHz);

            double srcStep = source.sampleRate / (double) choice.rate;
            long totalFrames = Math.max(1L, (long) Math.ceil(source.samples.length / srcStep));
            int fade = Math.max(1, (int) (choice.rate * 0.025));
            short[] block = new short[2048 * 2];
            OnePoleHighPass hp = new OnePoleHighPass(choice.rate, 90.0);
            double lowestCarrier = Math.min(cfg.carrierAHz, cfg.carrierBHz);
            OnePoleLowPass lp = new OnePoleLowPass(choice.rate, Math.min(6000.0, lowestCarrier * 0.22));

            double srcPos = 0.0;
            double phaseA = 0.0, phaseB = 0.0, elfA = 0.0, elfB = 0.0;
            long frame = 0;

            track.play();
            listener.onStatus("DUAL HYPERSONIC MATRIX • L " + Math.round(cfg.carrierAHz) + "+" + fmt(cfg.elfAHz)
                    + " Hz • R " + Math.round(cfg.carrierBHz) + "+" + fmt(cfg.elfBHz) + " Hz • " + choice.rate + " Hz clock");

            while (frame < totalFrames && !stopped.get()) {
                int frames = (int) Math.min(2048, totalFrames - frame);
                for (int i = 0; i < frames; i++) {
                    long n = frame + i;
                    float raw = interpolate(source.samples, srcPos);
                    srcPos += srcStep;
                    double voice = Math.tanh(lp.process(hp.process(raw)) * 2.25);
                    double env = Math.sqrt(clamp(1.0 + cfg.modulationDepth * voice, 0.025, 1.975));

                    double left = Math.sin(phaseA) * env * cfg.beamGain + Math.sin(elfA) * cfg.elfGain;
                    double right = Math.sin(phaseB) * env * cfg.beamGain + Math.sin(elfB) * cfg.elfGain;

                    double fi = Math.min(1.0, n / (double) fade);
                    double fo = Math.min(1.0, (totalFrames - 1 - n) / (double) fade);
                    double f = Math.max(0.0, Math.min(fi, fo));
                    block[i * 2] = toShort(left * f);
                    block[i * 2 + 1] = toShort(right * f);

                    phaseA = wrap(phaseA + 2.0 * Math.PI * cfg.carrierAHz / choice.rate);
                    phaseB = wrap(phaseB + 2.0 * Math.PI * cfg.carrierBHz / choice.rate);
                    elfA = wrap(elfA + 2.0 * Math.PI * cfg.elfAHz / choice.rate);
                    elfB = wrap(elfB + 2.0 * Math.PI * cfg.elfBHz / choice.rate);
                }
                write(track, block, frames * 2);
                frame += frames;
            }
            if (!stopped.get()) listener.onStatus("Dual hypersonic matrix complete.");
        } catch (Throwable t) {
            listener.onStatus("Dual hypersonic error: " + safe(t));
        } finally {
            release(track, listener);
        }
    }

    private static TrackChoice createTrack(int requestedRate, AudioDeviceInfo device) {
        int[] rates = unique(requestedRate, 192000, 176400, 96000, 88200, 48000);
        Throwable last = null;
        for (int rate : rates) {
            if (rate < 48000 || rate > 192000) continue;
            try {
                int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(Math.max(min * 3, 65536))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                if (device != null) try { t.setPreferredDevice(device); } catch (Throwable ignored) {}
                if (t.getState() == AudioTrack.STATE_INITIALIZED) return new TrackChoice(t, rate);
                t.release();
            } catch (Throwable t) { last = t; }
        }
        throw new IllegalStateException("No usable 48–192 kHz stereo output route.", last);
    }

    private static void validateCarrier(double hz, int rate) {
        if (!Double.isFinite(hz) || hz < 20000.0) throw new IllegalArgumentException("Carrier must be at least 20 kHz.");
        double max = rate * 0.44;
        if (hz > max) throw new IllegalArgumentException("Carrier " + Math.round(hz) + " Hz needs a higher output rate. Current safe limit is " + Math.round(max) + " Hz.");
    }

    private static void validateElf(double hz) {
        if (!Double.isFinite(hz) || hz <= 0.0 || hz > 100.0) throw new IllegalArgumentException("ELF must be >0 and <=100 Hz.");
    }

    private static void write(AudioTrack track, short[] data, int count) {
        int off = 0;
        while (off < count) {
            int n = track.write(data, off, count - off, AudioTrack.WRITE_BLOCKING);
            if (n < 0) throw new IllegalStateException("AudioTrack write failed: " + n);
            off += n;
        }
    }

    private void release(AudioTrack track, Listener listener) {
        if (track != null) {
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
        }
        active = null;
        stopped.set(true);
        listener.onStopped();
    }

    private static float interpolate(float[] data, double pos) {
        if (data == null || data.length == 0) return 0f;
        int i = (int) pos;
        if (i <= 0) return data[0];
        if (i >= data.length - 1) return data[data.length - 1];
        double f = pos - i;
        return (float) (data[i] + (data[i + 1] - data[i]) * f);
    }

    private static short toShort(double x) { return (short) Math.round(clamp(x, -0.965, 0.965) * 32767.0); }
    private static double wrap(double p) { while (p >= Math.PI * 2.0) p -= Math.PI * 2.0; return p; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String fmt(double v) { return String.format(java.util.Locale.US, "%.3f", v); }
    private static String safe(Throwable t) { String m=t.getMessage(); return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m; }

    private static int[] unique(int... values) {
        int[] out = new int[values.length]; int n = 0;
        for (int v : values) { boolean seen=false; for(int i=0;i<n;i++) if(out[i]==v){seen=true;break;} if(!seen) out[n++]=v; }
        int[] r = new int[n]; System.arraycopy(out,0,r,0,n); return r;
    }

    private static final class TrackChoice { final AudioTrack track; final int rate; TrackChoice(AudioTrack t,int r){track=t;rate=r;} }
    private static final class OnePoleLowPass {
        private final double a; private float y;
        OnePoleLowPass(int rate,double hz){double dt=1.0/rate,rc=1.0/(2.0*Math.PI*hz);a=dt/(rc+dt);} float process(float x){y+=(float)(a*(x-y));return y;}
    }
    private static final class OnePoleHighPass {
        private final double a; private float y,last;
        OnePoleHighPass(int rate,double hz){double dt=1.0/rate,rc=1.0/(2.0*Math.PI*hz);a=rc/(rc+dt);} float process(float x){y=(float)(a*(y+x-last));last=x;return y;}
    }
}

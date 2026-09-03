package com.vaan.infobeam;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;
import android.media.AudioTrack;
import android.os.Build;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * InfoBeam v4 high-rate parametric-array signal engine.
 *
 * Design goals:
 *  - one sample clock for audio, ultrasonic carrier(s), and ELF components;
 *  - no content splitting: the complete requested mix is mirrored to L/R;
 *  - PAL-aware modulation choices (SSB, SRAM, DSB, hybrid low-SRAM/high-SSB);
 *  - emitter-bandwidth-aware source filtering;
 *  - ELF can exist both as a direct low-frequency component and as ultrasonic
 *    envelope modulation, so narrowband ultrasonic hardware still carries the
 *    chosen ELF timing in its envelope;
 *  - Android 14+ USB mixer negotiation, preferring exact-rate bit-perfect PCM16
 *    when the connected device/HAL advertises it.
 */
public final class HypersonicV4Engine {
    public interface Listener {
        void onStatus(String text);
        void onStopped();
    }

    public enum Modulation {
        HYBRID_PAL("Hybrid PAL • low SRAM + high SSB"),
        SSB_UPPER("SSB upper • low distortion"),
        SSB_LOWER("SSB lower • low distortion"),
        SRAM("Square-root AM"),
        DSB("Classic double-sideband AM");

        public final String label;
        Modulation(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum ElfMode {
        FUSED("Fused • direct + ultrasonic envelope"),
        ENVELOPE_ONLY("Ultrasonic envelope only"),
        DIRECT_ONLY("Direct ELF only");

        public final String label;
        ElfMode(String label) { this.label = label; }
        @Override public String toString() { return label; }

        boolean direct() { return this == FUSED || this == DIRECT_ONLY; }
        boolean envelope() { return this == FUSED || this == ENVELOPE_ONLY; }
    }

    public static final class Common {
        public final Modulation modulation;
        public final ElfMode elfMode;
        public final double modulationDepth;
        public final double beamGain;
        public final double elfDirectGain;
        public final double elfEnvelopeDepth;
        public final double requestedAudioBandwidthHz;
        public final double emitterBandwidthHz;
        public final int requestedRate;
        public final boolean usbPrecision;
        public final AudioDeviceInfo preferredDevice;

        public Common(Modulation modulation, ElfMode elfMode, double modulationDepth,
                      double beamGain, double elfDirectGain, double elfEnvelopeDepth,
                      double requestedAudioBandwidthHz, double emitterBandwidthHz,
                      int requestedRate, boolean usbPrecision, AudioDeviceInfo preferredDevice) {
            this.modulation = modulation == null ? Modulation.HYBRID_PAL : modulation;
            this.elfMode = elfMode == null ? ElfMode.FUSED : elfMode;
            this.modulationDepth = clamp(modulationDepth, 0.02, 0.98);
            this.beamGain = clamp(beamGain, 0.02, 0.62);
            this.elfDirectGain = clamp(elfDirectGain, 0.0, 0.30);
            this.elfEnvelopeDepth = clamp(elfEnvelopeDepth, 0.0, 0.65);
            this.requestedAudioBandwidthHz = clamp(requestedAudioBandwidthHz, 400.0, 16000.0);
            this.emitterBandwidthHz = clamp(emitterBandwidthHz, 800.0, 40000.0);
            this.requestedRate = requestedRate;
            this.usbPrecision = usbPrecision;
            this.preferredDevice = preferredDevice;
        }
    }

    public static final class SingleConfig {
        public final double carrierHz;
        public final double elfHz;
        public final Common common;
        public SingleConfig(double carrierHz, double elfHz, Common common) {
            this.carrierHz = carrierHz;
            this.elfHz = elfHz;
            this.common = common;
        }
    }

    public static final class DualConfig {
        public final double carrierAHz, carrierBHz;
        public final double elfAHz, elfBHz;
        public final Common common;
        public DualConfig(double carrierAHz, double carrierBHz, double elfAHz, double elfBHz, Common common) {
            this.carrierAHz = carrierAHz;
            this.carrierBHz = carrierBHz;
            this.elfAHz = elfAHz;
            this.elfBHz = elfBHz;
            this.common = common;
        }
    }

    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private volatile AudioTrack active;
    private volatile TrackChoice activeChoice;

    public void stop() {
        stopped.set(true);
        AudioTrack t = active;
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) {}
            try { t.flush(); } catch (Throwable ignored) {}
            try { t.stop(); } catch (Throwable ignored) {}
            try { t.release(); } catch (Throwable ignored) {}
        }
        clearMixer(activeChoice);
        active = null;
        activeChoice = null;
    }

    public void playSingle(Context context, PcmWav.Data source, SingleConfig cfg, Listener listener) {
        stop();
        stopped.set(false);
        new Thread(() -> runSingle(context.getApplicationContext(), source, cfg, listener), "InfoBeam-v4-Single").start();
    }

    public void playDual(Context context, PcmWav.Data source, DualConfig cfg, Listener listener) {
        stop();
        stopped.set(false);
        new Thread(() -> runDual(context.getApplicationContext(), source, cfg, listener), "InfoBeam-v4-Dual").start();
    }

    private void runSingle(Context context, PcmWav.Data source, SingleConfig cfg, Listener listener) {
        TrackChoice choice = null;
        try {
            validateSource(source);
            validateCarrier(cfg.carrierHz);
            validateElf(cfg.elfHz);
            choice = createTrack(context, cfg.common);
            activeChoice = choice;
            active = choice.track;

            double bw = effectiveBandwidth(cfg.carrierHz, choice.rate, cfg.common);
            DspState dsp = new DspState(choice.rate, bw);
            double srcStep = source.sampleRate / (double) choice.rate;
            long totalFrames = Math.max(1L, (long) Math.ceil(source.samples.length / srcStep));
            int fade = Math.max(1, (int) (choice.rate * 0.020));
            short[] block = new short[1024 * 2];
            double srcPos = 0.0;
            double carrierPhase = 0.0;
            double elfPhase = 0.0;
            long frame = 0;

            choice.track.play();
            if (listener != null) listener.onStatus("V4 " + cfg.common.modulation.label + " • "
                    + Math.round(cfg.carrierHz) + " Hz + " + fmt(cfg.elfHz) + " Hz • "
                    + Math.round(bw) + " Hz audio BW • " + choice.describe());

            while (frame < totalFrames && !stopped.get()) {
                int frames = (int) Math.min(1024, totalFrames - frame);
                for (int i = 0; i < frames; i++) {
                    long n = frame + i;
                    float raw = interpolate(source.samples, srcPos);
                    srcPos += srcStep;
                    DspSample s = dsp.process(raw);

                    double elf = Math.sin(elfPhase);
                    double sin = Math.sin(carrierPhase);
                    double cos = Math.cos(carrierPhase);
                    double envElf = cfg.common.elfMode.envelope() ? cfg.common.elfEnvelopeDepth * elf : 0.0;
                    double modulated = modulate(cfg.common.modulation, s, sin, cos,
                            cfg.common.modulationDepth, envElf);
                    double mix = modulated * cfg.common.beamGain;
                    if (cfg.common.elfMode.direct()) mix += elf * cfg.common.elfDirectGain;

                    double f = fade(n, totalFrames, fade);
                    short q = toShort(softLimit(mix) * f);
                    block[i * 2] = q;
                    block[i * 2 + 1] = q;

                    carrierPhase = wrap(carrierPhase + 2.0 * Math.PI * cfg.carrierHz / choice.rate);
                    elfPhase = wrap(elfPhase + 2.0 * Math.PI * cfg.elfHz / choice.rate);
                }
                write(choice.track, block, frames * 2);
                frame += frames;
            }
            if (!stopped.get() && listener != null) listener.onStatus("Unified hypersonic + ELF playback complete.");
        } catch (Throwable t) {
            if (listener != null) listener.onStatus("V4 engine error: " + safe(t));
        } finally {
            release(choice, listener);
        }
    }

    private void runDual(Context context, PcmWav.Data source, DualConfig cfg, Listener listener) {
        TrackChoice choice = null;
        try {
            validateSource(source);
            validateCarrier(cfg.carrierAHz);
            validateCarrier(cfg.carrierBHz);
            validateElf(cfg.elfAHz);
            validateElf(cfg.elfBHz);
            choice = createTrack(context, cfg.common);
            activeChoice = choice;
            active = choice.track;

            double bwA = effectiveBandwidth(cfg.carrierAHz, choice.rate, cfg.common);
            double bwB = effectiveBandwidth(cfg.carrierBHz, choice.rate, cfg.common);
            double bw = Math.min(bwA, bwB);
            DspState dsp = new DspState(choice.rate, bw);
            double srcStep = source.sampleRate / (double) choice.rate;
            long totalFrames = Math.max(1L, (long) Math.ceil(source.samples.length / srcStep));
            int fade = Math.max(1, (int) (choice.rate * 0.020));
            short[] block = new short[1024 * 2];
            double srcPos = 0.0;
            double phaseA = 0.0, phaseB = 0.0, elfPhaseA = 0.0, elfPhaseB = 0.0;
            long frame = 0;
            double difference = Math.abs(cfg.carrierAHz - cfg.carrierBHz);

            choice.track.play();
            if (listener != null) listener.onStatus("V4 DUAL UNIFIED • " + cfg.common.modulation.label
                    + " • carriers " + Math.round(cfg.carrierAHz) + "/" + Math.round(cfg.carrierBHz)
                    + " Hz • Δ " + Math.round(difference) + " Hz • ELF " + fmt(cfg.elfAHz) + "/" + fmt(cfg.elfBHz)
                    + " • " + Math.round(bw) + " Hz audio BW • " + choice.describe());

            while (frame < totalFrames && !stopped.get()) {
                int frames = (int) Math.min(1024, totalFrames - frame);
                for (int i = 0; i < frames; i++) {
                    long n = frame + i;
                    float raw = interpolate(source.samples, srcPos);
                    srcPos += srcStep;
                    DspSample s = dsp.process(raw);

                    double ea = Math.sin(elfPhaseA);
                    double eb = Math.sin(elfPhaseB);
                    double envA = cfg.common.elfMode.envelope() ? cfg.common.elfEnvelopeDepth * ea : 0.0;
                    double envB = cfg.common.elfMode.envelope() ? cfg.common.elfEnvelopeDepth * eb : 0.0;
                    double a = modulate(cfg.common.modulation, s, Math.sin(phaseA), Math.cos(phaseA), cfg.common.modulationDepth, envA);
                    double b = modulate(cfg.common.modulation, s, Math.sin(phaseB), Math.cos(phaseB), cfg.common.modulationDepth, envB);

                    // Both carriers and both ELF components are in one physical mix.
                    // 0.53 keeps two simultaneous carrier paths inside useful digital headroom.
                    double mix = (a + b) * cfg.common.beamGain * 0.53;
                    if (cfg.common.elfMode.direct()) mix += (ea + eb) * cfg.common.elfDirectGain * 0.50;

                    double f = fade(n, totalFrames, fade);
                    short q = toShort(softLimit(mix) * f);
                    block[i * 2] = q;
                    block[i * 2 + 1] = q;

                    phaseA = wrap(phaseA + 2.0 * Math.PI * cfg.carrierAHz / choice.rate);
                    phaseB = wrap(phaseB + 2.0 * Math.PI * cfg.carrierBHz / choice.rate);
                    elfPhaseA = wrap(elfPhaseA + 2.0 * Math.PI * cfg.elfAHz / choice.rate);
                    elfPhaseB = wrap(elfPhaseB + 2.0 * Math.PI * cfg.elfBHz / choice.rate);
                }
                write(choice.track, block, frames * 2);
                frame += frames;
            }
            if (!stopped.get() && listener != null) listener.onStatus("Unified dual-carrier + dual-ELF playback complete.");
        } catch (Throwable t) {
            if (listener != null) listener.onStatus("V4 dual engine error: " + safe(t));
        } finally {
            release(choice, listener);
        }
    }

    private static double modulate(Modulation mode, DspSample s, double sin, double cos,
                                   double depth, double elfEnvelope) {
        switch (mode) {
            case SSB_UPPER:
                return (1.0 + depth * s.i + elfEnvelope) * sin + depth * s.q * cos;
            case SSB_LOWER:
                return (1.0 + depth * s.i + elfEnvelope) * sin - depth * s.q * cos;
            case SRAM: {
                double e = Math.sqrt(clamp(1.0 + depth * s.i + elfEnvelope, 0.025, 1.975));
                return e * sin;
            }
            case DSB:
                return clamp(1.0 + depth * s.i + elfEnvelope, 0.04, 1.96) * sin;
            case HYBRID_PAL:
            default: {
                // Low band gets SRAM for stronger low-frequency recovery. High band is
                // carried as a single sideband to reduce second-harmonic/intermod distortion.
                double lowEnv = Math.sqrt(clamp(1.0 + depth * 0.96 * s.lowI + elfEnvelope, 0.025, 1.975));
                double highSideband = depth * 0.82 * (s.highI * sin + s.highQ * cos);
                return lowEnv * sin + highSideband;
            }
        }
    }

    private static double effectiveBandwidth(double carrier, int rate, Common c) {
        validateCarrierForRate(carrier, rate);
        double emitter = Math.max(400.0, c.emitterBandwidthHz * 0.47);
        double nyquistMargin = Math.max(250.0, rate * 0.47 - carrier);
        double keepUltrasonic = Math.max(250.0, carrier - 20500.0);
        double bw = Math.min(c.requestedAudioBandwidthHz, Math.min(emitter, Math.min(nyquistMargin * 0.92, keepUltrasonic)));
        if (bw < 300.0) throw new IllegalArgumentException("Carrier / sample-rate / emitter-bandwidth combination leaves too little audio sideband bandwidth.");
        return bw;
    }

    private static TrackChoice createTrack(Context context, Common c) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        if (Build.VERSION.SDK_INT >= 34 && c.usbPrecision && isUsb(c.preferredDevice)) {
            try {
                MixerPick pick = chooseUsbMixer(manager, c.preferredDevice, c.requestedRate);
                if (pick != null) {
                    boolean set = manager.setPreferredMixerAttributes(attrs, c.preferredDevice, pick.attributes);
                    if (set) {
                        AudioTrack t = buildTrack(pick.format, attrs, c.preferredDevice);
                        if (t != null) {
                            return new TrackChoice(t, pick.format.getSampleRate(), manager, attrs, c.preferredDevice,
                                    true, pick.attributes.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                                    "USB mixer locked");
                        }
                        try { manager.clearPreferredMixerAttributes(attrs, c.preferredDevice); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {
                // Fall through to ordinary AudioTrack negotiation.
            }
        }

        int[] rates = unique(c.requestedRate, 192000, 176400, 96000, 88200, 48000);
        Throwable last = null;
        for (int rate : rates) {
            if (rate < 48000 || rate > 192000) continue;
            try {
                AudioFormat f = new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build();
                AudioTrack t = buildTrack(f, attrs, c.preferredDevice);
                if (t != null) return new TrackChoice(t, rate, null, attrs, c.preferredDevice, false, false, "framework route");
            } catch (Throwable t) { last = t; }
        }
        throw new IllegalStateException("No usable 48–192 kHz PCM16 output route.", last);
    }

    private static AudioTrack buildTrack(AudioFormat format, AudioAttributes attrs, AudioDeviceInfo device) {
        int rate = format.getSampleRate();
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return null;
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(min * 3, 65536))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (device != null) try { t.setPreferredDevice(device); } catch (Throwable ignored) {}
            try { t.setVolume(1.0f); } catch (Throwable ignored) {}
            if (t.getState() != AudioTrack.STATE_INITIALIZED) { t.release(); return null; }
            return t;
        } catch (Throwable ignored) { return null; }
    }

    private static MixerPick chooseUsbMixer(AudioManager manager, AudioDeviceInfo device, int requestedRate) {
        List<AudioMixerAttributes> list = manager.getSupportedMixerAttributes(device);
        if (list == null || list.isEmpty()) return null;
        AudioMixerAttributes best = null;
        long bestScore = Long.MIN_VALUE;
        for (AudioMixerAttributes a : list) {
            AudioFormat f = a.getFormat();
            if (f == null || f.getEncoding() != AudioFormat.ENCODING_PCM_16BIT || f.getChannelCount() != 2) continue;
            int rate = f.getSampleRate();
            if (rate < 48000 || rate > 192000) continue;
            long score = 0;
            if (rate == requestedRate) score += 1_000_000;
            score += rate;
            if (a.getMixerBehavior() == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) score += 2_000_000;
            if (score > bestScore) { bestScore = score; best = a; }
        }
        return best == null ? null : new MixerPick(best, best.getFormat());
    }

    private static boolean isUsb(AudioDeviceInfo d) {
        if (d == null) return false;
        return d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET;
    }

    private static void clearMixer(TrackChoice c) {
        if (c == null || !c.mixerSet || c.manager == null || c.device == null || Build.VERSION.SDK_INT < 34) return;
        try { c.manager.clearPreferredMixerAttributes(c.attributes, c.device); } catch (Throwable ignored) {}
    }

    private void release(TrackChoice c, Listener listener) {
        if (c != null && c.track != null) {
            try { c.track.stop(); } catch (Throwable ignored) {}
            try { c.track.release(); } catch (Throwable ignored) {}
        }
        clearMixer(c);
        active = null;
        activeChoice = null;
        stopped.set(true);
        if (listener != null) listener.onStopped();
    }

    private static void validateSource(PcmWav.Data source) {
        if (source == null || source.samples == null || source.samples.length == 0) throw new IllegalArgumentException("Audio source is empty.");
        if (source.sampleRate < 8000) throw new IllegalArgumentException("Audio source sample rate is invalid.");
    }

    private static void validateCarrier(double hz) {
        if (!Double.isFinite(hz) || hz < 20000.0 || hz > 90000.0) throw new IllegalArgumentException("Hypersonic carrier must be 20–90 kHz.");
    }

    private static void validateCarrierForRate(double hz, int rate) {
        double max = rate * 0.47;
        if (hz >= max) throw new IllegalArgumentException("Carrier " + Math.round(hz) + " Hz needs a higher hardware sample rate. Current PAL headroom limit: " + Math.round(max) + " Hz.");
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

    private static double fade(long n, long total, int fade) {
        double a = Math.min(1.0, n / (double) fade);
        double b = Math.min(1.0, (total - 1 - n) / (double) fade);
        return Math.max(0.0, Math.min(a, b));
    }

    private static double softLimit(double x) {
        return Math.tanh(x * 1.20) * 0.93;
    }

    private static short toShort(double x) {
        return (short) Math.round(clamp(x, -0.965, 0.965) * 32767.0);
    }

    private static float interpolate(float[] data, double pos) {
        if (data == null || data.length == 0) return 0f;
        int i = (int) pos;
        if (i <= 0) return data[0];
        if (i >= data.length - 1) return data[data.length - 1];
        double f = pos - i;
        return (float) (data[i] + (data[i + 1] - data[i]) * f);
    }

    private static double wrap(double p) {
        double two = Math.PI * 2.0;
        if (p >= two) p -= two;
        if (p < 0.0) p += two;
        return p;
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String fmt(double v) { return String.format(Locale.US, "%.3f", v); }
    private static String safe(Throwable t) { String m=t.getMessage(); return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m; }

    private static int[] unique(int... values) {
        int[] out = new int[values.length]; int n = 0;
        for (int v : values) {
            boolean seen = false;
            for (int i=0;i<n;i++) if (out[i] == v) { seen = true; break; }
            if (!seen) out[n++] = v;
        }
        int[] r = new int[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    private static final class MixerPick {
        final AudioMixerAttributes attributes;
        final AudioFormat format;
        MixerPick(AudioMixerAttributes a, AudioFormat f) { attributes=a; format=f; }
    }

    private static final class TrackChoice {
        final AudioTrack track;
        final int rate;
        final AudioManager manager;
        final AudioAttributes attributes;
        final AudioDeviceInfo device;
        final boolean mixerSet;
        final boolean bitPerfect;
        final String routeMode;
        TrackChoice(AudioTrack track, int rate, AudioManager manager, AudioAttributes attributes,
                    AudioDeviceInfo device, boolean mixerSet, boolean bitPerfect, String routeMode) {
            this.track=track; this.rate=rate; this.manager=manager; this.attributes=attributes; this.device=device;
            this.mixerSet=mixerSet; this.bitPerfect=bitPerfect; this.routeMode=routeMode;
        }
        String describe() {
            StringBuilder s = new StringBuilder();
            s.append(rate).append(" Hz • ").append(routeMode);
            if (mixerSet) s.append(bitPerfect ? " • BIT-PERFECT" : " • exact USB mixer");
            if (device != null && device.getProductName() != null) s.append(" • ").append(device.getProductName());
            return s.toString();
        }
    }

    private static final class DspSample {
        final double i, q, lowI, highI, highQ;
        DspSample(double i, double q, double lowI, double highI, double highQ) {
            this.i=i; this.q=q; this.lowI=lowI; this.highI=highI; this.highQ=highQ;
        }
    }

    private static final class DspState {
        final OnePoleHighPass hp;
        final CascadeLowPass lp;
        final OnePoleLowPass envelope;
        final HilbertPair hilbert;
        final OnePoleLowPass hybridI;
        final OnePoleLowPass hybridQ;

        DspState(int rate, double bandwidth) {
            hp = new OnePoleHighPass(rate, 75.0);
            lp = new CascadeLowPass(rate, bandwidth);
            envelope = new OnePoleLowPass(rate, 12.0);
            hilbert = new HilbertPair(63);
            double cross = Math.min(1700.0, Math.max(700.0, bandwidth * 0.38));
            hybridI = new OnePoleLowPass(rate, cross);
            hybridQ = new OnePoleLowPass(rate, cross);
        }

        DspSample process(float raw) {
            float clean = lp.process(hp.process(raw));
            float env = envelope.process(Math.abs(clean));
            double upward = clamp(0.34 / (0.045 + env), 0.92, 2.15);
            float dense = (float) Math.tanh(clean * (1.15 + upward * 0.38));
            Pair p = hilbert.process(dense);
            double lowI = hybridI.process((float) p.real);
            double lowQ = hybridQ.process((float) p.quad);
            return new DspSample(p.real, p.quad, lowI, p.real - lowI, p.quad - lowQ);
        }
    }

    private static final class Pair {
        final double real, quad;
        Pair(double real, double quad) { this.real=real; this.quad=quad; }
    }

    /** Windowed odd-tap FIR Hilbert transformer with matched real-path delay. */
    private static final class HilbertPair {
        final int n, mid;
        final double[] h;
        final float[] ring;
        int pos;

        HilbertPair(int taps) {
            if ((taps & 1) == 0) taps++;
            n=taps; mid=taps/2; h=new double[taps]; ring=new float[taps];
            for (int i=0;i<taps;i++) {
                int k=i-mid;
                double ideal = (k != 0 && (Math.abs(k) & 1) == 1) ? 2.0/(Math.PI*k) : 0.0;
                double window = 0.54 - 0.46 * Math.cos(2.0*Math.PI*i/(taps-1.0));
                h[i]=ideal*window;
            }
        }

        Pair process(float x) {
            ring[pos]=x;
            double q=0.0;
            for (int i=0;i<n;i++) {
                int r=pos-i;
                if (r<0) r+=n;
                q += h[i]*ring[r];
            }
            int d=pos-mid;
            if (d<0) d+=n;
            double real=ring[d];
            pos++;
            if (pos>=n) pos=0;
            return new Pair(real,q);
        }
    }

    private static final class CascadeLowPass {
        final OnePoleLowPass a,b,c;
        CascadeLowPass(int rate,double hz){a=new OnePoleLowPass(rate,hz);b=new OnePoleLowPass(rate,hz);c=new OnePoleLowPass(rate,hz);}
        float process(float x){return c.process(b.process(a.process(x)));}
    }

    private static final class OnePoleLowPass {
        final double a; float y;
        OnePoleLowPass(int rate,double hz){double dt=1.0/rate,rc=1.0/(2.0*Math.PI*Math.max(1.0,hz));a=dt/(rc+dt);}
        float process(float x){y+=(float)(a*(x-y));return y;}
    }

    private static final class OnePoleHighPass {
        final double a; float y,last;
        OnePoleHighPass(int rate,double hz){double dt=1.0/rate,rc=1.0/(2.0*Math.PI*Math.max(1.0,hz));a=rc/(rc+dt);}
        float process(float x){y=(float)(a*(y+x-last));last=x;return y;}
    }
}

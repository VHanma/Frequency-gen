package com.vaan.infobeam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UltrasonicFsk {
    public static final int SAMPLE_RATE = 48_000;
    public static final int BIT_FRAMES = 720;
    public static final int MAX_PAYLOAD_BYTES = 160;

    public enum Profile {
        BALANCED("Balanced", 18_400.0, 19_600.0, "Best first try"),
        HIGH_PRIVACY("High privacy", 19_200.0, 20_400.0, "Less audible on capable hardware"),
        RANGE("Range", 17_300.0, 18_400.0, "Stronger phone compatibility; may be faintly audible");

        public final String label;
        public final double zeroHz;
        public final double oneHz;
        public final String note;

        Profile(String label, double zeroHz, double oneHz, String note) {
            this.label = label;
            this.zeroHz = zeroHz;
            this.oneHz = oneHz;
            this.note = note;
        }

        @Override public String toString() {
            return label + "  " + Math.round(zeroHz) + "/" + Math.round(oneHz) + " Hz";
        }
    }

    public interface Listener {
        void onStatus(String text);
        void onDecoded(String text, Profile profile);
        void onStopped();
    }

    private static final int SYNC = 0xD391;
    private static final int PREAMBLE_BITS = 64;
    private static final int SILENCE_BITS = 10;

    private final AtomicBoolean stopTx = new AtomicBoolean(true);
    private final AtomicBoolean stopRx = new AtomicBoolean(true);
    private volatile AudioTrack activeTrack;
    private volatile AudioRecord activeRecord;

    public void stop() {
        stopTx.set(true);
        stopRx.set(true);
        AudioTrack t = activeTrack;
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) {}
            try { t.flush(); } catch (Throwable ignored) {}
            try { t.stop(); } catch (Throwable ignored) {}
            try { t.release(); } catch (Throwable ignored) {}
            activeTrack = null;
        }
        AudioRecord r = activeRecord;
        if (r != null) {
            try { r.stop(); } catch (Throwable ignored) {}
            try { r.release(); } catch (Throwable ignored) {}
            activeRecord = null;
        }
    }

    public void transmitText(String text, Profile profile, Listener listener) {
        final byte[] utf8 = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        final byte[] payload = utf8.length <= MAX_PAYLOAD_BYTES ? utf8 : Arrays.copyOf(utf8, MAX_PAYLOAD_BYTES);
        if (payload.length == 0) {
            listener.onStatus("Enter some information first.");
            return;
        }

        stopTx.set(false);
        new Thread(() -> {
            AudioTrack track = null;
            try {
                listener.onStatus("Encoding " + payload.length + " bytes into ultrasonic FSK…");
                int[] bits = buildPacketBits(payload);
                int totalFrames = (SILENCE_BITS * 2 + bits.length) * BIT_FRAMES;
                short[] pcm = new short[totalFrames];
                double phase = 0.0;
                int cursor = SILENCE_BITS * BIT_FRAMES;
                final double amplitude = 0.18 * Short.MAX_VALUE;
                final int ramp = Math.min(96, BIT_FRAMES / 5);

                for (int bitIndex = 0; bitIndex < bits.length && !stopTx.get(); bitIndex++) {
                    double hz = bits[bitIndex] == 0 ? profile.zeroHz : profile.oneHz;
                    double phaseStep = 2.0 * Math.PI * hz / SAMPLE_RATE;
                    for (int i = 0; i < BIT_FRAMES; i++) {
                        double edge = 1.0;
                        if (bitIndex == 0 && i < ramp) edge = i / (double) ramp;
                        if (bitIndex == bits.length - 1 && i >= BIT_FRAMES - ramp) {
                            edge = Math.min(edge, (BIT_FRAMES - 1 - i) / (double) ramp);
                        }
                        pcm[cursor++] = (short) Math.round(Math.sin(phase) * amplitude * Math.max(0.0, edge));
                        phase += phaseStep;
                        if (phase > Math.PI * 2.0) phase -= Math.PI * 2.0;
                    }
                }

                if (stopTx.get()) return;
                int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int buffer = Math.max(min, 16_384);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(buffer)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                activeTrack = track;
                track.play();
                listener.onStatus("BEAMING • " + profile.label + " • aim the speaker toward the receiver");

                int offset = 0;
                while (offset < pcm.length && !stopTx.get()) {
                    int count = Math.min(8192, pcm.length - offset);
                    int wrote = track.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING);
                    if (wrote < 0) throw new IllegalStateException("AudioTrack write failed: " + wrote);
                    offset += wrote;
                }
                if (!stopTx.get()) listener.onStatus("Transmission complete.");
            } catch (Throwable t) {
                listener.onStatus("Transmit error: " + safeMessage(t));
            } finally {
                if (track != null) {
                    try { track.stop(); } catch (Throwable ignored) {}
                    try { track.release(); } catch (Throwable ignored) {}
                }
                activeTrack = null;
                stopTx.set(true);
                listener.onStopped();
            }
        }, "InfoBeam-TX").start();
    }

    public void listen(Context context, Listener listener) {
        if (Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onStatus("Microphone permission is required for receiver mode.");
            return;
        }
        stopRx.set(false);
        new Thread(() -> receiveLoop(listener), "InfoBeam-RX").start();
    }

    private void receiveLoop(Listener listener) {
        AudioRecord record = null;
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("48 kHz microphone input is unavailable on this route.");

            int source = Build.VERSION.SDK_INT >= 24 ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.DEFAULT;
            try {
                record = new AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(Math.max(min * 4, 32_768))
                        .build();
            } catch (Throwable first) {
                record = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(Math.max(min * 4, 32_768))
                        .build();
            }

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Microphone could not initialize at 48 kHz.");
            }
            activeRecord = record;
            record.startRecording();
            listener.onStatus("LISTENING • waiting for InfoBeam packet…");

            final int maxSamples = SAMPLE_RATE * 32;
            short[] captured = new short[maxSamples];
            short[] chunk = new short[4096];
            int used = 0;
            long lastAttemptMs = 0L;

            while (!stopRx.get() && used < maxSamples) {
                int read = record.read(chunk, 0, Math.min(chunk.length, maxSamples - used), AudioRecord.READ_BLOCKING);
                if (read < 0) throw new IllegalStateException("Microphone read failed: " + read);
                if (read == 0) continue;
                System.arraycopy(chunk, 0, captured, used, read);
                used += read;

                long now = System.currentTimeMillis();
                if (used > SAMPLE_RATE * 2 && now - lastAttemptMs > 2200) {
                    lastAttemptMs = now;
                    DecodeResult result = decode(captured, used);
                    if (result != null) {
                        listener.onDecoded(result.text, result.profile);
                        listener.onStatus("Beam received • CRC verified • " + result.profile.label);
                        return;
                    }
                    int sec = used / SAMPLE_RATE;
                    listener.onStatus("LISTENING • " + sec + " s • scanning 3 ultrasonic profiles");
                }
            }
            if (!stopRx.get()) listener.onStatus("No verified packet found. Re-aim, raise media volume, or try Range profile.");
        } catch (Throwable t) {
            listener.onStatus("Receive error: " + safeMessage(t));
        } finally {
            if (record != null) {
                try { record.stop(); } catch (Throwable ignored) {}
                try { record.release(); } catch (Throwable ignored) {}
            }
            activeRecord = null;
            stopRx.set(true);
            listener.onStopped();
        }
    }

    private DecodeResult decode(short[] samples, int length) {
        int[] phaseOffsets = {0, BIT_FRAMES / 4, BIT_FRAMES / 2, (BIT_FRAMES * 3) / 4};
        for (Profile profile : Profile.values()) {
            for (int offset : phaseOffsets) {
                int availableBits = (length - offset) / BIT_FRAMES;
                if (availableBits < PREAMBLE_BITS + 40) continue;
                byte[] bits = new byte[availableBits];
                for (int b = 0; b < availableBits; b++) {
                    int start = offset + b * BIT_FRAMES;
                    double e0 = goertzel(samples, start, BIT_FRAMES, profile.zeroHz);
                    double e1 = goertzel(samples, start, BIT_FRAMES, profile.oneHz);
                    bits[b] = (byte) (e1 > e0 ? 1 : 0);
                }

                DecodeResult result = scanBits(bits, profile);
                if (result != null) return result;
            }
        }
        return null;
    }

    private DecodeResult scanBits(byte[] bits, Profile profile) {
        int[] syncBits = intToBits(SYNC, 16);
        for (int i = PREAMBLE_BITS; i + 16 + 8 + 16 < bits.length; i++) {
            int syncMatches = 0;
            for (int j = 0; j < 16; j++) if (bits[i + j] == syncBits[j]) syncMatches++;
            if (syncMatches < 15) continue;

            int preambleMatchesA = 0;
            int preambleMatchesB = 0;
            for (int j = 0; j < PREAMBLE_BITS; j++) {
                int actual = bits[i - PREAMBLE_BITS + j];
                if (actual == (j & 1)) preambleMatchesA++;
                if (actual == (1 - (j & 1))) preambleMatchesB++;
            }
            if (Math.max(preambleMatchesA, preambleMatchesB) < PREAMBLE_BITS - 10) continue;

            int cursor = i + 16;
            int payloadLen = bitsToInt(bits, cursor, 8);
            cursor += 8;
            if (payloadLen <= 0 || payloadLen > MAX_PAYLOAD_BYTES) continue;
            int needed = payloadLen * 8 + 16;
            if (cursor + needed > bits.length) continue;

            byte[] payload = new byte[payloadLen];
            for (int p = 0; p < payloadLen; p++) {
                payload[p] = (byte) bitsToInt(bits, cursor, 8);
                cursor += 8;
            }
            int receivedCrc = bitsToInt(bits, cursor, 16);
            int expectedCrc = crc16(payload);
            if (receivedCrc != expectedCrc) continue;

            String text = new String(payload, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) continue;
            return new DecodeResult(text, profile);
        }
        return null;
    }

    private int[] buildPacketBits(byte[] payload) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(payload.length & 0xFF);
        bytes.write(payload, 0, payload.length);
        int crc = crc16(payload);
        bytes.write((crc >>> 8) & 0xFF);
        bytes.write(crc & 0xFF);

        byte[] body = bytes.toByteArray();
        int[] bits = new int[PREAMBLE_BITS + 16 + body.length * 8];
        int c = 0;
        for (int i = 0; i < PREAMBLE_BITS; i++) bits[c++] = i & 1;
        int[] syncBits = intToBits(SYNC, 16);
        for (int b : syncBits) bits[c++] = b;
        for (byte value : body) {
            for (int bit = 7; bit >= 0; bit--) bits[c++] = (value >>> bit) & 1;
        }
        return bits;
    }

    private static int crc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte value : data) {
            crc ^= (value & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    private static double goertzel(short[] samples, int start, int count, double targetHz) {
        double omega = 2.0 * Math.PI * targetHz / SAMPLE_RATE;
        double coeff = 2.0 * Math.cos(omega);
        double s0;
        double s1 = 0.0;
        double s2 = 0.0;
        int end = Math.min(samples.length, start + count);
        for (int i = start; i < end; i++) {
            double x = samples[i] / 32768.0;
            s0 = x + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }

    private static int[] intToBits(int value, int width) {
        int[] out = new int[width];
        for (int i = 0; i < width; i++) out[i] = (value >>> (width - 1 - i)) & 1;
        return out;
    }

    private static int bitsToInt(byte[] bits, int offset, int width) {
        int value = 0;
        for (int i = 0; i < width; i++) value = (value << 1) | (bits[offset + i] & 1);
        return value;
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static final class DecodeResult {
        final String text;
        final Profile profile;
        DecodeResult(String text, Profile profile) {
            this.text = text;
            this.profile = profile;
        }
    }
}

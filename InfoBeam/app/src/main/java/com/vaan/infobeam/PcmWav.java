package com.vaan.infobeam;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public final class PcmWav {
    public static final class Data {
        public final float[] samples;
        public final int sampleRate;
        public Data(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    private PcmWav() {}

    public static Data read(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (!"RIFF".equals(readFourCC(raf))) throw new IOException("Not a RIFF WAV file.");
            readUInt32LE(raf);
            if (!"WAVE".equals(readFourCC(raf))) throw new IOException("Not a WAVE file.");

            int format = -1;
            int channels = -1;
            int sampleRate = -1;
            int bits = -1;
            long dataPos = -1;
            int dataSize = -1;

            while (raf.getFilePointer() + 8 <= raf.length()) {
                String id = readFourCC(raf);
                long chunkSizeL = readUInt32LE(raf);
                if (chunkSizeL > Integer.MAX_VALUE) throw new IOException("WAV chunk is too large.");
                int chunkSize = (int) chunkSizeL;
                long chunkStart = raf.getFilePointer();

                if ("fmt ".equals(id)) {
                    if (chunkSize < 16) throw new IOException("Incomplete WAV fmt chunk.");
                    format = readUInt16LE(raf);
                    channels = readUInt16LE(raf);
                    sampleRate = (int) readUInt32LE(raf);
                    readUInt32LE(raf);
                    readUInt16LE(raf);
                    bits = readUInt16LE(raf);
                } else if ("data".equals(id)) {
                    dataPos = raf.getFilePointer();
                    dataSize = chunkSize;
                }

                long next = chunkStart + chunkSize + (chunkSize & 1);
                if (next > raf.length()) next = raf.length();
                raf.seek(next);
                if (dataPos >= 0 && format > 0) break;
            }

            if (dataPos < 0 || dataSize <= 0) throw new IOException("WAV contains no audio data.");
            if (sampleRate < 8000 || sampleRate > 384000) throw new IOException("Unsupported sample rate: " + sampleRate);
            if (channels < 1 || channels > 8) throw new IOException("Unsupported channel count: " + channels);
            int bytesPerSample = (bits + 7) / 8;
            if (bytesPerSample < 1 || bytesPerSample > 4) throw new IOException("Unsupported bit depth: " + bits);
            int frameBytes = bytesPerSample * channels;
            int frames = dataSize / frameBytes;
            if (frames <= 0) throw new IOException("WAV has no complete frames.");
            float[] mono = new float[frames];

            raf.seek(dataPos);
            for (int frame = 0; frame < frames; frame++) {
                double sum = 0.0;
                for (int ch = 0; ch < channels; ch++) sum += readSample(raf, format, bits);
                mono[frame] = clamp((float) (sum / channels));
            }
            removeDcAndNormalize(mono);
            return new Data(mono, sampleRate);
        }
    }

    private static float readSample(RandomAccessFile raf, int format, int bits) throws IOException {
        if (format == 3 && bits == 32) {
            int raw = (int) readUInt32LE(raf);
            return clamp(Float.intBitsToFloat(raw));
        }
        if (format != 1) throw new IOException("Unsupported WAV encoding: " + format);
        switch (bits) {
            case 8:
                return (raf.readUnsignedByte() - 128) / 128f;
            case 16:
                return (short) readUInt16LE(raf) / 32768f;
            case 24: {
                int b0 = raf.readUnsignedByte();
                int b1 = raf.readUnsignedByte();
                int b2 = raf.readUnsignedByte();
                int v = b0 | (b1 << 8) | (b2 << 16);
                if ((v & 0x800000) != 0) v |= 0xFF000000;
                return v / 8388608f;
            }
            case 32:
                return (int) readUInt32LE(raf) / 2147483648f;
            default:
                throw new IOException("Unsupported PCM bit depth: " + bits);
        }
    }

    private static void removeDcAndNormalize(float[] samples) {
        double mean = 0.0;
        for (float s : samples) mean += s;
        mean /= samples.length;
        float peak = 0f;
        for (int i = 0; i < samples.length; i++) {
            samples[i] = clamp(samples[i] - (float) mean);
            peak = Math.max(peak, Math.abs(samples[i]));
        }
        if (peak > 0.0001f) {
            float gain = Math.min(4f, 0.93f / peak);
            for (int i = 0; i < samples.length; i++) samples[i] = clamp(samples[i] * gain);
        }
    }

    private static String readFourCC(RandomAccessFile raf) throws IOException {
        byte[] b = new byte[4];
        raf.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int readUInt16LE(RandomAccessFile raf) throws IOException {
        int a = raf.readUnsignedByte();
        int b = raf.readUnsignedByte();
        return a | (b << 8);
    }

    private static long readUInt32LE(RandomAccessFile raf) throws IOException {
        long a = raf.readUnsignedByte();
        long b = raf.readUnsignedByte();
        long c = raf.readUnsignedByte();
        long d = raf.readUnsignedByte();
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}

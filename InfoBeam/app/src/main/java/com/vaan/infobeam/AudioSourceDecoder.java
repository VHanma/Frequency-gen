package com.vaan.infobeam;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes a user-selected Android audio file to normalized mono PCM. */
public final class AudioSourceDecoder {
    private AudioSourceDecoder() {}

    public static PcmWav.Data decode(Context context, Uri uri, int maxSeconds) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            int track = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; inputFormat = f; break; }
            }
            if (track < 0 || inputFormat == null) throw new IllegalArgumentException("Selected file has no audio track.");
            extractor.selectTrack(track);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcmBytes = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            int pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT;
            long maxUs = Math.max(1, maxSeconds) * 1_000_000L;

            while (!outDone) {
                if (!inDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        if (in == null) continue;
                        int size = extractor.readSampleData(in, 0);
                        long pts = extractor.getSampleTime();
                        if (size < 0 || pts < 0 || pts > maxUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, Math.max(0, pts), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = codec.getOutputFormat();
                    if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (outIndex >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIndex);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        byte[] chunk = new byte[info.size];
                        out.get(chunk);
                        pcmBytes.write(chunk);
                    }
                    outDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                }
            }

            byte[] raw = pcmBytes.toByteArray();
            if (raw.length == 0) throw new IllegalArgumentException("Decoded audio is empty.");
            float[] mono;
            if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                int frames = raw.length / (4 * Math.max(1, channels));
                mono = new float[frames];
                ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < frames; i++) {
                    float sum = 0f;
                    for (int ch = 0; ch < channels; ch++) sum += b.getFloat();
                    mono[i] = clamp(sum / channels);
                }
            } else {
                int frames = raw.length / (2 * Math.max(1, channels));
                mono = new float[frames];
                ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < frames; i++) {
                    float sum = 0f;
                    for (int ch = 0; ch < channels; ch++) sum += b.getShort() / 32768f;
                    mono[i] = clamp(sum / channels);
                }
            }
            removeDcNormalize(mono);
            return new PcmWav.Data(mono, sampleRate);
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) {}
            try { extractor.release(); } catch (Throwable ignored) {}
        }
    }

    private static void removeDcNormalize(float[] s) {
        if (s.length == 0) return;
        double mean = 0.0;
        for (float v : s) mean += v;
        mean /= s.length;
        float peak = 1e-6f;
        for (int i = 0; i < s.length; i++) { s[i] -= (float) mean; peak = Math.max(peak, Math.abs(s[i])); }
        float gain = Math.min(1.0f / peak, 2.5f);
        for (int i = 0; i < s.length; i++) s[i] = clamp(s[i] * gain * 0.92f);
    }

    private static float clamp(float v) { return Math.max(-1f, Math.min(1f, v)); }
}

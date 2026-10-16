package com.kooo.evdashboard.core;

import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Chunk-aware PCM reader. Originals and replacement sources are never edited in place. */
public final class SoundWave {
    public static final int MAX_BYTES = 6 * 1024 * 1024;
    public final int channels, rate, dataAt, dataBytes, frames;
    private final byte[] bytes;

    public SoundWave(byte[] source) throws Exception {
        WaveAudio.silence(source); // validates RIFF sizes, padding, PCM format and complete frames
        bytes = source;
        ByteBuffer b = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int c = 0, r = 0, offset = 0, length = 0;
        for (int at = 12; at < source.length;) {
            int type = b.getInt(at), size = b.getInt(at + 4);
            if (type == 0x20746d66) {c = Short.toUnsignedInt(b.getShort(at + 10)); r = b.getInt(at + 12);}
            if (type == 0x61746164) {offset = at + 8; length = size;}
            at += 8 + size + (size & 1);
        }
        channels = c; rate = r; dataAt = offset; dataBytes = length; frames = length / (c * 2);
        FilesEx.require(frames > 0 && frames <= (long) rate * 30, "音频须为 0–30 秒");
    }

    public double seconds() {return (double) frames / rate;}

    /** Preserve channel layout, duration and all metadata chunks. Smooth limiting above full scale. */
    public byte[] volume(int percent) throws Exception {
        FilesEx.require(percent >= 0 && percent <= 200, "音量范围为 0–200%");
        byte[] out = bytes.clone();
        if (percent == 100) return out;
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = dataAt; at < dataAt + dataBytes; at += 2) {
            double v = b.getShort(at) * (percent / 100.0), a = Math.abs(v);
            if (percent > 100 && a > 30000) v = Math.copySign(30000 + 2767 * (1 - Math.exp(-(a - 30000) / 2767)), v);
            b.putShort(at, (short) Math.max(-32768, Math.min(32767, Math.round(v))));
        }
        return out;
    }

    public static byte[] encode(short[] pcm, int rate, int channels) throws Exception {
        FilesEx.require(channels >= 1 && channels <= 8 && rate >= 8000 && rate <= 192000
            && pcm.length > 0 && pcm.length % channels == 0 && pcm.length / channels <= (long) rate * 30
            && pcm.length * 2L + 44 <= MAX_BYTES, "不支持的 PCM 音频格式或长度");
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(b.capacity() - 8)
            .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
            .putShort((short) channels).putInt(rate).putInt(rate * channels * 2)
            .putShort((short) (channels * 2)).putShort((short) 16)
            .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length * 2);
        for (short sample : pcm) b.putShort(sample);
        return b.array();
    }

    /** Resample and retain stereo for stereo targets; mono targets receive a channel average. */
    public static byte[] convert(byte[] source, int outChannels, int outRate, int gain) throws Exception {
        SoundWave wave = new SoundWave(source);
        FilesEx.require((outChannels == 1 || outChannels == 2) && outRate == 48000, "目标声音格式不支持");
        int frames = (int) ((long) wave.frames * outRate / wave.rate);
        FilesEx.require(frames > 0, "音频过短");
        short[] pcm = new short[frames * outChannels];
        ByteBuffer b = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < frames; f++) {
            double pos = f * (double) wave.rate / outRate;
            int left = Math.min((int) pos, wave.frames - 1), right = Math.min(left + 1, wave.frames - 1);
            for (int c = 0; c < outChannels; c++) {
                double a = wave.sample(b, left, c, outChannels), z = wave.sample(b, right, c, outChannels);
                pcm[f * outChannels + c] = (short) Math.round(a + (z - a) * (pos - left));
            }
        }
        return new SoundWave(encode(pcm, outRate, outChannels)).volume(gain);
    }

    private double sample(ByteBuffer b, int frame, int channel, int targetChannels) {
        if (channels == targetChannels || channels == 1)
            return b.getShort(dataAt + (frame * channels + (channels == 1 ? 0 : channel)) * 2);
        double sum = 0;
        for (int c = 0; c < channels; c++) sum += b.getShort(dataAt + (frame * channels + c) * 2);
        return sum / channels;
    }
}

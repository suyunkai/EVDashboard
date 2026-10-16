package com.kooo.evdashboard.core;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Import-only reader. Stored originals and CP output remain strictly PCM16. */
public final class WaveImport {
    public static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;
    private WaveImport() {}

    public static byte[] convert(byte[] source, int targetChannels, int gain) throws Exception {
        FilesEx.require(targetChannels == 1 || targetChannels == 2, "目标声音格式不支持");
        FilesEx.require(gain >= 0 && gain <= 200, "音量范围为 0–200%");
        FilesEx.require(source.length >= 44 && source.length <= MAX_INPUT_BYTES, "WAV 长度异常");
        ByteBuffer b = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        FilesEx.require(b.getInt(0) == 0x46464952 && b.getInt(8) == 0x45564157
            && Integer.toUnsignedLong(b.getInt(4)) == source.length - 8, "RIFF/WAVE 容器长度不匹配");
        int format = -1, formatBytes = 0, data = -1, dataBytes = 0;
        for (int at = 12; at < source.length;) {
            FilesEx.require(at + 8 <= source.length, "WAV 尾部 chunk 不完整");
            int type = b.getInt(at); long size = Integer.toUnsignedLong(b.getInt(at + 4));
            long end = at + 8L + size + (size & 1);
            FilesEx.require(end <= source.length, "WAV chunk 或填充越界");
            if (type == 0x20746d66) {FilesEx.require(format < 0 && size >= 16, "WAV 格式块重复或不完整"); format = at + 8; formatBytes = (int) size;}
            if (type == 0x61746164) {FilesEx.require(data < 0 && size > 0, "WAV 音频块重复或为空"); data = at + 8; dataBytes = (int) size;}
            at = (int) end;
        }
        FilesEx.require(format >= 0 && data >= 0, "WAV 缺少格式、音频块或采样帧不完整");
        int tag = u16(b, format), channels = u16(b, format + 2), rate = b.getInt(format + 4);
        int alignment = u16(b, format + 12), bits = u16(b, format + 14), validBits = bits;
        FilesEx.require(formatBytes == 16 || formatBytes >= 18 && u16(b, format + 16) <= formatBytes - 18, "WAV 扩展格式块不完整");
        if (tag == 0xfffe) {
            FilesEx.require(formatBytes >= 40 && u16(b, format + 16) >= 22, "WAV 扩展格式块不完整");
            validBits = u16(b, format + 18);
            if (validBits == 0) validBits = bits;
            // KSDATAFORMAT_SUBTYPE_{PCM,IEEE_FLOAT}: full GUID, not only its tag prefix.
            if (b.getInt(format + 28) != 0x00100000 || b.getLong(format + 32) != 0x719b3800aa000080L)
                throw new UnsupportedEncodingException("此 WAV 编码需要系统解码器");
            tag = b.getInt(format + 24);
            long mask = Integer.toUnsignedLong(b.getInt(format + 20));
            FilesEx.require(mask == 0 || Long.bitCount(mask) == channels, "WAV 声道布局不一致");
        }
        boolean floating = tag == 3;
        if (tag != 1 && !floating) throw new UnsupportedEncodingException("此 WAV 编码需要系统解码器");
        FilesEx.require(floating ? (bits == 32 || bits == 64) && validBits == bits
            : (bits == 8 || bits == 16 || bits == 24 || bits == 32) && validBits > 0 && validBits <= bits, "不支持的 WAV 位深");
        FilesEx.require(channels >= 1 && channels <= 8 && rate >= 8000 && rate <= 192000
            && alignment == channels * (bits / 8) && b.getInt(format + 8) == rate * alignment
            && dataBytes % alignment == 0, "WAV 采样格式不一致");
        int frames = dataBytes / alignment, sampleBytes = bits / 8;
        FilesEx.require(frames > 0 && frames <= (long) rate * 30, "音频不能超过 30 秒");
        // Validate even samples skipped while downsampling; NaN/padding is never silently accepted.
        long paddingMask = (1L << (bits - validBits)) - 1;
        if (floating || paddingMask != 0) for (int at = data; at < data + dataBytes; at += sampleBytes) {
            if (floating) FilesEx.require(Double.isFinite(bits == 32 ? b.getFloat(at) : b.getDouble(at)), "音频含非法采样");
            else FilesEx.require((integer(b, at, bits) & paddingMask) == 0, "WAV 有效位填充不正确");
        }
        int count = (int) ((long) frames * 48000 / rate);
        FilesEx.require(count > 0, "音频过短");
        short[] pcm = new short[count * targetChannels];
        for (int f = 0; f < count; f++) {
            double position = f * (double) rate / 48000;
            int left = Math.min((int) position, frames - 1), right = Math.min(left + 1, frames - 1);
            for (int c = 0; c < targetChannels; c++) {
                double a = sample(b, data, left, c, channels, targetChannels, bits, floating);
                double z = sample(b, data, right, c, channels, targetChannels, bits, floating);
                pcm[f * targetChannels + c] = (short) Math.max(-32768, Math.min(32767, Math.round(a + (z - a) * (position - left))));
            }
        }
        return new SoundWave(SoundWave.encode(pcm, 48000, targetChannels)).volume(gain);
    }

    private static int u16(ByteBuffer b, int at) {return Short.toUnsignedInt(b.getShort(at));}
    private static long integer(ByteBuffer b, int at, int bits) {
        return switch (bits) {
            case 8 -> (b.get(at) & 255) - 128;
            case 16 -> b.getShort(at);
            case 24 -> (b.get(at) & 255) | (b.get(at + 1) & 255) << 8 | b.get(at + 2) << 16;
            default -> b.getInt(at);
        };
    }
    private static double sample(ByteBuffer b, int data, int frame, int channel, int channels, int targetChannels, int bits, boolean floating) {
        if (channels == targetChannels || channels == 1)
            return value(b, data + (frame * channels + (channels == 1 ? 0 : channel)) * (bits / 8), bits, floating);
        double sum = 0;
        for (int c = 0; c < channels; c++) sum += value(b, data + (frame * channels + c) * (bits / 8), bits, floating);
        return sum / channels;
    }
    private static double value(ByteBuffer b, int at, int bits, boolean floating) {
        if (floating) return Math.max(-1, Math.min(1, bits == 32 ? b.getFloat(at) : b.getDouble(at))) * 32768;
        return integer(b, at, bits) * Math.scalb(1.0, 16 - bits);
    }
}

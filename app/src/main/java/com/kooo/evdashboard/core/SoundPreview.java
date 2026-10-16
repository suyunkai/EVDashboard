package com.kooo.evdashboard.core;

import java.nio.*;

/** Audition-only PCM; source files, exported files and configured gain remain unchanged. */
public final class SoundPreview {
    public static final int REPEATS = 3, LEAD_MS = 600, GAP_MS = 650, TAIL_MS = 150;
    public static boolean shortSound(SoundWave wave) {return wave.frames * 2L < wave.rate;}
    public static byte[] prepare(byte[] source) throws Exception {
        SoundWave wave = new SoundWave(source);
        if (!shortSound(wave)) return source;
        int lead = wave.rate * LEAD_MS / 1000, gap = wave.rate * GAP_MS / 1000, tail = wave.rate * TAIL_MS / 1000;
        long frames = lead + (long) wave.frames * REPEATS + (long) gap * (REPEATS - 1) + tail;
        if (frames * wave.channels * 2 + 44 > SoundWave.MAX_BYTES) return source;
        short[] pcm = new short[(int) frames * wave.channels];
        ShortBuffer samples = ByteBuffer.wrap(source, wave.dataAt, wave.dataBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] clip = new short[wave.frames * wave.channels]; samples.get(clip);
        for (int n = 0; n < REPEATS; n++) System.arraycopy(clip, 0, pcm, (lead + n * (wave.frames + gap)) * wave.channels, clip.length);
        return SoundWave.encode(pcm, wave.rate, wave.channels);
    }
}

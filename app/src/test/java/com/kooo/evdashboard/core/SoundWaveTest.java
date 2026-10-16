package com.kooo.evdashboard.core;

import org.junit.Test;
import java.nio.*;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SoundWaveTest {
    @Test public void stereoVolumeAndMutePreserveChannelsAndSource() throws Exception {
        byte[] original = SoundWave.encode(new short[]{1000, -2000, 3000, -4000}, 48000, 2), before = original.clone();
        byte[] adjusted = new SoundWave(original).volume(50);
        assertArrayEquals(before, original); assertArrayEquals(before, new SoundWave(original).volume(100));
        assertEquals(2, new SoundWave(adjusted).channels);
        ByteBuffer b = ByteBuffer.wrap(adjusted).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(500, b.getShort(44)); assertEquals(-1000, b.getShort(46));
        for (byte v : Arrays.copyOfRange(new SoundWave(original).volume(0), 44, original.length)) assertEquals(0, v);
    }
    @Test public void volumeKeepsUnknownMetadataAndOddChunkPadding() throws Exception {
        byte[] raw = SoundWave.encode(new short[]{100, 200}, 48000, 1);
        ByteBuffer b = ByteBuffer.allocate(raw.length + 12).order(ByteOrder.LITTLE_ENDIAN);
        b.put(raw, 0, 36).putInt(0x4b4e554a).putInt(3).put(new byte[]{1,2,3,0}).put(raw, 36, raw.length - 36).putInt(4, b.capacity()-8);
        byte[] source = b.array(), result = new SoundWave(source).volume(50);
        assertArrayEquals(Arrays.copyOf(source, 56), Arrays.copyOf(result, 56));
        assertEquals(50, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getShort(56));
    }
    @Test public void amplificationIsUsefulAndLimitedWithoutWraparound() throws Exception {
        byte[] source = SoundWave.encode(new short[]{1000, 20000, 32767, -32768}, 48000, 1);
        ByteBuffer out = ByteBuffer.wrap(new SoundWave(source).volume(200)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2000, out.getShort(44)); assertTrue(out.getShort(46) > 30000);
        assertTrue(out.getShort(48) > 32000); assertTrue(out.getShort(50) < -32000);
        assertThrows(Exception.class, () -> new SoundWave(source).volume(201));
        assertThrows(Exception.class, () -> new SoundWave(source).volume(-1));
    }
    @Test public void targetChannelConversionRetainsStereoOrDownmixes() throws Exception {
        byte[] stereo = SoundWave.encode(new short[]{100, 300, 300, 500}, 24000, 2);
        byte[] retained = SoundWave.convert(stereo, 2, 48000, 100);
        assertEquals(4, new SoundWave(retained).frames); assertEquals(2, new SoundWave(retained).channels);
        ByteBuffer b = ByteBuffer.wrap(retained).order(ByteOrder.LITTLE_ENDIAN); assertEquals(100, b.getShort(44)); assertEquals(300, b.getShort(46));
        assertEquals(200, ByteBuffer.wrap(SoundWave.convert(stereo, 1, 48000, 100)).order(ByteOrder.LITTLE_ENDIAN).getShort(44));
    }
}

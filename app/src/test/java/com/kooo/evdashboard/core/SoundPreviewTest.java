package com.kooo.evdashboard.core;

import org.junit.Test;
import java.nio.*;
import java.util.*;
import static org.junit.Assert.*;

public class SoundPreviewTest {
    @Test public void shortTickRepeatsWithoutChangingSourceSamplesGainOrChannels() throws Exception {
        short[] pcm=new short[4800*2];for(int i=0;i<pcm.length;i++)pcm[i]=(short)(i%2==0?17000:-7000);
        byte[] source=SoundWave.encode(pcm,48000,2),before=source.clone(),preview=SoundPreview.prepare(source);
        SoundWave wave=new SoundWave(preview);assertEquals(2,wave.channels);assertEquals(48000,wave.rate);assertEquals(2.35,wave.seconds(),.001);assertArrayEquals(before,source);
        ShortBuffer data=ByteBuffer.wrap(preview,wave.dataAt,wave.dataBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int lead=48000*SoundPreview.LEAD_MS/1000,gap=48000*SoundPreview.GAP_MS/1000;
        for(int frame=0;frame<wave.frames;frame++)for(int ch=0;ch<2;ch++) {
            int expected=0;for(int n=0;n<SoundPreview.REPEATS;n++){int at=frame-lead-n*(4800+gap);if(at>=0&&at<4800)expected=pcm[at*2+ch];}
            assertEquals(expected,data.get());
        }
    }
    @Test public void normalDurationAndCompositeSoundRemainExact() throws Exception {
        byte[] half=SoundWave.encode(new short[24000],48000,1),composite=SoundWave.encode(new short[38400],48000,1);
        assertSame(half,SoundPreview.prepare(half));assertSame(composite,SoundPreview.prepare(composite));
    }
    @Test public void muteAndAdjustedGainStayComparableWithoutNormalization() throws Exception {
        short[] pcm=new short[4800];Arrays.fill(pcm,(short)10000);byte[] source=SoundWave.encode(pcm,48000,1);
        byte[] half=SoundPreview.prepare(new SoundWave(source).volume(50)),muted=SoundPreview.prepare(new SoundWave(source).volume(0));
        SoundWave wave=new SoundWave(half);int peak=0;ShortBuffer data=ByteBuffer.wrap(half,wave.dataAt,wave.dataBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();while(data.hasRemaining())peak=Math.max(peak,Math.abs((int)data.get()));assertEquals(5000,peak);
        SoundWave quiet=new SoundWave(muted);for(int i=quiet.dataAt;i<quiet.dataAt+quiet.dataBytes;i++)assertEquals(0,muted[i]);
    }
}

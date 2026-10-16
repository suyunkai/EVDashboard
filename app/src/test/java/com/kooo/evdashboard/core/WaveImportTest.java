package com.kooo.evdashboard.core;

import org.junit.Test;
import java.io.UnsupportedEncodingException;
import java.nio.*;
import java.util.*;
import static org.junit.Assert.*;

public class WaveImportTest {
    private byte[] wave(int tag,int rate,int channels,int bits,byte[] data,int validBits) {
        int fmt=validBits<0?16:40;
        ByteBuffer b=ByteBuffer.allocate(28+fmt+data.length+(data.length&1)).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(b.capacity()-8).putInt(0x45564157).putInt(0x20746d66).putInt(fmt);
        b.putShort((short)(fmt==40?0xfffe:tag)).putShort((short)channels).putInt(rate).putInt(rate*channels*bits/8).putShort((short)(channels*bits/8)).putShort((short)bits);
        if(fmt==40)b.putShort((short)22).putShort((short)validBits).putInt(channels==1?4:3).putInt(tag).putInt(0x00100000).putLong(0x719b3800aa000080L);
        b.putInt(0x61746164).putInt(data.length).put(data);return b.array();
    }
    private byte[] integers(int bits,long...samples) {ByteBuffer b=ByteBuffer.allocate(samples.length*bits/8).order(ByteOrder.LITTLE_ENDIAN);for(long v:samples)for(int n=0;n<bits/8;n++)b.put((byte)(v>>(n*8)));return b.array();}
    private short[] convert(byte[] input,int channels)throws Exception {byte[] w=WaveImport.convert(input,channels,100);SoundWave s=new SoundWave(w);assertEquals(48000,s.rate);assertEquals(channels,s.channels);short[] samples=new short[s.dataBytes/2];ByteBuffer b=ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);b.position(s.dataAt);b.asShortBuffer().get(samples);return samples;}

    @Test public void convertsIntegerDepthsWithCorrectSignednessAndClipping()throws Exception {
        assertArrayEquals(new short[]{-32768,0,32512},convert(wave(1,48000,1,8,new byte[]{0,(byte)128,(byte)255},-1),1));
        assertArrayEquals(new short[]{-32768,-1000,0,1000,32767},convert(wave(1,48000,1,24,integers(24,-8388608,-256000,0,256000,8388607),-1),1));
        assertArrayEquals(new short[]{-32768,-1000,0,1000,32767},convert(wave(1,48000,1,32,integers(32,Integer.MIN_VALUE,-65536000,0,65536000,Integer.MAX_VALUE),-1),1));
        byte[] pcm=SoundWave.encode(new short[]{100,-200,300,-400},48000,2);
        assertArrayEquals(pcm,WaveImport.convert(pcm,2,100));
    }
    @Test public void convertsFloat32AndFloat64AndClampsFiniteOverrange()throws Exception {
        for(int bits:new int[]{32,64}) {
            ByteBuffer b=ByteBuffer.allocate(6*bits/8).order(ByteOrder.LITTLE_ENDIAN);
            for(double v:new double[]{-2,-1,-.5,0,.5,2})if(bits==32)b.putFloat((float)v);else b.putDouble(v);
            assertArrayEquals(new short[]{-32768,-32768,-16384,0,16384,32767},convert(wave(3,48000,1,bits,b.array(),-1),1));
        }
    }
    @Test public void rejectsNonfiniteSamplesEvenWhenDownsamplingWouldSkipThem() {
        for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            ByteBuffer b=ByteBuffer.allocate(8*4).order(ByteOrder.LITTLE_ENDIAN);b.putFloat(8,value);
            assertThrows(Exception.class,()->convert(wave(3,192000,1,32,b.array(),-1),1));
        }
    }
    @Test public void extensiblePcmHonorsValidBitAlignmentAndFullSubtypeGuid()throws Exception {
        byte[] input=wave(1,48000,2,32,integers(32,65536000,-131072000,196608000,-262144000),24);
        assertArrayEquals(new short[]{1000,-2000,3000,-4000},convert(input,2));
        assertArrayEquals(new short[]{-500,-500},convert(input,1));
        byte[] floatWave=wave(3,48000,1,32,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(.5f).array(),32);
        assertArrayEquals(new short[]{16384,16384},convert(floatWave,2));
        byte[] bad=input.clone();bad[20+32]^=1;
        assertThrows(UnsupportedEncodingException.class,()->convert(bad,2));
        byte[] padding=input.clone();padding[68]=1;
        assertThrows(Exception.class,()->convert(padding,2));
        byte[] mask=input.clone();ByteBuffer.wrap(mask).order(ByteOrder.LITTLE_ENDIAN).putInt(40,1);
        assertThrows(Exception.class,()->convert(mask,2));
    }
    @Test public void resamplesAndKeepsStereoOrDuplicatesMonoWithoutEditingInput()throws Exception {
        byte[] input=wave(1,24000,2,24,integers(24,25600,76800,76800,128000),-1),before=input.clone();
        assertArrayEquals(new short[]{100,300,200,400,300,500,300,500},convert(input,2));
        assertArrayEquals(new short[]{200,300,400,400},convert(input,1));assertArrayEquals(before,input);
    }
    @Test public void skipsUnknownChunksAndAcceptsDataBeforeFormatWithOddPadding()throws Exception {
        byte[] raw=wave(1,48000,1,8,new byte[]{(byte)128},-1);
        ByteBuffer b=ByteBuffer.allocate(raw.length+10).order(ByteOrder.LITTLE_ENDIAN);
        b.put(raw,0,12).putInt(0x4b4e554a).putInt(1).put((byte)7).put((byte)0).put(raw,36,10).put(raw,12,24).putInt(4,b.capacity()-8);
        assertArrayEquals(new short[]{0},convert(b.array(),1));
    }
    @Test public void rejectsMalformedFramesSizesFormatsAndDuration()throws Exception {
        byte[] good=wave(1,48000,1,24,integers(24,123,456),-1);
        for(int[] edit:new int[][]{{4,good.length},{16,-1},{28,1},{40,5}}) {
            byte[] broken=good.clone();ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(edit[0],edit[1]);
            assertThrows(Exception.class,()->convert(broken,1));
        }
        byte[] truncated=Arrays.copyOf(good,good.length-1);ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(4,truncated.length-8);
        assertThrows(Exception.class,()->convert(truncated,1));
        assertThrows(Exception.class,()->convert(wave(1,8000,1,8,new byte[8000*31],-1),1));
        assertThrows(Exception.class,()->convert(wave(1,48000,1,20,new byte[10],-1),1));
        assertThrows(UnsupportedEncodingException.class,()->convert(wave(17,48000,1,8,new byte[8],-1),1));
    }
    @Test public void gainIsIndependentAndNeverChangesSource()throws Exception {
        byte[] input=wave(1,48000,1,24,integers(24,256000,-512000),-1),before=input.clone();
        SoundWave muted=new SoundWave(WaveImport.convert(input,1,0));assertEquals(2,muted.frames);
        assertArrayEquals(new short[]{0,0},convert(WaveImport.convert(input,1,0),1));assertArrayEquals(before,input);
    }
}

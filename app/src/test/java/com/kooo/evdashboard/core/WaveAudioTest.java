package com.kooo.evdashboard.core;

import org.junit.Test;
import java.nio.*;
import static org.junit.Assert.*;

public class WaveAudioTest {
    @Test public void resampleAndDownmix()throws Exception{short[] s=WaveAudio.convert(new short[]{100,300,300,500},2,24000,100);assertEquals(4,s.length);assertEquals(200,s[0]);assertEquals(400,s[3]);}
    @Test public void limitsPeak()throws Exception{short[] s=WaveAudio.convert(new short[]{32767,-32768},1,48000,200);for(short v:s)assertTrue(Math.abs((int)v)<=30000);}
    @Test public void wavAndSilencePreserveHeader()throws Exception{byte[] wave=WaveAudio.encodeMono(new short[]{12,-32,44},48000),silence=WaveAudio.silence(wave);assertArrayEquals(java.util.Arrays.copyOf(wave,44),java.util.Arrays.copyOf(silence,44));for(int i=44;i<silence.length;i++)assertEquals(0,silence[i]);}
    @Test public void invalidChunkRejected()throws Exception{byte[] wave=WaveAudio.encodeMono(new short[]{12,-32,44},48000);ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN).putInt(40,Integer.MAX_VALUE);assertThrows(Exception.class,()->WaveAudio.silence(wave));}
    @Test public void unsupportedAndOverlongRejected(){assertThrows(Exception.class,()->WaveAudio.convert(new short[]{1},0,48000,100));assertThrows(Exception.class,()->WaveAudio.encodeMono(new short[48000*30+1],48000));assertThrows(Exception.class,()->WaveAudio.convert(new short[]{1},1,48000,0));}
    @Test public void wrongRiffLengthRejected()throws Exception{byte[] w=WaveAudio.encodeMono(new short[]{1,2},48000);ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN).putInt(4,w.length);assertThrows(Exception.class,()->WaveAudio.silence(w));}
    @Test public void incompleteTrailingChunkRejected()throws Exception{byte[] w=java.util.Arrays.copyOf(WaveAudio.encodeMono(new short[]{1,2},48000),51);ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN).putInt(4,w.length-8);assertThrows(Exception.class,()->WaveAudio.silence(w));}
    @Test public void inconsistentSampleLayoutRejected()throws Exception{byte[] w=WaveAudio.encodeMono(new short[]{1,2},48000);ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN).putShort(22,(short)2);assertThrows(Exception.class,()->WaveAudio.silence(w));}
    @Test public void unalignedDataRejected()throws Exception{byte[] w=WaveAudio.encodeMono(new short[]{1,2},48000);ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN).putInt(40,3);assertThrows(Exception.class,()->WaveAudio.silence(w));}
    @Test public void normalizedInputIsFullyValidated()throws Exception{short[] pcm={1,-2,3};byte[] w=WaveAudio.encodeMono(pcm,48000);assertArrayEquals(pcm,WaveAudio.normalizedSamples(w));w[0]='X';assertThrows(Exception.class,()->WaveAudio.normalizedSamples(w));}
    @Test public void directWaveParserResamplesAndChecksDuration()throws Exception{byte[] w=WaveAudio.encodeMono(new short[24000],48000);ByteBuffer b=ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);b.putInt(24,24000).putInt(28,48000);assertEquals(48000,WaveAudio.normalizedSamples(WaveAudio.normalizeWave(w,100)).length);byte[] longWave=WaveAudio.encodeMono(new short[48000*6],48000);ByteBuffer.wrap(longWave).order(ByteOrder.LITTLE_ENDIAN).putInt(24,8000).putInt(28,16000);assertThrows(Exception.class,()->WaveAudio.normalizeWave(longWave,100));}
}

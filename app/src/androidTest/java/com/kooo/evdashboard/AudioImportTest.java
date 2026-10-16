package com.kooo.evdashboard;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evdashboard.core.WaveAudio;
import com.kooo.evdashboard.core.SoundWave;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Local codec tests only; no playback, networking or CP access. */
@RunWith(AndroidJUnit4.class)
public class AudioImportTest {
    private static byte[] wave(short[] samples,int rate){java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(44+samples.length*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);b.putInt(0x46464952).putInt(b.capacity()-8).putInt(0x45564157).putInt(0x20746d66).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).putInt(0x61746164).putInt(samples.length*2);for(short s:samples)b.putShort(s);return b.array();}
    private static byte[] decode(Context c,File f)throws Exception{return decode(c,f,1);}
    private static byte[] decode(Context c,File f,int channels)throws Exception{java.lang.reflect.Method method=Class.forName("com.kooo.evdashboard.AudioImport").getDeclaredMethod("decode",Context.class,Uri.class,int.class,int.class);method.setAccessible(true);try{return (byte[])method.invoke(null,c,Uri.fromFile(f),100,channels);}catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw failure;}}
    private static File fixture(String name)throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File f=File.createTempFile("audio-import-","-"+name,c.getCacheDir());
        try(java.io.InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)){Files.copy(in,f.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}return f;
    }
    private static SoundWave assertOutput(byte[] data,int channels)throws Exception{
        SoundWave wave=new SoundWave(data);assertEquals(48000,wave.rate);assertEquals(channels,wave.channels);
        java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(1,b.getShort(20));assertEquals(16,b.getShort(34));assertEquals(48000*channels*2,b.getInt(28));assertEquals(channels*2,b.getShort(32));
        int peak=0;long difference=0;for(int at=wave.dataAt;at<wave.dataAt+wave.dataBytes;at+=2)peak=Math.max(peak,Math.abs((int)b.getShort(at)));
        if(channels==2)for(int at=wave.dataAt;at+3<wave.dataAt+wave.dataBytes;at+=4)difference+=Math.abs((int)b.getShort(at)-b.getShort(at+2));
        assertTrue("Decoded audio must not be silent",peak>100);if(channels==2)assertTrue("Distinct stereo channels must be retained",difference>0);return wave;
    }
    @Test public void actualImporterResamplesWaveToCanonicalMono()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File f=File.createTempFile("audio-codec-",".wav",c.getCacheDir());
        try{short[] samples=new short[24000];for(int i=0;i<samples.length;i++)samples[i]=(short)(1000*Math.sin(i*0.02));Files.write(f.toPath(),wave(samples,24000));byte[] decoded=decode(c,f);assertEquals(48000,WaveAudio.normalizedSamples(decoded).length);}finally{Files.deleteIfExists(f.toPath());}
    }
    @Test public void overlongAudioRejected()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File f=File.createTempFile("audio-long-",".wav",c.getCacheDir());
        try{Files.write(f.toPath(),wave(new short[8000*31],8000));assertThrows(Exception.class,()->decode(c,f));}finally{Files.deleteIfExists(f.toPath());}
    }
    @Test public void corruptAudioRejected()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File f=File.createTempFile("audio-invalid-",".wav",c.getCacheDir());
        try{Files.write(f.toPath(),new byte[]{0,1,2,3});assertThrows(Exception.class,()->decode(c,f));}finally{Files.deleteIfExists(f.toPath());}
    }
    @Test public void actualCodecDecodesSyntheticMp3()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File f=File.createTempFile("audio-mp3-",".mp3",c.getCacheDir());
        try(java.io.InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("synthetic-tone.mp3")){Files.copy(in,f.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);short[] pcm=WaveAudio.normalizedSamples(decode(c,f));assertTrue(pcm.length>=10000&&pcm.length<=24000);}finally{Files.deleteIfExists(f.toPath());}
    }
    @Test public void actualImporterPreservesStereoTarget()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File f=File.createTempFile("audio-stereo-",".wav",c.getCacheDir());
        try{
            Files.write(f.toPath(),com.kooo.evdashboard.core.SoundWave.encode(new short[]{1000,-2000,3000,-4000},48000,2));
            java.lang.reflect.Method method=Class.forName("com.kooo.evdashboard.AudioImport").getDeclaredMethod("decode",Context.class,Uri.class,int.class,int.class);method.setAccessible(true);
            byte[] decoded=(byte[])method.invoke(null,c,Uri.fromFile(f),100,2);
            assertEquals(2,new com.kooo.evdashboard.core.SoundWave(decoded).channels);
            java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(decoded).order(java.nio.ByteOrder.LITTLE_ENDIAN);assertEquals(1000,b.getShort(44));assertEquals(-2000,b.getShort(46));
        }finally{Files.deleteIfExists(f.toPath());}
    }

    @Test public void mp4VideoAudioTrackConvertsToMatchingMonoAndStereo()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File f=fixture("synthetic-aac-video.mp4");
        try{for(int channels:new int[]{1,2}){SoundWave wave=assertOutput(decode(c,f,channels),channels);assertTrue(wave.seconds()>=.9&&wave.seconds()<=1.2);}}finally{Files.deleteIfExists(f.toPath());}
    }
    @Test public void videoWithoutAudioTrackIsRejectedClearly()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File f=fixture("synthetic-video-only.mp4");
        try{Exception error=assertThrows(Exception.class,()->decode(c,f));assertTrue(error.getMessage(),error.getMessage().contains("没有音轨"));}finally{Files.deleteIfExists(f.toPath());}
    }
    @Test public void highResolutionWavSourcesConvertToBothTargetLayouts()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(String asset:new String[]{"synthetic-pcm24.wav","synthetic-float32.wav"}){
            File f=fixture(asset);try{for(int channels:new int[]{1,2}){SoundWave wave=assertOutput(decode(c,f,channels),channels);assertEquals(.2,wave.seconds(),1.0/48000);}}finally{Files.deleteIfExists(f.toPath());}
        }
    }

}

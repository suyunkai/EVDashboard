package com.kooo.evdashboard;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evdashboard.core.WaveAudio;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Local codec tests only; no playback, networking or CP access. */
@RunWith(AndroidJUnit4.class)
public class AudioImportTest {
    private static byte[] wave(short[] samples,int rate){java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(44+samples.length*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);b.putInt(0x46464952).putInt(b.capacity()-8).putInt(0x45564157).putInt(0x20746d66).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).putInt(0x61746164).putInt(samples.length*2);for(short s:samples)b.putShort(s);return b.array();}
    private static byte[] decode(Context c,File f)throws Exception{java.lang.reflect.Method method=Class.forName("com.kooo.evdashboard.AudioImport").getDeclaredMethod("decode",Context.class,Uri.class,int.class);method.setAccessible(true);try{return (byte[])method.invoke(null,c,Uri.fromFile(f),100);}catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw failure;}}
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

}

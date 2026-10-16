package com.kooo.evdashboard;

import android.app.Instrumentation;
import android.content.*;
import android.media.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evdashboard.core.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Explicit opt-in: auditions actual Tick/Tock through Android; no CP changes or volume setters. */
@RunWith(AndroidJUnit4.class)
public class SoundPreviewDeviceTest {
    @Test public void actualTurnClipsUseMediaFocusCompleteAndKeepSourceUnchanged() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("cpPreview")));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        assertFalse(context.getSharedPreferences("settings",0).getBoolean("soundAutoApply",false));
        SoundCatalog catalog;try(InputStream in=context.getAssets().open("sounds/catalog.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();FilesEx.transfer(in,out);catalog=new SoundCatalog(out.toString("UTF-8"));}
        SoundStore store=new SoundStore(new File(context.getFilesDir(),"instrument-sounds"),catalog);
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Method preview=MainActivity.class.getDeclaredMethod("preview",File.class,Runnable.class);preview.setAccessible(true);
        Field player=MainActivity.class.getDeclaredField("player"),focus=MainActivity.class.getDeclaredField("previewFocus"),result=MainActivity.class.getDeclaredField("previewFocusResult");player.setAccessible(true);focus.setAccessible(true);result.setAccessible(true);
        int count=0;
        try {
            for(SoundStore.Entry entry:store.entries())if(entry.path.contains("Turn_light")&&entry.path.endsWith("Single.wav")) {
                File source=store.asset(entry.current);String before=FilesEx.digest(source);assertTrue(new SoundWave(store.bytes(entry.current)).seconds()<.5);
                int duration=(int)Math.round(new SoundWave(SoundPreview.prepare(store.bytes(entry.current))).seconds()*1000);
                CountDownLatch completed=new CountDownLatch(1);
                instrumentation.runOnMainSync(()->{try {
                    preview.invoke(activity,source,(Runnable)completed::countDown);
                    if(result.getInt(activity)==AudioManager.AUDIOFOCUS_REQUEST_FAILED){assertNull(player.get(activity));assertNull(focus.get(activity));assertEquals(0,completed.getCount());return;}
                    MediaPlayer active=(MediaPlayer)player.get(activity);assertNotNull(active);assertTrue(active.isPlaying());assertEquals(duration,active.getDuration());
                    AudioFocusRequest request=(AudioFocusRequest)focus.get(activity);assertNotNull(request);
                    assertEquals(AudioAttributes.USAGE_MEDIA,request.getAudioAttributes().getUsage());assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,request.getFocusGain());
                    System.out.println("Turn preview started: "+entry.path+", session="+active.getAudioSessionId()+", duration="+active.getDuration()+" ms, MEDIA focus");
                }catch(Exception e){throw new AssertionError(e);}});
                Assume.assumeTrue("Vehicle audio policy denied media focus; audible preview requires normal powered state",result.getInt(activity)!=AudioManager.AUDIOFOCUS_REQUEST_FAILED);
                assertTrue("Preview must complete",completed.await(8,TimeUnit.SECONDS));instrumentation.waitForIdleSync();
                assertNull(player.get(activity));assertNull(focus.get(activity));assertEquals(before,FilesEx.digest(source));count++;
            }
            assertEquals("Tick and Tock previewed",2,count);
        }finally {instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void actualTurnClipsDecodeWithLeadInAndRepeatsWithoutAudioOutput() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("cpPreview")));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();SoundCatalog catalog;
        try(InputStream in=context.getAssets().open("sounds/catalog.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();FilesEx.transfer(in,out);catalog=new SoundCatalog(out.toString("UTF-8"));}
        SoundStore store=new SoundStore(new File(context.getFilesDir(),"instrument-sounds"),catalog);int count=0;
        File prepared=new File(context.getCacheDir(),"turn-preview-decoder-qa.wav");
        try {
            for(SoundStore.Entry e:store.entries())if(e.path.contains("Turn_light")&&e.path.endsWith("Single.wav")) {
                File source=store.asset(e.current);String before=FilesEx.digest(source);
                byte[] preview=SoundPreview.prepare(store.bytes(e.current));int duration=(int)Math.round(new SoundWave(preview).seconds()*1000);
                java.nio.file.Files.write(prepared.toPath(),preview);
                MediaPlayer decoder=new MediaPlayer();
                try {decoder.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());decoder.setDataSource(prepared.getAbsolutePath());decoder.prepare();assertEquals(duration,decoder.getDuration());}
                finally {decoder.release();}
                assertEquals(before,FilesEx.digest(source));count++;
            }
            assertEquals(2,count);
        }finally {java.nio.file.Files.deleteIfExists(prepared.toPath());}
    }
}

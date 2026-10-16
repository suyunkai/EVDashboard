package com.kooo.evdashboard;

import android.content.Context;
import android.content.Intent;
import android.app.Instrumentation;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evdashboard.core.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Explicitly opt-in. Only reads CP resources; never calls apply, restore or a vehicle setter. */
@RunWith(AndroidJUnit4.class)
public class SoundReadOnlyTest {
    @Test public void readFromSoundPageCompletesThroughTaskService() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("cpReadOnly")));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context c=instrumentation.getTargetContext();
        assertFalse("This read-only test requires auto apply to be off",c.getSharedPreferences("settings",0).getBoolean("soundAutoApply",false));
        MainActivity a=(MainActivity)instrumentation.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));instrumentation.waitForIdleSync();
        try {
            Object state=a.getApplication();java.lang.reflect.Field busyField=state.getClass().getDeclaredField("busy"),failed=state.getClass().getDeclaredField("failed"),progressField=state.getClass().getDeclaredField("progress");
            for(java.lang.reflect.Field field:new java.lang.reflect.Field[]{busyField,failed,progressField})field.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean busy=(java.util.concurrent.atomic.AtomicBoolean)busyField.get(state);assertFalse(busy.get());
            java.lang.reflect.Field segments=MainActivity.class.getDeclaredField("segButtons"),items=MainActivity.class.getDeclaredField("navItems");segments.setAccessible(true);items.setAccessible(true);
            instrumentation.runOnMainSync(()->{try {((Button[])segments.get(a))[0].performClick();((View[][])items.get(a))[0][1].performClick();}catch(Exception e){throw new AssertionError(e);}});
            // Opening the trusted sound library invokes the same refresh action as its button.
            assertTrue("Read task started",busy.get());long deadline=System.nanoTime()+45_000_000_000L;boolean captured=false;
            Object progress=progressField.get(state);
            while(busy.get()&&System.nanoTime()<deadline) {
                Object snapshot=progress.getClass().getMethod("snapshot").invoke(progress);
                if(!captured&&(int)snapshot.getClass().getMethod("percent").invoke(snapshot)>0) {capture(instrumentation,a,"real-read-running");captured=true;}
                Thread.sleep(50);
            }
            assertFalse("Read task completed within deadline",busy.get());assertFalse("Read task succeeded",failed.getBoolean(state));
            Object snapshot=progress.getClass().getMethod("snapshot").invoke(progress);assertEquals(true,snapshot.getClass().getMethod("successful").invoke(snapshot));
            instrumentation.waitForIdleSync();capture(instrumentation,a,"real-read-complete");
        }finally {instrumentation.runOnMainSync(a::finish);}
    }
    private void capture(Instrumentation instrumentation,MainActivity activity,String name) {
        instrumentation.runOnMainSync(()->{
            View view=activity.getWindow().getDecorView();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(bitmap));File folder=new File(activity.getFilesDir(),"sound-read-qa");folder.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new AssertionError(e);}finally{bitmap.recycle();}
        });
    }
    @Test public void readActualCpAndVerifyTestAudioDirectory() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("cpReadOnly")));
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Class<?> ssh=Class.forName("com.kooo.evdashboard.SshAccess"); java.lang.reflect.Method trusted=ssh.getDeclaredMethod("trusted",Context.class);trusted.setAccessible(true);
        assertEquals("Current CP fingerprint must already be trusted",true,trusted.invoke(null,c));
        java.lang.reflect.Field busy=c.getApplicationContext().getClass().getDeclaredField("busy");busy.setAccessible(true);
        assertFalse(((java.util.concurrent.atomic.AtomicBoolean)busy.get(c.getApplicationContext())).get());
        Class<?> engine=Class.forName("com.kooo.evdashboard.AudioEngine");java.lang.reflect.Constructor<?> constructor=engine.getDeclaredConstructor(Context.class,java.util.function.Consumer.class);constructor.setAccessible(true);
        java.lang.reflect.Method refresh=engine.getDeclaredMethod("refresh",String.class);refresh.setAccessible(true);
        long start=System.nanoTime();
        Object instance=constructor.newInstance(c,(java.util.function.Consumer<String>)message->System.out.println(message));
        refresh.invoke(instance,"");
        SoundCatalog catalog;
        try(InputStream in=c.getAssets().open("sounds/catalog.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();FilesEx.transfer(in,out);catalog=new SoundCatalog(out.toString("UTF-8"));}
        SoundStore store=new SoundStore(new File(c.getFilesDir(),"instrument-sounds"),catalog);
        List<SoundStore.Entry> entries=store.entries();assertFalse(entries.isEmpty());
        for(SoundStore.Entry e:entries){assertTrue(SoundCatalog.allowedPath(e.path));assertNotNull(new SoundWave(store.bytes(e.current)));}
        System.out.println("CP read complete: "+entries.size()+" verified sounds, "+((System.nanoTime()-start)/1_000_000)+" ms");
        start=System.nanoTime();refresh.invoke(instance,"");
        java.lang.reflect.Field downloaded=engine.getDeclaredField("lastDownloaded");downloaded.setAccessible(true);
        assertEquals("Unchanged CP sounds must reuse verified local audio without WAV downloads",0,downloaded.getInt(instance));
        System.out.println("CP cached hash check complete: 0 audio downloads, "+((System.nanoTime()-start)/1_000_000)+" ms");
    }
    @Test public void verifyTestAudioDirectory() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("dsAudioFiles")));
        File folder=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"DSaudio");
        File[] files=folder.listFiles((d,name)->name.endsWith(".wav")||name.endsWith(".mp3")||name.endsWith(".m4a"));
        assertNotNull("DSaudio must be visible to the app",files);assertEquals(12,files.length);
        for(File file:files)assertTrue(Files.readAllBytes(file.toPath()).length>0);
    }
}

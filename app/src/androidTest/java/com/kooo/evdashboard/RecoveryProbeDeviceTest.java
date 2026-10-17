package com.kooo.evdashboard;

import android.content.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evdashboard.core.FilesEx;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Opt-in checks against the existing configuration. No CP mount, upload or service commands. */
@RunWith(AndroidJUnit4.class)
public class RecoveryProbeDeviceTest {
    private Context context;
    private SharedPreferences prefs;
    private Class<?> engineType, monitorType;
    private File proof, snapshot, rules;
    private String rulesBefore;
    private boolean initialized;
    private final JSONObject metrics = new JSONObject();
    @Before public void setup() throws Exception {
        Assume.assumeTrue("127.0.0.1:5556".equals(InstrumentationRegistry.getArguments().getString("allowedSerial")));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("E245", android.os.Build.MODEL); assertEquals(11, android.os.Process.myUid() / 100000);
        prefs = context.getSharedPreferences("settings", 0);
        assertTrue("Existing automatic configuration required", prefs.getBoolean("soundAutoApply", false));
        assertFalse("Resolve an uncertain task before this test", prefs.getBoolean("startupApplyUncertain", false));
        engineType = context.getClassLoader().loadClass("com.kooo.evdashboard.AudioEngine");
        monitorType = context.getClassLoader().loadClass("com.kooo.evdashboard.AutoApplyService");
        context.stopService(new Intent().setComponent(new ComponentName(context.getPackageName(), monitorType.getName())));
        context.getSystemService(android.app.job.JobScheduler.class).cancel(4201);
        Field f = context.getApplicationContext().getClass().getDeclaredField("executor"); f.setAccessible(true);
        ((ExecutorService)f.get(context.getApplicationContext())).submit(() -> {}).get(40, TimeUnit.SECONDS);
        Thread.sleep(500);
        proof = new File(context.getFilesDir(), "sound-recovery.json"); snapshot = new File(context.getFilesDir(), "instrument-sounds/snapshot.json");
        rules = new File(context.getFilesDir(), "instrument-sounds/rules.json"); rulesBefore = FilesEx.digest(rules); initialized = true;
    }
    private Object call(Class<?> type, Object target, String name, Class<?>[] types, Object...args) throws Exception {
        Method m = type.getDeclaredMethod(name, types); m.setAccessible(true);
        try {return m.invoke(target, args);} catch (InvocationTargetException e) {throw (Exception)e.getCause();}
    }
    private Object engine() throws Exception {
        Constructor<?> c = engineType.getDeclaredConstructor(Context.class, Consumer.class, boolean.class); c.setAccessible(true);
        return c.newInstance(context, (Consumer<String>)s -> {}, false);
    }
    private void check(boolean force, boolean light) throws Exception {
        Object engine = engine(); assertFalse("Existing sounds must already match; no writes permitted", (Boolean)call(engineType, engine, "prepareAutomatic", new Class<?>[]{boolean.class}, force));
        assertEquals(light, call(engineType, engine, "lightCheck", new Class<?>[]{}));
    }
    private long count() throws Exception {Field f = monitorType.getDeclaredField("checkCount"); f.setAccessible(true); return f.getLong(null);}
    private void start(String reason) {context.startForegroundService(new Intent().setComponent(new ComponentName(context.getPackageName(), monitorType.getName())).putExtra("reason", reason));}
    private long reads() throws Exception {
        for (String line : FilesEx.readText(new File("/proc/self/io")).split("\n")) if (line.startsWith("rchar:")) return Long.parseLong(line.substring(6).trim());
        throw new IOException("Missing process read counter");
    }
    @After public void cleanup() throws Exception {
        if (!initialized) return;
        prefs.edit().remove("startupApplyUncertain").commit();
        assertEquals(rulesBefore, FilesEx.digest(rules));
        FilesEx.atomicText(new File(context.getFilesDir(), "recovery-probe-0.2.10.json"), metrics.toString(2));
        start("SERVICE_RESTART");
    }
    @Test public void unchangedWakeChecksSkipAudioAndWatchdogDoesNotRepeatWork() throws Exception {
        check(true, false); assertTrue(proof.isFile());
        String snapshotBefore = FilesEx.digest(snapshot), proofBefore = FilesEx.digest(proof);
        long bytes = reads(), start = SystemClock.elapsedRealtime();
        for (int i = 0; i < 3; i++) check(false, true);
        metrics.put("threeLightChecksMs", SystemClock.elapsedRealtime() - start).put("threeLightChecksReadBytes", reads() - bytes);
        assertTrue("Light checks must not read WAVs: " + metrics, metrics.getLong("threeLightChecksReadBytes") < 4 * 1024 * 1024);
        assertEquals(snapshotBefore, FilesEx.digest(snapshot)); assertEquals(proofBefore, FilesEx.digest(proof));

        JSONObject stale = new JSONObject(FilesEx.readText(proof)); stale.put("cpBoot", "00000000-0000-0000-0000-000000000000");
        FilesEx.atomicText(proof, stale.toString()); check(false, false); check(false, true);
        prefs.edit().putBoolean("startupApplyUncertain", true).commit(); check(false, false);
        assertFalse(prefs.getBoolean("startupApplyUncertain", false)); check(false, true);

        long before = count(); start("SERVICE_RESTART"); long until = SystemClock.elapsedRealtime() + 15_000;
        while (count() == before && SystemClock.elapsedRealtime() < until) Thread.sleep(100);
        assertEquals(before + 1, count());
        Field mode = monitorType.getDeclaredField("lastCheckLight"); mode.setAccessible(true); assertTrue(mode.getBoolean(null));
        long stableCount = count();
        Class<?> jobType = context.getClassLoader().loadClass("com.kooo.evdashboard.RecoveryJobService"); Object job = jobType.getConstructor().newInstance();
        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(job, context);
        assertEquals(false, call(jobType, job, "onStartJob", new Class<?>[]{android.app.job.JobParameters.class}, (Object)null));
        call(monitorType, null, "start", new Class<?>[]{Context.class,String.class}, context, "WATCHDOG");
        // Crossing the old one-minute boundary must not schedule another check.
        until = SystemClock.elapsedRealtime() + 66_000;
        while (SystemClock.elapsedRealtime() < until) {assertEquals(stableCount, count()); Thread.sleep(500);}
        metrics.put("noCheckWithin66Seconds", true).put("watchdogSkippedWhenMonitorAlive", true);
        System.out.println("RECOVERY_PROBE_PASS " + metrics);
    }
}

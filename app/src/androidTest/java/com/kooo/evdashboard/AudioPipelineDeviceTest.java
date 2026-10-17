package com.kooo.evdashboard;

import android.content.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evdashboard.core.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** Opt-in physical-device benchmark. Preserves the user's rules and restores their actual sounds. */
@RunWith(AndroidJUnit4.class)
public class AudioPipelineDeviceTest {
    private Context context;
    private SharedPreferences prefs;
    private Class<?> engineType;
    private File rulesFile;
    private SoundCatalog catalog;
    private SoundStore store;
    private String initial;
    private Map<String, SoundStore.Rule> rules;
    private boolean automatic;
    private final JSONObject metrics = new JSONObject();

    @Before public void setup() throws Exception {
        Assume.assumeTrue("127.0.0.1:5556".equals(InstrumentationRegistry.getArguments().getString("allowedSerial"))
            && "true".equals(InstrumentationRegistry.getArguments().getString("applyExistingRules")));
        assertEquals("E245", android.os.Build.MODEL); assertEquals(11, android.os.Process.myUid() / 100000);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("settings", 0); automatic = prefs.getBoolean("soundAutoApply", false);
        pause();
        engineType = context.getClassLoader().loadClass("com.kooo.evdashboard.AudioEngine");
        try (InputStream in = context.getAssets().open("sounds/catalog.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); FilesEx.transfer(in, out); catalog = new SoundCatalog(out.toString("UTF-8"));
        }
        store = new SoundStore(new File(context.getFilesDir(), "instrument-sounds"), catalog);
        rulesFile = new File(context.getFilesDir(), "instrument-sounds/rules.json"); initial = FilesEx.readText(rulesFile); rules = store.rules();
        assertEquals(38, rules.size());
    }
    private Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = engineType.getDeclaredMethod(name, types); m.setAccessible(true);
        try {return m.invoke(target, args);} catch (InvocationTargetException e) {throw (Exception)e.getCause();}
    }
    private Object engine() throws Exception {
        Constructor<?> c = engineType.getDeclaredConstructor(Context.class, Consumer.class, boolean.class); c.setAccessible(true);
        return c.newInstance(context, (Consumer<String>)s -> System.out.println("PIPELINE " + s), false);
    }
    private void apply(String phase) throws Exception {
        long start = SystemClock.elapsedRealtime(); invoke(engine(), "apply", new Class<?>[]{String.class,boolean.class}, "", false);
        metrics.put(phase + "Ms", SystemClock.elapsedRealtime() - start); System.out.println("TIMING " + phase + "=" + metrics.getLong(phase + "Ms"));
    }
    private void pause() throws Exception {
        prefs.edit().putBoolean("soundAutoApply", false).commit();
        context.stopService(new Intent().setComponent(new ComponentName(context.getPackageName(), "com.kooo.evdashboard.AutoApplyService")));
        Field busyField = context.getApplicationContext().getClass().getDeclaredField("busy"); busyField.setAccessible(true);
        AtomicBoolean busy = (AtomicBoolean)busyField.get(context.getApplicationContext());
        long until = SystemClock.elapsedRealtime() + 120_000;
        while (busy.get() && SystemClock.elapsedRealtime() < until) Thread.sleep(250);
        assertFalse("Wait for current task before live changes", busy.get()); Thread.sleep(500);
    }
    private void verifyRules() throws Exception {
        Map<String, String> expected = new HashMap<>(); for (Map.Entry<String, SoundStore.Rule> r : rules.entrySet()) expected.put(r.getKey(), store.render(r.getValue()));
        SoundSync.verify(expected, store.entries());
        assertEquals(initial, FilesEx.readText(rulesFile)); assertFalse(prefs.getBoolean("startupApplyUncertain", false));
    }
    @Test public void benchmarkPresetSwitchAndWakeRecovery() throws Exception {
        try {
            invoke(engine(), "refresh", new Class<?>[]{String.class}, ""); verifyRules();
            SoundPreset preset;
            try (InputStream in = context.getAssets().open("presets/botw/preset.json")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); FilesEx.transfer(in, out); preset = SoundPreset.parse(out.toString("UTF-8"), catalog);
            }
            Map<String, byte[]> audio = new LinkedHashMap<>();
            for (SoundPreset.Item item : preset.items()) if (rules.containsKey(item.path)) {
                audio.put(item.path, PresetAudio.read(context.getAssets().open("presets/botw/" + item.file + ".packed"), true, item.sha256));
            }
            assertEquals(rules.size(), audio.size());
            PresetApply.Result result = PresetApply.apply(store, catalog, store.entries(), audio); assertTrue(result.skipped.isEmpty());
            Map<String, SoundStore.Rule> other = store.rules();
            for (String path : rules.keySet()) {SoundStore.Rule r = other.get(path); other.put(path, new SoundStore.Rule(r.original, r.replacement, rules.get(path).gain));}
            store.rules(other); apply("alternatePreset");
            store.rules(rules); FilesEx.atomicText(rulesFile, initial); apply("originalPreset"); verifyRules();
            try {store.rules(Collections.emptyMap()); apply("restoreFactory");}
            finally {store.rules(rules); FilesEx.atomicText(rulesFile, initial);}
            // Traverse the real receiver and service, with the user configuration restored.
            long start = SystemClock.elapsedRealtime();
            prefs.edit().putBoolean("soundAutoApply", true).remove("startupBlockedUntil").remove("startupStatus").commit();
            context.sendBroadcast(new Intent("com.ecarx.intent.action.STR_RESUME").setComponent(new ComponentName(context.getPackageName(), "com.kooo.evdashboard.BootReceiver")));
            long until = start + 120_000; boolean recovered = false;
            while (SystemClock.elapsedRealtime() < until) {
                if (prefs.getString("startupStatus", "").startsWith("声音配置已恢复")) {recovered = true; break;}
                Thread.sleep(250);
            }
            assertTrue("Wake recovery failed: " + prefs.getString("startupStatus", ""), recovered);
            metrics.put("wakeRecoveryMs", SystemClock.elapsedRealtime() - start);
            System.out.println("TIMING wakeRecovery=" + metrics.getLong("wakeRecoveryMs")); verifyRules();
        } finally {
            pause(); store.rules(rules); FilesEx.atomicText(rulesFile, initial); apply("finalVerify"); verifyRules();
            prefs.edit().putBoolean("soundAutoApply", automatic).commit();
            if (automatic) context.startForegroundService(new Intent().setComponent(new ComponentName(context.getPackageName(), "com.kooo.evdashboard.AutoApplyService")).putExtra("reason", "APP_START"));
            FilesEx.atomicText(new File(context.getFilesDir(), "audio-optimization-0.2.9.json"), metrics.toString(2));
        }
    }
}

package com.kooo.evdashboard;

import android.content.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evdashboard.core.FilesEx;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicitly opted-in live checks. Uses existing user rules; never generates test overlays. */
@RunWith(AndroidJUnit4.class)
public class StartupDeviceTest {
    private Context context;
    private SharedPreferences prefs;
    private ClassLoader loader;
    private Class<?> sshType, engineType;
    @Before public void setup() throws Exception {
        Assume.assumeTrue("127.0.0.1:5557".equals(InstrumentationRegistry.getArguments().getString("allowedSerial")));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("E245", android.os.Build.MODEL);
        prefs = context.getSharedPreferences("settings", 0); loader = context.getClassLoader();
        sshType = loader.loadClass("com.kooo.evdashboard.SshAccess"); engineType = loader.loadClass("com.kooo.evdashboard.AudioEngine");
    }
    private Object call(Class<?> type, Object target, String name, Class<?>[] types, Object...args) throws Exception {
        Method method = type.getDeclaredMethod(name, types); method.setAccessible(true);
        try {return method.invoke(target, args);} catch (InvocationTargetException e) {throw (Exception)e.getCause();}
    }
    private Object engine() throws Exception {
        Constructor<?> constructor = engineType.getDeclaredConstructor(Context.class, Consumer.class, boolean.class); constructor.setAccessible(true);
        return constructor.newInstance(context, (Consumer<String>)ignored -> {}, false);
    }
    @Test public void changedHostKeyDoesNotBlockRootConnectionOrChangeCatalogIdentity() throws Exception {
        assertFalse("Pause automatic mode before isolated checks", prefs.getBoolean("soundAutoApply", false));
        String known = prefs.getString("sshKnown", null), verified = prefs.getString("sshVerifiedHost", null);
        boolean changed = prefs.getBoolean("sshKeyChanged", false);
        String identity = (String)call(engineType, null, "identity", new Class<?>[]{Context.class}, context);
        Constructor<?> constructor = sshType.getDeclaredConstructor(Context.class); constructor.setAccessible(true);
        try {
            String host = (String)call(sshType, null, "host", new Class<?>[]{Context.class}, context);
            // Deliberately stale/invalid public key: no private credential is involved.
            prefs.edit().putString("sshKnown", host + " ssh-rsa stale-key-fixture").putBoolean("sshKeyChanged", true).commit();
            Object ssh = constructor.newInstance(context);
            try {
                call(sshType, ssh, "connect", new Class<?>[]{String.class}, "");
                assertEquals("0", ((String)call(sshType, ssh, "text", new Class<?>[]{String.class,int.class}, "id -u", 10)).trim());
            } finally {call(sshType, ssh, "close", new Class<?>[]{});}
            assertEquals(identity, call(engineType, null, "identity", new Class<?>[]{Context.class}, context));
            assertEquals(host, prefs.getString("sshVerifiedHost", "")); assertFalse(prefs.getBoolean("sshKeyChanged", true));
        } finally {
            SharedPreferences.Editor edit = prefs.edit().putBoolean("sshKeyChanged", changed);
            if (known == null) edit.remove("sshKnown"); else edit.putString("sshKnown", known);
            if (verified == null) edit.remove("sshVerifiedHost"); else edit.putString("sshVerifiedHost", verified);
            edit.commit();
        }
    }
    @Test public void readinessMigratesLegacyCatalogAndIgnoresUnavailableGearWithoutApplying() throws Exception {
        assertFalse(prefs.getBoolean("soundAutoApply", false));
        File rules = new File(context.getFilesDir(), "instrument-sounds/rules.json");
        String before = FilesEx.digest(rules);
        try {
            prefs.edit().putBoolean("soundAutoApply", true).commit();
            Object needed = call(engineType, engine(), "prepareAutomatic", new Class<?>[]{});
            System.out.println("READ_ONLY_READINESS_PASS; changesNeeded=" + needed);
            assertEquals(before, FilesEx.digest(rules));
            assertTrue(FilesEx.readText(new File(context.getFilesDir(), "instrument-sounds/snapshot.json")).contains(
                (String)call(engineType, null, "identity", new Class<?>[]{Context.class}, context)));
        } finally {prefs.edit().putBoolean("soundAutoApply", false).commit();}
    }
    @Test public void restoreExistingSoundsThenAutomaticallyRecoverConfiguredRules() throws Exception {
        assertFalse("Pause automatic mode before this recovery test", prefs.getBoolean("soundAutoApply", false));
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("applyExistingRules")));
        com.kooo.evdashboard.core.SoundStore store;
        try (java.io.InputStream input = context.getAssets().open("sounds/catalog.json")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); FilesEx.transfer(input, out);
            store = new com.kooo.evdashboard.core.SoundStore(new File(context.getFilesDir(), "instrument-sounds"),
                new com.kooo.evdashboard.core.SoundCatalog(out.toString("UTF-8")));
        }
        Map<String, com.kooo.evdashboard.core.SoundStore.Rule> rules = store.rules();
        assertFalse("Existing user configuration required", rules.isEmpty());
        try {
            store.rules(Map.of());
            call(engineType, engine(), "apply", new Class<?>[]{String.class,boolean.class,Set.class}, "", false, rules.keySet());
        } finally {store.rules(rules);}
        boolean needs = false;
        try {
            prefs.edit().putBoolean("soundAutoApply", true).commit();
            needs = (Boolean)call(engineType, engine(), "prepareAutomatic", new Class<?>[]{});
        } finally {prefs.edit().putBoolean("soundAutoApply", false).commit();}
        assertTrue("Restored originals must need configured overlays", needs);
        System.out.println("ORIGINALS_RESTORED; automatic recovery required for " + rules.size() + " configured sounds");
        enableExistingRulesAndRecoverFromWake();
        assertFalse((Boolean)call(engineType, engine(), "prepareAutomatic", new Class<?>[]{}));
    }
    @Test public void enableExistingRulesAndRecoverFromWake() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("applyExistingRules")));
        prefs.edit().putBoolean("soundAutoApply", true).remove("startupBlockedUntil").commit();
        context.sendBroadcast(new Intent("com.ecarx.intent.action.STR_RESUME").setComponent(new ComponentName(context, BootReceiver.class)));
        long deadline = SystemClock.elapsedRealtime() + 75_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            String status = prefs.getString("startupStatus", "");
            if (status.startsWith("声音配置已恢复") || status.startsWith("当前声音已符合配置")) {
                System.out.println("LIVE_RECOVERY_PASS: " + status); assertTrue(prefs.getBoolean("soundAutoApply", false)); return;
            }
            Thread.sleep(250);
        }
        fail("Recovery did not complete: " + prefs.getString("startupStatus", ""));
    }
}

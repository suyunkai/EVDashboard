package com.kooo.evdashboard;

import android.app.ActivityManager;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.kooo.evdashboard.core.StartupRecovery;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Broadcast/foreground-service checks only. Missing SSH trust prevents root/CP access. */
@RunWith(AndroidJUnit4.class)
public class StartupLifecycleTest {
    private Context context;
    private SharedPreferences prefs;
    private Map<String, ?> before;
    private final Set<String> changed = Set.of("soundAutoApply", "sshKnown", "startupStatus", "startupBoot", "startupBlockedUntil");
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("settings", 0);
        assertFalse("Disable automatic application before this no-write test", prefs.getBoolean("soundAutoApply", false));
        assertFalse(busy());
        before = prefs.getAll(); stopMonitor();
        prefs.edit().remove("sshKnown").remove("startupStatus").remove("startupBoot").remove("startupBlockedUntil").commit();
    }
    @After public void cleanup() throws Exception {
        if (before == null) return;
        prefs.edit().putBoolean("soundAutoApply", false).commit(); stopMonitor();
        long deadline = SystemClock.elapsedRealtime() + 4000;
        while (running() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : changed) {
            Object value = before.get(key);
            if (value == null) edit.remove(key);
            else if (value instanceof Boolean v) edit.putBoolean(key, v);
            else if (value instanceof Integer v) edit.putInt(key, v);
            else if (value instanceof Long v) edit.putLong(key, v);
            else edit.putString(key, (String) value);
        }
        edit.commit();
    }
    @SuppressWarnings("deprecation") private boolean running() {
        for (ActivityManager.RunningServiceInfo info : context.getSystemService(ActivityManager.class).getRunningServices(100))
            if (info.service.getClassName().equals(AutoApplyService.class.getName()) && info.foreground) return true;
        return false;
    }
    private void stopMonitor() {context.stopService(new Intent(context, AutoApplyService.class));}
    private boolean busy() throws Exception {
        Object app = context.getApplicationContext();
        java.lang.reflect.Field field = app.getClass().getDeclaredField("busy"); field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicBoolean) field.get(app)).get();
    }
    @Test public void manifestRegistersBootAndWakeButDoesNotExportTheWorker() throws Exception {
        PackageManager pm = context.getPackageManager();
        ActivityInfo receiver = pm.getReceiverInfo(new ComponentName(context, BootReceiver.class), 0);
        assertTrue(receiver.exported); assertTrue(receiver.directBootAware);
        ServiceInfo service = pm.getServiceInfo(new ComponentName(context, AutoApplyService.class), 0);
        assertFalse(service.exported);
        assertEquals(PackageManager.PERMISSION_GRANTED, pm.checkPermission("android.permission.RECEIVE_BOOT_COMPLETED", context.getPackageName()));
        for (String action : List.of(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, StartupRecovery.STR, "com.ecarx.intent.action.STR_RESUME")) {
            List<ResolveInfo> receivers = pm.queryBroadcastReceivers(new Intent(action).setPackage(context.getPackageName()), 0);
            assertTrue(action, receivers.stream().anyMatch(r -> r.activityInfo.name.equals(BootReceiver.class.getName())));
        }
    }
    @Test public void disabledAutomaticModeIgnoresWakeAndMalformedBroadcasts() throws Exception {
        BootReceiver receiver = new BootReceiver();
        receiver.onReceive(context, null); receiver.onReceive(context, new Intent());
        receiver.onReceive(context, new Intent("unrelated.action"));
        context.sendBroadcast(new Intent(StartupRecovery.STR).setComponent(new ComponentName(context, BootReceiver.class)));
        Thread.sleep(800); assertFalse(running()); assertFalse(busy());
    }
    @Test public void wakeStartsForegroundMonitoringWithoutUiOrCpTrust() throws Exception {
        prefs.edit().putBoolean("soundAutoApply", true).commit();
        for (int i = 0; i < 3; i++) context.sendBroadcast(new Intent(StartupRecovery.STR).setComponent(new ComponentName(context, BootReceiver.class)));
        long deadline = SystemClock.elapsedRealtime() + 7000;
        while ((!running() || !prefs.getString("startupStatus", "").startsWith("等待自动恢复条件：")) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        assertTrue(running());
        String status = prefs.getString("startupStatus", "");
        assertTrue(status, status.startsWith("等待自动恢复条件："));
        assertEquals("", prefs.getString("sshKnown", ""));
        assertFalse(busy());
        assertTrue(prefs.getBoolean("soundAutoApply", false));
    }
    @Test public void restartWithoutAWakeReasonDoesNotReplayAnEarlierAttempt() throws Exception {
        prefs.edit().putBoolean("soundAutoApply", true).putString("startupStatus", "previous-result").commit();
        context.startForegroundService(new Intent(context, AutoApplyService.class));
        Thread.sleep(2800);
        assertTrue(running()); assertEquals("previous-result", prefs.getString("startupStatus", ""));
        assertFalse(busy());
    }
}

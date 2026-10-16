package com.kooo.evdashboard.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class StartupRecoveryTest {
    @Test public void bootAndWakeEventsAreAllowlistedWithoutGenericBroadcastCommands() {
        assertTrue(StartupRecovery.EVENTS.contains("android.intent.action.BOOT_COMPLETED"));
        assertTrue(StartupRecovery.EVENTS.contains("android.intent.action.USER_UNLOCKED"));
        assertTrue(StartupRecovery.EVENTS.contains("android.intent.action.MY_PACKAGE_REPLACED"));
        assertTrue(StartupRecovery.EVENTS.contains("com.ecarx.intent.action.STR_RESUME"));
        assertTrue(StartupRecovery.EVENTS.contains(StartupRecovery.STR));
        assertFalse(StartupRecovery.EVENTS.contains("android.intent.action.SCREEN_OFF"));
        assertFalse(StartupRecovery.EVENTS.contains("com.example.APPLY"));
    }
    @Test public void broadcastBurstsMergeIntoOneSettledAttempt() {
        StartupRecovery gate = new StartupRecovery(0);
        assertTrue(gate.request(0)); long token = gate.generation();
        assertFalse(gate.request(500)); assertFalse(gate.request(1999));
        assertFalse(gate.due(1999)); assertTrue(gate.due(2000));
        assertTrue(gate.beginApply(token, 2000));
        assertFalse(gate.request(40_000));
        assertFalse(gate.beginApply(token, 50_000));
    }
    @Test public void readinessBackoffIsBoundedAndNeverExtendsTheWindow() {
        StartupRecovery gate = new StartupRecovery(0); gate.request(0);
        long now = 2000;
        for (int i = 0; i < 100 && !gate.expired(now); i++) {
            assertTrue(gate.due(now)); gate.defer(now);
            assertTrue(gate.delay(now) > 0 && gate.delay(now) <= 15_000);
            now += gate.delay(now);
        }
        assertEquals(StartupRecovery.WINDOW_MS, now);
        assertEquals(0, gate.remaining(now));
        assertTrue(gate.expired(now)); assertFalse(gate.due(now));
        assertFalse(gate.beginApply(gate.generation(), now));
    }
    @Test public void completionAlsoThrottlesDelayedDuplicateEvents() {
        StartupRecovery gate = new StartupRecovery(0); gate.request(0);
        assertTrue(gate.beginApply(gate.generation(), 2000)); gate.finish(60_000);
        assertFalse(gate.request(89_999)); assertTrue(gate.request(90_000));
    }
    @Test public void failedApplyIsNeverRetriedWithoutAFreshEvent() {
        StartupRecovery gate = new StartupRecovery(0); gate.request(0);
        assertTrue(gate.beginApply(gate.generation(), 2000)); gate.finish(5000);
        gate.defer(100_000);
        assertFalse(gate.waiting()); assertFalse(gate.due(200_000));
        assertTrue(gate.request(200_000));
    }
    @Test public void sleepInvalidatesAnInflightReadinessResult() {
        StartupRecovery gate = new StartupRecovery(0); gate.request(0);
        long old = gate.generation(); gate.cancelWaiting();
        assertFalse(gate.current(old)); assertTrue(gate.request(10_000));
        assertFalse(gate.beginApply(old, 12_000));
        assertTrue(gate.beginApply(gate.generation(), 12_000));
        // Once submitted, a sleep notification must not pretend the apply stopped.
        gate.cancelWaiting(); assertFalse(gate.request(60_000));
    }
    @Test public void persistedCooldownSurvivesServiceRecreation() {
        StartupRecovery gate = new StartupRecovery(30_000);
        assertFalse(gate.request(29_999)); assertTrue(gate.request(30_000));
    }
    @Test public void monitorCanCheckAgainAfterCpOutlivesInitialReadinessWindow() {
        StartupRecovery gate = new StartupRecovery(0); assertTrue(gate.request(0));
        assertTrue(gate.expired(180_000)); gate.finish(180_000);
        assertFalse(gate.request(209_999)); assertTrue(gate.request(240_000));
        assertTrue(gate.beginApply(gate.generation(), 242_000));
    }
    @Test public void serviceRestartAndUserWakeAreChecksRatherThanCommands() {
        for (String reason : new String[]{"SERVICE_RESTART", "MONITOR", "WATCHDOG", "android.intent.action.USER_FOREGROUND", "android.intent.action.USER_PRESENT", "android.intent.action.ACTION_POWER_CONNECTED"})
            assertTrue(reason, StartupRecovery.accepts(reason));
        assertFalse(StartupRecovery.accepts(null)); assertFalse(StartupRecovery.accepts("apply; reboot"));
    }
}

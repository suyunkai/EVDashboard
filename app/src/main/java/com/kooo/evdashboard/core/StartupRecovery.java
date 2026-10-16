package com.kooo.evdashboard.core;

import java.util.Set;

/** One bounded readiness window per wake burst; applying is never retried by this gate. */
public final class StartupRecovery {
    public static final long WINDOW_MS = 180_000, COOLDOWN_MS = 30_000, SETTLE_MS = 2_000;
    public static final String STR = "ecarx.intent.action.power.STRMODE";
    public static final Set<String> EVENTS = Set.of(
        "android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED",
        "android.intent.action.USER_UNLOCKED", "android.intent.action.MY_PACKAGE_REPLACED",
        "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON",
        "com.ecarx.intent.action.SYSTEM_READY", "com.ecarx.systemui.SYSTEM_UI_READY",
        "com.ecarx.intent.action.ACC_ON", "com.ecarx.intent.action.IGN_ON",
        "com.ecarx.intent.action.POWER_ON", "com.ecarx.intent.action.WAKEUP",
        "com.ecarx.intent.action.STR_RESUME", STR, "android.intent.action.SCREEN_ON");
    private long generation, deadline, next, blockedUntil;
    private int retries;
    private boolean waiting, applying;

    public StartupRecovery(long blockedUntil) {this.blockedUntil = Math.max(0, blockedUntil);}
    public synchronized boolean request(long now) {
        if (waiting || applying || now < blockedUntil) return false;
        generation++; waiting = true; retries = 0; deadline = now + WINDOW_MS; next = now + SETTLE_MS;
        return true;
    }
    public synchronized long generation() {return generation;}
    public synchronized boolean current(long token) {return generation == token && waiting;}
    public synchronized boolean waiting() {return waiting;}
    public synchronized boolean expired(long now) {return waiting && now >= deadline;}
    public synchronized boolean due(long now) {return waiting && now >= next && now < deadline;}
    public synchronized long delay(long now) {return Math.max(0, Math.min(next, deadline) - now);}
    public synchronized long remaining(long now) {return Math.max(0, deadline - now);}
    public synchronized void defer(long now) {
        if (waiting) next = Math.min(deadline, now + Math.min(15_000, 2_000L << Math.min(retries++, 3)));
    }
    public synchronized boolean beginApply(long token, long now) {
        if (!current(token) || !due(now)) return false;
        waiting = false; applying = true; return true;
    }
    public synchronized void finish(long now) {
        waiting = applying = false; blockedUntil = now + COOLDOWN_MS;
    }
    public synchronized void cancelWaiting() {if (waiting) {waiting = false; generation++;}}
}

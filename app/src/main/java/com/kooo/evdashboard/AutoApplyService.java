package com.kooo.evdashboard;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import com.kooo.evdashboard.core.StartupRecovery;
import java.util.concurrent.*;

/** Opt-in foreground monitor; no Activity launch and no wake lock while idle. */
public final class AutoApplyService extends Service {
    private static final String CHANNEL = "sound-recovery", REASON = "reason";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Future<?> checkTask;
    private final Runnable tick = this::check, pulse = this::poll;
    private AppState state;
    private SharedPreferences prefs;
    private StartupRecovery recovery;
    private boolean checking;
    private volatile boolean destroyed;
    private String lastIssue = "", message = "等待开机或唤醒事件";
    private int bootCount;
    private final SharedPreferences.OnSharedPreferenceChangeListener settings = (p, key) -> {
        if ("soundAutoApply".equals(key)) handler.post(() -> {
            if (!destroyed && !enabled(this)) {status("自动恢复已关闭"); RecoveryJobService.cancel(this); stopSelf();}
        });
    };
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                recovery.cancelWaiting(); handler.removeCallbacks(tick); status("已休眠，等待下次唤醒");
            } else if (intent.getAction() != null && StartupRecovery.EVENTS.contains(intent.getAction())) request(intent.getAction());
        }
    };
    static boolean enabled(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        return users != null && users.isUserUnlocked() && context.getSharedPreferences("settings", 0).getBoolean("soundAutoApply", false);
    }
    static void start(Context context, String reason) {
        if (!enabled(context)) return;
        RecoveryJobService.schedule(context);
        try {context.startForegroundService(new Intent(context, AutoApplyService.class).putExtra(REASON, reason));}
        catch (RuntimeException error) {
            Log.w("EVDashboard", "Background sound recovery could not start", error);
            context.getSharedPreferences("settings", 0).edit().putString("startupStatus", "后台启动被系统限制，请打开应用并允许后台运行").apply();
        }
    }
    static void stop(Context context) {RecoveryJobService.cancel(context); context.stopService(new Intent(context, AutoApplyService.class));}
    @Override public void onCreate() {
        super.onCreate(); state = (AppState) getApplication(); prefs = getSharedPreferences("settings", 0);
        bootCount = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        long until = bootCount >= 0 && prefs.getInt("startupBoot", -2) == bootCount ? prefs.getLong("startupBlockedUntil", 0) : 0;
        recovery = new StartupRecovery(until);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, I18n.text(this, "声音自动恢复"), NotificationManager.IMPORTANCE_LOW));
        startForeground(2, notification());
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        for (String action : StartupRecovery.EVENTS) filter.addAction(action);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, filter, Context.RECEIVER_EXPORTED);
        else registerWakeReceiver(filter);
        prefs.registerOnSharedPreferenceChangeListener(settings);
        handler.postDelayed(pulse, 60_000);
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerWakeReceiver(IntentFilter filter) {registerReceiver(events, filter);}
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enabled(this)) {stopSelf(); return START_NOT_STICKY;}
        RecoveryJobService.schedule(this);
        String reason = intent == null ? null : intent.getStringExtra(REASON);
        request(reason == null ? "SERVICE_RESTART" : reason);
        return START_STICKY;
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_workshop)
            .setContentTitle(I18n.text(this, "声音自动恢复已启用")).setContentText(I18n.text(this, message))
            .setStyle(new Notification.BigTextStyle().bigText(I18n.text(this, message)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build();
    }
    private void status(String text) {
        if (destroyed) return;
        message = text;
        try {
            java.io.File log = new java.io.File(getFilesDir(), "startup.log");
            String history = log.isFile() ? com.kooo.evdashboard.core.FilesEx.readText(log) : "";
            if (history.length() > 32_000) history = history.substring(history.length() - 24_000);
            com.kooo.evdashboard.core.FilesEx.atomicText(log, history + new java.util.Date() + "  " + text + "\n");
        } catch (Exception ignored) {}
        prefs.edit().putString("startupStatus", text).apply();
        getSystemService(NotificationManager.class).notify(2, notification());
        sendBroadcast(new Intent("com.kooo.evdashboard.STATUS").setPackage(getPackageName()));
    }
    private void request(String reason) {
        if (!enabled(this)) {stopSelf(); return;}
        if (!StartupRecovery.accepts(reason)) return;
        if (recovery.request(SystemClock.elapsedRealtime())) {
            prefs.edit().putString("startupReason", reason).apply();
            lastIssue = ""; status("等待车机与 CP 就绪，准备恢复声音配置"); schedule();
        }
    }
    private void poll() {
        if (destroyed || !enabled(this)) {stopSelf(); return;}
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && power.isInteractive()) request("MONITOR");
        handler.postDelayed(pulse, 60_000);
    }
    private void schedule() {
        handler.removeCallbacks(tick);
        if (!destroyed && recovery.waiting()) handler.postDelayed(tick, recovery.delay(SystemClock.elapsedRealtime()));
    }
    private void finish(String text) {
        long now = SystemClock.elapsedRealtime(); recovery.finish(now);
        prefs.edit().putInt("startupBoot", bootCount).putLong("startupBlockedUntil", now + StartupRecovery.COOLDOWN_MS).apply();
        status(text);
    }
    private void check() {
        long now = SystemClock.elapsedRealtime();
        if (!enabled(this)) {stopSelf(); return;}
        if (recovery.expired(now)) {finish("CP 暂未就绪，稍后继续检查：" + lastIssue); return;}
        if (!recovery.due(now)) {schedule(); return;}
        if (checking || state.busy.get()) {lastIssue = "等待当前任务完成"; recovery.defer(now); schedule(); return;}
        checking = true; long token = recovery.generation();
        handler.postDelayed(tick, recovery.remaining(now));
        checkTask = state.executor.submit(() -> {
            Exception failure = null; boolean needed = false;
            AudioEngine.Prepared prepared = null;
            PowerManager.WakeLock wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EVDashboard:recovery-check");
            try {
                if (destroyed || !recovery.current(token)) throw new java.io.IOException("恢复检查已取消");
                wake.acquire(45_000);
                AudioEngine engine = new AudioEngine(this, ignored -> {}, false);
                needed = engine.prepareAutomatic(); prepared = engine.prepared();
            }
            catch (Exception error) {failure = error;}
            finally {if (wake.isHeld()) wake.release();}
            final Exception result = failure; final boolean changes = needed;
            final AudioEngine.Prepared checked = prepared;
            handler.post(() -> ready(token, result, changes, checked));
        });
    }
    private void ready(long token, Exception error, boolean needed, AudioEngine.Prepared prepared) {
        checking = false;
        if (destroyed || !enabled(this) || !recovery.current(token)) return;
        handler.removeCallbacks(tick);
        long now = SystemClock.elapsedRealtime();
        if (recovery.expired(now)) {finish("CP 暂未就绪，稍后继续检查：" + (error == null ? lastIssue : error.getMessage())); return;}
        if (error != null || state.busy.get()) {
            lastIssue = error == null ? "等待当前任务完成" : String.valueOf(error.getMessage());
            status("等待自动恢复条件：" + lastIssue); recovery.defer(now); schedule(); return;
        }
        if (!needed) {finish("当前声音已符合配置，继续监听与定期检查"); return;}
        if (!recovery.beginApply(token, now)) {schedule(); return;}
        status("正在恢复声音配置");
        try {
            state.submit("自动恢复仪表提示音", () -> {
                boolean success = false;
                try {new AudioEngine(this, state::report).apply("", true, null, prepared); success = true;}
                finally {
                    boolean done = success;
                    handler.post(() -> {if (!destroyed) finish(done ? "声音配置已恢复，继续监听唤醒" : "自动恢复未完成，请查看任务记录；继续只读核对结果");});
                }
            });
        } catch (Exception failure) {finish("自动恢复未开始：" + failure.getMessage());}
    }
    @Override public void onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null); if (checkTask != null) checkTask.cancel(true);
        prefs.unregisterOnSharedPreferenceChangeListener(settings); unregisterReceiver(events);
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {return null;}
}

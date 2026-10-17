package com.kooo.evdashboard;

import android.app.job.*;
import android.content.*;
import android.os.PowerManager;

/** Persisted best-effort fallback when the monitor process or a wake broadcast is lost. */
public final class RecoveryJobService extends JobService {
    static final int JOB_ID = 4201;
    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || !AutoApplyService.enabled(context)) return;
        try {
            if (scheduler.getPendingJob(JOB_ID) == null)
                scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, RecoveryJobService.class))
                    .setPersisted(true).setPeriodic(15 * 60_000L).build());
        } catch (RuntimeException error) {android.util.Log.w("EVDashboard", "Recovery watchdog unavailable", error);}
    }
    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        if (!AutoApplyService.enabled(this)) {cancel(this); return false;}
        if (AutoApplyService.running()) return false;
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && power.isInteractive()) AutoApplyService.start(this, "WATCHDOG");
        return false;
    }
    @Override public boolean onStopJob(JobParameters parameters) {return false;}
}

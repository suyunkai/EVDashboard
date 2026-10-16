package com.kooo.evdashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import com.kooo.evdashboard.core.StartupRecovery;

/** Broadcasts are wake hints, never authorization or vehicle-state evidence. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null || !StartupRecovery.EVENTS.contains(intent.getAction())) return;
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()) return;
        AutoApplyService.start(context, intent.getAction());
    }
}

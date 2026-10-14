package com.kooo.evdashboard;

import android.content.*;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())||!context.getSharedPreferences("settings",0).getBoolean("reapply",false))return;
        AppState state=(AppState)context.getApplicationContext();try{state.submit("恢复已保存音效",()->{new AudioEngine(context,state::report).reapplySaved(true);state.report("已恢复保存的音效覆盖；未操作镜像或重启。实际声音需现场确认。");});}catch(Exception e){state.report("开机音效恢复未开始："+e.getMessage());}}
}

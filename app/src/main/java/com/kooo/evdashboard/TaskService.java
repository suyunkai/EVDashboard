package com.kooo.evdashboard;

import android.app.*;
import android.content.*;
import android.os.*;

public final class TaskService extends Service {
    private PowerManager.WakeLock wake;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable ticker=new Runnable(){public void run(){
        AppState state=(AppState)getApplication();
        if(state.busy.get())getSystemService(NotificationManager.class).notify(1,notification());
        handler.postDelayed(this,1000);
    }};
    private Notification notification(){
        AppState state=(AppState)getApplication();
        com.kooo.evdashboard.core.TaskProgress.Snapshot p=state.progress.snapshot();
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"maintenance").setSmallIcon(R.drawable.ic_stat_workshop)
            .setContentTitle(I18n.text(this,state.title)).setContentText(I18n.text(this,p.summary()))
            .setStyle(new Notification.BigTextStyle().bigText(I18n.text(this,p.stage()+"\n"+state.status+"\n"+p.summary())))
            .setProgress(100,Math.max(0,p.percent()),p.percent()<0).setOnlyAlertOnce(true)
            .setContentIntent(open).setOngoing(true).build();
    }
    @Override public void onCreate(){
        super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("maintenance",I18n.text(this,"本机维护任务"),NotificationManager.IMPORTANCE_LOW));
        startForeground(1,notification());handler.post(ticker);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"EVDashboard:maintenance");wake.acquire(30*60*1000L);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        AppState state=(AppState)getApplication();AppState.Job job=state.pending;state.pending=null;
        if(job==null){if(!state.busy.get())stopSelfResult(startId);return START_NOT_STICKY;}
        state.executor.execute(()->{
            boolean success=false;
            try{job.run();success=true;}catch(Exception e){state.failed=true;state.report("操作未完成："+e.getMessage());}
            finally{state.progress.finish(success);handler.post(()->{handler.removeCallbacks(ticker);stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);state.busy.set(false);state.sendBroadcast(new Intent("com.kooo.evdashboard.STATUS").setPackage(getPackageName()));});}
        });return START_NOT_STICKY;
    }
    @Override public void onDestroy(){handler.removeCallbacks(ticker);if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}

package com.kooo.evdashboard;

import android.app.*;
import android.content.*;
import android.os.*;

public final class TaskService extends Service {
    private PowerManager.WakeLock wake;
    @Override public void onCreate(){super.onCreate();NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("maintenance","本机维护任务",NotificationManager.IMPORTANCE_LOW));PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);startForeground(1,new Notification.Builder(this,"maintenance").setSmallIcon(R.drawable.ic_workshop).setContentTitle("仪表工坊任务进行中").setContentText("请保持车辆驻车与稳定供电，完成前不要强行停止应用").setContentIntent(open).setOngoing(true).build());wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"EVDashboard:maintenance");wake.acquire(30*60*1000L);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){AppState state=(AppState)getApplication();AppState.Job job=state.pending;state.pending=null;if(job==null){if(!state.busy.get())stopSelfResult(startId);return START_NOT_STICKY;}state.executor.execute(()->{try{job.run();}catch(Exception e){state.failed=true;state.report("任务未完成："+e.getMessage()+"\n不会自动重试写入或重启。备份和任务文件保留。");}finally{new Handler(Looper.getMainLooper()).post(()->{stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);state.busy.set(false);state.sendBroadcast(new Intent("com.kooo.evdashboard.STATUS").setPackage(getPackageName()));});}});return START_NOT_STICKY;}
    @Override public void onDestroy(){if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}

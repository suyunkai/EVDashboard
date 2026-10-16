package com.kooo.evdashboard;

import android.app.Application;
import android.content.*;
import android.os.Environment;
import com.kooo.evdashboard.core.*;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AppState extends Application {
    interface Job {void run()throws Exception;}
    final ExecutorService executor=Executors.newSingleThreadExecutor();final AtomicBoolean busy=new AtomicBoolean();
    final TaskProgress progress=new TaskProgress();
    private final AtomicBoolean audioStartup = new AtomicBoolean();
    volatile Job pending;volatile String title="就绪",status="所有镜像处理均在本机完成。未识别设备前不会读取或写入分区。";
    volatile String selected="";volatile boolean failed;volatile ImageLibrary library;volatile WorkshopEngine workshop;
    @Override public void onCreate(){
        super.onCreate();
        android.os.UserManager users = getSystemService(android.os.UserManager.class);
        if (users != null && users.isUserUnlocked()) new android.os.Handler(getMainLooper()).post(this::startupAudio);
    }
    void startupAudio() {
        if (!audioStartup.compareAndSet(false, true) || !getSharedPreferences("settings", 0).getBoolean("soundAutoApply", false)) return;
        AutoApplyService.start(this, "APP_START");
    }
    synchronized void storage()throws Exception{FilesEx.require(Environment.isExternalStorageManager(),"请前往 EVCC 的本应用权限页授予所有文件访问权限，返回后自动检查");if(library==null){library=new ImageLibrary(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),"EVDashboard"));workshop=new WorkshopEngine(this,library,this::report,progress);}}
    synchronized void report(String message){status=message;
        try {File log=new File(getFilesDir(),"last-task.log");String history=log.isFile()?FilesEx.readText(log):"";if(history.length()>128000)history=history.substring(history.length()-96000);FilesEx.atomicText(log,history+new java.util.Date()+"  "+title+"\n"+message+"\n\n");}catch(Exception ignored){}
        sendBroadcast(new Intent("com.kooo.evdashboard.STATUS").setPackage(getPackageName()));}
    void submit(String label,Job job)throws Exception{
        FilesEx.require(busy.compareAndSet(false,true),"已有任务运行，请等待完成");title=label;failed=false;pending=job;progress.start(label);report(label+"…");
        try{startForegroundService(new Intent(this,TaskService.class));}catch(Exception e){pending=null;failed=true;progress.finish(false);busy.set(false);throw e;}
    }
}

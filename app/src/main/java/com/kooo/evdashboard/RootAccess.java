package com.kooo.evdashboard;

import android.content.Context;
import java.io.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;

public final class RootAccess {
    private final LocalAdb adb;private String method="";
    public RootAccess(Context context){adb=new LocalAdb(context);}
    private static String process(String[] args,int seconds)throws Exception{
        Process p=new ProcessBuilder(args).redirectErrorStream(true).start();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Thread drain=new Thread(()->{try(InputStream in=p.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()<4*1024*1024)bytes.write(b,0,n);}}catch(IOException ignored){}});drain.start();
        if(!p.waitFor(seconds,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("命令超时；不会自动重试写入");}drain.join(3000);String text=new String(bytes.toByteArray(),StandardCharsets.UTF_8);if(p.exitValue()!=0)throw new IOException("命令退出 "+p.exitValue()+": "+text);return text.trim();
    }
    public String connect()throws Exception{
        try{if(process(new String[]{"su","-c","id -u"},10).equals("0")){method="su";return "独立 su / UID 0";}}catch(Exception ignored){}
        try{if(adb.run("id -u",12).trim().equals("0")){method="adb";return "本机 ADB / UID 0";}}catch(Exception e){throw new IOException("尚无 root 权限。请授权 su，或开启本机 5555 ADB 并确认授权。"+e.getMessage());}
        throw new IOException("本机 ADB 不是 UID 0；不能读取或刷写分区");
    }
    public String run(String script,int timeout)throws Exception{if(method.isEmpty())connect();return method.equals("su")?process(new String[]{"su","-c",script},timeout):adb.run(script,timeout);}
    public void copyReadOnly(String source,File destination,long expected)throws Exception{
        if(method.isEmpty())connect();com.kooo.evdashboard.core.FilesEx.require(expected>0&&destination.isFile()&&destination.length()==0,"复制目标必须是本任务新建的空文件");
        try(FileOutputStream out=new FileOutputStream(destination)){
            if(method.equals("adb"))adb.copyReadOnly(source,out,expected,300);
            else {
                Process p=new ProcessBuilder("su","-c","cat "+com.kooo.evdashboard.core.FilesEx.quote(source)).redirectErrorStream(true).start();ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();ScheduledFuture<?> deadline=timer.schedule(()->{p.destroyForcibly();},300,TimeUnit.SECONDS);
                try(InputStream in=p.getInputStream()){byte[] buffer=new byte[65536];long count=0;int n;while((n=in.read(buffer))!=-1){com.kooo.evdashboard.core.FilesEx.require(n<=expected-count,"复制数据超过预期长度");out.write(buffer,0,n);count+=n;}com.kooo.evdashboard.core.FilesEx.require(p.waitFor(10,TimeUnit.SECONDS)&&p.exitValue()==0&&count==expected,"复制进程失败或长度不完整");}
                finally{deadline.cancel(false);timer.shutdownNow();if(p.isAlive())p.destroyForcibly();}
            }
            out.getFD().sync();
        }
    }
    /** Android multi-user FUSE paths can be invisible to adbd's mount namespace. */
    public String sharedFile(File file)throws Exception {
        String path=file.getAbsolutePath(),hash=com.kooo.evdashboard.core.FilesEx.digest(file);
        java.util.List<String> candidates=new java.util.ArrayList<>();candidates.add(path);
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("^/storage/emulated/([0-9]+)/(.+)$").matcher(path);
        if(m.matches()&&!path.contains("/../"))candidates.add("/data/media/"+m.group(1)+"/"+m.group(2));
        StringBuilder command=new StringBuilder("set -e; for path in ");for(String p:candidates)command.append(com.kooo.evdashboard.core.FilesEx.quote(p)).append(' ');
        command.append("; do if [ -f \"$path\" ] && [ ! -L \"$path\" ] && [ \"$(sha256sum \"$path\" | cut -d ' ' -f1)\" = ").append(com.kooo.evdashboard.core.FilesEx.quote(hash)).append(" ]; then printf '%s' \"$path\"; exit 0; fi; done; echo 'App file is inaccessible from root namespace' >&2; exit 43");
        String result=run(command.toString(),180).trim();com.kooo.evdashboard.core.FilesEx.require(candidates.contains(result),"无法确认 root 与应用指向同一镜像文件");return result;
    }
    static String nativeRun(String executable,int timeout,String...args)throws Exception{String[] c=new String[args.length+1];c[0]=executable;System.arraycopy(args,0,c,1,args.length);return process(c,timeout);}
}

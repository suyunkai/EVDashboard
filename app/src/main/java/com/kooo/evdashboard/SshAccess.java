package com.kooo.evdashboard;

import android.content.Context;
import com.jcraft.jsch.*;
import com.kooo.evdashboard.core.FilesEx;
import com.kooo.evdashboard.core.BoundedBytes;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.security.MessageDigest;

final class SshAccess implements AutoCloseable {
    private final Context context;private Session session;
    SshAccess(Context c){context=c;}
    static String host(Context c){return c.getSharedPreferences("settings",0).getString("cpHost",com.kooo.evdashboard.core.CpAddress.DEFAULT);}
    static boolean trusted(Context c){android.content.SharedPreferences p=c.getSharedPreferences("settings",0);String known=p.getString("sshKnown","");return known.startsWith(host(c)+" ")&&!p.getBoolean("sshKeyChanged",false);}
    static final class KeyOffer {final String host,type,key,fingerprint;KeyOffer(String host,HostKey h)throws Exception{this.host=host;type=h.getType();key=h.getKey();fingerprint="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key)));}}
    static KeyOffer inspectKey(Context context)throws Exception{
        String host=com.kooo.evdashboard.core.CpAddress.parse(host(context));
        JSch ssh=new JSch();final HostKey[] observed={null};ssh.setHostKeyRepository(new HostKeyRepository(){public int check(String host,byte[] key){try{observed[0]=new HostKey(host,key);}catch(Exception ignored){}return NOT_INCLUDED;}public void add(HostKey h,UserInfo u){}public void remove(String h,String t){}public void remove(String h,String t,byte[] k){}public String getKnownHostsRepositoryID(){return "key-preview";}public HostKey[] getHostKey(){return new HostKey[0];}public HostKey[] getHostKey(String h,String t){return new HostKey[0];}});
        Session session=ssh.getSession("root",host,22);session.setConfig("StrictHostKeyChecking","yes");session.setTimeout(6000);
        try{session.connect(6000);}catch(JSchException e){if(observed[0]==null)throw e;}finally{session.disconnect();}FilesEx.require(observed[0]!=null,"未取得主机公钥");return new KeyOffer(host,observed[0]);
    }
    void connect(String password)throws Exception{
        String host=com.kooo.evdashboard.core.CpAddress.parse(host(context));String known=context.getSharedPreferences("settings",0).getString("sshKnown","");FilesEx.require(trusted(context),"请先在连接 CP 页检测并确认当前 SSH 主机指纹");JSch ssh=new JSch();ssh.setKnownHosts(new ByteArrayInputStream((known+"\n").getBytes(StandardCharsets.UTF_8)));session=ssh.getSession("root",host,22);session.setConfig("StrictHostKeyChecking","yes");session.setConfig("PreferredAuthentications","password,keyboard-interactive,none");session.setPassword(password==null?"":password);session.setTimeout(8000);session.setServerAliveInterval(5000);session.setServerAliveCountMax(2);
        try {session.connect(6000);} catch (JSchException e) {
            if (e.getMessage()!=null&&e.getMessage().contains("HostKey has been changed")) {
                context.getSharedPreferences("settings",0).edit().putBoolean("sshKeyChanged",true).putBoolean("soundAutoApply",false).apply();
                throw new IOException("CP SSH 指纹已变化，自动覆盖已关闭。请在连接 CP 页重新检测并确认指纹。",e);
            }
            throw e;
        }
        FilesEx.require(text("id -u",10).trim().equals("0"),"CP 登录用户不是 root");
    }
    byte[] command(String command,byte[] input,int seconds)throws Exception{
        ChannelExec channel=(ChannelExec)session.openChannel("exec");channel.setCommand(command);if(input!=null)channel.setInputStream(new ByteArrayInputStream(input));else channel.setInputStream(null);
        // Polling getInputStream().available() makes Android's PipedInputStream wait for
        // its buffer on every SSH packet. Large WAVs then hit the command deadline.
        BoundedBytes output=new BoundedBytes(16*1024*1024),errors=new BoundedBytes(65535);
        channel.setOutputStream(output);channel.setErrStream(errors);
        try{
            channel.connect(6000);long end=System.nanoTime()+seconds*1_000_000_000L;
            while(!channel.isClosed()){
                output.check();errors.check();FilesEx.require(System.nanoTime()<end,"SSH 操作超时");Thread.sleep(30);
            }
            output.check();errors.check();
            FilesEx.require(channel.getExitStatus()==0,"CP 命令失败："+new String(errors.bytes(),StandardCharsets.UTF_8));
            return output.bytes();
        }finally{channel.disconnect();}
    }
    String text(String command,int timeout)throws Exception{return new String(command(command,null,timeout),StandardCharsets.UTF_8);}
    void upload(String path,byte[] bytes)throws Exception{command("umask 077; test ! -e "+FilesEx.quote(path)+" && cat > "+FilesEx.quote(path),bytes,60);String actual=text("sha256sum "+FilesEx.quote(path),10).trim().split("\\s+")[0];FilesEx.require(actual.equals(FilesEx.hex(MessageDigest.getInstance("SHA-256").digest(bytes))),"音效上传摘要不匹配");}
    @Override public void close(){if(session!=null)session.disconnect();}
}

package com.kooo.evdashboard;

import android.content.Context;
import com.jcraft.jsch.*;
import com.kooo.evdashboard.core.FilesEx;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.security.MessageDigest;

final class SshAccess implements AutoCloseable {
    static final String HOST="198.18.44.2";private final Context context;private Session session;
    SshAccess(Context c){context=c;}
    static final class KeyOffer {final String type,key,fingerprint;KeyOffer(HostKey h)throws Exception{type=h.getType();key=h.getKey();fingerprint="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key)));}}
    static KeyOffer inspectKey(Context context)throws Exception{
        JSch ssh=new JSch();final HostKey[] observed={null};ssh.setHostKeyRepository(new HostKeyRepository(){public int check(String host,byte[] key){try{observed[0]=new HostKey(host,key);}catch(Exception ignored){}return NOT_INCLUDED;}public void add(HostKey h,UserInfo u){}public void remove(String h,String t){}public void remove(String h,String t,byte[] k){}public String getKnownHostsRepositoryID(){return "key-preview";}public HostKey[] getHostKey(){return new HostKey[0];}public HostKey[] getHostKey(String h,String t){return new HostKey[0];}});
        Session session=ssh.getSession("root",HOST,22);session.setConfig("StrictHostKeyChecking","yes");session.setTimeout(6000);
        try{session.connect(6000);}catch(JSchException e){if(observed[0]==null)throw e;}finally{session.disconnect();}FilesEx.require(observed[0]!=null,"未取得主机公钥");return new KeyOffer(observed[0]);
    }
    void connect(String password)throws Exception{
        String known=context.getSharedPreferences("settings",0).getString("sshKnown","");FilesEx.require(!known.isEmpty(),"请先检测并确认 CP 的 SSH 主机指纹");JSch ssh=new JSch();ssh.setKnownHosts(new ByteArrayInputStream((known+"\n").getBytes(StandardCharsets.UTF_8)));session=ssh.getSession("root",HOST,22);session.setConfig("StrictHostKeyChecking","yes");session.setConfig("PreferredAuthentications","password,keyboard-interactive,none");session.setPassword(password==null?"":password);session.setTimeout(8000);session.setServerAliveInterval(5000);session.setServerAliveCountMax(2);session.connect(6000);
        FilesEx.require(text("id -u",10).trim().equals("0"),"CP 登录用户不是 root");
    }
    byte[] command(String command,byte[] input,int seconds)throws Exception{
        ChannelExec channel=(ChannelExec)session.openChannel("exec");channel.setCommand(command);if(input!=null)channel.setInputStream(new ByteArrayInputStream(input));else channel.setInputStream(null);
        ByteArrayOutputStream output=new ByteArrayOutputStream(),errors=new ByteArrayOutputStream();channel.setErrStream(errors);InputStream in=channel.getInputStream();
        try{channel.connect(6000);long end=System.nanoTime()+seconds*1_000_000_000L;byte[] b=new byte[8192];while(true){while(in.available()>0){int n=in.read(b);if(n<0)break;output.write(b,0,n);FilesEx.require(output.size()<=16*1024*1024&&errors.size()<65536,"SSH 输出超过限制");}if(channel.isClosed()&&in.available()==0)break;FilesEx.require(System.nanoTime()<end,"SSH 操作超时");Thread.sleep(30);}FilesEx.require(channel.getExitStatus()==0,"CP 命令失败："+new String(errors.toByteArray(),StandardCharsets.UTF_8));return output.toByteArray();}finally{channel.disconnect();}
    }
    String text(String command,int timeout)throws Exception{return new String(command(command,null,timeout),StandardCharsets.UTF_8);}
    void upload(String path,byte[] bytes)throws Exception{command("umask 077; test ! -e "+FilesEx.quote(path)+" && cat > "+FilesEx.quote(path),bytes,60);String actual=text("sha256sum "+FilesEx.quote(path),10).trim().split("\\s+")[0];FilesEx.require(actual.equals(FilesEx.hex(MessageDigest.getInstance("SHA-256").digest(bytes))),"音效上传摘要不匹配");}
    @Override public void close(){if(session!=null)session.disconnect();}
}

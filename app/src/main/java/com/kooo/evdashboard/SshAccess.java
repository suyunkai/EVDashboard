package com.kooo.evdashboard;

import android.content.Context;
import com.jcraft.jsch.*;
import com.kooo.evdashboard.core.FilesEx;
import com.kooo.evdashboard.core.BoundedBytes;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class SshAccess implements AutoCloseable {
    private final Context context;private Session session;
    SshAccess(Context c){context=c;}
    static String host(Context c){return c.getSharedPreferences("settings",0).getString("cpHost",com.kooo.evdashboard.core.CpAddress.DEFAULT);}
    // CP recreates its host key on some boots. Remember the configured IP, not the key.
    static boolean trusted(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("settings", 0);
        return host(c).equals(p.getString("sshVerifiedHost", "")) || p.getString("sshKnown", "").startsWith(host(c) + " ");
    }
    void connect(String password) throws Exception {
        String host = com.kooo.evdashboard.core.CpAddress.parse(host(context));
        session = new JSch().getSession("root", host, 22);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive,none");
        session.setPassword(password == null ? "" : password); session.setTimeout(8000);
        session.setServerAliveInterval(5000); session.setServerAliveCountMax(2); session.connect(6000);
        FilesEx.require(text("id -u", 10).trim().equals("0"), "CP 登录用户不是 root");
        context.getSharedPreferences("settings", 0).edit().putString("sshVerifiedHost", host).putBoolean("sshKeyChanged", false).apply();
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

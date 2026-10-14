package com.kooo.evdashboard;

import android.content.Context;
import com.kooo.evdashboard.core.FilesEx;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.math.BigInteger;
import javax.crypto.Cipher;
import java.util.*;

/** Independent localhost ADB client. ADB authorization remains user-controlled. */
final class LocalAdb {
    private final Context context;
    LocalAdb(Context c){context=c;}
    private static int cmd(String s){return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt();}
    private record Packet(int command,int a,int b,byte[] data){}
    private void send(OutputStream out,String name,int a,int b,byte[] data)throws Exception{
        int sum=0;for(byte v:data)sum+=v&255;int command=cmd(name);
        ByteBuffer h=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);h.putInt(command).putInt(a).putInt(b).putInt(data.length).putInt(sum).putInt(command^(-1));out.write(h.array());out.write(data);out.flush();
    }
    private Packet receive(DataInputStream in)throws Exception{
        byte[] h=new byte[24];in.readFully(h);ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);int c=b.getInt(),a=b.getInt(),d=b.getInt(),n=b.getInt(),sum=b.getInt(),magic=b.getInt();
        if(n<0||n>1024*1024||magic!=(c^(-1)))throw new IOException("无效 ADB 帧");byte[] p=new byte[n];in.readFully(p);int actual=0;for(byte v:p)actual+=v&255;if(sum!=actual)throw new IOException("ADB 帧校验失败");return new Packet(c,a,d,p);
    }
    private KeyPair keys()throws Exception{
        File dir=new File(context.getFilesDir(),"local-adb");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法保存本机 ADB 身份");File priv=new File(dir,"private.pk8"),pub=new File(dir,"public.der");
        KeyFactory kf=KeyFactory.getInstance("RSA");
        if(priv.exists()&&pub.exists())return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(java.nio.file.Files.readAllBytes(pub.toPath()))),kf.generatePrivate(new PKCS8EncodedKeySpec(java.nio.file.Files.readAllBytes(priv.toPath()))));
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair kp=generator.generateKeyPair();
        try(FileOutputStream f=new FileOutputStream(priv)){f.write(kp.getPrivate().getEncoded());f.getFD().sync();}try(FileOutputStream f=new FileOutputStream(pub)){f.write(kp.getPublic().getEncoded());f.getFD().sync();}return kp;
    }
    private static void number(ByteBuffer out,BigInteger v,int count){for(int k=0;k<count;k++)out.put(v.shiftRight(k*8).byteValue());}
    private static byte[] adbPublic(KeyPair key){RSAPublicKey pub=(RSAPublicKey)key.getPublic();BigInteger n=pub.getModulus(),word=BigInteger.ONE.shiftLeft(32);ByteBuffer b=ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);b.putInt(64).putInt(n.modInverse(word).negate().intValue());number(b,n,256);number(b,BigInteger.ONE.shiftLeft(4096).mod(n),256);b.putInt(pub.getPublicExponent().intValue());return (Base64.getEncoder().encodeToString(b.array())+" instrument-workshop@localhost\0").getBytes(StandardCharsets.US_ASCII);}
    private void authenticate(DataInputStream in,OutputStream out)throws Exception{
        send(out,"CNXN",0x01000000,4096,"host::features=shell_v2\0".getBytes(StandardCharsets.UTF_8));KeyPair kp=null;int auth=0;
        while(true){Packet p=receive(in);if(p.command==cmd("CNXN"))return;if(p.command!=cmd("AUTH")||p.a!=1||p.data.length!=20)throw new IOException("ADB 握手失败");if(kp==null)kp=keys();if(auth++==0){byte[] prefix={0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};ByteArrayOutputStream digest=new ByteArrayOutputStream();digest.write(prefix);digest.write(p.data);Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.ENCRYPT_MODE,kp.getPrivate());send(out,"AUTH",2,0,cipher.doFinal(digest.toByteArray()));}else if(auth==2)send(out,"AUTH",3,0,adbPublic(kp));else throw new IOException("请在车机上确认仪表工坊的本机 ADB 授权");}
    }
    /** Raw local stream; length and independent source digest are checked by the caller. */
    void copyReadOnly(String source,OutputStream destination,long expected,int timeoutSeconds)throws Exception{
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1",5555),4000);socket.setSoTimeout(15000);
            DataInputStream in=new DataInputStream(socket.getInputStream());OutputStream out=socket.getOutputStream();authenticate(in,out);
            send(out,"OPEN",1,0,("exec:cat "+FilesEx.quote(source)+"\0").getBytes(StandardCharsets.UTF_8));long count=0,deadline=System.nanoTime()+timeoutSeconds*1_000_000_000L;
            while(true){FilesEx.require(System.nanoTime()<deadline,"本机复制超时");Packet p=receive(in);
                if(p.command==cmd("WRTE")){FilesEx.require(p.b==1&&p.data.length<=expected-count,"本机复制超过预期长度");destination.write(p.data);count+=p.data.length;send(out,"OKAY",1,p.a,new byte[0]);}
                else if(p.command==cmd("CLSE")){FilesEx.require(p.b==1&&count==expected,"本机复制长度不完整");send(out,"CLSE",1,p.a,new byte[0]);return;}
                else if(p.command!=cmd("OKAY"))throw new IOException("本机复制数据通道异常");
            }
        }
    }
    String run(String command,int timeoutSeconds)throws Exception{
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1",5555),4000);socket.setSoTimeout(timeoutSeconds*1000);
            DataInputStream in=new DataInputStream(socket.getInputStream());OutputStream out=socket.getOutputStream();authenticate(in,out);
            String marker="IW_EXIT_"+UUID.randomUUID().toString().replace("-","");String shell="shell:sh -c "+FilesEx.quote(command)+"; rc=$?; printf '\\n"+marker+"%s\\n' \"$rc\"";
            byte[] service=(shell+"\0").getBytes(StandardCharsets.UTF_8);if(service.length>4000)throw new IOException("ADB 命令过长");send(out,"OPEN",1,0,service);ByteArrayOutputStream response=new ByteArrayOutputStream();
            while(true){Packet p=receive(in);if(p.command==cmd("WRTE")){if(response.size()+p.data.length>4*1024*1024)throw new IOException("命令输出过大");response.write(p.data);send(out,"OKAY",1,p.a,new byte[0]);}else if(p.command==cmd("CLSE")){send(out,"CLSE",1,p.a,new byte[0]);break;}else if(p.command!=cmd("OKAY"))throw new IOException("ADB 数据通道异常");}
            String result=new String(response.toByteArray(),StandardCharsets.UTF_8).replace("\r\n","\n");int at=result.lastIndexOf("\n"+marker);if(at<0)throw new IOException("命令中断，未收到退出状态");String exit=result.substring(at+marker.length()+1).trim();if(!exit.equals("0"))throw new IOException("本机命令失败 ("+exit+"): "+result.substring(0,at));return result.substring(0,at);
        }
    }
}

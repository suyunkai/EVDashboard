package com.kooo.evdashboard.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;

public final class FilesEx {
    private FilesEx() {}
    public static String digest(File file) throws Exception {
        if (!file.isFile() || Files.isSymbolicLink(file.toPath())) throw new IOException("镜像不存在或是符号链接");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[1024*1024]; int n; while ((n=in.read(b))!=-1) md.update(b,0,n); }
        return hex(md.digest());
    }
    public static String hashText(String text) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    public static String hex(byte[] bytes) { StringBuilder b=new StringBuilder(); for(byte v:bytes)b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return b.toString(); }
    public static void require(boolean yes,String message) throws IOException { if(!yes)throw new IOException(message); }
    public static String quote(String s) { if(s.indexOf('\0')>=0)throw new IllegalArgumentException("NUL");return "'"+s.replace("'","'\\''")+"'"; }
    public static void atomicText(File file,String text) throws Exception {
        File tmp=new File(file.getParentFile(),file.getName()+".pending");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    public static String readText(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8); }
    public static void transfer(InputStream in,OutputStream out)throws IOException{byte[] buffer=new byte[1024*1024];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
    public static long copy(InputStream in,File to,long limit) throws Exception {
        require(!to.exists(),"目标文件已存在，拒绝覆盖"); long total=0;
        try(FileOutputStream out=new FileOutputStream(to)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1){total+=n;if(total>limit)throw new IOException("文件超过支持的大小");out.write(b,0,n);}out.getFD().sync();}return total;
    }
}

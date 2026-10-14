package com.kooo.evdashboard.core;

import java.io.*;
import java.util.Arrays;
import java.util.UUID;
import java.nio.ByteBuffer;

/** Structural profile, not a model-name allowlist. No write access here. */
public final class FipImage {
    public static final long PARTITION_BYTES=1073741824L;
    public final long payloadBytes,epoch;
    public final String uuid;
    private FipImage(long payload,long time,String id){payloadBytes=payload;epoch=time;uuid=id;}
    private static long le(byte[] b,int at,int count){long v=0;for(int k=0;k<count;k++)v|=(b[at+k]&255L)<<(8*k);return v;}
    public static FipImage read(File file) throws Exception {
        FilesEx.require(file.isFile()&&!java.nio.file.Files.isSymbolicLink(file.toPath()),"必须是普通镜像文件");
        FilesEx.require(file.length()==PARTITION_BYTES,"当前结构要求完整 1 GiB 分区镜像，不能使用裸 EROFS 或稀疏文件格式");
        try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
            byte[] h=new byte[4096];in.readFully(h);
            FilesEx.require(le(h,0,4)==0xaa640001L&&le(h,4,4)==0x12345678L&&le(h,8,8)==0,"不支持的 FIP 容器");
            byte[] type={(byte)0x8a,0x50,0x46,0x7f,(byte)0x89,(byte)0x9d,0x4a,(byte)0xb3,(byte)0x9e,(byte)0x97,(byte)0x8f,(byte)0xe0,(byte)0xed,0x6d,0x1d,(byte)0xcb};
            FilesEx.require(Arrays.equals(type,Arrays.copyOfRange(h,16,32)),"不是支持的 CP rootfs 条目");
            long size=le(h,40,8);FilesEx.require(size>=4096&&size<=PARTITION_BYTES-4096&&size%4096==0,"载荷长度越界");
            FilesEx.require(le(h,32,8)==4096&&le(h,48,8)==0&&le(h,72,8)==4096+size,"FIP 偏移或终止条目错误");
            for(int k=56;k<72;k++)FilesEx.require(h[k]==0,"不支持多条目 FIP");
            for(int k=80;k<h.length;k++)FilesEx.require(h[k]==0,"未知容器扩展");
            byte[] s=new byte[128];in.seek(4096+1024);in.readFully(s);
            FilesEx.require(le(s,0,4)==0xe0f5e1e2L&&s[12]==12&&le(s,8,4)==1&&le(s,80,4)==0&&le(s,84,2)==0,"不支持的 EROFS 特性");
            FilesEx.require(le(s,36,4)*4096==size,"文件系统长度与容器不匹配");
            ByteBuffer b=ByteBuffer.wrap(s,48,16);String uuid=new UUID(b.getLong(),b.getLong()).toString();
            return new FipImage(size,le(s,24,8),uuid);
        }
    }
    public static void compatibleEnvelope(File original,File candidate)throws Exception {
        FipImage a=read(original),b=read(candidate);
        FilesEx.require(a.uuid.equals(b.uuid)&&a.epoch==b.epoch,"文件系统 UUID 或构建时间不匹配");
        try(RandomAccessFile x=new RandomAccessFile(original,"r");RandomAccessFile y=new RandomAccessFile(candidate,"r")){
            byte[] h=new byte[4096],j=new byte[4096];x.readFully(h);y.readFully(j);
            for(int k=0;k<4096;k++)if(!(k>=40&&k<48)&&!(k>=72&&k<80))FilesEx.require(h[k]==j[k],"FIP 非许可字段发生变化");
            byte[] left=new byte[65536],right=new byte[65536],zeros=new byte[65536];
            for(long at=4096+a.payloadBytes;at<4096+b.payloadBytes;){int n=(int)Math.min(left.length,4096+b.payloadBytes-at);x.seek(at);x.readFully(left,0,n);if(n<left.length)Arrays.fill(left,n,left.length,(byte)0);FilesEx.require(Arrays.equals(left,zeros),"新载荷覆盖原容器非零尾部");at+=n;}
            for(long at=4096+b.payloadBytes;at<PARTITION_BYTES;){int n=(int)Math.min(left.length,PARTITION_BYTES-at);x.seek(at);y.seek(at);x.readFully(left,0,n);y.readFully(right,0,n);if(n<left.length){Arrays.fill(left,n,left.length,(byte)0);Arrays.fill(right,n,right.length,(byte)0);}FilesEx.require(Arrays.equals(left,right),"不透明尾部发生变化");at+=n;}
        }
    }
}

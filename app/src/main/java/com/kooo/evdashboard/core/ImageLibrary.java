package com.kooo.evdashboard.core;

import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Files live in Documents, not application cache; never silently overwrite. */
public final class ImageLibrary {
    public final File root,images,work,tasks;
    public ImageLibrary(File dir) throws Exception {root=dir;images=new File(dir,"images");work=new File(dir,"work");tasks=new File(dir,"tasks");for(File f:new File[]{root,images,work,tasks})FilesEx.require(f.isDirectory()||f.mkdirs(),"无法创建镜像库目录");}
    public File file(ImageRecord r) throws Exception {FilesEx.require(r.id.matches("[a-f0-9-]{36}"),"非法 ID");return new File(images,r.id+".img");}
    public synchronized void save(ImageRecord r) throws Exception {FilesEx.atomicText(new File(images,r.id+".json"),r.json().toString(2));}
    public synchronized List<ImageRecord> list() throws Exception {
        List<ImageRecord> records=new ArrayList<>();File[] entries=images.listFiles((d,n)->n.endsWith(".json"));if(entries==null)throw new IOException("镜像库不可读");
        for(File f:entries)records.add(ImageRecord.parse(new JSONObject(FilesEx.readText(f))));
        records.sort(Comparator.comparingLong((ImageRecord r)->r.created).reversed());return records;
    }
    public ImageRecord get(String id) throws Exception {for(ImageRecord r:list())if(r.id.equals(id))return r;throw new IOException("镜像记录不存在");}
    public boolean intact(ImageRecord r) throws Exception {File f=file(r);return f.isFile()&&f.length()==r.bytes&&r.sha.matches("[a-f0-9]{64}")&&FilesEx.digest(f).equals(r.sha);}
    public synchronized int remainingOriginals(ImageRecord selected) throws Exception {
        int count=0;for(ImageRecord r:list())if(r.original()&&!r.id.equals(selected.id)&&r.group().equals(selected.group())&&r.validated&&intact(r))count++;return count;
    }
    public synchronized void classify(String id,String category)throws Exception{
        ImageRecord r=get(id);FilesEx.require(r.origin.equals("DEVICE")&&r.category.equals("UNCLASSIFIED"),"历史分类不能更改");
        FilesEx.require(category.equals("ORIGINAL")||category.equals("MODIFIED"),"未知分类");FilesEx.require(r.validated&&intact(r),"镜像未通过完整校验，不能确认为有效备份");r.category=category;save(r);
    }
    public synchronized void delete(String id,Set<String> inUse,int confirmations) throws Exception {
        ImageRecord r=get(id);FilesEx.require(!inUse.contains(id),"镜像属于刷写或恢复所需文件，不能删除");FilesEx.require(confirmations>=(r.original()?2:1),"删除确认次数不足");
        for(ImageRecord other:list())FilesEx.require(!(other.validated&&other.origin.equals("GENERATED")&&other.sourceId.equals(id)),"仍有生成镜像依赖此来源，请先处理依赖镜像");
        if(r.original())FilesEx.require(remainingOriginals(r)>0,"禁止删除该车辆、槽位及固件基线的最后一份有效原始镜像");
        // Rename to a task-local recovery area first. Failed deletion is recoverable.
        File trash=new File(root,"deleted-"+UUID.randomUUID());FilesEx.require(trash.mkdir(),"无法创建删除暂存目录");
        File f=file(r),meta=new File(images,r.id+".json");
        File moved=new File(trash,f.getName());if(f.exists())java.nio.file.Files.move(f.toPath(),moved.toPath());
        try{java.nio.file.Files.move(meta.toPath(),new File(trash,meta.getName()).toPath());}catch(Exception e){if(moved.exists())java.nio.file.Files.move(moved.toPath(),f.toPath());throw e;}
    }
    public ImageRecord create(String origin,String name) throws Exception {ImageRecord r=new ImageRecord();r.id=UUID.randomUUID().toString();r.origin=origin;r.name=name;if(origin.equals("IMPORT"))r.category="IMPORTED";save(r);return r;}
}

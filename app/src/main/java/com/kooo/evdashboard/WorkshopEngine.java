package com.kooo.evdashboard;

import android.content.Context;
import com.kooo.evdashboard.core.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

public final class WorkshopEngine {
    final ImageLibrary library;final RootAccess root;final Context context;final Consumer<String> progress;
    DeviceInfo device;
    public WorkshopEngine(Context c,ImageLibrary lib,Consumer<String> log){context=c;library=lib;root=new RootAccess(c);progress=log;}
    String tool(String name){return new File(context.getApplicationInfo().nativeLibraryDir,"lib"+name+".so").getAbsolutePath();}
    String execute(String name,String...args)throws Exception {return RootAccess.nativeRun(tool(name),600,args);}
    DeviceInfo identify()throws Exception{progress.accept("检查独立 root 权限与 CP 槽位…");String method=root.connect();device=DeviceInfo.detect(root);progress.accept(method+"\n"+device.summary());return device;}
    private static String q(String s){return FilesEx.quote(s);}
    private File receipt(String name){return new File(context.getFilesDir(),name);}
    private void space(long need)throws Exception{FilesEx.require(library.root.getUsableSpace()>need,"可用空间不足，需要至少 "+(need/(1024*1024))+" MiB");}
    ImageRecord exportPartition()throws Exception {
        DeviceInfo d=identify();space(FipImage.PARTITION_BYTES+256*1024*1024L);
        ImageRecord r=library.create("DEVICE","分区导出 "+new java.text.SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date()));r.device=d.device;r.slot=d.slot;library.save(r);
        File image=library.file(r);progress.accept("完整导出 "+d.slot+"，原文件不会覆盖…");
        // Application creates the destination first, so the image retains app ownership.
        FilesEx.require(image.createNewFile(),"目标已存在");
        try {
            String before=partitionHash(d);d.same(DeviceInfo.detect(root));
            // The app owns all destination writes, avoiding stale FUSE attributes after
            // a root process writes through /data/media behind the app's mount namespace.
            root.copyReadOnly(d.block,image,d.bytes);
            FilesEx.require(image.length()==d.bytes,"导出长度不完整");r.sha=FilesEx.digest(image);r.bytes=image.length();
            FilesEx.require(before.equals(r.sha)&&before.equals(partitionHash(d)),"导出期间源分区发生变化或复制不完整");d.same(DeviceInfo.detect(root));
            inspect(r);r.message="导出、全量摘要与结构校验通过；请选择镜像类型";library.save(r);return r;
        }catch(Exception e){r.message="导出/检测失败，文件保留待检查："+e.getMessage();r.bytes=image.length();r.validated=false;library.save(r);throw e;}
    }
    String partitionHash(DeviceInfo d)throws Exception{progress.accept("读取目标分区摘要（只读）…");String s=root.run("sha256sum "+q(d.block),180).trim().split("\\s+")[0];FilesEx.require(s.matches("[a-f0-9]{64}"),"分区摘要无效");return s;}
    public void inspect(ImageRecord r)throws Exception {
        File f=library.file(r);progress.accept("检测容器与 EROFS，并读取 SSH/账号配置…");r.validated=false;
        try {FipImage.read(f);execute("fsck_erofs","--offset=4096","--extract",f.toString());
            JSONObject j=new JSONObject(execute("image_tool","inspect",f.toString(),"4096"));
            r.socket=j.getString("socket");r.password=j.getString("rootPassword");r.blankAllowed=j.getBoolean("blankAllowed");r.build=FilesEx.hashText(j.getString("build"));
            if(!r.sha.isEmpty())FilesEx.require(FilesEx.digest(f).equals(r.sha),"文件摘要与记录不符；不自动接受已被改动的镜像");else r.sha=FilesEx.digest(f);
            r.bytes=f.length();r.validated=true;r.message="结构检查通过；镜像配置不等于实时 SSH 状态";
        }catch(Exception e){r.message="未通过检查："+e.getMessage();throw e;}finally{library.save(r);}
    }
    ImageRecord importImage(InputStream in,String name)throws Exception{
        space(FipImage.PARTITION_BYTES+128*1024*1024L);ImageRecord r=library.create("IMPORT",name);File f=library.file(r);
        try {FilesEx.copy(in,f,FipImage.PARTITION_BYTES);r.bytes=f.length();r.sha=FilesEx.digest(f);library.save(r);inspect(r);return r;}
        catch(Exception e){r.bytes=f.length();r.validated=false;r.message="导入/检查失败："+e.getMessage();library.save(r);throw e;}
    }
    public ImageRecord generate(ImageRecord source,boolean ssh,boolean blank)throws Exception{
        FilesEx.require(ssh||blank,"请至少选择一项修改");FilesEx.require(library.intact(source),"源镜像损坏或已变化");inspect(source);space(4L*FipImage.PARTITION_BYTES);
        String originalSha=source.sha;FipImage header=FipImage.read(library.file(source));File job=new File(library.work,UUID.randomUUID().toString());FilesEx.require(job.mkdir(),"创建生成目录失败");
        File tar=new File(job,"filesystem.tar"),erofs=new File(job,"filesystem.erofs");ImageRecord result=library.create("GENERATED",source.name+" · SSH 修改");result.category="MODIFIED";result.sourceId=source.id;result.sourceSha=originalSha;result.enableSsh=ssh;result.clearPassword=blank;result.device=source.device;result.slot=source.slot;result.build=source.build;library.save(result);
        try {
            progress.accept("导出文件系统及元数据，仅修改已勾选项目…");execute("image_tool","export",library.file(source).toString(),"4096",ssh?"1":"0",blank?"1":"0",tar.toString());
            progress.accept("本机重建 EROFS…");execute("mkfs_erofs","--quiet","--tar=f","-b4096","-Elegacy-compress,^xattr-name-filter","--preserve-mtime","-T"+header.epoch,"--mkfs-time","-U"+header.uuid,erofs.toString(),tar.toString());
            execute("fsck_erofs","--extract",erofs.toString());progress.accept("逐文件比较内容、权限、扩展属性和链接…");
            execute("image_tool","verify",library.file(source).toString(),"4096",ssh?"1":"0",blank?"1":"0",erofs.toString(),"0");
            execute("fip_pack",library.file(source).toString(),erofs.toString(),library.file(result).toString());execute("fip_verify",library.file(source).toString(),erofs.toString(),library.file(result).toString());
            execute("image_tool","verify",library.file(source).toString(),"4096",ssh?"1":"0",blank?"1":"0",library.file(result).toString(),"4096");
            FilesEx.require(FilesEx.digest(library.file(source)).equals(originalSha),"源镜像发生变化");inspect(result);result.message="生成及独立复核通过；未写入分区";library.save(result);
            // Only disposable intermediates from this exact task; never remove source/candidate.
            java.nio.file.Files.deleteIfExists(tar.toPath());java.nio.file.Files.deleteIfExists(erofs.toPath());java.nio.file.Files.deleteIfExists(job.toPath());return result;
        }catch(Exception e){result.validated=false;result.message="生成未完成："+e.getMessage();library.save(result);throw e;}
    }
    public void verifyGenerated(ImageRecord r)throws Exception{
        try{
            inspect(r);if(!r.origin.equals("GENERATED"))return;ImageRecord source=library.get(r.sourceId);FilesEx.require(source.sha.equals(r.sourceSha)&&library.intact(source),"来源镜像缺失或变化，不能复核差异");
            FipImage.compatibleEnvelope(library.file(source),library.file(r));execute("image_tool","verify",library.file(source).toString(),"4096",r.enableSsh?"1":"0",r.clearPassword?"1":"0",library.file(r).toString(),"4096");r.message="重新读取候选，结构、来源及修改白名单校验通过";library.save(r);
        }catch(Exception e){r.validated=false;r.message="重新校验失败："+e.getMessage();library.save(r);throw e;}
    }
    void deleteImage(String id,int confirmations)throws Exception{
        Set<String> protectedIds=new HashSet<>();
        for(String name:new String[]{"pending-write.json","last-write.json"}){
            File saved=receipt(name);if(!saved.isFile())continue;
            JSONObject j=new JSONObject(FilesEx.readText(saved));protectedIds.add(j.getString("backupId"));
            if(name.equals("pending-write.json"))protectedIds.add(j.getString("candidateId"));
        }
        library.delete(id,protectedIds,confirmations);
    }
    private void compatible(ImageRecord baseline,ImageRecord candidate)throws Exception{
        FilesEx.require(baseline.build.equals(candidate.build),"固件基线不匹配，禁止跨版本混刷");
        if(baseline.sha.equals(candidate.sha))return;
        try {FipImage.compatibleEnvelope(library.file(baseline),library.file(candidate));semanticDelta(baseline,candidate);return;}catch(Exception forward){
            // A restore is the reverse of the same strictly scoped transformation.
            try{FipImage.compatibleEnvelope(library.file(candidate),library.file(baseline));semanticDelta(candidate,baseline);return;}catch(Exception reverse){throw new IOException("候选与当前备份不属于允许的 SSH/密码变更或其恢复；拒绝写入。"+forward.getMessage());}
        }
    }
    private void semanticDelta(ImageRecord from,ImageRecord to)throws Exception{
        String enable=!from.socket.equals("ENABLED")&&to.socket.equals("ENABLED")?"1":"0";
        // Preset may need fixing even when the enable link already exists.
        String clear=from.password.equals("SET")&&to.password.equals("EMPTY")?"1":"0";
        try{execute("image_tool","verify",library.file(from).toString(),"4096",enable,clear,library.file(to).toString(),"4096");}
        catch(Exception e){if(enable.equals("1"))throw e;execute("image_tool","verify",library.file(from).toString(),"4096","1",clear,library.file(to).toString(),"4096");}
    }
    ImageRecord backupFor(DeviceInfo d,String liveHash)throws Exception{
        for(ImageRecord r:library.list())if(r.origin.equals("DEVICE")&&r.device.equals(d.device)&&r.slot.equals(d.slot)&&r.sha.equals(liveHash)&&r.validated&&library.intact(r))return r;
        // Reuse an existing backup after our own fully verified write, never on an unexplained change.
        File receipt=receipt("last-write.json");if(receipt.isFile()){
            JSONObject j=new JSONObject(FilesEx.readText(receipt));if(j.optString("device").equals(d.device)&&j.optString("slot").equals(d.slot)&&j.optString("writtenSha").equals(liveHash)){
                ImageRecord original=library.get(j.getString("backupId"));ImageRecord current=library.get(j.getString("candidateId"));
                if(original.origin.equals("DEVICE")&&original.device.equals(d.device)&&original.slot.equals(d.slot)&&original.validated&&library.intact(original)&&current.sha.equals(liveHash)&&library.intact(current))return current;
            }
        }
        throw new IOException("没有当前分区的有效本机导出备份，请先导出；OTA 或未知变更后必须重新导出");
    }
    void flash(ImageRecord candidate,boolean parked,boolean power,boolean noOta,boolean acceptsRisk)throws Exception{
        flash(candidate,parked,power,noOta,acceptsRisk,false);
    }
    void flash(ImageRecord candidate,boolean parked,boolean power,boolean noOta,boolean acceptsRisk,boolean sameContentTest)throws Exception{
        FilesEx.require(!receipt("pending-write.json").exists(),"有未完成刷写任务，必须先核验；不会启动第二次写入");
        FilesEx.require(parked&&power&&noOta&&acceptsRisk,"请确认损坏及自费维修风险、P 挡驻车、稳定供电、无 OTA 且不会休眠");DeviceInfo d=identify();FilesEx.require(library.intact(candidate),"候选文件已变化");verifyGenerated(candidate);String live=partitionHash(d);ImageRecord baseline=backupFor(d,live);compatible(baseline,candidate);d.same(DeviceInfo.detect(root));space(2L*FipImage.PARTITION_BYTES+256*1024*1024L);
        WritePlan.checkContentMode(candidate.sha,live,sameContentTest);
        String id=UUID.randomUUID().toString();String job="/data/local/evdashboard/jobs/"+id;File localJob=new File(library.tasks,id);FilesEx.require(localJob.mkdir(),"无法创建任务记录");
        String backupId=baseline.id;if(!baseline.origin.equals("DEVICE")){JSONObject previous=new JSONObject(FilesEx.readText(receipt("last-write.json")));backupId=previous.getString("backupId");}
        ImageRecord backup=library.get(backupId);FilesEx.require(library.intact(backup),"原备份不可用");
        File script=new File(context.getFilesDir(),"writer-"+id+".sh");FilesEx.atomicText(script,WritePlan.build(d.slot,d.block,d.bootId,job,candidate.sha,live));String scriptSha=FilesEx.digest(script);
        String rootCandidate=root.sharedFile(library.file(candidate)),rootScript=root.sharedFile(script);
        JSONObject manifest=new JSONObject().put("device",d.device).put("slot",d.slot).put("block",d.block).put("boot",d.bootId).put("candidateId",candidate.id).put("writtenSha",candidate.sha).put("backupId",backupId).put("job",job).put("sameContentTest",sameContentTest).put("state","PREPARING");FilesEx.atomicText(new File(localJob,"job.json"),manifest.toString(2));FilesEx.atomicText(receipt("pending-write.json"),manifest.toString(2));
        progress.accept("将候选复制到 root 专用任务目录并再次校验。随后开始单槽写入，请勿断电…");
        String launch="set -e; umask 077; test ! -e /data/local/evdashboard/writer.lock; test \"$(df -k /data/local | tail -n 1 | awk '{print $4}')\" -gt 2359296; mkdir -p /data/local/evdashboard/jobs; mkdir "+q(job)+"; chmod 700 "+q(job)+"; cp "+q(rootScript)+" "+q(job+"/writer.sh")+"; cp "+q(rootCandidate)+" "+q(job+"/candidate.img")+"; test \"$(sha256sum "+q(job+"/writer.sh")+" | cut -d ' ' -f1)\" = "+q(scriptSha)+"; test \"$(sha256sum "+q(job+"/candidate.img")+" | cut -d ' ' -f1)\" = "+q(candidate.sha)+"; "+DetachedLaunch.writer(job);
        root.run(launch,300);pollWrite(manifest,localJob);
    }
    void recoverPending()throws Exception{
        File pending=receipt("pending-write.json");FilesEx.require(pending.isFile(),"没有待核验的刷写任务");JSONObject j=new JSONObject(FilesEx.readText(pending));String job=j.getString("job");FilesEx.require(job.matches("/data/local/evdashboard/jobs/[a-f0-9-]{36}"),"任务路径异常");DeviceInfo current=identify();FilesEx.require(current.device.equals(j.getString("device"))&&current.slot.equals(j.getString("slot")),"当前设备或 CP 槽位已变化，保留任务以人工核验");pollWrite(j,new File(library.tasks,job.substring(job.lastIndexOf('/')+1)));
    }
    private void pollWrite(JSONObject manifest,File localJob)throws Exception{
        String job=manifest.getString("job");String last="";
        for(int i=0;i<180;i++){
            String result=root.run("if [ -f "+q(job+"/result")+" ]; then cat "+q(job+"/result")+"; else echo RUNNING; fi; if [ -f "+q(job+"/stage")+" ]; then cat "+q(job+"/stage")+"; fi",12);
            if(!result.equals(last)){progress.accept("刷写任务："+result);last=result;}
            if(result.startsWith("PASS")){
                FilesEx.atomicText(new File(localJob,"writer.log"),root.run("cat "+q(job+"/writer.log"),15));manifest.put("state","WRITE_AND_READBACK_VERIFIED");FilesEx.atomicText(new File(localJob,"job.json"),manifest.toString(2));FilesEx.atomicText(receipt("last-write.json"),manifest.toString(2));java.nio.file.Files.deleteIfExists(receipt("pending-write.json").toPath());progress.accept("完整写入、全量回读及非目标分区核验通过。没有切槽或重启；请另行安排启动验收。");return;
            }
            if(result.startsWith("FAILED_BEFORE_WRITE")){FilesEx.atomicText(new File(localJob,"writer.log"),root.run("cat "+q(job+"/writer.log"),15));manifest.put("state","FAILED_BEFORE_WRITE");FilesEx.atomicText(new File(localJob,"job.json"),manifest.toString(2));java.nio.file.Files.deleteIfExists(receipt("pending-write.json").toPath());throw new IOException("写入前检查失败，没有写入分区。检查任务日志后再处理，不自动重试。");}
            if(result.startsWith("FAIL")){FilesEx.atomicText(new File(localJob,"writer.log"),root.run("cat "+q(job+"/writer.log"),15));throw new IOException("刷写未完成。请保持供电，不要重启；任务与备份已保留，不自动重试。");}
            Thread.sleep(4000);
        }
        throw new IOException("暂未取得最终结果。后台任务可能仍在运行；请使用“检查未完成任务”，不要重复写入。");
    }
}

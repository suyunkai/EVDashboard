package com.kooo.evdashboard;

import android.content.Context;
import android.content.SharedPreferences;
import com.kooo.evdashboard.core.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

/** SSH runtime resource overlays. Never modifies the firmware partition. */
final class AudioEngine {
    private static final String ROOT="/run/instrument-workshop",AVAS="/usr/avas/sound_type_1";
    private static final String CHIMES="/usr/share/chime-contents/V3_Geely_DNA_DHU/";
    private static final String LOCK="V3_Vehicle_Locked_Single.wav";
    private static final String[] CRUISE={"V3_Function_Enabled_Successful_Single.wav","V3_Function_Exited_Single.wav"};
    private final Context context;private final Consumer<String> progress;private boolean automatic;
    AudioEngine(Context c,Consumer<String> log){context=c;progress=log;}
    private static String q(String s){return FilesEx.quote(s);}
    String status(String password)throws Exception{try(SshAccess ssh=new SshAccess(context)){ssh.connect(password);return ssh.text("printf 'CP uptime: '; cat /proc/uptime; printf '\\nSSH 用户: '; id -u; printf '\\nAVAS: '; systemctl is-active app_1.8.service || :; printf '\\n提示音: '; systemctl is-active cluster-cmw-audioplay.service || :; printf '\\n覆盖: '; awk '$5 ~ /^\\/usr\\/(avas|share\\/chime-contents)/ {print $5, $4}' /proc/self/mountinfo",12);}}
    int[][] curves(String password)throws Exception{try(SshAccess ssh=new SshAccess(context)){ssh.connect(password);int[][] result=new int[2][];for(int i=0;i<2;i++){String name=i==0?"tuning_5w.txt":"tuning_5w_r.txt";String base=ROOT+"/factory-avas/"+name;byte[] data=ssh.command("if [ -f "+q(base)+" ]; then cat "+q(base)+"; else cat "+q(AVAS+"/"+name)+"; fi",null,12);result[i]=TuningCurve.values(data);}return result;}}
    private void establish(SshAccess ssh)throws Exception{ssh.text("set -e; test \"$(id -u)\" = 0; test -d /run; mkdir -p "+q(ROOT)+"; chmod 700 "+q(ROOT),10);}
    /** Refuse overlays owned by other software instead of unmounting them. */
    private String ownership(String target){return "mounted=$(awk -v dst="+q(target)+" '$5==dst {print $4}' /proc/self/mountinfo); case \"$mounted\" in '') ;; /instrument-workshop/*|/run/instrument-workshop/*) ;; *) echo '检测到其他工具覆盖，请先在原工具恢复默认' >&2; exit 62;; esac; ";}
    private void factory(SshAccess ssh,String target,String dest)throws Exception{
        ssh.text("set -e; "+ownership(target)+"if [ ! -e "+q(dest)+" ]; then test -z \"$mounted\"; cp -a "+q(target)+" "+q(dest)+"; fi",30);
    }
    private String staged(SshAccess ssh)throws Exception{String path=ROOT+"/stage-"+UUID.randomUUID();ssh.text("set -e; test \"$(df -k /run | tail -n 1 | awk '{print $4}')\" -gt 32768; mkdir "+q(path),10);return path;}
    private void switchResources(SshAccess ssh,String target,String replacement,String service,String tag)throws Exception{
        String job=staged(ssh);ssh.upload(job+"/operation.sh",AudioPlan.build(target,replacement,service,tag,job).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // The CP-side worker must survive an Android/SSH disconnect. Never replay an uncertain switch.
        ssh.text("command -v nohup >/dev/null; nohup /bin/sh "+q(job+"/operation.sh")+" > "+q(job+"/operation.log")+" 2>&1 < /dev/null &",10);
        long deadline=System.nanoTime()+120_000_000_000L;
        while(System.nanoTime()<deadline){String result=ssh.text("if [ -f "+q(job+"/result")+" ]; then cat "+q(job+"/result")+"; fi",10).trim();if(!result.isEmpty()){FilesEx.require(result.equals("PASS"),"音效切换未完成："+result+"。恢复记录："+job);return;}Thread.sleep(500);}
        throw new IOException("CP 音效任务仍未确认完成，不会重试。请读取运行状态及任务记录："+job);
    }
    private void activate(SshAccess ssh,String target,String replacement,String service,String tag)throws Exception{
        if(automatic)ParkedProbe.requireParked(context);
        switchResources(ssh,target,replacement,service,tag);
    }
    void applyAvas(String password,File audio,int[] forward,int[] reverse,int gain)throws Exception{
        FilesEx.require((forward==null)==(reverse==null),"正向和倒车曲线必须同时提供");FilesEx.require(audio!=null||forward!=null,"请选择自定义音频或加载曲线");
        FilesEx.require(gain>=50&&gain<=200,"曲线总音量必须为 50–200%");byte[] audioBytes=null;if(audio!=null){FilesEx.require(audio.isFile()&&audio.length()<=44+48000*30*2,"AVAS 音频缺失或过大");audioBytes=Files.readAllBytes(audio.toPath());WaveAudio.normalizedSamples(audioBytes);}
        try(SshAccess ssh=new SshAccess(context)){ssh.connect(password);establish(ssh);factory(ssh,AVAS,ROOT+"/factory-avas");String dir=staged(ssh),copy=dir+"/avas";ssh.text("cp -a "+q(ROOT+"/factory-avas")+" "+q(copy),30);
            if(audioBytes!=null){progress.accept("上传并校验 AVAS 音频…");ssh.upload(dir+"/audio.wav",audioBytes);ssh.text("set -e; found=0; for sound in "+q(copy)+"/*.wav "+q(copy)+"/*.WAV; do [ -f \"$sound\" ] || continue; cp "+q(dir+"/audio.wav")+" \"$sound\"; found=1; done; test \"$found\" = 1",15);}
            if(forward!=null){String[] names={"tuning_5w.txt","tuning_5w_r.txt","tuning_5w_AU_INDO_MALA.txt","tuning_5w_r_AU_INDO_MALA.txt"};int count=0;for(String name:names){String exists=ssh.text("if [ -f "+q(ROOT+"/factory-avas/"+name)+" ]; then echo yes; fi",10).trim();if(!exists.equals("yes"))continue;byte[] original=ssh.command("cat "+q(ROOT+"/factory-avas/"+name),null,10);byte[] changed=TuningCurve.update(original,name.contains("5w_r")?reverse:forward,gain);String temp=dir+"/"+name;ssh.upload(temp,changed);ssh.text("cat "+q(temp)+" > "+q(copy+"/"+name),10);count++;}FilesEx.require(count>=2,"缺少支持的正向/倒车曲线文件");}
            progress.accept("切换临时资源覆盖并检查 AVAS 服务…");activate(ssh,AVAS,copy,"app_1.8.service","avas");
        }
        SharedPreferences.Editor e=context.getSharedPreferences("settings",0).edit().putBoolean("avasApplied",true).putString("avasFile",audio==null?"":audio.toString()).putString("forward",join(forward)).putString("reverse",join(reverse)).putInt("avasGain",gain);e.apply();progress.accept("AVAS 资源及服务状态检查通过；实际声音、音量和提示效果仍需现场验收。");
    }
    void applyLock(String password,File audio,int gain)throws Exception{
        FilesEx.require(audio.isFile()&&audio.length()<=44+48000*30*2,"锁车音频缺失或过大");short[] pcm=WaveAudio.normalizedSamples(Files.readAllBytes(audio.toPath()));byte[] adjusted=WaveAudio.encodeMono(WaveAudio.convert(pcm,1,48000,gain),48000);
        try(SshAccess ssh=new SshAccess(context)){ssh.connect(password);establish(ssh);String target=CHIMES+LOCK;factory(ssh,target,ROOT+"/factory-lock.wav");String stage=staged(ssh);ssh.upload(stage+"/lock.wav",adjusted);activate(ssh,target,stage+"/lock.wav","cluster-cmw-audioplay.service","lock");}
        context.getSharedPreferences("settings",0).edit().putBoolean("lockApplied",true).putString("lockFile",audio.toString()).putInt("lockGain",gain).apply();progress.accept("锁车声临时覆盖已应用，请现场确认播放效果。");
    }
    void restore(String password,String feature)throws Exception{
        try(SshAccess ssh=new SshAccess(context)){ssh.connect(password);establish(ssh);
            if(feature.equals("avas"))restoreOne(ssh,AVAS,"app_1.8.service","avas");
            else if(feature.equals("lock"))restoreOne(ssh,CHIMES+LOCK,"cluster-cmw-audioplay.service","lock");
            else if(feature.equals("cruise"))for(int i=0;i<CRUISE.length;i++)restoreOne(ssh,CHIMES+CRUISE[i],"cluster-cmw-audioplay.service","cruise-"+i);
            else throw new IOException("未知音效类型");
        }
        if(!feature.equals("cruise"))context.getSharedPreferences("settings",0).edit().putBoolean(feature+"Applied",false).apply();progress.accept("已撤销本应用的覆盖并启动原服务。没有覆盖原始资源文件。");
    }
    private void restoreOne(SshAccess ssh,String target,String service,String tag)throws Exception{
        switchResources(ssh,target,"",service,tag);
    }
    void avasEnabled(String password,boolean enabled)throws Exception{try(SshAccess ssh=new SshAccess(context)){ssh.connect(password);ssh.text("systemctl "+(enabled?"start":"stop")+" app_1.8.service",20);String state=ssh.text("systemctl is-active app_1.8.service || :",10).trim();FilesEx.require(state.equals(enabled?"active":"inactive"),"AVAS 服务状态未符合预期："+state);}progress.accept(enabled?"AVAS 服务已启动":"AVAS 已临时停止。仅限驻车测试，离开测试前请恢复；此设置不会开机重放。");}
    void muteCruiseForTest(String password)throws Exception{
        try(SshAccess ssh=new SshAccess(context)){ssh.connect(password);establish(ssh);int applied=0;try{for(int i=0;i<CRUISE.length;i++){String target=CHIMES+CRUISE[i],base=ROOT+"/factory-cruise-"+i+".wav";factory(ssh,target,base);byte[] original=ssh.command("cat "+q(base),null,12);String stage=staged(ssh);ssh.upload(stage+"/silent.wav",WaveAudio.silence(original));activate(ssh,target,stage+"/silent.wav","cluster-cmw-audioplay.service","cruise-"+i);applied++;}}catch(Exception failure){for(int i=0;i<applied;i++)try{restoreOne(ssh,CHIMES+CRUISE[i],"cluster-cmw-audioplay.service","cruise-"+i);}catch(Exception rollback){failure.addSuppressed(rollback);}throw failure;}}progress.accept("两项通用功能提示音已临时静音；除巡航外其他功能也可能使用它们。仅限驻车测试，不会保存为开机设置。");
    }
    void reapplySaved(String password)throws Exception{reapplySaved(false,password);}
    void reapplySaved(boolean automatic)throws Exception{reapplySaved(automatic,"");}
    private void reapplySaved(boolean automatic,String password)throws Exception{this.automatic=automatic;SharedPreferences p=context.getSharedPreferences("settings",0);if(p.getBoolean("avasApplied",false)){if(automatic)ParkedProbe.requireParked(context);String path=p.getString("avasFile","");applyAvas(password,path.isEmpty()?null:new File(path),parseOptional(p.getString("forward","")),parseOptional(p.getString("reverse","")),p.getInt("avasGain",100));}if(p.getBoolean("lockApplied",false)){if(automatic)ParkedProbe.requireParked(context);applyLock(password,new File(p.getString("lockFile","")),p.getInt("lockGain",100));}}
    static String join(int[] values){if(values==null)return "";StringJoiner j=new StringJoiner(",");for(int v:values)j.add(Integer.toString(v));return j.toString();}
    static int[] parseOptional(String text){if(text.trim().isEmpty())return null;return Arrays.stream(text.trim().split("[,，\\s]+" )).mapToInt(Integer::parseInt).toArray();}
}

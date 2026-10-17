package com.kooo.evdashboard.core;

import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Executes only fake mount/service commands against temporary ordinary files. */
public class SoundBatchTest {
    private static final String ID="12345678-1234-1234-1234-123456789abc";
    private static void write(Path p,String s)throws Exception{Files.write(p,s.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String read(Path p)throws Exception{return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}
    private static final class Rig implements AutoCloseable {
        final Path dir=Files.createTempDirectory("sound-batch-");
        final Path root=Files.createDirectory(dir.resolve("workshop")),bin=Files.createDirectory(dir.resolve("bin")),job=Files.createDirectory(root.resolve("stage-"+ID));
        final Path one=dir.resolve("one.wav"),two=dir.resolve("two.wav"),old=Files.createDirectory(root.resolve("stage-old")).resolve("old.wav");
        final String hash1=FilesEx.hashText("new one"),hash2=FilesEx.hashText("new two");
        Rig(boolean previous,boolean secondActive)throws Exception {
            write(one,"factory one");write(two,"factory two");write(old,"previous");write(job.resolve(hash1+".wav"),"new one");write(job.resolve(hash2+".wav"),"new two");
            write(dir.resolve("mountinfo"),previous?"1 0 0:1 "+old+" "+one+" rw - tmpfs tmpfs rw\n":"");
            write(dir.resolve("cluster-cmw-audioplay.service"),"1");write(dir.resolve("outdoor-audioplay.service"),secondActive?"1":"0");
            String prefix="#!/bin/sh\nset -eu\ncd \"$RIG\"\nfail_once() { if [ \"${FAIL:-}\" = \"$1\" ] && [ ! -e failed ]; then touch failed; return 0; fi; return 1; }\n";
            command("systemctl",prefix+"case $1 in stop) echo 0 > \"$2\"; if fail_once stop; then exit 1; fi;; start) if fail_once start; then exit 1; fi; echo 1 > \"$2\";; is-active) if [ \"$2\" = --quiet ]; then shift; fi; test \"$(cat \"$2\")\" = 1;; *) exit 90;; esac\n");
            command("mount",prefix+"if [ \"$3\" = \"$RIG/two.wav\" ] && fail_once mount-second; then exit 1; fi\nprintf '1 0 0:1 %s %s rw - tmpfs tmpfs rw\\n' \"$2\" \"$3\" >> mountinfo\nif fail_once signal; then kill -TERM \"$PPID\"; fi\n");
            command("umount",prefix+"if fail_once umount; then exit 1; fi\nawk -v dst=\"$1\" '$5!=dst' mountinfo > mountinfo.new; mv mountinfo.new mountinfo\n");
            command("sha256sum",prefix+"source=$(awk -v dst=\"$1\" '$5==dst {print $4}' mountinfo); if [ -z \"$source\" ]; then source=$1; fi\n/usr/bin/shasum -a 256 \"$source\"\n");
        }
        void command(String name,String code)throws Exception{Path p=bin.resolve(name);write(p,code);assertTrue(p.toFile().setExecutable(true));}
        int run(String fail,boolean restore)throws Exception {
            String path1=SoundCatalog.CHIMES+"V3_Function_Exited_Single.wav",path2=SoundCatalog.CHIMES+"V3_Vehicle_Locked_Single.wav";
            boolean hadOld=read(dir.resolve("mountinfo")).contains(old.toString());
            List<AudioPlan.Change> changes=Arrays.asList(new AudioPlan.Change(path1,restore?"":AudioPlan.ROOT+"/stage-"+ID+"/"+hash1+".wav",FilesEx.hashText(hadOld?"previous":"factory one")),
                new AudioPlan.Change(path2,restore?"":AudioPlan.ROOT+"/stage-"+ID+"/"+hash2+".wav",FilesEx.hashText("factory two")));
            String code=AudioPlan.batch(changes,new LinkedHashSet<>(Arrays.asList("cluster-cmw-audioplay.service","outdoor-audioplay.service")),AudioPlan.ROOT+"/stage-"+ID)
                .replace(AudioPlan.ROOT,root.toString()).replace(path1,one.toString()).replace(path2,two.toString()).replace("/proc/self/mountinfo",dir.resolve("mountinfo").toString());
            write(job.resolve("operation.sh"),code);ProcessBuilder b=new ProcessBuilder("/bin/sh",job.resolve("operation.sh").toString()).redirectErrorStream(true).redirectOutput(dir.resolve("log").toFile());
            b.environment().put("RIG",dir.toString());b.environment().put("FAIL",fail);b.environment().put("PATH",bin+":"+System.getenv("PATH"));
            Process p=b.start();assertTrue(p.waitFor(15,TimeUnit.SECONDS));return p.exitValue();
        }
        void previous(boolean expected)throws Exception {
            String mounts=read(dir.resolve("mountinfo"));assertEquals(expected,mounts.contains(old.toString()));if(!expected)assertTrue(mounts.isEmpty());
            assertEquals("1",read(dir.resolve("cluster-cmw-audioplay.service")).trim());assertFalse(Files.exists(root.resolve("operation.lock")));
        }
        public void close()throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
    @Test public void successAppliesBothAndKeepsInitiallyStoppedServiceStopped()throws Exception{try(Rig r=new Rig(false,false)){assertEquals(0,r.run("",false));assertEquals("PASS",read(r.job.resolve("result")).trim());String mounts=read(r.dir.resolve("mountinfo"));assertTrue(mounts.contains(r.hash1));assertTrue(mounts.contains(r.hash2));assertEquals("0",read(r.dir.resolve("outdoor-audioplay.service")).trim());}}
    @Test public void everyFailureRestoresAllPreviousResourcesAndServices()throws Exception{for(String fail:new String[]{"stop","umount","mount-second","start","signal"})try(Rig r=new Rig(true,true)){assertNotEquals(fail,0,r.run(fail,false));assertEquals(fail+" "+read(r.dir.resolve("log")),"FAILED_RESTORED",read(r.job.resolve("result")).trim());r.previous(true);assertEquals("1",read(r.dir.resolve("outdoor-audioplay.service")).trim());}}
    @Test public void restoreRemovesPreviousOverlayAndKeepsFactoryResources()throws Exception{try(Rig r=new Rig(true,true)){assertEquals(0,r.run("",true));assertEquals("PASS",read(r.job.resolve("result")).trim());r.previous(false);}}
    @Test public void foreignMountPreventsAnyServiceChange()throws Exception{try(Rig r=new Rig(false,true)){write(r.dir.resolve("mountinfo"),"1 0 0:1 /foreign/file "+r.one+" rw - tmpfs tmpfs rw\n");assertNotEquals(0,r.run("",false));assertTrue(read(r.dir.resolve("mountinfo")).contains("/foreign/file"));assertEquals("1",read(r.dir.resolve("cluster-cmw-audioplay.service")).trim());}}
    @Test public void corruptStagedAudioIsRejectedBeforeChangingServicesOrMounts()throws Exception{try(Rig r=new Rig(false,true)){write(r.job.resolve(r.hash2+".wav"),"corrupt");assertNotEquals(0,r.run("",false));r.previous(false);assertEquals("1",read(r.dir.resolve("outdoor-audioplay.service")).trim());}}
    @Test public void injectionAndDuplicateTargetsRejected()throws Exception{
        AudioPlan.Change bad=new AudioPlan.Change("/etc/shadow","",FilesEx.hashText("x"));
        assertThrows(Exception.class,()->AudioPlan.batch(Collections.singletonList(bad),Collections.singleton("app_1.8.service"),AudioPlan.ROOT+"/stage-"+ID));
        AudioPlan.Change valid=new AudioPlan.Change(SoundCatalog.CHIMES+"V3_Vehicle_Locked_Single.wav","",FilesEx.hashText("x"));
        assertThrows(Exception.class,()->AudioPlan.batch(Arrays.asList(valid,valid),Collections.singleton("outdoor-audioplay.service"),AudioPlan.ROOT+"/stage-"+ID));
    }
}

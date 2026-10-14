package com.kooo.evdashboard.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class AudioPlanTest {
    private static void write(Path path,String value)throws Exception{Files.write(path,value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String read(Path path)throws Exception{return new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8);}
    private static final String ID="12345678-1234-1234-1234-123456789abc";
    /** All commands below are fakes operating on this test's fresh ordinary files. */
    private static final class Rig implements AutoCloseable {
        final Path dir=Files.createTempDirectory("audio-plan-test-");
        final Path root=Files.createDirectory(dir.resolve("workshop")),bin=Files.createDirectory(dir.resolve("bin")),job=Files.createDirectory(root.resolve("stage-"+ID));
        final Path replacement=job.resolve("new.wav"),target=dir.resolve("target.wav"),old=Files.createDirectory(root.resolve("stage-previous")).resolve("old.wav");
        Rig(boolean previous)throws Exception{
            write(replacement,"new");write(old,"old");write(target,"factory");write(dir.resolve("active"),"1");write(dir.resolve("mountinfo"),"");
            if(previous){Files.createSymbolicLink(root.resolve("active-lock"),old);write(dir.resolve("mountinfo"),"1 0 0:1 "+old+" "+target+" rw - tmpfs tmpfs rw\n");}
            String common="#!/bin/sh\nset -eu\ncd \"$RIG\"\nfail_once() { if [ \"${FAIL:-}\" = \"$1\" ] && [ ! -e failed ]; then touch failed; return 0; fi; return 1; }\n";
            command("systemctl",common+"case $1 in stop) echo 0 > active; if fail_once stop; then exit 1; fi;; start) if fail_once start; then exit 1; fi; echo 1 > active;; is-active) test \"$(cat active)\" = 1;; *) exit 99;; esac\n");
            command("mount",common+"if fail_once mount; then exit 1; fi\nprintf '1 0 0:1 %s %s rw - tmpfs tmpfs rw\\n' \"$2\" \"$3\" > mountinfo\nif fail_once signal; then kill -TERM \"$PPID\"; fi\n");
            command("umount",common+"if fail_once umount; then exit 1; fi\n: > mountinfo\n");
        }
        void command(String name,String code)throws Exception{Path file=bin.resolve(name);write(file,code);assertTrue(file.toFile().setExecutable(true));}
        int run(String failure,boolean restore)throws Exception{
            String s=AudioPlan.build("/usr/share/chime-contents/V3_Geely_DNA_DHU/V3_Vehicle_Locked_Single.wav",restore?"":AudioPlan.ROOT+"/stage-"+ID+"/new.wav","cluster-cmw-audioplay.service","lock",AudioPlan.ROOT+"/stage-"+ID);
            s=s.replace(AudioPlan.ROOT,root.toString()).replace("/usr/share/chime-contents/V3_Geely_DNA_DHU/V3_Vehicle_Locked_Single.wav",target.toString()).replace("/proc/self/mountinfo",dir.resolve("mountinfo").toString());
            write(job.resolve("operation.sh"),s);
            ProcessBuilder builder=new ProcessBuilder("/bin/sh",job.resolve("operation.sh").toString()).redirectErrorStream(true).redirectOutput(dir.resolve("log").toFile());
            builder.environment().put("PATH",bin+":"+System.getenv("PATH"));builder.environment().put("RIG",dir.toString());builder.environment().put("FAIL",failure);
            Process p=builder.start();assertTrue(p.waitFor(10,TimeUnit.SECONDS));return p.exitValue();
        }
        String result()throws Exception{return read(job.resolve("result")).trim();}
        void assertPrevious(boolean previous)throws Exception{assertEquals("1",read(dir.resolve("active")).trim());String mounted=read(dir.resolve("mountinfo"));if(previous){assertTrue(mounted.contains(old.toString()));assertEquals(old,Files.readSymbolicLink(root.resolve("active-lock")));}else{assertTrue(mounted.isEmpty());assertFalse(Files.exists(root.resolve("active-lock")));}assertFalse(Files.exists(root.resolve("operation.lock")));}
        public void close()throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
    @Test public void successfulSwitchAndRestore()throws Exception{try(Rig r=new Rig(false)){assertEquals(0,r.run("",false));assertEquals("PASS",r.result());assertEquals(r.replacement,Files.readSymbolicLink(r.root.resolve("active-lock")));assertEquals(0,r.run("",true));r.assertPrevious(false);}}
    @Test public void everySwitchFailureRestoresPreviousOverlay()throws Exception{for(String event:new String[]{"stop","umount","mount","start","signal"})try(Rig r=new Rig(true)){assertNotEquals(event,0,r.run(event,false));assertEquals(event,"FAILED_RESTORED",r.result());r.assertPrevious(true);}}
    @Test public void startFailureRestoresFactoryAndService()throws Exception{try(Rig r=new Rig(false)){assertNotEquals(0,r.run("start",false));assertEquals("FAILED_RESTORED",r.result());r.assertPrevious(false);}}
    @Test public void restoreFailureRetainsPreviousOverlay()throws Exception{try(Rig r=new Rig(true)){assertNotEquals(0,r.run("start",true));assertEquals("FAILED_RESTORED",r.result());r.assertPrevious(true);}}
    @Test public void foreignOverlayIsNeverRemoved()throws Exception{try(Rig r=new Rig(false)){write(r.dir.resolve("mountinfo"),"1 0 0:1 /other-tool "+r.target+" rw - tmpfs tmpfs rw\n");assertNotEquals(0,r.run("",false));assertTrue(read(r.dir.resolve("mountinfo")).contains("/other-tool"));assertEquals("1",read(r.dir.resolve("active")).trim());}}
    @Test public void unknownTargetsRejected(){assertThrows(Exception.class,()->AudioPlan.build("/etc/shadow","","app_1.8.service","avas",AudioPlan.ROOT+"/stage-"+ID));}
}

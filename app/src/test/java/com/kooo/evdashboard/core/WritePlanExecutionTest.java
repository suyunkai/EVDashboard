package com.kooo.evdashboard.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Executes production ordering using mocked device commands and fresh regular files, never a block device. */
public class WritePlanExecutionTest {
    private static final String ID="12345678-1234-1234-1234-123456789abc";
    private static void write(Path p,String s)throws Exception{Files.write(p,s.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String read(Path p)throws Exception{return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}
    static final class Rig implements AutoCloseable {
        final Path dir=Files.createTempDirectory("writer-plan-test-"),bin=Files.createDirectory(dir.resolve("bin")),base=Files.createDirectory(dir.resolve("writer")),job=Files.createDirectories(base.resolve("jobs").resolve(ID)),devices=Files.createDirectories(dir.resolve("devices/by-name")),target=devices.resolve("cp_rootfs_b"),sys=Files.createDirectories(dir.resolve("sys/cp_rootfs_b/holders"));
        String before,candidate;Rig()throws Exception{
            byte[] bytes=new byte[1024];Arrays.fill(bytes,(byte)1);Files.write(target,bytes);before=FilesEx.digest(target.toFile());Arrays.fill(bytes,(byte)2);Files.write(job.resolve("candidate.img"),bytes);candidate=FilesEx.digest(job.resolve("candidate.img").toFile());
            for(String n:new String[]{"cp_rootfs_a","cp_metadata","cp_metadata_bak"})write(devices.resolve(n),"untouched-"+n);
            write(dir.resolve("slot"),"cp:b");write(dir.resolve("boot"),ID);write(sys.getParent().resolve("dev"),"1:2");write(dir.resolve("mountinfo"),"");
            command("id","echo 0");command("readlink","printf '%s\\n' \"$2\"");command("blockdev","case $1 in --getsize64) echo 1024;; --getro) echo \"${READONLY:-0}\";; --flushbufs) :;; *) exit 99;; esac");command("stat","/usr/bin/stat -f %z \"$3\"");command("df","printf 'Filesystem blocks used available\\nmock 9999999 1 9999998\\n'");command("sha256sum","exec /usr/bin/shasum -a 256 \"$@\"");command("sync",":");
            command("dd","src=''; dst=''; for arg in \"$@\"; do case $arg in if=*) src=${arg#if=};; of=*) dst=${arg#of=};; esac; done; printf '%s -> %s\\n' \"$src\" \"$dst\" >> \"$RIG/writes\"; cp \"$src\" \"$dst\"; if [ \"${FAIL_READBACK:-0}\" = 1 ] && [ \"$dst\" = readback.img ]; then printf x >> \"$dst\"; fi");
        }
        void command(String name,String code)throws Exception{Path p=bin.resolve(name);write(p,"#!/bin/sh\nset -eu\n"+code+"\n");assertTrue(p.toFile().setExecutable(true));}
        int run(String failure)throws Exception{
            String s=WritePlan.build("cp:b","/dev/block/by-name/cp_rootfs_b",ID,"/data/local/evdashboard/jobs/"+ID,candidate,before);
            s=s.replace("/data/local/evdashboard",base.toString()).replace("/dev/block/by-name",devices.toString()).replace("/sys/class/block",dir.resolve("sys").toString()).replace("/proc/boot_slot",dir.resolve("slot").toString()).replace("/proc/sys/kernel/random/boot_id",dir.resolve("boot").toString()).replace("/proc/self/mountinfo",dir.resolve("mountinfo").toString()).replace("test -b \"$target\"","test -f \"$target\"").replace("1073741824","1024");
            Path script=job.resolve("test-writer.sh");write(script,s);ProcessBuilder b=new ProcessBuilder("/bin/sh",script.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("log").toFile());b.environment().put("PATH",bin+":"+System.getenv("PATH"));b.environment().put("RIG",dir.toString());b.environment().put("READONLY",failure.equals("ro")?"1":"0");b.environment().put("FAIL_READBACK",failure.equals("readback")?"1":"0");Process p=b.start();if(!p.waitFor(60,TimeUnit.SECONDS)){p.destroyForcibly();throw new AssertionError("Mock writer timeout");}return p.exitValue();
        }
        void noWrite()throws Exception{assertFalse(Files.exists(dir.resolve("writes")));assertEquals(before,FilesEx.digest(target.toFile()));assertFalse(Files.exists(base.resolve("writer.lock")));assertEquals("FAILED_BEFORE_WRITE",read(job.resolve("result")).trim());}
        public void close()throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
    @Test public void successfulWriteRequiresFullReadbackAndUntouchedComparison()throws Exception{try(Rig r=new Rig()){int code=r.run("");assertEquals(read(r.dir.resolve("log")),0,code);assertEquals("PASS",read(r.job.resolve("result")).trim());assertEquals(r.candidate,FilesEx.digest(r.target.toFile()));assertEquals(r.candidate,FilesEx.digest(r.job.resolve("readback.img").toFile()));assertEquals(read(r.job.resolve("untouched.before")),read(r.job.resolve("untouched.after")));assertFalse(Files.exists(r.base.resolve("writer.lock")));}}
    @Test public void slotBootReadonlyHolderAndMountFailuresNeverWrite()throws Exception{for(String problem:new String[]{"slot","boot","ro","holder","mount"})try(Rig r=new Rig()){if(problem.equals("slot"))write(r.dir.resolve("slot"),"cp:a");if(problem.equals("boot"))write(r.dir.resolve("boot"),"different");if(problem.equals("holder"))write(r.sys.resolve("holder"),"held");if(problem.equals("mount"))write(r.dir.resolve("mountinfo"),"1 0 1:2 / /mounted rw - ext4 fake rw\n");assertNotEquals(problem,0,r.run(problem));r.noWrite();}}
    @Test public void changedCandidateNeverWrites()throws Exception{try(Rig r=new Rig()){Files.write(r.job.resolve("candidate.img"),new byte[1024]);assertNotEquals(0,r.run(""));r.noWrite();}}
    @Test public void changedLivePartitionNeverWrites()throws Exception{try(Rig r=new Rig()){Files.write(r.target,new byte[1024]);String changed=FilesEx.digest(r.target.toFile());assertNotEquals(0,r.run(""));assertFalse(Files.exists(r.dir.resolve("writes")));assertEquals(changed,FilesEx.digest(r.target.toFile()));}}
    @Test public void readbackMismatchRetainsFailureLockAndNeverSaysPass()throws Exception{try(Rig r=new Rig()){assertNotEquals(0,r.run("readback"));assertEquals("FAIL",read(r.job.resolve("result")).trim());assertTrue(Files.exists(r.job.resolve("write.started")));assertTrue(Files.exists(r.base.resolve("writer.lock")));}}
}

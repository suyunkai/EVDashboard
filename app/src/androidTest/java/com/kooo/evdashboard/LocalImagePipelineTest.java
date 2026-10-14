package com.kooo.evdashboard;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.content.Context;
import com.kooo.evdashboard.core.*;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;

/** Ordinary application files only; one emulator-only root read. No CP detection, SSH, flash or reboot. */
@RunWith(AndroidJUnit4.class)
public class LocalImagePipelineTest {
    @Test public void detachedWorkerSurvivesLocalAdbClose()throws Exception{
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE.contains("ranchu"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        RootAccess root=new RootAccess(app);root.connect();
        String job="/data/local/evdashboard/jobs/"+UUID.randomUUID();
        String script="#!/system/bin/sh\ncd "+FilesEx.quote(job)+"\necho $$ > pid\nsleep 2\necho ORDINARY_FILE_ONLY > result\n";
        try{
            root.run("mkdir -p "+FilesEx.quote(job)+"; printf %s "+FilesEx.quote(script)+" > "+FilesEx.quote(job+"/writer.sh"),15);
            assertTrue(root.run(DetachedLaunch.writer(job),20).contains("WRITER_ACKNOWLEDGED"));
            Thread.sleep(3000);
            assertEquals("ORDINARY_FILE_ONLY",root.run("cat "+FilesEx.quote(job+"/result"),10).trim());
        }finally{
            // Exactly the fresh ordinary-file test directory, never a live writer job.
            root.run("rm -f "+FilesEx.quote(job+"/writer.sh")+" "+FilesEx.quote(job+"/writer.log")+" "+FilesEx.quote(job+"/pid")+" "+FilesEx.quote(job+"/result")+"; rmdir "+FilesEx.quote(job),10);
        }
    }
    @Test public void rootNamespaceAccessToOrdinaryAppFile()throws Exception {
        // Runner is invoked only after the host verifies the named emulator's identity.
        org.junit.Assume.assumeTrue("NewCar_E245_N5".equals(android.os.Build.MODEL)||android.os.Build.HARDWARE.contains("ranchu"));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File probe=new File(app.getExternalFilesDir(null),"namespace-test-"+UUID.randomUUID()+".txt");
        File copy=new File(app.getExternalFilesDir(null),"namespace-copy-"+UUID.randomUUID()+".bin");
        try{FilesEx.atomicText(probe,"namespace-"+UUID.randomUUID());RootAccess root=new RootAccess(app);root.connect();String path=root.sharedFile(probe);assertEquals(FilesEx.digest(probe),root.run("sha256sum "+FilesEx.quote(path),15).split("\\s+")[0]);assertTrue(copy.createNewFile());root.copyReadOnly(path,copy,probe.length());assertEquals(probe.length(),copy.length());assertEquals(FilesEx.digest(probe),FilesEx.digest(copy));}
        finally{Files.deleteIfExists(probe.toPath());Files.deleteIfExists(copy.toPath());}
    }
    @Test public void fullLocalGenerateAndReinspect()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root=new File(app.getCacheDir(),"pipeline-test-"+UUID.randomUUID());
        ImageLibrary library=new ImageLibrary(root);WorkshopEngine engine=new WorkshopEngine(app,library,s->android.util.Log.i("WorkshopTest",s));
        ImageRecord original=library.create("IMPORT","合成测试镜像（不是车辆固件）");File source=library.file(original);
        byte[] payload;try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("synthetic.erofs")){ByteArrayOutputStream out=new ByteArrayOutputStream();FilesEx.transfer(in,out);payload=out.toByteArray();}
        try(RandomAccessFile file=new RandomAccessFile(source,"rw")){
            ByteBuffer h=ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);h.putInt(0,0xaa640001).putInt(4,0x12345678);byte[] type={(byte)0x8a,0x50,0x46,0x7f,(byte)0x89,(byte)0x9d,0x4a,(byte)0xb3,(byte)0x9e,(byte)0x97,(byte)0x8f,(byte)0xe0,(byte)0xed,0x6d,0x1d,(byte)0xcb};h.position(16);h.put(type);h.putLong(32,4096).putLong(40,payload.length).putLong(72,4096+payload.length);file.write(h.array());file.write(payload);file.seek(1024*1024);file.write("opaque-test-trailer".getBytes(java.nio.charset.StandardCharsets.US_ASCII));file.setLength(FipImage.PARTITION_BYTES);
        }
        try {
            engine.inspect(original);assertTrue(original.validated);assertEquals("DISABLED",original.socket);assertEquals("SET",original.password);String hash=original.sha;
            ImageRecord generated=engine.generate(original,true,true);assertTrue(generated.validated);assertEquals("ENABLED",generated.socket);assertEquals("EMPTY",generated.password);assertEquals(hash,FilesEx.digest(source));
            engine.verifyGenerated(generated);assertTrue(library.intact(generated));assertEquals(2,library.list().size());
            String sourceId=generated.sourceId;generated.sourceId=UUID.randomUUID().toString();library.save(generated);
            assertThrows(Exception.class,()->engine.verifyGenerated(generated));assertFalse(library.get(generated.id).validated);
            generated.sourceId=sourceId;library.save(generated);engine.verifyGenerated(generated);assertTrue(generated.validated);
            // A changed file must not be silently re-baselined.
            try(RandomAccessFile corrupt=new RandomAccessFile(library.file(generated),"rw")){corrupt.seek(1024*1024);corrupt.write(99);}
            assertThrows(Exception.class,()->engine.verifyGenerated(generated));assertFalse(generated.validated);
        }finally{
            // Only this method's fresh UUID-owned fixture tree, never Documents or backups.
            try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(root.toPath())){for(java.nio.file.Path p:paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new))Files.delete(p);}
        }
    }
}

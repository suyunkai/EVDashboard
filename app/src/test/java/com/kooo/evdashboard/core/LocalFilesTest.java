package com.kooo.evdashboard.core;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LocalFilesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void listsFoldersFirstAndFiltersFiles() throws Exception {
        File root=temp.newFolder("volume");File dir=new File(root,"Z folder");assertTrue(dir.mkdir());
        for(String name:new String[]{"test.img","firmware.BIN","music.wav","note.txt"})assertTrue(new File(root,name).createNewFile());
        List<File> images=LocalFiles.list(root,root,false,false);
        assertEquals(3,images.size());assertEquals(dir.getCanonicalFile(),images.get(0));
        assertEquals(2,LocalFiles.list(root,root,true,false).size());
        assertEquals(Collections.singletonList(dir.getCanonicalFile()),LocalFiles.list(root,root,false,true));
    }
    @Test public void rejectsEscapeAndSymbolicLinks() throws Exception {
        File root=temp.newFolder("volume"),outside=temp.newFile("outside.img");
        File link=new File(root,"link.img");Files.createSymbolicLink(link.toPath(),outside.toPath());
        assertThrows(Exception.class,()->LocalFiles.within(root,outside));
        assertThrows(Exception.class,()->LocalFiles.within(root,link));
        assertTrue(LocalFiles.list(root,root,false,false).isEmpty());
    }
    @Test public void refusesOverwriteAndProtectedLibrary() throws Exception {
        File root=temp.newFolder("volume"),library=new File(root,"EVDashboard");assertTrue(library.mkdir());
        File existing=new File(root,"copy.img");assertTrue(existing.createNewFile());
        assertThrows(Exception.class,()->LocalFiles.destination(root,root,"copy.img",library));
        assertThrows(Exception.class,()->LocalFiles.destination(root,library,"new.img",library));
        assertEquals(new File(root,"safe.img").getCanonicalFile(),LocalFiles.destination(root,root,"safe.img",library));
        assertFalse(new File(root,"safe.img").exists());
    }
    @Test public void rejectsInvalidNames() throws Exception {
        File root=temp.newFolder("volume");
        for(String name:new String[]{"","..",".","../outside.img","folder/name.img","path\\name.img","a\n.img"})
            assertThrows(name,Exception.class,()->LocalFiles.destination(root,root,name,null));
    }
    @Test public void transferReportsBytesAndRejectsTruncation() throws Exception {
        byte[] source=new byte[2000000];new Random(7).nextBytes(source);
        ByteArrayOutputStream output=new ByteArrayOutputStream();List<Long> counts=new ArrayList<>();
        assertEquals(source.length,FilesEx.transfer(new ByteArrayInputStream(source),output,source.length,counts::add));
        assertArrayEquals(source,output.toByteArray());assertEquals(Long.valueOf(source.length),counts.get(counts.size()-1));
        assertThrows(Exception.class,()->FilesEx.transfer(new ByteArrayInputStream(source),new ByteArrayOutputStream(),source.length+1,n->{}));
        assertThrows(Exception.class,()->FilesEx.transfer(new ByteArrayInputStream(source),new ByteArrayOutputStream(),source.length-1,n->{}));
    }
    @Test public void digestAndCopyReportCompleteBytesWithoutChangingSource() throws Exception {
        File source=temp.newFile("source.bin"),target=new File(temp.getRoot(),"target.bin");
        Files.write(source.toPath(),new byte[2048]);List<Long> counts=new ArrayList<>();
        String hash=FilesEx.digest(source,counts::add);assertEquals(Long.valueOf(2048),counts.get(counts.size()-1));
        try(InputStream in=new FileInputStream(source)){assertEquals(2048,FilesEx.copy(in,target,2048,counts::add));}
        assertEquals(hash,FilesEx.digest(target));assertEquals(hash,FilesEx.digest(source));
    }
}

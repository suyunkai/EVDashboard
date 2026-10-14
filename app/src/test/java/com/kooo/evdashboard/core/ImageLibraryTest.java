package com.kooo.evdashboard.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.util.Set;
import static org.junit.Assert.*;

public class ImageLibraryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    ImageLibrary library;
    @Before public void setup()throws Exception{library=new ImageLibrary(temp.newFolder());}
    ImageRecord original(String device,String slot,String build)throws Exception{
        ImageRecord r=library.create("DEVICE","备份");r.category="ORIGINAL";r.device=device;r.slot=slot;r.build=build;
        Files.write(library.file(r).toPath(),new byte[]{1,2,3,4});r.bytes=4;r.sha=FilesEx.digest(library.file(r));r.validated=true;library.save(r);return r;
    }
    @Test public void lastOriginalCannotBeDeleted()throws Exception{
        ImageRecord r=original("car","cp:b","version");assertThrows(Exception.class,()->library.delete(r.id,Set.of(),2));assertTrue(library.intact(r));
    }
    @Test public void originalNeedsTwoConfirmationsAndIndependentCopy()throws Exception{
        ImageRecord a=original("car","cp:b","version"),b=original("car","cp:b","version");
        assertThrows(Exception.class,()->library.delete(a.id,Set.of(),1));library.delete(a.id,Set.of(),2);assertTrue(library.intact(b));assertEquals(1,library.list().size());
        assertThrows(Exception.class,()->library.delete(b.id,Set.of(),2));
    }
    @Test public void corruptPeerDoesNotCount()throws Exception{
        ImageRecord a=original("car","cp:b","version"),b=original("car","cp:b","version");Files.write(library.file(b).toPath(),new byte[]{0,0,0,0});
        assertEquals(0,library.remainingOriginals(a));assertThrows(Exception.class,()->library.delete(a.id,Set.of(),2));
    }
    @Test public void otherCarSlotBuildDoesNotCount()throws Exception{
        ImageRecord a=original("car","cp:b","version");original("other","cp:b","version");original("car","cp:a","version");original("car","cp:b","new");
        assertEquals(0,library.remainingOriginals(a));
    }
    @Test public void ordinaryNeedsOneConfirmationButCannotBeInUse()throws Exception{
        ImageRecord r=original("car","cp:b","version");r.category="MODIFIED";library.save(r);
        assertThrows(Exception.class,()->library.delete(r.id,Set.of(),0));assertThrows(Exception.class,()->library.delete(r.id,Set.of(r.id),1));
        library.delete(r.id,Set.of(),1);assertTrue(library.list().isEmpty());
    }
    @Test public void modifiedPeerDoesNotProtectOriginal()throws Exception{
        ImageRecord a=original("car","cp:b","version"),b=original("car","cp:b","version");b.category="MODIFIED";library.save(b);assertEquals(0,library.remainingOriginals(a));
    }
    @Test public void pathTraversalIsRejected()throws Exception{ImageRecord r=new ImageRecord();r.id="../../bad";assertThrows(Exception.class,()->library.file(r));}
    @Test public void classificationCannotDemoteOriginal()throws Exception{ImageRecord r=original("car","cp:b","version");assertThrows(Exception.class,()->library.classify(r.id,"MODIFIED"));assertTrue(library.get(r.id).original());}
    @Test public void invalidExportCannotBeDeclaredValidOriginal()throws Exception{ImageRecord r=library.create("DEVICE","unfinished");assertThrows(Exception.class,()->library.classify(r.id,"ORIGINAL"));}
    @Test public void generatedImageProtectsItsSource()throws Exception{ImageRecord r=original("car","cp:b","version");r.category="MODIFIED";library.save(r);ImageRecord child=library.create("GENERATED","child");child.sourceId=r.id;child.validated=true;library.save(child);assertThrows(Exception.class,()->library.delete(r.id,Set.of(),1));assertTrue(library.intact(r));}
}

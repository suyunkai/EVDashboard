package com.kooo.evdashboard.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class WritePlanTest {
    String uuid="12345678-1234-1234-1234-123456789abc",sha="a".repeat(64);
    String script(String slot)throws Exception{return WritePlan.build(slot,"/dev/block/sdd4",uuid,"/data/local/evdashboard/jobs/"+uuid,sha,"b".repeat(64));}
    @Test public void onlySelectedCpSlotCanBeTarget()throws Exception{String a=script("cp:a"),b=script("cp:b");assertTrue(a.contains("target=$(readlink -f /dev/block/by-name/cp_rootfs_a)"));assertTrue(b.contains("target=$(readlink -f /dev/block/by-name/cp_rootfs_b)"));assertFalse(b.contains("reboot"));assertFalse(b.contains("bootctl"));assertEquals(1,b.split("dd if=candidate.img of=",-1).length-1);}
    @Test public void safeguardsPrecedeWriteAndReadbackIsMandatory()throws Exception{String s=script("cp:b");assertTrue(s.indexOf("/holders")<s.indexOf("echo WRITING"));assertTrue(s.indexOf("untouched.before")<s.indexOf("echo WRITING"));assertTrue(s.contains("cmp candidate.img readback.img"));assertTrue(s.contains("cmp untouched.before untouched.after"));assertTrue(s.contains("FAILED_BEFORE_WRITE"));assertTrue(s.indexOf("cmp candidate.img readback.img")<s.indexOf("echo PASS"));}
    @Test public void arbitraryInputsRejected(){assertThrows(Exception.class,()->script("_a"));assertThrows(Exception.class,()->WritePlan.build("cp:a","/dev/block/../bad",uuid,"/data/local/evdashboard/jobs/"+uuid,sha,sha));assertThrows(Exception.class,()->WritePlan.build("cp:a","/dev/block/sdd3",uuid,"/tmp/job",sha,sha));}
    @Test public void identicalWriteIsDeniedByDefaultButExplicitTestIsAllowed()throws Exception{assertThrows(Exception.class,()->WritePlan.checkContentMode(sha,sha,false));WritePlan.checkContentMode(sha,sha,true);}
    @Test public void identicalTestCannotAuthorizeAnUnexpectedChangedImage()throws Exception{assertThrows(Exception.class,()->WritePlan.checkContentMode(sha,"b".repeat(64),true));WritePlan.checkContentMode(sha,"b".repeat(64),false);}
}

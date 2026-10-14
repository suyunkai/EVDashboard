package com.kooo.evdashboard.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class DetachedLaunchTest {
    @Test public void ignoresHangupBeforeForkAndWaitsForWorker()throws Exception{
        String plan=DetachedLaunch.writer("/data/local/evdashboard/jobs/12345678-1234-1234-1234-123456789abc");
        assertTrue(plan.indexOf("trap '' HUP")<plan.indexOf("setsid toybox nohup"));
        assertTrue(plan.contains("/pid"));assertTrue(plan.contains("/result"));
        assertTrue(plan.contains("exit 48"));assertTrue(plan.contains("sleep 0.1"));
        assertTrue(plan.contains("</dev/null"));
        assertFalse(plan.contains("dd "));
    }
    @Test public void refusesArbitraryJobPath(){
        assertThrows(Exception.class,()->DetachedLaunch.writer("/data/local/tmp"));
        assertThrows(Exception.class,()->DetachedLaunch.writer("/data/local/evdashboard/jobs/../../tmp"));
    }
}

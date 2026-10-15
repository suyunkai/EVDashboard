package com.kooo.evdashboard.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class CpAddressTest {
    @Test public void acceptsNormalVehicleAddresses() throws Exception {
        assertEquals(CpAddress.DEFAULT, CpAddress.parse(" 198.18.44.2 "));
        assertEquals("192.168.10.20", CpAddress.parse("192.168.10.20"));
        assertEquals("10.0.0.1", CpAddress.parse("10.0.0.1"));
    }
    @Test public void rejectsUrlsPortsHostnamesAndInvalidAddresses() {
        for (String value : new String[]{null,"","cp.local","ssh://198.18.44.2","198.18.44.2:22","198.18.44.999","198.18.44","198.18.44.","198.018.44.2","127.0.0.1","0.0.0.0","224.1.1.1","255.255.255.255","1.2.3.4;id","1.2.3.-1"})
            assertThrows(value, Exception.class, () -> CpAddress.parse(value));
    }
}

package com.kooo.evdashboard.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SoundRecoveryStateTest {
    private static final String BOOT = "12345678-1234-1234-1234-123456789abc", SHA = "a".repeat(64);
    private static final String TARGET = SoundCatalog.CHIMES + "V3_Vehicle_Locked_Single.wav", SOURCE = "/instrument-workshop/stage-" + BOOT + "/" + SHA + ".wav";
    private SoundRecoveryState state(Map<String, String> mounts) throws Exception {return new SoundRecoveryState("boot-7", BOOT, SHA, SHA, SHA, mounts);}
    @Test public void persistedVerificationRequiresSameAndroidCpAddressRulesAndCatalog() throws Exception {
        Map<String, String> mounts = Map.of(TARGET, SOURCE); SoundRecoveryState saved = SoundRecoveryState.parse(state(mounts).json());
        assertTrue(saved.matches("boot-7", BOOT, SHA, SHA, SHA, mounts));
        assertFalse(saved.matches("boot-8", BOOT, SHA, SHA, SHA, mounts));
        assertFalse(saved.matches("boot-7", "00000000-0000-0000-0000-000000000000", SHA, SHA, SHA, mounts));
        assertFalse(saved.matches("boot-7", BOOT, "b".repeat(64), SHA, SHA, mounts));
        assertFalse(saved.matches("boot-7", BOOT, SHA, "b".repeat(64), SHA, mounts));
        assertFalse(saved.matches("boot-7", BOOT, SHA, SHA, "b".repeat(64), mounts));
        assertThrows(Exception.class, () -> SoundRecoveryState.parse(saved.json().replace("\"version\":1", "\"version\":2")));
        assertThrows(Exception.class, () -> SoundRecoveryState.parse("{}"));
    }
    @Test public void comparesEveryMountSourceAndTargetIncludingParentOverlays() throws Exception {
        SoundRecoveryState saved = state(Map.of(TARGET, SOURCE));
        assertFalse(saved.matches("boot-7", BOOT, SHA, SHA, SHA, Map.of()));
        assertFalse(saved.matches("boot-7", BOOT, SHA, SHA, SHA, Map.of(TARGET, SOURCE.replace(SHA, "b".repeat(64)))));
        assertFalse(saved.matches("boot-7", BOOT, SHA, SHA, SHA, Map.of(SoundCatalog.CHIMES + "other.wav", SOURCE)));
        assertFalse(saved.matches("boot-7", BOOT, SHA, SHA, SHA, Map.of(TARGET, SOURCE, "/usr", "/foreign")));
    }
    @Test public void probeIgnoresUnrelatedMountsButRejectsStackedOrInvalidResponses() throws Exception {
        SoundRecoveryState.Probe probe = new SoundRecoveryState.Probe("BOOT\t" + BOOT + "\nMOUNT\t/data/test\t/irrelevant\nMOUNT\t" + TARGET + "\t" + SOURCE + "\n");
        assertEquals(Map.of(TARGET, SOURCE), probe.mounts); assertEquals(BOOT, probe.boot);
        String header = "BOOT\t" + BOOT + "\n", row = "MOUNT\t" + TARGET + "\t" + SOURCE + "\n";
        assertThrows(Exception.class, () -> new SoundRecoveryState.Probe(header + row + row));
        assertThrows(Exception.class, () -> new SoundRecoveryState.Probe(header + header));
        assertThrows(Exception.class, () -> new SoundRecoveryState.Probe("BOOT\tinvalid\n"));
        assertThrows(Exception.class, () -> new SoundRecoveryState.Probe(header + "unknown\n"));
        assertTrue(new SoundRecoveryState.Probe(header + "MOUNT\t/usr\t/foreign\n").mounts.containsKey("/usr"));
    }
    @Test public void catalogMetadataIsOrderIndependentAndDetectsChangedResources() throws Exception {
        SoundStore.Entry a = new SoundStore.Entry(TARGET, SHA, SHA, "OWN"), b = new SoundStore.Entry(SoundCatalog.CHIMES + "other.wav", SHA, SHA, "NONE");
        assertEquals(SoundRecoveryState.catalogHash(List.of(a,b)), SoundRecoveryState.catalogHash(List.of(b,a)));
        assertNotEquals(SoundRecoveryState.catalogHash(List.of(a)), SoundRecoveryState.catalogHash(List.of(new SoundStore.Entry(TARGET, SHA, SHA, "NONE"))));
        assertThrows(Exception.class, () -> SoundRecoveryState.catalogHash(List.of(a,a)));
    }
}

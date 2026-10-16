package com.kooo.evdashboard.core;

import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

public class SoundSyncTest {
    private File folder;
    private SoundStore store;
    private byte[] wave;
    private String sha;
    @Before public void setup() throws Exception {
        folder = Files.createTempDirectory("sound-sync-").toFile();
        store = new SoundStore(folder, new SoundCatalog("[]"));
        wave = SoundWave.encode(new short[]{1000, -1000}, 48000, 1);
        sha = FilesEx.hex(MessageDigest.getInstance("SHA-256").digest(wave));
    }
    @After public void cleanup() throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(folder.toPath())) {for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);}
    }
    @Test public void downloadsOnlyMissingAudioAndReusesVerifiedContentAcrossPaths() throws Exception {
        int[] reads = {0}; SoundSync.Reader remote = path -> {reads[0]++; return wave;};
        assertEquals(sha, SoundSync.cache(store, "/first.wav", sha, remote)); assertEquals(1, reads[0]);
        assertEquals(sha, SoundSync.cache(store, "/other.wav", sha, remote)); assertEquals(1, reads[0]);
        assertArrayEquals(wave, store.bytes(sha));
    }
    @Test public void changedDuringDownloadCannotPublishAnIncorrectSnapshot() throws Exception {
        assertThrows(Exception.class, () -> SoundSync.cache(store, "/sound.wav", "a".repeat(64), path -> wave));
        assertFalse(new File(folder, "assets/" + sha + ".wav").exists());
    }
    @Test public void corruptCacheIsNeverSilentlyReused() throws Exception {
        store.put(wave); Files.write(store.asset(sha).toPath(), new byte[]{0}); int[] reads = {0};
        assertThrows(Exception.class, () -> SoundSync.cache(store, "/sound.wav", sha, path -> {reads[0]++; return wave;}));
        assertEquals(1, reads[0]);
    }
    @Test public void manifestRejectsMissingDuplicateUnexpectedAndMalformedPaths() throws Exception {
        List<String> required = Arrays.asList("/tick.wav", "/tock.wav"), optional = Collections.singletonList("/original.wav");
        String tick = sha + "  /tick.wav\n", tock = sha + "  /tock.wav\n";
        assertEquals(2, SoundSync.parse(tick + tock, required, optional).size());
        assertEquals(3, SoundSync.parse(tick + tock + sha + "  /original.wav\n", required, optional).size());
        for (String bad : new String[]{tick, tick + tock + tick, tick + tock + sha + "  /foreign.wav\n", "bad  /tick.wav\n"})
            assertThrows(Exception.class, () -> SoundSync.parse(bad, required, optional));
    }
    @Test public void batchScriptChecksEveryRequiredFileAndAllowsMissingOriginals() throws Exception {
        File current = new File(folder, "tick ' quoted.wav"), optional = new File(folder, "missing.wav"); Files.write(current.toPath(), wave);
        List<String> paths = Collections.singletonList(current.getAbsolutePath()), originals = Collections.singletonList(optional.getAbsolutePath());
        // macOS stat differs from CP Linux; the test shim implements only CP's stat -c %s.
        File stat = new File(folder, "stat"); Files.write(stat.toPath(), "#!/bin/sh\nwc -c < \"$3\"\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); assertTrue(stat.setExecutable(true));
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", SoundSync.script(paths, originals));
        builder.environment().put("PATH", folder.getAbsolutePath() + ":" + System.getenv("PATH"));
        Process process = builder.start(); String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(new String(process.getErrorStream().readAllBytes()), 0, process.waitFor());
        assertEquals(sha, SoundSync.parse(output, paths, originals).get(current.getAbsolutePath()));
        Files.delete(current.toPath()); process = builder.start(); process.getInputStream().readAllBytes(); assertNotEquals(0, process.waitFor());
    }
    @Test public void resultVerificationRequiresEveryExpectedTargetIncludingRestores() throws Exception {
        Map<String, String> expected = Collections.singletonMap("/tick.wav", sha);
        SoundSync.verify(expected, Collections.singletonList(new SoundStore.Entry("/tick.wav", sha, sha, "NONE")));
        assertThrows(Exception.class, () -> SoundSync.verify(expected, Collections.emptyList()));
        assertThrows(Exception.class, () -> SoundSync.verify(expected, Collections.singletonList(new SoundStore.Entry("/tick.wav", sha, "a".repeat(64), "OWN"))));
    }
}

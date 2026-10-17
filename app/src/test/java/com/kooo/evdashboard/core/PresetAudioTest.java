package com.kooo.evdashboard.core;

import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

public class PresetAudioTest {
    private static byte[] gzip(byte[] bytes) throws Exception {ByteArrayOutputStream out = new ByteArrayOutputStream(); try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {gzip.write(bytes);} return out.toByteArray();}
    @Test public void everyBundledWavSurvivesLosslessPackagingWithSameDigestAndFormat() throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(Path.of("src/main/assets/presets"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".wav")).toArray(Path[]::new)) {
                byte[] bytes = Files.readAllBytes(path); String sha = FilesEx.digest(path.toFile());
                assertArrayEquals(bytes, PresetAudio.read(new ByteArrayInputStream(bytes), false, sha));
                assertArrayEquals(bytes, PresetAudio.read(new ByteArrayInputStream(gzip(bytes)), true, sha));
            }
        }
    }
    @Test public void damagedTruncatedOversizedOrWrongDigestFailsBeforeUse() throws Exception {
        Path path;
        try (java.util.stream.Stream<Path> paths = Files.walk(Path.of("src/main/assets/presets"))) {path = paths.filter(p -> p.toString().endsWith(".wav")).findFirst().orElseThrow();}
        byte[] bytes = Files.readAllBytes(path), compressed = gzip(bytes); String sha = FilesEx.digest(path.toFile());
        assertThrows(Exception.class, () -> PresetAudio.read(new ByteArrayInputStream(compressed), true, "0".repeat(64)));
        assertThrows(Exception.class, () -> PresetAudio.read(new ByteArrayInputStream(java.util.Arrays.copyOf(compressed, compressed.length - 3)), true, sha));
        compressed[compressed.length - 8] ^= 1;
        assertThrows(Exception.class, () -> PresetAudio.read(new ByteArrayInputStream(compressed), true, sha));
        byte[] large = gzip(new byte[SoundWave.MAX_BYTES + 1]);
        assertThrows(Exception.class, () -> PresetAudio.read(new ByteArrayInputStream(large), true, sha));
    }
}

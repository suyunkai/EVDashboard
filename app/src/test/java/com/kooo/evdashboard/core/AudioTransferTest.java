package com.kooo.evdashboard.core;

import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class AudioTransferTest {
    private static final String STAGE = AudioPlan.ROOT + "/stage-12345678-1234-1234-1234-123456789abc";
    private static final String TARGET = SoundCatalog.CHIMES + "V3_Vehicle_Locked_Single.wav";
    @Test public void archiveExtractsByteExactlyAndDeduplicatesByDigest() throws Exception {
        Path dir = Files.createTempDirectory("audio-transfer-");
        try {
            Map<String, File> files = new LinkedHashMap<>();
            for (int size : new int[]{1, 512, 513, 8193}) {
                byte[] bytes = new byte[size]; new Random(size).nextBytes(bytes);
                Path source = dir.resolve("source-" + size); Files.write(source, bytes); files.put(FilesEx.digest(source.toFile()), source.toFile());
            }
            Path tar = dir.resolve("audio.tar"), out = Files.createDirectory(dir.resolve("out"));
            try (OutputStream stream = new java.util.zip.GZIPOutputStream(Files.newOutputStream(tar))) {assertEquals(9219, AudioTransfer.write(files, stream));}
            Process p = new ProcessBuilder("tar", "-xzf", tar.toString(), "-C", out.toString()).start();
            assertTrue(p.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, p.exitValue());
            assertEquals(files.size(), out.toFile().list().length);
            StringBuilder hashes = new StringBuilder();
            for (Map.Entry<String, File> entry : files.entrySet()) {
                assertArrayEquals(Files.readAllBytes(entry.getValue().toPath()), Files.readAllBytes(out.resolve(entry.getKey() + ".wav")));
                hashes.append(entry.getKey()).append("  ").append(STAGE).append('/').append(entry.getKey()).append(".wav\n");
            }
            AudioTransfer.verify(hashes.toString(), STAGE, files.keySet());
            assertThrows(Exception.class, () -> AudioTransfer.verify(hashes.toString().replaceFirst(".wav", ".bad"), STAGE, files.keySet()));
            assertThrows(Exception.class, () -> AudioTransfer.verify("", STAGE, files.keySet()));
            assertThrows(Exception.class, () -> AudioTransfer.verify(hashes.toString() + hashes, STAGE, files.keySet()));
        } finally {remove(dir);}
    }
    @Test public void rejectsUnsafeNamesSymlinksAndChangedSources() throws Exception {
        Path dir = Files.createTempDirectory("audio-invalid-");
        try {
            Path file = AudioTransferTest.write(dir.resolve("source"), "original"); String sha = FilesEx.digest(file.toFile());
            AudioTransferTest.write(file, "changed");
            assertThrows(Exception.class, () -> AudioTransfer.write(Map.of(sha, file.toFile()), new ByteArrayOutputStream()));
            assertThrows(Exception.class, () -> AudioTransfer.write(Map.of("../bad", file.toFile()), new ByteArrayOutputStream()));
            Path link = Files.createSymbolicLink(dir.resolve("link"), file);
            assertThrows(Exception.class, () -> AudioTransfer.write(Map.of(FilesEx.digest(file.toFile()), link.toFile()), new ByteArrayOutputStream()));
            assertThrows(Exception.class, () -> AudioTransfer.receive(STAGE + "/../other"));
        } finally {remove(dir);}
    }
    @Test public void cpLocalBackupsAreVerifiedImmutableAndNeverCopyAnOverlayAsOriginal() throws Exception {
        Path dir = Files.createTempDirectory("audio-backup-");
        try {
            Path root = Files.createDirectory(dir.resolve("workshop")); Files.createDirectory(root.resolve("originals"));
            Path stage = Files.createDirectory(root.resolve(STAGE.substring(STAGE.lastIndexOf('/') + 1)));
            Path source = AudioTransferTest.write(dir.resolve("factory.wav"), "factory");
            String original = AudioPlan.ROOT + "/originals/sound-" + "a".repeat(24) + ".wav", hash = FilesEx.hashText("factory");
            String script = AudioTransfer.backups(List.of(new AudioTransfer.Backup(original, TARGET, hash)), STAGE)
                .replace(AudioPlan.ROOT, root.toString()).replace(TARGET, source.toString());
            assertEquals(0, run(dir, script));
            Path backup = root.resolve("originals/sound-" + "a".repeat(24) + ".wav");
            assertEquals(hash, FilesEx.digest(backup.toFile())); AudioTransferTest.write(source, "replacement");
            assertEquals(0, run(dir, script)); assertEquals(hash, FilesEx.digest(backup.toFile()));
            Files.delete(backup); assertNotEquals(0, run(dir, script));
            assertFalse(Files.exists(backup)); assertFalse(Files.exists(stage.resolve("original.pending"))); assertFalse(Files.exists(root.resolve("operation.lock")));
            assertThrows(Exception.class, () -> AudioTransfer.backups(List.of(new AudioTransfer.Backup(original, "/etc/shadow", hash)), STAGE));
        } finally {remove(dir);}
    }
    static Path write(Path path, String value) throws Exception {return Files.write(path, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static int run(Path dir, String script) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin")); Path sha = bin.resolve("sha256sum");
        AudioTransferTest.write(sha, "#!/bin/sh\nexec /usr/bin/shasum -a 256 \"$@\"\n"); assertTrue(sha.toFile().setExecutable(true));
        Path stat = bin.resolve("stat"); AudioTransferTest.write(stat, "#!/bin/sh\nwc -c < \"$3\"\n"); assertTrue(stat.toFile().setExecutable(true));
        Path code = AudioTransferTest.write(dir.resolve("run.sh"), script);
        ProcessBuilder b = new ProcessBuilder("/bin/sh", code.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("log").toFile());
        b.environment().put("PATH", bin + ":" + System.getenv("PATH")); Process p = b.start();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS)); return p.exitValue();
    }
    static void remove(Path dir) throws Exception {try (java.util.stream.Stream<Path> all = Files.walk(dir)) {for (Path p : all.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);}}
}

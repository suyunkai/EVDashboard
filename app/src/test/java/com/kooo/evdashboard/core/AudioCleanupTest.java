package com.kooo.evdashboard.core;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class AudioCleanupTest {
    @Test public void removesOnlyUnmountedAudioOfFinishedTransactions() throws Exception {
        Path dir = Files.createTempDirectory("audio-cleanup-");
        try {
            Path root = Files.createDirectory(dir.resolve("workshop"));
            Path done = stage(root, "1", "PASS"), rollback = stage(root, "2", "FAILED_RESTORED");
            Path uncertain = stage(root, "3", "FAILED_RECOVERY_REQUIRED"), pending = stage(root, "4", null);
            Path parent = stage(root, "5", "PASS");
            Path live = audio(done, "a"), stale = audio(done, "b"), restored = audio(rollback, "c"), unknown = audio(uncertain, "d"), inflight = audio(pending, "e"), child = audio(parent, "f");
            Path original = Files.createDirectories(root.resolve("originals")).resolve("sound-original.wav"); AudioTransferTest.write(original, "factory");
            Path outsider = AudioTransferTest.write(dir.resolve("outside.wav"), "outside"); Files.createSymbolicLink(done.resolve("0".repeat(64) + ".wav"), outsider);
            Path badName = Files.createDirectory(root.resolve("stage-not-a-uuid")); AudioTransferTest.write(badName.resolve("result"), "PASS"); Path bad = audio(badName, "9");
            Path mountinfo = AudioTransferTest.write(dir.resolve("mountinfo"),
                "1 0 0:1 / / rw - tmpfs tmpfs rw\n2 0 0:1 " + live.toString().replace(root.toString(), "/instrument-workshop") + " /target1 rw - tmpfs tmpfs rw\n"
                + "3 0 0:1 " + parent + " /target2 rw - tmpfs tmpfs rw\n");
            String code = AudioCleanup.script().replace(AudioPlan.ROOT, root.toString())
                .replace("/proc/self/mountinfo", mountinfo.toString());
            assertEquals(0, AudioTransferTest.run(dir, code));
            assertFalse(FilesEx.readText(dir.resolve("log").toFile()), Files.exists(stale)); assertFalse(Files.exists(restored));
            for (Path path : List.of(live, unknown, inflight, child, original, outsider, bad)) assertTrue(path.toString(), Files.exists(path));
            assertTrue(FilesEx.readText(dir.resolve("log").toFile()).contains("RECLAIMED=10")); assertFalse(Files.exists(root.resolve("operation.lock")));
            Files.createDirectory(root.resolve("operation.lock")); Path locked = audio(done, "8");
            assertEquals(0, AudioTransferTest.run(dir, code)); assertTrue(Files.exists(locked)); assertTrue(Files.exists(root.resolve("operation.lock")));
            Files.delete(root.resolve("operation.lock"));
            for (String invalid : List.of("", "unrecognized mount record\n")) {
                AudioTransferTest.write(mountinfo, invalid); assertEquals(0, AudioTransferTest.run(dir, code)); assertTrue(Files.exists(locked));
            }
        } finally {AudioTransferTest.remove(dir);}
    }
    private static Path stage(Path root, String digit, String result) throws Exception {
        Path path = Files.createDirectory(root.resolve("stage-" + digit.repeat(8) + "-" + digit.repeat(4) + "-" + digit.repeat(4) + "-" + digit.repeat(4) + "-" + digit.repeat(12)));
        if (result != null) AudioTransferTest.write(path.resolve("result"), result); return path;
    }
    private static Path audio(Path stage, String digit) throws Exception {return AudioTransferTest.write(stage.resolve(digit.repeat(64) + ".wav"), "audio");}
}

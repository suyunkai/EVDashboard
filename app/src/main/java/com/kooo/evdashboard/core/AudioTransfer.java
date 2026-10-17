package com.kooo.evdashboard.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded, regular-file-only ustar stream. The CP never stores a second archive copy. */
public final class AudioTransfer {
    private AudioTransfer() {}
    public static long write(Map<String, File> files, OutputStream out) throws Exception {
        FilesEx.require(files.size() <= 64, "音频数量超过上限");
        long total = 0;
        for (Map.Entry<String, File> entry : files.entrySet()) {
            String sha = entry.getKey(); File file = entry.getValue(); long size = file.length();
            FilesEx.require(sha.matches("[a-f0-9]{64}") && file.isFile() && !java.nio.file.Files.isSymbolicLink(file.toPath())
                && size > 0 && size <= SoundWave.MAX_BYTES, "无效的音频传输文件");
            byte[] header = new byte[512]; put(header, 0, sha + ".wav");
            octal(header, 100, 8, 0444); octal(header, 108, 8, 0); octal(header, 116, 8, 0);
            octal(header, 124, 12, size); octal(header, 136, 12, 0);
            Arrays.fill(header, 148, 156, (byte)' '); header[156] = '0'; put(header, 257, "ustar"); put(header, 263, "00");
            int checksum = 0; for (byte b : header) checksum += b & 255;
            put(header, 148, String.format(Locale.ROOT, "%06o", checksum)); header[154] = 0; header[155] = ' ';
            out.write(header); MessageDigest digest = MessageDigest.getInstance("SHA-256"); long copied = 0;
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[32 * 1024]; int n;
                while ((n = in.read(buffer)) != -1) {
                    copied += n; FilesEx.require(copied <= size, "传输期间音频发生变化");
                    digest.update(buffer, 0, n); out.write(buffer, 0, n);
                }
            }
            FilesEx.require(copied == size && FilesEx.hex(digest.digest()).equals(sha), "音频传输摘要不匹配");
            int padding = (int)((512 - size % 512) % 512); out.write(new byte[padding]); total += size;
        }
        out.write(new byte[1024]); return total;
    }
    private static void put(byte[] b, int offset, String value) {byte[] text = value.getBytes(StandardCharsets.US_ASCII); System.arraycopy(text, 0, b, offset, text.length);}
    private static void octal(byte[] b, int offset, int length, long value) {put(b, offset, String.format(Locale.ROOT, "%0" + (length - 1) + "o", value));}

    public static String receive(String stage) throws Exception {
        stage(stage);
        return "set -eu; test ! -L " + q(stage) + "; test -d " + q(stage) + "; tar -xzf - -C " + q(stage)
            + "; chmod 0444 " + q(stage) + "/*.wav; sha256sum " + q(stage) + "/*.wav";
    }
    public static void verify(String output, String stage, Set<String> expected) throws Exception {
        stage(stage); Set<String> found = new HashSet<>();
        for (String line : output.split("\n")) {
            String[] parts = line.trim().split("\\s+", 2);
            FilesEx.require(parts.length == 2 && expected.contains(parts[0]) && found.add(parts[0])
                && parts[1].equals(stage + "/" + parts[0] + ".wav"), "CP 批量上传摘要不匹配");
        }
        FilesEx.require(found.equals(expected), "CP 批量上传文件不完整");
    }
    public static final class Backup {
        public final String path, source, sha;
        public Backup(String path, String source, String sha) {this.path = path; this.source = source; this.sha = sha;}
    }
    /** Existing backups are immutable; missing originals are copied on CP and checked before publication. */
    public static String backups(List<Backup> backups, String stage) throws Exception {
        stage(stage); FilesEx.require(backups.size() <= 64, "原声备份数量超过上限");
        String root = AudioPlan.ROOT, lock = root + "/operation.lock";
        StringBuilder s = new StringBuilder("set -eu\numask 077\nmkdir " + q(lock) + "\npending=''\n"
            + "finish() { rc=$?; trap - EXIT HUP INT TERM; [ -z \"$pending\" ] || rm -f \"$pending\"; rmdir " + q(lock) + "; exit \"$rc\"; }\n"
            + "trap finish EXIT\ntrap 'exit 129' HUP\ntrap 'exit 130' INT\ntrap 'exit 143' TERM\n");
        Set<String> seen = new HashSet<>();
        for (Backup backup : backups) {
            FilesEx.require(backup.path.matches(root + "/originals/sound-[a-f0-9]{24}\\.wav") && seen.add(backup.path)
                && backup.sha.matches("[a-f0-9]{64}") && (backup.source.equals(backup.path) || SoundCatalog.allowedPath(backup.source)
                || backup.source.matches(root + "/factory-(?:avas/[A-Za-z0-9_.-]+\\.wav|lock\\.wav|cruise-[01]\\.wav)")), "无效的原声备份");
            s.append("test ! -L ").append(q(backup.path)).append("\nif [ ! -e ").append(q(backup.path)).append(" ]; then\n")
                .append("pending=").append(q(stage + "/original.pending")).append("\ntest ! -e \"$pending\"\ncp ").append(q(backup.source)).append(" \"$pending\"\n")
                .append("test \"$(sha256sum \"$pending\" | cut -d ' ' -f 1)\" = ").append(q(backup.sha)).append("\nchmod 0444 \"$pending\"\nmv \"$pending\" ")
                .append(q(backup.path)).append("\npending=''\nfi\ntest \"$(sha256sum ").append(q(backup.path)).append(" | cut -d ' ' -f 1)\" = ").append(q(backup.sha)).append("\n");
        }
        return s.toString();
    }
    private static String q(String s) {return FilesEx.quote(s);}
    private static void stage(String s) throws Exception {FilesEx.require(s.matches(AudioPlan.ROOT + "/stage-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"), "无效的音频暂存目录");}
}

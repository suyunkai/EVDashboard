package com.kooo.evdashboard.core;

import java.security.MessageDigest;
import java.util.*;

/** A fresh remote hash manifest permits reuse of verified immutable local audio. */
public final class SoundSync {
    @FunctionalInterface public interface Reader {byte[] read(String path) throws Exception;}
    public static String script(Collection<String> required, Collection<String> optional) throws Exception {
        StringBuilder script = new StringBuilder("set -e\n");
        Set<String> all = new LinkedHashSet<>(required); all.addAll(optional);
        for (String path : all) {
            FilesEx.require(path.startsWith("/") && path.indexOf('\n') < 0 && path.indexOf('\r') < 0, "无效的声音校验路径");
            String q = FilesEx.quote(path);
            if (!required.contains(path)) script.append("if [ -f ").append(q).append(" ]; then\n");
            script.append("test -f ").append(q).append("\ntest \"$(stat -c %s ").append(q).append(")\" -le ")
                .append(SoundWave.MAX_BYTES).append("\nsha256sum ").append(q).append('\n');
            if (!required.contains(path)) script.append("fi\n");
        }
        return script.toString();
    }
    public static Map<String, String> parse(String output, Collection<String> required, Collection<String> optional) throws Exception {
        Set<String> allowed = new HashSet<>(required); allowed.addAll(optional);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String line : output.split("\n")) {
            if (line.isEmpty()) continue;
            FilesEx.require(line.length() > 66 && line.substring(0, 64).matches("[a-f0-9]{64}") && line.substring(64, 66).equals("  "), "CP 声音摘要格式异常");
            String path = line.substring(66);
            FilesEx.require(allowed.contains(path) && !hashes.containsKey(path), "CP 声音摘要路径异常或重复");
            hashes.put(path, line.substring(0, 64));
        }
        FilesEx.require(hashes.keySet().containsAll(required), "CP 声音摘要不完整，请重新读取");
        return hashes;
    }
    public static String cache(SoundStore store, String path, String expected, Reader remote) throws Exception {
        FilesEx.require(expected != null && expected.matches("[a-f0-9]{64}"), "CP 声音摘要不完整，请重新读取");
        try {store.asset(expected); return expected;} catch (Exception missing) { /* Fetch only absent or invalid local audio. */ }
        byte[] source = remote.read(path);
        String actual = FilesEx.hex(MessageDigest.getInstance("SHA-256").digest(source));
        FilesEx.require(expected.equals(actual), "读取期间声音已变化，请重新读取");
        return store.put(source);
    }
    public static void verify(Map<String, String> expected, List<SoundStore.Entry> actual) throws Exception {
        Map<String, SoundStore.Entry> byPath = new HashMap<>(); for (SoundStore.Entry entry : actual) byPath.put(entry.path, entry);
        for (Map.Entry<String, String> e : expected.entrySet())
            FilesEx.require(byPath.containsKey(e.getKey()) && e.getValue().equals(byPath.get(e.getKey()).current), "切换后音频摘要不匹配：" + e.getKey());
    }
}

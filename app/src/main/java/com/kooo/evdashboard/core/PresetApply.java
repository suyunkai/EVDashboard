package com.kooo.evdashboard.core;

import java.util.*;

/**
 * Writes chosen built-in preset sounds into the local sound configuration. It only edits rules (replacement audio,
 * volume reset to 100%); nothing is sent to the CP. Sounds that cannot be used are reported, never half-applied.
 */
public final class PresetApply {
    public static final class Result {
        public final List<String> applied = new ArrayList<>();
        public final Map<String, String> skipped = new LinkedHashMap<>();
    }
    private PresetApply() {}

    /** @param audio CP path -> replacement WAV bytes for every sound the user ticked. */
    public static Result apply(SoundStore store, SoundCatalog catalog, List<SoundStore.Entry> entries, Map<String, byte[]> audio) throws Exception {
        Map<String, SoundStore.Entry> byPath = new HashMap<>();
        for (SoundStore.Entry e : entries) byPath.put(e.path, e);
        Map<String, SoundStore.Rule> rules = new LinkedHashMap<>(store.rules());
        Result result = new Result();
        for (Map.Entry<String, byte[]> item : audio.entrySet()) {
            String path = item.getKey(); SoundStore.Entry entry = byPath.get(path);
            String reason = entry == null ? "当前 CP 没有这个声音" : !catalog.spec(path).writable() ? "此声音的映射未确认"
                : entry.original.isEmpty() ? "尚未读取到系统原声" : entry.overlay.equals("FOREIGN") || entry.overlay.equals("LEGACY") ? "已有其他覆盖，请先恢复" : "";
            if (reason.isEmpty()) {
                try {
                    SoundWave original = new SoundWave(store.bytes(entry.original)), replacement = new SoundWave(item.getValue());
                    if (original.rate != replacement.rate || original.channels != replacement.channels) reason = "音频格式与系统原声不一致";
                } catch (Exception e) {reason = "音频无法读取";}
            }
            if (!reason.isEmpty()) {result.skipped.put(path, reason); continue;}
            rules.put(path, new SoundStore.Rule(entry.original, store.put(item.getValue()), 100));
            result.applied.add(path);
        }
        if (!result.applied.isEmpty()) store.rules(rules);
        return result;
    }
}

package com.kooo.evdashboard.core;

import org.json.*;
import java.util.*;

/** Metadata from a successful full check. Never substitutes for write-time content verification. */
public final class SoundRecoveryState {
    public final String androidBoot, cpBoot, identity, rulesHash, catalogHash;
    public final Map<String, String> mounts;
    public SoundRecoveryState(String androidBoot, String cpBoot, String identity, String rulesHash, String catalogHash, Map<String, String> mounts) throws Exception {
        FilesEx.require(!androidBoot.isEmpty() && androidBoot.length() <= 100 && cpBoot.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")
            && identity.matches("[a-f0-9]{64}") && rulesHash.matches("[a-f0-9]{64}") && catalogHash.matches("[a-f0-9]{64}") && mounts.size() <= 256, "自动恢复核验记录无效");
        this.androidBoot = androidBoot; this.cpBoot = cpBoot; this.identity = identity; this.rulesHash = rulesHash; this.catalogHash = catalogHash;
        this.mounts = Collections.unmodifiableMap(new TreeMap<>(mounts));
    }
    public boolean matches(String androidBoot, String cpBoot, String identity, String rulesHash, String catalogHash, Map<String, String> mounts) {
        return this.androidBoot.equals(androidBoot) && this.cpBoot.equals(cpBoot) && this.identity.equals(identity)
            && this.rulesHash.equals(rulesHash) && this.catalogHash.equals(catalogHash) && this.mounts.equals(mounts);
    }
    public String json() throws Exception {
        return new JSONObject().put("version", 1).put("androidBoot", androidBoot).put("cpBoot", cpBoot).put("identity", identity)
            .put("rulesHash", rulesHash).put("catalogHash", catalogHash).put("mounts", new JSONObject(mounts)).toString();
    }
    public static SoundRecoveryState parse(String json) throws Exception {
        JSONObject o = new JSONObject(json); FilesEx.require(o.getInt("version") == 1, "不支持的自动恢复核验记录");
        JSONObject rows = o.getJSONObject("mounts"); Map<String, String> mounts = new TreeMap<>();
        for (Iterator<String> keys = rows.keys(); keys.hasNext();) {String key = keys.next(); mounts.put(key, rows.getString(key));}
        return new SoundRecoveryState(o.getString("androidBoot"), o.getString("cpBoot"), o.getString("identity"), o.getString("rulesHash"), o.getString("catalogHash"), mounts);
    }
    /** Ignore readAt; a foreground refresh of identical resources does not invalidate verification. */
    public static String catalogHash(List<SoundStore.Entry> entries) throws Exception {
        TreeMap<String, String> rows = new TreeMap<>();
        for (SoundStore.Entry e : entries) {
            FilesEx.require(!rows.containsKey(e.path), "声音目录包含重复路径");
            rows.put(e.path, new JSONArray().put(e.original).put(e.current).put(e.overlay).toString());
        }
        JSONArray canonical = new JSONArray();
        for (Map.Entry<String, String> row : rows.entrySet()) canonical.put(new JSONArray().put(row.getKey()).put(row.getValue()));
        return FilesEx.hashText(canonical.toString());
    }
    public static boolean relevant(String target) {
        for (String root : SoundCatalog.ROOTS) if (target.startsWith(root) || root.startsWith(target + "/") || root.equals(target + "/")) return true;
        return false;
    }
    public static final class Probe {
        public final String boot;
        public final Map<String, String> mounts;
        public Probe(String text) throws Exception {
            String boot = ""; Map<String, String> mounts = new TreeMap<>();
            for (String line : text.split("\n")) {
                String[] row = line.split("\t", -1);
                if (row.length == 2 && row[0].equals("BOOT") && boot.isEmpty()) boot = row[1];
                else {
                    FilesEx.require(row.length == 3 && row[0].equals("MOUNT") && row[1].startsWith("/") && row[2].startsWith("/"), "CP 轻量检查响应无效");
                    if (relevant(row[1])) FilesEx.require(mounts.put(row[1], row[2]) == null && mounts.size() <= 256, "检测到叠加挂载，请先恢复其他覆盖");
                }
            }
            FilesEx.require(boot.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"), "CP 启动标识无效");
            this.boot = boot; this.mounts = Collections.unmodifiableMap(mounts);
        }
    }
}

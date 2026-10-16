package com.kooo.evdashboard.core;

import org.json.*;
import java.util.*;

/**
 * A built-in sound preset: one replacement WAV for each confirmed-writable CP sound. Parsing validates everything
 * the app will later trust (paths, categories, file names and digests), so a damaged asset cannot reach the store.
 */
public final class SoundPreset {
    public static final int MAX_ITEMS = 64;
    public static final class Item {
        public final String path, category, group, variant, file, sha256;
        public final double seconds;
        Item(String path, String category, String group, String variant, String file, String sha256, double seconds) {
            this.path = path; this.category = category; this.group = group; this.variant = variant;
            this.file = file; this.sha256 = sha256; this.seconds = seconds;
        }
        /** Card name plus variant, e.g. "锁车提示 · 轻音版"; the default variant is not repeated. */
        public String label() {return variant.equals("默认") ? group : group + " · " + variant;}
    }
    public final String id, name, description;
    private final List<Item> items;
    private SoundPreset(String id, String name, String description, List<Item> items) {
        this.id = id; this.name = name; this.description = description; this.items = Collections.unmodifiableList(items);
    }
    public static SoundPreset parse(String json, SoundCatalog catalog) throws Exception {
        JSONObject o = new JSONObject(json);
        FilesEx.require(o.getString("format").equals("instrument-preset") && o.getInt("version") == 1, "不支持的内置预设版本");
        String id = o.getString("id"), name = o.getString("name");
        FilesEx.require(id.matches("[a-z0-9-]{1,24}"), "内置预设标识无效");
        FilesEx.require(!name.isEmpty() && name.length() <= 40 && name.chars().noneMatch(c -> c < 32), "内置预设名称无效");
        JSONArray list = o.getJSONArray("sounds");
        FilesEx.require(list.length() > 0 && list.length() <= MAX_ITEMS, "内置预设的声音数量无效");
        List<Item> items = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.getJSONObject(i); String path = s.getString("path"), sha = s.getString("sha256"), file = s.getString("file");
            SoundCatalog.Spec spec = catalog.spec(path);
            FilesEx.require(spec.writable() && seen.add(path), "内置预设包含未确认映射或重复声音");
            FilesEx.require(sha.matches("[a-f0-9]{64}") && file.equals("audio/" + sha + ".wav"), "内置预设的音频文件名无效");
            FilesEx.require(s.getString("category").equals(spec.category), "内置预设的分类与声音目录不一致");
            items.add(new Item(path, spec.category, spec.group, spec.variant, file, sha, s.optDouble("seconds", 0)));
        }
        return new SoundPreset(id, name, o.optString("description", ""), items);
    }
    public List<Item> items() {return items;}
    /** Categories that have at least one sound, in the catalog's display order. */
    public List<String> categories() {
        List<String> result = new ArrayList<>();
        for (String category : SoundCatalog.CATEGORIES) if (!inCategory(category).isEmpty()) result.add(category);
        return result;
    }
    public List<Item> inCategory(String category) {
        List<Item> result = new ArrayList<>();
        for (Item item : items) if (item.category.equals(category)) result.add(item);
        return result;
    }
}

package com.kooo.evdashboard.core;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Private, content-addressed audio library. Configuration commits are atomic; sources are immutable. */
public final class SoundStore {
    public static final int MAX_PRESETS = 5;
    // Every valid export (64 rules, up to two 6 MiB sources per rule) can be reimported.
    private static final long MAX_ARCHIVE_BYTES = 128L * SoundWave.MAX_BYTES + 256 * 1024;
    private final File root, assets, presets;
    private final SoundCatalog catalog;
    public SoundStore(File root, SoundCatalog catalog) throws Exception {
        this.root = root; this.catalog = catalog; assets = new File(root, "assets"); presets = new File(root, "presets");
        Files.createDirectories(assets.toPath()); Files.createDirectories(presets.toPath());
    }
    public static final class Entry {
        public final String path, original, current, overlay;
        public Entry(String path, String original, String current, String overlay) {
            this.path = path; this.original = original; this.current = current; this.overlay = overlay;
        }
        JSONObject json() throws Exception {return new JSONObject().put("path", path).put("original", original).put("current", current).put("overlay", overlay);}
    }
    public static final class Rule {
        public final String original, replacement;
        public final int gain;
        public Rule(String original, String replacement, int gain) {this.original = original; this.replacement = replacement; this.gain = gain;}
        public boolean changed() {return !replacement.isEmpty() || gain != 100;}
        JSONObject json(String path) throws Exception {return new JSONObject().put("path", path).put("original", original).put("replacement", replacement).put("gain", gain);}
    }
    public static final class Preset {
        public final String id, name;
        public Preset(String id, String name) {this.id = id; this.name = name;}
    }
    public synchronized String put(byte[] bytes) throws Exception {
        new SoundWave(bytes);
        String sha = FilesEx.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        File file = new File(assets, sha + ".wav");
        if (file.exists()) {FilesEx.require(FilesEx.digest(file).equals(sha), "本机音频摘要校验失败"); return sha;}
        File tmp = File.createTempFile("asset-", ".pending", assets);
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {out.write(bytes); out.getFD().sync();}
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } finally {Files.deleteIfExists(tmp.toPath());}
        return sha;
    }
    public synchronized File asset(String sha) throws Exception {
        FilesEx.require(sha.matches("[a-f0-9]{64}"), "无效的音频摘要");
        File f = new File(assets, sha + ".wav");
        FilesEx.require(f.isFile() && f.length() <= SoundWave.MAX_BYTES && FilesEx.digest(f).equals(sha), "音频缺失或已损坏，请重新读取/导入");
        return f;
    }
    public synchronized byte[] bytes(String sha) throws Exception {return Files.readAllBytes(asset(sha).toPath());}
    public synchronized String render(Rule rule) throws Exception {
        validateRule(rule);
        String source = rule.replacement.isEmpty() ? rule.original : rule.replacement;
        if (rule.gain == 100) return source;
        String cached = cachedRender(rule);
        if (!cached.isEmpty()) {asset(cached); return cached;}
        String result = put(new SoundWave(bytes(source)).volume(rule.gain));
        FilesEx.atomicText(renderIndex(rule), result); return result;
    }
    private File renderIndex(Rule rule) throws Exception {
        String source = rule.replacement.isEmpty() ? rule.original : rule.replacement;
        FilesEx.require(source.matches("[a-f0-9]{64}") && rule.gain >= 0 && rule.gain <= 200, "无效音量规则");
        return new File(root, "render-" + source + "-" + rule.gain + ".sha");
    }
    /** For status display only. Applying/exporting always verifies the actual asset contents. */
    public synchronized String cachedRender(Rule rule) throws Exception {
        if (rule.gain == 100) return rule.replacement.isEmpty() ? rule.original : rule.replacement;
        File index = renderIndex(rule); if (!index.isFile()) return "";
        String sha = FilesEx.readText(index).trim();
        return sha.matches("[a-f0-9]{64}") && new File(assets, sha + ".wav").isFile() ? sha : "";
    }
    private void validateRule(Rule rule) throws Exception {
        FilesEx.require(rule.gain >= 0 && rule.gain <= 200, "音量范围为 0–200%");
        SoundWave original = new SoundWave(bytes(rule.original));
        FilesEx.require(original.rate == 48000 && original.channels <= 2, "CP 原声音格式不支持");
        if (!rule.replacement.isEmpty()) {
            SoundWave replacement = new SoundWave(bytes(rule.replacement));
            FilesEx.require(original.rate == replacement.rate && original.channels == replacement.channels, "替换音频与原声的采样率/声道不一致");
        }
    }
    public synchronized List<Entry> entries() throws Exception {
        List<Entry> list = new ArrayList<>(); File file = new File(root, "snapshot.json");
        if (!file.isFile()) return list;
        JSONArray a = new JSONObject(FilesEx.readText(file)).getJSONArray("entries");
        for (int i = 0; i < a.length(); i++) {JSONObject o = a.getJSONObject(i); catalog.spec(o.getString("path"));
            list.add(new Entry(o.getString("path"), o.getString("original"), o.getString("current"), o.getString("overlay")));}
        return list;
    }
    public synchronized String identity() throws Exception {
        File file = new File(root, "snapshot.json");
        return file.isFile() ? new JSONObject(FilesEx.readText(file)).getString("identity") : "";
    }
    public synchronized void snapshot(String identity, List<Entry> entries) throws Exception {
        JSONArray list = new JSONArray(); for (Entry e : entries) list.put(e.json());
        FilesEx.atomicText(new File(root, "snapshot.json"), new JSONObject().put("identity", identity).put("entries", list).put("readAt", System.currentTimeMillis()).toString());
    }
    public synchronized Map<String, Rule> rules() throws Exception {
        File file = new File(root, "rules.json");
        return file.isFile() ? parseRules(new JSONObject(FilesEx.readText(file)), true) : new LinkedHashMap<>();
    }
    public synchronized void rules(Map<String, Rule> rules) throws Exception {
        JSONObject json = document("当前配置", rules); parseRules(json, true);
        for (Rule rule : rules.values()) if (rule.changed()) render(rule);
        FilesEx.atomicText(new File(root, "rules.json"), json.toString());
    }
    private JSONObject document(String name, Map<String, Rule> rules) throws Exception {
        JSONArray list = new JSONArray();
        for (Map.Entry<String, Rule> e : rules.entrySet()) if (e.getValue().changed()) list.put(e.getValue().json(e.getKey()));
        return new JSONObject().put("format", "instrument-sounds").put("version", 1).put("name", name).put("rules", list);
    }
    private Map<String, Rule> parseRules(JSONObject json, boolean checkAssets) throws Exception {
        FilesEx.require(json.getString("format").equals("instrument-sounds") && json.getInt("version") == 1, "不支持的预设版本");
        JSONArray list = json.getJSONArray("rules"); FilesEx.require(list.length() <= 64, "声音规则超过上限");
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i); String path = o.getString("path");
            FilesEx.require(catalog.spec(path).writable() && !rules.containsKey(path), "预设包含未确认映射或重复声音");
            Rule rule = new Rule(o.getString("original"), o.getString("replacement"), o.getInt("gain"));
            FilesEx.require(rule.original.matches("[a-f0-9]{64}") && (rule.replacement.isEmpty() || rule.replacement.matches("[a-f0-9]{64}"))
                && rule.gain >= 0 && rule.gain <= 200 && rule.changed(), "无效的声音规则");
            if (checkAssets) validateRule(rule);
            rules.put(path, rule);
        }
        return rules;
    }
    public synchronized List<Preset> presets() throws Exception {
        List<Preset> result = new ArrayList<>(); File[] files = presets.listFiles((d, n) -> n.matches("[a-f0-9-]{36}\\.json"));
        FilesEx.require(files != null, "无法读取预设"); Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) result.add(new Preset(f.getName().replace(".json", ""), new JSONObject(FilesEx.readText(f)).getString("name")));
        return result;
    }
    public synchronized void savePreset(String name) throws Exception {
        name = name.trim(); FilesEx.require(!name.isEmpty() && name.length() <= 40 && name.chars().noneMatch(c -> c < 32), "预设名称须为 1–40 个字符");
        List<Preset> all = presets(); FilesEx.require(all.size() < MAX_PRESETS, "最多保存 5 个预设，请先删除一个");
        for (Preset p : all) FilesEx.require(!p.name.equals(name), "已有同名预设，请使用其他名称");
        FilesEx.atomicText(new File(presets, UUID.randomUUID() + ".json"), document(name, rules()).toString());
    }
    private File presetFile(String id) throws Exception {FilesEx.require(id.matches("[a-f0-9-]{36}"), "无效预设"); return new File(presets, id + ".json");}
    public synchronized void loadPreset(String id) throws Exception {rules(parseRules(new JSONObject(FilesEx.readText(presetFile(id))), true));}
    public synchronized void deletePreset(String id) throws Exception {Files.delete(presetFile(id).toPath());}
    private static Set<String> required(Map<String, Rule> rules) {
        Set<String> assets = new LinkedHashSet<>();
        for (Rule r : rules.values()) {assets.add(r.original); if (!r.replacement.isEmpty()) assets.add(r.replacement);}
        return assets;
    }
    public synchronized void exportZip(OutputStream output) throws Exception {
        Map<String, Rule> rules = rules();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(document("仪表提示音", rules).toString(2).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            for (String sha : required(rules)) {zip.putNextEntry(new ZipEntry("audio/" + sha + ".wav")); zip.write(bytes(sha)); zip.closeEntry();}
        }
    }
    /** Never extracts caller-supplied paths. Validate everything before publishing configuration. */
    public synchronized void importZip(InputStream input) throws Exception {
        File stage = Files.createTempDirectory(root.toPath(), "import-").toFile();
        try {
            Set<String> names = new HashSet<>(); JSONObject manifest = null; long total = 0; int count = 0;
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName(); boolean isManifest = name.equals("manifest.json");
                    FilesEx.require(++count <= 130 && !entry.isDirectory() && names.add(name)
                        && (isManifest || name.matches("audio/[a-f0-9]{64}\\.wav")), "ZIP 条目无效、重复或包含非法路径");
                    int limit = isManifest ? 256 * 1024 : SoundWave.MAX_BYTES;
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                    while ((n = zip.read(buffer)) != -1) {total += n; FilesEx.require(out.size() + n <= limit && total <= MAX_ARCHIVE_BYTES, "ZIP 解压大小超过限制"); out.write(buffer, 0, n);}
                    byte[] data = out.toByteArray();
                    if (isManifest) manifest = new JSONObject(new String(data, StandardCharsets.UTF_8));
                    else {new SoundWave(data); String sha = name.substring(6, 70);
                        FilesEx.require(FilesEx.hex(MessageDigest.getInstance("SHA-256").digest(data)).equals(sha), "ZIP 音频摘要不匹配");
                        Files.write(new File(stage, sha + ".wav").toPath(), data);}
                    zip.closeEntry();
                }
            }
            FilesEx.require(manifest != null, "ZIP 缺少 manifest.json");
            Map<String, Rule> imported = parseRules(manifest, false); Set<String> required = required(imported);
            FilesEx.require(names.size() == required.size() + 1, "ZIP 音频清单与配置不一致");
            for (String sha : required) FilesEx.require(new File(stage, sha + ".wav").isFile(), "ZIP 缺少规则引用的音频");
            // Validate channel compatibility before adding any asset or changing the active rules.
            for (Rule r : imported.values()) {
                SoundWave original = new SoundWave(Files.readAllBytes(new File(stage, r.original + ".wav").toPath()));
                FilesEx.require(original.rate == 48000 && original.channels <= 2, "ZIP 原声音格式不支持");
                if (!r.replacement.isEmpty()) {SoundWave replacement = new SoundWave(Files.readAllBytes(new File(stage, r.replacement + ".wav").toPath()));
                    FilesEx.require(replacement.rate == original.rate && replacement.channels == original.channels, "ZIP 替换声音格式不一致");}
            }
            for (String sha : required) put(Files.readAllBytes(new File(stage, sha + ".wav").toPath()));
            rules(imported);
        } finally {File[] files = stage.listFiles(); if (files != null) for (File file : files) Files.deleteIfExists(file.toPath()); Files.deleteIfExists(stage.toPath());}
    }
}

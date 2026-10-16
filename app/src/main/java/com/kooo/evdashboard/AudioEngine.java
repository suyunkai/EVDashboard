package com.kooo.evdashboard;

import android.content.Context;
import com.kooo.evdashboard.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

/** Discovers actual CP resources and applies a complete configuration with temporary bind mounts. */
final class AudioEngine {
    private static final String ROOT = AudioPlan.ROOT;
    private final Context context;
    private final Consumer<String> progress;
    private final TaskProgress measured;
    private final SoundCatalog catalog;
    private final SoundStore store;
    private int lastDownloaded;
    AudioEngine(Context context, Consumer<String> progress) throws Exception {this(context, progress, true);}
    AudioEngine(Context context, Consumer<String> progress, boolean taskProgress) throws Exception {
        this.context = context; this.progress = progress; catalog = catalog(context); store = store(context, catalog);
        measured = taskProgress && context.getApplicationContext() instanceof AppState ? ((AppState) context.getApplicationContext()).progress : null;
    }
    static SoundCatalog catalog(Context context) throws Exception {
        try (InputStream in = context.getAssets().open("sounds/catalog.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); FilesEx.transfer(in, out);
            return new SoundCatalog(out.toString(StandardCharsets.UTF_8.name()));
        }
    }
    static SoundStore store(Context context, SoundCatalog catalog) throws Exception {
        return new SoundStore(new File(context.getFilesDir(), "instrument-sounds"), catalog);
    }
    static String identity(Context context) throws Exception {
        return FilesEx.hashText("cp-ip-v1:" + CpAddress.parse(SshAccess.host(context)) + ":22/root");
    }
    private static String q(String text) {return FilesEx.quote(text);}
    private static boolean owned(String path) {return path.startsWith(ROOT + "/") || path.startsWith("/instrument-workshop/");}
    private Map<String, String> mounts(SshAccess ssh) throws Exception {
        Map<String, String> mounts = new HashMap<>();
        for (String line : ssh.text("awk '$5 != \"/\" {print $5 \"\\t\" $4}' /proc/self/mountinfo", 12).split("\n")) {
            String[] parts = line.split("\t"); if (parts.length == 2 && relevantMount(parts[0])) {FilesEx.require(!mounts.containsKey(parts[0]), "检测到叠加挂载，请先恢复其他覆盖"); mounts.put(parts[0], parts[1]);}
        }
        return mounts;
    }
    private static boolean relevantMount(String target) {
        for (String root : SoundCatalog.ROOTS) if (target.startsWith(root) || root.startsWith(target + "/") || root.equals(target + "/")) return true;
        return false;
    }
    private String overlayRoot(Map<String, String> mounts, String path) {
        String result = "", longest = "";
        for (Map.Entry<String, String> e : mounts.entrySet()) if ((path.equals(e.getKey()) || path.startsWith(e.getKey() + "/")) && e.getKey().length() > longest.length()) {result = e.getValue(); longest = e.getKey();}
        return result;
    }
    private String originalRemote(SoundCatalog.Spec spec) throws Exception {return ROOT + "/originals/" + spec.tag() + ".wav";}
    private String legacyOriginal(String path) {
        if (path.startsWith("/usr/avas/sound_type_1/")) return ROOT + "/factory-avas/" + path.substring(path.lastIndexOf('/') + 1);
        if (path.endsWith("/V3_Vehicle_Locked_Single.wav")) return ROOT + "/factory-lock.wav";
        if (path.endsWith("/V3_Function_Enabled_Successful_Single.wav")) return ROOT + "/factory-cruise-0.wav";
        if (path.endsWith("/V3_Function_Exited_Single.wav")) return ROOT + "/factory-cruise-1.wav";
        return "";
    }
    List<SoundStore.Entry> refresh(String password) throws Exception {
        if (measured != null) measured.stage("连接 CP 并检查声音目录", 0);
        try (SshAccess ssh = new SshAccess(context)) {ssh.connect(password); return refresh(ssh);}
    }
    private List<SoundStore.Entry> refresh(SshAccess ssh) throws Exception {
        ssh.text("if [ -d " + q(ROOT + "/operation.lock") + " ]; then echo 'CP 音效任务仍在执行或锁尚未恢复，请先核对上次任务记录' >&2; exit 60; fi", 10);
        StringBuilder command = new StringBuilder();
        for (String root : SoundCatalog.ROOTS) command.append("if [ -d ").append(q(root)).append(" ]; then find ").append(q(root)).append(" -maxdepth 1 -type f -iname '*.wav'; fi\n");
        String found = ssh.text(command.toString(), 15); Map<String, String> mounts = mounts(ssh);
        List<String> paths = new ArrayList<>();
        for (String path : found.split("\n")) if (SoundCatalog.allowedPath(path) && !paths.contains(path)) paths.add(path);
        FilesEx.require(!paths.isEmpty() && paths.size() <= 128, "未发现支持的 CP 音频资源或数量异常");
        Collections.sort(paths); List<SoundStore.Entry> entries = new ArrayList<>(); int count = 0;
        Set<String> originals = new LinkedHashSet<>();
        for (String path : paths) if (owned(overlayRoot(mounts, path))) {
            originals.add(originalRemote(catalog.spec(path))); String legacy = legacyOriginal(path); if (!legacy.isEmpty()) originals.add(legacy);
        }
        if (measured != null) measured.stage("核对 CP 声音摘要", 0);
        // Dropbear limits exec command length; stream the read-only batch over stdin.
        byte[] manifest = ssh.command("/bin/sh -s", SoundSync.script(paths, originals).getBytes(StandardCharsets.UTF_8), 20);
        Map<String, String> hashes = SoundSync.parse(new String(manifest, StandardCharsets.UTF_8), paths, originals);
        lastDownloaded = 0;
        SoundSync.Reader download = path -> {progress.accept("读取变化的声音 · " + path.substring(path.lastIndexOf('/') + 1)); lastDownloaded++; return read(ssh, path);};
        if (measured != null) measured.stage("核对声音与本机缓存", paths.size());
        for (String path : paths) {
            progress.accept("核对实际提示音 " + (++count) + "/" + paths.size() + " · " + catalog.spec(path).name);
            String mounted = overlayRoot(mounts, path), overlay = mounted.isEmpty() ? "NONE" : owned(mounted) ? "OWN" : "FOREIGN";
            String currentSha = SoundSync.cache(store, path, hashes.get(path), download), original = "";
            if (overlay.equals("NONE")) original = currentSha;
            else if (overlay.equals("OWN")) {
                String base = originalRemote(catalog.spec(path)), legacy = legacyOriginal(path);
                String source = hashes.containsKey(base) ? base : hashes.containsKey(legacy) ? legacy : "";
                if (!source.isEmpty()) original = SoundSync.cache(store, source, hashes.get(source), download);
                if (mounts.containsKey("/usr/avas/sound_type_1")) overlay = "LEGACY";
            }
            entries.add(new SoundStore.Entry(path, original, currentSha, overlay));
            if (measured != null) measured.advance(count);
        }
        if (measured != null) measured.stage("保存声音目录", 0);
        store.snapshot(identity(context), entries);
        progress.accept("已核对 " + entries.size() + " 个实际声音，下载 " + lastDownloaded + " 个变化文件。试听通过 Android 媒体音频输出。");
        return entries;
    }
    private byte[] read(SshAccess ssh, String path) throws Exception {
        byte[] data = ssh.command("test -f " + q(path) + " && test \"$(stat -c %s " + q(path) + ")\" -le " + SoundWave.MAX_BYTES + " && cat " + q(path), null, 20);
        new SoundWave(data); return data;
    }
    private void establish(SshAccess ssh) throws Exception {
        ssh.text("set -e; test \"$(id -u)\" = 0; test -d /run; mkdir -p " + q(ROOT + "/originals") + "; chmod 700 " + q(ROOT), 10);
    }
    private void requireAutomaticAllowed() throws Exception {
        android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
        FilesEx.require(AutoApplyService.enabled(context) && power != null && power.isInteractive(), "自动覆盖已关闭或车机仍在休眠");
        FilesEx.require(!new File(context.getFilesDir(), "pending-write.json").exists(), "存在待核验的分区写入任务，暂不自动恢复声音");
        String known = context.getSharedPreferences("settings", 0).getString("sshKnown", "");
        boolean legacy = known.startsWith(SshAccess.host(context) + " ") && store.identity().equals(FilesEx.hashText(known));
        FilesEx.require(SshAccess.trusted(context) && !store.entries().isEmpty() && (store.identity().equals(identity(context)) || legacy),
            "自动覆盖的 CP 地址与声音目录不一致，请重新读取");
    }
    /** Retryable readiness work is strictly read-only on CP. */
    boolean prepareAutomatic() throws Exception {
        requireAutomaticAllowed();
        try (SshAccess ssh = new SshAccess(context)) {
            ssh.connect("");
            ssh.text("test ! -d " + q(ROOT + "/operation.lock") + " && test -d /run && test -d " + q(SoundCatalog.CHIMES), 10);
            Map<String, SoundStore.Rule> rules = store.rules();
            List<SoundStore.Entry> entries = refresh(ssh);
            Set<String> found = new HashSet<>(); boolean needed = false;
            for (SoundStore.Entry entry : entries) {
                SoundStore.Rule rule = rules.get(entry.path);
                FilesEx.require(!entry.overlay.equals("LEGACY"), "检测到旧版覆盖，请先恢复旧版覆盖");
                if (rule != null) {
                    found.add(entry.path);
                    FilesEx.require(entry.original.equals(rule.original) && !entry.overlay.equals("FOREIGN"), "预设原声与当前 CP 不匹配，请重新配置此声音");
                    if (!entry.current.equals(store.render(rule)) || !entry.overlay.equals("OWN")) needed = true;
                } else if (entry.overlay.equals("OWN")) needed = true;
            }
            FilesEx.require(found.size() == rules.size(), "配置中的声音在当前 CP 上不存在，请重新读取");
            FilesEx.require(!needed || !context.getSharedPreferences("settings", 0).getBoolean("startupApplyUncertain", false),
                "上次覆盖结果待确认，请手动应用配置后再自动恢复");
            if (!needed) clearUncertain();
            return needed;
        }
    }
    private void clearUncertain() {
        context.getSharedPreferences("settings", 0).edit().remove("startupApplyUncertain").apply();
    }
    void apply(String password, boolean automatic) throws Exception {apply(password, automatic, null);}
    void apply(String password, boolean automatic, Set<String> scope) throws Exception {
        boolean unresolved = context.getSharedPreferences("settings", 0).getBoolean("startupApplyUncertain", false);
        Map<String, SoundStore.Rule> rules = store.rules();
        if (automatic) requireAutomaticAllowed();
        try (SshAccess ssh = new SshAccess(context)) {
            ssh.connect(password); List<SoundStore.Entry> entries = refresh(ssh);
            Map<String, SoundStore.Entry> byPath = new HashMap<>(); for (SoundStore.Entry e : entries) byPath.put(e.path, e);
            // Validate the entire desired configuration before any service or mount is changed.
            for (Map.Entry<String, SoundStore.Rule> r : rules.entrySet()) {
                if (scope != null && !scope.contains(r.getKey())) continue;
                SoundStore.Entry entry = byPath.get(r.getKey());
                FilesEx.require(entry != null && entry.original.equals(r.getValue().original), "预设原声与当前 CP 不匹配：" + catalog.spec(r.getKey()).name + "，请重新配置此声音");
            }
            for (SoundStore.Entry e : entries) FilesEx.require(!e.overlay.equals("LEGACY"), "检测到旧版 AVAS 目录覆盖，请先在连接页恢复旧版覆盖，再重新读取");
            List<AudioPlan.Change> changes = new ArrayList<>(); Set<String> services = new LinkedHashSet<>();
            String stage = ROOT + "/stage-" + UUID.randomUUID(); Map<String, byte[]> uploads = new LinkedHashMap<>();
            Map<String, String> expected = new HashMap<>();
            for (SoundStore.Entry e : entries) {
                if (scope != null && !scope.contains(e.path)) continue;
                SoundCatalog.Spec spec = catalog.spec(e.path); SoundStore.Rule rule = rules.get(e.path);
                if (rule == null && !e.overlay.equals("OWN")) continue;
                FilesEx.require(spec.writable() && !e.original.isEmpty() && !e.overlay.equals("FOREIGN"), "无法安全管理此覆盖：" + spec.name);
                String desired = rule == null ? e.original : store.render(rule); expected.put(e.path, desired);
                if (rule != null && e.current.equals(desired) && e.overlay.equals("OWN")) continue;
                String replacement = rule == null ? "" : stage + "/" + desired + ".wav";
                if (rule != null) uploads.put(desired, store.bytes(desired));
                changes.add(new AudioPlan.Change(e.path, replacement, e.current)); services.addAll(spec.services);
            }
            if (changes.isEmpty()) {if (scope == null) clearUncertain(); progress.accept("当前声音已符合配置，无需重复覆盖。"); return;}
            if (automatic) requireAutomaticAllowed();
            establish(ssh);
            long uploadBytes = 0; for (byte[] data : uploads.values()) uploadBytes += data.length;
            ssh.text("set -e; test \"$(df -k /run | tail -n 1 | awk '{print $4}')\" -gt " + (uploadBytes / 1024 + 32768) + "; mkdir " + q(stage), 10);
            // Save only pristine resources. Existing originals are checked rather than silently replaced.
            for (AudioPlan.Change c : changes) {
                SoundStore.Entry e = byPath.get(c.target); String original = originalRemote(catalog.spec(e.path));
                String existing = ssh.text("if [ -f " + q(original) + " ]; then sha256sum " + q(original) + " | cut -d ' ' -f 1; fi", 10).trim();
                if (existing.isEmpty()) ssh.upload(original, store.bytes(e.original));
                else FilesEx.require(existing.equals(e.original), "CP 原声备份不匹配，请先恢复并重新读取");
            }
            for (Map.Entry<String, byte[]> upload : uploads.entrySet()) {
                String path = stage + "/" + upload.getKey() + ".wav"; ssh.upload(path, upload.getValue());
                ssh.text("chmod 0444 " + q(path), 10);
            }
            if (automatic) requireAutomaticAllowed();
            progress.accept("临时切换 " + changes.size() + " 个声音，检查播放服务及覆盖摘要…");
            FilesEx.require(context.getSharedPreferences("settings", 0).edit().putBoolean("startupApplyUncertain", true).commit(), "无法保存覆盖任务状态");
            run(ssh, stage, AudioPlan.batch(changes, services, stage));
            if (measured != null) measured.stage("校验覆盖结果", 0);
            SoundSync.verify(expected, refresh(ssh));
            if (scope == null || !unresolved) clearUncertain();
            progress.accept("配置已应用，资源摘要和播放服务检查通过。实际车外/仪表声音仍需现场确认。");
        }
    }
    private void run(SshAccess ssh, String job, String script) throws Exception {
        ssh.upload(job + "/operation.sh", script.getBytes(StandardCharsets.UTF_8));
        ssh.text("command -v nohup >/dev/null; nohup /bin/sh " + q(job + "/operation.sh") + " > " + q(job + "/operation.log") + " 2>&1 < /dev/null &", 10);
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            String result = ssh.text("if [ -f " + q(job + "/result") + " ]; then cat " + q(job + "/result") + "; fi", 10).trim();
            if (!result.isEmpty()) {FilesEx.require(result.equals("PASS"), "音效切换结果：" + result + "；任务记录：" + job); return;}
            Thread.sleep(500);
        }
        throw new IOException("CP 任务结果尚未确认，不会自动重试。请检查：" + job);
    }
    void restoreLegacy(String password) throws Exception {
        try (SshAccess ssh = new SshAccess(context)) {
            ssh.connect(password); establish(ssh);
            String[] targets = {"/usr/avas/sound_type_1", SoundCatalog.CHIMES + "V3_Vehicle_Locked_Single.wav",
                SoundCatalog.CHIMES + "V3_Function_Enabled_Successful_Single.wav", SoundCatalog.CHIMES + "V3_Function_Exited_Single.wav"};
            String[] tags = {"avas", "lock", "cruise-0", "cruise-1"};
            Map<String, String> mounts = mounts(ssh);
            for (int i = 0; i < targets.length; i++) if (mounts.containsKey(targets[i]) && owned(mounts.get(targets[i]))) {
                String stage = ROOT + "/stage-" + UUID.randomUUID(); ssh.text("mkdir " + q(stage), 10);
                String service = i == 0 ? "app_1.8.service" : i == 1 ? "outdoor-audioplay.service" : "cluster-cmw-audioplay.service";
                run(ssh, stage, AudioPlan.build(targets[i], "", service, tags[i], stage));
            }
        }
        context.getSharedPreferences("settings", 0).edit().putBoolean("reapply", false).apply(); refresh(password);
    }
}

package com.kooo.evdashboard.core;

import org.junit.*;
import org.json.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class PresetApplyTest {
    private static final String STEREO = SoundCatalog.CHIMES + "V3_Vehicle_Locked_Single.wav", MONO = SoundCatalog.CHIMES + "V3_01_1_Fault_Alert_Chime_Single.wav",
        MISSING = SoundCatalog.CHIMES + "V3_02_1_Collision_Alert_Chime_Single.wav";
    private SoundCatalog catalog;
    private File folder;
    private SoundStore store;
    private String stereoOriginal, monoOriginal;
    private List<SoundStore.Entry> entries;

    private static JSONObject row(String path, String group) throws Exception {
        return new JSONObject().put("path", path).put("category", "常用").put("group", group).put("variant", "默认").put("name", group)
            .put("services", new JSONArray().put("outdoor-audioplay.service")).put("events", 1);
    }
    @Before public void setup() throws Exception {
        catalog = new SoundCatalog(new JSONArray().put(row(STEREO, "锁车提示")).put(row(MONO, "通用故障提示")).put(row(MISSING, "碰撞警告")).toString());
        folder = Files.createTempDirectory("preset-test-").toFile(); store = new SoundStore(folder, catalog);
        stereoOriginal = store.put(SoundWave.encode(new short[]{1000, 2000}, 48000, 2));
        monoOriginal = store.put(SoundWave.encode(new short[]{500, 600}, 48000, 1));
        entries = new ArrayList<>(Arrays.asList(new SoundStore.Entry(STEREO, stereoOriginal, stereoOriginal, "NONE"), new SoundStore.Entry(MONO, monoOriginal, monoOriginal, "NONE")));
    }
    @After public void cleanup() throws Exception {try (java.util.stream.Stream<Path> files = Files.walk(folder.toPath())) {for (Path p : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);}}
    private static byte[] stereo(int v) throws Exception {return SoundWave.encode(new short[]{(short) v, (short) (v + 1), (short) (v + 2), (short) (v + 3)}, 48000, 2);}

    @Test public void writesReplacementRulesResetsVolumeAndLeavesOtherSoundsAlone() throws Exception {
        String keptReplacement = store.put(SoundWave.encode(new short[]{7, 8}, 48000, 1));
        Map<String, SoundStore.Rule> before = new LinkedHashMap<>();
        before.put(STEREO, new SoundStore.Rule(stereoOriginal, "", 150));                      // volume set earlier: the preset replaces it
        before.put(MONO, new SoundStore.Rule(monoOriginal, keptReplacement, 60));              // not part of the preset: untouched
        store.rules(before);
        PresetApply.Result r = PresetApply.apply(store, catalog, entries, Collections.singletonMap(STEREO, stereo(100)));
        assertEquals(Collections.singletonList(STEREO), r.applied); assertTrue(r.skipped.isEmpty());
        SoundStore.Rule lock = store.rules().get(STEREO);
        assertEquals(stereoOriginal, lock.original); assertEquals(100, lock.gain); assertArrayEquals(stereo(100), store.bytes(lock.replacement));
        SoundStore.Rule other = store.rules().get(MONO); assertEquals(keptReplacement, other.replacement); assertEquals(60, other.gain);
    }
    @Test public void reportsEverySoundItCannotApplyAndChangesNothingWhenNoneApply() throws Exception {
        Map<String, byte[]> audio = new LinkedHashMap<>();
        audio.put(MONO, stereo(10));                                  // stereo replacement for a mono original
        audio.put(MISSING, SoundWave.encode(new short[]{1, 2}, 48000, 1));   // not in this CP's snapshot
        PresetApply.Result r = PresetApply.apply(store, catalog, entries, audio);
        assertTrue(r.applied.isEmpty()); assertEquals(2, r.skipped.size());
        assertEquals("音频格式与系统原声不一致", r.skipped.get(MONO)); assertEquals("当前 CP 没有这个声音", r.skipped.get(MISSING));
        assertTrue(store.rules().isEmpty());
    }
    @Test public void refusesSoundsWithForeignOverlaysOrNoOriginalYet() throws Exception {
        entries.set(0, new SoundStore.Entry(STEREO, stereoOriginal, stereoOriginal, "FOREIGN"));
        entries.set(1, new SoundStore.Entry(MONO, "", "", "NONE"));
        Map<String, byte[]> audio = new LinkedHashMap<>(); audio.put(STEREO, stereo(10)); audio.put(MONO, SoundWave.encode(new short[]{1, 2}, 48000, 1));
        PresetApply.Result r = PresetApply.apply(store, catalog, entries, audio);
        assertTrue(r.applied.isEmpty()); assertEquals("已有其他覆盖，请先恢复", r.skipped.get(STEREO)); assertEquals("尚未读取到系统原声", r.skipped.get(MONO));
    }
    @Test public void applyingTwiceIsStable() throws Exception {
        Map<String, byte[]> audio = Collections.singletonMap(STEREO, stereo(40));
        PresetApply.apply(store, catalog, entries, audio); String first = store.rules().get(STEREO).replacement;
        PresetApply.apply(store, catalog, entries, audio);
        assertEquals(first, store.rules().get(STEREO).replacement); assertEquals(1, store.rules().size());
    }
}

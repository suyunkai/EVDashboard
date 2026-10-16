package com.kooo.evdashboard.core;

import org.junit.Test;
import org.json.*;
import java.io.File;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

public class SoundPresetTest {
    private static final String LOCK = SoundCatalog.CHIMES + "V3_Vehicle_Locked_Single.wav", ALERT = SoundCatalog.CHIMES + "V3_01_1_Fault_Alert_Chime_Single.wav",
        UNCONFIRMED = SoundCatalog.CHIMES + "V3_Vehicle_Charging_Single.wav";
    private static final String SHA = "a".repeat(64);

    private static SoundCatalog catalog() throws Exception {
        JSONArray rows = new JSONArray();
        rows.put(row(LOCK, "常用", "锁车提示", "普通版", true)).put(row(ALERT, "警报", "通用故障提示", "默认", true)).put(row(UNCONFIRMED, "常用", "充电提示", "普通版·调用待确认", false));
        return new SoundCatalog(rows.toString());
    }
    private static JSONObject row(String path, String category, String group, String variant, boolean writable) throws Exception {
        return new JSONObject().put("path", path).put("category", category).put("group", group).put("variant", variant).put("name", group)
            .put("services", writable ? new JSONArray().put("outdoor-audioplay.service") : new JSONArray()).put("events", 1);
    }
    private static JSONObject sound(String path, String category, String sha, String file) throws Exception {
        return new JSONObject().put("path", path).put("category", category).put("sha256", sha).put("file", file).put("seconds", 1.5);
    }
    private static String preset(JSONObject... sounds) throws Exception {
        JSONArray list = new JSONArray(); for (JSONObject s : sounds) list.put(s);
        return new JSONObject().put("format", "instrument-preset").put("version", 1).put("id", "demo").put("name", "演示").put("description", "说明").put("sounds", list).toString();
    }
    private static JSONObject lock() throws Exception {return sound(LOCK, "常用", SHA, "audio/" + SHA + ".wav");}

    @Test public void groupsSoundsByCategoryInDisplayOrderAndLabelsVariants() throws Exception {
        SoundPreset p = SoundPreset.parse(preset(sound(ALERT, "警报", "b".repeat(64), "audio/" + "b".repeat(64) + ".wav"), lock()), catalog());
        assertEquals(Arrays.asList("常用", "警报"), p.categories());
        assertEquals("锁车提示 · 普通版", p.inCategory("常用").get(0).label());
        assertEquals("通用故障提示", p.inCategory("警报").get(0).label());      // the default variant is not repeated
        assertEquals(2, p.items().size());
        assertTrue(p.inCategory("低速").isEmpty());
    }
    @Test public void rejectsSoundsThatCouldNeverBeApplied() throws Exception {
        assertThrows(Exception.class, () -> SoundPreset.parse(preset(sound(UNCONFIRMED, "常用", SHA, "audio/" + SHA + ".wav")), catalog()));       // mapping unconfirmed
        assertThrows(Exception.class, () -> SoundPreset.parse(preset(lock(), lock()), catalog()));                                                  // duplicate path
        assertThrows(Exception.class, () -> SoundPreset.parse(preset(sound(LOCK, "警报", SHA, "audio/" + SHA + ".wav")), catalog()));              // wrong category
        assertThrows(Exception.class, () -> SoundPreset.parse(preset(sound("/etc/passwd.wav", "常用", SHA, "audio/" + SHA + ".wav")), catalog()));  // outside the allowed roots
    }
    @Test public void rejectsUnsafeOrInconsistentAudioReferences() throws Exception {
        assertThrows(Exception.class, () -> SoundPreset.parse(preset(sound(LOCK, "常用", SHA, "../" + SHA + ".wav")), catalog()));
        assertThrows(Exception.class, () -> SoundPreset.parse(preset(sound(LOCK, "常用", SHA, "audio/" + "c".repeat(64) + ".wav")), catalog()));    // file != digest
        assertThrows(Exception.class, () -> SoundPreset.parse(preset(sound(LOCK, "常用", "xyz", "audio/xyz.wav")), catalog()));
        assertThrows(Exception.class, () -> SoundPreset.parse(preset(), catalog()));
        assertThrows(Exception.class, () -> SoundPreset.parse(preset(lock()).replace("instrument-preset", "other"), catalog()));
    }
    @Test public void bundledPresetsMatchTheCatalogAndTheirAudioFiles() throws Exception {
        SoundCatalog real = new SoundCatalog(new String(Files.readAllBytes(Path.of("src/main/assets/sounds/catalog.json")), java.nio.charset.StandardCharsets.UTF_8));
        File[] folders = new File("src/main/assets/presets").listFiles(File::isDirectory);
        assertNotNull("no bundled presets", folders); assertTrue("the 8bit preset ships with the repository", new File("src/main/assets/presets/8bit/preset.json").isFile());
        for (File folder : folders) {
            SoundPreset p = SoundPreset.parse(new String(Files.readAllBytes(new File(folder, "preset.json").toPath()), java.nio.charset.StandardCharsets.UTF_8), real);
            assertEquals(folder.getName(), p.id); assertFalse(p.items().isEmpty());
            for (SoundPreset.Item item : p.items()) {
                byte[] bytes = Files.readAllBytes(new File(folder, item.file).toPath());
                assertEquals(item.path, item.sha256, FilesEx.hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                SoundWave wave = new SoundWave(bytes);
                assertEquals(item.path, 48000, wave.rate); assertTrue(item.path, wave.channels == 1 || wave.channels == 2); assertTrue(item.path, bytes.length <= SoundWave.MAX_BYTES);
            }
        }
    }
}

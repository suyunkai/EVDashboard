package com.kooo.evdashboard.core;

import org.junit.Test;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class TranslationsTest {
    private final Translations t;
    public TranslationsTest() throws Exception {t = new Translations(new String(Files.readAllBytes(Path.of("src/main/assets/i18n/en.json")), java.nio.charset.StandardCharsets.UTF_8));}
    @Test public void longestMatchHandlesDynamicStatusesWithoutChangingIdentifiers() {
        assertEquals("Read 51 files · Common 11 items", t.translate("已读取 51 个素材 · 常用 11 项"));
        assertEquals("Audio playback failed: 12 / 3", t.translate("音频播放失败：12 / 3"));
        assertEquals("/usr/avas/sound_type_1/01.wav", t.translate("/usr/avas/sound_type_1/01.wav"));
    }
    @Test public void userNamesAndPathsArePreservedInBothLanguages() throws Exception {
        String name = "常用原声-测试.wav";
        assertEquals("Export sounds: " + name, new Translations(new JSONObject().put("导出：", "Export sounds: ").put("原声", "Original").toString()).translate("导出：" + Translations.literal(name)));
        assertEquals(name, Translations.unmark(Translations.literal(name)));
        assertEquals("安全", Translations.unmark(Translations.literal("安\ue000全\ue001")));
    }
    @Test public void everyCatalogSoundAndEveryDictionaryValueIsEnglish() throws Exception {
        JSONArray rows = new JSONArray(new String(Files.readAllBytes(Path.of("src/main/assets/sounds/catalog.json")), java.nio.charset.StandardCharsets.UTF_8));
        for (int i = 0; i < rows.length(); i++) for (String key : List.of("name", "group", "variant", "category"))
            assertFalse(rows.getJSONObject(i).optString(key), hasHan(t.translate(rows.getJSONObject(i).optString(key))));
        JSONObject map = new JSONObject(new String(Files.readAllBytes(Path.of("src/main/assets/i18n/en.json")), java.nio.charset.StandardCharsets.UTF_8));
        for (Iterator<String> keys = map.keys(); keys.hasNext();) {String key = keys.next();assertFalse(key, map.getString(key).isEmpty()); assertFalse(key, hasHan(t.translate(key)));}
    }
    private boolean hasHan(String value) {return value.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);}
}

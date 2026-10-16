package com.kooo.evdashboard;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import com.kooo.evdashboard.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Presets shipped inside the APK (assets/presets/&lt;id&gt;/preset.json + audio/&lt;sha256&gt;.wav). Missing or damaged ones are skipped. */
final class BuiltinPresets {
    private static final String ROOT = "presets";
    private final AssetManager assets;
    private final SoundCatalog catalog;
    private List<SoundPreset> loaded;

    BuiltinPresets(Context context, SoundCatalog catalog) {this.assets = context.getAssets(); this.catalog = catalog;}

    synchronized List<SoundPreset> list() {
        if (loaded != null) return loaded;
        List<SoundPreset> result = new ArrayList<>();
        try {
            String[] folders = assets.list(ROOT);
            if (folders != null) {
                Arrays.sort(folders);
                for (String folder : folders) {
                    try {result.add(SoundPreset.parse(text(ROOT + "/" + folder + "/preset.json"), catalog));}
                    catch (Exception e) {Log.w("BuiltinPresets", "Skipping preset " + folder, e);}
                }
            }
        } catch (IOException e) {Log.w("BuiltinPresets", "Cannot list presets", e);}
        result.sort(Comparator.comparingInt(p -> order(p.id)));
        return loaded = result;
    }

    /** The 8-bit set first, then the game-audio set; anything else after them. */
    private static int order(String id) {return id.equals("8bit") ? 0 : id.equals("botw") ? 1 : 2;}

    /** One replacement WAV, verified against the digest recorded in preset.json. */
    byte[] audio(SoundPreset preset, SoundPreset.Item item) throws Exception {
        try (InputStream in = assets.open(ROOT + "/" + preset.id + "/" + item.file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); FilesEx.transfer(in, out);
            byte[] bytes = out.toByteArray();
            FilesEx.require(bytes.length <= SoundWave.MAX_BYTES, "内置音频过大");
            FilesEx.require(FilesEx.hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(item.sha256), "内置音频校验失败");
            return bytes;
        }
    }

    private String text(String path) throws IOException {
        try (InputStream in = assets.open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); FilesEx.transfer(in, out);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}

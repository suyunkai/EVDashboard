package com.kooo.evdashboard.core;

import org.junit.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class SoundStoreTest {
    private static final String PATH = SoundCatalog.CHIMES + "V3_Vehicle_Locked_Single.wav";
    private SoundCatalog catalog;
    private File folder;
    private SoundStore store;
    private String original, replacement;
    @Before public void setup() throws Exception {
        catalog = new SoundCatalog(new JSONArray().put(new JSONObject().put("path", PATH).put("category","常用").put("group","锁车提示").put("variant","普通版").put("name","锁车提示")
            .put("services",new JSONArray().put("outdoor-audioplay.service")).put("events",1)).toString());
        folder = Files.createTempDirectory("sounds-test-").toFile(); store = new SoundStore(folder, catalog);
        original = store.put(SoundWave.encode(new short[]{1000,2000},48000,2));
        replacement = store.put(SoundWave.encode(new short[]{3000,4000},48000,2));
    }
    @After public void cleanup() throws Exception {try (java.util.stream.Stream<Path> files = Files.walk(folder.toPath())) {for (Path p : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);}}
    private void configure(int gain) throws Exception {store.rules(Collections.singletonMap(PATH,new SoundStore.Rule(original,replacement,gain)));}
    @Test public void gainNeverCompoundsOrOverwritesSources() throws Exception {
        byte[] before = store.bytes(replacement); configure(50); String half = store.render(store.rules().get(PATH));
        assertNotEquals(replacement,half); configure(100); assertEquals(replacement,store.render(store.rules().get(PATH)));
        configure(50); assertEquals(half,store.render(store.rules().get(PATH))); assertArrayEquals(before,store.bytes(replacement));
    }
    @Test public void zipRoundTripPreservesOriginalSourceGainAndMute() throws Exception {
        configure(0); ByteArrayOutputStream out = new ByteArrayOutputStream(); store.exportZip(out);
        SoundStore other = new SoundStore(new File(folder,"other"),catalog); other.importZip(new ByteArrayInputStream(out.toByteArray()));
        SoundStore.Rule r = other.rules().get(PATH); assertEquals(original,r.original); assertEquals(replacement,r.replacement); assertEquals(0,r.gain);
        assertArrayEquals(store.bytes(original),other.bytes(original)); assertArrayEquals(store.bytes(replacement),other.bytes(replacement));
        assertArrayEquals(store.bytes(store.render(store.rules().get(PATH))),other.bytes(other.render(r)));
    }
    @Test public void fivePresetsEnforcedAndSwitchRestoresIndependentRules() throws Exception {
        for (int i=0;i<5;i++) {configure(i*25); store.savePreset("preset " + i);}
        assertThrows(Exception.class,()->store.savePreset("sixth")); assertEquals(5,store.presets().size());
        SoundStore.Preset first=store.presets().stream().filter(p->p.name.equals("preset 0")).findFirst().get();
        store.loadPreset(first.id); assertEquals(0,store.rules().get(PATH).gain);
        store.deletePreset(first.id); store.savePreset("replacement preset"); assertEquals(5,store.presets().size());
    }
    private byte[] archive(Map<String,byte[]> entries) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)) {for(Map.Entry<String,byte[]> entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return out.toByteArray();
    }
    @Test public void badZipCannotChangeCurrentConfigurationOrEscapeDirectory() throws Exception {
        configure(75); Map<String,byte[]> bad=new LinkedHashMap<>();bad.put("../escape.wav",store.bytes(original));
        assertThrows(Exception.class,()->store.importZip(new ByteArrayInputStream(archive(bad))));assertEquals(75,store.rules().get(PATH).gain);assertFalse(new File(folder.getParentFile(),"escape.wav").exists());
        bad.clear();bad.put("manifest.json",Files.readAllBytes(new File(folder,"rules.json").toPath()));
        assertThrows(Exception.class,()->store.importZip(new ByteArrayInputStream(archive(bad))));assertEquals(75,store.rules().get(PATH).gain);
        bad.put("audio/"+original+".wav",store.bytes(replacement));bad.put("audio/"+replacement+".wav",store.bytes(replacement));
        assertThrows(Exception.class,()->store.importZip(new ByteArrayInputStream(archive(bad))));assertEquals(75,store.rules().get(PATH).gain);
    }
    @Test public void incompatibleChannelsAndUnmappedPathsRejected() throws Exception {
        String mono=store.put(SoundWave.encode(new short[]{1,2},48000,1));
        assertThrows(Exception.class,()->store.rules(Collections.singletonMap(PATH,new SoundStore.Rule(original,mono,100))));
        assertThrows(Exception.class,()->store.rules(Collections.singletonMap("/etc/shadow",new SoundStore.Rule(original,replacement,100))));
        assertFalse(catalog.spec("/usr/avas/sound_type_1/unknown.wav").writable());
    }
    @Test public void corruptAssetCannotBeExportedOrApplied() throws Exception {
        configure(100);Files.write(store.asset(replacement).toPath(),new byte[]{1,2});
        assertThrows(Exception.class,()->store.exportZip(new ByteArrayOutputStream()));
    }
}

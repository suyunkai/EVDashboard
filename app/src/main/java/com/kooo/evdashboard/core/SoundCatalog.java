package com.kooo.evdashboard.core;

import org.json.*;
import java.util.*;

/** Verified mapping metadata; only files actually discovered on this CP become visible. */
public final class SoundCatalog {
    public static final String CHIMES = "/usr/share/chime-contents/V3_Geely_DNA_DHU/";
    public static final String[] CATEGORIES = {"常用", "警报", "雷达", "低速", "参考", "诊断"};
    public static final String[] ROOTS = {CHIMES, "/usr/avas/sound_type_1/", "/usr/avas/sound_type_2/",
        "/usr/avas/sound_type_3/", "/etc/cp_bootanimation/", "/usr/bin/cluster_service/wav/", "/usr/share/sounds/alsa/"};
    public static final class Spec {
        public final String path, category, group, variant, name;
        public final List<String> services;
        public final int events;
        Spec(String path, String category, String group, String variant, String name, List<String> services, int events) {
            this.path = path; this.category = category; this.group = group; this.variant = variant;
            this.name = name; this.services = Collections.unmodifiableList(services); this.events = events;
        }
        public boolean writable() {return !services.isEmpty();}
        public String tag() throws Exception {return "sound-" + FilesEx.hashText(path).substring(0, 24);}
    }
    private final Map<String, Spec> known = new LinkedHashMap<>();
    public SoundCatalog(String json) throws Exception {
        JSONArray array = new JSONArray(json);
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i); String path = o.getString("path");
            FilesEx.require(allowedPath(path), "声音目录包含非法路径");
            List<String> services = new ArrayList<>(); JSONArray list = o.getJSONArray("services");
            for (int j = 0; j < list.length(); j++) {String s = list.getString(j); FilesEx.require(allowedService(s), "不支持的声音服务"); services.add(s);}
            known.put(path, new Spec(path, o.getString("category"), o.getString("group"), o.getString("variant"),
                o.getString("name"), services, o.getInt("events")));
        }
    }
    public Spec spec(String path) throws Exception {
        FilesEx.require(allowedPath(path), "声音路径不在允许的资源目录内");
        Spec found = known.get(path);
        return found != null ? found : new Spec(path, "参考", path.substring(path.lastIndexOf('/') + 1),
            "映射待确认", path.substring(path.lastIndexOf('/') + 1), new ArrayList<>(), 0);
    }
    public static boolean allowedPath(String path) {
        for (String root : ROOTS) if (path.startsWith(root) && path.substring(root.length()).matches("[A-Za-z0-9_.-]+\\.[wW][aA][vV]")) return true;
        return false;
    }
    public static boolean allowedService(String service) {
        return service.equals("app_1.8.service") || service.equals("cluster-cmw-audioplay.service") || service.equals("outdoor-audioplay.service");
    }
}

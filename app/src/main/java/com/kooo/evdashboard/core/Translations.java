package com.kooo.evdashboard.core;

import org.json.JSONObject;
import java.util.*;

/** Display-only translations. Stored rules, paths, identifiers and user input remain unchanged. */
public final class Translations {
    public static final char LITERAL_START = '\ue000', LITERAL_END = '\ue001';
    private static final class Node {final Map<Character, Node> next = new HashMap<>(); String value;}
    private final Node root = new Node();
    public Translations(String json) throws Exception {
        JSONObject entries = new JSONObject(json);
        for (Iterator<String> keys = entries.keys(); keys.hasNext();) {
            String key = keys.next();
            FilesEx.require(!key.isEmpty(), "Empty translation key"); Node n = root;
            for (int i = 0; i < key.length(); i++) n = n.next.computeIfAbsent(key.charAt(i), ignored -> new Node());
            n.value = entries.getString(key);
        }
    }
    public static String literal(String value) {return LITERAL_START + value.replace(String.valueOf(LITERAL_START), "").replace(String.valueOf(LITERAL_END), "") + LITERAL_END;}
    public static String unmark(String value) {return value.replace(String.valueOf(LITERAL_START), "").replace(String.valueOf(LITERAL_END), "");}
    public String translate(String text) {
        StringBuilder out = new StringBuilder();
        for (int at = 0; at < text.length();) {
            if (text.charAt(at) == LITERAL_START) {
                int end = text.indexOf(LITERAL_END, at + 1); if (end < 0) end = text.length();
                out.append(text, at + 1, end); at = Math.min(text.length(), end + 1); continue;
            }
            Node n = root; String value = null; int end = at;
            for (int i = at; i < text.length(); i++) {n = n.next.get(text.charAt(i)); if (n == null) break; if (n.value != null) {value = n.value; end = i + 1;}}
            if (value == null) out.append(text.charAt(at++)); else {out.append(value); at = end;}
        }
        return out.toString();
    }
}

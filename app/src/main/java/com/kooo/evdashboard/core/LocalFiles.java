package com.kooo.evdashboard.core;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Browser cannot escape the selected volume, follow symlinks or overwrite existing files. */
public final class LocalFiles {
    private LocalFiles() {}
    public static File within(File root, File target) throws IOException {
        File r = root.getCanonicalFile(), f = target.getCanonicalFile();
        FilesEx.require(f.equals(r) || f.toPath().startsWith(r.toPath()), "所选路径不在当前存储设备内");
        FilesEx.require(!Files.isSymbolicLink(target.toPath()), "不支持符号链接");
        return f;
    }
    public static List<File> list(File root, File folder, boolean audio, boolean directoriesOnly) throws IOException {
        return list(root, folder, audio ? "audio" : "image", directoriesOnly);
    }
    public static List<File> list(File root, File folder, String kind, boolean directoriesOnly) throws IOException {
        File dir = within(root, folder);
        File[] entries = dir.listFiles();
        FilesEx.require(entries != null, "无法读取目录；请检查文件管理权限或 U 盘是否已挂载");
        List<File> result = new ArrayList<>();
        for (File f : entries) {
            if (Files.isSymbolicLink(f.toPath())) continue;
            if (f.isDirectory() || (!directoriesOnly && f.isFile() && (kind.equals("zip") ? f.getName().toLowerCase(Locale.ROOT).endsWith(".zip") : accepted(f.getName(), kind.equals("audio"))))) result.add(f);
        }
        result.sort(Comparator.comparing((File f) -> !f.isDirectory()).thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }
    public static boolean accepted(String name, boolean audio) {
        String lower = name.toLowerCase(Locale.ROOT);
        return audio ? lower.matches(".*\\.(wav|mp3|m4a|aac|ogg|flac|opus|amr)") : lower.matches(".*\\.(img|bin)");
    }
    public static File destination(File root, File folder, String name, File protectedLibrary) throws IOException {
        FilesEx.require(name != null && !name.isBlank() && name.length() <= 160 && !name.equals(".") && !name.equals("..")
            && name.chars().noneMatch(c -> c < 32 || c == '/' || c == '\\'), "请输入文件名，不能包含路径或控制字符");
        File dir = within(root, folder), target = within(root, new File(dir, name));
        FilesEx.require(dir.isDirectory() && dir.canWrite(), "当前目录不可写");
        if (protectedLibrary != null) FilesEx.require(!target.toPath().startsWith(protectedLibrary.getCanonicalFile().toPath()), "请另存到镜像库以外的目录，不能写入受保护的镜像库");
        FilesEx.require(!target.exists(), "同名文件已存在；请选择其他文件名，不覆盖任何已有文件");
        return target;
    }
}

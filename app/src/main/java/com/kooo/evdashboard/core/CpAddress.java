package com.kooo.evdashboard.core;

import java.io.IOException;

/** Numeric IPv4 only: no DNS, URLs, ports or shell syntax. */
public final class CpAddress {
    public static final String DEFAULT = "198.18.44.2";
    private CpAddress() {}

    public static String parse(String input) throws IOException {
        String value = input == null ? "" : input.trim();
        String[] parts = value.split("\\.", -1);
        FilesEx.require(parts.length == 4, "请输入 CP 的 IPv4 地址，例如 198.18.44.2（不包含端口）");
        int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) {
            FilesEx.require(parts[i].matches("0|[1-9][0-9]{0,2}"), "IP 地址格式不正确，不接受前导零");
            bytes[i] = Integer.parseInt(parts[i]);
            FilesEx.require(bytes[i] <= 255, "IP 地址每一段必须在 0–255 之间");
        }
        FilesEx.require(bytes[0] > 0 && bytes[0] != 127 && bytes[0] < 224, "请填写可连接的 CP 单播地址，不能使用本机、组播或广播地址");
        return value;
    }
}

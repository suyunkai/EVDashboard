package com.kooo.evdashboard.core;

import java.io.*;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;

/** Lossless packaging only: digests always describe the decoded, original WAV. */
public final class PresetAudio {
    private PresetAudio() {}
    public static byte[] read(InputStream input, boolean gzip, String expected) throws Exception {
        FilesEx.require(expected.matches("[a-f0-9]{64}"), "无效的内置音频摘要");
        try (InputStream in = gzip ? new GZIPInputStream(input) : input;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                FilesEx.require(out.size() + n <= SoundWave.MAX_BYTES, "内置音频过大");
                out.write(buffer, 0, n);
            }
            byte[] bytes = out.toByteArray();
            FilesEx.require(FilesEx.hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected), "内置音频校验失败");
            new SoundWave(bytes);
            return bytes;
        }
    }
}

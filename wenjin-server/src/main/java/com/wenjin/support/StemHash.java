package com.wenjin.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 题干指纹：与 MySQL {@code SHA2(TRIM(stem), 256)} 对齐的 SHA-256 小写十六进制。
 */
public final class StemHash {

    private StemHash() {
    }

    /**
     * 计算题干指纹。{@code null} 按空串；两侧空白先 trim（与迁移 backfill 一致）。
     */
    public static String sha256Hex(String stem) {
        String normalized = stem == null ? "" : stem.trim();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return toHexLower(dig);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHexLower(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0f];
        }
        return new String(out);
    }
}

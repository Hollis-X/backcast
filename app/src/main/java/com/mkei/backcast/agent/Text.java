package com.mkei.backcast.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small string and digest helpers shared across packages. */
public final class Text {
    private Text() { }

    /** Lowercase hexadecimal form of the bytes. */
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 15, 16));
            out.append(Character.forDigit(value & 15, 16));
        }
        return out.toString();
    }

    /** SHA-256 of the UTF-8 bytes as lowercase hex. */
    public static String sha256Hex(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** At most {@code limit} chars, never ending between the halves of a surrogate pair. */
    public static String clip(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        int end = limit;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }
}

package com.suanla.relayq.core.support;

import java.nio.charset.StandardCharsets;

public final class ByteTruncator {

    private ByteTruncator() {
    }

    public static Result truncate(String text, int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative: " + maxBytes);
        }
        if (text == null) {
            return new Result(null, 0, false);
        }

        int originalByteLength = text.getBytes(StandardCharsets.UTF_8).length;
        if (originalByteLength <= maxBytes) {
            return new Result(text, originalByteLength, false);
        }

        int byteLength = 0;
        int endIndex = 0;
        while (endIndex < text.length()) {
            int codePoint = text.codePointAt(endIndex);
            int codePointBytes = utf8Length(codePoint);
            if (byteLength + codePointBytes > maxBytes) {
                break;
            }
            byteLength += codePointBytes;
            endIndex += Character.charCount(codePoint);
        }
        return new Result(text.substring(0, endIndex), originalByteLength, true);
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    public record Result(String text, int originalByteLength, boolean truncated) {
    }
}

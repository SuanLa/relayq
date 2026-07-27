package com.suanla.relayq.core.support;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteTruncatorTest {

    @Test
    void shouldNotSplitThreeByteChineseCharacter() {
        ByteTruncator.Result result = ByteTruncator.truncate("中文A", 4);

        assertEquals("中", result.text());
        assertEquals(7, result.originalByteLength());
        assertTrue(result.truncated());
        assertEquals("中", new String(result.text().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    void shouldKeepChineseCharacterAtExactBoundary() {
        ByteTruncator.Result result = ByteTruncator.truncate("中文", 3);

        assertEquals("中", result.text());
        assertTrue(result.truncated());
    }

    @Test
    void shouldNotSplitFourByteEmoji() {
        ByteTruncator.Result tooShort = ByteTruncator.truncate("😀A", 3);
        ByteTruncator.Result exact = ByteTruncator.truncate("😀A", 4);

        assertEquals("", tooShort.text());
        assertEquals("😀", exact.text());
        assertTrue(tooShort.truncated());
        assertTrue(exact.truncated());
    }

    @Test
    void shouldReportNotTruncatedWhenWithinLimit() {
        ByteTruncator.Result result = ByteTruncator.truncate("中文😀", 10);

        assertEquals("中文😀", result.text());
        assertEquals(10, result.originalByteLength());
        assertFalse(result.truncated());
    }
}

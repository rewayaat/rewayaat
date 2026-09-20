package com.rewayaat.controllers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Arabic blurbs load, and the hero gets text rather than markup. */
class BookBlurbsTest {

    private final BookBlurbs blurbs = new BookBlurbs();

    @Test
    @DisplayName("the Arabic blurb is served for an Arabic page")
    void arabicBlurbIsServed() {
        String arabic = blurbs.forSlug("al-kafi", true);
        assertNotNull(arabic, "al-kafi should carry an Arabic blurb");
        assertTrue(arabic.codePoints().anyMatch(c -> c >= 0x0600 && c <= 0x06FF),
                "expected Arabic script, got: " + arabic.substring(0, Math.min(60, arabic.length())));
    }

    @Test
    @DisplayName("the hero summary is plain text, never the blurb's markup")
    void arabicSummaryIsUnwrapped() {
        // The hero renders with th:text. Handing it the blurb's HTML printed the tags
        // to the reader - "<h2>الكافي</h2><p>..." - on every Arabic book page.
        String summary = blurbs.summaryForSlug("al-kafi", true);
        assertNotNull(summary);
        assertFalse(summary.contains("<"), "hero summary must carry no markup: " + summary);
        assertFalse(summary.contains("&lt;"), "nor escaped markup: " + summary);
    }

    @Test
    @DisplayName("English pages are untouched")
    void englishIsUnchanged() {
        assertTrue(blurbs.forSlug("al-kafi").startsWith("<"), "the English blurb is still HTML");
        String english = blurbs.summaryForSlug("al-kafi", false);
        if (english != null) {
            assertFalse(english.contains("<"), "the English hero summary is plain text");
        }
    }
}

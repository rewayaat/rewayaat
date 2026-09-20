package com.rewayaat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped translations load, and a missing one falls back rather than blanking. */
class ArabicNamesTest {

    @Test
    @DisplayName("the mapping resources load from the classpath")
    void translationsAreOnTheClasspath() {
        // The files live in src/main/resources/i18n and are also what the Elasticsearch
        // loader reads, so a packaging mistake shows up here rather than as empty pages.
        // 6,812 of the 7,708 chapter titles are translated. The rest are present in the
        // file with an empty value and are skipped on load, which is what makes the
        // fallback below the normal case rather than an error path.
        assertEquals(6812, ArabicNames.coverage().get("chapters"));
        assertTrue(ArabicNames.coverage().get("books") >= 10,
                "every book a hub page exists for needs a name: " + ArabicNames.coverage());
    }

    @Test
    @DisplayName("books and chapters resolve to Arabic")
    void namesResolve() {
        assertEquals("الكافي", ArabicNames.book("Al-Kāfi"));
        assertEquals("روايات عن الرضا (ع)", ArabicNames.chapter("Traditions about Ar-Ridha (a.s.)"));
    }

    @Test
    @DisplayName("an untranslated name returns null so the caller can fall back")
    void missingTranslationsAreNull() {
        assertNull(ArabicNames.chapter("A chapter title that was never translated"));
        assertNull(ArabicNames.book(null));
        assertNull(ArabicNames.book(""));
    }
}

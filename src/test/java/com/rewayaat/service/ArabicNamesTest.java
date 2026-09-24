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
        // 7,704 of the 7,710 chapter titles are translated, taken from thaqalayn's own
        // Arabic pages. The handful left out are rows whose stored Arabic was English or
        // machine-translation debris; they carry no entry, which is what makes the
        // fallback below the normal case rather than an error path.
        assertEquals(7704, ArabicNames.coverage().get("chapters"));
        assertTrue(ArabicNames.coverage().get("books") >= 10,
                "every book a hub page exists for needs a name: " + ArabicNames.coverage());
    }

    @Test
    @DisplayName("books and chapters resolve to Arabic")
    void namesResolve() {
        assertEquals("الكافي", ArabicNames.book("Al-Kāfi"));
        assertEquals("أخبار عن الرضا عليه السلام",
                ArabicNames.chapter("Traditions about Ar-Ridha (a.s.)"));
    }

    @Test
    @DisplayName("an untranslated name returns null so the caller can fall back")
    void missingTranslationsAreNull() {
        assertNull(ArabicNames.chapter("A chapter title that was never translated"));
        assertNull(ArabicNames.book(null));
        assertNull(ArabicNames.book(""));
    }
}

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
        // The chapter titles are taken from thaqalayn's own Arabic pages. A few rows carry
        // no entry — their stored Arabic was English or machine-translation debris — which
        // is what makes the fallback below the normal case rather than an error path.
        //
        // A floor, not an exact count. The point of the assertion is that the resource
        // loaded at all; pinning the number meant every legitimate addition failed the
        // build, which is what happened when production turned out to spell nineteen
        // Al-Khisal chapters differently and needed keys of its own.
        assertTrue(ArabicNames.coverage().get("chapters") >= 7724,
                "chapter titles did not load: " + ArabicNames.coverage());
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

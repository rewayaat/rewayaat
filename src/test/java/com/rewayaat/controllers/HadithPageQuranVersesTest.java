package com.rewayaat.controllers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how the hadith page turns the Quranic insight overview into the verses it renders
 * server-side, so a crawler sees them without the TAFSIR panel's XHR.
 */
class HadithPageQuranVersesTest {

    @Test
    void eachCandidateBecomesAVerseWithItsReference() {
        Map<String, Object> overview = Map.of("count", 1, "candidates", List.of(Map.of(
                "verse_key", "2:255",
                "surah_name_english", "Al-Baqarah",
                "text_arabic", "اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ",
                "text_english", "Allah - there is no deity except Him")));

        List<Map<String, String>> verses = HadithPageController.quranVerses(overview);

        assertEquals(1, verses.size());
        assertEquals("Al-Baqarah (2:255)", verses.get(0).get("reference"));
        assertEquals("اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ", verses.get(0).get("arabic"));
        assertEquals("Allah - there is no deity except Him", verses.get(0).get("english"));
    }

    @Test
    void aCandidateWithNoTextIsSkipped() {
        Map<String, Object> overview = Map.of("candidates", List.of(
                Map.of("verse_key", "1:1", "surah_name_english", "Al-Fatihah"),
                Map.of("verse_key", "1:2", "text_english", "Praise be to Allah")));

        List<Map<String, String>> verses = HadithPageController.quranVerses(overview);

        assertEquals(1, verses.size());
        assertEquals("1:2", verses.get(0).get("reference"));
    }

    @Test
    void aNarrationWithNoConnectionsRendersNothing() {
        // insightOverview answers without "candidates" when the narration has no document.
        assertTrue(HadithPageController.quranVerses(Map.of("ok", true, "count", 0)).isEmpty());
        assertTrue(HadithPageController.quranVerses(Map.of("count", 0, "candidates", List.of())).isEmpty());
    }
}

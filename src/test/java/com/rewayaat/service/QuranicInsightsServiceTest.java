package com.rewayaat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuranicInsightsServiceTest {

    private final QuranicInsightsService service = new QuranicInsightsService();

    /** Map.of stops at ten pairs; an index candidate has more. */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> v2Document() {
        return Map.of(
                "hadith_id", "Man-La-Yahduruh-al-Faqih-Volume-4-Saduq:203",
                "candidate_count", 1,
                "quotes", List.of(
                        Map.of("verse_key", "4:93", "source", "arabic_quote", "field", "arabic",
                                "start", 80, "end", 121, "text", "وَ مَنْ يَقْتُلْ مُؤْمِناً مُتَعَمِّداً"),
                        // A span the page could not place is dropped, not passed on.
                        Map.of("verse_key", "4:93", "field", "chain", "start", 0, "end", 3, "text", "x")),
                "candidates", List.of(map(
                        "verse_key", "4:93",
                        "surah_number", 4,
                        "ayah_number", 93,
                        "surah_name_english", "An-Nisaa",
                        "surah_name_arabic", "النساء",
                        "kind", "quoted",
                        "reason", "The Imam explains which killer this verse means.",
                        "reason_ar", "يبيّن الإمام من هو القاتل المتعمد في هذه الآية.",
                        "quoted", true,
                        "combined_score", 1.0,
                        "tafsir_snippets", List.of(
                                Map.of("tafsir_slug", "ar-amthal", "tafsir_name", "تفسير الأمثل",
                                        "commentary_text", "نص", "language", "ar", "preview", "نص",
                                        "commentary_score", 99.0),
                                map("tafsir_slug", "enlightening-commentary",
                                        "tafsir_name", "An Enlightening Commentary",
                                        "commentary_text", "Full passage.\n\nSecond paragraph.",
                                        "commentary_text_highlighted", "<em>Full passage.</em>\n\nSecond paragraph.",
                                        "language", "en", "preview", "Full passage.",
                                        "commentary_score", 100.0)))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void v2LinkCarriesKindReasonAndPreview() {
        Map<String, Object> out = service.overviewFromSource(v2Document(), "x", false, true);
        Map<String, Object> verse = ((List<Map<String, Object>>) out.get("candidates")).get(0);

        assertEquals("quoted", verse.get("kind"));
        assertEquals("The Imam explains which killer this verse means.", verse.get("reason"));
        assertTrue(((String) verse.get("reason_ar")).startsWith("يبيّن"));
        assertEquals(true, verse.get("quoted"));
        assertEquals("النساء", verse.get("surah_name_arabic"));

        List<Map<String, Object>> snippets = (List<Map<String, Object>>) verse.get("tafsir_snippets");
        // Snippets keep the builder's order, which it encodes as a descending score.
        assertEquals("enlightening-commentary", snippets.get(0).get("tafsir_slug"));
        assertEquals("Full passage.", snippets.get(0).get("preview"));
        assertEquals("en", snippets.get(0).get("language"));
        assertEquals("ar", snippets.get(1).get("language"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void quotesArePassedOnOnlyWhenThePageCanPlaceThem() {
        Map<String, Object> out = service.overviewFromSource(v2Document(), "x", false, true);
        List<Map<String, Object>> quotes = (List<Map<String, Object>>) out.get("quotes");

        assertEquals(1, quotes.size());
        assertEquals("arabic", quotes.get(0).get("field"));
        assertEquals(80, quotes.get(0).get("start"));
        assertEquals(121, quotes.get(0).get("end"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOldIndexDocumentStillReadsWithTheNewFieldsEmpty() {
        Map<String, Object> old = Map.of("candidate_count", 1, "candidates", List.of(Map.of(
                "verse_key", "2:228", "combined_score", 12.5,
                "tafsir_snippets", List.of(
                        Map.of("tafsir_slug", "al-mizan", "commentary_text", "Text"),
                        Map.of("tafsir_slug", "ar-amthal", "commentary_text", "نص")))));
        Map<String, Object> out = service.overviewFromSource(old, "x", false, true);
        Map<String, Object> verse = ((List<Map<String, Object>>) out.get("candidates")).get(0);

        assertEquals("", verse.get("reason"));
        assertEquals("", verse.get("kind"));
        assertEquals(false, verse.get("quoted"));
        List<Map<String, Object>> oldSnippets = (List<Map<String, Object>>) verse.get("tafsir_snippets");
        // No language field in v1: the slug decides, so a rollback still keeps each site to its language.
        assertEquals("en", oldSnippets.stream().filter(x -> "al-mizan".equals(x.get("tafsir_slug"))).findFirst().get().get("language"));
        assertEquals("ar", oldSnippets.stream().filter(x -> "ar-amthal".equals(x.get("tafsir_slug"))).findFirst().get().get("language"));
        assertTrue(((List<?>) out.get("quotes")).isEmpty());
    }

    @Test
    void countOnlyCarriesNoCandidates() {
        Map<String, Object> out = service.overviewFromSource(v2Document(), "x", true, false);
        assertEquals(1, out.get("count"));
        assertFalse(out.containsKey("candidates"));
    }
}

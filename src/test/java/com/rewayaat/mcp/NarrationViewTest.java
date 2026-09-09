package com.rewayaat.mcp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shaping contract. These assertions are the reason a tool result fits in a client's
 * budget, so they are written as "this field must not survive" rather than as a snapshot -
 * a snapshot would pass just as happily if the payload doubled.
 */
class NarrationViewTest {

    private static final String BASE = "https://hadith.academyofislam.com";

    private Map<String, Object> fullSource() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("book", "Al-Kāfi");
        source.put("volume", "4");
        source.put("chapter", "Minimum Limits of I‘tikaf");
        source.put("number", "690");
        source.put("english", "The one in i'tikaf does not smell perfume.");
        source.put("arabic", "الْمُعْتَكِفُ لا يَشَمُّ الطِّيبَ");
        source.put("gradings", List.of(Map.of("grading", "صحيح", "grader", "Allamah Majlisi")));
        source.put("topic_tags", List.of("itikaf", "fasting"));
        // Everything below exists to save the browser work and must not reach a model.
        source.put("llm_similar", List.of(Map.of("id", "x", "reason", "a very long reason")));
        source.put("englishContent", "The one in i'tikaf does not smell perfume.");
        source.put("arabicContent", "الْمُعْتَكِفُ لا يَشَمُّ الطِّيبَ");
        source.put("englishChain", "Ali ibn Ibrahim from his father");
        source.put("arabicChain", "علي بن إبراهيم عن أبيه");
        source.put("semantic_matn_source", "الْمُعْتَكِفُ لا يَشَمُّ الطِّيبَ");
        source.put("semantic_english_hint_source", "i'tikaf rules");
        source.put("semantic_significant_terms_source", "itikaf perfume");
        source.put("history", List.of(Map.of("edited", "2026-01-01")));
        return source;
    }

    @Test
    void summary_dropsEveryFieldThatExistsForTheBrowser() {
        Map<String, Object> shaped = NarrationView.summary("Al-Kafi-Volume-4-Kulayni:690", fullSource(), BASE);

        for (String excluded : List.of("llm_similar", "englishContent", "arabicContent",
                "englishChain", "arabicChain", "semantic_matn_source",
                "semantic_english_hint_source", "semantic_significant_terms_source", "history")) {
            assertFalse(shaped.containsKey(excluded),
                    excluded + " reached the model; it is dropped precisely because it is "
                            + "duplicated content or a retrieval input.");
        }
    }

    @Test
    void summary_keepsTheTextAndWhatMakesTheCitationResolvable() {
        Map<String, Object> shaped = NarrationView.summary("Al-Kafi-Volume-4-Kulayni:690", fullSource(), BASE);

        assertEquals("Al-Kāfi", shaped.get("book"));
        assertEquals("4", shaped.get("volume"));
        assertEquals("690", shaped.get("number"));
        assertEquals("Minimum Limits of I‘tikaf", shaped.get("chapter"));
        assertTrue(String.valueOf(shaped.get("english")).contains("i'tikaf"));
        assertTrue(String.valueOf(shaped.get("arabic")).contains("الْمُعْتَكِفُ"));
        assertEquals(BASE + "/hadith/Al-Kafi-Volume-4-Kulayni:690", shaped.get("url"));
    }

    @Test
    void summary_flattensGradingsIntoAttributedStrings() {
        Map<String, Object> shaped = NarrationView.summary("id", fullSource(), BASE);
        assertEquals(List.of("صحيح (Allamah Majlisi)"), shaped.get("gradings"));
    }

    @Test
    void summary_isSubstantiallySmallerThanTheRawSource() {
        Map<String, Object> source = fullSource();
        int raw = source.toString().length();
        int shaped = NarrationView.summary("id", source, BASE).toString().length();
        assertTrue(shaped < raw / 2,
                "Shaped output (" + shaped + ") should be well under half the raw source ("
                        + raw + "); shaping is what keeps a result inside the client's cap.");
    }

    @Test
    void summary_omitsEmptyFieldsRatherThanEmittingBlanks() {
        Map<String, Object> sparse = new LinkedHashMap<>();
        sparse.put("book", "Nahj al-Balāgha");
        sparse.put("english", "Text.");

        Map<String, Object> shaped = NarrationView.summary("id", sparse, BASE);

        assertFalse(shaped.containsKey("volume"));
        assertFalse(shaped.containsKey("chapter"));
        assertFalse(shaped.containsKey("gradings"));
        assertFalse(shaped.containsKey("topic_tags"));
    }

    @Test
    void label_readsAsACitationEvenWhenLiftedOutOfTheResult() {
        assertEquals("Al-Kāfi #690", NarrationView.label(fullSource()));
        assertEquals("Al-Kāfi", NarrationView.label(Map.of("book", "Al-Kāfi")));
        assertEquals("#690", NarrationView.label(Map.of("number", "690")));
        assertEquals("", NarrationView.label(Map.of()));
    }
}

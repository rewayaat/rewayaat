package com.rewayaat.core;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HadithSignificantTermsTest {

    @Test
    void rankTermsPrefersRareSalientTokens() {
        List<String> candidates = List.of("ركعه", "رمضان", "حج", "زكاه", "جبريل", "النمره");
        Map<String, Integer> docFrequency = new HashMap<>();
        docFrequency.put("ركعه", 1400);
        docFrequency.put("رمضان", 320);
        docFrequency.put("حج", 900);
        docFrequency.put("زكاه", 850);
        docFrequency.put("جبريل", 180);
        docFrequency.put("النمره", 1);

        List<String> ranked = HadithSignificantTerms.rankTerms(candidates, docFrequency, 32519, 4);

        assertEquals(4, ranked.size());
        assertTrue(ranked.contains("النمره"));
        assertTrue(ranked.contains("جبريل"));
        assertTrue(ranked.contains("رمضان"));
    }

    @Test
    void rankTermsPreservesRepeatedDistinctiveSignalOverOddSingletons() {
        List<String> candidates = List.of(
                "نمره", "قبحه", "اقبح", "نمره", "جبرييل", "فرض", "نمره", "جبرييل", "جبرييل", "جبرييل",
                "رمضان", "ركعه", "سرها", "ادركته");
        Map<String, Integer> docFrequency = new HashMap<>();
        docFrequency.put("نمره", 2);
        docFrequency.put("قبحه", 3);
        docFrequency.put("اقبح", 2);
        docFrequency.put("جبرييل", 180);
        docFrequency.put("فرض", 2100);
        docFrequency.put("رمضان", 320);
        docFrequency.put("ركعه", 1400);
        docFrequency.put("سرها", 1);
        docFrequency.put("ادركته", 4);

        List<String> ranked = HadithSignificantTerms.rankTerms(candidates, docFrequency, 32519, 6);

        assertTrue(ranked.indexOf("جبرييل") >= 0);
        assertTrue(!ranked.contains("سرها") || ranked.indexOf("جبرييل") < ranked.indexOf("سرها"));
        assertTrue(ranked.contains("نمره"));
        assertTrue(ranked.contains("رمضان"));
    }

    @Test
    void candidateTermsKeepsRepeatedDistinctiveTokens() {
        List<String> terms = HadithSignificantTerms.candidateTerms("ذو النمره ذو النمره جبريل جبريل رمضان", 0);

        assertEquals(List.of("نمره", "نمره", "جبريل", "جبريل", "رمضان"), terms);
    }

    @Test
    void parseAndJoinTermsRoundTrip() {
        List<String> parsed = HadithSignificantTerms.parseTerms("النمره جبريل رمضان", 6);
        String joined = HadithSignificantTerms.joinTerms(parsed, 6);

        assertEquals(List.of("النمره", "جبريل", "رمضان"), parsed);
        assertEquals("النمره جبريل رمضان", joined);
    }
}

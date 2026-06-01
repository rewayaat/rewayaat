package com.rewayaat.core;

import com.rewayaat.core.data.NarratorDocument;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * Matches narrator names across Arabic and English using normalized comparison
 * for deduplication against existing narrator documents.
 */
public final class NarratorNameMatcher {

    private static final double FUZZY_THRESHOLD = 0.85;

    private NarratorNameMatcher() {
    }

    /**
     * Find a matching narrator ID from existing documents.
     * Returns the matched document ID, or null if no match found.
     */
    public static String findMatchingNarratorId(String arabicName, String englishName,
                                                 Map<String, NarratorDocument> existingNarrators) {
        String normAr = normalizeArabic(arabicName);
        String normEn = normalizeEnglish(englishName);

        // 1. Exact normalized Arabic match
        for (NarratorDocument doc : existingNarrators.values()) {
            if (normAr.equals(doc.getNormalizedArabic())) {
                return doc.getId();
            }
            if (doc.getArabicAliases() != null) {
                for (String alias : doc.getArabicAliases()) {
                    if (normAr.equals(normalizeArabic(alias))) {
                        return doc.getId();
                    }
                }
            }
        }

        // 2. Exact normalized English match
        if (!normEn.isEmpty()) {
            for (NarratorDocument doc : existingNarrators.values()) {
                if (normEn.equals(doc.getNormalizedEnglish())) {
                    return doc.getId();
                }
                if (doc.getEnglishAliases() != null) {
                    for (String alias : doc.getEnglishAliases()) {
                        if (normEn.equals(normalizeEnglish(alias))) {
                            return doc.getId();
                        }
                    }
                }
            }
        }

        // 3. Fuzzy match using Jaro-Winkler similarity
        if (!normAr.isEmpty()) {
            String bestId = null;
            double bestScore = 0;
            for (NarratorDocument doc : existingNarrators.values()) {
                double score = jaroWinkler(normAr, doc.getNormalizedArabic());
                if (score > bestScore) {
                    bestScore = score;
                    bestId = doc.getId();
                }
            }
            if (bestScore >= FUZZY_THRESHOLD && bestId != null) {
                return bestId;
            }
        }

        return null;
    }

    /**
     * Generate a slug ID from the normalized Arabic name.
     */
    public static String generateNarratorId(String arabicName) {
        if (arabicName == null || arabicName.isBlank()) {
            return "narrator-" + System.nanoTime();
        }
        String normalized = normalizeArabic(arabicName)
                .replaceAll("\\s+", "-");
        if (normalized.isEmpty()) {
            return "narrator-" + System.nanoTime();
        }
        return normalized;
    }

    public static String normalizeArabic(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw
                .replaceAll("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]", "")
                .replace('\u0640', ' ')
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
                .replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')
                .replaceAll("[^\\p{IsArabic}\\s]+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String normalizeEnglish(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .replace("ʿ", "").replace("ʾ", "").replace("ʻ", "")
                .replace("'", "").replace("\u2019", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Jaro-Winkler string similarity (0.0 to 1.0).
     */
    static double jaroWinkler(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }

        int maxRange = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] aMatched = new boolean[a.length()];
        boolean[] bMatched = new boolean[b.length()];
        int matches = 0;
        int transpositions = 0;

        for (int i = 0; i < a.length(); i++) {
            int start = Math.max(0, i - maxRange);
            int end = Math.min(b.length() - 1, i + maxRange);
            for (int j = start; j <= end; j++) {
                if (bMatched[j] || a.charAt(i) != b.charAt(j)) {
                    continue;
                }
                aMatched[i] = true;
                bMatched[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) {
            return 0.0;
        }

        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!aMatched[i]) {
                continue;
            }
            while (!bMatched[k]) {
                k++;
            }
            if (a.charAt(i) != b.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        double jaro = ((double) matches / a.length()
                + (double) matches / b.length()
                + (double) (matches - transpositions / 2.0) / matches) / 3.0;

        // Winkler bonus for common prefix (up to 4 chars)
        int prefix = 0;
        int maxPrefix = Math.min(4, Math.min(a.length(), b.length()));
        for (int i = 0; i < maxPrefix; i++) {
            if (a.charAt(i) == b.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }
}

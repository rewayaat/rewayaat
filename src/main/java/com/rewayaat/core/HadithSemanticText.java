package com.rewayaat.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared matn extraction for semantic hadith retrieval and reranking.
 */
public final class HadithSemanticText {

    private static final String ARABIC_FIELD = "arabic";
    private static final String ENGLISH_FIELD = "english";
    private static final String SEMANTIC_MATN_FIELD = "semantic_matn_source";
    private static final int DEFAULT_ENGLISH_HINT_CHARS = 120;
    private static final int MAX_ARABIC_SCAN_CHARS = 2200;
    private static final String[] CHAIN_CUES = new String[] {
            " حدثنا ",
            " حدثني ",
            " اخبرنا ",
            " بهذا الاسناد ",
            " باسناده ",
            " وباسناده ",
            " عن ",
            " وعنه "
    };
    private static final String[] ARABIC_SPLIT_MARKERS = new String[] {
            "قلت:",
            "قلت ",
            " قلت:",
            " قلت ",
            "فقلت:",
            "فقلت ",
            "فقال:",
            "فقال ",
            " فقال:",
            " فقال ",
            "قال:",
            "قال ",
            " قال:",
            " قال ",
            "انه قال",
            "انها قالت",
            "قالت:",
            "قالت ",
            " قالت:",
            " قالت "
    };

    private HadithSemanticText() {
    }

    public static String extractMatn(Map source, int maxChars) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        String arabic = valueAsString(source.get(ARABIC_FIELD));
        if (arabic.isBlank()) {
            return "";
        }
        Map<String, Object> temp = new HashMap<>();
        temp.put(ARABIC_FIELD, arabic);
        HadithDisplaySegmenter.enrich(temp);

        String contentFirst = valueAsString(temp.get("arabicContent"));
        String segmented = SemanticTextNormalizer.normalizeMatn(contentFirst, maxChars);
        String direct = SemanticTextNormalizer.normalizeMatn(arabic, maxChars);
        String heuristic = extractFromRawArabic(arabic, maxChars);
        return chooseBestMatn(segmented.trim(), heuristic.trim(), direct.trim());
    }

    public static String extractContentOnlyMatn(Map source, int maxChars) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        String arabic = valueAsString(source.get(ARABIC_FIELD));
        if (arabic.isBlank()) {
            return "";
        }
        Map<String, Object> temp = new HashMap<>();
        temp.put(ARABIC_FIELD, arabic);
        HadithDisplaySegmenter.enrich(temp);

        String contentFirst = valueAsString(temp.get("arabicContent"));
        String segmented = SemanticTextNormalizer.normalizeMatn(contentFirst, maxChars);
        String heuristic = extractFromRawArabic(arabic, maxChars);
        String direct = SemanticTextNormalizer.normalizeMatn(arabic, maxChars);
        if (chainCueScore(direct) > 0) {
            direct = "";
        }
        String result = chooseBestMatn(segmented.trim(), heuristic.trim(), direct.trim());
        if (result.isBlank()) {
            // Fallback to pre-computed semantic_matn_source which was extracted
            // by SemanticMatnSourceBackfillTool using the less strict extractMatn()
            String storedMatn = valueAsString(source.get(SEMANTIC_MATN_FIELD));
            if (!storedMatn.isBlank()) {
                result = SemanticTextNormalizer.normalizeMatn(storedMatn, maxChars);
            }
        }
        return result;
    }

    public static String toQueryText(String matn, int maxChars) {
        return toQueryText(matn, "", maxChars);
    }

    public static String toPassageText(String matn, int maxChars) {
        return toPassageText(matn, "", maxChars);
    }

    public static String toQueryText(String matn, String significantTermsText, int maxChars) {
        String body = buildSemanticBody(matn, "", significantTermsText, maxChars);
        return body.isEmpty() ? "" : "query: " + body;
    }

    public static String toPassageText(String matn, String significantTermsText, int maxChars) {
        String body = buildSemanticBody(matn, "", significantTermsText, maxChars);
        return body.isEmpty() ? "" : "passage: " + body;
    }

    public static String toQueryText(String matn, String englishHint, String significantTermsText, int maxChars) {
        String body = buildSemanticBody(matn, englishHint, significantTermsText, maxChars);
        return body.isEmpty() ? "" : "query: " + body;
    }

    public static String toPassageText(String matn, String englishHint, String significantTermsText, int maxChars) {
        String body = buildSemanticBody(matn, englishHint, significantTermsText, maxChars);
        return body.isEmpty() ? "" : "passage: " + body;
    }

    public static String normalizeForComparison(String matn) {
        return SimilarHadithRanking.normalizeArabic(matn);
    }

    public static String extractEnglishHint(Map source) {
        return extractEnglishHint(source, DEFAULT_ENGLISH_HINT_CHARS);
    }

    public static String extractEnglishMatn(Map source, int maxChars) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        String english = valueAsString(source.get(ENGLISH_FIELD));
        if (english.isBlank()) {
            return "";
        }
        return SemanticTextNormalizer.normalizeMatn(english, maxChars);
    }

    public static String extractEnglishHint(Map source, int maxChars) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        String english = valueAsString(source.get(ENGLISH_FIELD));
        if (english.isBlank()) {
            return "";
        }
        Map<String, Object> temp = new HashMap<>();
        temp.put(ENGLISH_FIELD, english);
        HadithDisplaySegmenter.enrich(temp);

        String contentFirst = valueAsString(temp.get("englishContent"));
        String segmented = SemanticTextNormalizer.normalizeMatn(contentFirst, maxChars);
        String direct = SemanticTextNormalizer.normalizeMatn(english, maxChars);
        return chooseBestEnglishHint(segmented, direct);
    }

    private static String valueAsString(Object value) {
        if (!(value instanceof String)) {
            return "";
        }
        return ((String) value).trim();
    }

    private static String buildSemanticBody(String matn, String englishHint, String significantTermsText, int maxChars) {
        String body = matn == null ? "" : matn.trim();
        if (body.isBlank()) {
            return "";
        }
        String normalizedBody = SemanticTextNormalizer.normalizeMatn(body, maxChars);
        if (normalizedBody.isBlank()) {
            return "";
        }
        body = normalizedBody;
        String english = englishHint == null ? "" : SemanticTextNormalizer.normalizeMatn(englishHint, DEFAULT_ENGLISH_HINT_CHARS);
        if (!english.isBlank()) {
            body = body + " || en_hint: " + english;
        }
        String significantTerms = significantTermsText == null ? "" : significantTermsText.trim();
        if (!significantTerms.isBlank()) {
            body = body + " || key_terms: " + significantTerms;
        }
        if (maxChars > 0 && body.length() > maxChars) {
            body = body.substring(0, maxChars).trim();
        }
        return body;
    }

    private static String chooseBestMatn(String... candidates) {
        String best = "";
        int bestScore = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            String clean = candidate == null ? "" : candidate.trim();
            if (clean.isBlank()) {
                continue;
            }
            int score = chainCueScore(clean);
            if (best.isBlank() || score < bestScore || (score == bestScore && isLongerBetter(clean, best))) {
                best = clean;
                bestScore = score;
            }
        }
        return best;
    }

    private static String chooseBestEnglishHint(String segmented, String direct) {
        String cleanSegmented = segmented == null ? "" : segmented.trim();
        if (!cleanSegmented.isBlank()) {
            return cleanSegmented;
        }
        return direct == null ? "" : direct.trim();
    }

    private static boolean isLongerBetter(String candidate, String currentBest) {
        if (candidate.length() > currentBest.length() && candidate.contains(currentBest)) {
            return true;
        }
        if (currentBest.length() > candidate.length() && currentBest.contains(candidate)) {
            return false;
        }
        return candidate.length() > currentBest.length() + 40;
    }

    private static int chainCueScore(String text) {
        if (text == null || text.isBlank()) {
            return Integer.MAX_VALUE;
        }
        String normalized = " " + SimilarHadithRanking.normalizeArabic(text) + " ";
        int score = 0;
        for (String cue : CHAIN_CUES) {
            score += countOccurrences(normalized, cue);
        }
        return score;
    }

    private static int countOccurrences(String text, String token) {
        if (text == null || text.isEmpty() || token == null || token.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while (index >= 0) {
            index = text.indexOf(token, index);
            if (index >= 0) {
                count++;
                index += token.length();
            }
        }
        return count;
    }

    private static String extractFromRawArabic(String arabic, int maxChars) {
        if (arabic == null || arabic.isBlank()) {
            return "";
        }
        ArabicMatchText matchText = normalizeArabicForMatching(arabic);
        String normalized = matchText.normalized();
        if (normalized.isBlank()) {
            return "";
        }
        int bestBoundary = -1;
        int bestPrefixScore = -1;
        for (String marker : ARABIC_SPLIT_MARKERS) {
            int index = normalized.indexOf(marker);
            while (index >= 0 && index <= MAX_ARABIC_SCAN_CHARS) {
                int prefixScore = chainCueScore(normalized.substring(0, index));
                if (prefixScore >= 2) {
                    int boundary = matchText.toRawBoundary(index + marker.length());
                    if (boundary > 0 && boundary < arabic.length()) {
                        if (bestBoundary < 0 || boundary < bestBoundary
                                || (boundary == bestBoundary && prefixScore > bestPrefixScore)) {
                            bestBoundary = boundary;
                            bestPrefixScore = prefixScore;
                        }
                    }
                }
                index = normalized.indexOf(marker, index + marker.length());
            }
        }
        if (bestBoundary <= 0 || bestBoundary >= arabic.length()) {
            return "";
        }
        return SemanticTextNormalizer.normalizeMatn(arabic.substring(bestBoundary), maxChars);
    }

    private static ArabicMatchText normalizeArabicForMatching(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArabicMatchText("", new int[0], 0);
        }
        StringBuilder normalized = new StringBuilder(raw.length());
        int[] rawIndexByNormalized = new int[raw.length()];
        int normalizedLength = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (isArabicDiacritic(ch) || ch == '\u0640') {
                continue;
            }
            if (ch == '\u0623' || ch == '\u0625' || ch == '\u0622') {
                ch = '\u0627';
            } else if (ch == '\u0649') {
                ch = '\u064A';
            }
            normalized.append(ch);
            rawIndexByNormalized[normalizedLength] = i;
            normalizedLength++;
        }
        int[] compact = new int[normalizedLength];
        System.arraycopy(rawIndexByNormalized, 0, compact, 0, normalizedLength);
        return new ArabicMatchText(normalized.toString(), compact, raw.length());
    }

    private static boolean isArabicDiacritic(char ch) {
        return (ch >= '\u0610' && ch <= '\u061A')
                || (ch >= '\u064B' && ch <= '\u065F')
                || ch == '\u0670'
                || (ch >= '\u06D6' && ch <= '\u06ED');
    }

    private record ArabicMatchText(String normalized, int[] rawIndexByNormalized, int rawLength) {
        private int toRawBoundary(int normalizedBoundary) {
            if (normalizedBoundary <= 0) {
                return 0;
            }
            if (normalizedBoundary >= rawIndexByNormalized.length) {
                return rawLength;
            }
            return rawIndexByNormalized[normalizedBoundary - 1] + 1;
        }
    }
}

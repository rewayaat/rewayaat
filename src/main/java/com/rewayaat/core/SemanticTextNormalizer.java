package com.rewayaat.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizes hadith text for semantic embeddings by trimming markup, honorifics,
 * and likely isnad-heavy prefixes.
 */
public final class SemanticTextNormalizer {

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern MULTISPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern LEADING_REFERENCE_PATTERN = Pattern.compile("(?iu)^\\s*\\[[^\\]]{1,40}\\]\\s*[^:]{0,160}:\\s*");
    private static final Pattern PROMPT_PREFIX_PATTERN = Pattern.compile("(?iu)^\\s*(query|passage)\\s*:\\s*");
    private static final Pattern LEADING_PUNCT_PATTERN = Pattern.compile("^[\\s\\-–—:;,.\"'`“”‘’\\[\\]()]+");
    private static final Pattern HONORIFIC_PATTERN = Pattern.compile("(?iu)\\((as|a|rh|sa|ra|asws|saww|aj)\\)|\\b(swt|azwj|pbuh|asws|saww)\\b");
    private static final int MAX_CHAIN_SCAN_CHARS = 2200;
    private static final int MIN_TRIMMED_LENGTH = 40;

    private static final String[] ENGLISH_NARRATION_TOKENS = new String[] {
            " narrated to us",
            " narrated ",
            " has narrated",
            " narration from ",
            " narrated to me",
            " we were told by ",
            " related to me",
            " quoted ",
            " reports ",
            " reported ",
            " it is narrated"
    };

    private static final String[] ENGLISH_LINK_TOKENS = new String[] {
            " from ",
            " reported ",
            " through ",
            " on the authority of "
    };

    private static final String[] ENGLISH_SPLIT_MARKERS = new String[] {
            " who has said the following:",
            " who has said the following.",
            " who has said:",
            " who has said.",
            " who has said the foiiowing:",
            " who has narrated the following:",
            " who has narrated the foiiowing:",
            " who has narrated:",
            " who said:",
            " who said.",
            " who says:",
            " saying:",
            " said the following:",
            " has said the following:",
            " has said, the following:",
            " has narrated the following:",
            " has narrated, the following:",
            " that he said:",
            " that she said:",
            " that they said:",
            " said:",
            " say:",
            " say,",
            " i asked",
            " i heard"
    };

    private static final String[] ARABIC_SPLIT_MARKERS = new String[] {
            " قال:",
            " قال ",
            " فقال:",
            " فقال ",
            " أنه قال",
            " انه قال"
    };

    private SemanticTextNormalizer() {
    }

    public static String normalizeForQuery(String rawText, int maxChars) {
        String body = normalizeBody(rawText, maxChars);
        return body.isEmpty() ? "" : "query: " + body;
    }

    public static String normalizeForPassage(String rawText, int maxChars) {
        String body = normalizeBody(rawText, maxChars);
        return body.isEmpty() ? "" : "passage: " + body;
    }

    public static String normalizeMatn(String rawText, int maxChars) {
        return normalizeBody(rawText, maxChars);
    }

    static String normalizeBody(String rawText, int maxChars) {
        if (rawText == null) {
            return "";
        }
        String normalized = stripPromptPrefix(rawText.trim());
        if (normalized.isEmpty()) {
            return "";
        }

        normalized = HTML_TAG_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = HONORIFIC_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = normalized
                .replace("عليه السلام", " ")
                .replace("عليهما السلام", " ")
                .replace("عليهم السلام", " ")
                .replace("صلّى الله عليه وآله", " ")
                .replace("صلى الله عليه وآله", " ")
                .replace("(asws)", " ")
                .replace("(saww)", " ")
                .replace("رحمه الله", " ");
        normalized = MULTISPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();
        normalized = LEADING_REFERENCE_PATTERN.matcher(normalized).replaceFirst("").trim();
        for (int i = 0; i < 3; i++) {
            String next = trimLikelyIsnadPrefix(normalized);
            if (next.equals(normalized)) {
                break;
            }
            normalized = next;
        }
        normalized = stripLeadingSpeechCues(normalized);
        normalized = LEADING_PUNCT_PATTERN.matcher(normalized).replaceFirst("").trim();
        normalized = MULTISPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();

        if (maxChars > 0 && normalized.length() > maxChars) {
            normalized = normalized.substring(0, maxChars).trim();
        }
        return normalized;
    }

    private static String stripPromptPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return PROMPT_PREFIX_PATTERN.matcher(text).replaceFirst("").trim();
    }

    private static String trimLikelyIsnadPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String candidate = text.trim();
        String lower = candidate.toLowerCase(Locale.ROOT);
        int scanLimit = Math.min(candidate.length(), MAX_CHAIN_SCAN_CHARS);
        int chainSignals = countChainSignals(lower, candidate, scanLimit);
        if (chainSignals < 2) {
            return candidate;
        }

        int splitAt = findBestSplitPoint(candidate, lower);
        if (splitAt > 0 && splitAt < candidate.length()) {
            String trimmed = candidate.substring(splitAt).trim();
            int minLength = Math.min(MIN_TRIMMED_LENGTH, Math.max(18, candidate.length() / 4));
            if (trimmed.length() >= minLength) {
                candidate = trimmed;
            }
        }
        return candidate;
    }

    private static int findBestSplitPoint(String candidate, String lower) {
        int best = -1;
        for (String marker : ENGLISH_SPLIT_MARKERS) {
            int markerBest = findMarkerSplit(lower, marker, lower, candidate);
            if (markerBest > 0 && (best < 0 || markerBest < best)) {
                best = markerBest;
            }
        }
        for (String marker : ARABIC_SPLIT_MARKERS) {
            int markerBest = findMarkerSplit(candidate, marker, lower, candidate);
            if (markerBest > 0 && (best < 0 || markerBest < best)) {
                best = markerBest;
            }
        }
        if (best < 0) {
            int saidQuote = findSaidQuoteSplit(lower, candidate);
            if (saidQuote > best) {
                best = saidQuote;
            }
        }
        return best;
    }

    private static int findMarkerSplit(String searchText, String marker, String lower, String raw) {
        int fallbackBest = -1;
        int fallbackScore = -1;
        int markerLength = marker.length();
        int at = searchText.indexOf(marker);
        while (at >= 0 && at <= MAX_CHAIN_SCAN_CHARS) {
            int prefixSignals = countChainSignals(lower, raw, at);
            if (prefixSignals >= 2) {
                int split = at + markerLength;
                if (isLikelyMatnBoundary(lower, raw, split, prefixSignals)) {
                    return split;
                }
                if (prefixSignals > fallbackScore) {
                    fallbackScore = prefixSignals;
                    fallbackBest = split;
                }
            }
            at = searchText.indexOf(marker, at + markerLength);
        }
        return fallbackBest;
    }

    private static int countChainSignals(String lower, String raw, int endExclusive) {
        int safeEnd = Math.max(0, Math.min(endExclusive, Math.min(lower.length(), raw.length())));
        String lowerPrefix = lower.substring(0, safeEnd);
        String rawPrefix = raw.substring(0, safeEnd);
        int narrationCount = 0;
        for (String token : ENGLISH_NARRATION_TOKENS) {
            narrationCount += countOccurrences(lowerPrefix, token);
        }
        int linkCount = 0;
        for (String token : ENGLISH_LINK_TOKENS) {
            linkCount += countOccurrences(lowerPrefix, token);
        }
        int arabicLinkCount = countOccurrences(rawPrefix, " عن ");
        boolean hasSpeechCue = lowerPrefix.contains(" who said")
                || lowerPrefix.contains(" who has said")
                || lowerPrefix.contains(" who has narrated")
                || lowerPrefix.contains(" he said")
                || lowerPrefix.contains(" say,")
                || lowerPrefix.contains(" say:");
        boolean fromPreviousChain = lowerPrefix.contains("from the previous chain")
                || lowerPrefix.contains("from previous chain");

        boolean likelyChain = (narrationCount >= 1 && (linkCount + arabicLinkCount) >= 2)
                || ((linkCount + arabicLinkCount) >= 6)
                || narrationCount >= 3
                || (narrationCount >= 1 && (linkCount + arabicLinkCount) >= 1 && hasSpeechCue)
                || (fromPreviousChain && (linkCount + arabicLinkCount) >= 2);
        if (!likelyChain) {
            return 0;
        }

        return (narrationCount * 2) + linkCount + arabicLinkCount;
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

    private static boolean isLikelyMatnBoundary(String lower, String raw, int split, int prefixSignals) {
        if (split <= 0 || split >= raw.length()) {
            return false;
        }
        int lookaheadEnd = Math.min(lower.length(), split + 220);
        String tailLower = lower.substring(split, lookaheadEnd);
        int tailNarration = 0;
        for (String token : ENGLISH_NARRATION_TOKENS) {
            tailNarration += countOccurrences(tailLower, token);
        }
        int tailLinks = 0;
        for (String token : ENGLISH_LINK_TOKENS) {
            tailLinks += countOccurrences(tailLower, token);
        }
        boolean tailStillChain = (tailNarration >= 1 && tailLinks >= 1)
                || tailLinks >= 3
                || tailLower.contains(" on the authority of ");
        if (tailStillChain && prefixSignals < 10) {
            return false;
        }
        return true;
    }

    private static int findSaidQuoteSplit(String lower, String raw) {
        String[] patterns = new String[] {
                " said: \"",
                " said, \"",
                " said: “",
                " said, “",
                " has said: \"",
                " has said, \"",
                " has said: “",
                " has said, “"
        };
        for (String pattern : patterns) {
            int at = lower.indexOf(pattern);
            if (at > 0 && at < MAX_CHAIN_SCAN_CHARS) {
                int signal = countChainSignals(lower, raw, at);
                if (signal >= 6) {
                    return at + pattern.length();
                }
            }
        }
        return -1;
    }

    private static String stripLeadingSpeechCues(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String candidate = text.trim();
        String previous;
        do {
            previous = candidate;
            candidate = candidate.replaceFirst("(?iu)^that\\s+(he|she|they)\\s+said\\s*[:,-]\\s*", "");
            candidate = candidate.replaceFirst("(?iu)^(he|she|they|i)\\s+said\\s*[:,-]\\s*", "");
            candidate = candidate.replaceFirst("(?iu)^([\\p{L}`'\\-.\\s]{1,140})\\s+(has\\s+)?said(\\s+the\\s+following)?\\s*[:,-]\\s*", "");
            candidate = candidate.replaceFirst("(?iu)^the\\s+following\\s*[:,-]\\s*", "");
            candidate = candidate.replaceFirst("(?iu)^the\\s+foiiowing\\s*[:,-]\\s*", "");
            candidate = candidate.replaceFirst("^(قال|فقال|قلت|يقول)\\s*[:،-]\\s*", "");
            candidate = LEADING_PUNCT_PATTERN.matcher(candidate).replaceFirst("").trim();
        } while (!candidate.equals(previous));
        return candidate;
    }
}

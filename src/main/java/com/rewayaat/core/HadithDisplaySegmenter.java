package com.rewayaat.core;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Splits narration text into chain (isnad) and content (matn) for display.
 */
public final class HadithDisplaySegmenter {

    private static final int MAX_SCAN_CHARS = 2200;
    private static final int MATN_LOOKAHEAD_CHARS = 220;
    private static final int MIN_CHAIN_VISIBLE_CHARS = 12;
    private static final int MIN_CONTENT_VISIBLE_CHARS = 8;

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern MULTISPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern SIGNAL_DELIMITERS_PATTERN = Pattern.compile("[،,;:\\-]+");
    private static final Pattern ENGLISH_DIALOGUE_BOUNDARY_PATTERN = Pattern.compile(
            "(?i)(?:\\bthen\\s+i\\s+said\\b|\\bi\\s+said\\b|\\bthen\\s+i\\s+asked\\b|\\bi\\s+asked\\b|"
                    + "\\bi\\s+heard\\b|\\bhe\\s+heard\\b|\\bshe\\s+heard\\b|"
                    + "\\bi\\s+saw\\b|\\bi\\s+entered\\b|\\bi\\s+wrote\\b|\\bi\\s+was\\b|"
                    + "\\bmy\\s+father\\s+(?:once\\s+)?(?:asked|said|heard|saw|entered|wrote)\\b|"
                    + "\\ba\\s+man\\s+(?:entered|came)\\b|\\ba\\s+woman\\s+(?:entered|came)\\b|"
                    + "\\bwhen\\s+a\\s+man\\s+(?:entered|came)\\b|\\bon\\s+the\\s+day\\s+when\\b)");

    private static final String[] ENGLISH_SPLIT_MARKERS = new String[] {
            " [who narrated] that ",
            " who narrated that ",
            " that the ",
            " saying,",
            " saying: ",
            " who has said that he heard ",
            " who has said that ",
            " who has said the following ",
            " who has said the following:",
            " who has said the following.",
            " has narrated the following ",
            " has narrated the following:",
            " has narrated the following.",
            " who has said the ",
            " who has said:",
            " has said the following:",
            " has said the following.",
            " had said that ",
            " had said:",
            " had said,",
            " was asked by ",
            " was asked: ",
            " was asked, ",
            " who was asked: ",
            " who was asked, ",
            " asked: “",
            " asked: \"",
            " that i asked ",
            " that i wrote ",
            " that i said ",
            " that he asked ",
            " that he wrote ",
            " that she asked ",
            " that \"",
            " that “",
            " that '",
            " that ‘",
            " that: ",
            " that a man ",
            " the following is narrated: ",
            " who said:",
            " who said,",
            " the imam (as) said,",
            " the imam (a.s.) said,",
            " imam (as) said,",
            " imam (a.s.) said,",
            " said, “",
            " said, \"",
            " said, ‘",
            " said, '",
            " said to me:",
            " said to him:",
            " say, “",
            " say, \"",
            " say:",
            " he said:",
            " he has said:",
            " she said:",
            " they said:",
            " saying:",
            " said the following:",
            " said:",
            // Split markers for "that he [narrative action]" where action ≠ said
            " that he mentioned ",
            " that he saw ",
            " that he heard ",
            " that he asked ",
            " that he wrote ",
            " that he entered ",
            " that he came ",
            " that she mentioned ",
            " that she saw ",
            " that she heard ",
            " that they mentioned ",
            " that they saw ",
            " that they heard ",
            // Split marker for "It has been narrated/reported that [name] [action]"
            // When followed by proper name + action (not "said"), split at "that"
            "it has been narrated that ",
            "it has been reported that ",
            "it was narrated that "
    };

    private static final String[] ENGLISH_CHAIN_CUES = new String[] {
            " narrated ",
            " has narrated ",
            " narrated to us",
            " narrated to me",
            " narrated to him",
            " narrated from",
            " by this chain",
            " through this chain",
            " through the same chain",
            " by this isnad",
            " with this isnad",
            " chain of narrators",
            " from the previous narration",
            " from the previous hadith",
            " it has been narrated that ",
            " it has been reported that ",
            " it has been related that ",
            " it is narrated from ",
            " reported from",
            " on the authority of ",
            " from "
    };

    private static final String[] ENGLISH_CHAIN_STRONG_CUES = new String[] {
            " narrated ",
            " has narrated ",
            " narrated to us",
            " narrated to me",
            " narrated to him",
            " narrated from",
            " narrated that ",
            " quoted ",
            " related ",
            " by this chain",
            " through this chain",
            " through the same chain",
            " chain of narrators",
            " chain of transmission",
            " from the previous narration",
            " from the previous hadith",
            " by this isnad",
            " with this isnad",
            " it has been narrated that ",
            " it was narrated that ",
            " it has been reported that ",
            " it has been related that ",
            " it is narrated from ",
            " reported from",
            " reported by ",
            " on the authority of "
    };

    private static final String[] ARABIC_SPLIT_MARKERS = new String[] {
            "قالت:",
            "قالت ",
            "فقالت:",
            "فقالت ",
            "قالوا:",
            "قالوا ",
            "فقالوا:",
            "فقالوا ",
            " قالت:",
            " قالت ",
            " فقالت:",
            " فقالت ",
            " قالوا:",
            " قالوا ",
            " فقالوا:",
            " فقالوا ",
            "قال:",
            "قال ",
            "فقال:",
            "فقال ",
            " قال:",
            " قال ",
            " فقال:",
            " فقال ",
            "سئل عليه السلام ",
            " سئل عليه السلام ",
            "سئل عليهما السلام ",
            " سئل عليهما السلام ",
            " أنه قال",
            " انه قال",
            "سمعت ",
            " سمعت ",
            "سمعته ",
            " سمعته ",
            "سالت ",
            " سالت ",
            "ساله ",
            " ساله ",
            "سالته ",
            " سالته ",
            "كنت ",
            " كنت ",
            "دخلت ",
            " دخلت ",
            "كتبت ",
            " كتبت ",
            "رايت ",
            " رايت ",
            "قلت:",
            "قلت ",
            " قلت:",
            " قلت "
    };

    private static final String[] ARABIC_CHAIN_CUES = new String[] {
            " وحدثنا ",
            " وحدّثنا ",
            " وحدثني ",
            " وحدّثني ",
            " حدثنا ",
            " حدّثنا ",
            " حدثني ",
            " حدّثني ",
            " واخبرنا ",
            " وأخبرنا ",
            " واخبرني ",
            " وأخبرني ",
            " اخبرنا ",
            " أخبرنا ",
            " اخبرني ",
            " أخبرني ",
            " بهذا الاسناد ",
            " وبهذا الاسناد ",
            " بهذا الإسناد ",
            " وبهذا الإسناد ",
            " بإسناده ",
            " وبإسناده ",
            " باسناده ",
            " وباسناده ",
            " وعن ",
            " وعنه ",
            " عنه ",
            " عن ",
            " روي ",
            " وروي ",
            " روى ",
            " سمعت "
    };

    private static final String[] ARABIC_DIALOGUE_CUES = new String[] {
            "انه سمع",
            "اخبرني من",
            "في رجل",
            "ثم قلت",
            "فقلت",
            "قلت",
            "سالت",
            "ساله",
            "سالته",
            "سالني",
            "سمع",
            "سمعت",
            "سمعته",
            "بينما",
            "كان",
            "كانت",
            "كانوا",
            "دخلت",
            "كنت",
            "كتبت",
            "رايت",
            "بعث الي",
            "بعث اليه",
            "قال يا",
            "قال لي",
            "قال له",
            "قال لها",
            "قال لهم",
            "فدخل عليه رجل",
            "دخل عليه رجل",
            "قام رجل",
            "قامت امرأة",
            "سئل الصادق",
            "سئل ابو",
            "سئل ابا",
            // "أنه [action]" patterns where action starts narrative (not reporting)
            "أنه ذكر",
            "انه ذكر",
            "أنه رأى",
            "انه رأى",
            "أنه سأل",
            "انه سأل",
            "أنه كتب",
            "انه كتب",
            "أنه دخل",
            "انه دخل",
            "أنه صار",
            "انه صار",
            "أنه أقبل",
            "انه أقبل",
            "أنه ذهب",
            "انه ذهب",
            "أنه لما",
            "انه لما",
            "أنه قال",
            "انه قال"
    };

    private static final String[] ARABIC_DIRECT_CHAIN_CONTINUATIONS = new String[] {
            " حدثنا ",
            " حدّثنا ",
            " حدثني ",
            " حدّثني ",
            " اخبرنا ",
            " أخبرنا ",
            " اخبرني ",
            " أخبرني "
    };

    private HadithDisplaySegmenter() {
    }

    public static void enrich(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        splitAndAttach(source, "english", false);
        splitAndAttach(source, "arabic", true);
    }

    private static void splitAndAttach(Map<String, Object> source, String field, boolean arabic) {
        Object rawValue = source.get(field);
        if (!(rawValue instanceof String)) {
            return;
        }
        String raw = ((String) rawValue).trim();
        if (raw.isEmpty()) {
            return;
        }
        VisibleText visibleText = toVisibleText(raw);
        String visible = visibleText.visible();
        if (visible.isEmpty()) {
            return;
        }
        int visibleSplit = arabic ? findArabicSplit(visible) : findEnglishSplit(visible);
        if (visibleSplit <= 0) {
            return;
        }
        int rawSplit = moveSplitBoundaryOutsideHtml(raw, visibleText.toRawBoundary(visibleSplit));
        SplitResult split = buildSplit(raw, rawSplit);
        if (!split.hasSplit()) {
            return;
        }
        source.put(field + "Chain", split.chain());
        source.put(field + "Content", split.content());
    }

    private static int findEnglishSplit(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        int bestSplit = -1;
        int bestIndex = -1;
        int fallbackSplit = -1;
        int fallbackIndex = -1;
        for (String marker : ENGLISH_SPLIT_MARKERS) {
            int index = lower.indexOf(marker);
            while (index >= 0 && index <= MAX_SCAN_CHARS) {
                String prefix = lower.substring(0, index);
                int score = englishChainSignalScore(prefix);
                if (score >= minEnglishChainScore(prefix)) {
                    int split = index + marker.length();
                    int maxTailSignals = maxEnglishTailSignals(marker);
                    if (" that a man ".equals(marker)) {
                        split = index + " that ".length();
                    }
                    if (isLikelyMatnBoundary(lower, split, ENGLISH_CHAIN_CUES, maxTailSignals)) {
                        if (bestSplit < 0 || index < bestIndex || (index == bestIndex && split > bestSplit)) {
                            bestIndex = index;
                            bestSplit = split;
                        }
                    } else if (fallbackSplit < 0 || index < fallbackIndex || (index == fallbackIndex && split > fallbackSplit)) {
                        fallbackIndex = index;
                        fallbackSplit = split;
                    }
                }
                index = lower.indexOf(marker, index + marker.length());
            }
        }
        if (bestSplit < 0 && fallbackSplit < 0) {
            int quoteSplit = findEnglishQuotedBoundary(raw, lower);
            if (quoteSplit > 0) {
                bestSplit = quoteSplit;
            }
        }
        if (bestSplit < 0 && fallbackSplit < 0) {
            int colonSplit = findEnglishColonBoundary(lower);
            if (colonSplit > 0) {
                bestSplit = colonSplit;
            }
        }
        if (bestSplit < 0) {
            bestSplit = fallbackSplit;
        }
        SplitResult split = buildSplit(raw, bestSplit);
        if (!split.hasSplit() && fallbackSplit > 0 && fallbackSplit != bestSplit) {
            bestSplit = fallbackSplit;
            split = buildSplit(raw, fallbackSplit);
        }
        if (!split.hasSplit()) {
            return -1;
        }
        int adjustedBoundary = findEnglishDialogueBoundary(raw, bestSplit);
        if (adjustedBoundary > 0 && adjustedBoundary < bestSplit) {
            SplitResult adjusted = buildSplit(raw, adjustedBoundary);
            if (adjusted.hasSplit()) {
                return adjustedBoundary;
            }
        }
        return bestSplit;
    }

    private static int findArabicSplit(String raw) {
        ArabicMatchText matchText = normalizeArabicForMatching(raw);
        String normalized = matchText.normalized();
        if (normalized.isEmpty()) {
            return -1;
        }
        int bestSplit = -1;
        int bestIndex = -1;
        int fallbackSplit = -1;
        int fallbackIndex = -1;
        for (String marker : ARABIC_SPLIT_MARKERS) {
            int index = normalized.indexOf(marker);
            while (index >= 0 && index <= MAX_SCAN_CHARS) {
                String prefix = normalized.substring(0, index);
                int score = arabicChainSignalScore(prefix);
                if (score >= minArabicChainScore(prefix)) {
                    int markerEnd = index + marker.length();
                    int split = matchText.toRawBoundary(markerEnd);
                    String tail = normalized.substring(Math.min(markerEnd, normalized.length()));
                    String rawTail = raw.substring(Math.min(split, raw.length()));
                    String trimmedRawTail = trimContent(rawTail);
                    int maxTailSignals = maxArabicTailSignals(marker);
                    boolean directChainContinuation = isBroadArabicQalaMarker(marker)
                            && (startsWithAnyCue(rawTail, ARABIC_DIRECT_CHAIN_CONTINUATIONS)
                            || startsWithAnyCue(trimmedRawTail, ARABIC_DIRECT_CHAIN_CONTINUATIONS));
                    if (!directChainContinuation
                            && isLikelyMatnBoundary(normalized, markerEnd, ARABIC_CHAIN_CUES, maxTailSignals)) {
                        if (bestSplit < 0 || index < bestIndex || (index == bestIndex && split > bestSplit)) {
                            bestIndex = index;
                            bestSplit = split;
                        }
                    } else if (!startsWithAnyCue(tail, ARABIC_CHAIN_CUES)
                            && (fallbackSplit < 0 || index < fallbackIndex || (index == fallbackIndex && split > fallbackSplit))) {
                        fallbackIndex = index;
                        fallbackSplit = split;
                    }
                }
                index = normalized.indexOf(marker, index + marker.length());
            }
        }
        if (bestSplit < 0) {
            bestSplit = fallbackSplit;
        }
        if (bestSplit < 0 && fallbackSplit < 0) {
            bestSplit = findArabicColonBoundary(raw);
        }
        if (bestSplit < 0 && fallbackSplit < 0) {
            bestSplit = findArabicReportedClauseBoundary(raw, matchText, normalized);
        }
        SplitResult split = buildSplit(raw, bestSplit);
        if (!split.hasSplit() && fallbackSplit > 0 && fallbackSplit != bestSplit) {
            bestSplit = fallbackSplit;
            split = buildSplit(raw, fallbackSplit);
        }
        if (!split.hasSplit()) {
            return -1;
        }
        int adjustedBoundary = findArabicDialogueBoundary(raw, bestSplit);
        if (adjustedBoundary > 0 && adjustedBoundary < bestSplit) {
            SplitResult adjusted = buildSplit(raw, adjustedBoundary);
            if (adjusted.hasSplit()) {
                return adjustedBoundary;
            }
        }
        return bestSplit;
    }

    private static int maxArabicTailSignals(String marker) {
        if (marker == null || marker.isEmpty()) {
            return 1;
        }
        String normalized = marker.replace(" ", "");
        // "قال" markers are very broad. If any explicit chain cue remains in the tail,
        // keep scanning to avoid cutting before the final "قال" in a multi-link isnad.
        if ((normalized.startsWith("قال") || normalized.startsWith("فقال"))
                && !normalized.contains("قلت")) {
            return 0;
        }
        return 1;
    }

    private static int findArabicReportedClauseBoundary(String raw, ArabicMatchText matchText, String normalized) {
        if (raw == null || raw.isEmpty() || normalized == null || normalized.isEmpty()) {
            return -1;
        }
        int bestSplit = -1;
        int bestIndex = -1;
        for (String marker : new String[] { " أنه ", " انه ", " أن ", " ان " }) {
            int index = normalized.indexOf(marker);
            while (index >= 0 && index <= MAX_SCAN_CHARS) {
                String prefix = normalized.substring(0, index);
                if (arabicChainSignalScore(prefix) >= 3) {
                    int split = matchText.toRawBoundary(index + marker.length());
                    String tail = trimContent(raw.substring(Math.min(split, raw.length())));
                    if (!startsWithAnyCue(tail, ARABIC_CHAIN_CUES)) {
                        SplitResult candidate = buildSplit(raw, split);
                        if (candidate.hasSplit()
                                && (bestSplit < 0 || index < bestIndex || (index == bestIndex && split > bestSplit))) {
                            bestIndex = index;
                            bestSplit = split;
                        }
                    }
                }
                index = normalized.indexOf(marker, index + marker.length());
            }
        }
        return bestSplit;
    }

    private static int findArabicColonBoundary(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        int bestSplit = -1;
        int bestIndex = -1;
        int index = raw.indexOf(':');
        while (index >= 0 && index <= MAX_SCAN_CHARS) {
            String prefixRaw = raw.substring(0, index);
            String prefix = normalizeArabicForMatching(prefixRaw).normalized();
            boolean hasChainPrefix = arabicChainSignalScore(prefix) >= minArabicChainScore(prefix);
            boolean hasShortNamedNarrator = isShortArabicNamedQalaPrefix(prefixRaw);
            String tail = trimContent(raw.substring(index + 1));
            if ((hasChainPrefix || hasShortNamedNarrator) && !startsWithAnyCue(tail, ARABIC_CHAIN_CUES)) {
                int split = index + 1;
                SplitResult candidate = buildSplit(raw, split);
                if (candidate.hasSplit()
                        && (bestSplit < 0 || index < bestIndex || (index == bestIndex && split > bestSplit))) {
                    bestIndex = index;
                    bestSplit = split;
                }
            }
            index = raw.indexOf(':', index + 1);
        }
        return bestSplit;
    }

    private static boolean isShortArabicNamedQalaPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        String simplified = normalizeWhitespace(SIGNAL_DELIMITERS_PATTERN.matcher(prefix).replaceAll(" ")).trim();
        String suffix = null;
        for (String candidate : new String[] { " قالوا", " قالت", " قال" }) {
            if (simplified.endsWith(candidate)) {
                suffix = candidate;
                break;
            }
        }
        if (suffix == null) {
            return false;
        }
        String subject = simplified.substring(0, simplified.length() - suffix.length()).trim();
        if (subject.isEmpty() || subject.length() > 80) {
            return false;
        }
        String normalizedSubject = normalizeSignalText(subject);
        int nasabLinks = countOccurrences(normalizedSubject, " بن ")
                + countOccurrences(normalizedSubject, " ابن ")
                + countOccurrences(normalizedSubject, " أبي ")
                + countOccurrences(normalizedSubject, " أبو ");
        return nasabLinks >= 1;
    }

    private static boolean isBroadArabicQalaMarker(String marker) {
        if (marker == null || marker.isEmpty()) {
            return false;
        }
        String normalized = marker.replace(" ", "");
        return (normalized.startsWith("قال") || normalized.startsWith("فقال"))
                && !normalized.contains("قلت");
    }

    private static int maxEnglishTailSignals(String marker) {
        if (marker == null || marker.isEmpty()) {
            return 1;
        }
        String normalized = marker.toLowerCase(Locale.ROOT);
        if (normalized.contains("the following")) {
            return 4;
        }
        return 1;
    }

    private static int findEnglishDialogueBoundary(String raw, int splitIndex) {
        if (raw == null || raw.isEmpty() || splitIndex <= 0) {
            return -1;
        }
        var matcher = ENGLISH_DIALOGUE_BOUNDARY_PATTERN.matcher(raw);
        int earliest = -1;
        while (matcher.find()) {
            int idx = matcher.start();
            if (idx > 0 && idx < splitIndex) {
                if (earliest < 0 || idx < earliest) {
                    earliest = idx;
                }
            }
        }
        return earliest;
    }

    private static int findEnglishQuotedBoundary(String raw, String lower) {
        if (raw == null || raw.isEmpty() || lower == null || lower.isEmpty()) {
            return -1;
        }
        int best = -1;
        for (char quote : new char[] { '“', '"' }) {
            int index = raw.indexOf(quote);
            while (index >= 0 && index <= MAX_SCAN_CHARS) {
                String prefix = lower.substring(0, index);
                int required = Math.max(2, minEnglishChainScore(prefix) + 1);
                if (englishChainSignalScore(prefix) >= required
                        && isLikelyMatnBoundary(lower, index, ENGLISH_CHAIN_CUES, 1)) {
                    if (best < 0 || index < best) {
                        best = index;
                    }
                }
                index = raw.indexOf(quote, index + 1);
            }
        }
        return best;
    }

    private static int findEnglishColonBoundary(String lower) {
        if (lower == null || lower.isEmpty()) {
            return -1;
        }
        int best = -1;
        int index = lower.indexOf(':');
        while (index >= 0 && index <= MAX_SCAN_CHARS) {
            String prefix = lower.substring(0, index);
            int required = Math.max(2, minEnglishChainScore(prefix) + 1);
            if (englishChainSignalScore(prefix) >= required
                    && isLikelyMatnBoundary(lower, index + 1, ENGLISH_CHAIN_CUES, 1)) {
                int split = index + 1;
                if (best < 0 || split < best) {
                    best = split;
                }
            }
            index = lower.indexOf(':', index + 1);
        }
        return best;
    }

    private static int findArabicDialogueBoundary(String raw, int splitIndex) {
        if (raw == null || raw.isEmpty() || splitIndex <= 0) {
            return -1;
        }
        ArabicMatchText matchText = normalizeArabicForMatching(raw);
        String normalized = matchText.normalized();
        if (normalized.isEmpty()) {
            return -1;
        }
        int[] rawIndexByNormalized = matchText.rawIndexByNormalized();
        int earliest = -1;
        for (String cue : ARABIC_DIALOGUE_CUES) {
            int idx = findCueIndex(normalized, cue, 0);
            while (idx >= 0) {
                if (idx >= rawIndexByNormalized.length) {
                    break;
                }
                int rawBoundary = rawIndexByNormalized[idx];
                if (rawBoundary > 0 && rawBoundary < splitIndex) {
                    if (earliest < 0 || rawBoundary < earliest) {
                        earliest = rawBoundary;
                    }
                    break;
                }
                idx = findCueIndex(normalized, cue, idx + 1);
            }
        }
        return earliest;
    }

    private static int arabicChainSignalScore(String prefix) {
        String normalized = normalizeSignalText(prefix);
        if (normalized.isEmpty()) {
            return 0;
        }
        int base = chainSignalScore(normalized, ARABIC_CHAIN_CUES);
        int nasabLinks = countOccurrences(normalized, " بن ") + countOccurrences(normalized, " ابن ");
        // "bin/ibn" should help only when chain-link signals already exist; otherwise
        // they over-match long matn passages that mention historical names.
        if (base >= 1 && nasabLinks > 0) {
            return base + Math.min(2, nasabLinks);
        }
        return base;
    }

    private static int minEnglishChainScore(String prefix) {
        String normalized = normalizeSignalText(prefix);
        if (normalized.isEmpty()) {
            return 2;
        }
        String simplified = stripLeadingNarrationPrefix(normalized);
        if (simplified.startsWith(" it has been narrated that ")
                || simplified.startsWith(" it was narrated that ")
                || simplified.startsWith(" it has been reported that ")
                || simplified.startsWith(" it has been related that ")
                || simplified.startsWith(" it is narrated from ")
                || simplified.startsWith(" a number of our people has narrated ")
                || simplified.startsWith(" by this isnad ")
                || simplified.startsWith(" with this isnad ")
                || simplified.startsWith(" and by this chain ")
                || simplified.startsWith(" and with the same chain of transmission ")
                || simplified.contains(" reported by ")
                || simplified.contains(" narrated that ")
                || simplified.contains(" narrated to me ")
                || simplified.contains(" on the authority of ")) {
            return 1;
        }
        return 2;
    }

    private static int englishChainSignalScore(String prefix) {
        String normalized = normalizeSignalText(prefix);
        if (normalized.isEmpty()) {
            return 0;
        }
        int strong = chainSignalScore(normalized, ENGLISH_CHAIN_STRONG_CUES);
        int fromLinks = countOccurrences(normalized, " from ");
        int saidLinks = countOccurrences(normalized, " said ");
        int score = strong * 2;
        if (fromLinks >= 2) {
            score += 2;
        }
        if (fromLinks >= 4) {
            score++;
        }
        if (saidLinks >= 2) {
            score++;
        }
        if (normalized.contains(" narrated that ")) {
            score++;
        }
        if (normalized.contains(" reported by ")) {
            score++;
        }
        return score;
    }

    private static int minArabicChainScore(String prefix) {
        String normalized = normalizeSignalText(prefix);
        if (normalized.isEmpty()) {
            return 2;
        }
        String simplified = stripLeadingNarrationPrefix(normalized);
        String trimmed = simplified.trim();
        if (trimmed.startsWith("وعن")
                || trimmed.startsWith("عن")
                || trimmed.startsWith("عنه")
                || trimmed.startsWith("وبإسناده")
                || trimmed.startsWith("بإسناده")
                || trimmed.startsWith("وباسناده")
                || trimmed.startsWith("باسناده")
                || trimmed.startsWith("وبهذا الاسناد")
                || trimmed.startsWith("بهذا الاسناد")
                || trimmed.startsWith("وبهذا الإسناد")
                || trimmed.startsWith("بهذا الإسناد")
                || trimmed.startsWith("حدثنا")
                || trimmed.startsWith("حدّثنا")
                || trimmed.startsWith("حدثني")
                || trimmed.startsWith("حدّثني")
                || trimmed.startsWith("أخبرنا")
                || trimmed.startsWith("اخبرنا")
                || trimmed.startsWith("أخبرني")
                || trimmed.startsWith("اخبرني")
                || trimmed.startsWith("روي")
                || trimmed.startsWith("وروي")
                || trimmed.startsWith("روى")
                || trimmed.startsWith("وروى")) {
            return 1;
        }
        return 2;
    }

    private static String stripLeadingNarrationPrefix(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return "";
        }
        String simplified = normalized.replaceFirst("^\\s*(?:[\\d\\[\\]()/\\\\.:\\-ـ]+\\s*)+", " ");
        return simplified.isBlank() ? normalized : simplified;
    }

    private static boolean isLikelyMatnBoundary(String text, int splitIndex, String[] cues, int maxTailSignals) {
        if (splitIndex <= 0 || splitIndex >= text.length()) {
            return false;
        }
        int lookAheadEnd = Math.min(text.length(), splitIndex + MATN_LOOKAHEAD_CHARS);
        String tail = text.substring(splitIndex, lookAheadEnd);
        return chainSignalScore(tail, cues) <= maxTailSignals;
    }

    private static boolean startsWithAnyCue(String text, String[] cues) {
        String normalized = normalizeWhitespace(SIGNAL_DELIMITERS_PATTERN.matcher(text == null ? "" : text).replaceAll(" "))
                .stripLeading();
        if (normalized.isEmpty()) {
            return false;
        }
        for (String cue : cues) {
            String normalizedCue = normalizeWhitespace(SIGNAL_DELIMITERS_PATTERN.matcher(cue == null ? "" : cue).replaceAll(" "))
                    .trim();
            if (!normalizedCue.isEmpty()
                    && (normalized.equals(normalizedCue) || normalized.startsWith(normalizedCue + " "))) {
                return true;
            }
        }
        return false;
    }

    private static int findCueIndex(String text, String cue, int fromIndex) {
        if (text == null || text.isEmpty() || cue == null || cue.isBlank()) {
            return -1;
        }
        String token = cue.trim();
        int index = Math.max(0, fromIndex);
        while (index >= 0) {
            index = text.indexOf(token, index);
            if (index < 0) {
                return -1;
            }
            if (hasCueBoundary(text, index, token.length())) {
                return index;
            }
            index += token.length();
        }
        return -1;
    }

    private static boolean hasCueBoundary(String text, int start, int length) {
        int before = start - 1;
        int after = start + length;
        return isCueBoundaryChar(text, before) && isCueBoundaryChar(text, after);
    }

    private static boolean isCueBoundaryChar(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return true;
        }
        char ch = text.charAt(index);
        return !Character.isLetterOrDigit(ch);
    }

    private static SplitResult buildSplit(String raw, int splitIndex) {
        if (splitIndex <= 0 || splitIndex >= raw.length()) {
            return SplitResult.none();
        }
        String chain = trimChain(raw.substring(0, splitIndex));
        String content = trimContent(raw.substring(splitIndex));
        if (visibleLength(chain) < MIN_CHAIN_VISIBLE_CHARS || visibleLength(content) < MIN_CONTENT_VISIBLE_CHARS) {
            return SplitResult.none();
        }
        return new SplitResult(chain, content);
    }

    private static int chainSignalScore(String text, String[] cues) {
        String normalized = normalizeSignalText(text);
        if (normalized.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String cue : cues) {
            score += countOccurrences(normalized, cue);
        }
        return score;
    }

    private static String normalizeSignalText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String delimiterSpaced = SIGNAL_DELIMITERS_PATTERN.matcher(value).replaceAll(" ");
        String normalized = normalizeWhitespace(delimiterSpaced);
        if (normalized.isEmpty()) {
            return "";
        }
        // Pad boundaries so space-delimited cue tokens match at the start/end.
        return " " + normalized + " ";
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

    private static String trimChain(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith(":") || result.endsWith(",") || result.endsWith(";") || result.endsWith("-")
                || result.endsWith("،")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static String trimContent(String value) {
        String result = value == null ? "" : value.trim();
        boolean changed = true;
        while (changed && !result.isEmpty()) {
            changed = false;
            while (result.startsWith(":") || result.startsWith(",") || result.startsWith(";") || result.startsWith("-")
                    || result.startsWith("،")) {
                result = result.substring(1).trim();
                changed = true;
            }
            while (!result.isEmpty() && (isArabicDiacritic(result.charAt(0)) || result.charAt(0) == '\u0640')) {
                result = result.substring(1).trim();
                changed = true;
            }
        }
        return result;
    }

    private static String normalizeWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return MULTISPACE_PATTERN.matcher(value).replaceAll(" ");
    }

    private static VisibleText toVisibleText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new VisibleText("", new int[0], 0);
        }
        StringBuilder visible = new StringBuilder(raw.length());
        int[] rawIndexByVisible = new int[raw.length()];
        int visibleLength = 0;
        boolean inTag = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (inTag) {
                if (ch == '>') {
                    inTag = false;
                }
                continue;
            }
            if (ch == '<') {
                inTag = true;
                continue;
            }
            visible.append(ch);
            rawIndexByVisible[visibleLength] = i;
            visibleLength++;
        }
        int[] compact = new int[visibleLength];
        System.arraycopy(rawIndexByVisible, 0, compact, 0, visibleLength);
        return new VisibleText(visible.toString(), compact, raw.length());
    }

    private static int moveSplitBoundaryOutsideHtml(String raw, int splitIndex) {
        if (raw == null || raw.isEmpty() || splitIndex <= 0 || splitIndex >= raw.length()) {
            return splitIndex;
        }
        int adjusted = splitIndex;
        int lastOpen = raw.lastIndexOf('<', adjusted - 1);
        int lastClose = raw.lastIndexOf('>', adjusted - 1);
        if (lastOpen > lastClose) {
            int tagEnd = raw.indexOf('>', adjusted);
            if (tagEnd < 0) {
                return raw.length();
            }
            adjusted = tagEnd + 1;
        }
        while (adjusted < raw.length() && raw.startsWith("</", adjusted)) {
            int tagEnd = raw.indexOf('>', adjusted);
            if (tagEnd < 0) {
                return raw.length();
            }
            adjusted = tagEnd + 1;
        }
        return adjusted;
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

    private static int visibleLength(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        String withoutTags = HTML_TAG_PATTERN.matcher(value).replaceAll(" ").trim();
        return withoutTags.length();
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

    private record VisibleText(String visible, int[] rawIndexByVisible, int rawLength) {
        private int toRawBoundary(int visibleBoundary) {
            if (visibleBoundary <= 0) {
                return 0;
            }
            if (visibleBoundary >= rawIndexByVisible.length) {
                return rawLength;
            }
            return rawIndexByVisible[visibleBoundary - 1] + 1;
        }
    }

    private record SplitResult(String chain, String content) {
        private static SplitResult none() {
            return new SplitResult("", "");
        }

        private boolean hasSplit() {
            return !chain.isEmpty() && !content.isEmpty();
        }
    }
}

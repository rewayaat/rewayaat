package com.rewayaat.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared scoring utilities for similar-hadith ranking.
 */
public final class SimilarHadithRanking {

    private static final Pattern ARABIC_DIACRITICS = Pattern.compile("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]");
    private static final Pattern NON_ARABIC_LETTERS = Pattern.compile("[^\\p{IsArabic}\\p{Digit}\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
    private static final Set<String> DISTINCTIVE_TOKEN_STOPWORDS = new HashSet<>(List.of(
            "الله", "اللهم", "قال", "قلت", "كان", "كانت", "يكون", "فكان", "تكون", "قالت", "يقول", "يقولون",
            "رسول", "نبي", "ابو", "ابي", "ابن", "بنت", "عبد", "رجل", "امراه", "ناس", "الناس", "قوم",
            "عليه", "عليها", "عليهم", "عليهما", "السلام", "صلى", "اله", "عز", "وجل", "تبارك", "تعالى",
            "جل", "ذكره", "عند", "عندي", "عنده", "عندها", "عندهم", "هذا", "هذه", "ذلك", "تلك", "هناك",
            "ثم", "اذا", "فاذا", "انه", "انها", "انهم", "انهما", "انا", "انت", "انتم", "نحن", "هو", "هي",
            "هم", "هن", "كما", "لما", "بعد", "قبل", "بين", "مع", "من", "الى", "على", "عن", "في", "ما",
            "لا", "لم", "لن", "قد", "بل", "او", "اي", "ايضا", "هنا", "اليوم", "ليله", "الا",
            "قول", "قالوا", "قلنا", "فقالوا", "فقلت", "اخبرني", "اخبرنا", "حدثني", "حدثنا", "سمعت", "سالت",
            "سئل", "دخل", "خرج", "جاء", "اتى", "جعل", "جعلت", "جعلكم", "جعلنا", "اعلم", "اعلمه", "قالوا",
            "فقال", "وقال", "ويقول", "ويقولون", "يقال", "ساله", "سالته", "لهم", "له", "لها", "لك", "لي",
            "عنه", "عنها", "عنهم", "منه", "منها", "منهم", "عندك", "عندكم", "بهم", "بها", "به", "فيه",
            "فيها", "فيهم", "علي", "الي", "اليه", "اليها", "اليهم", "اليكم", "عليك", "عليكم", "عليكن",
            "الذي", "التي", "الذين", "اللذين", "اللتين", "انما", "ربي", "ربك", "ربه", "ربها", "ربهم",
            "روي", "روى", "يروي", "سند", "اسناد", "اراد", "اردت", "يريد", "كانوا", "كنت", "كنا",
            "يكن", "فلما", "حتى", "ولكن", "لكن", "اذ", "اما", "شيء", "شيئا", "احد", "احدكم", "بعض",
            "بعضهم", "كل", "جميع", "كبير", "صغير", "كثير", "قليل"));

    private SimilarHadithRanking() {
    }

    public static double toPercentFromRawScore(Double score) {
        if (score == null) {
            return 0d;
        }
        double value = score;
        if (value <= 0d) {
            return 0d;
        }
        double percent = value <= 1.0d ? value * 100.0d : value;
        return round(clampPercent(percent), 2);
    }

    public static double calibrateBoundedScore(Double score, double floor, double ceiling, double exponent) {
        if (score == null) {
            return 0d;
        }
        double value = score;
        if (value <= floor) {
            return 0d;
        }
        double safeCeiling = Math.max(floor + 0.0001d, ceiling);
        if (value >= safeCeiling) {
            return 100d;
        }
        double normalized = (value - floor) / (safeCeiling - floor);
        double curved = Math.pow(Math.max(0d, Math.min(normalized, 1d)), Math.max(0.1d, exponent));
        return round(clampPercent(curved * 100.0d), 2);
    }

    public static double combinePercent(double primaryPercent, double primaryWeight,
            double secondaryPercent, double secondaryWeight) {
        double pWeight = Math.max(0d, primaryWeight);
        double sWeight = Math.max(0d, secondaryWeight);
        double total = pWeight + sWeight;
        if (total <= 0d) {
            return round(clampPercent(primaryPercent), 2);
        }
        double combined = ((clampPercent(primaryPercent) * pWeight) + (clampPercent(secondaryPercent) * sWeight))
                / total;
        return round(clampPercent(combined), 2);
    }

    public static double syntacticSimilarityPercent(String source, String target) {
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            return 0d;
        }
        String sourceNorm = normalizeArabic(source);
        String targetNorm = normalizeArabic(target);
        if (sourceNorm.isBlank() || targetNorm.isBlank()) {
            return 0d;
        }
        double tokenScore = tokenJaccard(sourceNorm, targetNorm);
        double charScore = charNgramJaccard(sourceNorm, targetNorm, 3);
        return round(clampPercent(((tokenScore * 0.65d) + (charScore * 0.35d)) * 100.0d), 2);
    }

    public static List<String> distinctiveTokens(String text) {
        List<String> stream = distinctiveTokenStream(text);
        if (stream.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(stream));
    }

    public static List<String> distinctiveTokenStream(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = normalizeArabic(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String rawToken : normalized.split(" ")) {
            String token = normalizeDistinctiveToken(rawToken);
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    public static String distinctiveLexicalQuery(String text, int maxTokens) {
        List<String> tokens = distinctiveTokens(text);
        if (tokens.isEmpty() || maxTokens == 0) {
            return "";
        }
        int limit = maxTokens < 0 ? tokens.size() : Math.min(tokens.size(), maxTokens);
        return String.join(" ", tokens.subList(0, limit));
    }

    public static int distinctiveTokenCount(String text) {
        return distinctiveTokens(text).size();
    }

    public static int sharedDistinctiveTokenCount(String source, String target) {
        Set<String> sourceTokens = new LinkedHashSet<>(distinctiveTokens(source));
        Set<String> targetTokens = new LinkedHashSet<>(distinctiveTokens(target));
        if (sourceTokens.isEmpty() || targetTokens.isEmpty()) {
            return 0;
        }
        sourceTokens.retainAll(targetTokens);
        return sourceTokens.size();
    }

    public static List<String> sharedSyntacticTokens(String source, String target) {
        String normalizedSource = normalizeArabic(source);
        String normalizedTarget = normalizeArabic(target);
        if (normalizedSource.isBlank() || normalizedTarget.isBlank()) {
            return List.of();
        }
        return orderedIntersection(tokenizeArabicOrdered(normalizedSource), tokenizeArabic(normalizedTarget));
    }

    public static List<String> sharedDistinctiveTokens(String source, String target) {
        Set<String> targetTokens = new LinkedHashSet<>(distinctiveTokens(target));
        if (targetTokens.isEmpty()) {
            return List.of();
        }
        return orderedIntersection(distinctiveTokens(source), targetTokens);
    }

    public static List<String> sharedTerms(List<String> sourceTerms, String targetText) {
        Set<String> targetTokens = new LinkedHashSet<>(distinctiveTokens(targetText));
        if (targetTokens.isEmpty()) {
            return List.of();
        }
        return orderedIntersection(new ArrayList<>(normalizedDistinctiveTerms(sourceTerms)), targetTokens);
    }

    public static double distinctiveTokenRecallPercent(String source, String target) {
        Set<String> sourceTokens = new LinkedHashSet<>(distinctiveTokens(source));
        if (sourceTokens.isEmpty()) {
            return 0d;
        }
        Set<String> targetTokens = new LinkedHashSet<>(distinctiveTokens(target));
        if (targetTokens.isEmpty()) {
            return 0d;
        }
        sourceTokens.retainAll(targetTokens);
        return round(clampPercent((((double) sourceTokens.size()) / ((double) distinctiveTokenCount(source))) * 100.0d), 2);
    }

    public static double bidirectionalTokenOverlapPercent(String source, String target) {
        double forwardRecall = distinctiveTokenRecallPercent(source, target);
        double reverseRecall = distinctiveTokenRecallPercent(target, source);
        if (forwardRecall + reverseRecall <= 0d) {
            return 0d;
        }
        return round(clampPercent((2.0d * forwardRecall * reverseRecall) / (forwardRecall + reverseRecall)), 2);
    }

    public static int sharedTopicTagCount(List<String> sourceTopicTags, List<String> targetTopicTags) {
        Set<String> sourceTags = normalizedTopicTags(sourceTopicTags);
        Set<String> targetTags = normalizedTopicTags(targetTopicTags);
        if (sourceTags.isEmpty() || targetTags.isEmpty()) {
            return 0;
        }
        sourceTags.retainAll(targetTags);
        return sourceTags.size();
    }

    public static List<String> sharedTopicTags(List<String> sourceTopicTags, List<String> targetTopicTags) {
        Set<String> targetTags = normalizedTopicTags(targetTopicTags);
        if (targetTags.isEmpty()) {
            return List.of();
        }
        return orderedIntersection(new ArrayList<>(normalizedTopicTags(sourceTopicTags)), targetTags);
    }

    public static double topicTagJaccardPercent(List<String> sourceTopicTags, List<String> targetTopicTags) {
        Set<String> sourceTags = normalizedTopicTags(sourceTopicTags);
        Set<String> targetTags = normalizedTopicTags(targetTopicTags);
        if (sourceTags.isEmpty() || targetTags.isEmpty()) {
            return 0d;
        }
        Set<String> intersection = new HashSet<>(sourceTags);
        intersection.retainAll(targetTags);
        Set<String> union = new HashSet<>(sourceTags);
        union.addAll(targetTags);
        if (union.isEmpty()) {
            return 0d;
        }
        return round(clampPercent((((double) intersection.size()) / ((double) union.size())) * 100.0d), 2);
    }

    public static int sharedTermCount(List<String> sourceTerms, String targetText) {
        Set<String> sourceTokens = normalizedDistinctiveTerms(sourceTerms);
        Set<String> targetTokens = new LinkedHashSet<>(distinctiveTokens(targetText));
        if (sourceTokens.isEmpty() || targetTokens.isEmpty()) {
            return 0;
        }
        sourceTokens.retainAll(targetTokens);
        return sourceTokens.size();
    }

    public static double termRecallPercent(List<String> sourceTerms, String targetText) {
        Set<String> sourceTokens = normalizedDistinctiveTerms(sourceTerms);
        if (sourceTokens.isEmpty()) {
            return 0d;
        }
        Set<String> targetTokens = new LinkedHashSet<>(distinctiveTokens(targetText));
        if (targetTokens.isEmpty()) {
            return 0d;
        }
        int sourceSize = sourceTokens.size();
        sourceTokens.retainAll(targetTokens);
        return round(clampPercent((((double) sourceTokens.size()) / ((double) sourceSize)) * 100.0d), 2);
    }

    public static String normalizeArabic(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = ARABIC_DIACRITICS.matcher(text).replaceAll("");
        normalized = normalized.replace("أ", "ا")
                .replace("إ", "ا")
                .replace("آ", "ا")
                .replace("ٱ", "ا")
                .replace("ى", "ي")
                .replace("ؤ", "و")
                .replace("ئ", "ي")
                .replace("ة", "ه")
                .replace("ـ", "");
        normalized = NON_ARABIC_LETTERS.matcher(normalized).replaceAll(" ");
        return MULTI_SPACE.matcher(normalized).replaceAll(" ").trim();
    }

    public static List<CandidateScore> filterAndSort(List<CandidateInput> candidates,
            double minCombinedPercent, double llmWeight) {
        List<CandidateScore> result = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (CandidateInput input : candidates) {
            if (input == null || input.id() == null || input.id().isBlank()) {
                continue;
            }
            if (!seen.add(input.id())) {
                continue;
            }
            double combined = combinePercent(input.llmPercent(), llmWeight, input.retrievalPercent(), 1.0d - llmWeight);
            if (combined < minCombinedPercent) {
                continue;
            }
            result.add(new CandidateScore(input.id(), round(clampPercent(input.llmPercent()), 2),
                    round(clampPercent(input.retrievalPercent()), 2), combined));
        }
        result.sort(Comparator
                .comparingDouble(CandidateScore::combinedPercent)
                .thenComparingDouble(CandidateScore::llmPercent)
                .reversed());
        return result;
    }

    public static double clampPercent(double value) {
        if (value < 0d) {
            return 0d;
        }
        if (value > 100d) {
            return 100d;
        }
        return value;
    }

    public static double round(double value, int precision) {
        double scale = Math.pow(10, precision);
        return Math.round(value * scale) / scale;
    }

    private static String normalizeDistinctiveToken(String token) {
        String clean = token == null ? "" : token.trim();
        if (clean.isBlank() || DISTINCTIVE_TOKEN_STOPWORDS.contains(clean)) {
            return "";
        }
        clean = stripContentPrefixes(clean);
        if (clean.isBlank() || DISTINCTIVE_TOKEN_STOPWORDS.contains(clean) || "الله".equals(clean)) {
            return "";
        }
        return clean.length() >= 3 ? clean : "";
    }

    private static String stripContentPrefixes(String token) {
        String clean = token == null ? "" : token;
        for (int i = 0; i < 3; i++) {
            String stripped = clean;
            if (stripped.length() > 5 && (stripped.startsWith("وال") || stripped.startsWith("فال")
                    || stripped.startsWith("بال") || stripped.startsWith("كال"))) {
                stripped = stripped.substring(1);
            } else if (stripped.length() > 4 && stripped.startsWith("لل") && !stripped.startsWith("لله")) {
                stripped = stripped.substring(2);
            } else if (stripped.length() > 4 && stripped.startsWith("ال") && !stripped.startsWith("الله")) {
                stripped = stripped.substring(2);
            } else if (stripped.length() > 4 && (stripped.startsWith("و") || stripped.startsWith("ف")
                    || stripped.startsWith("ب") || stripped.startsWith("ك"))) {
                stripped = stripped.substring(1);
            }
            if (stripped.equals(clean)) {
                break;
            }
            clean = stripped;
        }
        return clean;
    }

    private static Set<String> normalizedDistinctiveTerms(List<String> terms) {
        Set<String> normalized = new LinkedHashSet<>();
        if (terms == null || terms.isEmpty()) {
            return normalized;
        }
        for (String term : terms) {
            normalized.addAll(distinctiveTokens(term));
        }
        return normalized;
    }

    private static Set<String> normalizedTopicTags(List<String> topicTags) {
        Set<String> normalized = new LinkedHashSet<>();
        if (topicTags == null || topicTags.isEmpty()) {
            return normalized;
        }
        for (String topicTag : topicTags) {
            if (topicTag == null) {
                continue;
            }
            String clean = topicTag.trim();
            if (!clean.isBlank()) {
                normalized.add(clean);
            }
        }
        return normalized;
    }

    private static List<String> orderedIntersection(List<String> sourceTokens, Set<String> targetTokens) {
        if (sourceTokens == null || sourceTokens.isEmpty() || targetTokens == null || targetTokens.isEmpty()) {
            return List.of();
        }
        List<String> shared = new ArrayList<>();
        for (String token : sourceTokens) {
            if (token != null && !token.isBlank() && targetTokens.contains(token)) {
                shared.add(token);
            }
        }
        return shared;
    }

    private static double tokenJaccard(String source, String target) {
        Set<String> sourceTokens = tokenizeArabic(source);
        Set<String> targetTokens = tokenizeArabic(target);
        if (sourceTokens.isEmpty() || targetTokens.isEmpty()) {
            return 0d;
        }
        Set<String> intersection = new HashSet<>(sourceTokens);
        intersection.retainAll(targetTokens);
        Set<String> union = new HashSet<>(sourceTokens);
        union.addAll(targetTokens);
        if (union.isEmpty()) {
            return 0d;
        }
        return ((double) intersection.size()) / ((double) union.size());
    }

    private static Set<String> tokenizeArabic(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String[] split = text.split(" ");
        for (String token : split) {
            String clean = token == null ? "" : token.trim();
            if (clean.length() >= 2) {
                tokens.add(clean);
            }
        }
        return tokens;
    }

    private static List<String> tokenizeArabicOrdered(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        String[] split = text.split(" ");
        for (String token : split) {
            String clean = token == null ? "" : token.trim();
            if (clean.length() >= 2) {
                ordered.add(clean);
            }
        }
        return new ArrayList<>(ordered);
    }

    private static double charNgramJaccard(String source, String target, int ngramSize) {
        Set<String> sourceNgrams = toCharacterNgrams(source, ngramSize);
        Set<String> targetNgrams = toCharacterNgrams(target, ngramSize);
        if (sourceNgrams.isEmpty() || targetNgrams.isEmpty()) {
            return 0d;
        }
        Set<String> intersection = new HashSet<>(sourceNgrams);
        intersection.retainAll(targetNgrams);
        Set<String> union = new HashSet<>(sourceNgrams);
        union.addAll(targetNgrams);
        if (union.isEmpty()) {
            return 0d;
        }
        return ((double) intersection.size()) / ((double) union.size());
    }

    private static Set<String> toCharacterNgrams(String text, int ngramSize) {
        Set<String> ngrams = new HashSet<>();
        if (text == null || text.isBlank() || ngramSize <= 0) {
            return ngrams;
        }
        String compact = text.replace(" ", "");
        if (compact.length() < ngramSize) {
            return ngrams;
        }
        for (int i = 0; i <= compact.length() - ngramSize; i++) {
            ngrams.add(compact.substring(i, i + ngramSize));
        }
        return ngrams;
    }

    public record CandidateInput(String id, double llmPercent, double retrievalPercent) {
    }

    public record CandidateScore(String id, double llmPercent, double retrievalPercent, double combinedPercent) {
    }
}

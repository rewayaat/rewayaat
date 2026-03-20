package com.rewayaat.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared utilities for extracting and persisting hadith-level significant terms.
 */
public final class HadithSignificantTerms {

    public static final String FIELD_NAME = "semantic_significant_terms_source";

    private HadithSignificantTerms() {
    }

    public static List<String> readTerms(Map source, int maxTerms) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Object rawValue = source.get(FIELD_NAME);
        if (!(rawValue instanceof String)) {
            return List.of();
        }
        return parseTerms((String) rawValue, maxTerms);
    }

    public static List<String> parseTerms(String rawTerms, int maxTerms) {
        if (rawTerms == null || rawTerms.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String token : rawTerms.split("[,\\s]+")) {
            String clean = token == null ? "" : token.trim();
            if (!clean.isBlank()) {
                terms.add(clean);
            }
            if (maxTerms > 0 && terms.size() >= maxTerms) {
                break;
            }
        }
        return new ArrayList<>(terms);
    }

    public static List<String> fallbackTerms(String normalizedMatn, int maxTerms) {
        List<String> candidates = SimilarHadithRanking.distinctiveTokens(normalizedMatn);
        if (candidates.isEmpty()) {
            return List.of();
        }
        int limit = maxTerms > 0 ? Math.min(maxTerms, candidates.size()) : candidates.size();
        return new ArrayList<>(candidates.subList(0, limit));
    }

    public static List<String> candidateTerms(String normalizedMatn, int limit) {
        List<String> tokens = SimilarHadithRanking.distinctiveTokenStream(normalizedMatn);
        if (tokens.isEmpty()) {
            return List.of();
        }
        int resolvedLimit = limit > 0 ? Math.min(limit, tokens.size()) : tokens.size();
        return new ArrayList<>(tokens.subList(0, resolvedLimit));
    }

    public static List<String> rankTerms(List<String> candidateTerms,
            Map<String, Integer> documentFrequency,
            int totalDocumentCount,
            int maxTerms) {
        if (candidateTerms == null || candidateTerms.isEmpty()) {
            return List.of();
        }
        int safeDocCount = Math.max(totalDocumentCount, 1);
        Map<String, Integer> firstPosition = new HashMap<>();
        Map<String, Integer> termFrequency = new HashMap<>();
        for (int i = 0; i < candidateTerms.size(); i++) {
            String term = candidateTerms.get(i);
            if (term == null || term.isBlank()) {
                continue;
            }
            firstPosition.putIfAbsent(term, i);
            termFrequency.put(term, termFrequency.getOrDefault(term, 0) + 1);
        }
        List<TermScore> ranked = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : firstPosition.entrySet()) {
            String term = entry.getKey();
            if (term == null || term.isBlank()) {
                continue;
            }
            int position = entry.getValue();
            int tf = Math.max(1, termFrequency.getOrDefault(term, 1));
            int df = Math.max(1, documentFrequency == null ? 1 : documentFrequency.getOrDefault(term, 1));
            double rarityScore = Math.log((safeDocCount + 1.0d) / (df + 1.0d));
            double tfBoost = 1.0d + (0.9d * Math.log1p(Math.max(0, tf - 1)));
            double lengthBonus = 1.0d + Math.min(0.35d, Math.max(0, term.length() - 4) * 0.05d);
            double positionBonus = Math.max(0.74d, 1.10d - (position * 0.03d));
            double singletonPenalty = 1.0d;
            if (tf == 1 && df <= 80) {
                if (df <= 5) {
                    singletonPenalty = 0.38d;
                } else if (df <= 25) {
                    singletonPenalty = 0.55d;
                } else {
                    singletonPenalty = 0.75d;
                }
            }
            double score = rarityScore * tfBoost * lengthBonus * positionBonus * singletonPenalty;
            ranked.add(new TermScore(term, position, score));
        }
        ranked.sort(Comparator
                .comparingDouble(TermScore::score)
                .thenComparingInt(TermScore::position)
                .reversed());
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (TermScore score : ranked) {
            result.add(score.term());
            if (maxTerms > 0 && result.size() >= maxTerms) {
                break;
            }
        }
        return new ArrayList<>(result);
    }

    public static String joinTerms(List<String> terms, int maxTerms) {
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        List<String> clean = new ArrayList<>();
        for (String term : terms) {
            String token = term == null ? "" : term.trim();
            if (!token.isBlank()) {
                clean.add(token);
            }
            if (maxTerms > 0 && clean.size() >= maxTerms) {
                break;
            }
        }
        return String.join(" ", clean);
    }

    public static Map<String, Integer> incrementDocumentFrequency(Map<String, Integer> documentFrequency,
            List<String> terms) {
        if (documentFrequency == null || terms == null || terms.isEmpty()) {
            return documentFrequency;
        }
        Set<String> uniqueTerms = new LinkedHashSet<>(terms);
        for (String term : uniqueTerms) {
            documentFrequency.put(term, documentFrequency.getOrDefault(term, 0) + 1);
        }
        return documentFrequency;
    }

    private record TermScore(String term, int position, double score) {
    }
}

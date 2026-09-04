package com.rewayaat.core;

import com.rewayaat.config.ESClientProvider;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SearchType;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A collection of highly significant terms based on a given set of input terms.
 */
public class HighlySignificantTerms {
    private static final String SIGNIFICANT_TERMS_FIELD = HadithSignificantTerms.FIELD_NAME;

    private final int size;
    private List<String> inputTerms;

    // Common stopwords to filter out
    private static final Set<String> ENGLISH_STOPWORDS = new HashSet<>(Arrays.asList(
        "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with",
        "from", "by", "about", "as", "into", "through", "during", "before",
        "after", "above", "below", "between", "under", "again", "further",
        "then", "once", "here", "there", "when", "where", "why", "how",
        "all", "each", "few", "more", "most", "other", "some", "such", "no",
        "nor", "not", "only", "own", "same", "so", "than", "too", "very",
        "can", "will", "just", "should", "now", "said", "says", "also",
        "would", "could", "shall", "may", "might", "must", "ought"
    ));

    private static final Set<String> ARABIC_STOPWORDS = new HashSet<>(Arrays.asList(
        "في", "من", "على", "إلى", "عن", "مع", "هذا", "هذه", "ذلك", "تلك",
        "كان", "كانت", "يكون", "الذي", "التي", "الذين", "اللاتي", "أن",
        "أن", "إن", "لا", "ما", "لم", "لن", "هل", "كيف", "أين", "متى",
        "كم", "لماذا", "من", "في", "عن", "على", "إلا", "أو", "ثم", "حتى"
    ));

    public HighlySignificantTerms(int size, String[] inputTerms) {
        this.size = size;
        this.inputTerms = Arrays.asList(inputTerms);
    }

    public JSONArray terms() throws IOException {
        JSONArray result = new JSONArray();
        List<String> englishValues = new ArrayList<>();
        List<String> arabicValues = new ArrayList<>();
        Set<String> normalizedInputs = new HashSet<>();
        boolean hasArabicInput = false;

        for (String inputTerm : inputTerms) {
            // filter out phrases..
            if (!inputTerm.trim().contains(" ") && !inputTerm.trim().startsWith("\"")) {
                if (new RewayaatTerm(inputTerm).isArabic()) {
                    String normalized = StringUtils.stripAccents(inputTerm.trim());
                    arabicValues.add(normalized);
                    normalizedInputs.add(normalized);
                    hasArabicInput = true;
                } else {
                    String normalized = StringUtils.stripAccents(inputTerm.trim().toLowerCase(Locale.ROOT));
                    englishValues.add(normalized);
                    normalizedInputs.add(normalized);
                }
            }
        }

        if (englishValues.isEmpty() && arabicValues.isEmpty()) {
            return result;
        }

        try (ESClientProvider provider = new ESClientProvider()) {
            BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();

            // Determine primary language field based on input
            boolean isArabicSearch = hasArabicInput || (!hasArabicInput && englishValues.isEmpty());

            if (!englishValues.isEmpty()) {
                List<FieldValue> values = englishValues.stream().map(FieldValue::of).collect(Collectors.toList());
                // Use MUST for English terms - all terms must match for relevance
                boolQueryBuilder.must(s -> s.terms(t -> t.field("english").terms(v -> v.value(values))));
            }

            if (!arabicValues.isEmpty()) {
                List<FieldValue> values = arabicValues.stream().map(FieldValue::of).collect(Collectors.toList());
                // Use MUST for Arabic terms - all terms must match for relevance
                boolQueryBuilder.must(s -> s.terms(t -> t.field("arabic").terms(v -> v.value(values))));
            }

            Query query = new Query.Builder().bool(boolQueryBuilder.build()).build();

            // Increase sample size for better term analysis
            int sampleSize = isArabicSearch ? 300 : 500;

            SearchResponse<Map> response = provider.client().search(s -> s
                    .index(ESClientProvider.INDEX)
                    .searchType(SearchType.DfsQueryThenFetch)
                    .size(sampleSize)
                    .source(HadithSourceFilter.searchSource())
                    .query(query),
                    Map.class);

            Map<String, Integer> termFrequency = new HashMap<>();
            Map<String, Integer> termDocFreq = new HashMap<>();
            int totalDocs = response.hits().hits().size();

            if (totalDocs == 0) {
                return result;
            }

            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source == null || source.isEmpty()) {
                    continue;
                }

                List<String> storedTerms = HadithSignificantTerms.readTerms(source, 16);
                if (storedTerms.isEmpty()) {
                    continue;
                }

                Set<String> docTerms = new LinkedHashSet<>();
                for (String storedTerm : storedTerms) {
                    String normalized = normalizeCandidateTerm(storedTerm);
                    if (!isUsefulTerm(normalized, normalizedInputs)) {
                        continue;
                    }
                    termFrequency.merge(normalized, 1, Integer::sum);
                    docTerms.add(normalized);
                }

                for (String docTerm : docTerms) {
                    termDocFreq.merge(docTerm, 1, Integer::sum);
                }
            }

            Map<String, Double> termScores = new HashMap<>();
            for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
                String term = entry.getKey();
                int freq = entry.getValue();
                int docFreq = termDocFreq.getOrDefault(term, 1);
                double score = (docFreq * 12.0) + (freq * 0.75) + Math.min(1.5, term.length() * 0.08);
                termScores.put(term, score);
            }

            int minDocFreq = Math.max(2, totalDocs / 30);
            int maxDocFreq = Math.max(minDocFreq, (int) Math.ceil(totalDocs * 0.7));

            termScores.entrySet().stream()
                .filter(e -> {
                    int df = termDocFreq.getOrDefault(e.getKey(), 0);
                    return df >= minDocFreq && df <= maxDocFreq;
                })
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(this.size)
                .forEach(entry -> result.put(entry.getKey()));

            return result;
        }
    }

    private static String normalizeCandidateTerm(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        cleaned = cleaned.replaceAll("^[\\p{Punct}\\[\\]\\(\\)\\{\\}«»]+", "");
        cleaned = cleaned.replaceAll("[\\p{Punct}\\[\\]\\(\\)\\{\\}«»]+$", "");
        cleaned = cleaned.replaceAll("^\"+|\"+$", "");
        return StringUtils.stripAccents(cleaned.toLowerCase(Locale.ROOT));
    }

    private static boolean isUsefulTerm(String normalized, Set<String> normalizedInputs) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        if (normalized.matches("\\d+")) {
            return false;
        }
        if (normalizedInputs.contains(normalized)) {
            return false;
        }
        boolean isArabic = new RewayaatTerm(normalized).isArabic();
        if (normalized.length() <= (isArabic ? 2 : 3)) {
            return false;
        }
        return !(isArabic ? ARABIC_STOPWORDS : ENGLISH_STOPWORDS).contains(normalized);
    }
}

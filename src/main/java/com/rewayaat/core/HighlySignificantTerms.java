package com.rewayaat.core;

import com.rewayaat.config.ESClientProvider;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SearchType;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.SignificantStringTermsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.SignificantStringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A collection of highly significant terms based on a given set of input terms.
 */
public class HighlySignificantTerms {

    private final int size;
    private List<String> inputTerms;
    private static final double MINIMUM_SCORE = 0.5;

    public HighlySignificantTerms(int size, String[] inputTerms) {
        this.size = size;
        this.inputTerms = Arrays.asList(inputTerms);
    }

    public JSONArray terms() throws IOException {
        JSONArray result = new JSONArray();
        List<String> englishValues = new ArrayList<>();
        List<String> arabicValues = new ArrayList<>();
        Set<String> normalizedInputs = new HashSet<>();
        for (String inputTerm : inputTerms) {
            // filter out phrases..
            if (!inputTerm.trim().contains(" ") && !inputTerm.trim().startsWith("\"")) {
                if (new RewayaatTerm(inputTerm).isArabic()) {
                    String normalized = StringUtils.stripAccents(inputTerm.trim());
                    arabicValues.add(normalized);
                    normalizedInputs.add(normalized);
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
            int shouldClauseCount = 0;
            if (!englishValues.isEmpty()) {
                List<FieldValue> values = englishValues.stream().map(FieldValue::of).collect(Collectors.toList());
                boolQueryBuilder.should(s -> s.terms(t -> t.field("english").terms(v -> v.value(values))));
                shouldClauseCount++;
            }
            if (!arabicValues.isEmpty()) {
                List<FieldValue> values = arabicValues.stream().map(FieldValue::of).collect(Collectors.toList());
                boolQueryBuilder.should(s -> s.terms(t -> t.field("arabic").terms(v -> v.value(values))));
                shouldClauseCount++;
            }
            boolQueryBuilder.minimumShouldMatch(String.valueOf(Math.max(1, shouldClauseCount)));
            Query query = new Query.Builder().bool(boolQueryBuilder.build()).build();

            SearchResponse<Void> response = provider.client().search(s -> {
                s.index(ESClientProvider.INDEX)
                        .searchType(SearchType.DfsQueryThenFetch)
                        .size(0)
                        .query(query);
                s.aggregations("significantArabicTerms", a -> a
                        .significantTerms(t -> t.field("arabic").size(Math.max(this.size * 6, 12))));
                return s;
            }, Void.class);

            Aggregate arabicAgg = response.aggregations().get("significantArabicTerms");
            List<SignificantStringTermsBucket> allBuckets = new ArrayList<>();
            if (arabicAgg != null) {
                SignificantStringTermsAggregate arabicTermsAgg = arabicAgg.sigsterms();
                allBuckets.addAll(arabicTermsAgg.buckets().array());
            }
            allBuckets = allBuckets.stream()
                    .filter(x -> {
                        String normalized = StringUtils.stripAccents(x.key().trim().toLowerCase(Locale.ROOT));
                        return !normalizedInputs.contains(normalized);
                    })
                    .collect(Collectors.toList());
            allBuckets.sort(new SignificantTermsBucketComparator());
            List<SignificantStringTermsBucket> firstSizeElementsList = allBuckets.stream().limit(this.size)
                    .collect(Collectors.toList());
            for (SignificantStringTermsBucket bucket : firstSizeElementsList) {
                if (bucket.score() < MINIMUM_SCORE) {
                    break;
                } else {
                    result.put(bucket.key());
                }
            }
            return result;
        }
    }

    /**
     * Comparator for the Significant Terms Bucket.
     */
    public static class SignificantTermsBucketComparator implements Comparator<SignificantStringTermsBucket> {
        @Override
        public int compare(SignificantStringTermsBucket o1, SignificantStringTermsBucket o2) {
            if (o1.score() == o2.score()) {
                return 0;
            } else if (o1.score() < o2.score()) {
                return 1;
            } else {
                return -1;
            }
        }
    }
}

package com.rewayaat.core;

import com.rewayaat.config.ESClientProvider;

import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.ParsedSignificantTerms;
import org.elasticsearch.search.aggregations.bucket.terms.SignificantTerms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.json.JSONArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
        for (String inputTerm : inputTerms) {
            // filter out phrases..
            if (!inputTerm.trim().contains(" ") && !inputTerm.trim().startsWith("\"")) {
                if (new RewayaatTerm(inputTerm).isArabic()) {
                    arabicValues.add(StringUtils.stripAccents(inputTerm.trim()));
                } else {
                    englishValues.add(StringUtils.stripAccents(inputTerm.trim().toLowerCase()));
                }
            }
        }

        try (RestHighLevelClient client = new ESClientProvider().client()) {
            BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery()
                    .should(QueryBuilders.termsQuery("english", englishValues))
                    .should(QueryBuilders.termsQuery("arabic", arabicValues))
                    .minimumShouldMatch((int) (this.inputTerms.size() * 0.25));

            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            searchSourceBuilder.query(boolQueryBuilder)
                    .aggregation(AggregationBuilders
                            .significantTerms("significantEnglishTerms").field("english").size(this.size))
                    .aggregation(AggregationBuilders
                            .significantTerms("significantArabicTerms").field("arabic").size(this.size));

            SearchRequest searchRequest = new SearchRequest(ESClientProvider.INDEX);
            searchRequest.searchType(SearchType.DFS_QUERY_THEN_FETCH).source(searchSourceBuilder);

            SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

            ParsedSignificantTerms englishTermsAgg = response.getAggregations().get("significantEnglishTerms");
            ParsedSignificantTerms arabicTermsAgg = response.getAggregations().get("significantArabicTerms");
            List<SignificantTerms.Bucket> allBuckets = new ArrayList<>();
            allBuckets.addAll(englishTermsAgg.getBuckets());
            allBuckets.addAll(arabicTermsAgg.getBuckets());
            allBuckets = allBuckets.stream()
                    .filter(x -> !this.inputTerms
                            .contains(StringUtils.stripAccents(x.getKeyAsString().trim().toLowerCase())))
                    .collect(Collectors.toList());
            allBuckets.sort(new SignifcantTermsBucketComparator());
            List<SignificantTerms.Bucket> firstSizeElementsList = allBuckets.stream().limit(this.size)
                    .collect(Collectors.toList());
            for (SignificantTerms.Bucket bucket : firstSizeElementsList) {
                if (bucket.getSignificanceScore() < MINIMUM_SCORE) {
                    break;
                } else {
                    result.put(bucket.getKeyAsString());
                }
            }
            return result;
        }
    }

    /**
     * Comparator for the Significant Terms Bucket.
     */
    public static class SignifcantTermsBucketComparator implements Comparator<SignificantTerms.Bucket> {
        @Override
        public int compare(SignificantTerms.Bucket o1, SignificantTerms.Bucket o2) {
            if (o1.getSignificanceScore() == o2.getSignificanceScore()) {
                return 0;
            } else if (o1.getSignificanceScore() < o2.getSignificanceScore()) {
                return 1;
            } else {
                return -1;
            }
        }
    }
}

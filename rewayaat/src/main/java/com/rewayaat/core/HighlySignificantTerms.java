package com.rewayaat.core;

import com.rewayaat.config.ClientProvider;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantTerms;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    public JSONArray terms() {
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

        SearchResponse resp = ClientProvider.instance().getClient().prepareSearch(ClientProvider.INDEX)
                .setTypes(ClientProvider.TYPE).setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(QueryBuilders.boolQuery().should(QueryBuilders.termsQuery("english", englishValues))
                        .should(QueryBuilders.termsQuery("arabic", arabicValues)).minimumShouldMatch((int) (this.inputTerms.size() * 0.25)))
                .addAggregation(AggregationBuilders
                        .significantTerms("significantEnglishTerms").field("english").size(this.size))
                .addAggregation(AggregationBuilders
                        .significantTerms("significantArabicTerms").field("arabic").size(this.size))
                .get();
        SignificantTerms englishTermsAgg = resp.getAggregations().get("significantEnglishTerms");
        SignificantTerms arabicTermsAgg = resp.getAggregations().get("significantArabicTerms");
        List<SignificantTerms.Bucket> allBuckets = new ArrayList<>();
        allBuckets.addAll(englishTermsAgg.getBuckets());
        allBuckets.addAll(arabicTermsAgg.getBuckets());
        allBuckets = allBuckets.stream()
                .filter(x -> !this.inputTerms.contains(StringUtils.stripAccents(x.getKeyAsString().trim().toLowerCase())))
                .collect(Collectors.toList());
        Collections.sort(allBuckets, new SignifcantTermsBucketComparator());
        List<SignificantTerms.Bucket> firstSizeElementsList = allBuckets.stream().limit(this.size).collect(Collectors.toList());
        for (SignificantTerms.Bucket bucket : firstSizeElementsList) {
            if (bucket.getSignificanceScore() < MINIMUM_SCORE) {
                break;
            } else {
                result.put(bucket.getKeyAsString());
            }
        }
        return result;
    }

    /**
     * Comparator for the Signifcant Terms Bucket.
     */
    public class SignifcantTermsBucketComparator implements Comparator<SignificantTerms.Bucket> {
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




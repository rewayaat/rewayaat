package com.rewayaat.core;

import com.rewayaat.config.ESClientProvider;

import java.io.IOException;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.bucket.terms.IncludeExclude;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.json.JSONArray;

/**
 * Represents a collections of the most frequently used terms in the database.
 */
public class DatabaseTopTerms {

    private final String language;
    private final int size;
    private String prefix = "";

    public DatabaseTopTerms(int size, String prefix) {
        this.size = size;
        this.prefix = prefix;
        if (new RewayaatTerm(prefix).isArabic()) {
            this.language = "arabic";
        } else {
            this.language = "english";
        }
    }

    public JSONArray terms() throws IOException {
        JSONArray result = new JSONArray();
        try (RestHighLevelClient client = new ESClientProvider().client()) {
            SearchRequest searchRequest = new SearchRequest(ESClientProvider.INDEX);
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            searchSourceBuilder.query(QueryBuilders.matchAllQuery());
            searchSourceBuilder.aggregation(AggregationBuilders.terms("topterms")
                    .field(this.language)
                    .size(this.size)
                    .order(BucketOrder.count(false))
                    .includeExclude(new IncludeExclude(this.prefix + ".*", null)));
            searchRequest.source(searchSourceBuilder);

            SearchResponse resp = client.search(searchRequest, RequestOptions.DEFAULT);

            Terms topterms = resp.getAggregations().get("topterms");
            for (Terms.Bucket bucket : topterms.getBuckets()) {
                String topTerm = bucket.getKey().toString();
                result.put(topTerm);
            }
            return result;
        }
    }
}

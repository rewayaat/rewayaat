package com.rewayaat.core;

import com.rewayaat.config.ESClientProvider;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.bucket.terms.IncludeExclude;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.json.JSONArray;

import java.net.UnknownHostException;

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

    public JSONArray terms() throws UnknownHostException {
        JSONArray result = new JSONArray();
        SearchResponse resp = ESClientProvider.instance().getClient().prepareSearch(ESClientProvider.INDEX)
                                              .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                                              .addAggregation(AggregationBuilders
                        .terms("topterms")
                        .field(this.language)
                        .size(this.size)
                        .order(BucketOrder.count(false))
                        .includeExclude(new IncludeExclude(this.prefix + ".*", null)))
                                              .get();
        Terms topterms = resp.getAggregations().get("topterms");
        for (Terms.Bucket bucket : topterms.getBuckets()) {
            String topTerm = bucket.getKey().toString();
            result.put(topTerm);
        }
        return result;
    }
}

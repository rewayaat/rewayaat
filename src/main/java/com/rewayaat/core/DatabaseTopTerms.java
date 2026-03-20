package com.rewayaat.core;

import com.rewayaat.config.ESClientProvider;

import java.io.IOException;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.util.NamedValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
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
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Void> resp = provider.client().search(s -> s
                    .index(ESClientProvider.INDEX)
                    .query(q -> q.matchAll(m -> m))
                    .aggregations("topterms", a -> a
                            .terms(t -> t
                                    .field(this.language)
                                    .size(this.size)
                                    .order(NamedValue.of("_count", SortOrder.Desc))
                                    .include(i -> i.regexp(this.prefix + ".*")))),
                    Void.class);

            Aggregate aggregate = resp.aggregations().get("topterms");
            if (aggregate != null) {
                StringTermsAggregate topterms = aggregate.sterms();
                for (StringTermsBucket bucket : topterms.buckets().array()) {
                    String topTerm = bucket.key().stringValue();
                    result.put(topTerm);
                }
            }
            return result;
        }
    }
}

package com.rewayaat.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.json.JSONArray;

/**
 * Represents a collections of the most frequently used terms in the database.
 */
public class DatabaseTopTerms {

    private final String language;
    private final int size;
    private String prefix = "";
    private final ObjectMapper mapper = new ObjectMapper();

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
        Set<String> uniqueTerms = new LinkedHashSet<>();

        try (ESClientProvider provider = new ESClientProvider()) {
            // Use prefix query instead of aggregation on text field
            Query prefixQuery = Query.of(q -> q
                .prefix(p -> p
                    .field(this.language)
                    .value(this.prefix)
                ));

            SearchResponse<Map> response = provider.client().search(s -> s
                    .index(ESClientProvider.INDEX)
                    .query(prefixQuery)
                    .source(HadithSourceFilter.searchSource())
                    .size(this.size * 10), // Fetch more docs to find unique terms
                    Map.class);

            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source != null) {
                    Object fieldValue = source.get(this.language);
                    if (fieldValue != null) {
                        String textValue = fieldValue.toString();
                        // Split by common delimiters to find individual terms
                        String[] terms = textValue.split("[\\s\\p{Punct}–—ـ،ًٌَُِّ]+");
                        for (String term : terms) {
                            String trimmed = term.trim();
                            if (!trimmed.isEmpty() &&
                                trimmed.toLowerCase().startsWith(prefix.toLowerCase()) &&
                                trimmed.length() >= prefix.length() + 2 &&
                                uniqueTerms.size() < this.size) {
                                uniqueTerms.add(trimmed);
                            }
                        }
                    }
                }
                if (uniqueTerms.size() >= this.size) {
                    break;
                }
            }

            for (String term : uniqueTerms) {
                result.put(term);
            }
            return result;
        }
    }
}

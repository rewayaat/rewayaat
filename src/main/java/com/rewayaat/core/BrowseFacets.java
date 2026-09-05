package com.rewayaat.core;

import com.rewayaat.config.ESClientProvider;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.util.NamedValue;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class BrowseFacets {

    private static final int MAX_BUCKETS = 500;
    private static final String BOOK_FIELD = "book";
    private static final Map<String, Integer> FACET_ORDER = new LinkedHashMap<>();

    static {
        FACET_ORDER.put("volume", 0);
        FACET_ORDER.put("part", 1);
        FACET_ORDER.put("section", 2);
        FACET_ORDER.put("chapter", 3);
    }

    /**
     * All books with their narration counts, and the slug their page answers at.
     *
     * <p>The slug comes from {@link Slugs#slugify} rather than being recomputed in
     * the browser: the home page's book cards link straight to /books/{slug}, and a second
     * slug implementation would drift from the one the routes are built on.
     */
    public JSONArray books() throws IOException {
        JSONArray books = termsAgg("book", null, null, null);
        for (int i = 0; i < books.length(); i++) {
            JSONObject book = books.getJSONObject(i);
            book.put("slug", Slugs.slugify(book.optString("name")));
        }
        return books;
    }

    /**
     * Returns the correct field name for aggregation.
     * For text fields with keyword subfields, returns field.keyword.
     * For keyword fields, returns the field name directly.
     */
    private String aggField(String fieldName) {
        // Fields that are text with keyword subfield
        if ("chapter".equals(fieldName)) {
            return fieldName + ".keyword";
        }
        // Fields that are already keyword type
        return fieldName;
    }

    public JSONObject facets(String book) throws IOException {
        return facets(book, null, null, null, null);
    }

    public JSONObject facets(String book, String volume, String part, String section, String chapter) throws IOException {
        JSONObject response = new JSONObject();
        response.put("book", book);

        Map<String, String> filters = new LinkedHashMap<>();
        if (volume != null && !volume.trim().isEmpty()) {
            filters.put("volume", volume.trim());
        }
        if (part != null && !part.trim().isEmpty()) {
            filters.put("part", part.trim());
        }
        if (section != null && !section.trim().isEmpty()) {
            filters.put("section", section.trim());
        }
        if (chapter != null && !chapter.trim().isEmpty()) {
            filters.put("chapter", chapter.trim());
        }

        JSONObject facets = new JSONObject();
        facets.put("section", termsAgg(aggField("section"), book, filters, "section"));
        facets.put("chapter", termsAgg(aggField("chapter"), book, filters, "chapter"));
        facets.put("volume", termsAgg(aggField("volume"), book, filters, "volume"));
        facets.put("part", termsAgg(aggField("part"), book, filters, "part"));

        response.put("facets", facets);
        response.put("primaryFacet", resolvePrimaryFacet(facets));
        return response;
    }

    private String resolvePrimaryFacet(JSONObject facets) {
        String[] order = new String[]{"section", "chapter", "volume", "part"};
        for (String key : order) {
            JSONArray items = facets.optJSONArray(key);
            if (items != null && items.length() > 0) {
                return key;
            }
        }
        return "section";
    }

    private JSONArray termsAgg(String field, String book, Map<String, String> filters, String excludeKey)
            throws IOException {
        JSONArray result = new JSONArray();
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Void> resp = provider.client().search(s -> {
                s.index(ESClientProvider.INDEX).size(0);
                if (book != null && !book.trim().isEmpty()) {
                    s.query(q -> q.bool(b -> {
                        b.filter(f -> f.term(t -> t.field(BOOK_FIELD).value(book)));
                        if (filters != null && !filters.isEmpty()) {
                            for (Map.Entry<String, String> entry : filters.entrySet()) {
                                String key = entry.getKey();
                                if (key == null || key.trim().isEmpty()) {
                                    continue;
                                }
                                if (excludeKey != null && excludeKey.equals(key)) {
                                    continue;
                                }
                                if (shouldSkipFilterForFacet(excludeKey, key)) {
                                    continue;
                                }
                                String value = entry.getValue();
                                if (value == null || value.trim().isEmpty()) {
                                    continue;
                                }
                                // Use correct field name for filtering (same as aggregation)
                                String filterField = aggField(key);
                                b.filter(f -> f.term(t -> t.field(filterField).value(value)));
                            }
                        }
                        return b;
                    }));
                } else {
                    s.query(q -> q.matchAll(m -> m));
                }
                s.aggregations("terms", a -> a.terms(t -> t
                        .field(field)
                        .size(MAX_BUCKETS)
                        .order(NamedValue.of("_key", SortOrder.Asc))));
                return s;
            }, Void.class);

            Aggregate aggregate = resp.aggregations().get("terms");
            if (aggregate != null) {
                StringTermsAggregate terms = aggregate.sterms();
                for (StringTermsBucket bucket : terms.buckets().array()) {
                    String key = bucket.key().stringValue();
                    if (key == null || key.trim().isEmpty()) {
                        continue;
                    }
                    JSONObject item = new JSONObject();
                    item.put("name", key);
                    item.put("count", bucket.docCount());
                    result.put(item);
                }
            }
            return result;
        }
    }

    private boolean shouldSkipFilterForFacet(String facetKey, String filterKey) {
        if (facetKey == null || filterKey == null) {
            return false;
        }
        Integer facetRank = FACET_ORDER.get(facetKey);
        Integer filterRank = FACET_ORDER.get(filterKey);
        if (facetRank == null || filterRank == null) {
            return false;
        }
        // When computing options for a facet, only keep higher-level filters.
        // Example: for "part", keep "volume"; for "volume", keep none.
        return filterRank >= facetRank;
    }
}

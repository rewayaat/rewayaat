package com.rewayaat.core;

import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.util.NamedValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QueryStringQueryResultTest {

    @Test
    void strictScopedSearchRetainsResidualKeywordAndBookFilter() throws Exception {
        SearchRequest request = buildSearchRequest("anger AND book:\"Nahj al-Balāgha\"", true);
        Query textQuery = textClause(request);
        Query filter = request.query().bool().filter().get(0);

        assertEquals("anger", textQuery.queryString().query());
        assertEquals(Operator.And, textQuery.queryString().defaultOperator());
        assertEquals("book", filter.term().field());
        assertEquals("Nahj al-Balāgha", filter.term().value().stringValue());
    }

    @Test
    void strictScopedSearchWithTwoFiltersRetainsResidualKeywordWithoutDanglingBoolean() throws Exception {
        SearchRequest request = buildSearchRequest("anger AND book:\"Al-Kāfi\" AND volume:\"1\"", true);
        Query textQuery = textClause(request);

        assertEquals("anger", textQuery.queryString().query());
        assertEquals(2, request.query().bool().filter().size());
        assertEquals("book", request.query().bool().filter().get(0).term().field());
        assertEquals("volume", request.query().bool().filter().get(1).term().field());
    }

    @Test
    void permissiveScopedSearchRetainsFlexibleKeywordAndBookFilter() throws Exception {
        SearchRequest request = buildSearchRequest("anger~ book:\"Nahj al-Balāgha\"", false);
        Query textQuery = textClause(request);
        Query filter = request.query().bool().filter().get(0);

        assertEquals("anger~", textQuery.queryString().query());
        assertEquals("book", filter.term().field());
        assertEquals("Nahj al-Balāgha", filter.term().value().stringValue());
    }

    @Test
    void pureScopedSearchBuildsFilterWithoutDroppingIntoWildcardQueryString() throws Exception {
        SearchRequest request = buildSearchRequest("book:\"Nahj al-Balāgha\"", true);

        assertNotNull(request.query().bool().filter());
        assertEquals(1, request.query().bool().filter().size());
        assertEquals("book", request.query().bool().filter().get(0).term().field());
        assertEquals("Nahj al-Balāgha", request.query().bool().filter().get(0).term().value().stringValue());
    }

    @Test
    void removeFieldScopesLeavesOnlyResidualTerms() throws Exception {
        Method method = QueryStringQueryResult.class.getDeclaredMethod("removeFieldScopes", String.class);
        method.setAccessible(true);

        assertEquals("anger", method.invoke(null, "anger AND book:\"Nahj al-Balāgha\""));
        assertEquals("anger AND prayer", method.invoke(null, "anger AND book:\"Nahj al-Balāgha\" AND prayer"));
        assertEquals("anger", method.invoke(null, "anger AND book:\"Al-Kāfi\" AND volume:\"1\""));
        assertEquals("", method.invoke(null, "book:\"Nahj al-Balāgha\""));
    }

    @Test
    void highlightQueryStringStripsFlexibleBoostsAndFuzzySyntax() throws Exception {
        Method method = QueryStringQueryResult.class.getDeclaredMethod("buildHighlightQueryString", String.class);
        method.setAccessible(true);

        assertEquals("غدير", method.invoke(null, "(غدير^6 OR غدير~)"));
        assertEquals("anger", method.invoke(null, "(anger^6 OR anger~) book:\"Nahj al-Balāgha\""));
    }


    /**
     * The free-text clause, wherever it sits in the bool.
     *
     * <p>The clause is a {@code must}: the metadata is reached through analysed
     * {@code .text} sub-fields that query_string's "*" expansion already covers, so there
     * is no second clause the text query has to sit beside as a {@code should}.
     */
    private static Query textClause(SearchRequest request) {
        for (Query q : request.query().bool().must()) {
            if (q.isQueryString()) {
                return q;
            }
        }
        throw new AssertionError("no query_string clause in the bool");
    }

    /**
     * The metadata has to be highlighted through its analysed sub-field, not its base name.
     *
     * <p>book, part, section, source, volume and publisher are mapped as keyword, so the
     * stored value is one token and highlighting the base field marks the whole of "The
     * Book of Commerce" for a search for "commerce". The .text sub-field indexes the value
     * word by word, which is what marks the word alone. Matching needs no clause of its
     * own - query_string's "*" expansion reaches a multi-field - so the field list here is
     * the only thing that has to name them.
     */
    @Test
    void theMetadataIsHighlightedThroughItsAnalysedSubField() throws Exception {
        Highlight highlight = buildHighlight("(commerce^6 OR commerce~)", false);

        List<String> fields = new ArrayList<>();
        for (NamedValue<HighlightField> f : highlight.fields()) {
            fields.add(f.name());
        }
        for (String expected : List.of("book.text", "volume.text", "part.text",
                "section.text", "source.text", "publisher.text")) {
            assertEquals(true, fields.contains(expected), expected + " is not highlighted");
            assertEquals(false, fields.contains(expected.replace(".text", "")),
                    expected.replace(".text", "") + " is highlighted on its keyword base, which marks the whole value");
        }
        assertEquals(true, fields.contains("english"), "the matn stopped being highlighted");
    }

    /**
     * The highlight query stays a plain query_string.
     *
     * <p>It reaches the metadata the same way the matching query does, so the two cannot
     * drift apart the way they did when only one of them knew about keyword fields.
     */
    @Test
    void theHighlightQueryIsThePlainTextQuery() throws Exception {
        Highlight highlight = buildHighlight("(commerce^6 OR commerce~)", false);

        assertEquals(true, highlight.highlightQuery().isQueryString());
        assertEquals("*", highlight.highlightQuery().queryString().defaultField());
    }

    private SearchRequest buildSearchRequest(String query, boolean strictMatchMode) throws Exception {
        QueryStringQueryResult result = newResult(query, strictMatchMode);
        Highlight highlight = invokeHighlightBuilder(result, query);

        Method buildMethod = QueryStringQueryResult.class.getDeclaredMethod("buildSearchRequest", String.class, Highlight.class);
        buildMethod.setAccessible(true);
        return (SearchRequest) buildMethod.invoke(result, query, highlight);
    }

    private Highlight buildHighlight(String query, boolean strictMatchMode) throws Exception {
        return invokeHighlightBuilder(newResult(query, strictMatchMode), query);
    }

    private Highlight invokeHighlightBuilder(QueryStringQueryResult result, String query) throws Exception {
        Method highlightMethod = QueryStringQueryResult.class.getDeclaredMethod("getHighlightBuilder", String.class);
        highlightMethod.setAccessible(true);
        return (Highlight) highlightMethod.invoke(result, query);
    }

    private QueryStringQueryResult newResult(String query, boolean strictMatchMode) {
        return new QueryStringQueryResult(
                query,
                0,
                20,
                Collections.emptyList(),
                strictMatchMode,
                0
        );
    }
}

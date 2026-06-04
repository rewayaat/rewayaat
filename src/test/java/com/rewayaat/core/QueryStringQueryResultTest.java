package com.rewayaat.core;

import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QueryStringQueryResultTest {

    @Test
    void strictScopedSearchRetainsResidualKeywordAndBookFilter() throws Exception {
        SearchRequest request = buildSearchRequest("anger AND book:\"Nahj al-Balāgha\"", true);
        Query boolMust = request.query().bool().must().get(0);
        Query filter = request.query().bool().filter().get(0);

        assertEquals("anger", boolMust.queryString().query());
        assertEquals(Operator.And, boolMust.queryString().defaultOperator());
        assertEquals("book", filter.term().field());
        assertEquals("Nahj al-Balāgha", filter.term().value().stringValue());
    }

    @Test
    void strictScopedSearchWithTwoFiltersRetainsResidualKeywordWithoutDanglingBoolean() throws Exception {
        SearchRequest request = buildSearchRequest("anger AND book:\"Al-Kāfi\" AND volume:\"1\"", true);
        Query boolMust = request.query().bool().must().get(0);

        assertEquals("anger", boolMust.queryString().query());
        assertEquals(2, request.query().bool().filter().size());
        assertEquals("book", request.query().bool().filter().get(0).term().field());
        assertEquals("volume", request.query().bool().filter().get(1).term().field());
    }

    @Test
    void permissiveScopedSearchRetainsFlexibleKeywordAndBookFilter() throws Exception {
        SearchRequest request = buildSearchRequest("anger~ book:\"Nahj al-Balāgha\"", false);
        Query boolMust = request.query().bool().must().get(0);
        Query filter = request.query().bool().filter().get(0);

        assertEquals("anger~", boolMust.queryString().query());
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

    private SearchRequest buildSearchRequest(String query, boolean strictMatchMode) throws Exception {
        QueryStringQueryResult result = new QueryStringQueryResult(
                query,
                0,
                20,
                Collections.emptyList(),
                strictMatchMode,
                0
        );
        Method highlightMethod = QueryStringQueryResult.class.getDeclaredMethod("getHighlightBuilder", String.class);
        highlightMethod.setAccessible(true);
        Highlight highlight = (Highlight) highlightMethod.invoke(result, query);

        Method buildMethod = QueryStringQueryResult.class.getDeclaredMethod("buildSearchRequest", String.class, Highlight.class);
        buildMethod.setAccessible(true);
        return (SearchRequest) buildMethod.invoke(result, query, highlight);
    }
}

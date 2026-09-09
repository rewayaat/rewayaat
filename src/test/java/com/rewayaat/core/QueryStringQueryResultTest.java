package com.rewayaat.core;

import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Highlight;
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
     * <p>It is a {@code should} rather than a {@code must} so that a narration matching
     * only on keyword metadata can come back on its own - see the metadata test below.
     * These three tests asserted {@code must().get(0)} and failed with an
     * IndexOutOfBounds when it moved, which is the right way for them to fail.
     */
    private static Query textClause(SearchRequest request) {
        for (Query q : request.query().bool().should()) {
            if (q.isQueryString()) {
                return q;
            }
        }
        throw new AssertionError("no query_string clause in the bool");
    }

    /**
     * A plain word has to reach book, volume, part, section and source.
     *
     * <p>Those are mapped as keyword, so the whole value is one token: the 1,062
     * narrations under "The Book of Commerce" were unreachable by a search for
     * "commerce" until these clauses existed. The wildcard has to be case-insensitive -
     * the stored value is capitalised and the query is not - and the bool needs
     * minimumShouldMatch, or the should clauses would only boost a set the text query
     * had already decided.
     */
    @Test
    void plainTermsReachTheKeywordMetadataFields() throws Exception {
        SearchRequest request = buildSearchRequest("(commerce^6 OR commerce~)", false);

        List<String> wildcarded = new ArrayList<>();
        for (Query q : request.query().bool().should()) {
            if (q.isWildcard()) {
                assertEquals("*commerce*", q.wildcard().value());
                assertEquals(Boolean.TRUE, q.wildcard().caseInsensitive());
                wildcarded.add(q.wildcard().field());
            }
        }
        assertEquals(List.of("book", "volume", "part", "section", "source"), wildcarded);
        assertEquals("1", request.query().bool().minimumShouldMatch());
    }

    /** Two-letter noise would wildcard-match most of the metadata; it is not asked. */
    @Test
    void tooShortATermIsNotAskedOfTheMetadata() throws Exception {
        SearchRequest request = buildSearchRequest("(of^6 OR of~)", false);

        for (Query q : request.query().bool().should()) {
            assertEquals(false, q.isWildcard(), "a two-letter term reached the metadata");
        }
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

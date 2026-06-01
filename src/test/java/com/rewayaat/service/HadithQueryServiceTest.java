package com.rewayaat.service;

import com.rewayaat.core.QueryMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import co.elastic.clients.elasticsearch._types.SortOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HadithQueryServiceTest {

    @Test
    void enhanceQuery_appendsFuzzyByDefault() {
        HadithQueryService service = new HadithQueryService();
        assertEquals("foo~ bar~", service.enhanceQuery("foo bar", QueryMode.SEARCH, false));
    }

    @Test
    void enhanceQuery_usesAndForLookupMode() {
        HadithQueryService service = new HadithQueryService();
        assertEquals("foo~ AND bar~", service.enhanceQuery("foo bar", QueryMode.LOOKUP, false));
    }

    @Test
    void enhanceQuery_keepsQuotedPhrasesUntouched() {
        HadithQueryService service = new HadithQueryService();
        assertEquals("\"foo bar\"", service.enhanceQuery("\"foo bar\"", QueryMode.SEARCH, false));
    }

    @Test
    void enhanceQuery_respectsFieldQueries() {
        HadithQueryService service = new HadithQueryService();
        assertEquals("title:foo bar~", service.enhanceQuery("title:foo bar", QueryMode.SEARCH, false));
    }

    @Test
    void enhanceQuery_mapsIdFieldAliasToElasticsearchId() {
        HadithQueryService service = new HadithQueryService();
        assertEquals("_id:\"abc123\"", service.enhanceQuery("id:\"abc123\"", QueryMode.SEARCH, true));
    }

    @Test
    void setupSortBuilders_defaultsToScoreSort() {
        HadithQueryService service = new HadithQueryService();
        List<SortOptions> sorts = service.setupSortBuilders("");
        assertEquals(1, sorts.size());
        assertEquals(SortOptions.Kind.Score, sorts.get(0)._kind());
    }

    @Test
    void setupSortBuilders_buildsFieldSorts() {
        HadithQueryService service = new HadithQueryService();
        List<SortOptions> sorts = service.setupSortBuilders("book:asc");
        assertEquals(1, sorts.size());
        assertEquals(SortOptions.Kind.Field, sorts.get(0)._kind());
        assertEquals("book", sorts.get(0).field().field());
    }

    @Test
    void setupSortBuilders_buildsNumberSortScript() {
        HadithQueryService service = new HadithQueryService();
        List<SortOptions> sorts = service.setupSortBuilders("number:desc");
        assertEquals(1, sorts.size());
        assertEquals(SortOptions.Kind.Script, sorts.get(0)._kind());
        assertTrue(sorts.get(0).script().script().source().scriptString().contains("doc['number'].value"));
    }

    @Test
    void isProbablyArabic_detectsArabicCharacters() {
        HadithQueryService service = new HadithQueryService();
        assertTrue(service.isProbablyArabic("\u0633\u0644\u0627\u0645"));
        assertFalse(service.isProbablyArabic("hello"));
    }
}

package com.rewayaat.service;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.HadithObjectCollection;
import com.rewayaat.core.QueryMode;
import com.rewayaat.core.QueryStringQueryResult;
import com.rewayaat.core.data.NarratorDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Service for looking up narrator documents and searching hadiths by narrator.
 */
@Service
public class NarratorService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NarratorService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String NARRATOR_INDEX = resolveNarratorIndex();

    @Autowired
    private HadithQueryService hadithQueryService;

    /**
     * Get a single narrator by ID from the narrators index.
     */
    public NarratorDocument getNarrator(String narratorId) {
        try (ESClientProvider provider = new ESClientProvider()) {
            GetResponse<Map> response = provider.client().get(
                    g -> g.index(NARRATOR_INDEX).id(narratorId), Map.class);
            if (!response.found() || response.source() == null) {
                return null;
            }
            Map<String, Object> map = response.source();
            map.put("_id", narratorId);
            return JSON.convertValue(map, NarratorDocument.class);
        } catch (Exception e) {
            LOGGER.error("Error fetching narrator {}: {}", narratorId, e.getMessage());
            return null;
        }
    }

    /**
     * Search hadiths narrated by a specific narrator using all their name variants.
     * Searches the arabic and english fields against all aliases, titles, and kunyahs.
     */
    public HadithObjectCollection searchHadithsByNarrator(String narratorId, int page, int perPage) throws Exception {
        NarratorDocument narrator = getNarrator(narratorId);
        if (narrator == null) {
            return emptyCollection();
        }

        // Build a query that searches for any of the narrator's name variants
        // in the hadith chain/text fields
        List<String> searchTerms = collectSearchTerms(narrator);
        if (searchTerms.isEmpty()) {
            return emptyCollection();
        }

        // Use a bool/should query across arabic and english fields
        String queryString = buildNarratorQueryString(searchTerms);
        LOGGER.debug("Narrator search query for {}: {}", narratorId, queryString);

        List<SortOptions> sortBuilders = List.of(
                SortOptions.of(s -> s.score(sc -> sc.order(SortOrder.Desc))));

        return new QueryStringQueryResult(
                queryString,
                page,
                perPage,
                sortBuilders,
                true,
                100,
                null,
                null).result();
    }

    /**
     * Collect all searchable name variants for a narrator.
     */
    private List<String> collectSearchTerms(NarratorDocument narrator) {
        List<String> terms = new ArrayList<>();

        addIfPresent(terms, narrator.getPrimaryArabicName());
        addIfPresent(terms, narrator.getPrimaryEnglishName());
        addIfPresent(terms, narrator.getKunyahArabic());
        addIfPresent(terms, narrator.getKunyahEnglish());

        if (narrator.getArabicAliases() != null) {
            narrator.getArabicAliases().forEach(a -> addIfPresent(terms, a));
        }
        if (narrator.getEnglishAliases() != null) {
            narrator.getEnglishAliases().forEach(a -> addIfPresent(terms, a));
        }
        if (narrator.getTitles() != null) {
            narrator.getTitles().forEach(a -> addIfPresent(terms, a));
        }
        if (narrator.getScholarlyNames() != null) {
            narrator.getScholarlyNames().forEach(a -> addIfPresent(terms, a));
        }

        return terms;
    }

    private void addIfPresent(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value.trim());
        }
    }

    /**
     * Build a Lucene query string that searches for any of the narrator's name variants.
     * Arabic terms search the arabic field, English terms search the english field.
     * Uses quoted phrases for exact matching of multi-word names.
     */
    private String buildNarratorQueryString(List<String> terms) {
        List<String> clauses = new ArrayList<>();
        for (String term : terms) {
            String escaped = term.replace("\"", "\\\"");
            String field = isProbablyArabic(term) ? "arabic" : "english";
            clauses.add(field + ":\"" + escaped + "\"");
        }
        return String.join(" OR ", clauses);
    }

    private boolean isProbablyArabic(String s) {
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            if (c >= 0x0600 && c <= 0x06E0) {
                return true;
            }
            i += Character.charCount(c);
        }
        return false;
    }

    private HadithObjectCollection emptyCollection() {
        return new HadithObjectCollection(Collections.emptyList(), 0L, 0L);
    }

    private static String resolveNarratorIndex() {
        String index = System.getProperty("narrator.index");
        if (index != null && !index.isEmpty()) {
            return index;
        }
        index = System.getenv("NARRATOR_INDEX");
        if (index != null && !index.isEmpty()) {
            return index;
        }
        return "rewayaat_narrators";
    }
}

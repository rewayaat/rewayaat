package com.rewayaat.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.data.HadithObject;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Represents a processed query for narrations.
 */
public class QueryStringQueryResult implements RewayaatQueryResult {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryStringQueryResult.class);
    // Do not change without considering impact on front-end
    private int pageSize;
    private String query;
    private int page;
    private List<SortBuilder> sortBuilders = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public QueryStringQueryResult(String query, int page, int perPage,
                                  List<SortBuilder> sortBuilders) {
        this.query = query;
        this.page = page;
        this.pageSize = perPage;
        this.sortBuilders = sortBuilders;
    }

    @Override
    public HadithObjectCollection result() throws Exception {
        List<HadithObject> hadithes = new ArrayList<HadithObject>();
        HighlightBuilder highlightBuilder = getHighlightBuilder(this.query);
        SearchRequestBuilder sRB = buildSearchQueryModeRequest(query, highlightBuilder);
        SearchResponse resp = sRB.execute().get();
        SearchHit[] results = resp.getHits().getHits();
        LOGGER.info("Current results: " + results.length);
        for (SearchHit hit : results) {
            processHit(hadithes, hit);
        }
        // Return result set with duplicates removed.
        return new HadithObjectCollection(
            new LinkedList<>(new LinkedHashSet<>(hadithes)), resp.getHits().getTotalHits().value);
    }

    private void processHit(List<HadithObject> hadithes, SearchHit hit) {
        Map<String, Object> result = hit.getSourceAsMap();
        result.put("_id", hit.getId());
        for (Entry<String, HighlightField> entry : hit.getHighlightFields().entrySet()) {
            result.put(entry.getKey(), entry.getValue().fragments()[0].toString());
        }
        hadithes.add(mapper.convertValue(result, HadithObject.class));
    }

    private SearchRequestBuilder buildSearchQueryModeRequest(
        String fuzziedQuery, HighlightBuilder highlightBuilder) throws UnknownHostException {
        SearchRequestBuilder sRB =
            ESClientProvider.instance().getClient().prepareSearch(ESClientProvider.INDEX)
                            .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                            .setQuery(QueryBuilders.boolQuery().should(QueryBuilders.queryStringQuery(fuzziedQuery)))
                            .highlighter(highlightBuilder).setFrom(page * this.pageSize).setSize(this.pageSize).setExplain(true);
        for (SortBuilder sort : this.sortBuilders) {
            sRB.addSort(sort);
        }
        return sRB;
    }

    private HighlightBuilder getHighlightBuilder(String fuzziedQuery) {
        HighlightBuilder highlightBuilder =
            new HighlightBuilder().field("english").field("allFields").field("notes").field("arabic")
                .field("book").field("section").field("part").field("chapter").field("publisher").field("source")
                .field("volume").postTags("</span>").preTags("<span class=\"highlight\">")
                .highlightQuery(QueryBuilders.queryStringQuery(fuzziedQuery).defaultField("*"))
                .highlightQuery(QueryBuilders.queryStringQuery(query).defaultField("*").analyzer(
                    "search_analyzer")).numOfFragments(0);
        return highlightBuilder;
    }
}

package com.rewayaat.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.data.HadithObject;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private String userQuery;
    private int page;
    private final ObjectMapper mapper = new ObjectMapper();

    public QueryStringQueryResult(String userQuery, int page, int perPage) {
        this.userQuery = userQuery;
        this.page = page;
        this.pageSize = perPage;
    }

    @Override
    public HadithObjectCollection result() throws Exception {

        List<HadithObject> hadithes = new ArrayList<HadithObject>();
        String fuzziedQuery = new RewayaatQuery(userQuery).query();

        HighlightBuilder highlightBuilder =
            new HighlightBuilder().field("english").field("allFields").field("notes").field("arabic")
                .field("book").field("section").field("part").field("chapter").field("publisher").field("source")
                .field("volume").postTags("</span>").preTags("<span class=\"highlight\">")
                .highlightQuery(QueryBuilders.queryStringQuery(fuzziedQuery).defaultField("*"))
                .highlightQuery(QueryBuilders.queryStringQuery(userQuery).defaultField("*").analyzer(
                    "search_analyzer"))
                .numOfFragments(0);

        SearchResponse resp = ESClientProvider.instance().getClient().prepareSearch(ESClientProvider.INDEX)
                                              .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                                              .setQuery(QueryBuilders.boolQuery().should(QueryBuilders.queryStringQuery(fuzziedQuery))
                        .should(QueryBuilders.queryStringQuery(userQuery).analyzer("search_analyzer").boost(10)))
                                              .highlighter(highlightBuilder).setFrom(page * this.pageSize).setSize(this.pageSize).setExplain(true)
                                              .addSort("_score", SortOrder.DESC).execute().get();

        SearchHit[] results = resp.getHits().getHits();
        System.out.println("Current results: " + results.length);
        for (SearchHit hit : results) {
            System.out.println("------------------------------");
            Map<String, Object> result = hit.getSourceAsMap();
            result.put("_id", hit.getId());
            for (Entry<String, HighlightField> entry : hit.getHighlightFields().entrySet()) {
                // Add the highlighted fragment if it is not a Qur'anic verse.
                // Front-end will take care of highlighting Qur'anic verses.
                if (!entry.getValue().fragments()[0].toString().matches(".*[0-114]:[0-300].*")) {
                    result.put(entry.getKey(), entry.getValue().fragments()[0].toString());
                }
            }
            hadithes.add(mapper.convertValue(result, HadithObject.class));
            System.out.println(result);
        }

        // remove duplicates if found...
        for (int i = 0; i < hadithes.size(); i++) {
            for (int j = 0; j < hadithes.size(); j++) {
                if (i != j) {
                    if (hadithes.get(i).equals(hadithes.get(j))) {
                        System.out.println("Found duplicate! deleting...");
                        System.out.println(hadithes.get(i).toString());
                        try {
                            Map duplicatedHadith = mapper.convertValue(hadithes.get(i), Map.class);
                            ESClientProvider.instance().getClient().prepareDelete(ESClientProvider.INDEX, "_doc", (String) duplicatedHadith.get("_id")).get();
                        } catch (Exception e) {
                            LOGGER.error("Unable to delete duplicated hadith: " + hadithes.get(i).toString(), e);
                        }
                    }
                }
            }
        }

        // Return result set with duplicates removed.
        return new HadithObjectCollection(new LinkedList<>(new LinkedHashSet<>(hadithes)),
                                          resp.getHits().getTotalHits().value);
    }
}

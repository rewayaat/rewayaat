package com.rewayaat.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.RewayaatLogger;
import com.rewayaat.config.ClientProvider;
import com.rewayaat.core.data.HadithObject;
import org.apache.log4j.spi.LoggerFactory;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Represents a processed query for narrations.
 */
public class QueryStringQueryResult implements RewayaatQueryResult {

    private static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(QueryStringQueryResult.class.getName(), new LoggerFactory() {
        @Override
        public org.apache.log4j.Logger makeNewLoggerInstance(String name) {
            return new RewayaatLogger(name);
        }
    });

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

        HighlightBuilder highlightBuilder = new HighlightBuilder().field("english").field("all").field("notes").field("arabic")
                .field("book").field("section").field("part").field("chapter").field("publisher").field("source")
                .field("volume").postTags("</span>").preTags("<span class=\"highlight\">")
                .highlightQuery(QueryBuilders.queryStringQuery(userQuery).useAllFields(true))
                .highlightQuery(QueryBuilders.queryStringQuery(fuzziedQuery).useAllFields(true))
                .numOfFragments(0);

        SearchResponse resp = ClientProvider.instance().getClient().prepareSearch(ClientProvider.INDEX)
                .setTypes(ClientProvider.TYPE).setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(QueryBuilders.boolQuery().must(QueryBuilders.queryStringQuery(fuzziedQuery))
                        .should(QueryBuilders.queryStringQuery(userQuery).boost(100)))
                .highlighter(highlightBuilder).setFrom(page * this.pageSize).setSize(this.pageSize).setExplain(true)
                .addSort("_score", SortOrder.DESC).execute().get();

        SearchHit[] results = resp.getHits().getHits();
        System.out.println("Current results: " + results.length);
        for (SearchHit hit : results) {
            System.out.println("------------------------------");
            Map<String, Object> result = hit.getSource();
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
                            ClientProvider.instance().getClient().prepareDelete(ClientProvider.INDEX, ClientProvider.TYPE, (String) duplicatedHadith.get("_id")).get();
                        } catch (Exception e) {
                            log.error("Unable to delete duplicated hadith: " + hadithes.get(i).toString(), e);
                        }
                    }
                }
            }
        }

        // Return result set with duplicates removed.
        return new HadithObjectCollection(new LinkedList<>(new HashSet<>(hadithes)), resp.getHits().getTotalHits());
    }
}

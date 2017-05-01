package com.rewayaat.web.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.web.config.ClientProvider;
import com.rewayaat.web.data.hadith.HadithObject;

/**
 * Represents a processed query for narrations.
 */
public class QueryStringQueryResult implements RewayaatQueryResult {

	public static int PAGE_SIZE = 10;
	private String userQuery;
	private int page;
	final ObjectMapper mapper = new ObjectMapper();

	public QueryStringQueryResult(String userQuery, int page) {
		this.userQuery = userQuery;
		this.page = page;
	}

	public List<HadithObject> result() throws Exception {
		List<HadithObject> hadithes = new ArrayList<HadithObject>();

		HighlightBuilder highlightBuilder = new HighlightBuilder().field("english").field("notes").field("arabic")
				.postTags("</span>")
				.preTags("<span class=\"highlight\">").highlightQuery(QueryBuilders.queryStringQuery(userQuery)
						.field("english").field("book").field("edition").field("notes").field("arabic").field("tags"))
				.numOfFragments(0);

		SearchResponse resp = ClientProvider.instance().getClient().prepareSearch(ClientProvider.INDEX)
				.setTypes(ClientProvider.TYPE).setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
				.setQuery(QueryBuilders.queryStringQuery(userQuery))
				.highlighter(highlightBuilder).setFrom(page * PAGE_SIZE).setSize(PAGE_SIZE)
				.setExplain(true).execute().get();

		SearchHit[] results = resp.getHits().getHits();
		System.out.println("Current results: " + results.length);
		for (SearchHit hit : results) {
			System.out.println("------------------------------");
			Map<String, Object> result = hit.getSource();
			result.put("id", hit.getId());
			for (Entry<String, HighlightField> entry : hit.getHighlightFields().entrySet()) {
				result.put(entry.getKey(), entry.getValue().fragments()[0].toString());
			}
			hadithes.add(mapper.convertValue(result, HadithObject.class));
			System.out.println(result);
		}
		return hadithes;
	}
}

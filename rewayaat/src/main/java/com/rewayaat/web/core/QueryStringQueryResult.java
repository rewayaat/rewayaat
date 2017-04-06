package com.rewayaat.web.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.highlight.HighlightBuilder;
import org.elasticsearch.search.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.web.data.hadith.HadithObject;

/**
 * Represents a processed query for narrations.
 */
public class QueryStringQueryResult implements RewayaatQueryResult {

	public static int PAGE_SIZE = 20;
	private String userQuery;
	private int page;
	private ElasticsearchTemplate esT;

	public QueryStringQueryResult(String userQuery, int page, ElasticsearchTemplate esT) {
		this.userQuery = userQuery;
		this.page = page;
		this.esT = esT;
	}

	public List<HadithObject> result() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		List<HadithObject> hadithes = new ArrayList<HadithObject>();
		SearchResponse response = esT.getClient().prepareSearch("rewayaattest").setTypes("narrationstest")
				.setQuery(QueryBuilders.queryStringQuery(userQuery)).setSearchType(SearchType.QUERY_AND_FETCH)
				.setHighlighterPreTags("<highlight>").setHighlighterPostTags("</highlight>")
				.setHighlighterQuery(QueryBuilders.queryStringQuery(userQuery))
				.setFrom(page * PAGE_SIZE).setSize(PAGE_SIZE).execute().actionGet();

		for (SearchHit hit : response.getHits()) {
			HadithObject obj = mapper.readValue(hit.getSourceAsString(), HadithObject.class);
			hit.highlightFields();
			hadithes.add(obj);
		}
		return hadithes;
	}
}

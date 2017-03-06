package com.rewayaat.web.core;

import java.util.List;

import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.SearchQuery;

import com.rewayaat.web.data.hadith.HadithInfo;
import com.rewayaat.web.data.hadith.HadithRepository;

/**
 * A processed query for hadith.
 */
public class QueryResult {

    public static int PAGE_SIZE = 20;
    private String userQuery;
    private HadithRepository hadithRepo;
    private int page;

    public QueryResult(String userQuery, HadithRepository hadithRepo, int page) {
        this.userQuery = userQuery;
        this.hadithRepo = hadithRepo;
        this.page = page;
    }

    public List<HadithInfo> result() {
        SearchQuery searchQuery = new NativeSearchQueryBuilder()
                .withQuery(new QueryStringQueryBuilder(userQuery))
                .withPageable(new PageRequest(page, PAGE_SIZE)).build();
        Page<HadithInfo> page = hadithRepo.search(searchQuery);
        return page.getContent();
    }
}

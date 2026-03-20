package com.rewayaat.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.data.HadithObject;
import com.rewayaat.tools.TopicTaxonomySupport;

import co.elastic.clients.elasticsearch._types.SearchType;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.util.NamedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents a processed query for narrations.
 */
public class QueryStringQueryResult implements RewayaatQueryResult {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryStringQueryResult.class);
    private static final List<TopicTaxonomySupport.TopicTaxonomyEntry> TAXONOMY = loadTaxonomy();
    private static final Map<String, List<TopicTaxonomySupport.TopicTaxonomyEntry>> TAXONOMY_CHILDREN =
            TopicTaxonomySupport.childrenByParent(TAXONOMY);
    // Do not change without considering impact on front-end
    private int pageSize;
    private String query;
    private int page;
    private List<SortOptions> sortBuilders = new ArrayList<>();
    private boolean strictMatchMode;
    private int maxResultWindow;
    private List<String> topicTags = Collections.emptyList();
    private List<String> topicTagsAny = Collections.emptyList();
    private final ObjectMapper mapper = new ObjectMapper();

    public QueryStringQueryResult(String query, int page, int perPage,
            List<SortOptions> sortBuilders, boolean strictMatchMode, int maxResultWindow) {
        this(query, page, perPage, sortBuilders, strictMatchMode, maxResultWindow, Collections.emptyList(),
                Collections.emptyList());
    }

    public QueryStringQueryResult(String query, int page, int perPage,
            List<SortOptions> sortBuilders, boolean strictMatchMode, int maxResultWindow,
            List<String> topicTags, List<String> topicTagsAny) {
        this.query = query;
        this.page = page;
        this.pageSize = perPage;
        this.sortBuilders = sortBuilders;
        this.strictMatchMode = strictMatchMode;
        this.maxResultWindow = maxResultWindow;
        this.topicTags = sanitizeTags(topicTags);
        this.topicTagsAny = sanitizeTags(topicTagsAny);
    }

    @Override
    public HadithObjectCollection result() throws Exception {
        List<HadithObject> hadithes = new ArrayList<HadithObject>();
        Highlight highlightBuilder = getHighlightBuilder(this.query);
        SearchRequest searchRequest = buildSearchRequest(query, highlightBuilder);
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> resp = provider.client().search(searchRequest, Map.class);
            List<Hit<Map>> results = resp.hits().hits();
            LOGGER.debug("Query returned {} results", results.size());
            for (Hit<Map> hit : results) {
                processHit(hadithes, hit);
            }
            long totalHits = resp.hits().total() == null ? hadithes.size() : resp.hits().total().value();
            if (maxResultWindow > 0) {
                totalHits = Math.min(totalHits, maxResultWindow);
            }
            HadithObjectCollection collection = new HadithObjectCollection(
                    new LinkedList<>(new LinkedHashSet<>(hadithes)), totalHits);
            collection.setTopicTagFacets(extractTopicTagFacets(resp));
            return collection;
        }
    }

    private void processHit(List<HadithObject> hadithes, Hit<Map> hit) {
        Map<String, Object> source = hit.source();
        if (source == null) {
            return;
        }
        Map<String, Object> result = new HashMap<>(source);
        result.put("_id", hit.id());
        Map<String, List<String>> highlights = hit.highlight();
        if (highlights != null) {
            for (Entry<String, List<String>> entry : highlights.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    result.put(entry.getKey(), entry.getValue().get(0));
                }
            }
        }
        HadithDisplaySegmenter.enrich(result);
        hadithes.add(mapper.convertValue(result, HadithObject.class));
    }

    private SearchRequest buildSearchRequest(
            String fuzziedQuery, Highlight highlightBuilder) throws UnknownHostException {
        int from = Math.max(0, page * this.pageSize);
        int size = Math.max(0, this.pageSize);
        if (maxResultWindow > 0) {
            if (from >= maxResultWindow) {
                size = 0;
            } else {
                size = Math.min(size, maxResultWindow - from);
            }
        }
        // Use match_all for empty queries to avoid search_phase_execution_exception
        String finalQuery = (fuzziedQuery == null || fuzziedQuery.trim().isEmpty()) ? "*" : fuzziedQuery.trim();

        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(ESClientProvider.INDEX)
                .searchType(SearchType.DfsQueryThenFetch)
                .query(q -> q.bool(b -> {
                    b.must(s -> s.queryString(qs -> {
                        qs.query(finalQuery);
                        if (strictMatchMode) {
                            qs.defaultOperator(Operator.And);
                        }
                        return qs;
                    }));
                    for (String topicTag : topicTags) {
                        List<String> expanded = expandSelectedTag(topicTag);
                        if (expanded.size() == 1) {
                            b.filter(f -> f.term(t -> t.field("topic_tags").value(expanded.get(0))));
                        } else if (!expanded.isEmpty()) {
                            b.filter(f -> f.terms(t -> t.field("topic_tags")
                                    .terms(tv -> tv.value(expanded.stream()
                                            .map(FieldValue::of)
                                            .collect(Collectors.toList())))));
                        }
                    }
                    if (!topicTagsAny.isEmpty()) {
                        List<String> expandedAny = topicTagsAny.stream()
                                .flatMap(tag -> expandSelectedTag(tag).stream())
                                .distinct()
                                .collect(Collectors.toList());
                        b.filter(f -> f.terms(t -> t.field("topic_tags")
                                .terms(tv -> tv.value(expandedAny.stream()
                                        .map(FieldValue::of)
                                        .collect(Collectors.toList())))));
                    }
                    return b;
                }))
                .highlight(highlightBuilder)
                .from(from)
                .size(size)
                .aggregations("topic_tag_counts",
                        a -> a.terms(t -> t.field("topic_tags").size(200)));

        for (SortOptions sort : this.sortBuilders) {
            builder.sort(sort);
        }
        return builder.build();
    }

    private Highlight getHighlightBuilder(String fuzziedQuery) {
        Highlight.Builder highlightBuilder = new Highlight.Builder()
                .fields(
                        NamedValue.of("english", new HighlightField.Builder().build()),
                        NamedValue.of("allFields", new HighlightField.Builder().build()),
                        NamedValue.of("notes", new HighlightField.Builder().build()),
                        NamedValue.of("arabic", new HighlightField.Builder().build()),
                        NamedValue.of("book", new HighlightField.Builder().build()),
                        NamedValue.of("section", new HighlightField.Builder().build()),
                        NamedValue.of("part", new HighlightField.Builder().build()),
                        NamedValue.of("chapter", new HighlightField.Builder().build()),
                        NamedValue.of("publisher", new HighlightField.Builder().build()),
                        NamedValue.of("source", new HighlightField.Builder().build()),
                        NamedValue.of("volume", new HighlightField.Builder().build()))
                .postTags("</span>")
                .preTags("<span class=\"highlight\">")
                .highlightQuery(q -> {
                    String highlightQuery = (query == null || query.trim().isEmpty()) ? "*" : query.trim();
                    return q.queryString(qs -> {
                        qs.query(highlightQuery).defaultField("*").analyzer("search_analyzer");
                        if (strictMatchMode) {
                            qs.defaultOperator(Operator.And);
                        }
                        return qs;
                    });
                })
                .numberOfFragments(0);
        return highlightBuilder.build();
    }

    private Map<String, Long> extractTopicTagFacets(SearchResponse<Map> response) {
        if (response == null || response.aggregations() == null || !response.aggregations().containsKey("topic_tag_counts")) {
            return new LinkedHashMap<>();
        }
        try {
            return response.aggregations()
                    .get("topic_tag_counts")
                    .sterms()
                    .buckets()
                    .array()
                    .stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(
                            bucket -> bucket.key().stringValue(),
                            bucket -> bucket.docCount(),
                            (left, right) -> left,
                            LinkedHashMap::new));
        } catch (Exception ex) {
            LOGGER.debug("Unable to extract topic tag facets from aggregation response.", ex);
            return new LinkedHashMap<>();
        }
    }

    private List<String> sanitizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private static List<String> expandSelectedTag(String tag) {
        String slug = TopicTaxonomySupport.normalizeSlug(tag);
        if (slug.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        expanded.add(slug);
        expanded.addAll(TopicTaxonomySupport.descendantsOf(slug, TAXONOMY_CHILDREN));
        return List.copyOf(expanded);
    }

    private static List<TopicTaxonomySupport.TopicTaxonomyEntry> loadTaxonomy() {
        try {
            return TopicTaxonomySupport.loadBundledTaxonomy();
        } catch (Exception ex) {
            LOGGER.warn("Unable to load taxonomy for hierarchical topic-tag filters.", ex);
            return List.of();
        }
    }
}

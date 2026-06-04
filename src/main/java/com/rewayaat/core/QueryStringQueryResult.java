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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Represents a processed query for narrations.
 */
public class QueryStringQueryResult implements RewayaatQueryResult {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryStringQueryResult.class);
    private static final Pattern BOOST_PATTERN = Pattern.compile("\\^(\\d+(?:\\.\\d+)?)");
    private static final Pattern FUZZY_SUFFIX_PATTERN = Pattern.compile("~\\d*");
    private static final Pattern BOOLEAN_OPERATOR_PATTERN = Pattern.compile("(?i)\\b(?:AND|OR|NOT)\\b");
    private static final Pattern HIGHLIGHT_TOKEN_PATTERN = Pattern.compile("\"[^\"]+\"|\\S+");
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
        boolean hasTopicTags = !topicTags.isEmpty() || !topicTagsAny.isEmpty();

        try (ESClientProvider provider = new ESClientProvider()) {
            // If topic tags are applied, first get the base count without topic tag filters
            long baseHits = 0;
            if (hasTopicTags) {
                SearchRequest baseRequest = buildBaseSearchRequest(query, highlightBuilder);
                SearchResponse<Map> baseResp = provider.client().search(baseRequest, Map.class);
                baseHits = baseResp.hits().total() == null ? 0 : baseResp.hits().total().value();
                if (maxResultWindow > 0) {
                    baseHits = Math.min(baseHits, maxResultWindow);
                }
            }

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
            // If no topic tags were applied, baseHits equals totalHits
            if (!hasTopicTags) {
                baseHits = totalHits;
            }

            HadithObjectCollection collection = new HadithObjectCollection(
                    new LinkedList<>(new LinkedHashSet<>(hadithes)), totalHits, baseHits);
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

        LOGGER.debug("buildSearchRequest: strictMatchMode={}, finalQuery={}", strictMatchMode, finalQuery);

        List<FieldScope> fieldScopes = parseFieldScopes(finalQuery);
        String residualQuery = removeFieldScopes(finalQuery);

        LOGGER.debug("buildSearchRequest: fieldScopes.size={}, residualQuery='{}'", fieldScopes.size(), residualQuery);

        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(ESClientProvider.INDEX)
                .searchType(SearchType.DfsQueryThenFetch)
                .query(q -> q.bool(b -> {
                    if (!residualQuery.isBlank()) {
                        b.must(s -> s.queryString(qs -> {
                            qs.query(residualQuery);
                            if (strictMatchMode) {
                                qs.defaultOperator(Operator.And);
                            }
                            return qs;
                        }));
                    } else if (fieldScopes.isEmpty()) {
                        b.must(s -> s.queryString(qs -> qs.query("*")));
                    }
                    if (!fieldScopes.isEmpty()) {
                        LOGGER.debug("Applying {} field scopes", fieldScopes.size());
                        applyFieldScopes(b, fieldScopes);
                    }
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

    /**
     * Builds a search request without topic tag filters to get the base count.
     * Used to calculate the denominator for "showing X/Y results" display.
     */
    private SearchRequest buildBaseSearchRequest(
            String fuzziedQuery, Highlight highlightBuilder) throws UnknownHostException {
        String finalQuery = (fuzziedQuery == null || fuzziedQuery.trim().isEmpty()) ? "*" : fuzziedQuery.trim();

        List<FieldScope> fieldScopes = parseFieldScopes(finalQuery);
        String residualQuery = removeFieldScopes(finalQuery);

        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(ESClientProvider.INDEX)
                .searchType(SearchType.DfsQueryThenFetch)
                .query(q -> q.bool(b -> {
                    if (!residualQuery.isBlank()) {
                        b.must(s -> s.queryString(qs -> {
                            qs.query(residualQuery);
                            if (strictMatchMode) {
                                qs.defaultOperator(Operator.And);
                            }
                            return qs;
                        }));
                    } else if (fieldScopes.isEmpty()) {
                        b.must(s -> s.queryString(qs -> qs.query("*")));
                    }
                    if (!fieldScopes.isEmpty()) {
                        applyFieldScopes(b, fieldScopes);
                    }
                    // Note: No topic tag filters here - we want the base count
                    return b;
                }))
                .from(0)
                .size(0); // We only need the count, not actual results

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
                    String highlightQuery = buildHighlightQueryString(query);
                    return q.queryString(qs -> {
                        qs.query(highlightQuery).defaultField("*");
                        if (strictMatchMode) {
                            qs.defaultOperator(Operator.And);
                        }
                        return qs;
                    });
                })
                .numberOfFragments(0);
        return highlightBuilder.build();
    }

    private static String buildHighlightQueryString(String query) {
        String raw = (query == null || query.trim().isEmpty()) ? "*" : query.trim();
        if ("*".equals(raw)) {
            return raw;
        }
        String residual = removeFieldScopes(raw);
        if (residual.isBlank()) {
            return "*";
        }
        String normalized = BOOST_PATTERN.matcher(residual).replaceAll("");
        normalized = FUZZY_SUFFIX_PATTERN.matcher(normalized).replaceAll("");
        normalized = BOOLEAN_OPERATOR_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = normalized.replace("(", " ").replace(")", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "*";
        }
        Matcher matcher = HIGHLIGHT_TOKEN_PATTERN.matcher(normalized);
        LinkedHashSet<String> uniqueTokens = new LinkedHashSet<>();
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (!token.isEmpty()) {
                uniqueTokens.add(token);
            }
        }
        if (uniqueTokens.isEmpty()) {
            return "*";
        }
        return String.join(" ", uniqueTokens);
    }

    private Map<String, Long> extractTopicTagFacets(SearchResponse<Map> response) {
        if (response == null || response.aggregations() == null || !response.aggregations().containsKey("topic_tag_counts")) {
            return new LinkedHashMap<>();
        }
        try {
            long maxCount = maxResultWindow > 0 ? maxResultWindow : Long.MAX_VALUE;
            return response.aggregations()
                    .get("topic_tag_counts")
                    .sterms()
                    .buckets()
                    .array()
                    .stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(
                            bucket -> bucket.key().stringValue(),
                            bucket -> Math.min(bucket.docCount(), maxCount),
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
        // Only return the tag itself for exact matching - no hierarchical expansion
        // This means parent tags like "prayer" only match hadith with "prayer" tag directly,
        // not hadith with child tags like "friday-prayer"
        return List.of(slug);
    }

    private static List<TopicTaxonomySupport.TopicTaxonomyEntry> loadTaxonomy() {
        try {
            return TopicTaxonomySupport.loadBundledTaxonomy();
        } catch (Exception ex) {
            LOGGER.warn("Unable to load taxonomy for hierarchical topic-tag filters.", ex);
            return List.of();
        }
    }

    /**
     * Represents a field-scoped query like field:"value"
     */
    private static class FieldScope {
        String field;
        String value;

        FieldScope(String field, String value) {
            this.field = field;
            this.value = value;
        }
    }

    /**
     * Fields that are keyword type (no .keyword subfield needed).
     * These fields use exact matching without text analysis.
     */
    private static final String[] KEYWORD_ONLY_FIELDS = new String[]{"book", "volume", "part", "section", "number", "edition", "publisher"};

    private boolean isKeywordOnlyField(String fieldName) {
        for (String kwField : KEYWORD_ONLY_FIELDS) {
            if (kwField.equals(fieldName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fields that have simple values (numbers, short strings) suitable for term queries.
     */
    private boolean isSimpleValueField(String fieldName) {
        return "volume".equals(fieldName) || "section".equals(fieldName) || "number".equals(fieldName);
    }

    /**
     * Parses field-scoped queries from the query string.
     * Handles patterns like: field:"quoted value" joined by " AND "
     * Returns a list of FieldScopes.
     */
    private static List<FieldScope> parseFieldScopes(String query) {
        List<FieldScope> scopes = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return scopes;
        }

        // Pattern to match field:"value" - handles quoted values with any characters
        // The query may have " AND " between field scopes in strict mode
        Pattern pattern = Pattern.compile("(\\w+):\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(query);

        while (matcher.find()) {
            String field = matcher.group(1);
            String value = matcher.group(2);
            if (field != null && value != null) {
                LOGGER.debug("Parsed field scope: {} = {}", field, value);
                scopes.add(new FieldScope(field, value));
            }
        }

        LOGGER.debug("Total field scopes parsed: {}", scopes.size());
        return scopes;
    }

    private static String removeFieldScopes(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "";
        }
        String stripped = query.replaceAll("(\\w+):\"([^\"]*)\"", " ");
        stripped = stripped.replaceAll("\\s+", " ").trim();
        if (stripped.isEmpty()) {
            return "";
        }
        String[] tokens = stripped.split("\\s+");
        List<String> cleaned = new ArrayList<>();
        boolean expectingTerm = true;
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String upper = token.toUpperCase();
            boolean isBoolean = "AND".equals(upper) || "OR".equals(upper);
            if (isBoolean) {
                if (expectingTerm || cleaned.isEmpty()) {
                    continue;
                }
                cleaned.add(upper);
                expectingTerm = true;
                continue;
            }
            cleaned.add(token);
            expectingTerm = false;
        }
        if (!cleaned.isEmpty()) {
            String tail = cleaned.get(cleaned.size() - 1);
            if ("AND".equalsIgnoreCase(tail) || "OR".equalsIgnoreCase(tail)) {
                cleaned.remove(cleaned.size() - 1);
            }
        }
        return String.join(" ", cleaned).trim();
    }

    private void applyFieldScopes(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder boolBuilder,
                                  List<FieldScope> fieldScopes) {
        for (FieldScope scope : fieldScopes) {
            String field = isKeywordOnlyField(scope.field) ? scope.field : scope.field + ".keyword";
            String wildcardValue = scope.field.equals("book") ? scope.value : "*" + scope.value + "*";
            if (scope.field.equals("book") || isSimpleValueField(scope.field)) {
                boolBuilder.filter(f -> f.term(t -> t.field(field).value(scope.value)));
            } else {
                boolBuilder.filter(f -> f.wildcard(w -> w.field(field).value(wildcardValue)));
            }
        }
    }
}

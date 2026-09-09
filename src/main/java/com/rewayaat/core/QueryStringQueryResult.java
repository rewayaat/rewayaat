package com.rewayaat.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.data.HadithObject;
import com.rewayaat.tools.TopicTaxonomySupport;

import co.elastic.clients.elasticsearch._types.SearchType;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.SourceConfig;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
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
    private List<String> queryFields = null;
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

    /**
     * Restricts and weights the fields the query string searches.
     *
     * <p>Null, the default, uses {@link #SEARCHABLE_FIELDS}, which is what the website
     * does and what its relevance has always been tuned against. It is a seam for
     * callers whose failure mode is different - see
     * {@link com.rewayaat.mcp.NarrationRepository}, where an unweighted search over every
     * field lets isnād chains outrank the matn.
     *
     * @param fields query_string field specs, boosts included, e.g. {@code "english^2"}.
     */
    public QueryStringQueryResult queryFields(List<String> fields) {
        this.queryFields = fields == null || fields.isEmpty() ? null : List.copyOf(fields);
        return this;
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
                    // Metadata is highlighted through its analysed .text sub-field, but the
                    // client knows the field by its base name and overlays the marked-up
                    // value onto it. Without this the highlight arrives under a key nothing
                    // reads and the metadata renders unmarked.
                    String field = entry.getKey();
                    if (field.endsWith(METADATA_TEXT_SUFFIX)) {
                        field = field.substring(0, field.length() - METADATA_TEXT_SUFFIX.length());
                    }
                    result.put(field, entry.getValue().get(0));
                }
            }
        }
        HadithDisplaySegmenter.enrich(result);
        hadithes.add(mapper.convertValue(result, HadithObject.class));
    }

    private SearchRequest buildSearchRequest(
            String fuzziedQuery, Highlight highlightBuilder) throws UnknownHostException {
        return buildSearchRequest(fuzziedQuery, highlightBuilder, HadithSourceFilter.searchSource());
    }

    /**
     * Builds the search this class runs.
     *
     * <p>{@code sourceConfig} and a null {@code highlightBuilder} are the seams a caller that
     * wants the query but not the display shaping uses - see {@link #rawResult(List)}. Every
     * caller shares the field scoping, the topic-tag filters, the strictness handling and the
     * sorting below, because a second implementation of those is a second set of search
     * semantics for the same corpus.
     */
    private SearchRequest buildSearchRequest(
            String fuzziedQuery, Highlight highlightBuilder, SourceConfig sourceConfig)
            throws UnknownHostException {
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
                        // The metadata fields carry an analysed .text sub-field, which the
                        // default "*" field expansion reaches, so one query_string covers
                        // matn and metadata alike. See METADATA_TEXT_FIELDS.
                        b.must(s -> s.queryString(qs -> {
                            qs.query(residualQuery);
                            qs.fields(queryFields != null ? queryFields : SEARCHABLE_FIELDS);
                            if (strictMatchMode) {
                                qs.defaultOperator(Operator.And);
                            }
                            return qs;
                        }));
                        applyMetadataRankingBoost(b, residualQuery);
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
                .source(sourceConfig)
                .from(from)
                .size(size);

        if (highlightBuilder != null) {
            // Highlighting and the facet aggregation exist for the website's result list.
            // A tool result has nowhere to render either, and both cost time and bytes.
            builder.highlight(highlightBuilder)
                    .aggregations("topic_tag_counts",
                            a -> a.terms(t -> t.field("topic_tags").size(200)));
        }

        for (SortOptions sort : this.sortBuilders) {
            builder.sort(sort);
        }
        return builder.build();
    }

    /** One hit, before any display shaping: the id and the trimmed {@code _source}. */
    public record RawHit(String id, Map<String, Object> source) {
    }

    /** {@link #rawResult} output: the page, and the true total behind it. */
    public record RawResult(List<RawHit> hits, long total) {
    }

    /**
     * Runs this query and returns the raw {@code _source} maps, limited to
     * {@code sourceIncludes}, with no highlighting, no facet aggregation and none of the
     * segmenting {@link #result()} applies.
     *
     * <p>For callers that shape their own output - the MCP tools, which send a language model
     * the matn and the citation and nothing else. They reuse this rather than assembling
     * their own query so that a tool call and a website search interpret the same words the
     * same way: a fork loses the field scoping and the strictness handling above, and the
     * connector then quietly disagrees with the site it claims to index.
     */
    public RawResult rawResult(List<String> sourceIncludes) throws Exception {
        SourceConfig sourceConfig = SourceConfig.of(sc -> sc.filter(f -> f.includes(sourceIncludes)));
        SearchRequest request = buildSearchRequest(this.query, null, sourceConfig);

        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(request, Map.class);
            List<RawHit> hits = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                if (hit.source() == null) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> source = new LinkedHashMap<>((Map<String, Object>) hit.source());
                hits.add(new RawHit(hit.id(), source));
            }
            long total = response.hits().total() == null ? hits.size() : response.hits().total().value();
            if (maxResultWindow > 0) {
                total = Math.min(total, maxResultWindow);
            }
            return new RawResult(hits, total);
        }
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
                        // The metadata fields carry an analysed .text sub-field, which the
                        // default "*" field expansion reaches, so one query_string covers
                        // matn and metadata alike. See METADATA_TEXT_FIELDS.
                        b.must(s -> s.queryString(qs -> {
                            qs.query(residualQuery);
                            qs.fields(queryFields != null ? queryFields : SEARCHABLE_FIELDS);
                            if (strictMatchMode) {
                                qs.defaultOperator(Operator.And);
                            }
                            return qs;
                        }));
                        applyMetadataRankingBoost(b, residualQuery);
                    } else if (fieldScopes.isEmpty()) {
                        b.must(s -> s.queryString(qs -> qs.query("*")));
                    }
                    if (!fieldScopes.isEmpty()) {
                        applyFieldScopes(b, fieldScopes);
                    }
                    // Note: No topic tag filters here - we want the base count
                    return b;
                }))
                .source(HadithSourceFilter.searchSource())
                .from(0)
                .size(0); // We only need the count, not actual results

        for (SortOptions sort : this.sortBuilders) {
            builder.sort(sort);
        }
        return builder.build();
    }

    /**
     * Builds the highlighting, which is a second query run over the matched documents.
     *
     * <p>The metadata is asked for by its {@code .text} sub-field: the base {@code keyword}
     * is one token, so highlighting it marks the whole value, and "The Book of Commerce"
     * comes back wholly wrapped for a search for {@code commerce}. The sub-field marks the
     * word. Both field lists and the query below are plain query_string, which reaches a
     * multi-field through the default "*" expansion, so this stays in step with the
     * matching query without either side maintaining clauses of its own.
     */
    private Highlight getHighlightBuilder(String fuzziedQuery) {
        List<NamedValue<HighlightField>> fields = new ArrayList<>();
        for (String field : List.of("english", "arabic", "chapter", "notes")) {
            fields.add(NamedValue.of(field, new HighlightField.Builder().build()));
        }
        for (String field : METADATA_TEXT_FIELDS) {
            fields.add(NamedValue.of(field, new HighlightField.Builder().build()));
        }
        Highlight.Builder highlightBuilder = new Highlight.Builder()
                .fields(fields)
                .postTags("</span>")
                .preTags("<span class=\"highlight\">")
                .highlightQuery(q -> q.queryString(qs -> {
                    qs.query(buildHighlightQueryString(query)).fields(SEARCHABLE_FIELDS);
                    if (strictMatchMode) {
                        qs.defaultOperator(Operator.And);
                    }
                    return qs;
                }))
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


    /**
     * The fields a free-text search actually reads.
     *
     * <p>The default was Elasticsearch's "*", which searches every field in the mapping.
     * That included the embedding pipeline's working copies - semantic_matn_source is the
     * chain-stripped matn and holds no narration that {@code arabic} does not, measured at
     * zero unique hits for الصلاة, الزكاة and الصوم - so a match counted two or three times
     * and the affected narrations outranked equally good ones for no reason a reader could
     * see. It also included a dozen fields that hold nothing at all.
     *
     * <p>Arabic and English metadata are both listed: the sub-fields are analysed, so they
     * match a bare word, and {@link #applyMetadataRankingBoost} decides where they sort.
     * Anything absent here is deliberately unsearchable - annotations, footnotes, gradings,
     * translation suggestions, the llm_similar bookkeeping and the semantic sources.
     */
    private static final List<String> SEARCHABLE_FIELDS = List.of(
            "english", "arabic", "chapter", "notes",
            "book.text", "volume.text", "part.text", "section.text",
            "source.text", "publisher.text",
            "book_ar", "chapter_ar.text", "part_ar.text", "section_ar.text", "source_ar.text",
            "topic_tags");

    /**
     * The metadata fields that carry an analysed {@code .text} sub-field.
     *
     * <p>These are mapped as {@code keyword}, so the stored value is a single token: the
     * 1,062 narrations under part "The Book of Commerce" hold one term, "The Book of
     * Commerce", and a bare search for {@code commerce} matches none of them. The sub-field
     * indexes the same value word by word, which is what makes it both findable and
     * highlightable a word at a time rather than a whole value at a time.
     *
     * <p>The base {@code keyword} is left in place, so the exact term filters in
     * {@link #applyFieldScopes} are unaffected. Nothing here needs a clause of its own:
     * query_string's default "*" expansion already covers a multi-field, so matching and
     * highlighting both pick these up from the one query. The list exists so the highlight
     * builder asks for the right field names.
     */
    /** The sub-field suffix, stripped before a highlight is handed to the client. */
    /**
     * How far a metadata match is lifted above a matn match.
     *
     * <p>Both sides are BM25 over analysed text, so they sit on one scale and the gap is
     * small - the old wildcard scored a flat 1.0 and needed a thousand to be seen at all.
     * The figure is the knee rather than a margin: swept over commerce, zakat, prayer,
     * fasting, pilgrimage, knowledge, marriage, hassan, mercy and ghadir against the
     * enhanced {@code (term^6 OR term~)} form the site actually sends, twelve still left
     * The Book of Commerce below three matn hits, twenty-five put every query that has
     * matching metadata at the top of its results, and fifty, eighty, a hundred and twenty
     * and two hundred changed nothing further. Past the knee a larger number buys no
     * ordering and only flattens relevance within the metadata itself.
     *
     * <p>hassan, mercy and ghadir stay matn-first at every value, which is correct: no
     * book, part or section is named for them, so there is nothing to lift.
     */
    private static final float METADATA_RANKING_BOOST = 25f;

    /** The sub-field suffix, stripped before a highlight is handed to the client. */
    private static final String METADATA_TEXT_SUFFIX = ".text";

    private static final List<String> METADATA_TEXT_FIELDS =
            List.of("book.text", "volume.text", "part.text", "section.text",
                    "source.text", "publisher.text");

    /**
     * Sorts a metadata match above a matn match without changing what matches.
     *
     * <p>Someone searching "commerce" wants The Book of Commerce before a narration that
     * happens to use the word in passing. This is a {@code should} beside the {@code must}
     * above, so it contributes score only - the {@code must} has already decided the result
     * set, and a narration that matches nothing here is neither excluded nor required to.
     *
     * <p>The boost is modest because both sides are now BM25 over analysed text and so are
     * already on one scale; the old wildcard scored a flat 1.0 and needed three orders of
     * magnitude to be seen at all. A short metadata value also scores high on its own
     * through BM25 field-length normalisation, which does much of the work unaided.
     */
    private void applyMetadataRankingBoost(
            co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder boolBuilder,
            String residualQuery) {
        String normalized = buildHighlightQueryString(residualQuery);
        if (normalized == null || normalized.isBlank() || "*".equals(normalized)) {
            return;
        }
        boolBuilder.should(s -> s.multiMatch(m -> m
                .query(normalized)
                .fields(METADATA_TEXT_FIELDS)
                .boost(METADATA_RANKING_BOOST)));
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
    /**
     * Fields that are mapped {@code keyword} outright and so have no {@code .keyword} child.
     *
     * <p>A field missing from this list is filtered as {@code field.keyword}, which for a
     * field that is already a keyword names something that does not exist - the filter then
     * matches nothing and the scope silently returns no results. That is what
     * {@code source:"..."} and {@code topic_tags:"..."} did: both are populated, on 32,519
     * and 31,809 narrations, and both returned zero.
     */
    private static final String[] KEYWORD_ONLY_FIELDS = new String[]{"book", "volume", "part", "section",
            "number", "edition", "publisher", "source", "topic_tags"};

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

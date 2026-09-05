package com.rewayaat.mcp;

import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.core.QueryMode;
import com.rewayaat.core.QueryStringQueryResult;
import com.rewayaat.service.HadithQueryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch reads for the MCP tools.
 *
 * <p>Search delegates to the website's own {@link QueryStringQueryResult}, through its
 * {@code rawResult} seam, rather than assembling a second query. An earlier version of this
 * class did build its own, on the reasoning that the tools need a much tighter field list -
 * but the field list is a {@code _source} argument, not grounds to reimplement a query. The
 * fork silently lost field scoping ({@code chapter:"..."}, {@code source:"..."} and the
 * rest), the precise-match handling and the topic-tag slug normalisation, and it introduced a
 * bug those would have prevented: written inline, {@code book:"..."} was OR-ed against the
 * search terms and widened the result set instead of narrowing it.
 *
 * <p>The rule that came out of it: a tool call and a website search must interpret the same
 * words the same way, so there is one query builder and two output shapes - never two query
 * builders.
 */
@Component
public class NarrationRepository {

    /**
     * Everything a citation or a reading needs, and nothing else.
     *
     * <p>What this leaves behind in Elasticsearch is mostly {@code llm_similar} - the largest
     * field in a document, and {@code find_similar}'s job to return deliberately - along with
     * the {@code semantic_*_source} retrieval inputs and the {@code *_ar} metadata the site
     * renders but a tool result has no use for.
     *
     * <p>It does not save us the {@code englishContent} / {@code arabicChain} splits, which
     * are the other half of an API response's bulk: those are not stored at all. They are
     * computed by {@link com.rewayaat.core.HadithDisplaySegmenter} on the way out, so the
     * saving there comes from not invoking it - which is what {@code rawResult} avoids.
     */
    private static final List<String> SUMMARY_FIELDS = List.of(
            "book", "volume", "part", "section", "chapter", "number",
            "english", "arabic", "gradings", "topic_tags");

    private final HadithQueryService queryService;

    public NarrationRepository(HadithQueryService queryService) {
        this.queryService = queryService;
    }

    /** A page of search hits, plus the total so a tool can report an exhaustive count. */
    public record Page(List<Narration> narrations, long total) {
    }

    /** One narration: its id and the trimmed {@code _source}. */
    public record Narration(String id, Map<String, Object> source) {
    }

    /**
     * Runs a keyword search, with the website's exact semantics.
     *
     * <p>Keyword, not semantic: embedding a query at request time needs an Elasticsearch
     * inference endpoint that is not currently deployed. The stored vectors support kNN
     * between existing documents, which is what similarity uses, but not from arbitrary
     * text. The tool descriptions say so rather than letting a model assume otherwise.
     *
     * @param book optional single-book narrowing. Expressed as a field scope on the query so
     *        it travels the same path as a {@code book:"..."} a caller writes by hand; the
     *        structured argument exists because a model handles a named parameter more
     *        reliably than embedded Lucene syntax, not because it is a second mechanism.
     */
    public Page search(String query, int from, int size, List<String> topicTags, String book)
            throws Exception {
        String enhanced = queryService.enhanceQuery(query, QueryMode.SEARCH, false);
        String finalQuery = enhanced == null || enhanced.isBlank() ? "*" : enhanced.trim();
        if (book != null && !book.isBlank()) {
            finalQuery = finalQuery + " book:\"" + book.trim() + "\"";
        }

        int page = size > 0 ? from / size : 0;
        QueryStringQueryResult search = new QueryStringQueryResult(
                finalQuery,
                page,
                size,
                queryService.setupSortBuilders(""),
                false,
                0,
                topicTags == null ? List.of() : topicTags,
                List.of());

        QueryStringQueryResult.RawResult raw = search.rawResult(SUMMARY_FIELDS);
        List<Narration> narrations = new ArrayList<>();
        for (QueryStringQueryResult.RawHit hit : raw.hits()) {
            narrations.add(new Narration(hit.id(), hit.source()));
        }
        return new Page(narrations, raw.total());
    }

    /**
     * Every narration in one chapter, in document order, with the true chapter size.
     *
     * <p>The count is the point. Browsing a chapter on the open web gives you the narrations
     * somebody wrote up; this gives you all of them and tells you how many there were, which
     * is the difference between "these appear to be the main ones" and an answer.
     */
    public Page chapter(String book, String chapter, String volume, int from, int size) throws Exception {
        SearchRequest request = new SearchRequest.Builder()
                .index(ESClientProvider.INDEX)
                .query(q -> q.bool(b -> {
                    b.filter(f -> f.term(t -> t.field("book").value(book)));
                    b.filter(f -> f.term(t -> t.field("chapter.keyword").value(chapter)));
                    if (volume != null && !volume.isBlank()) {
                        b.filter(f -> f.term(t -> t.field("volume").value(volume)));
                    }
                    return b;
                }))
                .source(s -> s.filter(f -> f.includes(SUMMARY_FIELDS)))
                // `number` is a keyword, so the default sort would order it lexically -
                // 1, 10, 100, 2. HadithQueryService already owns the numeric script sort
                // the website uses for the same field; reuse it rather than restate it.
                .sort(queryService.setupSortBuilders("number:asc"))
                .from(Math.max(0, from))
                .size(Math.max(0, size))
                .trackTotalHits(t -> t.enabled(true))
                .build();

        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(request, Map.class);
            List<Narration> narrations = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                narrations.add(new Narration(hit.id(), asSource(hit.source())));
            }
            long total = response.hits().total() == null ? narrations.size() : response.hits().total().value();
            return new Page(narrations, total);
        }
    }

    /** One narration by id, or {@code null} when the id is not in the corpus. */
    public Narration get(String id) throws Exception {
        if (id == null || id.isBlank()) {
            return null;
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            GetResponse<Map> response = provider.client().get(g -> g
                    .index(ESClientProvider.INDEX)
                    .id(id.trim())
                    .sourceIncludes(SUMMARY_FIELDS), Map.class);
            if (!response.found() || response.source() == null) {
                return null;
            }
            return new Narration(id.trim(), asSource(response.source()));
        }
    }

    /**
     * The {@code llm_similar} field of one narration, which no other read path returns.
     *
     * <p>It is fetched on its own because it is the single largest field in a document and
     * every other tool deliberately excludes it.
     */
    public List<Map<String, Object>> similarLinks(String id) throws Exception {
        try (ESClientProvider provider = new ESClientProvider()) {
            GetResponse<Map> response = provider.client().get(g -> g
                    .index(ESClientProvider.INDEX)
                    .id(id)
                    .sourceIncludes(List.of("llm_similar")), Map.class);
            if (!response.found() || response.source() == null) {
                return List.of();
            }
            Object raw = response.source().get("llm_similar");
            List<Map<String, Object>> links = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        links.add(asSource(map));
                    }
                }
            }
            return links;
        }
    }

    /** Bulk id lookup, preserving the order of {@code ids} and skipping any that are missing. */
    public List<Narration> getAll(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        try (ESClientProvider provider = new ESClientProvider()) {
            MgetResponse<Map> response = provider.client().mget(m -> m
                    .index(ESClientProvider.INDEX)
                    .ids(ids)
                    .sourceIncludes(SUMMARY_FIELDS), Map.class);
            Map<String, Narration> found = new LinkedHashMap<>();
            for (MultiGetResponseItem<Map> item : response.docs()) {
                if (item.isFailure() || item.result() == null || !item.result().found()) {
                    continue;
                }
                if (item.result().source() == null) {
                    continue;
                }
                found.put(item.result().id(), new Narration(item.result().id(), asSource(item.result().source())));
            }
            List<Narration> ordered = new ArrayList<>();
            for (String id : ids) {
                Narration narration = found.get(id);
                if (narration != null) {
                    ordered.add(narration);
                }
            }
            return ordered;
        }
    }

    /** Distinct book names with their narration counts, largest first. */
    public Map<String, Long> books() throws Exception {
        SearchRequest request = new SearchRequest.Builder()
                .index(ESClientProvider.INDEX)
                .size(0)
                .aggregations("books", a -> a.terms(t -> t.field("book").size(100)))
                .build();
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Map> response = provider.client().search(request, Map.class);
            Map<String, Long> books = new LinkedHashMap<>();
            response.aggregations().get("books").sterms().buckets().array()
                    .forEach(bucket -> books.put(bucket.key().stringValue(), bucket.docCount()));
            return books;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asSource(Map<?, ?> raw) {
        return new LinkedHashMap<>((Map<String, Object>) raw);
    }
}

package com.rewayaat.mcp.tools;

import com.rewayaat.mcp.ChainView;
import com.rewayaat.mcp.CorpusScope;
import com.rewayaat.mcp.McpTool;
import com.rewayaat.mcp.NarrationRepository;
import com.rewayaat.mcp.NarrationView;
import com.rewayaat.service.HadithQueryService;
import com.rewayaat.service.NarratorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Search with the metadata the {@code search} façade has no room for.
 *
 * <p>The difference that matters is {@code total_matches}. OpenAI's shape returns a list and
 * says nothing about how much it left behind, so a model reading ten results cannot tell
 * whether it has seen the subject or a tenth of it. Here the count is always reported, which
 * is what lets an answer be exhaustive rather than impressionistic.
 */
@Component
public class SearchHadithTool implements McpTool {

    // See GetChapterTool on sizing: full Arabic makes a result far denser in tokens than
    // its character count suggests, and total_matches means a short page is not a lossy one.
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 15;
    // A resolved chain adds several hundred tokens a narration, so asking for chains also
    // lowers the ceiling. The count is still reported, so a smaller page costs a second call
    // and nothing else.
    private static final int MAX_LIMIT_WITH_ISNAD = 6;

    private final NarrationRepository repository;
    private final HadithQueryService queryService;
    private final NarratorService narrators;
    private final String baseUrl;

    public SearchHadithTool(NarrationRepository repository,
                            HadithQueryService queryService,
                            NarratorService narrators,
                            @Value("${rewayaat.canonical-url:https://rewayaat.info}") String baseUrl) {
        this.repository = repository;
        this.queryService = queryService;
        this.narrators = narrators;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "search_hadith";
    }

    @Override
    public String title() {
        return "Search narrations with full metadata";
    }

    @Override
    public String description() {
        return "Searches the corpus and returns full narrations - Arabic and English text, "
                + "book, chapter, number, gradings, topic tags and a citable URL - together "
                + "with `total_matches`, the true number of narrations matching the query. "
                + "Use `total_matches` before summarising: it is what distinguishes 'these "
                + "are all of them' from 'these are the first ten of many'.\n\n"
                + CorpusScope.SCOPE_SENTENCE + "\n\n"
                + "Matching is keyword-based (BM25) over the Arabic and English text, not "
                + "semantic. Two consequences worth planning around. Searching an English "
                + "gloss may return nothing when the Arabic returns the narration exactly, "
                + "so prefer an Arabic phrase from the matn when you have one. And a common "
                + "name will pull in chains of transmission rather than subject matter. "
                + "To find the narrations a particular narrator transmits, do not search his "
                + "name: look him up with `lookup_narrator` and pass his id to "
                + "`hadith_of_narrator`, which matches the man rather than the words. Narrow "
                + "with `topic_tags` when a query is a common word.\n\n"
                + "Results are lean by design and omit the chain of transmission. Set "
                + "`include_isnad` to get each narration's chain with the narrators resolved "
                + "and a status on every step; it makes results much larger, so the page size "
                + "is capped at " + MAX_LIMIT_WITH_ISNAD + " when it is on. For one narration's "
                + "chain, `fetch` always carries it.\n\n"
                + "If a search returns nothing, retry before concluding the corpus is "
                + "silent: the Arabic of the matn, a shorter phrase, a synonym. Matching is "
                + "literal, so the wording you choose is doing the work.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "Search terms, Arabic or English. Quoted "
                                        + "phrases are matched as phrases."),
                        "book", Map.of(
                                "type", "string",
                                "description", "Restrict to one book, named exactly as it "
                                        + "appears in results, e.g. 'Kāmil al-Ziyārāt'."),
                        "topic_tags", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Controlled topic tags that all results must "
                                        + "carry, e.g. ['fasting']."),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Results per page (default " + DEFAULT_LIMIT
                                        + ", max " + MAX_LIMIT + ")."),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "Results to skip, for paging through "
                                        + "`total_matches`."),
                        "include_isnad", Map.of(
                                "type", "boolean",
                                "description", "Also return each narration's chain of "
                                        + "transmission, with narrators identified where the "
                                        + "books allow and unanswered steps marked. Default "
                                        + "false; caps `limit` at " + MAX_LIMIT_WITH_ISNAD + "."),
                        "language", Map.of(
                                "type", "string",
                                "enum", List.of("en", "ar"),
                                "description", "Language of the chain's words and narrator "
                                        + "names when `include_isnad` is set (default 'en')."),
                        "match_mode", Map.of(
                                "type", "string",
                                "enum", List.of("flexible", "precise"),
                                "description", "'flexible' (default) also matches close "
                                        + "variants of each term and does not require them "
                                        + "all, which is what you want when you are looking "
                                        + "for a narration. 'precise' requires every term "
                                        + "exactly as written, which is what you want to "
                                        + "check whether a specific wording occurs.")),
                "required", List.of("query"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> arguments) throws Exception {
        String query = ToolArguments.requiredString(arguments, "query");
        List<String> topicTags = ToolArguments.stringList(arguments, "topic_tags");
        String book = ToolArguments.optionalString(arguments, "book", "");
        boolean withIsnad = ToolArguments.optionalBoolean(arguments, "include_isnad");
        String language = ToolArguments.language(arguments);
        int limit = ToolArguments.boundedInt(arguments, "limit", DEFAULT_LIMIT, 1,
                withIsnad ? MAX_LIMIT_WITH_ISNAD : MAX_LIMIT);
        int offset = ToolArguments.boundedInt(arguments, "offset", 0, 0, 9_000);
        String matchMode = ToolArguments.optionalString(arguments, "match_mode", "flexible");
        boolean precise = queryService.isPreciseMatchMode(matchMode);

        NarrationRepository.Page page =
                repository.search(query, offset, limit, topicTags, book, precise, withIsnad);

        List<Map<String, Object>> results = new ArrayList<>();
        for (NarrationRepository.Narration narration : page.narrations()) {
            results.add(NarrationView.summary(narration.id(), narration.source(), baseUrl));
        }
        if (withIsnad) {
            ChainView.attach(page.narrations(), results, language, narrators, baseUrl);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        if (!book.isEmpty()) {
            out.put("book", book);
        }
        out.put("match_mode", precise ? "precise" : "flexible");
        out.put("total_matches", page.total());
        out.put("offset", offset);
        out.put("returned", results.size());
        out.put("results", results);
        if (results.isEmpty()) {
            out.put("note", CorpusScope.NO_RESULTS_NOTE
                    + " Before concluding anything, try the Arabic phrasing - an English "
                    + "gloss often misses a narration this corpus does hold.");
        } else if (page.total() > offset + results.size()) {
            out.put("note", "Showing " + results.size() + " of " + page.total()
                    + " matches. Page with `offset` before describing the whole set.");
        }
        return out;
    }
}

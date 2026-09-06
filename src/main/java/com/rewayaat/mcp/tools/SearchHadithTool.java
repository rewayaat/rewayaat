package com.rewayaat.mcp.tools;

import com.rewayaat.mcp.CorpusScope;
import com.rewayaat.mcp.McpTool;
import com.rewayaat.mcp.NarrationRepository;
import com.rewayaat.mcp.NarrationView;
import com.rewayaat.service.HadithQueryService;
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

    private final NarrationRepository repository;
    private final HadithQueryService queryService;
    private final String baseUrl;

    public SearchHadithTool(NarrationRepository repository,
                            HadithQueryService queryService,
                            @Value("${rewayaat.canonical-url:https://hadith.academyofislam.com}") String baseUrl) {
        this.repository = repository;
        this.queryService = queryService;
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
                + "name will pull in chains of transmission rather than subject matter - "
                + "searching a narrator's name finds narrations he appears in the isnād of, "
                + "which is usually not what was wanted. Narrow with `topic_tags` when a "
                + "query is a common word.\n\n"
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
        int limit = ToolArguments.boundedInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        int offset = ToolArguments.boundedInt(arguments, "offset", 0, 0, 9_000);
        String matchMode = ToolArguments.optionalString(arguments, "match_mode", "flexible");
        boolean precise = queryService.isPreciseMatchMode(matchMode);

        NarrationRepository.Page page =
                repository.search(query, offset, limit, topicTags, book, precise);

        List<Map<String, Object>> results = new ArrayList<>();
        for (NarrationRepository.Narration narration : page.narrations()) {
            results.add(NarrationView.summary(narration.id(), narration.source(), baseUrl));
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

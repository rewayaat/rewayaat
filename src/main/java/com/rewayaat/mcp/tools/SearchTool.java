package com.rewayaat.mcp.tools;

import com.rewayaat.mcp.CorpusScope;
import com.rewayaat.mcp.McpTool;
import com.rewayaat.mcp.NarrationRepository;
import com.rewayaat.mcp.NarrationView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code search} half of OpenAI's two-tool contract.
 *
 * <p>ChatGPT's deep-research and company-knowledge paths can only call two tools, and they
 * must be named {@code search} and {@code fetch}, take a single string, and return a fixed
 * shape. That is not a shape we would have chosen - it has no room for a chapter, a grading
 * or a result count - but it is the price of being installable there at all, so it is
 * reproduced exactly rather than approximated.
 *
 * <p>The richer {@link SearchHadithTool} exists alongside it for Claude and for ChatGPT's
 * developer mode, which can call arbitrary tools.
 *
 * @see <a href="https://developers.openai.com/api/docs/mcp">Building MCP servers for ChatGPT</a>
 */
@Component
public class SearchTool implements McpTool {

    /**
     * Deliberately small. Every result here is a full narration, and ChatGPT will follow up
     * with {@code fetch} for the ones it wants, so a long list buys nothing but tokens.
     */
    private static final int RESULT_LIMIT = 10;

    private final NarrationRepository repository;
    private final String baseUrl;

    public SearchTool(NarrationRepository repository,
                      @Value("${rewayaat.canonical-url:https://hadith.academyofislam.com}") String baseUrl) {
        this.repository = repository;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "search";
    }

    @Override
    public String title() {
        return "Search Shia narrations";
    }

    @Override
    public String description() {
        return "Searches the Rewayaat corpus of Shia hadith and returns matching narrations "
                + "with their ids, titles and canonical URLs. Accepts Arabic or English; an "
                + "Arabic phrase from the matn is usually the most precise way to find a "
                + "specific narration. Use the `fetch` tool with a returned id to read the "
                + "full Arabic and English text.\n\n"
                + CorpusScope.SCOPE_SENTENCE + "\n\n"
                + "Matching is keyword-based, so a narration may be present under wording you "
                + "did not try. Before concluding that something is absent, search again with "
                + "the Arabic phrasing.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of(
                        "type", "string",
                        "description", "Search terms, in Arabic or English.")),
                "required", List.of("query"));
    }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("results", Map.of(
                        "type", "array",
                        "items", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string"),
                                        "title", Map.of("type", "string"),
                                        "url", Map.of("type", "string")),
                                "required", List.of("id", "title", "url")))),
                "required", List.of("results"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> arguments) throws Exception {
        String query = ToolArguments.requiredString(arguments, "query");
        NarrationRepository.Page page = repository.search(query, 0, RESULT_LIMIT, List.of());

        List<Map<String, Object>> results = new ArrayList<>();
        for (NarrationRepository.Narration narration : page.narrations()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", narration.id());
            // The title has to carry the citation, because this shape has nowhere else to
            // put it - ChatGPT shows the title and cites the url, and drops everything else.
            row.put("title", NarrationView.label(narration.source()));
            row.put("url", NarrationView.url(narration.id(), baseUrl));
            results.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        if (results.isEmpty()) {
            out.put("note", CorpusScope.NO_RESULTS_NOTE);
        }
        return out;
    }
}

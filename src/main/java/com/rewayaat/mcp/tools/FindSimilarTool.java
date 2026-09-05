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
 * Narrations judged similar to a given one, with the reason the judgement was made.
 *
 * <p>This is the tool with no equivalent anywhere else. The links are not computed at request
 * time and are not a vector-similarity score: 374,461 candidate pairs were put to a language
 * model, 47,522 came back judged similar, and each carries a written reason and a type.
 * 89,225 of those judgements are in the index today, across 25,273 narrations. A search
 * engine indexes documents; this is a join over them, and nothing crawls it.
 *
 * <p>The reason text is the payload - it is why {@code llm_similar} is the largest field in a
 * document and why every other tool excludes it. Returning it is this tool's whole purpose.
 */
@Component
public class FindSimilarTool implements McpTool {

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 25;

    private final NarrationRepository repository;
    private final String baseUrl;

    public FindSimilarTool(NarrationRepository repository,
                           @Value("${rewayaat.canonical-url:https://hadith.academyofislam.com}") String baseUrl) {
        this.repository = repository;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "find_similar";
    }

    @Override
    public String title() {
        return "Find similar narrations";
    }

    @Override
    public String description() {
        return "Given a narration id, returns other narrations in the corpus judged to be "
                + "related to it, each with the reason for the judgement and a match type: "
                + "'wording' (near-identical text), 'conceptual' (same ruling or subject, "
                + "different wording), or 'thematic' (looser topical link).\n\n"
                + "These links were judged in advance by a language model over candidate "
                + "pairs, not computed from a similarity score at request time, and they "
                + "exist nowhere else - no web search can retrieve them, because they are a "
                + "relation between narrations rather than a document. Use this to gather "
                + "parallel reports of the same hadith across different books, or the "
                + "complementary rulings that sit around one in the law.\n\n"
                + "Ids come from `search` or `search_hadith`. Not every narration has links: "
                + "25,273 of 32,519 do.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of(
                                "type", "string",
                                "description", "Narration id to find relatives of."),
                        "match_type", Map.of(
                                "type", "string",
                                "enum", List.of("wording", "conceptual", "thematic"),
                                "description", "Return only links of this type. Omit for all."),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Maximum links to return (default "
                                        + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ").")),
                "required", List.of("id"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> arguments) throws Exception {
        String id = ToolArguments.requiredString(arguments, "id");
        String matchType = ToolArguments.optionalString(arguments, "match_type", "");
        int limit = ToolArguments.boundedInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

        NarrationRepository.Narration source = repository.get(id);
        if (source == null) {
            throw new IllegalArgumentException(
                    "No narration with id '" + id + "'. " + CorpusScope.NO_RESULTS_NOTE);
        }

        List<Map<String, Object>> links = repository.similarLinks(id);
        List<Map<String, Object>> selected = new ArrayList<>();
        for (Map<String, Object> link : links) {
            String type = NarrationView.str(link.get("match_type"));
            if (!matchType.isEmpty() && !matchType.equalsIgnoreCase(type)) {
                continue;
            }
            selected.add(link);
            if (selected.size() >= limit) {
                break;
            }
        }

        // One multi-get for the whole page rather than a lookup per link.
        List<String> targetIds = selected.stream()
                .map(link -> NarrationView.str(link.get("id")))
                .filter(value -> !value.isEmpty())
                .toList();
        Map<String, NarrationRepository.Narration> targets = new LinkedHashMap<>();
        for (NarrationRepository.Narration narration : repository.getAll(targetIds)) {
            targets.put(narration.id(), narration);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> link : selected) {
            String targetId = NarrationView.str(link.get("id"));
            NarrationRepository.Narration target = targets.get(targetId);
            if (target == null) {
                // A judged pair can outlive the narration it points at; skip rather than
                // emit a citation that resolves to nothing.
                continue;
            }
            Map<String, Object> row = NarrationView.summary(targetId, target.source(), baseUrl);
            row.put("match_type", NarrationView.str(link.get("match_type")));
            row.put("reason", NarrationView.str(link.get("reason")));
            results.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", Map.of(
                "id", id,
                "citation", NarrationView.label(source.source()),
                "url", NarrationView.url(id, baseUrl)));
        out.put("total_links", links.size());
        out.put("returned", results.size());
        out.put("similar", results);
        if (links.isEmpty()) {
            out.put("note", "This narration has no similarity links. That means the pair "
                    + "judgement found none, not that no related narration exists - "
                    + "try `search_hadith` with a distinctive phrase from its text.");
        }
        return out;
    }
}

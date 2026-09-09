package com.rewayaat.mcp.tools;

import com.rewayaat.mcp.CorpusScope;
import com.rewayaat.mcp.McpTool;
import com.rewayaat.mcp.NarrationRepository;
import com.rewayaat.mcp.NarrationView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code fetch} half of OpenAI's two-tool contract: an id in, one document out.
 *
 * <p>The fixed shape allows exactly one free-form field, {@code metadata}, so the book,
 * chapter, number and gradings all travel there. {@code text} carries the narration itself
 * with both languages, since a caller that asked for one narration wants to read it.
 *
 * @see SearchTool
 */
@Component
public class FetchTool implements McpTool {

    private final NarrationRepository repository;
    private final String baseUrl;

    public FetchTool(NarrationRepository repository,
                     @Value("${rewayaat.canonical-url:https://hadith.academyofislam.com}") String baseUrl) {
        this.repository = repository;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "fetch";
    }

    @Override
    public String title() {
        return "Read a narration";
    }

    @Override
    public String description() {
        return "Retrieves the full Arabic and English text of one narration by its id, along "
                + "with its book, chapter, number and any scholarly gradings. Ids come from "
                + "the `search` tool. Cite the returned url, not the number on its own - the "
                + "numbering follows this edition and may differ from a printed one.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("id", Map.of(
                        "type", "string",
                        "description", "Narration id, as returned by `search`.")),
                "required", List.of("id"));
    }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of("type", "string"),
                        "title", Map.of("type", "string"),
                        "text", Map.of("type", "string"),
                        "url", Map.of("type", "string"),
                        "metadata", Map.of("type", "object")),
                "required", List.of("id", "title", "text", "url"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> arguments) throws Exception {
        String id = ToolArguments.requiredString(arguments, "id");
        NarrationRepository.Narration narration = repository.get(id);
        if (narration == null) {
            throw new IllegalArgumentException(
                    "No narration with id '" + id + "'. " + CorpusScope.NO_RESULTS_NOTE
                    + " Ids come from the `search` tool and look like "
                    + "'Al-Kafi-Volume-4-Kulayni:690'.");
        }

        Map<String, Object> source = narration.source();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", narration.id());
        out.put("title", NarrationView.label(source));
        out.put("text", text(source));
        out.put("url", NarrationView.url(narration.id(), baseUrl));
        out.put("metadata", metadata(source));
        return out;
    }

    /** Both languages in one string, since {@code text} is the only content field there is. */
    private static String text(Map<String, Object> source) {
        String arabic = NarrationView.str(source.get("arabic"));
        String english = NarrationView.str(source.get("english"));
        StringBuilder text = new StringBuilder();
        if (!arabic.isEmpty()) {
            text.append(arabic);
        }
        if (!english.isEmpty()) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(english);
        }
        return text.toString();
    }

    private static Map<String, Object> metadata(Map<String, Object> source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String field : List.of("book", "volume", "part", "section", "chapter", "number")) {
            String value = NarrationView.str(source.get(field));
            if (!value.isEmpty()) {
                metadata.put(field, value);
            }
        }
        Map<String, Object> shaped = NarrationView.summary("", source, "");
        if (shaped.containsKey("gradings")) {
            metadata.put("gradings", shaped.get("gradings"));
        }
        if (shaped.containsKey("topic_tags")) {
            metadata.put("topic_tags", shaped.get("topic_tags"));
        }
        return metadata;
    }
}

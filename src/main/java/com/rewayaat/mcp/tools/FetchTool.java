package com.rewayaat.mcp.tools;

import com.rewayaat.mcp.ChainView;
import com.rewayaat.mcp.CorpusScope;
import com.rewayaat.mcp.McpTool;
import com.rewayaat.mcp.NarrationRepository;
import com.rewayaat.mcp.NarrationView;
import com.rewayaat.service.NarratorService;
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
 * <p>It takes no language argument - ChatGPT's schema is one string, and a rule in
 * ArchitectureRulesTest holds it there - so the chain comes in both languages at once.
 * {@code search_hadith} and {@code get_chapter} take a {@code language} when a client wants
 * one only.
 *
 * <p>The chain of transmission rides in {@code metadata.isnad}, for two reasons. The fixed
 * shape has one free-form field and a top-level addition may be dropped by a client that
 * validates against it. And this is the one tool whose job is a single narration, so it is the
 * one place the chain is always worth its tokens: a twelve-step chain with names and links is
 * some 800 tokens, which is cheap once and ruinous across a page of search hits. Those tools
 * return it only when asked.
 *
 * @see SearchTool
 */
@Component
public class FetchTool implements McpTool {

    private final NarrationRepository repository;
    private final NarratorService narrators;
    private final String baseUrl;

    public FetchTool(NarrationRepository repository, NarratorService narrators,
                     @Value("${rewayaat.canonical-url:https://rewayaat.info}") String baseUrl) {
        this.repository = repository;
        this.narrators = narrators;
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
                + "with its book, chapter, number and any scholarly gradings, and its chain of "
                + "transmission (`metadata.isnad`, in English with the Arabic beside it): each step as printed, the narrator it was "
                + "resolved to with a link to his page, and an explicit status where a step "
                + "could not be named. Ids come from the `search` tool. Cite the returned url, not the number on "
                + "its own - the numbering follows this edition and may differ from a printed one.\n\n"
                + "Read the isnad's `status` and `basis` before saying who narrated this. A step "
                + "marked `identified` was matched to a man in the Rijal books, but by "
                + "`sole_name` (one man holds the name), `context` (several do and one was "
                + "chosen from the neighbouring names) or `convention` (a bare name taken to "
                + "mean a particular man in this author's chains) - say which when it matters. "
                + "Steps marked `unidentified`, `ambiguous` or `group` are unanswered on "
                + "purpose: do not fill them in. Steps are in printed order, compiler's source "
                + "first, Imam last. A narration with no steps may simply have no analysed "
                + "chain, which is not the same as having none. Narrator pages and the "
                + "`lookup_narrator` tool tell you what the Rijal books say about each man; "
                + "this does not.";
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
        String language = ChainView.BOTH;
        NarrationRepository.Narration narration = repository.get(id, true);
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
        Map<String, Object> metadata = metadata(source);
        metadata.put("isnad", ChainView.isnad(source, language,
                narrators.names(ChainView.personIds(source)), baseUrl));
        out.put("metadata", metadata);
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
        for (String field : List.of("book", "volume", "part", "section", "chapter", "number", "source", "cited_from")) {
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

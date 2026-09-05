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
 * Every narration in one chapter, in order, with the chapter's true size.
 *
 * <p>{@code chapter_size} is the reason this tool exists. The evaluation behind issue #66
 * asked the open web for the Kāmil al-Ziyārāt chapter on creation weeping for al-Ḥusayn and
 * got five of its seven narrations, with nothing to indicate that two were missing. A
 * bounded corpus can say seven, and that turns "these appear to be the main ones" into an
 * answer.
 *
 * <p>Chapters run to 352 narrations, which is far past what a client will accept in one tool
 * result, so this pages. The count is reported on every page regardless - a partial page that
 * knows its denominator is still an exhaustive answer, and one that does not is not.
 */
@Component
public class GetChapterTool implements McpTool {

    // Sized against the client's ceiling, not by taste. Claude caps a tool result near
    // 150,000 characters and Claude Code at 25,000 tokens, and these narrations carry full
    // Arabic, which is dense in tokens - fifty of them measured 94,594 characters. Twenty
    // keeps the worst case inside both limits with room to spare; chapter_size still tells
    // the caller how much is left, so a small page costs nothing but a second call.
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;

    private final NarrationRepository repository;
    private final String baseUrl;

    public GetChapterTool(NarrationRepository repository,
                          @Value("${rewayaat.canonical-url:https://hadith.academyofislam.com}") String baseUrl) {
        this.repository = repository;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "get_chapter";
    }

    @Override
    public String title() {
        return "Read a whole chapter";
    }

    @Override
    public String description() {
        return "Returns the narrations of one chapter in order, with `chapter_size` - the "
                + "true total for that chapter. This is how to answer 'what does this book "
                + "say about X' exhaustively rather than impressionistically: the count is "
                + "authoritative, so you can state how many narrations a chapter holds and "
                + "whether you have seen all of them.\n\n"
                + "Take `book` and `chapter` verbatim from a `search_hadith` result; the "
                + "chapter title must match exactly. Chapters reach 352 narrations, so page "
                + "with `offset` when `chapter_size` exceeds what you received.\n\n"
                + "Note that chapter titles are an unreliable guide to contents - narrations "
                + "sit under headings that do not describe them. Do not infer a chapter's "
                + "subject from its title without reading it.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "book", Map.of(
                                "type", "string",
                                "description", "Book name exactly as it appears in results, "
                                        + "e.g. 'Kāmil al-Ziyārāt'."),
                        "chapter", Map.of(
                                "type", "string",
                                "description", "Chapter title exactly as it appears in results."),
                        "volume", Map.of(
                                "type", "string",
                                "description", "Volume, when a book repeats a chapter title "
                                        + "across volumes."),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Narrations per page (default " + DEFAULT_LIMIT
                                        + ", max " + MAX_LIMIT + ")."),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "Narrations to skip.")),
                "required", List.of("book", "chapter"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> arguments) throws Exception {
        String book = ToolArguments.requiredString(arguments, "book");
        String chapter = ToolArguments.requiredString(arguments, "chapter");
        String volume = ToolArguments.optionalString(arguments, "volume", "");
        int limit = ToolArguments.boundedInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        int offset = ToolArguments.boundedInt(arguments, "offset", 0, 0, 9_000);

        NarrationRepository.Page page = repository.chapter(book, chapter, volume, offset, limit);

        List<Map<String, Object>> narrations = new ArrayList<>();
        for (NarrationRepository.Narration narration : page.narrations()) {
            narrations.add(NarrationView.summary(narration.id(), narration.source(), baseUrl));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("book", book);
        out.put("chapter", chapter);
        if (!volume.isEmpty()) {
            out.put("volume", volume);
        }
        out.put("chapter_size", page.total());
        out.put("offset", offset);
        out.put("returned", narrations.size());
        out.put("narrations", narrations);
        if (page.total() == 0) {
            out.put("note", "No chapter matched that book and title exactly. Titles must be "
                    + "copied verbatim from a search result - this is a failure to match a "
                    + "title, not evidence about the corpus. " + CorpusScope.NO_RESULTS_NOTE);
        } else if (page.total() > offset + narrations.size()) {
            out.put("note", "This chapter holds " + page.total() + " narrations; "
                    + narrations.size() + " are shown from offset " + offset
                    + ". Page with `offset` before claiming to have covered it.");
        } else {
            out.put("note", "Complete: all " + page.total() + " narrations in this chapter "
                    + "are shown.");
        }
        return out;
    }
}

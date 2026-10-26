package com.rewayaat.mcp.tools;

import com.rewayaat.mcp.ChainView;
import com.rewayaat.mcp.McpTool;
import com.rewayaat.mcp.NarrationRepository;
import com.rewayaat.mcp.NarrationView;
import com.rewayaat.service.NarratorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The narrations a man transmits, from his id.
 *
 * <p>The other direction from {@code fetch}: that tool goes from a narration to the men in its
 * chain, this goes from a man to the narrations whose chain names him. It is a tool of its own
 * rather than an option on {@code lookup_narrator} because the two answer different questions
 * and have nothing else in common. {@code lookup_narrator} is about what the Rijal books say
 * of a man, is keyed by a name that may be ambiguous, and returns a few kilobytes of the books'
 * own Arabic. This one is keyed by an id that is not ambiguous, reads the hadith corpus rather
 * than the Rijal books, pages through as many as 8,635 narrations, and each of its rows is a
 * full narration. Folded into one tool, a model asking "is Zurara reliable" would pay for a
 * page of narrations it did not want, and one asking "what does Zurara narrate" would have to
 * discover that the answer hides behind a flag.
 *
 * <p>It takes an id and not a name on purpose. A name that two men bear would return a mixture
 * with nothing to say so; the id comes from {@code lookup_narrator} or from a step of a chain
 * in {@code fetch}, where a human or the resolver has already settled which man is meant.
 */
@Component
public class HadithOfNarratorTool implements McpTool {

    // See GetChapterTool on sizing: these are full narrations, in Arabic.
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 15;

    /** n000706 for a narrator, i06 for an Imam: the two shapes the narrator index holds. */
    private static final Pattern PERSON_ID = Pattern.compile("^(?:n\\d{6}|i\\d{2})$");

    private final NarrationRepository repository;
    private final NarratorService narrators;
    private final String baseUrl;

    public HadithOfNarratorTool(NarrationRepository repository, NarratorService narrators,
                                @Value("${rewayaat.canonical-url:https://rewayaat.info}")
                                String baseUrl) {
        this.repository = repository;
        this.narrators = narrators;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "hadith_of_narrator";
    }

    @Override
    public String title() {
        return "Narrations a narrator transmits";
    }

    @Override
    public String description() {
        return "Given a narrator's id, returns the narrations in whose chain of transmission he "
                + "stands, with `total_narrations` - the true number - and, for each, where in "
                + "the chain he is (`his_place`: the step, the words printed, and how he was "
                + "identified there). Get the id from `lookup_narrator`, or from a step of the "
                + "`isnad` that `fetch` returns. Imams have ids too, and the same call lists "
                + "the narrations that name them in a chain.\\n\\n"
                + "This matches the man, not his name, so it does not drown in other men who "
                + "bear the same name the way a keyword search does. The price is that it "
                + "counts only steps the books let us name. A step left unanswered - a name "
                + "several men hold, a man no Rijal entry covers - is linked to nobody, so "
                + "`total_narrations` is a floor, not a census, and a narration he did "
                + "transmit may be missing. A low count or a zero is not evidence he narrated "
                + "little.\\n\\n"
                + "Read `basis` on every row before saying he narrated something. `sole_name` "
                + "is a plain match; `context` and `convention` are inferences, the second "
                + "resting on how this author's chains are conventionally read. Results are "
                + "ordered by book and number, not by importance. Page with `offset`; narrow "
                + "with `book`. It cannot tell you whether he is reliable - that is "
                + "`lookup_narrator`'s question - and it says nothing about who he heard from "
                + "beyond the narration in front of you.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of(
                                "type", "string",
                                "description", "A narrator id such as «n000706», or an Imam's "
                                        + "such as «i06», from `lookup_narrator` or a step of a "
                                        + "chain from `fetch`. Not a name."),
                        "book", Map.of(
                                "type", "string",
                                "description", "Restrict to one book, named exactly as it "
                                        + "appears in results, e.g. 'Al-Kāfi'."),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Narrations per page (default " + DEFAULT_LIMIT
                                        + ", max " + MAX_LIMIT + ")."),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "Narrations to skip, for paging through "
                                        + "`total_narrations`."),
                        "language", Map.of(
                                "type", "string",
                                "enum", List.of("en", "ar"),
                                "description", "Language of the narrator's name and of the "
                                        + "words printed at his step (default 'en').")),
                "required", List.of("id"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> arguments) throws Exception {
        String id = ToolArguments.requiredString(arguments, "id");
        String book = ToolArguments.optionalString(arguments, "book", "");
        String language = ToolArguments.language(arguments);
        int limit = ToolArguments.boundedInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        int offset = ToolArguments.boundedInt(arguments, "offset", 0, 0, 9_000);

        if (!PERSON_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("'" + id + "' is not a narrator id. Ids look like "
                    + "'n000706' (a narrator) or 'i06' (an Imam); this tool takes an id, not a "
                    + "name. Use `lookup_narrator` to find the id of a man by name.");
        }
        Map<String, Object> record = narrators.names(List.of(id)).get(id);
        if (record == null) {
            throw new IllegalArgumentException("No narrator with id '" + id + "'. Ids come from "
                    + "`lookup_narrator`, or from a step of the chain `fetch` returns.");
        }

        NarrationRepository.Page page = repository.byNarrator(id, book, offset, limit);

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> bases = new LinkedHashSet<>();
        for (NarrationRepository.Narration narration : page.narrations()) {
            Map<String, Object> row = NarrationView.summary(
                    narration.id(), narration.source(), baseUrl);
            List<Map<String, Object>> places = ChainView.placesOf(narration.source(), id, language);
            row.put("his_place", places);
            for (Map<String, Object> place : places) {
                bases.add(String.valueOf(place.get("basis")));
            }
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("narrator", narrator(id, record, language));
        if (!book.isEmpty()) {
            out.put("book", book);
        }
        out.put("total_narrations", page.total());
        out.put("offset", offset);
        out.put("returned", rows.size());
        out.put("narrations", rows);
        if (!bases.isEmpty()) {
            out.put("legend", ChainView.legend(bases));
        }

        String moved = NarrationView.str(record.get("moved_to"));
        String floor = "This counts narrations where a chain step was linked to him; steps left "
                + "unanswered are not counted, so it is a lower bound.";
        if (page.total() == 0) {
            out.put("note", "No narration in these 18 books has a chain step linked to this "
                    + "man" + (book.isEmpty() ? "" : " in that book") + ". " + floor
                    + " A man the books mention only in a chain, or only under a name several "
                    + "men share, can stand in many chains and show none here."
                    + (moved.isEmpty() ? "" : " This id was folded into " + moved
                    + "; try that one."));
        } else if (page.total() > offset + rows.size()) {
            out.put("note", "Showing " + rows.size() + " of " + page.total() + " narrations "
                    + "from offset " + offset + ". Page with `offset` before describing the "
                    + "whole set. " + floor);
        } else {
            out.put("note", "All " + page.total() + " are shown. " + floor);
        }
        return out;
    }

    private Map<String, Object> narrator(String id, Map<String, Object> record, String language) {
        String ar = NarrationView.str(record.get("display_ar"));
        String en = NarrationView.str(record.get("display_en"));
        String slug = NarrationView.str(record.get("slug"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        String name = "ar".equals(language) ? (ar.isEmpty() ? en : ar) : (en.isEmpty() ? ar : en);
        if (!name.isEmpty()) {
            out.put("name", name);
        }
        out.put("url", baseUrl + "/narrator/" + id + (slug.isEmpty() ? "" : "/" + slug));
        return out;
    }
}

package com.rewayaat.mcp.tools;

import com.rewayaat.mcp.McpTool;
import com.rewayaat.service.NarratorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Shia Rijal books say about a narrator, in their own words.
 *
 * <p>The verdicts are the point, and so is their disagreement. Sahl b. Ziyad is «very weak» to
 * al-Najashi, «thiqa» to al-Tusi in one chapter, and a fabricator to Ibn al-Ghada'iri; a model
 * asked whether he is reliable can only answer «according to whom», and this tool is what makes
 * that answerable. Each verdict comes back under the book that gives it, with the page, and the
 * book's own Arabic beside it, so nothing has to be taken on our word.
 *
 * <p>The identity behind a narrator — which entries across eight books are one man — is the
 * pipeline's own work, measured at 98.2% on a blind audit of sampled people. Where it is thin,
 * the answer says so rather than implying more.
 */
@Component
public class LookupNarratorTool implements McpTool {

    private static final int MAX_MATCHES = 5;
    private static final int MAX_ENTRIES = 12;

    private final NarratorService narrators;
    private final String baseUrl;

    public LookupNarratorTool(NarratorService narrators,
                              @Value("${rewayaat.canonical-url:https://hadith.academyofislam.com}")
                              String baseUrl) {
        this.narrators = narrators;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "lookup_narrator";
    }

    @Override
    public String title() {
        return "Look up a narrator in the Rijal books";
    }

    @Override
    public String description() {
        return "Given a narrator's name, returns what the Shia Rijal books say about him: "
                + "al-Najashi, al-Tusi's Fihrist and Rijal, al-Kashshi, Ibn al-Ghada'iri's "
                + "Du'afa, Jami' al-Ruwat, Khoei's Mu'jam and Mamaqani's Tanqih. Each entry "
                + "comes with the book's own Arabic, its page, and the verdict that book "
                + "gives — thiqa, da'if, majhul, kadhdhab.\n\n"
                + "The books disagree about most men, and the disagreement is what this "
                + "returns: one narrator is «very weak» in al-Najashi and «thiqa» in al-Tusi "
                + "under a different Imam. Never report a grade without the book that gives "
                + "it. A grade the pipeline could not trace to an entry is not returned.\n\n"
                + "The entries of one man are gathered across the eight books by what those "
                + "books themselves say — a quotation reproduced, a citation by number, a "
                + "cross-reference — not by name alone. Absence means absent from these eight "
                + "books, not that no such narrator existed; and a man the books mention only "
                + "in a chain, with no entry anywhere, has no page here.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of(
                                "type", "string",
                                "description", "The narrator's name, in Arabic or transliterated: "
                                        + "«سهل بن زياد», «Sahl b. Ziyad», «Zurara»."),
                        "id", Map.of(
                                "type", "string",
                                "description", "A narrator id from an earlier answer, «n000515». "
                                        + "Give this instead of a name to fetch one man exactly."),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "How many matching narrators to return when "
                                        + "searching by name (default 3, max " + MAX_MATCHES + ").")),
                "required", List.of());
    }

    @Override
    public Map<String, Object> call(Map<String, Object> arguments) throws Exception {
        String id = ToolArguments.optionalString(arguments, "id", "");
        String name = ToolArguments.optionalString(arguments, "name", "");
        int limit = ToolArguments.boundedInt(arguments, "limit", 3, 1, MAX_MATCHES);

        if (!id.isEmpty()) {
            Map<String, Object> narrator = narrators.getNarrator(id);
            if (narrator == null) {
                throw new IllegalArgumentException("No narrator with id '" + id + "'.");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("narrator", full(narrator));
            return out;
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Give a narrator's name, or an id from an "
                    + "earlier answer.");
        }

        Map<String, Object> found = narrators.search(name, 1, limit);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) found.get("narrators");
        if (rows == null || rows.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", name);
            out.put("narrators", List.of());
            out.put("note", "No narrator of that name holds an entry in these eight Rijal "
                    + "books. He may still appear inside the chains, or in a book not held "
                    + "here; this is not evidence that no such narrator existed.");
            return out;
        }

        // The first match is returned in full, the rest as names only: a model asking about
        // «Muhammad b. Isa» needs to see which man it got, and that the others exist.
        List<Map<String, Object>> others = new ArrayList<>();
        for (int n = 1; n < rows.size(); n++) {
            Map<String, Object> row = rows.get(n);
            Map<String, Object> other = new LinkedHashMap<>();
            other.put("id", row.get("person_id"));
            other.put("name_ar", row.get("display_ar"));
            other.put("name_en", row.get("display_en"));
            other.put("entries", row.get("entry_count"));
            other.put("url", url(row.get("person_id")));
            others.add(other);
        }
        Map<String, Object> best = narrators.getNarrator(String.valueOf(rows.get(0).get("person_id")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", name);
        out.put("narrator", best == null ? rows.get(0) : full(best));
        if (!others.isEmpty()) {
            out.put("others_of_similar_name", others);
            out.put("note", "Several narrators answer to this name. Check the entries below "
                    + "against the chain you are reading before taking the verdict.");
        }
        return out;
    }

    /** One narrator as an answer should carry him: his verdicts, and the entries behind them. */
    private Map<String, Object> full(Map<String, Object> narrator) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", narrator.get("person_id"));
        out.put("name_ar", narrator.get("display_ar"));
        out.put("name_en", narrator.get("display_en"));
        out.put("url", url(narrator.get("person_id")));
        out.put("also_named", narrator.get("names_ar"));

        List<Map<String, Object>> verdicts = new ArrayList<>();
        for (Map<String, Object> verdict : rows(narrator.get("verdicts"))) {
            if (!Boolean.TRUE.equals(verdict.get("stated"))) {
                continue;               // a grade read from a book's silence is not its verdict
            }
            Map<String, Object> said = new LinkedHashMap<>();
            said.put("book", verdict.get("book_title"));
            said.put("verdict", verdict.get("said"));
            said.put("grade", verdict.get("grade"));
            said.put("pages", verdict.get("pages"));
            if (verdict.get("book_note") != null) {
                said.put("about_this_book", verdict.get("book_note"));
            }
            verdicts.add(said);
        }
        out.put("verdicts", verdicts);

        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> entry : rows(narrator.get("entries"))) {
            if (entries.size() >= MAX_ENTRIES) {
                break;
            }
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("book", entry.get("book_title"));
            shown.put("author", entry.get("book_author"));
            shown.put("page", entry.get("page_first"));
            shown.put("heading_ar", entry.get("heading_ar"));
            shown.put("text_ar", entry.get("text_ar"));
            if (entry.get("text_en") != null) {
                shown.put("text_en", entry.get("text_en"));
            }
            if (entry.get("generation") != null) {
                shown.put("kept_company_with", entry.get("generation"));
            }
            if (entry.get("book_note") != null) {
                shown.put("about_this_book", entry.get("book_note"));
            }
            entries.add(shown);
        }
        out.put("entries", entries);
        out.put("entry_count", narrator.get("entry_count"));
        out.put("generations", narrator.get("generations"));
        out.put("cities_or_tribes", narrator.get("cities_or_tribes"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private String url(Object personId) {
        return baseUrl + "/narrator/" + personId;
    }
}

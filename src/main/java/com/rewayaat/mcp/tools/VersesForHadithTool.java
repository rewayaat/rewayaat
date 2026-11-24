package com.rewayaat.mcp.tools;

import com.rewayaat.mcp.CorpusScope;
import com.rewayaat.mcp.McpTool;
import com.rewayaat.mcp.NarrationRepository;
import com.rewayaat.mcp.NarrationView;
import com.rewayaat.service.QuranicInsightsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Qurʾānic verses connected to a narration, with the tafsīr that makes the connection.
 *
 * <p>Like {@code find_similar}, these are pre-computed judgements rather than a retrieval
 * score. Every link was reviewed and carries its kind (the narration quotes the verse,
 * explains it, rules from it, or makes the same point) and a one-line reason.
 *
 * <p>The tafsīr commentary is the largest thing in a candidate and is trimmed hard here: a
 * model asking which verses relate to a narration wants the verses and one line of why, and
 * can follow the source URL for the rest. Returning every snippet in full would spend the
 * whole result budget on one narration.
 */
@Component
public class VersesForHadithTool implements McpTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 15;

    /** Enough commentary to judge the connection; the source URL carries the rest. */
    private static final int SNIPPET_CHARS = 400;

    /** Measured when the index was built; see docs/pipelines/quranic-insights.md. */
    private static final String COVERAGE =
            "COVERAGE_NARRATIONS of the 50,038 narrations have at least one.";

    private final QuranicInsightsService insights;
    private final NarrationRepository repository;
    private final String baseUrl;

    public VersesForHadithTool(QuranicInsightsService insights,
                               NarrationRepository repository,
                               @Value("${rewayaat.canonical-url:https://rewayaat.info}") String baseUrl) {
        this.insights = insights;
        this.repository = repository;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "verses_for_hadith";
    }

    @Override
    public String title() {
        return "Qur'anic verses for a narration";
    }

    @Override
    public String description() {
        return "Given a narration id, returns the Qur'anic verses connected to it - Arabic "
                + "and English text of each verse, what kind of link it is, a one-line reason "
                + "saying what connects the two, and an extract from a tafsīr on the verse with "
                + "a link to it.\n\n"
                + "kind is quoted (the narration quotes or cites the verse), explains (it "
                + "interprets or applies the verse), ruling (a legal ruling resting on the "
                + "verse) or theme (both make the same specific point). Every link was "
                + "reviewed, so a returned verse is an argued link rather than a keyword "
                + "overlap. " + COVERAGE + " An empty result means no link passed review for "
                + "this narration, not that no relationship exists.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of(
                                "type", "string",
                                "description", "Narration id, from `search` or `search_hadith`."),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Maximum verses (default " + DEFAULT_LIMIT
                                        + ", max " + MAX_LIMIT + ").")),
                "required", List.of("id"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> arguments) throws Exception {
        String id = ToolArguments.requiredString(arguments, "id");
        int limit = ToolArguments.boundedInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

        NarrationRepository.Narration narration = repository.get(id);
        if (narration == null) {
            throw new IllegalArgumentException(
                    "No narration with id '" + id + "'. " + CorpusScope.NO_RESULTS_NOTE);
        }

        Map<String, Object> overview = insights.insightOverview(id, false, true);
        List<Map<String, Object>> verses = new ArrayList<>();
        Object rawCandidates = overview.get("candidates");
        if (rawCandidates instanceof List<?> candidates) {
            for (Object item : candidates) {
                if (!(item instanceof Map<?, ?> candidate) || verses.size() >= limit) {
                    continue;
                }
                verses.add(verse(candidate));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", Map.of(
                "id", id,
                "citation", NarrationView.label(narration.source()),
                "url", NarrationView.url(id, baseUrl)));
        out.put("total_verses", overview.getOrDefault("count", 0));
        out.put("returned", verses.size());
        out.put("verses", verses);
        if (verses.isEmpty()) {
            out.put("note", "No Qur'anic link passed review for this narration. That is "
                    + "the review's verdict, not a statement that none exists.");
        }
        return out;
    }

    private Map<String, Object> verse(Map<?, ?> candidate) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("reference", NarrationView.str(candidate.get("reference")));
        row.put("verse_key", NarrationView.str(candidate.get("verse_key")));
        row.put("surah", NarrationView.str(candidate.get("surah_name_english")));
        row.put("arabic", NarrationView.str(candidate.get("text_arabic")));
        row.put("english", NarrationView.str(candidate.get("text_english")));
        String kind = NarrationView.str(candidate.get("kind"));
        if (!kind.isEmpty()) {
            row.put("kind", kind);
        }
        String reason = NarrationView.str(candidate.get("reason"));
        if (!reason.isEmpty()) {
            row.put("reason", reason);
        }

        List<Map<String, Object>> commentary = new ArrayList<>();
        if (candidate.get("tafsir_snippets") instanceof List<?> snippets) {
            for (Object item : snippets) {
                if (!(item instanceof Map<?, ?> snippet) || commentary.size() >= 2) {
                    continue;
                }
                if ("ar".equals(NarrationView.str(snippet.get("language")))) {
                    continue;
                }
                // The preview is the passage's relevant span, chosen for this narration.
                String text = NarrationView.str(snippet.get("preview"));
                if (text.isEmpty()) {
                    text = NarrationView.str(snippet.get("commentary_text"));
                }
                if (text.isEmpty()) {
                    continue;
                }
                Map<String, Object> row2 = new LinkedHashMap<>();
                row2.put("tafsir", NarrationView.str(snippet.get("tafsir_name")));
                row2.put("extract", truncate(text));
                String url = NarrationView.str(snippet.get("source_url"));
                if (!url.isEmpty()) {
                    row2.put("url", url);
                }
                commentary.add(row2);
            }
        }
        if (!commentary.isEmpty()) {
            row.put("commentary", commentary);
        }
        return row;
    }

    private static String truncate(String text) {
        if (text.length() <= SNIPPET_CHARS) {
            return text;
        }
        // Break on a space so the extract does not end mid-word.
        int cut = text.lastIndexOf(' ', SNIPPET_CHARS);
        return text.substring(0, cut < SNIPPET_CHARS / 2 ? SNIPPET_CHARS : cut) + "…";
    }
}

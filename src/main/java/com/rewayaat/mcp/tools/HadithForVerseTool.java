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
 * The narrations connected to a Qurʾānic verse - {@code verses_for_hadith} run backwards.
 *
 * <p>This is the direction with no equivalent anywhere else. Asking what a verse means will
 * find tafsīr, because tafsīr is written up and indexed; asking which narrations in these
 * eighteen books were judged to bear on it is a join over 8,556 pre-computed connections,
 * and a join is not a document any search engine has crawled.
 *
 * <p>It reports {@code total_matches} for the same reason {@code search_hadith} does. A verse
 * with forty connected narrations and a verse with two look identical if you only ever see
 * the first page.
 */
@Component
public class HadithForVerseTool implements McpTool {

    // Narrations here are returned whole, so the same sizing reasoning as search_hadith
    // applies: Arabic is far denser in tokens than its character count suggests.
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 15;

    private final NarrationRepository repository;
    private final String baseUrl;

    public HadithForVerseTool(NarrationRepository repository,
                              @Value("${rewayaat.canonical-url:https://hadith.academyofislam.com}") String baseUrl) {
        this.repository = repository;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "hadith_for_verse";
    }

    @Override
    public String title() {
        return "Narrations connected to a Qur'anic verse";
    }

    @Override
    public String description() {
        return "Given a Qur'anic verse as `surah:ayah`, returns the narrations judged to "
                + "bear on it, with `total_matches` - the true number connected to that "
                + "verse.\n\n"
                + "This is the inverse of `verses_for_hadith` and reads the same "
                + "pre-computed judgements from the other end: connections were rated in "
                + "advance and only those rated strong were kept, so a returned narration is "
                + "an argued link rather than a keyword overlap. An empty result means none "
                + "survived that filter for this verse, not that no relationship exists.\n\n"
                + CorpusScope.SCOPE_SENTENCE;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "verse", Map.of(
                                "type", "string",
                                "description", "The verse as `surah:ayah`, e.g. '2:255' for "
                                        + "Ayat al-Kursī."),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Narrations per page (default " + DEFAULT_LIMIT
                                        + ", max " + MAX_LIMIT + ")."),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "Narrations to skip, for paging through "
                                        + "`total_matches`.")),
                "required", List.of("verse"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> arguments) throws Exception {
        String verse = ToolArguments.requiredString(arguments, "verse");
        int limit = ToolArguments.boundedInt(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        int offset = ToolArguments.boundedInt(arguments, "offset", 0, 0, 9_000);

        // Resolving the verse first means an unknown reference is answered as an unknown
        // verse rather than as a verse nothing connects to - two very different findings.
        NarrationRepository.Verse resolved = repository.verse(verse);
        if (resolved == null) {
            throw new IllegalArgumentException(
                    "No verse '" + verse + "' in the Qur'an index. Give it as surah:ayah, "
                            + "e.g. '2:255'.");
        }

        NarrationRepository.Page page = repository.hadithForVerse(verse, offset, limit);

        List<Map<String, Object>> results = new ArrayList<>();
        for (NarrationRepository.Narration narration : page.narrations()) {
            results.add(NarrationView.summary(narration.id(), narration.source(), baseUrl));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> verseOut = new LinkedHashMap<>();
        verseOut.put("key", resolved.key());
        verseOut.put("arabic", resolved.arabic());
        verseOut.put("english", resolved.english());
        out.put("verse", verseOut);
        out.put("total_matches", page.total());
        out.put("offset", offset);
        out.put("returned", results.size());
        out.put("results", results);
        if (results.isEmpty()) {
            out.put("note", "No narration in these books was judged to connect strongly to "
                    + resolved.key() + ". That is the filter's verdict, not a statement "
                    + "that none exists.");
        } else if (page.total() > offset + results.size()) {
            out.put("note", "Showing " + results.size() + " of " + page.total()
                    + " narrations connected to " + resolved.key()
                    + ". Page with `offset` before describing the whole set.");
        }
        return out;
    }
}

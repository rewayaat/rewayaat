package com.rewayaat.mcp.tools;

import com.rewayaat.service.NarratorService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the tool hands a model.
 *
 * <p>The risk here is not a crash: it is an answer that reads as though the tradition agreed.
 * A verdict must always arrive under the book that gives it, a grade nobody stated must not
 * arrive at all, and where several men answer to one name the model has to be told.
 */
class LookupNarratorToolTest {

    /** Sahl b. Ziyad as the index holds him, cut to what these tests turn on. */
    private static Map<String, Object> sahl() {
        Map<String, Object> narrator = new LinkedHashMap<>();
        narrator.put("person_id", "n000515");
        narrator.put("display_ar", "سهل بن زياد");
        narrator.put("display_en", "Sahl ibn Ziyad");
        narrator.put("entry_count", 10);
        narrator.put("names_ar", List.of("سهل بن زياد", "أبو سعيد الآدمي"));
        narrator.put("verdicts", List.of(
                verdict("Rijāl al-Najāshī", "very weak", "very_weak", true, null),
                verdict("Rijāl al-Ṭūsī", "thiqa", "thiqa", true, null),
                verdict("Kitāb al-Ḍuʿafāʾ", "liar/fabricator", "kadhdhab", true,
                        "The ascription of this book to Ibn al-Ghaḍāʾirī is disputed"),
                verdict("Muʿjam Rijāl al-Ḥadīth", "unknown (majhul)", "majhul", false, null)));
        narrator.put("entries", List.of(Map.of(
                "book_title", "Rijāl al-Najāshī",
                "book_author", "Aḥmad b. ʿAlī al-Najāshī (d. 450)",
                "page_first", 183,
                "heading_ar", "سهل بن زياد",
                "text_ar", "كان ضعيفا في الحديث، غير معتمد فيه.")));
        return narrator;
    }

    private static Map<String, Object> verdict(String book, String said, String grade,
                                               boolean stated, String note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("book_title", book);
        row.put("said", said);
        row.put("grade", grade);
        row.put("stated", stated);
        row.put("pages", List.of(183));
        row.put("book_note", note);
        return row;
    }

    private static LookupNarratorTool toolReturning(Map<String, Object> narrator,
                                                    List<Map<String, Object>> matches) {
        NarratorService service = new NarratorService() {
            @Override
            public Map<String, Object> getNarrator(String personId) {
                return narrator;
            }

            @Override
            public Map<String, Object> search(String query, int page, int pageSize) {
                return Map.of("total", matches.size(), "narrators", matches);
            }
        };
        return new LookupNarratorTool(service, "https://example.test");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyVerdictArrivesUnderTheBookThatGivesIt() throws Exception {
        LookupNarratorTool tool = toolReturning(sahl(), List.of(Map.of("person_id", "n000515")));

        Map<String, Object> answer = tool.call(Map.of("name", "Sahl b. Ziyad"));
        Map<String, Object> narrator = (Map<String, Object>) answer.get("narrator");
        List<Map<String, Object>> verdicts = (List<Map<String, Object>>) narrator.get("verdicts");

        for (Map<String, Object> said : verdicts) {
            assertFalse(String.valueOf(said.get("book")).isBlank(),
                    "A grade with no book beside it reads as the tradition's own verdict.");
        }
        assertEquals(List.of("very weak", "thiqa", "liar/fabricator"),
                verdicts.stream().map(v -> v.get("verdict")).toList(),
                "The books' disagreement is the answer, so all of it is returned.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aGradeNoBookStatedIsNotReturned() throws Exception {
        LookupNarratorTool tool = toolReturning(sahl(), List.of(Map.of("person_id", "n000515")));

        Map<String, Object> narrator = (Map<String, Object>) tool
                .call(Map.of("id", "n000515")).get("narrator");
        List<Map<String, Object>> verdicts = (List<Map<String, Object>>) narrator.get("verdicts");

        assertTrue(verdicts.stream().noneMatch(v -> "majhul".equals(v.get("grade"))),
                "«unknown (majhul)» was read from a book's silence; returning it as Khoei's "
                        + "verdict would put words in his mouth.");
    }

    @Test
    void ibnAlGhadairisBookCarriesTheDoubtAboutIt() throws Exception {
        LookupNarratorTool tool = toolReturning(sahl(), List.of(Map.of("person_id", "n000515")));

        String answer = tool.call(Map.of("id", "n000515")).toString();

        assertTrue(answer.contains("disputed"),
                "Ibn al-Ghaḍāʾirī's verdicts are the harshest in the tradition and the "
                        + "ascription of his book is doubted; the doubt travels with them.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void severalMenOfOneNameAreAllNamed() throws Exception {
        LookupNarratorTool tool = toolReturning(sahl(), List.of(
                Map.of("person_id", "n000515", "display_ar", "سهل بن زياد", "entry_count", 10),
                Map.of("person_id", "n008501", "display_ar", "سهل بن زياد السندي", "entry_count", 1)));

        Map<String, Object> answer = tool.call(Map.of("name", "سهل بن زياد"));

        List<Map<String, Object>> others =
                (List<Map<String, Object>>) answer.get("others_of_similar_name");
        assertEquals(1, others.size());
        assertEquals("https://example.test/narrator/n008501", others.get(0).get("url"));
        assertTrue(String.valueOf(answer.get("note")).contains("Several narrators"),
                "A model that cannot see the homonyms will attach a verdict to the wrong man.");
    }

    @Test
    void anAbsentNameSaysAbsentFromTheseBooks() throws Exception {
        LookupNarratorTool tool = toolReturning(null, List.of());

        Map<String, Object> answer = tool.call(Map.of("name", "لا أحد"));

        assertNull(answer.get("narrator"));
        assertTrue(String.valueOf(answer.get("note")).contains("not evidence that no such"),
                "Silence in eight books is not silence in the tradition.");
    }

    @Test
    void anEmptyCallSaysWhatItNeeds() {
        LookupNarratorTool tool = toolReturning(sahl(), List.of());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tool.call(Map.of()));

        assertTrue(thrown.getMessage().contains("name"));
    }
}

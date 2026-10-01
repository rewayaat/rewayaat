package com.rewayaat.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the content that is translated in place, as opposed to in the message catalogue.
 *
 * <p>There are two homes for anything a reader sees, and only two. UI and system strings
 * live in {@code messages.properties} with a twin in {@code messages_ar.properties};
 * {@link MessageCatalogueTest} guards those. Content — tag names, book blurbs, the
 * updates feed — carries its translation inside the same record as the English, either
 * as an {@code ar} field beside an {@code en} one or as a {@code field_ar} beside
 * {@code field}. This guards those.
 *
 * <p>The point of both is the same: adding an English thing should make the missing
 * Arabic one impossible to forget, rather than something you find out about months later
 * by reading the Arabic site. Every gap found so far was found that way — the tags had
 * carried Arabic names all along and the server had simply never read them.
 */
class TranslatedDataTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode read(String path) throws IOException {
        return JSON.readTree(Files.readString(Path.of(path)));
    }

    @Test
    @DisplayName("every topic tag has an Arabic name beside its English one")
    void everyTagIsTranslated() throws IOException {
        List<String> missing = new ArrayList<>();
        for (JsonNode tag : read("src/main/resources/static/taxonomy.json")) {
            String slug = tag.path("slug").asText("");
            if (slug.isBlank()) {
                continue;
            }
            if (tag.path("ar").asText("").isBlank()) {
                missing.add(slug);
            }
        }
        assertTrue(missing.isEmpty(),
                "these tags have no Arabic name: " + missing
                        + "\nAdd an \"ar\" beside the \"en\" in static/taxonomy.json. The tag is "
                        + "shown by name on every card and in the filter bar, in both languages.");
    }

    @Test
    @DisplayName("every book has an Arabic name and an Arabic blurb")
    void everyBookIsIntroducedInBothLanguages() throws IOException {
        List<String> missing = new ArrayList<>();

        // {"Al-Kāfi": {"ar": "الكافي"}} — keyed by the English name, the translation inside.
        JsonNode names = read("src/main/resources/i18n/book_names_mapping.json");
        names.fieldNames().forEachRemaining(english -> {
            if (names.path(english).path("ar").asText("").isBlank()) {
                missing.add("name: " + english);
            }
        });

        for (JsonNode entry : read("src/main/resources/static/book_blurbs.json")) {
            if (!entry.has("blurb")) {
                continue;
            }
            String name = entry.path("slug").asText(entry.path("book").asText("?"));
            if (entry.path("blurb_ar").asText("").isBlank()) {
                missing.add("blurb: " + name);
            }
        }

        assertTrue(missing.isEmpty(), "untranslated book content: " + missing);
    }

    @Test
    @DisplayName("every book page has an About section, and it names its own page")
    void everyBookHasABlurbKeyedToItsPage() throws IOException {
        // book_summaries.json is keyed by page slug and covers all eighteen books, so it
        // is the list of pages that exist. Two blurbs used to key themselves to pages that
        // did not, by slugifying the book name the entry carried and guessing wrong; both
        // existed in both languages and neither had ever rendered. An entry now names its
        // slug outright, and this checks the name against a real page.
        Set<String> pages = new TreeSet<>();
        read("src/main/resources/static/book_summaries.json").fieldNames()
                .forEachRemaining(slug -> {
                    if (!slug.startsWith("_")) {
                        pages.add(slug);
                    }
                });
        assertFalse(pages.isEmpty(), "no book pages found, so this checked nothing");

        Set<String> covered = new TreeSet<>();
        List<String> wrong = new ArrayList<>();
        for (JsonNode entry : read("src/main/resources/static/book_blurbs.json")) {
            String slug = entry.path("slug").asText("");
            if (slug.isBlank()) {
                // The three entries describing books this corpus does not hold.
                continue;
            }
            if (!pages.contains(slug)) {
                wrong.add(slug);
            }
            covered.add(slug);
        }
        assertTrue(wrong.isEmpty(),
                "these blurbs name a page that does not exist, so they render nowhere: "
                        + wrong + "\nThe slug must be one of " + pages);

        Set<String> uncovered = new TreeSet<>(pages);
        uncovered.removeAll(covered);
        assertTrue(uncovered.isEmpty(),
                "these book pages have no About section: " + uncovered
                        + "\nAdd an entry to book_blurbs.json with \"slug\", \"blurb\" and "
                        + "\"blurb_ar\".");
    }

    @Test
    @DisplayName("every field of an updates entry that has English has Arabic too")
    void everyUpdateIsTranslated() throws IOException {
        // The feed is read through pick(), which swaps in field_ar when the page is
        // Arabic and silently shows the English when there is none — so a missing
        // translation here is invisible until somebody reads the page.
        JsonNode feed = read("src/main/resources/static/recent_updates.json");
        JsonNode entries = feed.isArray() ? feed
                : feed.has("updates") ? feed.get("updates") : feed.get("entries");

        List<String> missing = new ArrayList<>();
        int index = 0;
        for (JsonNode entry : entries) {
            for (String field : new String[]{"title", "summary", "videoTitle"}) {
                if (!entry.path(field).asText("").isBlank()
                        && entry.path(field + "_ar").asText("").isBlank()) {
                    missing.add("entry " + index + ": " + field);
                }
            }
            index++;
        }
        assertTrue(missing.isEmpty(),
                "updates entries missing Arabic: " + missing
                        + "\nAdd the _ar field beside the English one in recent_updates.json.");
    }

    @Test
    @DisplayName("the announcement bar is written in both languages")
    void theAnnouncementIsTranslated() throws IOException {
        JsonNode announcement = read("src/main/resources/static/announcement.json");
        List<String> missing = new ArrayList<>();
        for (String field : new String[]{"label", "text"}) {
            if (!announcement.path(field).asText("").isBlank()
                    && announcement.path(field + "_ar").asText("").isBlank()) {
                missing.add(field);
            }
        }
        assertTrue(missing.isEmpty(), "announcement fields missing Arabic: " + missing);
    }

    @Test
    @DisplayName("every book's hero introduction has an Arabic twin")
    void everyBookSummaryIsTranslated() throws IOException {
        // The intro at the top of a book page. It was English only, and the Arabic hero
        // fell back to the first paragraph of the Arabic blurb — which five of eighteen
        // books have, so thirteen Arabic book pages opened blank against an English page
        // that opened with a paragraph. Nothing said so from the English site.
        assertBothLanguages("src/main/resources/static/book_summaries.json", "book summaries");
    }

    @Test
    @DisplayName("every volume and part summary has an Arabic twin")
    void everySectionSummaryIsTranslated() throws IOException {
        // These sit in the hero of a volume or part page. They were English only, and the
        // Arabic page showed nothing at all rather than English prose under an Arabic
        // heading — which was right, and also left the Arabic hubs looking emptier than
        // the English ones. Now that they are translated, the thing to guard is that a
        // new one cannot arrive with only half of it written.
        assertBothLanguages("src/main/resources/static/section_summaries.json",
                "section summaries");
    }

    /** Both halves present in a {@code {key: {en, ar}}} file, for every key it holds. */
    private static void assertBothLanguages(String path, String what) throws IOException {
        JsonNode summaries = read(path);
        List<String> missing = new ArrayList<>();
        int seen = 0;
        for (String key : (Iterable<String>) summaries::fieldNames) {
            if (key.startsWith("_")) {
                continue;
            }
            seen++;
            JsonNode entry = summaries.path(key);
            if (!entry.isObject()) {
                missing.add(key + " (still a bare string, so it has no Arabic at all)");
            } else if (entry.path("en").asText("").isBlank()) {
                missing.add(key + " (no English)");
            } else if (entry.path("ar").asText("").isBlank()) {
                missing.add(key);
            }
        }
        // Without this the check passes just as well against a file that failed to parse.
        assertTrue(seen > 0, path + " held no entries, so this checked nothing");
        assertTrue(missing.isEmpty(),
                what + " with no Arabic: " + missing
                        + "\nEach entry is {\"en\": ..., \"ar\": ...}; the page shows nothing "
                        + "in Arabic without the second half.");
    }
}

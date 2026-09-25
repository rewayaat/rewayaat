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

        JsonNode blurbs = read("src/main/resources/static/book_blurbs.json");
        blurbs.fieldNames().forEachRemaining(slug -> {
            JsonNode entry = blurbs.path(slug);
            if (!entry.has("blurb")) {
                return;
            }
            if (entry.path("blurb_ar").asText("").isBlank()) {
                missing.add("blurb: " + slug);
            }
        });

        assertTrue(missing.isEmpty(), "untranslated book content: " + missing);
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
}

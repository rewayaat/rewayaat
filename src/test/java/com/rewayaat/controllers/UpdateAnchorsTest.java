package com.rewayaat.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every update on the updates page can be linked to, and the link keeps working.
 *
 * <p>The page is one long timeline built in the browser from {@code recent_updates.json},
 * and an entry is reachable only if it carries an {@code id}: the heading renders a
 * permalink from it, the script finishes the scroll the browser abandons, and the
 * fragment is what somebody pastes into a message. One of the eleven entries had an id
 * and the rest had none, so ten of them could be read but not sent.
 *
 * <p>The ids are chosen rather than derived. A slug made from the title would change
 * when the title is reworded and would differ between the two languages, and a link that
 * used to work is worse than one that never existed - so they are written into the file
 * and checked here instead.
 */
class UpdateAnchorsTest {

    private static final Path FEED = Path.of("src/main/resources/static/recent_updates.json");

    /** A fragment that survives being pasted into a message, a Markdown link or a shell. */
    private static final Pattern URL_SAFE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    @Test
    @DisplayName("every update carries a unique, URL-safe id")
    void everyUpdateIsAddressable() throws IOException {
        JsonNode entries = new ObjectMapper().readTree(Files.readString(FEED));
        assertTrue(entries.isArray() && !entries.isEmpty(), "the updates feed is empty");

        List<String> unusable = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        int index = 0;
        for (JsonNode entry : entries) {
            String id = entry.path("id").asText("");
            String title = entry.path("title").asText("entry " + index);
            if (id.isBlank() || !URL_SAFE.matcher(id).matches()) {
                unusable.add(title + "  ->  " + (id.isBlank() ? "(none)" : id));
            } else if (!seen.add(id)) {
                duplicated.add(id);
            }
            // A guide lives inside an entry but shares the document's one id namespace,
            // and both are link targets, so a collision silently sends one link to the
            // other's content.
            for (JsonNode guide : entry.path("guides")) {
                String guideId = guide.path("id").asText("");
                if (!guideId.isBlank() && !seen.add(guideId)) {
                    duplicated.add(guideId);
                }
            }
            index++;
        }

        assertTrue(unusable.isEmpty(),
                "these updates have no usable id, so nothing can link to them:\n  "
                        + String.join("\n  ", unusable)
                        + "\nAdd \"id\": \"a-short-slug\" to the entry in recent_updates.json. "
                        + "Choose it once and leave it alone; it is a public URL.");
        assertEquals(List.of(), duplicated,
                "two link targets share an id, so one of them is unreachable: " + duplicated);
    }

    @Test
    @DisplayName("the page renders a permalink from the id it is given")
    void theHeadingCarriesTheLink() throws IOException {
        // The template builds the timeline in a script, so there is no rendered page to
        // assert against without a browser. This checks the one wiring that makes the
        // ids above worth having: the heading is built with permalinkHtml, and
        // permalinkHtml writes an href from the id.
        String page = Files.readString(
                Path.of("src/main/resources/templates/updates.html"));

        assertTrue(page.contains("permalinkHtml(u.id)"),
                "the update heading no longer renders a permalink, so the ids in "
                        + "recent_updates.json address nothing a reader can see");
        assertTrue(page.contains("href=\"#' + id + '\""),
                "permalinkHtml no longer builds an href from the id");
        assertTrue(page.contains("scroll-margin-top"),
                "arriving on a fragment puts the heading under the sticky navbar without "
                        + "scroll-margin-top");
    }
}

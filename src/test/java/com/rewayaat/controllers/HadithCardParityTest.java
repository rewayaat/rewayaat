package com.rewayaat.controllers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the two hadith-card renderers from drifting apart.
 *
 * <p>The card is painted twice: by Vue from JSON on the search page (index.html), and by
 * the server from Elasticsearch on a chapter page (fragments/hadith-card.html). That
 * duplication is deliberate — the search page needs per-card client state for its
 * Related and Tafsir panels, and the chapter page must be readable by a crawler that
 * runs no scripts — but it is exactly the kind of duplication that rots.
 *
 * <p>Visual drift is already impossible: both use the same class names, so they share one
 * stylesheet. This covers the other half — structural drift. If the Vue card gains,
 * loses or renames a structural class, the server card has to keep up or this fails.
 *
 * <p>If you are here because this test broke: either mirror the change in the other
 * template, or, if the class genuinely belongs to only one of them, add it to
 * {@link #CLIENT_ONLY}.
 */
class HadithCardParityTest {

    /**
     * The search card is spread across two files: the outer markup sits in index.html,
     * while the metadata panel is the hadith-details Vue component. Both count.
     */
    private static final List<Path> SEARCH_CARD = List.of(
            Path.of("src/main/resources/templates/index.html"),
            Path.of("src/main/resources/static/js/vue-components.js"));

    private static final Path SERVER_CARD =
            Path.of("src/main/resources/templates/fragments/hadith-card.html");

    /** The classes that carry the card's structure, as opposed to its state or theming. */
    private static final List<String> STRUCTURAL = List.of(
            "hadith-card",
            "hadith-card__result-num",
            "card-body",
            "hadith-card__layout",
            "hadith-sidecar",
            "hadith-card__meta",
            "hadith-sidecar__rail",
            "hadith-sidecar__rail-btn",
            "hadith-sidecar__rail-indicator",
            "hadith-sidecar__rail-label",
            "hadith-sidecar__rail-icon",
            "hadith-sidecar__panel",
            "hadith-sidecar__panel-body",
            "hadith-details",
            "meta-item",
            "meta-icon",
            "meta-label",
            "meta-text",
            "hadith-card__resizer",
            "hadith-card__main",
            "hadith-content",
            "hadith-card__ornament",
            "hadith-card__ornament-line",
            "hadith-card__ornament-mark",
            "hadith-reading-scroll",
            "hadith-reading-row",
            "hadith-text-block",
            "hadith-text-block--primary",
            "hadith-text-block--arabic",
            "hadith-chain",
            "hadith-english",
            "hadith-arabic",
            "arabic-text",
            "hadith-notes",
            "hadith-notes__head",
            "hadith-notes__label",
            "hadith-notes__body",
            "hadith-card__footer",
            "topic-tag-pills",
            "hadith-card__tags",
            "topic-tag-pills__label",
            "topic-pill",
            "hadith-card__actions",
            "icon-action");

    /**
     * Classes the search card carries that the server card deliberately does not: they
     * belong to behaviour that needs a live Vue instance, and the server card links to
     * the narration's own page for that material instead.
     */
    private static final Set<String> CLIENT_ONLY = Set.of(
            "hadith-sidecar__rail-badge",     // live counts, fetched per card
            "hadith-reading-toggle",          // show-full-hadith clamp toggle
            "hadith-notes__toggle",           // show-more on long notes
            "hadith-tags-toggle",             // mobile tag overflow
            "hadith-copy-dropdown",           // copy menu
            "hadith-inline-context");         // the Related / Tafsir inline panels

    @Test
    void serverCardCarriesEveryStructuralClassTheSearchCardDoes() throws IOException {
        String index = readSearchCard();
        String card = read(SERVER_CARD);

        Set<String> missing = new LinkedHashSet<>();
        for (String cls : STRUCTURAL) {
            if (containsClass(index, cls) && !containsClass(card, cls) && !CLIENT_ONLY.contains(cls)) {
                missing.add(cls);
            }
        }

        assertTrue(missing.isEmpty(),
                "The search card uses these structural classes and the server card does not: " + missing
                        + "\nMirror them in fragments/hadith-card.html, or add them to CLIENT_ONLY "
                        + "if they genuinely belong only to the interactive card.");
    }

    /**
     * The reverse direction. A class only the server card has is usually a sign the two
     * have been edited independently, which is how they start to look different.
     */
    @Test
    void serverCardInventsNoStructuralClassesOfItsOwn() throws IOException {
        String index = readSearchCard();
        String card = read(SERVER_CARD);

        Set<String> extra = new LinkedHashSet<>();
        for (String cls : STRUCTURAL) {
            if (containsClass(card, cls) && !containsClass(index, cls)) {
                extra.add(cls);
            }
        }

        assertTrue(extra.isEmpty(),
                "The server card uses structural classes the search card does not: " + extra);
    }

    /** The classes the whole scheme rests on; if these vanish the sharing is over. */
    @Test
    void bothCardsShareTheLoadBearingClasses() throws IOException {
        String index = readSearchCard();
        String card = read(SERVER_CARD);

        for (String cls : List.of("hadith-card", "hadith-sidecar", "hadith-english", "hadith-arabic")) {
            assertTrue(containsClass(index, cls), "search card lost " + cls);
            assertTrue(containsClass(card, cls), "server card lost " + cls);
        }
    }

    private static boolean containsClass(String html, String cls) {
        Matcher m = Pattern.compile("class=\"([^\"]*)\"").matcher(html);
        while (m.find()) {
            for (String token : m.group(1).trim().split("\\s+")) {
                if (token.equals(cls)) {
                    return true;
                }
            }
        }
        // Thymeleaf and Vue also add classes through th:classappend / v-bind:class.
        return html.contains("'" + cls + "'") || html.contains("\"" + cls + "\"");
    }

    private static String readSearchCard() throws IOException {
        StringBuilder combined = new StringBuilder();
        for (Path path : SEARCH_CARD) {
            combined.append(read(path)).append('\n');
        }
        return combined.toString();
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}

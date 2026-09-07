package com.rewayaat.controllers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every page in the book tree should preview as itself.
 *
 * <p>fragments/site.html falls back to the site mark when a page sets no
 * {@code shareImageUrl}, and the fallback is silent — the page renders, the tag is
 * present, and the image is simply wrong. That is how 8,035 of the 8,054 pages in
 * sitemap-books.xml came to share one image: only the book page set it, and nothing said
 * so. This asserts that a page setting a canonical URL also names its own card.
 */
class ShareImageCoverageTest {

    private static final Path CONTROLLER =
            Path.of("src/main/java/com/rewayaat/controllers/BookPageController.java");

    /**
     * The books index is a list of books, not a thing with a text of its own, so the site
     * mark is the honest image for it.
     */
    private static final Set<String> SITE_MARK_IS_CORRECT = Set.of("/books");

    @Test
    void everyBookTreePageAdvertisesACardOfItsOwn() throws IOException {
        String source = Files.readString(CONTROLLER, StandardCharsets.UTF_8);

        // Each page method sets exactly one canonicalUrl; the card should follow it.
        Matcher m = Pattern.compile(
                "model\\.addAttribute\\(\"canonicalUrl\",(.*?)\\);", Pattern.DOTALL).matcher(source);
        Set<String> missing = new LinkedHashSet<>();
        int pages = 0;
        while (m.find()) {
            pages++;
            String canonical = m.group(1).trim();
            if (SITE_MARK_IS_CORRECT.stream().anyMatch(canonical::contains)) {
                continue;
            }
            // The next 200 characters cover the rest of that page's model attributes.
            String following = source.substring(m.end(),
                    Math.min(source.length(), m.end() + 200));
            if (!following.contains("shareImageUrl")) {
                missing.add(canonical.replaceAll("\\s+", " "));
            }
        }

        assertTrue(pages >= 4, "expected the book, volume, part and chapter pages; found " + pages);
        assertTrue(missing.isEmpty(),
                "These pages set a canonical URL but no shareImageUrl, so fragments/site.html "
                        + "will quietly preview them with the site mark instead of their own "
                        + "card: " + missing);
    }

    /** The routes those pages point at have to exist, or the tag names a 404. */
    @Test
    void theCardRoutesThosePagesNameAllExist() throws IOException {
        String routes = Files.readString(
                Path.of("src/main/java/com/rewayaat/controllers/ShareCardController.java"),
                StandardCharsets.UTF_8);

        Set<String> required = Set.of(
                "/books/{bookSlug}/card.png",
                "/books/{bookSlug}/volume/{volume}/card.png",
                "/books/{bookSlug}/part/{partSlug}/card.png",
                "/books/{bookSlug}/{chapterSlug}/card.png",
                "/hadith/{id}/card.png");

        Set<String> absent = new LinkedHashSet<>();
        for (String route : required) {
            if (!routes.contains(route)) {
                absent.add(route);
            }
        }
        assertFalse(routes.isBlank(), "could not read ShareCardController");
        assertTrue(absent.isEmpty(), "ShareCardController is missing these card routes: " + absent);
    }
}

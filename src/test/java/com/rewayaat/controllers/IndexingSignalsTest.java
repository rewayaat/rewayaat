package com.rewayaat.controllers;

import com.rewayaat.service.BookCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fixes for the Search Console page-indexing report.
 *
 * <p>Most of these read source rather than rendering pages, as {@link ShareImageCoverageTest}
 * does. The chapter page's narration query filters on {@code chapter.keyword}, which the
 * integration test index does not map, and what matters here is wiring between files — a
 * predicate two controllers must share, an attribute a template must read — that a rendered
 * page would only show indirectly.
 */
class IndexingSignalsTest {

    private static final Path BOOK_PAGES =
            Path.of("src/main/java/com/rewayaat/controllers/BookPageController.java");
    private static final Path SITEMAP =
            Path.of("src/main/java/com/rewayaat/controllers/SitemapController.java");

    private static BookCatalog.Chapter chapterOf(long narrations) {
        return new BookCatalog.Chapter("Al-Kāfi", "al-kafi", "honey", "Honey",
                "6", "The Book of Food", "", narrations);
    }

    @Test
    void onlyAChapterOfExactlyOneNarrationDuplicatesIt() {
        assertTrue(chapterOf(1).holdsSingleNarration());
        assertFalse(chapterOf(2).holdsSingleNarration());
        // A chapter the catalog counted as empty duplicates nothing.
        assertFalse(chapterOf(0).holdsSingleNarration());
    }

    /**
     * Over half the chapters hold a single narration. If the sitemap and the canonical each
     * decided that for themselves, a chapter could be listed in the sitemap while its
     * canonical pointed at the narration — asking Google to index the duplicate.
     */
    @Test
    void theSitemapAndTheCanonicalAskTheSameQuestion() throws IOException {
        String sitemap = read(SITEMAP);
        String pages = read(BOOK_PAGES);

        assertTrue(sitemap.contains("chapter.holdsSingleNarration()"),
                "booksSitemap no longer excludes single-narration chapters by the shared predicate");
        assertTrue(methodBody(pages, "chapterPage").contains("chapter.holdsSingleNarration()"),
                "chapterPage no longer gates its canonical on the shared predicate");
        assertFalse(sitemap.contains("count() == 1"),
                "the sitemap is deciding single-narration chapters by its own count again");
    }

    /**
     * volume.html is shared by the volume and part pages. When a volume splits into parts its
     * chapter list goes to the parts, and a hero that counted that list announced "0
     * chapters" on a volume holding 1,448 narrations.
     */
    @Test
    void theHubHeroCountsChaptersFromTheRealCount() throws IOException {
        String volume = read(Path.of("src/main/resources/templates/volume.html"));
        Matcher hero = Pattern.compile("<div class=\"hub-hero__stats\">(.*?)</div>", Pattern.DOTALL)
                .matcher(volume);
        assertTrue(hero.find(), "could not find the hub hero stats in volume.html");
        String stats = hero.group(1);
        assertTrue(stats.contains("chapterCount"),
                "the hero's chapter count no longer reads chapterCount");
        assertFalse(stats.contains("#lists.size(chapters)"),
                "the hero counts the chapter list again, which is empty on a volume with parts");

        String pages = read(BOOK_PAGES);
        for (String route : new String[]{"volumePage", "partPage"}) {
            assertTrue(methodBody(pages, route).contains("model.addAttribute(\"chapterCount\""),
                    route + " renders volume.html without setting chapterCount");
        }
    }

    /**
     * {@code noindex, follow} keeps a {@code ?tag=} view out of the index but not out of the
     * crawl. About 19,600 of them exist, and Google had already crawled 1,731.
     */
    @Test
    void tagFilterLinksAreNofollow() throws IOException {
        for (Path template : new Path[]{
                Path.of("src/main/resources/templates/chapter.html"),
                Path.of("src/main/resources/templates/fragments/hadith-card.html")}) {
            Matcher pill = Pattern.compile("<a\\b[^>]*\\btopic-pill\\b[^>]*>").matcher(read(template));
            int pills = 0;
            while (pill.find()) {
                pills++;
                String tag = pill.group();
                assertTrue(tag.contains("rel=\"nofollow\""),
                        () -> template.getFileName() + " has a tag pill without rel=\"nofollow\": " + tag);
            }
            assertTrue(pills > 0, "found no tag pills in " + template.getFileName()
                    + ", so this test is not checking anything");
        }
    }

    /** One handler's source, from its name to the next route mapping. */
    private static String methodBody(String source, String name) {
        int start = source.indexOf(" " + name + "(");
        assertTrue(start >= 0, "could not find " + name + " in BookPageController");
        int end = source.indexOf("@GetMapping", start);
        return source.substring(start, end < 0 ? source.length() : end);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}

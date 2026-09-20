package com.rewayaat.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.service.BookCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The descriptive blurbs already shipped for the search UI, keyed so a book page can use them.
 *
 * <p>{@code book_blurbs.json} spells its books differently from Elasticsearch — "AL-KAFI"
 * against "Al-Kāfi", "Nahj Al-Balagha" against "Nahj al-Balāgha" — so the two are matched on
 * the same slug the URLs use, which folds away case and the transliteration diacritics.
 * A book with no blurb simply renders without one.
 */
class BookBlurbs {

    private static final Logger LOGGER = LoggerFactory.getLogger(BookBlurbs.class);

    private final Map<String, String> bySlug;
    private final Map<String, String> bySlugAr;
    private final Map<String, String> summaries;
    private final Map<String, String> sectionSummaries;

    BookBlurbs() {
        this.bySlug = load("blurb");
        this.bySlugAr = load("blurb_ar");
        this.summaries = loadSummaries("static/book_summaries.json");
        this.sectionSummaries = loadSummaries("static/section_summaries.json");
    }

    String forSlug(String slug) {
        return bySlug.get(slug);
    }

    /**
     * The blurb in the page's language, falling back to English.
     *
     * <p>All ten books carry a {@code blurb_ar}. The fallback is for a book added later
     * without one: an English paragraph on an Arabic page is worse than an Arabic one and
     * better than a blank hero.
     */
    String forSlug(String slug, boolean arabic) {
        if (arabic) {
            String arabicBlurb = bySlugAr.get(slug);
            if (arabicBlurb != null && !arabicBlurb.isBlank()) {
                return arabicBlurb;
            }
        }
        return bySlug.get(slug);
    }

    /**
     * The one-paragraph introduction shown at the top of a book page.
     *
     * <p>A separate file, keyed by the page's own slug. The long blurbs are keyed by
     * slugifying the book name each entry carries, which is a guess at the catalogue's
     * slug rather than the slug itself - and it guessed wrong for Al-Tawḥīd, whose entry
     * is named "Kitab Al-Tawhid" and so keyed itself to a page that does not exist. Two
     * books had blurbs that never rendered because of it.
     */
    String summaryForSlug(String slug) {
        return summaries.get(slug);
    }

    /**
     * A plain-text opening paragraph for the hero, in the page's language.
     *
     * <p>book_summaries.json is English only. The Arabic blurb is a written introduction
     * in its own right, so the Arabic hero draws its first paragraph from there instead of
     * showing English prose under an Arabic heading.
     *
     * <p>The blurb is HTML - the About section renders it with {@code th:utext} - while the
     * hero is plain text in a {@code th:text}. Handing the markup straight over printed the
     * tags to the reader, so the paragraph is parsed out and unwrapped here.
     */
    String summaryForSlug(String slug, boolean arabic) {
        if (!arabic) {
            return summaries.get(slug);
        }
        String arabicBlurb = bySlugAr.get(slug);
        if (arabicBlurb == null || arabicBlurb.isBlank()) {
            return summaries.get(slug);
        }
        Element paragraph = Jsoup.parseBodyFragment(arabicBlurb).body().selectFirst("p");
        if (paragraph == null) {
            return null;
        }
        String text = paragraph.text().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * The note for a volume or part page, keyed by its path without the leading slash.
     *
     * <p>Deliberately partial, and absence is the normal case. A note is only worth
     * carrying where the title does not already say the whole thing: Al-Kāfi's volumes map
     * onto the Uṣūl/Furūʿ/Rawḍa division and its parts are the classical kitāb headings,
     * whereas Al-Khiṣāl's parts are titled "On Three-Numbered Characteristics" and have
     * nothing left to explain. The hero falls back to its centred layout when there is
     * nothing here, exactly as a book with no summary does.
     */
    String sectionSummaryForPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return sectionSummaries.get(path.startsWith("/") ? path.substring(1) : path);
    }

    private static Map<String, String> loadSummaries(String resource) {
        Map<String, String> loaded = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            root.fields().forEachRemaining(entry -> {
                // A leading underscore marks the file's own note to the reader, not a book.
                if (!entry.getKey().startsWith("_") && !entry.getValue().asText("").isBlank()) {
                    loaded.put(entry.getKey(), entry.getValue().asText());
                }
            });
        } catch (Exception e) {
            LOGGER.warn("Could not read {}; those pages will render without an introduction",
                    resource, e);
        }
        return Map.copyOf(loaded);
    }

    private static Map<String, String> load(String field) {
        Map<String, String> loaded = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource("static/book_blurbs.json").getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode entry : root) {
                String book = entry.path("book").asText("");
                String blurb = entry.path(field).asText("");
                if (!book.isBlank() && !blurb.isBlank()) {
                    loaded.put(BookCatalog.slugify(book), blurb);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read book_blurbs.json; book pages will render without blurbs", e);
        }
        return Map.copyOf(loaded);
    }
}

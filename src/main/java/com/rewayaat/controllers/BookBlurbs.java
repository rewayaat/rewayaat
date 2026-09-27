package com.rewayaat.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.service.BookCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final Map<String, String> summariesAr;
    private final Map<String, String> sectionSummaries;
    private final Map<String, String> sectionSummariesAr;

    BookBlurbs() {
        this.bySlug = load("blurb");
        this.bySlugAr = load("blurb_ar");
        this.summaries = loadSummaries("static/book_summaries.json");
        this.summariesAr = loadSummaries("static/book_summaries.json", "ar");
        this.sectionSummaries = loadSummaries("static/section_summaries.json");
        this.sectionSummariesAr = loadSummaries("static/section_summaries.json", "ar");
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
     * <p>All eighteen books carry both halves now, and the Arabic is written rather than
     * salvaged. It used to be taken from the first paragraph of the Arabic blurb, which
     * only five books have — so thirteen Arabic book pages opened with nothing at all
     * while their English twins opened with a paragraph. That is invisible from the
     * English site, which is why it survived as long as it did.
     *
     * <p>Still no English fallback. The hero collapses to its centred layout when there is
     * no intro, whereas an English paragraph under an Arabic heading is mixed-language body
     * text on a page whose whole purpose is to rank for Arabic queries.
     */
    String summaryForSlug(String slug, boolean arabic) {
        return arabic ? summariesAr.get(slug) : summaries.get(slug);
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
    /**
     * A volume or part's opening note, in the reader's language.
     *
     * <p>These were English only, and the Arabic page showed nothing rather than English
     * prose under an Arabic heading — right while nothing was translated, but it left the
     * Arabic hubs looking emptier than the English ones for no reason a reader could see.
     * All 47 carry an Arabic twin now. A page with no Arabic note still shows none.
     */
    String sectionSummaryForPath(String path, boolean arabic) {
        if (!arabic) {
            return sectionSummaryForPath(path);
        }
        return path == null || path.isBlank() ? null
                : sectionSummariesAr.get(path.startsWith("/") ? path.substring(1) : path);
    }

    String sectionSummaryForPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return sectionSummaries.get(path.startsWith("/") ? path.substring(1) : path);
    }

    private static Map<String, String> loadSummaries(String resource) {
        return loadSummaries(resource, "en");
    }

    /**
     * Summaries for one language.
     *
     * <p>An entry is either a bare string, which is English, or an object carrying both
     * languages as en and ar — the shape taxonomy.json uses, so the translation sits
     * beside the English rather than in a file of its own. Both files carry both halves;
     * the bare-string form is still read because it is what a hand-added entry looks like
     * before anyone translates it, and TranslatedDataTest is what catches that.
     */
    private static Map<String, String> loadSummaries(String resource, String language) {
        Map<String, String> loaded = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            root.fields().forEachRemaining(entry -> {
                // A leading underscore marks the file's own note to the reader, not a book.
                if (entry.getKey().startsWith("_")) {
                    return;
                }
                JsonNode value = entry.getValue();
                String text = value.isObject() ? value.path(language).asText("") : value.asText("");
                if (!text.isBlank()) {
                    loaded.put(entry.getKey(), text);
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

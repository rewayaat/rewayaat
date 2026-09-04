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

    BookBlurbs() {
        this.bySlug = load();
    }

    String forSlug(String slug) {
        return bySlug.get(slug);
    }

    private static Map<String, String> load() {
        Map<String, String> loaded = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource("static/book_blurbs.json").getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode entry : root) {
                String book = entry.path("book").asText("");
                String blurb = entry.path("blurb").asText("");
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

package com.rewayaat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The Arabic name of a book, chapter, section or part, looked up by its English one.
 *
 * <p>These are closed sets - 18 books, 7,708 chapter titles, 596 sections, 145 parts -
 * translated once and shipped as resources. The catalogue is built from a composite
 * aggregation over the English keyword fields, so the English name is the key that is
 * already in hand at render time and no second aggregation is needed to find the Arabic
 * one. Adding {@code chapter_ar} as a composite source would also split a chapter in two
 * wherever the Arabic is missing, which for chapters is 12% of them.
 *
 * <p>The same translations are written into Elasticsearch by
 * {@code scripts/i18n/translate_tier1.py}, from these very files, but for a different
 * purpose: there they make Arabic titles <em>searchable</em>
 * ({@code chapter_ar.text} is in {@code SEARCHABLE_FIELDS}). Rendering reads them from
 * here, which is why a missing index field never leaves a hub page blank.
 *
 * <p>A missing translation returns null and every caller falls back to the English name.
 * Arabic coverage is deliberately partial - a page half in Arabic is better than a page
 * with a hole in it.
 */
public final class ArabicNames {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArabicNames.class);

    private static final Map<String, String> BOOKS = loadBooks("i18n/book_names_mapping.json");
    private static final Map<String, String> CHAPTERS = loadFlat("i18n/chapter_ar_mapping.json");
    private static final Map<String, String> SECTIONS = loadFlat("i18n/section_ar_mapping.json");
    private static final Map<String, String> PARTS = loadFlat("i18n/part_ar_mapping.json");

    private ArabicNames() {
    }

    /** The Arabic name of a book, or null. */
    public static String book(String english) {
        return lookup(BOOKS, english);
    }

    /** The Arabic title of a chapter, or null. */
    public static String chapter(String english) {
        return lookup(CHAPTERS, english);
    }

    /** The Arabic name of a section, or null. */
    public static String section(String english) {
        return lookup(SECTIONS, english);
    }

    /** The Arabic name of a part, or null. */
    public static String part(String english) {
        return lookup(PARTS, english);
    }

    /** How many translations were loaded, for the startup log and for tests. */
    public static Map<String, Integer> coverage() {
        return Map.of("books", BOOKS.size(), "chapters", CHAPTERS.size(),
                "sections", SECTIONS.size(), "parts", PARTS.size());
    }

    private static String lookup(Map<String, String> table, String english) {
        if (english == null || english.isBlank()) {
            return null;
        }
        String arabic = table.get(english);
        return arabic == null || arabic.isBlank() ? null : arabic;
    }

    /** {@code {"English": "العربية"}}. */
    private static Map<String, String> loadFlat(String resource) {
        return load(resource, JsonNode::asText);
    }

    /** {@code {"Al-Kāfi": {"ar": "الكافي"}}} - the book file carries more than the name. */
    private static Map<String, String> loadBooks(String resource) {
        return load(resource, node -> node.isObject() ? node.path("ar").asText(null) : node.asText());
    }

    private static Map<String, String> load(String resource, ValueReader reader) {
        Map<String, String> table = new HashMap<>();
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String arabic = reader.read(entry.getValue());
                if (arabic != null && !arabic.isBlank()) {
                    table.put(entry.getKey(), arabic);
                }
            }
        } catch (IOException e) {
            // The Arabic pages fall back to English names rather than failing to render.
            LOGGER.warn("Could not read {}; Arabic pages will show English names", resource, e);
        }
        return Collections.unmodifiableMap(table);
    }

    @FunctionalInterface
    private interface ValueReader {
        String read(JsonNode node);
    }
}

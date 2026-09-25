package com.rewayaat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Human labels for topic tag slugs, from the same taxonomy.json the search UI reads.
 *
 * <p>A card shows "Congregational Prayer", not "congregational-prayer", and both
 * renderers have to agree on that; sharing the file is how they do.
 *
 * <p>The file carries both languages — every one of its 276 entries has an {@code ar} —
 * and the search app has been reading them all along through its own taxonomyLabel().
 * Only this side read the English and nothing else, so an Arabic chapter page tagged its
 * narrations "Friday Prayer" and "Intellect/Reason".
 */
@Component
public class TopicLabelSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(TopicLabelSource.class);

    private final Map<String, String> english = load("en");
    private final Map<String, String> arabic = load("ar");

    /** Falls back to the slug, which is what the UI shows for an unknown tag. */
    public String label(String slug) {
        return label(slug, PageLocale.ENGLISH);
    }

    /**
     * The tag's name in one language, falling back to the other before the slug.
     *
     * <p>A slug on the page reads as a bug; an English name on an Arabic page reads as
     * an untranslated tag, which is the truer thing to show if one is ever missing.
     */
    public String label(String slug, PageLocale locale) {
        if (slug == null || slug.isBlank()) {
            return "";
        }
        if (locale != null && locale.isArabic()) {
            String translated = arabic.get(slug);
            if (translated != null && !translated.isBlank()) {
                return translated;
            }
        }
        return english.getOrDefault(slug, slug);
    }

    private static Map<String, String> load(String field) {
        Map<String, String> loaded = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource("static/taxonomy.json").getInputStream()) {
            for (JsonNode entry : new ObjectMapper().readTree(in)) {
                String slug = entry.path("slug").asText("");
                String label = entry.path(field).asText("");
                if (!slug.isBlank() && !label.isBlank()) {
                    loaded.put(slug, label);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read taxonomy.json; topic tags will render as slugs", e);
        }
        return Map.copyOf(loaded);
    }
}

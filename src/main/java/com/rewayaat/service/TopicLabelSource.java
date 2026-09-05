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
 */
@Component
public class TopicLabelSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(TopicLabelSource.class);

    private final Map<String, String> bySlug = load();

    /** Falls back to the slug, which is what the UI shows for an unknown tag. */
    public String label(String slug) {
        if (slug == null || slug.isBlank()) {
            return "";
        }
        return bySlug.getOrDefault(slug, slug);
    }

    private static Map<String, String> load() {
        Map<String, String> loaded = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource("static/taxonomy.json").getInputStream()) {
            for (JsonNode entry : new ObjectMapper().readTree(in)) {
                String slug = entry.path("slug").asText("");
                String label = entry.path("en").asText("");
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

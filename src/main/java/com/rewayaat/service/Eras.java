package com.rewayaat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The thirteen generations in years: the Prophet, then each Imam's imamate, as AH spans.
 *
 * <p>The thirteen marks on a narrator's page say which Imams the books place him with; this
 * is what lets the preview behind each mark say when that was, on one axis with the man's own
 * years (see {@code narrator-gens.js}). The spans are curated in {@code data/chronology.json},
 * the conventional Twelver dates, and corrections belong there.
 *
 * <p>In a template, inlined into a script as JSON: {@code [[${@eras.forPage(isArabic)}]]}.
 */
@Component("eras")
public class Eras {

    private static final Logger LOGGER = LoggerFactory.getLogger(Eras.class);

    private final List<JsonNode> eras = load();

    /** Each era in order, its names in the page's language: order, from, to, name, short. */
    public List<Map<String, Object>> forPage(boolean arabic) {
        List<Map<String, Object>> out = new ArrayList<>(eras.size());
        for (JsonNode era : eras) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order", era.path("order").asInt());
            row.put("from", era.path("fromAh").asInt());
            row.put("to", era.path("toAh").asInt());
            row.put("name", era.path(arabic ? "nameAr" : "nameEn").asText(""));
            row.put("short", era.path(arabic ? "shortAr" : "shortEn").asText(""));
            // The Arabic name is shown on an English page too, as the card's ornament; an
            // Arabic page has it already as the name.
            row.put("nameAr", era.path("nameAr").asText(""));
            out.add(row);
        }
        return out;
    }

    private static List<JsonNode> load() {
        List<JsonNode> rows = new ArrayList<>();
        try (InputStream in = new ClassPathResource("data/chronology.json").getInputStream()) {
            new ObjectMapper().readTree(in).path("eras").forEach(rows::add);
        } catch (Exception ex) {
            LOGGER.warn("Could not read the chronology; the marks on a narrator's page will "
                    + "not open a preview", ex);
        }
        return List.copyOf(rows);
    }
}

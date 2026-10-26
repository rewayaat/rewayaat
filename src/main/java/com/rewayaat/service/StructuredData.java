package com.rewayaat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON-LD a page hands a crawler.
 *
 * <p>The breadcrumb list was built twice, by the same thirteen lines in two controllers, and
 * on the narrator pages not at all — the deepest trail on the site reached the reader and
 * nothing reached the crawler. A third copy is not the answer to that, so it lives here.
 */
public final class StructuredData {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructuredData.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private StructuredData() {
    }

    /**
     * The trail at the top of the page, said again in the way a crawler reads it.
     *
     * <p>Each rung's {@code url} is a path, as the page's own links are, and is written out
     * against {@link PageLocale#BASE_URL} because an {@code item} must be absolute. The
     * prefix an Arabic page carries is already in the path, so the Arabic trail names
     * Arabic pages.
     */
    public static String breadcrumbs(List<Map<String, String>> crumbs) {
        List<Map<String, Object>> items = new ArrayList<>();
        int position = 1;
        for (Map<String, String> crumb : crumbs) {
            items.add(new LinkedHashMap<>(Map.of(
                    "@type", "ListItem",
                    "position", position++,
                    "name", crumb.get("name"),
                    "item", PageLocale.BASE_URL + crumb.get("url"))));
        }
        Map<String, Object> ld = new LinkedHashMap<>();
        ld.put("@context", "https://schema.org");
        ld.put("@type", "BreadcrumbList");
        ld.put("itemListElement", items);
        return write(ld);
    }

    /** JSON, or an empty object — a page is never failed over its structured data. */
    public static String write(Object value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            LOGGER.warn("Could not serialise JSON-LD", e);
            return "{}";
        }
    }
}

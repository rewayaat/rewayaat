package com.rewayaat.controllers;

import com.rewayaat.config.ESClientProvider;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates dynamic XML sitemaps for search engine crawlers.
 */
@Controller
public class SitemapController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SitemapController.class);
    private static final String BASE_URL = "https://hadith.academyofislam.com";
    private static final int PAGE_SIZE = 10000;
    private static final int ES_MAX_RESULT_WINDOW = 10000;

    @RequestMapping(value = "/sitemap.xml", method = RequestMethod.GET, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemapIndex() {
        LOGGER.debug("Generating sitemap index");
        long total = totalHadithCount();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        // Static pages sitemap
        xml.append("  <sitemap>\n");
        xml.append("    <loc>").append(BASE_URL).append("/sitemap-static.xml</loc>\n");
        xml.append("    <lastmod>").append(LocalDate.now()).append("</lastmod>\n");
        xml.append("  </sitemap>\n");

        // Hadith sitemap pages
        for (int i = 1; i <= totalPages; i++) {
            xml.append("  <sitemap>\n");
            xml.append("    <loc>").append(BASE_URL).append("/sitemap-hadith-").append(i).append(".xml</loc>\n");
            xml.append("    <lastmod>").append(LocalDate.now()).append("</lastmod>\n");
            xml.append("  </sitemap>\n");
        }

        xml.append("</sitemapindex>");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.toString());
    }

    @RequestMapping(value = "/sitemap-static.xml", method = RequestMethod.GET, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> staticSitemap() {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        appendUrl(xml, "/", "1.0", "weekly");
        appendUrl(xml, "/search_tips.html", "0.5", "monthly");

        xml.append("</urlset>");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.toString());
    }

    @RequestMapping(value = "/sitemap-hadith-{page}.xml", method = RequestMethod.GET, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> hadithSitemap(@PathVariable("page") int page) {
        LOGGER.debug("Generating hadith sitemap page {}", page);
        if (page < 1) {
            page = 1;
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        try {
            List<String> ids = fetchHadithIds(page);
            for (String id : ids) {
                appendUrl(xml, "/hadith/" + escapeXml(id), "0.8", "monthly");
            }
        } catch (Exception e) {
            LOGGER.error("Error fetching hadith IDs for sitemap page {}", page, e);
        }

        xml.append("</urlset>");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.toString());
    }

    /**
     * Fetches hadith IDs for a given sitemap page using search_after pagination.
     */
    private List<String> fetchHadithIds(int page) throws Exception {
        try (ESClientProvider provider = new ESClientProvider()) {
            List<String> allIds = new ArrayList<>();
            String searchAfter = null;

            // We need to walk through pages of 10K to reach the desired offset,
            // then return the IDs for that page.
            for (int current = 1; current <= page; current++) {
                final String afterValue = searchAfter;
                SearchResponse<Void> response = provider.client().search(s -> {
                    s.index(ESClientProvider.INDEX)
                            .size(PAGE_SIZE)
                            .source(src -> src.fetch(false))
                            .sort(so -> so.field(f -> f.field("_id").order(SortOrder.Asc)));
                    if (afterValue != null) {
                        s.searchAfter(afterValue);
                    }
                    return s;
                }, Void.class);

                List<Hit<Void>> hits = response.hits().hits();
                if (hits.isEmpty()) {
                    break;
                }

                if (current == page) {
                    for (Hit<Void> hit : hits) {
                        allIds.add(hit.id());
                    }
                } else {
                    searchAfter = hits.get(hits.size() - 1).id();
                }
            }

            return allIds;
        }
    }

    private long totalHadithCount() {
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Void> response = provider.client().search(s -> {
                s.index(ESClientProvider.INDEX).size(0);
                return s;
            }, Void.class);
            return response.hits().total().value();
        } catch (IOException e) {
            LOGGER.error("Error counting hadith for sitemap", e);
            return 0;
        }
    }

    private void appendUrl(StringBuilder xml, String path, String priority, String changefreq) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(BASE_URL).append(path).append("</loc>\n");
        xml.append("    <lastmod>").append(LocalDate.now()).append("</lastmod>\n");
        xml.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
        xml.append("    <priority>").append(priority).append("</priority>\n");
        xml.append("  </url>\n");
    }

    private String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                     .replace("<", "&lt;")
                     .replace(">", "&gt;")
                     .replace("\"", "&quot;")
                     .replace("'", "&apos;");
    }
}

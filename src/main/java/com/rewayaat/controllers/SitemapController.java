package com.rewayaat.controllers;

import com.rewayaat.config.ESClientProvider;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
    private static final String PIT_KEEP_ALIVE = "5m";

    @RequestMapping(value = "/sitemap.xml", method = RequestMethod.GET, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemapIndex() {
        LOGGER.debug("Generating sitemap index");
        long total;
        try {
            total = totalHadithCount();
        } catch (Exception e) {
            // Better a 5xx than a sitemap index that silently under-reports the corpus.
            LOGGER.error("Error counting hadith for sitemap index", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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

        List<String> ids;
        try {
            ids = fetchHadithIds(page);
        } catch (Exception e) {
            // A well-formed but empty urlset looks healthy to crawlers and to monitoring,
            // so surface the failure as a 5xx instead of hiding it behind a 200.
            LOGGER.error("Error fetching hadith IDs for sitemap page {}", page, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        for (String id : ids) {
            appendUrl(xml, "/hadith/" + escapeXml(id), "0.8", "monthly");
        }

        xml.append("</urlset>");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.toString());
    }

    /**
     * Fetches hadith IDs for a given sitemap page using search_after pagination.
     *
     * <p>Pagination runs against a Point-In-Time so the walk sees a consistent snapshot,
     * sorted by {@code _shard_doc}. Sorting on {@code _id} is not an option: Elasticsearch 9
     * disallows fielddata on that field, which fails the whole search.
     */
    private List<String> fetchHadithIds(int page) throws Exception {
        try (ESClientProvider provider = new ESClientProvider()) {
            ElasticsearchClient client = provider.client();
            List<String> allIds = new ArrayList<>();

            String pitId = client.openPointInTime(p -> p
                    .index(ESClientProvider.INDEX)
                    .keepAlive(t -> t.time(PIT_KEEP_ALIVE))).id();

            try {
                List<FieldValue> searchAfter = null;

                // We need to walk through pages of 10K to reach the desired offset,
                // then return the IDs for that page.
                for (int current = 1; current <= page; current++) {
                    final String currentPitId = pitId;
                    final List<FieldValue> afterValue = searchAfter;
                    SearchResponse<Void> response = client.search(s -> {
                        // The index is carried by the PIT, so it must not be set here.
                        s.size(PAGE_SIZE)
                                .trackTotalHits(t -> t.enabled(false))
                                .source(src -> src.fetch(false))
                                .pit(p -> p.id(currentPitId).keepAlive(t -> t.time(PIT_KEEP_ALIVE)))
                                .sort(so -> so.field(f -> f.field("_shard_doc").order(SortOrder.Asc)));
                        if (afterValue != null) {
                            s.searchAfter(afterValue);
                        }
                        return s;
                    }, Void.class);

                    // Elasticsearch may hand back a refreshed PIT id on each search.
                    if (response.pitId() != null) {
                        pitId = response.pitId();
                    }

                    List<Hit<Void>> hits = response.hits().hits();
                    if (hits.isEmpty()) {
                        break;
                    }

                    if (current == page) {
                        for (Hit<Void> hit : hits) {
                            allIds.add(hit.id());
                        }
                    } else {
                        searchAfter = hits.get(hits.size() - 1).sort();
                    }
                }
            } finally {
                closePointInTime(client, pitId);
            }

            return allIds;
        }
    }

    /** Releases a Point-In-Time without masking any exception already in flight. */
    private void closePointInTime(ElasticsearchClient client, String pitId) {
        try {
            client.closePointInTime(c -> c.id(pitId));
        } catch (Exception e) {
            LOGGER.warn("Error closing Point-In-Time for sitemap generation", e);
        }
    }

    private long totalHadithCount() throws IOException {
        try (ESClientProvider provider = new ESClientProvider()) {
            SearchResponse<Void> response = provider.client().search(s -> {
                // Without this Elasticsearch caps the reported total at 10,000.
                s.index(ESClientProvider.INDEX).size(0).trackTotalHits(t -> t.enabled(true));
                return s;
            }, Void.class);
            return response.hits().total().value();
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

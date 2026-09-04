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
    private static final String PIT_KEEP_ALIVE = "2m";

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
        xml.append("  </sitemap>\n");

        // Hadith sitemap pages
        for (int i = 1; i <= totalPages; i++) {
            xml.append("  <sitemap>\n");
            xml.append("    <loc>").append(BASE_URL).append("/sitemap-hadith-").append(i).append(".xml</loc>\n");
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
        appendUrl(xml, "/updates.html", "0.6", "weekly");
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
            // Serving an empty urlset with a 200 tells crawlers there is nothing
            // here, which is how a broken query went unnoticed. A 5xx tells them
            // to retry and to keep the sitemap they already have.
            LOGGER.error("Error fetching hadith IDs for sitemap page {}", page, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        xml.append("</urlset>");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.toString());
    }

    /**
     * Fetches the hadith IDs belonging to one sitemap page.
     *
     * <p>Paging is done against a point-in-time so the view of the index stays
     * fixed for the whole walk, with {@code _shard_doc} as the cursor. Sorting on
     * {@code _id} would be the obvious choice, but Elasticsearch has disallowed
     * fielddata access on that field since 8.x, and the resulting error was being
     * swallowed into an empty sitemap.
     */
    private List<String> fetchHadithIds(int page) throws IOException {
        try (ESClientProvider provider = new ESClientProvider()) {
            ElasticsearchClient client = provider.client();
            String pitId = client.openPointInTime(p -> p
                    .index(ESClientProvider.INDEX)
                    .keepAlive(t -> t.time(PIT_KEEP_ALIVE))).id();

            try {
                List<FieldValue> cursor = null;

                // Walk forward a page at a time until we reach the one asked for.
                for (int current = 1; current <= page; current++) {
                    final List<FieldValue> after = cursor;
                    SearchResponse<Void> response = client.search(s -> {
                        s.pit(p -> p.id(pitId).keepAlive(t -> t.time(PIT_KEEP_ALIVE)))
                                .size(PAGE_SIZE)
                                .trackTotalHits(t -> t.enabled(false))
                                .source(src -> src.fetch(false))
                                .sort(so -> so.field(f -> f.field("_shard_doc").order(SortOrder.Asc)));
                        if (after != null) {
                            s.searchAfter(after);
                        }
                        return s;
                    }, Void.class);

                    List<Hit<Void>> hits = response.hits().hits();
                    if (hits.isEmpty()) {
                        return List.of();
                    }
                    if (current == page) {
                        return hits.stream().map(Hit::id).toList();
                    }
                    cursor = hits.get(hits.size() - 1).sort();
                }

                return List.of();
            } finally {
                try {
                    client.closePointInTime(c -> c.id(pitId));
                } catch (Exception e) {
                    // The point-in-time expires on its own; never mask a real failure.
                    LOGGER.warn("Could not close sitemap point-in-time", e);
                }
            }
        }
    }

    private long totalHadithCount() {
        try (ESClientProvider provider = new ESClientProvider()) {
            // Without trackTotalHits the count saturates at 10,000, which
            // silently truncated the sitemap index to a single page.
            SearchResponse<Void> response = provider.client().search(s -> s
                    .index(ESClientProvider.INDEX)
                    .size(0)
                    .trackTotalHits(t -> t.enabled(true)), Void.class);
            return response.hits().total().value();
        } catch (IOException e) {
            LOGGER.error("Error counting hadith for sitemap", e);
            return 0;
        }
    }

    /**
     * Writes one {@code <url>} entry.
     *
     * <p>No {@code <lastmod>}: nothing in the index records when a narration was last
     * edited, and the previous code emitted {@code LocalDate.now()} — telling crawlers
     * that all 32,519 pages had changed today, every day. Search engines discount a
     * lastmod they can see is unreliable, so omitting it beats fabricating it. If an
     * updated-at field is ever added to the index, it belongs here.
     */
    private void appendUrl(StringBuilder xml, String path, String priority, String changefreq) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(BASE_URL).append(path).append("</loc>\n");
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

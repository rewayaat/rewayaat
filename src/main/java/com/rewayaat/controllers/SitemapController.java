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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.PathVariable;
import com.rewayaat.service.BookCatalog;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    /**
     * The whole ID list, scanned once and sliced per page.
     *
     * <p>Paging straight from Elasticsearch meant page N re-walked pages 1..N-1 and
     * threw the results away, so /sitemap-hadith-4.xml took 24 seconds and Google
     * gave up on it ("Couldn't fetch") while the earlier pages succeeded. One scan
     * of ~32k ids costs about a megabyte of heap and makes every page instant.
     */
    private volatile List<String> cachedIds = List.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    private final BookCatalog catalog;

    public SitemapController(BookCatalog catalog) {
        this.catalog = catalog;
    }

    @RequestMapping(value = "/sitemap.xml", method = RequestMethod.GET, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemapIndex() {
        LOGGER.debug("Generating sitemap index");
        int totalPages;
        try {
            totalPages = Math.max(1, (int) Math.ceil((double) allHadithIds().size() / PAGE_SIZE));
        } catch (Exception e) {
            // Falling back to a count of zero still advertises page 1, so crawlers would
            // follow a link that only 500s. Failing here keeps the index they already have.
            LOGGER.error("Error counting hadith for sitemap index", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        // Static pages sitemap
        xml.append("  <sitemap>\n");
        xml.append("    <loc>").append(BASE_URL).append("/sitemap-static.xml</loc>\n");
        xml.append("  </sitemap>\n");

        // Book, volume and chapter hubs. These are the pages that link down to the
        // narrations, so a crawler that starts here finds the whole corpus by following
        // links rather than by trusting the 32k-entry hadith sitemaps alone.
        xml.append("  <sitemap>\n");
        xml.append("    <loc>").append(BASE_URL).append("/sitemap-books.xml</loc>\n");
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
        appendUrl(xml, "/books", "0.9", "weekly");
        appendUrl(xml, "/updates.html", "0.6", "weekly");
        appendUrl(xml, "/search_tips.html", "0.5", "monthly");

        xml.append("</urlset>");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.toString());
    }

    /**
     * Every book, volume and chapter hub.
     *
     * <p>Roughly 7,800 URLs, comfortably inside the 50,000-per-file limit, so this does
     * not page. They carry a higher priority than the narrations they lead to because
     * they are the pages that can rank for a book or chapter name.
     */
    @RequestMapping(value = "/sitemap-books.xml", method = RequestMethod.GET, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> booksSitemap() {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        try {
            appendUrl(xml, "/books", "0.9", "weekly");
            for (BookCatalog.Book book : catalog.books()) {
                appendUrl(xml, "/books/" + escapeXml(book.slug()), "0.9", "weekly");
                for (String volume : book.volumes()) {
                    appendUrl(xml, "/books/" + escapeXml(book.slug()) + "/volume/"
                            + escapeXml(urlEncode(volume)), "0.8", "monthly");
                }
                // Thirteen of the eighteen books divide into parts, and for some the part
                // is the organising principle rather than the volume.
                for (BookCatalog.Part part : book.parts()) {
                    appendUrl(xml, escapeXml(part.url()), "0.8", "monthly");
                }
                for (BookCatalog.Chapter chapter : book.chapters()) {
                    appendUrl(xml, escapeXml(chapter.url()), "0.7", "monthly");
                }
            }
        } catch (Exception e) {
            // As with the hadith pages: a 5xx tells crawlers to retry and keep the
            // sitemap they already have, where a 200 with an empty urlset would tell
            // them the hubs had been withdrawn.
            LOGGER.error("Error building the books sitemap", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        xml.append("</urlset>");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.toString());
    }

    private static String urlEncode(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
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
            List<String> ids = hadithIdsForPage(page);
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

    /** Returns the slice of IDs belonging to one sitemap page. */
    private List<String> hadithIdsForPage(int page) throws IOException {
        List<String> all = allHadithIds();
        int from = (page - 1) * PAGE_SIZE;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(from + PAGE_SIZE, all.size()));
    }

    /**
     * The full list of hadith IDs, rescanned at most once per {@link #CACHE_TTL}.
     *
     * <p>Synchronised so a burst of crawler requests triggers one scan rather than
     * one per request; the happy path reads the volatile field and never blocks.
     */
    private List<String> allHadithIds() throws IOException {
        List<String> cached = cachedIds;
        if (!cached.isEmpty() && Duration.between(cachedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached;
        }
        synchronized (this) {
            if (!cachedIds.isEmpty() && Duration.between(cachedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
                return cachedIds;
            }
            List<String> scanned = scanAllHadithIds();
            cachedIds = scanned;
            cachedAt = Instant.now();
            return scanned;
        }
    }

    /**
     * Walks every document once and collects its ID.
     *
     * <p>Paging runs against a point-in-time so the view of the index stays fixed for
     * the whole walk, with {@code _shard_doc} as the cursor. Sorting on {@code _id}
     * would be the obvious choice, but Elasticsearch has disallowed fielddata access
     * on that field since 8.x, and the resulting error was being swallowed into an
     * empty sitemap.
     */
    private List<String> scanAllHadithIds() throws IOException {
        long startedAt = System.currentTimeMillis();
        try (ESClientProvider provider = new ESClientProvider()) {
            ElasticsearchClient client = provider.client();
            String pitId = client.openPointInTime(p -> p
                    .index(ESClientProvider.INDEX)
                    .keepAlive(t -> t.time(PIT_KEEP_ALIVE))).id();

            try {
                List<String> ids = new ArrayList<>();
                List<FieldValue> cursor = null;

                while (true) {
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
                        break;
                    }
                    for (Hit<Void> hit : hits) {
                        ids.add(hit.id());
                    }
                    cursor = hits.get(hits.size() - 1).sort();
                }

                LOGGER.info("Scanned {} hadith ids for the sitemap in {}ms",
                        ids.size(), System.currentTimeMillis() - startedAt);
                return List.copyOf(ids);
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

    /**
     * Fills the cache shortly after boot so no crawler is the one that pays for the
     * scan. Runs on its own daemon thread: readiness must not wait on Elasticsearch.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmSitemapCache() {
        Thread warmer = new Thread(() -> {
            try {
                allHadithIds();
            } catch (Exception e) {
                LOGGER.warn("Could not warm the sitemap id cache; it will be built on first request", e);
            }
        }, "sitemap-cache-warmer");
        warmer.setDaemon(true);
        warmer.start();
    }

    /**
     * Drops the memoised id list so the next request rescans Elasticsearch.
     *
     * <p>The cache is otherwise held for {@link #CACHE_TTL}, which is the right answer
     * for crawler traffic but wrong right after a reindex, and wrong for tests that
     * seed a different corpus per case.
     */
    public void invalidateCache() {
        synchronized (this) {
            cachedIds = List.of();
            cachedAt = Instant.EPOCH;
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

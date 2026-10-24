package com.rewayaat.controllers;

import com.rewayaat.config.ArabicIndexing;
import com.rewayaat.config.ESClientProvider;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import com.rewayaat.service.PageLocale;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PreDestroy;

/**
 * Generates dynamic XML sitemaps for search engine crawlers.
 */
@Controller
public class SitemapController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SitemapController.class);
    // One authority for the host, shared with the canonical and hreflang tags the
    // pages carry. A sitemap that names a different host than the canonical is a
    // sitemap crawlers ignore.
    private static final String BASE_URL = PageLocale.BASE_URL;
    private static final String XHTML_NS = "http://www.w3.org/1999/xhtml";
    private static final int PAGE_SIZE = 10000;
    private static final String PIT_KEEP_ALIVE = "2m";
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    /**
     * How long a hadith page waits for a cold pod's first scan before answering 503.
     * Long enough for a small corpus to finish, far short of a crawler's own timeout.
     */
    private static final Duration COLD_SCAN_PATIENCE = Duration.ofSeconds(10);
    /** Sent with that 503, so a crawler comes back after the scan rather than during it. */
    private static final String RETRY_AFTER_SECONDS = "600";

    /**
     * The whole ID list, scanned once and sliced per page.
     *
     * <p>Paging straight from Elasticsearch meant page N re-walked pages 1..N-1 and
     * threw the results away, so /sitemap-hadith-4.xml took 24 seconds and Google
     * gave up on it ("Couldn't fetch") while the earlier pages succeeded. One scan
     * of ~32k ids costs about a megabyte of heap and makes every page instant.
     *
     * <p>The scan itself is slow in production: fetching an id makes Elasticsearch read
     * the stored document, vector and all, and on 2026-10-21 50,038 ids took 337 s.
     * So no request ever waits on a rescan, and none starts a scan before the quiet start
     * is over; see {@link #allHadithIds} and {@link SitemapScanSchedule}.
     */
    private volatile List<String> cachedIds = List.of();
    /**
     * When the quiet start ends, when the stored list goes stale and how long a failed
     * scan blocks the next one. Whether a list has loaded is {@link SitemapScanSchedule#hasList}.
     */
    private final SitemapScanSchedule schedule;
    /** The scan in flight, if any. Guarded by {@code this}. */
    private CompletableFuture<List<String>> scan;
    /** Bumped by {@link #invalidateCache}, so a scan started before it cannot land after it. */
    private long generation;
    /**
     * Bumped by {@link #expireCache}. A scan started before it read the index as it was
     * then, so it may store its ids but must leave the list stale for a rescan.
     */
    private long expiries;

    private volatile List<String> cachedNarratorPaths = List.of();
    private volatile Instant narratorsCachedAt = Instant.EPOCH;

    private final BookCatalog catalog;
    private final ArabicIndexing arabicIndexing;
    private final IsnadPageController isnad;

    /**
     * @param firstScanDelay    quiet start: no scan before this long after boot
     * @param firstScanJitter   plus a random extra up to this, so the pods do not scan together
     * @param rescanJitter      random extra on each list's {@link #CACHE_TTL}, so the pods drift apart
     * @param retryAfterFailure how long a failed scan blocks the next one
     */
    public SitemapController(BookCatalog catalog, ArabicIndexing arabicIndexing,
                             IsnadPageController isnad,
                             @Value("${rewayaat.sitemap.first-scan-delay:PT10M}") Duration firstScanDelay,
                             @Value("${rewayaat.sitemap.first-scan-jitter:PT10M}") Duration firstScanJitter,
                             @Value("${rewayaat.sitemap.rescan-jitter:PT60M}") Duration rescanJitter,
                             @Value("${rewayaat.sitemap.retry-after-failure:PT15M}") Duration retryAfterFailure) {
        this.catalog = catalog;
        this.arabicIndexing = arabicIndexing;
        this.isnad = isnad;
        this.schedule = SitemapScanSchedule.system(CACHE_TTL, firstScanDelay, firstScanJitter,
                rescanJitter, retryAfterFailure);
    }

    @RequestMapping(value = "/sitemap.xml", method = RequestMethod.GET, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemapIndex() {
        LOGGER.debug("Generating sitemap index");
        int totalPages;
        try {
            // Counted from the same list the hadith pages are sliced from, so the index
            // never advertises a page this pod would serve empty. A cold pod has no list
            // yet and counts instead, which costs milliseconds where the scan costs minutes.
            List<String> ids = allHadithIds(Duration.ZERO);
            long total = ids != null ? ids.size() : countHadith();
            totalPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
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
        // The narrator pages were in no sitemap at all. Fifteen thousand of them are served,
        // server-rendered, each with its canonical and its Arabic alternate, and the only way
        // in was a link from a chain on a narration page. A crawler that never reaches a
        // chain never reaches a rijāl page.
        for (int i = 1; i <= narratorSitemapPages(); i++) {
            xml.append("  <sitemap>\n");
            xml.append("    <loc>").append(BASE_URL).append("/sitemap-narrators-").append(i)
                    .append(".xml</loc>\n");
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
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n");
        xml.append("        xmlns:xhtml=\"").append(XHTML_NS).append("\">\n");

        appendLocalisedUrl(xml, "/", "1.0", "weekly");
        appendLocalisedUrl(xml, "/books", "0.9", "weekly");
        // The narrator index, the hub every one of the 15,518 narrator pages hangs off.
        // It carries its own lede and SEO copy in both languages and was listed nowhere,
        // the same omission /privacy had. The query form of it is a search, not a page,
        // and only the bare path is listed.
        appendLocalisedUrl(xml, "/narrators", "0.8", "weekly");
        appendLocalisedUrl(xml, "/updates.html", "0.6", "weekly");
        // A static file rather than a template, so it exists in English only:
        // PageLocale.hasArabicVersion says so and this emits the single entry.
        appendLocalisedUrl(xml, "/search_tips.html", "0.5", "monthly");
        // The privacy policy exists in both languages, is indexable, and was in no
        // sitemap at all - neither half. Nothing failed; it was simply never listed,
        // which is the failure mode a sitemap written by hand has.
        appendLocalisedUrl(xml, "/privacy", "0.3", "yearly");
        // The bare contact page only; the prefilled variants are noindex.
        appendLocalisedUrl(xml, "/contact", "0.3", "yearly");
        // The chain conventions. Each is a page a chain links into thousands of times, so
        // a crawler would reach them anyway; listing them says which they are and that
        // both languages exist.
        for (String slug : isnad.slugs()) {
            appendLocalisedUrl(xml, IsnadPageController.PATH + slug, "0.6", "monthly");
        }

        xml.append("</urlset>");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml.toString());
    }

    /**
     * Every book, volume and chapter hub.
     *
     * <p>Roughly 4,000 URLs, comfortably inside the 50,000-per-file limit, so this does
     * not page. They carry a higher priority than the narrations they lead to because
     * they are the pages that can rank for a book or chapter name.
     *
     * <p>Single-narration chapters are left out — about 4,000 of the 7,839, over half.
     * They canonicalise to their narration, and a sitemap that advertises a non-canonical
     * URL is asking for the duplicate to be indexed instead of the page it points at.
     * Parts that are not pages of their own are left out for the same reason: the 18 that
     * wrap a single chapter, and the 4 that are the only part dividing their parent.
     */
    @RequestMapping(value = "/sitemap-books.xml", method = RequestMethod.GET, produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> booksSitemap() {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n");
        xml.append("        xmlns:xhtml=\"").append(XHTML_NS).append("\">\n");

        try {
            appendLocalisedUrl(xml, "/books", "0.9", "weekly");
            for (BookCatalog.Book book : catalog.books()) {
                appendLocalisedUrl(xml, "/books/" + escapeXml(book.slug()), "0.9", "weekly");
                for (String volume : book.volumes()) {
                    appendLocalisedUrl(xml, "/books/" + escapeXml(book.slug()) + "/volume/"
                            + escapeXml(urlEncode(volume)), "0.8", "monthly");
                }
                // Thirteen of the eighteen books divide into parts, and for some the part
                // is the organising principle rather than the volume.
                // Only the parts that are pages in their own right. A part wrapping one
                // chapter canonicalises to that chapter, and a part that is the only one
                // dividing its parent canonicalises to the parent
                // (BookPageController.partPage); neither may be advertised here, for the
                // same reason a single-narration chapter may not be. pageParts() is the
                // predicate partPage gates on, so the two cannot drift apart.
                for (BookCatalog.Part part : book.pageParts()) {
                    appendLocalisedUrl(xml, escapeXml(part.url()), "0.8", "monthly");
                }
                for (BookCatalog.Chapter chapter : book.chapters()) {
                    // A chapter holding one narration canonicalises to that narration
                    // (BookPageController.chapterPage), and a sitemap may only advertise
                    // canonical URLs. Listing both halves of ~4,000 duplicate pairs is
                    // what spent the crawl budget that never reached the narrations. The
                    // same predicate gates the canonical, so the two cannot drift apart.
                    if (chapter.holdsSingleNarration()) {
                        continue;
                    }
                    appendLocalisedUrl(xml, escapeXml(chapter.url()), "0.7", "monthly");
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
            if (ids == null) {
                // A 503 with Retry-After is the status crawlers treat as "come back", and
                // they keep the copy they have meanwhile. Holding the request open for the
                // minutes the first scan takes is what Google reported as "Sitemap could
                // not be read".
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header("Retry-After", RETRY_AFTER_SECONDS)
                        .build();
            }
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

    @RequestMapping(value = "/sitemap-narrators-{page}.xml", method = RequestMethod.GET,
                    produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> narratorSitemap(@PathVariable("page") int page) {
        if (page < 1) {
            page = 1;
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        try {
            List<String> paths = narratorPaths();
            int from = (page - 1) * PAGE_SIZE;
            if (from < paths.size()) {
                for (String path : paths.subList(from, Math.min(from + PAGE_SIZE, paths.size()))) {
                    // Lower than a narration's 0.8: a man's page is a reference entry, and
                    // the narrations are what this site is for.
                    appendUrl(xml, path, "0.6", "monthly");
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error listing narrators for sitemap page {}", page, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        xml.append("</urlset>");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml.toString());
    }

    private int narratorSitemapPages() {
        try {
            int found = narratorPaths().size();
            return Math.max(1, (found + PAGE_SIZE - 1) / PAGE_SIZE);
        } catch (Exception e) {
            LOGGER.warn("Could not size the narrator sitemap: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Every narrator page, as the path its canonical uses.
     *
     * <p>The men moved to the roster of the Fourteen are left out: their pages answer with a
     * 301 to the Imam's own page, and a sitemap full of redirects is a sitemap a crawler
     * learns to distrust.
     */
    private synchronized List<String> narratorPaths() throws IOException {
        if (!cachedNarratorPaths.isEmpty()
                && Duration.between(narratorsCachedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cachedNarratorPaths;
        }
        List<String> paths = new ArrayList<>();
        try (ESClientProvider provider = new ESClientProvider()) {
            ElasticsearchClient client = provider.client();
            String pitId = client.openPointInTime(p -> p
                    .index("rewayaat_narrators")
                    .keepAlive(t -> t.time(PIT_KEEP_ALIVE))).id();
            try {
                List<FieldValue> cursor = null;
                while (true) {
                    final List<FieldValue> after = cursor;
                    SearchResponse<Map> response = client.search(s -> {
                        s.pit(p -> p.id(pitId).keepAlive(t -> t.time(PIT_KEEP_ALIVE)))
                                .size(PAGE_SIZE)
                                .trackTotalHits(t -> t.enabled(false))
                                .query(q -> q.bool(b -> b.mustNot(m -> m
                                        .exists(e -> e.field("moved_to")))))
                                .source(src -> src.filter(f -> f.includes("person_id", "slug")))
                                .sort(so -> so.field(f -> f.field("_shard_doc")
                                        .order(SortOrder.Asc)));
                        if (after != null) {
                            s.searchAfter(after);
                        }
                        return s;
                    }, Map.class);
                    List<Hit<Map>> hits = response.hits().hits();
                    if (hits.isEmpty()) {
                        break;
                    }
                    for (Hit<Map> hit : hits) {
                        Map<?, ?> row = hit.source();
                        if (row == null) {
                            continue;
                        }
                        Object personValue = row.get("person_id");
                        Object slugValue = row.get("slug");
                        String person = personValue == null ? "" : String.valueOf(personValue).trim();
                        String slug = slugValue == null ? "" : String.valueOf(slugValue).trim();
                        if (person.isEmpty() || "null".equals(person)) {
                            continue;
                        }
                        paths.add("/narrator/" + person
                                + (slug.isEmpty() || "null".equals(slug) ? "" : "/" + slug));
                    }
                    cursor = hits.get(hits.size() - 1).sort();
                }
            } finally {
                final String closing = pitId;
                client.closePointInTime(c -> c.id(closing));
            }
        }
        cachedNarratorPaths = List.copyOf(paths);
        narratorsCachedAt = Instant.now();
        return cachedNarratorPaths;
    }

    /** Returns the slice of IDs belonging to one sitemap page, or null while a cold pod is still scanning. */
    private List<String> hadithIdsForPage(int page) throws IOException {
        List<String> all = allHadithIds(COLD_SCAN_PATIENCE);
        if (all == null) {
            return null;
        }
        int from = (page - 1) * PAGE_SIZE;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(from + PAGE_SIZE, all.size()));
    }

    /**
     * The full list of hadith IDs, rescanned at most once per {@link #CACHE_TTL} (plus a
     * random jitter per stored list).
     *
     * <p>Once a list has loaded it is always served at once, even past its TTL: a stale
     * list starts a rescan in the background and the request goes on with the list it
     * has. Before, the request that found the list stale ran the scan itself, and every
     * sitemap request behind it waited on the lock for the minutes that took.
     *
     * <p>Before the first list has loaded, the caller waits up to {@code patience} for
     * the scan and gets null if it is still running. It also gets null at once, without
     * starting anything, while the quiet start or a failure back-off forbids a scan.
     *
     * @throws IOException if the scan this request waited on failed
     */
    private List<String> allHadithIds(Duration patience) throws IOException {
        if (schedule.hasList()) {
            if (schedule.stale()) {
                startScan();
            }
            return cachedIds;
        }
        CompletableFuture<List<String>> pending = startScan();
        if (pending == null) {
            return null;
        }
        try {
            return pending.get(patience.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof UncheckedIOException unchecked
                    ? unchecked.getCause() : e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Sitemap id scan failed", cause);
        }
    }

    /**
     * The scan in flight, or a new one on its own daemon thread.
     *
     * <p>One at a time, so a burst of crawler requests shares a scan rather than
     * starting one each. A scan that fails leaves the previous list in place and blocks
     * new scans for the back-off; the next request after that tries again.
     *
     * @return the scan, or null if none is running and none may start yet (quiet start
     *         or back-off)
     */
    private synchronized CompletableFuture<List<String>> startScan() {
        if (scan != null && !scan.isDone()) {
            return scan;
        }
        if (!schedule.mayStart()) {
            return null;
        }
        long startedIn = generation;
        long expiriesAtStart = expiries;
        CompletableFuture<List<String>> started = CompletableFuture.supplyAsync(() -> {
            List<String> scanned;
            try {
                scanned = scanAllHadithIds();
            } catch (IOException e) {
                scanFailed(startedIn);
                throw new UncheckedIOException(e);
            } catch (RuntimeException e) {
                scanFailed(startedIn);
                throw e;
            }
            synchronized (this) {
                if (generation == startedIn) {
                    cachedIds = scanned;
                    if (expiries == expiriesAtStart || !schedule.hasList()) {
                        schedule.listStored();
                    }
                }
            }
            return scanned;
        }, task -> {
            Thread thread = new Thread(task, "sitemap-id-scan");
            thread.setDaemon(true);
            thread.start();
        });
        started.whenComplete((ids, e) -> {
            if (e != null) {
                LOGGER.warn("Sitemap id scan failed; serving the previous list until the next attempt", e);
            }
        });
        scan = started;
        return started;
    }

    /** Starts the back-off, unless {@link #invalidateCache} has discarded the scan that failed. */
    private synchronized void scanFailed(long startedIn) {
        if (generation == startedIn) {
            schedule.scanFailed();
        }
    }

    /** The number of narrations, for the index of a pod that has not finished its first scan. */
    private long countHadith() throws IOException {
        try (ESClientProvider provider = new ESClientProvider()) {
            return provider.client().count(c -> c.index(ESClientProvider.INDEX)).count();
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

    private ScheduledExecutorService firstScanTimer;

    /**
     * Schedules the first scan for the end of the quiet start, so no crawler is the one
     * that pays for it and nothing competes with the boot-time catalog build. It used to
     * start at once; on 2026-10-21 that, plus crawlers on the cold pods, starved the
     * Elasticsearch connection pool. The timer thread is a daemon, and the scan runs on
     * its own daemon thread: readiness must not wait on Elasticsearch.
     *
     * <p>If the scan fails, this does not retry; the next request after the back-off does.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void warmSitemapCache() {
        if (firstScanTimer != null) {
            return;
        }
        firstScanTimer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "sitemap-first-scan");
            thread.setDaemon(true);
            return thread;
        });
        scheduleFirstScan();
    }

    /**
     * Arms the timer for whatever is left of the quiet start.
     *
     * <p>The timer counts on {@code System.nanoTime} and the schedule on the wall clock,
     * and the delay is rounded to milliseconds, so the timer can fire a little before
     * the quiet start ends by the schedule's reckoning. {@link #startScan} then refuses,
     * and on 2026-10-21 one pod waited for a crawler instead. So the task checks, and
     * re-arms for the remainder if it is early.
     */
    private synchronized void scheduleFirstScan() {
        if (firstScanTimer.isShutdown()) {
            return;
        }
        Duration left = schedule.untilFirstScan();
        long delayMs = left.isNegative() ? 0 : left.toMillis() + 1;
        firstScanTimer.schedule(() -> {
            synchronized (this) {
                if (schedule.quiet()) {
                    scheduleFirstScan();
                    return;
                }
                // A request cannot have started a scan before this moment, but an
                // invalidateCache() followed by a request may have loaded a list already.
                if (!schedule.hasList()) {
                    startScan();
                }
                firstScanTimer.shutdown();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    synchronized void stopFirstScanTimer() {
        if (firstScanTimer != null) {
            firstScanTimer.shutdownNow();
        }
    }

    /**
     * Drops the memoised id list so the next request rescans Elasticsearch, and
     * discards whatever an in-flight scan would have stored.
     *
     * <p>The cache is otherwise held for {@link #CACHE_TTL}, which is the right answer
     * for crawler traffic but wrong right after a reindex, and wrong for tests that
     * seed a different corpus per case. It also clears any failure back-off, but not the
     * quiet start: that is a property of this boot, not of the list.
     */
    public synchronized void invalidateCache() {
        generation++;
        scan = null;
        cachedIds = List.of();
        schedule.reset();
    }

    /**
     * Ages the memoised list past its TTL without dropping it: the next request is
     * served the list as it stands and starts a rescan behind itself.
     */
    public synchronized void expireCache() {
        expiries++;
        schedule.expire();
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
        appendEntry(xml, BASE_URL + path, priority, changefreq, null);
    }

    /**
     * The page and its Arabic counterpart, each annotated with the pair.
     *
     * <p>Both entries carry the same set of alternates, because hreflang has to be
     * reciprocal: a page that names a translation the translation does not name back
     * is discarded rather than followed. Declaring it here rather than only in the
     * markup means a crawler learns the pair from the sitemap, without having to
     * fetch both pages first to find out they are the same page in two languages.
     *
     * <p>Pages with no Arabic version fall through to a single, unannotated entry.
     */
    private void appendLocalisedUrl(StringBuilder xml, String path, String priority, String changefreq) {
        // The release switch. Until the Arabic site is indexable, each pair is listed as
        // the English page alone and carries no annotation: the Arabic twin is served
        // with X-Robots-Tag: noindex, and an hreflang naming a noindex URL is a pair
        // Google discards rather than follows. See ArabicIndexing.
        if (!PageLocale.hasArabicVersion(path) || !arabicIndexing.isIndexable()) {
            appendUrl(xml, path, priority, changefreq);
            return;
        }
        Map<String, String> alternates = PageLocale.alternatesFor(path);
        appendEntry(xml, PageLocale.ENGLISH.urlFor(path), priority, changefreq, alternates);
        appendEntry(xml, PageLocale.ARABIC.urlFor(path), priority, changefreq, alternates);
    }

    private void appendEntry(StringBuilder xml, String loc, String priority, String changefreq,
                             Map<String, String> alternates) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(loc).append("</loc>\n");
        if (alternates != null) {
            for (Map.Entry<String, String> alternate : alternates.entrySet()) {
                xml.append("    <xhtml:link rel=\"alternate\" hreflang=\"")
                        .append(escapeXml(alternate.getKey()))
                        .append("\" href=\"").append(escapeXml(alternate.getValue())).append("\"/>\n");
            }
        }
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

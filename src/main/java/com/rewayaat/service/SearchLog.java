package com.rewayaat.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import com.rewayaat.config.ESClientProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Keeps a record of what readers search for, so the question can be answered a year from
 * now rather than only for as long as an analytics product happens to retain it.
 *
 * <p>Deliberately not a copy of the analytics event. This is the version that can be shown
 * to someone outside: it survives an ad blocker, it does not depend on a cookie or on
 * consent, it carries the result count so a search that found nothing is visible, and it
 * is kept for as long as the index is kept rather than for a retention window.
 *
 * <p><strong>Nothing here identifies anybody.</strong> No address, no account, no user
 * agent, no session. A row is a phrase, a count and a time, which is what a question like
 * "what did people look for in March" actually needs, and holding less means there is
 * nothing here to leak or to have to explain.
 *
 * <p>Writing is asynchronous and bounded. A search must never wait on this, and must never
 * fail because of it: the queue is small, a full queue drops the row and counts the drop,
 * and every write is wrapped. The data is worth having and worth nothing at the cost of a
 * slower or a broken search.
 *
 * <p>Crawlers are excluded. Most requests to this site are automated, and a table whose
 * popular terms were really a crawler walking the sitemap would be worse than no table -
 * it would be a confident wrong answer, and it is the kind of number that ends up in front
 * of somebody being asked to pay for a placement.
 */
@Service
public class SearchLog {

    private static final Logger log = LoggerFactory.getLogger(SearchLog.class);

    public static final String INDEX = "rewayaat_search_log";

    /** Small on purpose: a backlog means something is wrong, and old searches are the least worth keeping. */
    private static final int QUEUE_CAPACITY = 500;
    private static final int MAX_QUERY_LENGTH = 300;

    private static final Pattern CRAWLER = Pattern.compile(
            "bot|crawl|spider|slurp|bingpreview|facebookexternalhit|headless|python-requests|"
                    + "curl/|wget|go-http|scrapy|bytespider|petal|yandex|baidu|gptbot|ccbot|"
                    + "claude|semrush|ahrefs|dataforseo|mj12|dotbot|zgrab|censys|expanse|"
                    + "internetmeasurement|feedfetcher|monitoring",
            Pattern.CASE_INSENSITIVE);

    private final ESClientProvider elasticsearch;
    private final BlockingQueue<Map<String, Object>> pending = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private volatile Thread writer;
    private volatile boolean running = true;
    private volatile boolean indexReady;

    public SearchLog(ESClientProvider elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    /** A request this should not learn anything from. */
    public static boolean isCrawler(String userAgent) {
        return userAgent != null && CRAWLER.matcher(userAgent).find();
    }

    /**
     * Case and spacing folded away so that "Al-Kafi", "al-kafi" and "al-kafi " are one
     * term when counted. The phrase as typed is kept alongside it, because how people
     * write a thing is sometimes the interesting part.
     */
    static String normalise(String query) {
        return query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Records a search, unless there is nothing worth recording.
     *
     * @param query     what was searched for
     * @param hits      how many narrations matched; zero is the interesting case
     * @param page      results page; only the first is a search, the rest are paging
     * @param locale    the language the reader is reading in
     * @param host      which host served it
     * @param userAgent used only to recognise a crawler, and never stored
     */
    public void record(String query, long hits, int page, String locale, String host, String userAgent) {
        if (query == null || query.isBlank() || page > 1 || isCrawler(userAgent)) {
            return;
        }
        String trimmed = query.strip();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            trimmed = trimmed.substring(0, MAX_QUERY_LENGTH);
        }
        Map<String, Object> row = new HashMap<>();
        row.put("query", trimmed);
        row.put("query_normalized", normalise(trimmed));
        row.put("hits", hits);
        row.put("locale", locale == null ? "en" : locale);
        row.put("host", host == null ? "" : host);
        row.put("timestamp", Instant.now().toString());
        if (!pending.offer(row)) {
            long total = dropped.incrementAndGet();
            if (total % 100 == 1) {
                log.warn("Search log queue full; {} searches not recorded so far", total);
            }
        }
    }

    @PostConstruct
    void start() {
        // The index is created on the first row, not here. At startup the shared
        // Elasticsearch client may not be connected yet - and after a devtools restart the
        // previous context has already closed its pool - so touching it from a
        // @PostConstruct only produced a warning and a dead log.
        writer = new Thread(this::drain, "search-log-writer");
        writer.setDaemon(true);
        writer.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (writer != null) {
            writer.interrupt();
        }
    }

    private void drain() {
        while (running) {
            try {
                Map<String, Object> row = pending.poll(1, TimeUnit.SECONDS);
                if (row == null) {
                    continue;
                }
                if (!indexReady) {
                    ensureIndex();
                }
                ElasticsearchClient client = elasticsearch.client();
                client.index(i -> i.index(INDEX).document(row));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // A search that was answered is not retracted because recording it failed.
                log.debug("Could not write a search log row", e);
            }
        }
    }

    private void ensureIndex() {
        try {
            ElasticsearchClient client = elasticsearch.client();
            if (client.indices().exists(e -> e.index(INDEX)).value()) {
                indexReady = true;
                return;
            }
            Map<String, Property> properties = new HashMap<>();
            // The phrase as typed stays searchable; the folded form is what gets counted,
            // and a keyword is the only thing an aggregation can group on.
            properties.put("query", Property.of(p -> p.text(t -> t)));
            properties.put("query_normalized", Property.of(p -> p.keyword(k -> k)));
            properties.put("hits", Property.of(p -> p.long_(l -> l)));
            properties.put("locale", Property.of(p -> p.keyword(k -> k)));
            properties.put("host", Property.of(p -> p.keyword(k -> k)));
            properties.put("timestamp", Property.of(p -> p.date(d -> d)));
            client.indices().create(CreateIndexRequest.of(c -> c
                    .index(INDEX)
                    .mappings(TypeMapping.of(m -> m.properties(properties)))));
            indexReady = true;
            log.info("Created {}", INDEX);
        } catch (Exception e) {
            // Without the index the writer simply fails per row, which is already handled.
            // Left false so the next row tries again, which is what makes this survive
            // Elasticsearch being unreachable for a while.
            log.debug("Could not ensure {} exists yet", INDEX, e);
        }
    }

    /** Rows dropped because the queue was full. Exposed for tests and diagnostics. */
    public long droppedCount() {
        return dropped.get();
    }

    /** Visible for tests: how many rows are waiting to be written. */
    int queued() {
        return pending.size();
    }
}

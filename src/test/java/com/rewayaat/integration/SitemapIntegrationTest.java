package com.rewayaat.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
import com.rewayaat.controllers.SitemapController;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the ways the hadith sitemap has silently gone missing: a search failure that
 * emitted a well-formed but empty urlset, and a page count derived from a total that
 * Elasticsearch caps at 10,000. Both were fixed without tests; these pin the behaviour
 * so the next rewrite of the controller cannot quietly reintroduce either one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SitemapIntegrationTest extends ElasticsearchTestSupport {

    private static final String BASE_URL = "https://hadith.academyofislam.com";
    private static final int PAGE_SIZE = 10000;
    private static final int BULK_BATCH_SIZE = 2000;
    private static final long VISIBILITY_TIMEOUT_MS = 30000L;
    private static final String MISSING_INDEX = "rewayaat_index_that_does_not_exist";
    /** A port in the IANA ephemeral range that nothing in the test JVM binds. */
    private static final int DEAD_PORT = 59999;

    private static final Pattern LOC = Pattern.compile("<loc>(.*?)</loc>");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SitemapController sitemapController;

    /**
     * The controller memoises the id list for hours, so without this every test after
     * the first would be served the previous test's index contents.
     */
    @BeforeEach
    void clearSitemapCache() {
        sitemapController.invalidateCache();
    }

    @Test
    void hadithSitemap_listsIndexedHadith() throws Exception {
        indexDoc("Al-Kafi-Volume-2-Kulayni:391", "{\"english\":\"hello world\"}");
        indexDoc("Uyun-akhbar-al-Rida-Volume-1-Saduq:145", "{\"english\":\"hello there\"}");
        indexDoc("Nahj-al-Balagha-Sharif-al-Radi:1", "{\"english\":\"help needed\"}");

        ResponseEntity<String> response = restTemplate.getForEntity("/sitemap-hadith-1.xml", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        List<String> locs = locations(response.getBody());
        assertEquals(3, locs.size(), () -> "Expected one <loc> per indexed hadith, got: " + locs);
        assertTrue(locs.contains(BASE_URL + "/hadith/Al-Kafi-Volume-2-Kulayni:391"), () -> "Missing hadith URL in " + locs);
        assertTrue(locs.contains(BASE_URL + "/hadith/Uyun-akhbar-al-Rida-Volume-1-Saduq:145"), () -> "Missing hadith URL in " + locs);
        assertTrue(locs.contains(BASE_URL + "/hadith/Nahj-al-Balagha-Sharif-al-Radi:1"), () -> "Missing hadith URL in " + locs);
    }

    @Test
    void sitemapIndex_countsHadithPagesBeyondTheTenThousandHitCap() throws Exception {
        int total = PAGE_SIZE + 1;
        bulkIndexStubs(total);

        ResponseEntity<String> response = restTemplate.getForEntity("/sitemap.xml", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        List<String> locs = locations(response.getBody());
        assertTrue(locs.contains(BASE_URL + "/sitemap-static.xml"), () -> "Static sitemap missing from index: " + locs);

        List<String> hadithSitemaps = new ArrayList<>();
        for (String loc : locs) {
            if (loc.contains("/sitemap-hadith-")) {
                hadithSitemaps.add(loc);
            }
        }
        // 10,001 documents span two pages. Deriving the count from hits().total() without
        // track_total_hits caps it at 10,000 and advertises a single page, hiding the
        // overflow; deriving it from a capped search window does the same.
        assertEquals(2, hadithSitemaps.size(), () -> "Expected two hadith sitemap pages, got: " + hadithSitemaps);
        assertTrue(hadithSitemaps.contains(BASE_URL + "/sitemap-hadith-1.xml"), () -> "Missing page 1 in " + hadithSitemaps);
        assertTrue(hadithSitemaps.contains(BASE_URL + "/sitemap-hadith-2.xml"), () -> "Missing page 2 in " + hadithSitemaps);
    }

    @Test
    void hadithSitemap_pagesThroughEveryDocumentWithoutDuplicates() throws Exception {
        int total = PAGE_SIZE + 1;
        bulkIndexStubs(total);

        List<String> firstPage = locations(okBody("/sitemap-hadith-1.xml"));
        List<String> secondPage = locations(okBody("/sitemap-hadith-2.xml"));

        assertEquals(PAGE_SIZE, firstPage.size(), "First sitemap page should be full");
        assertEquals(total - PAGE_SIZE, secondPage.size(), "Second sitemap page should hold the remainder");

        Set<String> unique = new HashSet<>(firstPage);
        unique.addAll(secondPage);
        assertEquals(total, unique.size(), "Every indexed hadith should appear exactly once across the pages");
        for (String loc : unique) {
            assertTrue(loc.startsWith(BASE_URL + "/hadith/"), () -> "Unexpected sitemap URL: " + loc);
        }
    }

    @Test
    void staticSitemap_doesNotDependOnElasticsearch() {
        withMissingIndex(() -> {
            ResponseEntity<String> response = restTemplate.getForEntity("/sitemap-static.xml", String.class);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            List<String> locs = locations(response.getBody());
            assertTrue(locs.contains(BASE_URL + "/"), () -> "Missing home page URL in " + locs);
            assertTrue(locs.contains(BASE_URL + "/search_tips.html"), () -> "Missing search tips URL in " + locs);
        });
    }

    @Test
    void hadithSitemap_failsLoudlyWhenElasticsearchIsUnavailable() {
        withMissingIndex(() -> {
            // A well-formed empty urlset served with 200 looks healthy to crawlers and to
            // monitoring, which is exactly how the empty sitemap went unnoticed.
            ResponseEntity<String> response = restTemplate.getForEntity("/sitemap-hadith-1.xml", String.class);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        });
    }

    @Test
    void sitemapIndex_failsLoudlyWhenElasticsearchIsUnavailable() {
        withMissingIndex(() -> {
            ResponseEntity<String> response = restTemplate.getForEntity("/sitemap.xml", String.class);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        });
    }

    @Test
    void sitemapIndex_failsLoudlyWhenElasticsearchIsUnreachable() {
        // A missing index fails with a runtime exception, which Spring turns into a 500
        // on its own. A connection failure surfaces as an IOException, which the counter
        // used to swallow into a total of zero -- still advertising page 1, which then
        // 500s. Only this path distinguishes the two.
        withUnreachableElasticsearch(() -> {
            ResponseEntity<String> response = restTemplate.getForEntity("/sitemap.xml", String.class);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        });
    }

    private String okBody(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), () -> path + " should be served");
        return response.getBody();
    }

    private List<String> locations(String xml) {
        assertNotNull(xml, "Sitemap body should not be null");
        List<String> locs = new ArrayList<>();
        Matcher matcher = LOC.matcher(xml);
        while (matcher.find()) {
            locs.add(matcher.group(1));
        }
        return locs;
    }

    /** Points the controller at an index that does not exist, so every search fails. */
    private void withMissingIndex(Runnable body) {
        System.setProperty("REWAYAAT_INDEX", MISSING_INDEX);
        ESClientProvider.resetIndex();
        try {
            body.run();
        } finally {
            System.setProperty("REWAYAAT_INDEX", INDEX);
            ESClientProvider.resetIndex();
        }
    }

    /** Points the shared client at a port nothing is listening on, so every call fails with an IOException. */
    private void withUnreachableElasticsearch(Runnable body) {
        System.setProperty("ELASTIC_PORT", String.valueOf(DEAD_PORT));
        ESClientProvider.resetSharedClient();
        try {
            body.run();
        } finally {
            System.setProperty("ELASTIC_PORT", String.valueOf(elasticPort));
            ESClientProvider.resetSharedClient();
        }
    }

    private void indexDoc(String id, String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> doc = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
        client.index(i -> i.index(INDEX).id(id).document(doc).refresh(Refresh.True));
    }

    private void bulkIndexStubs(int count) throws Exception {
        for (int start = 0; start < count; start += BULK_BATCH_SIZE) {
            int end = Math.min(start + BULK_BATCH_SIZE, count);
            List<BulkOperation> operations = new ArrayList<>();
            for (int i = start; i < end; i++) {
                String id = "stub-hadith-" + i;
                Map<String, Object> doc = Map.of("english", "stub narration " + i);
                operations.add(BulkOperation.of(op -> op.index(idx -> idx.index(INDEX).id(id).document(doc))));
            }
            BulkResponse response = client.bulk(b -> b.index(INDEX).operations(operations));
            assertFalse(response.errors(), "Bulk indexing of sitemap stubs reported errors");
        }
        client.indices().refresh(r -> r.index(INDEX));
        awaitVisible(count);
    }

    /** Waits for the freshly bulk-indexed stubs to become searchable before the sitemap is requested. */
    private void awaitVisible(int expected) throws Exception {
        long deadline = System.currentTimeMillis() + VISIBILITY_TIMEOUT_MS;
        long indexed = 0;
        while (System.currentTimeMillis() < deadline) {
            indexed = client.count(c -> c.index(INDEX)).count();
            if (indexed == expected) {
                return;
            }
            Thread.sleep(100);
            client.indices().refresh(r -> r.index(INDEX));
        }
        final long lastSeen = indexed;
        assertEquals(expected, lastSeen, "Seeded documents never became searchable");
    }
}

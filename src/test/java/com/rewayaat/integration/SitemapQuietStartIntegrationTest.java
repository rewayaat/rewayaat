package com.rewayaat.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.controllers.SitemapController;
import co.elastic.clients.elasticsearch._types.Refresh;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first scan waits out a quiet start, and nothing a request does can bring it forward.
 * The window is a few seconds here; in production it is 10 to 20 minutes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rewayaat.sitemap.first-scan-delay=PT6S",
        "rewayaat.sitemap.first-scan-jitter=PT0S",
        "rewayaat.sitemap.rescan-jitter=PT0S",
        "rewayaat.sitemap.retry-after-failure=PT0S"
})
class SitemapQuietStartIntegrationTest extends ElasticsearchTestSupport {

    private static final long DEADLINE_MS = 30000L;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SitemapController sitemapController;

    /** The window opened when the context started, which may be some seconds ago. */
    private final long contextStartedAt = System.currentTimeMillis();

    @BeforeEach
    void invalidate() {
        // Must not cancel or re-arm the quiet window.
        sitemapController.invalidateCache();
    }

    @Test
    void duringTheQuietStart_aColdPodAnswers503AtOnce_andNeverStartsAScan_thenTheScanLandsByItself() throws Exception {
        indexDoc("Al-Kafi-Volume-2-Kulayni:391", "{\"english\":\"hello world\"}");
        indexDoc("Al-Kafi-Volume-2-Kulayni:392", "{\"english\":\"hello there\"}");

        // Not restTemplate: its Apache client honours Retry-After on a 503 and sleeps.
        HttpClient plain = HttpClient.newHttpClient();
        long startedAt = System.currentTimeMillis();
        HttpResponse<String> hadith = get(plain, "/sitemap-hadith-1.xml");
        long tookMs = System.currentTimeMillis() - startedAt;
        // The window must still have been open, or this asserts nothing.
        assertTrue(System.currentTimeMillis() - contextStartedAt < 5500,
                "the test ran too late to observe the quiet start");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), hadith.statusCode());
        assertTrue(hadith.headers().firstValue("Retry-After").isPresent(), "A 503 should say when to come back");
        assertTrue(tookMs < 2000, () -> "The crawler was held for " + tookMs + "ms");

        // The index counts rather than scans, so it still answers.
        assertEquals(HttpStatus.OK.value(), get(plain, "/sitemap.xml").statusCode());

        // No request started a scan: nothing more to do but wait for the timer.
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        int status = 0;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            HttpResponse<String> response = get(plain, "/sitemap-hadith-1.xml");
            status = response.statusCode();
            body = response.body();
            if (status == 200) {
                break;
            }
        }
        final String finalBody = body;
        assertEquals(200, status, "The background scan should have landed on its own");
        assertTrue(body.contains("Al-Kafi-Volume-2-Kulayni:391") && body.contains("Al-Kafi-Volume-2-Kulayni:392"),
                () -> "Seeded hadith missing from " + finalBody);
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(restTemplate.getRootUri() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void indexDoc(String id, String json) throws Exception {
        Map<String, Object> doc = new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {
        });
        client.index(i -> i.index(INDEX).id(id).document(doc).refresh(Refresh.True));
    }
}

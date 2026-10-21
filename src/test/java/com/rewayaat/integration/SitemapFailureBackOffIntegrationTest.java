package com.rewayaat.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ESClientProvider;
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

/** After a failed scan no request starts another until the back-off has passed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rewayaat.sitemap.first-scan-delay=PT0S",
        "rewayaat.sitemap.first-scan-jitter=PT0S",
        "rewayaat.sitemap.rescan-jitter=PT0S",
        "rewayaat.sitemap.retry-after-failure=PT8S"
})
class SitemapFailureBackOffIntegrationTest extends ElasticsearchTestSupport {

    private static final String MISSING_INDEX = "rewayaat_index_that_does_not_exist";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SitemapController sitemapController;

    @BeforeEach
    void invalidate() {
        sitemapController.invalidateCache();
    }

    @Test
    void aFailedScan_blocksTheNextRequestFromScanning_untilTheBackOffPasses() throws Exception {
        indexDoc("Al-Kafi-Volume-2-Kulayni:391", "{\"english\":\"hello world\"}");
        HttpClient plain = HttpClient.newHttpClient();

        // The request that waits on the failing scan hears about it.
        System.setProperty("REWAYAAT_INDEX", MISSING_INDEX);
        ESClientProvider.resetIndex();
        try {
            assertEquals(500, get(plain, "/sitemap-hadith-1.xml").statusCode());
        } finally {
            System.setProperty("REWAYAAT_INDEX", INDEX);
            ESClientProvider.resetIndex();
        }
        long failedAt = System.currentTimeMillis();

        // Elasticsearch is healthy again, but the back-off holds: 503, not a new scan.
        HttpResponse<String> inside = get(plain, "/sitemap-hadith-1.xml");
        assertTrue(System.currentTimeMillis() - failedAt < 6000, "too slow to observe the back-off");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), inside.statusCode());

        // Once it has passed, the next request starts the scan and the list lands.
        Thread.sleep(Math.max(0, failedAt + 8500 - System.currentTimeMillis()));
        long deadline = System.currentTimeMillis() + 30000L;
        int status = 0;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = get(plain, "/sitemap-hadith-1.xml");
            status = response.statusCode();
            body = response.body();
            if (status == 200) {
                break;
            }
            Thread.sleep(300);
        }
        final String finalBody = body;
        assertEquals(200, status);
        assertTrue(body.contains("Al-Kafi-Volume-2-Kulayni:391"), () -> "Seeded hadith missing from " + finalBody);
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

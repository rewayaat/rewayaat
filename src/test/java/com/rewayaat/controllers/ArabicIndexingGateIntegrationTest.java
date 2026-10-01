package com.rewayaat.controllers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.rewayaat.integration.ElasticsearchTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one irreversible step of the Arabic release, in both positions.
 *
 * <p>Every other step can be undone: the data load has a rollback script and writes
 * nothing the English site reads, and the deploy can be reverted or the image repointed.
 * Being indexed cannot. So {@code arabic.indexable} separates "live in production" from
 * "discoverable", and this covers what that switch is supposed to do — on both settings,
 * because a switch verified in one position is a constant.
 *
 * <p>What must stay true in either position: {@code /ar} answers, and in Arabic. The
 * switch governs who is told about it, never whether it works.
 */
class ArabicIndexingGateIntegrationTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "arabic.indexable=false")
    @DisplayName("during the release")
    class WhileHeldBack extends ElasticsearchTestSupport {

        @Autowired
        private TestRestTemplate restTemplate;

        @Test
        @DisplayName("the Arabic site works, and says it is not to be indexed")
        void arabicIsServedButNoindexed() {
            ResponseEntity<String> page = restTemplate.getForEntity("/ar/books", String.class);

            assertEquals(HttpStatus.OK, page.getStatusCode(),
                    "the switch governs discoverability, not whether the page exists - "
                            + "the whole point is to verify it in production first");
            assertNotNull(page.getBody());
            assertTrue(page.getBody().contains("dir=\"rtl\""), "and it is still Arabic");

            assertEquals("noindex", page.getHeaders().getFirst("X-Robots-Tag"),
                    "a header rather than a robots.txt Disallow: a blocked URL is still "
                            + "indexed from links, and a crawler that cannot fetch the page "
                            + "never reads the directive telling it to stay away");
        }

        @Test
        @DisplayName("the header survives the forward that strips the prefix")
        void theHeaderOutlivesThePrefix() {
            // ArabicSiteConfig answers /ar by forwarding to the English handler, which no
            // longer sees /ar in the URI. Both filters match the same patterns and both
            // default to the lowest precedence, so without the explicit order this passes
            // or fails on whichever Spring happened to register first.
            for (String path : new String[]{"/ar/", "/ar/books", "/ar/privacy",
                    "/ar/updates.html", "/ar/search_tips.html"}) {
                ResponseEntity<String> page = restTemplate.getForEntity(path, String.class);
                assertEquals("noindex", page.getHeaders().getFirst("X-Robots-Tag"),
                        path + " is reachable without saying it should not be indexed");
            }
        }

        @Test
        @DisplayName("no sitemap advertises an Arabic URL, or claims one as an alternate")
        void theSitemapsSayNothingAboutArabic() {
            String xml = restTemplate.getForObject("/sitemap-static.xml", String.class);
            assertNotNull(xml);

            assertTrue(xml.contains("<loc>https://hadith.academyofislam.com/</loc>"),
                    "the English pages are still listed");
            assertFalse(xml.contains("/ar/"),
                    "an Arabic URL in the sitemap is an invitation to crawl it:\n" + xml);
            assertFalse(xml.contains("hreflang"),
                    "an alternate naming a noindex URL is a pair Google discards, so the "
                            + "annotation goes with it");
        }

        @Test
        @DisplayName("the English site is untouched by the switch")
        void englishIsUnaffected() {
            ResponseEntity<String> page = restTemplate.getForEntity("/books", String.class);
            assertEquals(HttpStatus.OK, page.getStatusCode());
            assertEquals(null, page.getHeaders().getFirst("X-Robots-Tag"),
                    "holding the Arabic site back must not take the English one with it");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "arabic.indexable=true")
    @DisplayName("after the switch")
    class OnceReleased extends ElasticsearchTestSupport {

        @Autowired
        private TestRestTemplate restTemplate;

        @Test
        @DisplayName("the Arabic site is indexable and paired in the sitemap")
        void arabicIsDiscoverable() {
            ResponseEntity<String> page = restTemplate.getForEntity("/ar/books", String.class);
            assertEquals(HttpStatus.OK, page.getStatusCode());
            assertEquals(null, page.getHeaders().getFirst("X-Robots-Tag"),
                    "the switch is on, so nothing should be holding the Arabic site out");

            String xml = restTemplate.getForObject("/sitemap-static.xml", String.class);
            assertNotNull(xml);
            assertTrue(xml.contains("<loc>https://hadith.academyofislam.com/ar/books</loc>"),
                    "both halves of each pair are listed once the switch is on");
            assertTrue(xml.contains("hreflang=\"ar\""), "and each one names the other");
        }

        @Test
        @DisplayName("a page with no Arabic version is still noindexed on its own account")
        void theOtherNoindexPathsAreUnaffected() {
            // /signin.html was noindex long before this switch existed. Turning the
            // Arabic site on must not reach into that list.
            ResponseEntity<String> page = restTemplate.getForEntity("/signin.html", String.class);
            assertEquals("noindex", page.getHeaders().getFirst("X-Robots-Tag"));
        }
    }
}

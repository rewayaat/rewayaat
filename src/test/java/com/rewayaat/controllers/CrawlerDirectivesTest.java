package com.rewayaat.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins how the pages that must never be indexed tell a crawler so.
 *
 * <p>{@code /edit} and {@code /signin.html} were {@code Disallow}ed in robots.txt, and Search
 * Console reported both as "Indexed, though blocked by robots.txt": a crawler that may not
 * fetch a page never reads the noindex on it, while a link is enough to index the URL. They
 * carry {@code X-Robots-Tag: noindex} now, and have to stay crawlable for that header to be
 * read at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrawlerDirectivesTest {

    private static final List<String> NOINDEX_PAGES = List.of("/edit", "/signin.html");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void pagesThatMustNotBeIndexedSayNoindex() {
        for (String path : NOINDEX_PAGES) {
            ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

            assertEquals(HttpStatus.OK, response.getStatusCode(), () -> path + " should be served");
            assertEquals("noindex", response.getHeaders().getFirst("X-Robots-Tag"),
                    () -> path + " must carry X-Robots-Tag: noindex, or it can be indexed from a link");
        }
    }

    /** A Disallow would hide the header above from the only reader it is written for. */
    @Test
    void robotsTxtDoesNotBlockThePagesItNeedsCrawlersToRead() {
        String robots = restTemplate.getForObject("/robots.txt", String.class);
        assertNotNull(robots, "robots.txt should be served");

        for (String path : NOINDEX_PAGES) {
            for (String line : robots.split("\\R")) {
                String rule = line.strip();
                if (!rule.regionMatches(true, 0, "Disallow:", 0, "Disallow:".length())) {
                    continue;
                }
                String blocked = rule.substring("Disallow:".length()).strip();
                assertFalse(!blocked.isEmpty() && path.startsWith(blocked),
                        () -> "robots.txt rule '" + rule + "' blocks " + path
                                + ", so its noindex can never be read");
            }
        }
    }
}

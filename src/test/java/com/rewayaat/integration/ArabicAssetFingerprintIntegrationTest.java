package com.rewayaat.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An Arabic page links its stylesheets and scripts by fingerprinted URL, as the English
 * page does.
 *
 * <p>The Arabic site is the English one reached by a forward (ArabicSiteConfig), and the
 * filter that fingerprints asset URLs ran on direct requests only, so every {@code /ar}
 * page went out with plain {@code /js/rewayaat.js}. Plain URLs carry no Cache-Control,
 * so a returning reader's browser kept the previous release's script: after the
 * bookcase shipped, the Arabic home page drew it and then the old script replaced it
 * with the old cards, while the English page was fine.
 *
 * <p>Named {@code …IntegrationTest} because it boots the application, which needs a live
 * Elasticsearch; see ArabicLinksStayArabicIntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArabicAssetFingerprintIntegrationTest extends ElasticsearchTestSupport {

    private static final List<String> PAGES = List.of("/ar/", "/ar/books", "/ar/privacy", "/ar/updates.html", "/ar/signin.html");

    private static final Pattern ASSET = Pattern.compile("(?:src|href)=\"(/(?:css|js)/[^\"?]+)\"");
    private static final Pattern FINGERPRINTED = Pattern.compile("-[0-9a-f]{32}\\.(?:css|js)$");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Arabic pages name their stylesheets and scripts by content hash")
    void arabicAssetsAreFingerprinted() {
        Set<String> plain = new TreeSet<>();
        for (String page : PAGES) {
            String html = restTemplate.getForObject(page, String.class);
            Matcher m = ASSET.matcher(html == null ? "" : html);
            int seen = 0;
            while (m.find()) {
                seen++;
                if (!FINGERPRINTED.matcher(m.group(1)).find()) {
                    plain.add(page + "  ->  " + m.group(1));
                }
            }
            assertTrue(seen > 0, page + " links no stylesheets or scripts at all");
        }
        assertEquals(Set.of(), plain, "these assets reach Arabic readers by a cacheable plain URL:\n"
                + String.join("\n", plain));
    }

    @Test
    @DisplayName("an Arabic page and its English twin load the same files")
    void arabicAndEnglishShareFingerprints() {
        assertEquals(assets("/books"), assets("/ar/books"));
    }

    private Set<String> assets(String page) {
        Set<String> out = new TreeSet<>();
        Matcher m = ASSET.matcher(restTemplate.getForObject(page, String.class));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}

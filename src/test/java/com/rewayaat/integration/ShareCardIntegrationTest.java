package com.rewayaat.integration;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the share-card route end to end: the image, its caching contract and the meta
 * tags that point at it.
 *
 * <p>All 32,519 narration pages used to share one generic {@code og:image}, so every
 * WhatsApp forward of a different narration previewed the same logo. What makes that fix
 * hold is not that the route exists but that the page references it and that the response
 * is cacheable — an uncacheable card would be redrawn for every crawler hit on a corpus
 * this size.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShareCardIntegrationTest extends ElasticsearchTestSupport {

    private static final String ID = "Al-Kafi-Volume-2-Kulayni:81";

    /** Real shape: an isnad the card has to drop, then the matn it has to keep. */
    private static final String ARABIC =
            "عِدَّةٌ مِنْ أَصْحَابِنَا عَنْ أَحْمَدَ بْنِ أَبِي عَبْدِ الله قَالَ "
            + "إِنَّ الله عَزَّ وَجَلَّ وَضَعَ الإيمَانَ عَلَى سَبْعَةِ أَسْهُمٍ عَلَى الْبِرِّ وَالصِّدْقِ";

    private static final String ENGLISH =
            "A number of our people have narrated from Ahmad ibn abu Abd Allah who has said the "
            + "following: Allah made belief in seven shares: virtue, truthfulness, certainty, "
            + "compliance, loyalty, knowledge and forbearance.";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void theCardIsAPngOfTheOpenGraphSize() throws Exception {
        indexNarration();

        ResponseEntity<byte[]> response = restTemplate.getForEntity(
                "/hadith/" + ID + "/card.png", byte[].class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().length > 1000, "the card was suspiciously small");
    }

    /**
     * Crawlers and chat clients hit these hard and drawing text is not free, so the card
     * must be cacheable and must answer a conditional request without redrawing.
     */
    @Test
    void aSecondRequestWithTheEtagIsAnsweredWith304() throws Exception {
        indexNarration();
        String url = "/hadith/" + ID + "/card.png";

        ResponseEntity<byte[]> first = restTemplate.getForEntity(url, byte[].class);
        String etag = first.getHeaders().getETag();
        assertNotNull(etag, "no ETag, so every crawler hit would redraw and re-download the card");

        String cacheControl = first.getHeaders().getCacheControl();
        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("max-age=31536000"), "expected a year, got: " + cacheControl);
        assertTrue(cacheControl.contains("public"), "expected public, got: " + cacheControl);

        HttpHeaders conditional = new HttpHeaders();
        conditional.setIfNoneMatch(etag);
        ResponseEntity<byte[]> second = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(conditional), byte[].class);

        assertEquals(HttpStatus.NOT_MODIFIED, second.getStatusCode());
        assertEquals(etag, second.getHeaders().getETag());
    }

    /**
     * Two themes behind one URL. They must be distinct images with distinct ETags, or the
     * second one requested would be served out of the first one's cache — and with
     * {@code immutable} on the response, a viewer would keep the wrong one for a year.
     */
    @Test
    void theLightThemeIsADistinctImageWithItsOwnEtag() throws Exception {
        indexNarration();

        ResponseEntity<byte[]> dark = restTemplate.getForEntity(
                "/hadith/" + ID + "/card.png", byte[].class);
        ResponseEntity<byte[]> light = restTemplate.getForEntity(
                "/hadith/" + ID + "/card.png?theme=light", byte[].class);

        assertEquals(HttpStatus.OK, light.getStatusCode());
        assertFalse(Arrays.equals(dark.getBody(), light.getBody()),
                "the light card rendered identically to the dark one");
        assertFalse(dark.getHeaders().getETag().equals(light.getHeaders().getETag()),
                "the themes share an ETag, so a client would be served the wrong one");
    }

    /**
     * An unrecognised theme is the dark card, not a 400. These URLs are pasted into
     * newsletters and chat clients by hand, and a broken image is a worse answer than the
     * default one.
     */
    @Test
    void anUnknownThemeFallsBackToTheDarkCard() throws Exception {
        indexNarration();

        ResponseEntity<byte[]> dark = restTemplate.getForEntity(
                "/hadith/" + ID + "/card.png", byte[].class);
        ResponseEntity<byte[]> nonsense = restTemplate.getForEntity(
                "/hadith/" + ID + "/card.png?theme=chartreuse", byte[].class);

        assertEquals(HttpStatus.OK, nonsense.getStatusCode());
        assertTrue(Arrays.equals(dark.getBody(), nonsense.getBody()));
    }

    /** A card for a narration that is not there is a 404, not a 500 and not a blank image. */
    @Test
    void anUnknownNarrationAnswers404() throws Exception {
        indexNarration();

        ResponseEntity<byte[]> response = restTemplate.getForEntity(
                "/hadith/no-such-narration:1/card.png", byte[].class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    /**
     * The route only pays for itself if the page points at it. This is the regression the
     * whole change exists to prevent: a narration page advertising the site logo.
     */
    @Test
    void theNarrationPageAdvertisesItsOwnCardRatherThanTheSiteLogo() throws Exception {
        indexNarration();

        ResponseEntity<String> page = restTemplate.getForEntity("/hadith/" + ID, String.class);
        String html = page.getBody();

        assertNotNull(html);
        String expected = "https://hadith.academyofislam.com/hadith/" + ID + "/card.png";
        assertTrue(html.contains("<meta property=\"og:image\" content=\"" + expected + "\""),
                "og:image does not point at this narration's card");
        assertTrue(html.contains("<meta name=\"twitter:image\" content=\"" + expected + "\""),
                "twitter:image does not point at this narration's card");
        assertFalse(html.contains("/img/share-card.png"),
                "the narration page still references the generic share card");
    }

    private void indexNarration() throws Exception {
        Map<String, Object> doc = Map.of(
                "book", "Al-Kāfi", "volume", "2", "number", "81",
                "arabic", ARABIC, "english", ENGLISH);
        client.index(i -> i.index(INDEX).id(ID).document(doc).refresh(Refresh.True));
    }
}

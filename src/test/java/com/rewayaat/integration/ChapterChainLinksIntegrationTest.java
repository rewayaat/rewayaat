package com.rewayaat.integration;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.core.HadithDisplaySegmenter;
import com.rewayaat.service.BookCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A name in an isnād is a link on every page that prints the isnād.
 *
 * <p>The chapter pages printed it as plain text. {@code BookPageController} narrows
 * {@code _source} to what a card needs and its list named {@code chain_people} without
 * {@code chain_links}, so {@code ChainLinks} was handed null spans and returned the text
 * untouched — on all 5,445 chapter pages, across nineteen books, against a corpus that
 * had 101,592 links in it. Nothing failed. The RIJĀL panel beside the chain is built from
 * {@code chain_people}, so it was complete, and a chain of plain names under a full panel
 * reads as a resolver that found nobody.
 *
 * <p>{@link com.rewayaat.core.ChainFieldsAreFetchedTest} guards the field list itself, for
 * every route at once and without an Elasticsearch. This is the other half: the fetched
 * field has to survive the controller, the card factory and the template and come out as
 * an href, and only real HTML says whether it did.
 *
 * <p>The offsets are computed here by running {@link HadithDisplaySegmenter} over the same
 * text that gets indexed, rather than written out by hand. {@code ChainLinks} drops a span
 * whose covered text does not match, so a hand-counted offset that drifts by one would
 * make this test pass by rendering nothing — which is the exact failure it exists to catch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChapterChainLinksIntegrationTest extends ElasticsearchTestSupport {

    private static final Path PRODUCTION_MAPPING = Path.of("scripts/search/v2_mapping.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BOOK = "Al-Kāfi";
    private static final String CHAPTER = "The Virtue of Benevolent Assistance";

    private static final String ARABIC =
            "محمد بن يعقوب عن علي بن إبراهيم عن أبيه قال قال أبو عبد الله عليه السلام "
                    + "إن الله يحب الرفق في الأمر كله";
    private static final String ENGLISH =
            "Muhammad ibn Yaqub narrated from Ali ibn Ibrahim, from his father, who said: "
                    + "Abu Abdullah (AS) said, Allah loves gentleness in every matter.";

    /** The name linked, and the man it is linked to. */
    private static final String NAME_AR = "علي بن إبراهيم";
    private static final String NAME_EN = "Ali ibn Ibrahim";
    private static final String PERSON = "n000339";
    private static final String SLUG = "ibn-qulawayh";
    private static final String HREF = "/narrator/" + PERSON + "/" + SLUG;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void seed() throws Exception {
        if (client.indices().exists(ExistsRequest.of(e -> e.index(INDEX))).value()) {
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(INDEX)));
        }
        String mapping = Files.readString(PRODUCTION_MAPPING, StandardCharsets.UTF_8);
        client.indices().create(CreateIndexRequest.of(b -> b
                .index(INDEX)
                .withJson(new StringReader(mapping))));

        // Two of them, because a chapter holding one narration is not a page of its own:
        // BookPageController canonicalises it to that narration and the chapter URL folds
        // away, so a single-narration fixture would test the hadith page twice.
        index("Al-Kafi:1", "1");
        index("Al-Kafi:2", "2");
    }

    @Test
    @DisplayName("a chapter page links the names in its chains")
    void theChapterPagePutsLinksInTheChain() {
        String path = chapterUrl();
        String html = restTemplate.getForObject(path, String.class);

        assertNotNull(html, path + " did not render");
        assertTrue(html.contains("class=\"chain-name\""),
                path + " rendered no chain links at all. The spans are fetched by "
                        + "BookPageController's CARD_FIELDS; if chain_links is missing from "
                        + "that list, ChainLinks gets null and hands back plain text.");
        assertTrue(html.contains("href=\"" + HREF + "\""),
                path + " has chain links but not the one the fixture placed (" + HREF + ")");
        // Both narrations, not just whichever the page happens to render first.
        assertTrue(count(html, HREF) >= 2,
                "only " + count(html, HREF) + " of the chapter's 2 narrations carry the link");
    }

    @Test
    @DisplayName("the chapter page and the narration's own page agree")
    void bothSurfacesLinkTheSameName() {
        // The bug was one surface being wrong while the other was right, and the two are
        // fed by different queries. Asserting the chapter page alone would not have told
        // anyone which of the two had drifted.
        String hadith = restTemplate.getForObject("/hadith/Al-Kafi:1", String.class);
        assertNotNull(hadith, "/hadith/Al-Kafi:1 did not render");
        assertTrue(hadith.contains("href=\"" + HREF + "\""),
                "the narration's own page lost the link, so the fixture is wrong rather "
                        + "than the chapter page being right");
    }

    @Test
    @DisplayName("the English chain is linked too")
    void theEnglishChainIsLinkedAsWell() {
        // chain_links and chain_links_en are separate fields and were forgotten together.
        // Only the Arabic one is visible on an English page's Arabic half, so a list that
        // regained one and not the other would look fixed.
        String html = restTemplate.getForObject(chapterUrl(), String.class);
        assertNotNull(html);
        assertTrue(html.contains(">" + NAME_EN + "</a>"),
                "the English chain carries no link on " + NAME_EN);
    }

    /** The chapter's real URL, from the catalogue rather than a guessed slug. */
    private String chapterUrl() {
        return new BookCatalog().books().stream()
                .filter(b -> b.name().equals(BOOK))
                .flatMap(b -> b.chapters().stream())
                .filter(c -> c.title().equals(CHAPTER))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the catalogue has no chapter \"" + CHAPTER + "\" in " + BOOK
                                + "; the fixture did not reach Elasticsearch"))
                .url();
    }

    private void index(String id, String number) throws Exception {
        Map<String, Object> doc = new HashMap<>();
        doc.put("book", BOOK);
        doc.put("volume", "2");
        doc.put("number", number);
        doc.put("chapter", CHAPTER);
        doc.put("part", "The Book of Faith and Disbelief");
        doc.put("english", ENGLISH);
        doc.put("arabic", ARABIC);
        doc.put("chain_links", List.of(span(chainOf("arabicChain"), NAME_AR)));
        doc.put("chain_links_en", List.of(span(chainOf("englishChain"), NAME_EN)));
        // So that the page carries the panel as well, which is what made the missing links
        // look like a resolver that had found nobody rather than like a missing field.
        doc.put("chain_people", List.of(Map.of(
                "s", 0, "e", NAME_AR.length(), "t", NAME_AR, "p", PERSON, "g", SLUG,
                "o", "heading")));

        String json = JSON.writeValueAsString(doc);
        client.index(IndexRequest.of(b -> b
                .index(INDEX).id(id)
                .withJson(new StringReader(json))
                .refresh(Refresh.True)));
    }

    /** What the segmenter calls the chain half of the fixture, in one language. */
    private static String chainOf(String field) {
        Map<String, Object> probe = new HashMap<>();
        probe.put("arabic", ARABIC);
        probe.put("english", ENGLISH);
        HadithDisplaySegmenter.enrich(probe);
        Object chain = probe.get(field);
        assertNotNull(chain, "the segmenter produced no " + field + " for the fixture");
        return String.valueOf(chain);
    }

    private static Map<String, Object> span(String chain, String name) {
        int at = chain.indexOf(name);
        assertTrue(at >= 0, "the segmenter did not put \"" + name + "\" in the chain it cut "
                + "(«" + chain + "»), so there is nothing to hang a link on");
        return Map.of("s", at, "e", at + name.length(), "t", name, "p", PERSON, "g", SLUG);
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }
}

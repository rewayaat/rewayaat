package com.rewayaat.integration;

import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.DeleteIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.rewayaat.service.ArabicNames;
import com.rewayaat.service.BookCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A chapter's Arabic title is the one its own narrations carry.
 *
 * <p>The catalogue used to look every title up in one English-keyed map shared by all
 * books. Ghurar al-Ḥikam has a chapter "Greed" whose Arabic is «الحِرص», and the map's
 * "Greed" is Al-Kāfi's «باب الطمع»: the restored book would have shown another book's
 * heading on the Arabic site. The map stays as the fallback for chapters whose narrations
 * carry no Arabic title.
 */
class CatalogArabicTitlesIntegrationTest extends ElasticsearchTestSupport {

    private static final Path PRODUCTION_MAPPING = Path.of("scripts/search/v2_mapping.json");

    @BeforeEach
    void seed() throws Exception {
        if (client.indices().exists(ExistsRequest.of(e -> e.index(INDEX))).value()) {
            client.indices().delete(DeleteIndexRequest.of(b -> b.index(INDEX)));
        }
        String mapping = Files.readString(PRODUCTION_MAPPING, StandardCharsets.UTF_8);
        client.indices().create(CreateIndexRequest.of(b -> b
                .index(INDEX)
                .withJson(new StringReader(mapping))));
        index("Ghurar-al-Hikam-Amidi:1", """
                {"book":"Ghurar al-Ḥikam","volume":"1","number":"1","chapter":"Greed",
                 "chapter_ar":"الحِرص","english":"e","arabic":"ع"}""");
        index("Al-Kafi:1", """
                {"book":"Al-Kāfi","volume":"1","number":"1","chapter":"Greed",
                 "part":"The Book of Faith and Disbelief","part_ar":"كتاب الإيمان والكفر",
                 "english":"e","arabic":"ع"}""");
    }

    @Test
    void aChapterTakesTheArabicTitleOfItsOwnNarrations() {
        assertEquals("الحِرص", chapter("Ghurar al-Ḥikam", "Greed").titleAr());
    }

    @Test
    void aChapterWithoutOneFallsBackToTheMapping() {
        assertEquals(ArabicNames.chapter("Greed"), chapter("Al-Kāfi", "Greed").titleAr());
    }

    @Test
    void aPartTakesTheArabicTitleOfItsOwnNarrations() {
        BookCatalog.Book kafi = book("Al-Kāfi");
        List<BookCatalog.Part> parts = kafi.parts();
        assertEquals(1, parts.size());
        assertEquals("كتاب الإيمان والكفر", parts.get(0).titleAr());
    }

    private BookCatalog.Chapter chapter(String bookName, String title) {
        return book(bookName).chapters().stream()
                .filter(c -> c.title().equals(title))
                .findFirst().orElseThrow();
    }

    private static BookCatalog.Book book(String name) {
        return new BookCatalog().books().stream()
                .filter(b -> b.name().equals(name))
                .findFirst().orElseThrow();
    }

    private void index(String id, String json) throws Exception {
        client.index(IndexRequest.of(b -> b
                .index(INDEX).id(id)
                .withJson(new StringReader(json))
                .refresh(Refresh.True)));
    }
}
